package com.ai.assistance.operit.integrations.tasker

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowReceiverManifestTest {
    private val androidNamespace = "http://schemas.android.com/apk/res/android"

    @Test
    fun workflowReceiver_isExportedOnlyForAuthenticatedWorkflowAction() {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("Missing source manifest: ${manifest.absolutePath}", manifest.isFile)
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)
        val receivers = document.getElementsByTagName("receiver")
        val receiver = (0 until receivers.length)
            .map { receivers.item(it) }
            .single {
                it.attributes.getNamedItemNS(androidNamespace, "name")?.nodeValue ==
                    ".integrations.tasker.WorkflowTaskerReceiver"
            }

        assertEquals("true", receiver.attributes.getNamedItemNS(androidNamespace, "exported").nodeValue)
        val actions = (receiver.childNodes.let { nodes -> 0 until nodes.length }.map { receiver.childNodes.item(it) })
            .flatMap { child ->
                val descendants = (child as? org.w3c.dom.Element)?.getElementsByTagName("action")
                    ?: return@flatMap emptyList()
                (0 until descendants.length).map { descendants.item(it) }
            }
            .mapNotNull { it.attributes?.getNamedItemNS(androidNamespace, "name")?.nodeValue }

        assertEquals(listOf(WorkflowTaskerReceiver.ACTION_TRIGGER_WORKFLOW), actions)
        assertFalse(actions.contains("com.twofortyfouram.locale.intent.action.FIRE_SETTING"))
    }

    @Test
    fun mergedManifest_exposesTaskerLibraryActionPathCoveredByRunnerAuthentication() {
        val manifest = File(
            "build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml"
        )
        assertTrue("Missing merged manifest: ${manifest.absolutePath}", manifest.isFile)
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)

        val taskerReceiver = (0 until document.getElementsByTagName("receiver").length)
            .map { document.getElementsByTagName("receiver").item(it) }
            .single {
                it.attributes.getNamedItemNS(androidNamespace, "name")?.nodeValue ==
                    "com.joaomgcd.taskerpluginlibrary.action.BroadcastReceiverAction"
            }
        assertEquals(
            "true",
            taskerReceiver.attributes.getNamedItemNS(androidNamespace, "exported").nodeValue
        )
        assertTrue(
            (0 until taskerReceiver.childNodes.length)
                .map { taskerReceiver.childNodes.item(it) }
                .filterIsInstance<org.w3c.dom.Element>()
                .flatMap { element ->
                    val actions = element.getElementsByTagName("action")
                    (0 until actions.length).map { actions.item(it) }
                }
                .any {
                    it.attributes?.getNamedItemNS(androidNamespace, "name")?.nodeValue ==
                        "com.twofortyfouram.locale.intent.action.FIRE_SETTING"
                }
        )

        val taskerService = (0 until document.getElementsByTagName("service").length)
            .map { document.getElementsByTagName("service").item(it) }
            .single {
                it.attributes.getNamedItemNS(androidNamespace, "name")?.nodeValue ==
                    "com.joaomgcd.taskerpluginlibrary.action.IntentServiceAction"
            }
        assertEquals(
            "true",
            taskerService.attributes.getNamedItemNS(androidNamespace, "exported").nodeValue
        )
    }
}
