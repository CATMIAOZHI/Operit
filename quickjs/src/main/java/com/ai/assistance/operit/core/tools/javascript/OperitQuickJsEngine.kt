package com.ai.assistance.operit.core.tools.javascript

import android.webkit.JavascriptInterface
import java.io.Closeable
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

class OperitQuickJsEngine : Closeable {

    private val runtimeRef = AtomicReference<QuickJsNativeRuntime?>()
    private val nativeInterfaceRef = AtomicReference<Any?>()
    private val methodCache = ConcurrentHashMap<String, Method>()
    private val closed = AtomicBoolean(false)
    private val runtimeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "OperitQuickJsRuntime").apply { isDaemon = true }
    }
    private val hostDispatcher = QuickJsNativeHostDispatcher(
        dispatchTimer = ::dispatchTimerOnRuntimeThread,
        forwardCall = ::dispatchNativeCall
    )
    private val runtime = runOnRuntimeThread {
        QuickJsNativeRuntime.create(hostDispatcher).also { quickJs ->
            runtimeRef.set(quickJs)
            quickJs.installCompatLayerOrThrow()
        }
    }

    fun bindNativeInterface(instance: Any) {
        check(!closed.get()) { "QuickJS engine already closed" }
        nativeInterfaceRef.set(instance)
        methodCache.clear()
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> evaluate(script: String, fileName: String = "<eval>"): T? {
        check(!closed.get()) { "QuickJS engine already closed" }
        return runOnRuntimeThread {
            val result = runtime.eval(script, fileName)
            runtime.executePendingJobs()
            if (!result.success) {
                error(result.describeFailure("QuickJS evaluation failed"))
            }
            decodeJsonValue(result.valueJson) as T?
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> callFunction(
        functionName: String,
        argsJson: String,
        callSite: String = "<call:$functionName>"
    ): T? {
        check(!closed.get()) { "QuickJS engine already closed" }
        return runOnRuntimeThread {
            val result = runtime.callFunction(functionName, argsJson, callSite)
            runtime.executePendingJobs()
            if (!result.success) {
                error(result.describeFailure("QuickJS function call failed"))
            }
            decodeJsonValue(result.valueJson) as T?
        }
    }

    fun interrupt() {
        runtimeRef.get()?.interrupt()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        // Runtime cleanup is dispatched to the runtime executor. Interrupt first so close does
        // not queue behind JavaScript that never yields.
        interrupt()
        runCatching { runOnRuntimeThread { runtime.clearAllTimers() } }
        hostDispatcher.close()
        runtime.close()
        runtimeExecutor.shutdownNow()
        runtimeRef.set(null)
        nativeInterfaceRef.set(null)
        methodCache.clear()
    }

    private fun dispatchTimerOnRuntimeThread(timerId: Int) {
        if (closed.get()) {
            return
        }
        try {
            runtimeExecutor.execute {
                if (closed.get()) {
                    return@execute
                }
                runCatching {
                    val result = runtime.dispatchTimer(timerId)
                    runtime.executePendingJobs()
                    if (!result.success) {
                        error(result.describeFailure("QuickJS timer callback failed"))
                    }
                }.getOrElse { error ->
                    System.err.println("QuickJS timer dispatch failed: ${error.message}")
                    error.printStackTrace()
                }
            }
        } catch (error: RejectedExecutionException) {
            if (!closed.get()) {
                throw error
            }
        }
    }

    private fun <T> runOnRuntimeThread(block: () -> T): T {
        val future = runtimeExecutor.submit<T> { block() }
        try {
            return future.get()
        } catch (error: ExecutionException) {
            throw (error.cause ?: error)
        }
    }

    private fun dispatchNativeCall(methodName: String, argsJson: String?): String? {
        val target = nativeInterfaceRef.get() ?: error("NativeInterface is not bound")
        val args =
            if (isNativeMethodStrictlyBudgeted(methodName)) {
                decodeNativeInterfaceArgs(argsJson)
            } else {
                decodeLegacyNativeInterfaceArgs(argsJson)
            }
        val method = resolveMethod(target, methodName, args.size)
        val convertedArgs = method.parameterTypes.mapIndexed { index, type ->
            convertArg(args[index], type)
        }.toTypedArray()
        return method.invoke(target, *convertedArgs)?.toString()
    }

    private fun resolveMethod(target: Any, methodName: String, argCount: Int): Method {
        val cacheKey = "${target.javaClass.name}#$methodName/$argCount"
        return methodCache.getOrPut(cacheKey) {
            resolveJavascriptInterfaceMethod(target.javaClass, methodName, argCount)
                ?: error("NativeInterface method not found: $methodName/$argCount")
        }
    }

    private fun decodeJsonValue(valueJson: String?): Any? {
        if (valueJson.isNullOrBlank()) {
            return null
        }
        return normalizeJsonValue(JSONTokener(valueJson).nextValue())
    }

    private fun decodeLegacyNativeInterfaceArgs(argsJson: String?): List<Any?> {
        if (argsJson.isNullOrBlank()) {
            return emptyList()
        }
        val parsed = JSONTokener(argsJson).nextValue()
        if (parsed !is JSONArray) {
            return emptyList()
        }
        return List(parsed.length()) { index -> normalizeJsonValue(parsed.opt(index)) }
    }

    private fun normalizeJsonValue(value: Any?): Any? {
        return when (value) {
            JSONObject.NULL -> null
            is JSONArray -> List(value.length()) { index -> normalizeJsonValue(value.opt(index)) }
            is JSONObject -> value.toString()
            else -> value
        }
    }

    private fun convertArg(value: Any?, parameterType: Class<*>): Any? {
        return when (parameterType) {
            java.lang.String::class.java -> value?.toString() ?: ""
            java.lang.Integer.TYPE,
            java.lang.Integer::class.java -> (value as? Number)?.toInt()
                ?: value?.toString()?.toIntOrNull()
                ?: 0
            java.lang.Long.TYPE,
            java.lang.Long::class.java -> (value as? Number)?.toLong()
                ?: value?.toString()?.toLongOrNull()
                ?: 0L
            java.lang.Boolean.TYPE,
            java.lang.Boolean::class.java -> when (value) {
                is Boolean -> value
                is Number -> value.toInt() != 0
                else -> value?.toString()?.toBooleanStrictOrNull() ?: false
            }
            java.lang.Double.TYPE,
            java.lang.Double::class.java -> (value as? Number)?.toDouble()
                ?: value?.toString()?.toDoubleOrNull()
                ?: 0.0
            java.lang.Float.TYPE,
            java.lang.Float::class.java -> (value as? Number)?.toFloat()
                ?: value?.toString()?.toFloatOrNull()
                ?: 0f
            else -> value?.toString()
        }
    }
}

private const val MAX_NATIVE_INTERFACE_ARGS_JSON_CHARS = 2_113_536
private const val MAX_NATIVE_INTERFACE_ARGS_DEPTH = 64
private const val MAX_NATIVE_INTERFACE_ARGS_ELEMENTS = 65_536
private val STRICTLY_BUDGETED_NATIVE_METHODS =
    setOf(
        "javaClassExists",
        "javaLoadDex",
        "javaLoadJar",
        "javaListLoadedCodePaths",
        "javaGetApplicationContext",
        "javaGetCurrentActivity",
        "javaNewInstance",
        "javaCallStatic",
        "javaCallInstance",
        "javaCallStaticSuspend",
        "javaCallInstanceSuspend",
        "javaGetStaticField",
        "javaSetStaticField",
        "javaGetInstanceField",
        "javaHasInstanceMethod",
        "javaSetInstanceField",
        "javaPollPendingJsCallback",
        "javaResolvePendingJsCallback",
        "javaSleepMillis",
        "__javaReleaseInstanceInternal",
        "listSandboxPackageDevPackageAssets",
        "readSandboxPackageDevPackageAssetBase64"
    )

internal fun isNativeMethodStrictlyBudgeted(methodName: String): Boolean =
    methodName in STRICTLY_BUDGETED_NATIVE_METHODS

internal fun decodeNativeInterfaceArgs(argsJson: String?): List<Any?> {
    if (argsJson == null) {
        return emptyList()
    }
    validateNativeInterfaceArgsJsonText(argsJson)
    if (argsJson.isBlank()) {
        return emptyList()
    }

    val parsed = JSONTokener(argsJson).nextValue()
    if (parsed !is JSONArray) {
        return emptyList()
    }

    var visitedElements = 0
    fun consumeElement() {
        visitedElements += 1
        require(visitedElements <= MAX_NATIVE_INTERFACE_ARGS_ELEMENTS) {
            "NativeInterface arguments exceed the element limit " +
                "($MAX_NATIVE_INTERFACE_ARGS_ELEMENTS)"
        }
    }
    fun normalize(value: Any?, depth: Int): Any? {
        require(depth <= MAX_NATIVE_INTERFACE_ARGS_DEPTH) {
            "NativeInterface arguments exceed the nesting depth limit " +
                "($MAX_NATIVE_INTERFACE_ARGS_DEPTH)"
        }
        return when (value) {
            JSONObject.NULL -> null
            is JSONArray ->
                List(value.length()) { index ->
                    consumeElement()
                    normalize(value.opt(index), depth + 1)
                }
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    consumeElement()
                    normalize(value.opt(keys.next()), depth + 1)
                }
                value.toString()
            }
            else -> value
        }
    }

    return List(parsed.length()) { index ->
        consumeElement()
        normalize(parsed.opt(index), 1)
    }
}

internal fun validateNativeInterfaceArgsJsonText(argsJson: String) {
    require(argsJson.length <= MAX_NATIVE_INTERFACE_ARGS_JSON_CHARS) {
        "NativeInterface arguments exceed the JSON text limit " +
            "($MAX_NATIVE_INTERFACE_ARGS_JSON_CHARS characters)"
    }

    var depth = 0
    var quoteCharacter: Char? = null
    var escaped = false
    var scannedElements = 0
    val containerTypes = mutableListOf<Char>()
    val arrayExpectingValue = mutableListOf<Boolean>()

    fun consumeElement() {
        scannedElements += 1
        require(scannedElements <= MAX_NATIVE_INTERFACE_ARGS_ELEMENTS) {
            "NativeInterface arguments exceed the element limit " +
                "($MAX_NATIVE_INTERFACE_ARGS_ELEMENTS)"
        }
    }

    argsJson.forEachIndexed { index, character ->
        if (quoteCharacter != null) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == quoteCharacter -> quoteCharacter = null
            }
        } else {
            require(
                character != '\'' &&
                    character != ';' &&
                    character != '=' &&
                    character != '#' &&
                    !(
                        character == '/' &&
                            (argsJson.getOrNull(index + 1) == '/' || argsJson.getOrNull(index + 1) == '*')
                    )
            ) {
                "NativeInterface arguments use a non-canonical JSON extension"
            }
            val currentIndex = containerTypes.lastIndex
            if (
                currentIndex >= 0 &&
                    containerTypes[currentIndex] == '[' &&
                    arrayExpectingValue[currentIndex] &&
                    !character.isWhitespace() &&
                    character != ']' &&
                    character != ','
            ) {
                consumeElement()
                arrayExpectingValue[currentIndex] = false
            }
            when (character) {
                '"' -> quoteCharacter = character
                '[', '{' -> {
                    depth += 1
                    require(depth <= MAX_NATIVE_INTERFACE_ARGS_DEPTH) {
                        "NativeInterface arguments exceed the nesting depth limit " +
                            "($MAX_NATIVE_INTERFACE_ARGS_DEPTH)"
                    }
                    containerTypes.add(character)
                    arrayExpectingValue.add(character == '[')
                }
                ']', '}' -> if (depth > 0) {
                    depth -= 1
                    containerTypes.removeAt(containerTypes.lastIndex)
                    arrayExpectingValue.removeAt(arrayExpectingValue.lastIndex)
                }
                ',' -> {
                    val index = containerTypes.lastIndex
                    if (index >= 0 && containerTypes[index] == '[') {
                        if (arrayExpectingValue[index]) {
                            consumeElement()
                        }
                        arrayExpectingValue[index] = true
                    }
                }
                ':' -> {
                    val index = containerTypes.lastIndex
                    if (index >= 0 && containerTypes[index] == '{') {
                        consumeElement()
                    }
                }
            }
        }
    }
}

internal fun resolveJavascriptInterfaceMethod(
    targetClass: Class<*>,
    methodName: String,
    argCount: Int
): Method? {
    return targetClass.methods.firstOrNull { method ->
        method.name == methodName &&
            method.parameterTypes.size == argCount &&
            method.isAnnotationPresent(JavascriptInterface::class.java)
    }
}
