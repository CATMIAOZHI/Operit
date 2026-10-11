package com.ai.assistance.operit.ui.common.dynamicui

import androidx.a2ui.compose.ui.A2uiMessageProcessor
import androidx.a2ui.engine.model.A2uiCoreSurfaceModel
import androidx.a2ui.model.processor.processInput
import androidx.a2ui.model.processor.A2uiJsonMessageParser
import androidx.a2ui.model.processor.A2uiJsonReader
import androidx.a2ui.model.processor.A2uiJsonToken
import androidx.a2ui.model.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Exercises the actual published AndroidX parser and processor, not a mock renderer. */
class DynamicUiOfficialEngineTest {
    @Test fun queuedKeypadActionsReadLatestStateWhenConsumed() = runBlocking {
        val batch = DynamicUiProtocol.parse(CardExpress.compile(javaClass.getResource("/dynamicui/calculator.express")!!.readText()), true)
        val processor = A2uiMessageProcessor(listOf(operitDynamicUiCatalog))
        val channel = Channel<A2uiClientEventMessage>(Channel.UNLIMITED)
        val errors = java.util.concurrent.CopyOnWriteArrayList<A2uiClientErrorMessage>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            processor.outboundEvents.collect {
                if (it is A2uiClientEventMessage) channel.send(it)
                if (it is A2uiClientErrorMessage) errors.add(it)
            }
        }
        val job = launch(Dispatchers.Default) { processor.collectMessages() }
        try {
            val parser = A2uiJsonMessageParser(::JvmJsonReader)
            batch.lines.forEach { processor.processInput(parser, it) }
            processor.processMessage(A2uiUpdateDataModelMessage("card", "/ready", true))
            val surface = withTimeout(5000) { processor.activeSurfaces.first { it.isNotEmpty() }.first() } as A2uiCoreSurfaceModel
            withTimeout(5000) { while (surface.dataModel[A2uiDataPath("/ready")] != true) delay(10) }
            val graph = batch.componentsBySurface.getValue("card")
            val buttons = graph.values.filter { it["component"]?.jsonPrimitive?.content == "Button" }
            for (key in listOf("1", "2", "*", "3", "4", "=")) {
                val component = buttons.first {
                    it["action"]!!.jsonObject["event"]!!.jsonObject["context"]!!.jsonObject["value"]!!.jsonObject["args"]!!.jsonObject["key"]!!.jsonPrimitive.content == key
                }
                @Suppress("UNCHECKED_CAST")
                surface.dispatchAction(component["id"]!!.jsonPrimitive.content, DynamicUiProtocol.toValue(component["action"]!!) as Map<String, Any?>)
            }
            // Delay host writes until all six official actor snapshots have been produced.
            val queued = withTimeout(5000) { List(6) { channel.receive() } }
            assertEquals("0", surface.dataModel[A2uiDataPath("/calculator/display")])
            queued.forEach { event ->
                val context = resolveLocalCardContext(surface, event.componentId, graph[event.componentId], event.context)
                surface.dataModel.update(A2uiDataPath(context["path"] as String), context["value"])
            }
            assertEquals("408", surface.dataModel[A2uiDataPath("/calculator/display")])
            assertTrue(errors.toString(), errors.isEmpty())
            assertTrue(queued.all { it.type == "local_action" })
        } finally {
            job.cancelAndJoin()
            collector.cancelAndJoin()
            channel.close()
        }
    }

    @Test fun fourChartExamplesLoadInOfficialEngine() = runBlocking {
        val batch = DynamicUiProtocol.parse(CardExpress.compile(javaClass.getResource("/dynamicui/charts.express")!!.readText()), true)
        val processor = A2uiMessageProcessor(listOf(operitDynamicUiCatalog))
        val events = java.util.concurrent.CopyOnWriteArrayList<A2uiClientToServerMessage>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { processor.outboundEvents.collect { events.add(it) } }
        val job = launch(Dispatchers.Default) { processor.collectMessages() }
        try {
            val parser = A2uiJsonMessageParser(::JvmJsonReader)
            batch.lines.forEach { processor.processInput(parser, it) }
            batch.surfaceIds.forEach { processor.processMessage(A2uiUpdateDataModelMessage(it, "/ready", true)) }
            val surfaces = withTimeout(5000) { processor.activeSurfaces.first { it.size == 1 } }
            withTimeout(5000) { while (surfaces.any { (it as A2uiCoreSurfaceModel).dataModel[A2uiDataPath("/ready")] != true }) delay(10) }
            assertTrue(events.toString(), events.none { it is A2uiClientErrorMessage || it is A2uiClientEventMessage })
        } finally {
            job.cancelAndJoin()
            collector.cancelAndJoin()
        }
    }

    @Test fun shippedCalculatorRecalculatesThroughOfficialEvaluatorWithoutActions() = runBlocking {
        val source = CardExpress.compile(javaClass.getResource("/dynamicui/cost_calculator.express")!!.readText())
        val batch = DynamicUiProtocol.parse(source, true)
        val processor = A2uiMessageProcessor(listOf(operitDynamicUiCatalog))
        val events = java.util.concurrent.CopyOnWriteArrayList<A2uiClientToServerMessage>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { processor.outboundEvents.collect { events.add(it) } }
        val job = launch(Dispatchers.Default) { processor.collectMessages() }
        try {
            val parser = A2uiJsonMessageParser(::JvmJsonReader)
            batch.lines.forEach { processor.processInput(parser, it) }
            processor.processMessage(A2uiUpdateDataModelMessage("card", "/ready", true))
            val surface = withTimeout(5000) { processor.activeSurfaces.first { it.isNotEmpty() }.first() } as A2uiCoreSurfaceModel
            withTimeout(5000) { while (surface.dataModel[A2uiDataPath("/ready")] != true) delay(10) }
            val display = batch.componentsBySurface.getValue("card").values.single { it["component"]?.jsonPrimitive?.content == "Display" }
            val payload = DynamicUiProtocol.toValue(display.getValue("text"))
            fun total() = surface.evaluatePayload("total", { surface.dataModel[it] }, A2uiDataPath("/"), payload).toString()
            val originalLocale = java.util.Locale.getDefault()
            try {
                java.util.Locale.setDefault(java.util.Locale.US)
                assertEquals("46.49", total())
                surface.dataModel.update(A2uiDataPath("/form/discount"), true)
                assertEquals("41.84", total())
                surface.dataModel.update(A2uiDataPath("/form/years"), 10)
                assertEquals("104.60", total())
            } finally { java.util.Locale.setDefault(originalLocale) }
            assertTrue(events.toString(), events.none { it is A2uiClientErrorMessage || it is A2uiClientEventMessage })
        } finally {
            job.cancelAndJoin()
            collector.cancelAndJoin()
        }
    }

    @Test fun shippedDemoReplaysAndRestoresWithoutEmittingActions() = runBlocking {
        val source = CardExpress.compile(javaClass.getResource("/dynamicui/form.express")!!.readText())
        val batch = DynamicUiProtocol.parse(source, true)
        val processor = A2uiMessageProcessor(listOf(operitDynamicUiCatalog))
        val events = java.util.concurrent.CopyOnWriteArrayList<A2uiClientToServerMessage>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { processor.outboundEvents.collect { events.add(it) } }
        val job = launch(Dispatchers.Default) { processor.collectMessages() }
        try {
            // Same official protocol parser; Gson supplies real JVM tokens instead of Android stubs.
            val parser = A2uiJsonMessageParser(::JvmJsonReader)
            batch.lines.forEach { processor.processInput(parser, it) }
            processor.processMessage(A2uiUpdateDataModelMessage("card", "/restored", "saved"))
            val surface = withTimeout(5000) { processor.activeSurfaces.first { it.isNotEmpty() }.first() } as A2uiCoreSurfaceModel
            withTimeout(5000) { while (surface.dataModel[A2uiDataPath("/restored")] != "saved") delay(10) }
            assertEquals("", surface.dataModel[A2uiDataPath("/form/name")])
            assertTrue(events.toString(), events.none { it is A2uiClientErrorMessage || it is A2uiClientEventMessage })
            surface.dataModel.update(A2uiDataPath("/form/notes"), "保留中文输入")
            val saved = DynamicUiProtocol.toJson(surface.dataModel[A2uiDataPath("/")])
            processor.processMessage(A2uiUpdateDataModelMessage("card", "/", DynamicUiProtocol.toValue(saved)))
            processor.processMessage(A2uiUpdateDataModelMessage("card", "/afterRestore", true))
            withTimeout(5000) { while (surface.dataModel[A2uiDataPath("/afterRestore")] != true) delay(10) }
            assertEquals("保留中文输入", surface.dataModel[A2uiDataPath("/form/notes")])
            assertTrue(events.none { it is A2uiClientEventMessage })
        } finally {
            job.cancelAndJoin()
            collector.cancelAndJoin()
        }
    }
}

private class JvmJsonReader(text: String) : A2uiJsonReader {
    private val reader = com.google.gson.stream.JsonReader(java.io.StringReader(text))
    override fun peek() = A2uiJsonToken.valueOf(reader.peek().name)
    override fun beginObject() = reader.beginObject()
    override fun endObject() = reader.endObject()
    override fun beginArray() = reader.beginArray()
    override fun endArray() = reader.endArray()
    override fun hasNext() = reader.hasNext()
    override fun nextName() = reader.nextName()
    override fun nextString() = reader.nextString()
    override fun nextBoolean() = reader.nextBoolean()
    override fun nextDouble() = reader.nextDouble()
    override fun nextInt() = reader.nextInt()
    override fun nextLong() = reader.nextLong()
    override fun nextNull() = reader.nextNull()
    override fun skipValue() = reader.skipValue()
    override fun close() = reader.close()
}
