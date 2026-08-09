package com.ai.assistance.operit.core.tools.javascript

import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.ArrayList
import java.util.Collections
import java.util.HashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class JsJavaBridgeSecurityTest {
    @Test
    fun bootstrapRetainsTheRestrictedJavaAndKotlinGlobals() {
        val module = buildRestrictedJavaBridgeBootstrapModule()

        assertEquals("quickjs/init/java-bridge.js", module.fileName)
        assertEquals(listOf("Java", "Kotlin"), module.globals)
        assertTrue(module.source.contains("javaCallStatic"))
        assertTrue(module.source.contains("javaCallStaticSuspend"))
        assertTrue(module.source.contains("Java bridge interface proxy is not allowed"))
        assertFalse(module.source.contains("ensureJsInterfaceMarkerRegistered"))
    }

    @Test
    fun classLookupAllowsTheSafeSubsetAndRejectsPrivilegeExpansion() {
        assertTrue(JsJavaBridgeDelegates.classExists("java.lang.StringBuilder"))
        assertTrue(JsJavaBridgeDelegates.classExists("java.util.ArrayList"))

        assertFalse(JsJavaBridgeDelegates.classExists("java.lang.Runtime"))
        assertFalse(JsJavaBridgeDelegates.classExists("java.lang.ProcessBuilder"))
        assertFalse(JsJavaBridgeDelegates.classExists("android.content.Context"))
        assertFalse(JsJavaBridgeDelegates.classExists("java.io.File"))
        assertFalse(JsJavaBridgeDelegates.classExists("java.util.Comparator"))
        assertFalse(JsJavaBridgeDelegates.classExists("java.lang.CharSequence"))
        assertFalse(JsJavaBridgeDelegates.classExists("java.util.Locale"))
        assertFalse(JsJavaBridgeDelegates.classExists("java.math.BigInteger"))
        assertFalse(JsJavaBridgeDelegates.classExists("java.math.BigDecimal"))
    }

    @Test
    fun memberContractRejectsAmplifyingCallsBeforeReflection() {
        val emptyBuilderConstructor = StringBuilder::class.java.getConstructor()
        val capacityBuilderConstructor =
            StringBuilder::class.java.getConstructor(Int::class.javaPrimitiveType)
        val charSequenceBuilderConstructor =
            StringBuilder::class.java.getConstructor(CharSequence::class.java)
        val emptyListConstructor = ArrayList::class.java.getConstructor()
        val capacityListConstructor =
            ArrayList::class.java.getConstructor(Int::class.javaPrimitiveType)
        val append = StringBuilder::class.java.getMethod("append", String::class.java)
        val appendCharSequence =
            StringBuilder::class.java.getMethod("append", CharSequence::class.java)
        val ensureCapacity =
            StringBuilder::class.java.getMethod(
                "ensureCapacity",
                Int::class.javaPrimitiveType
            )
        val repeat = String::class.java.getMethod("repeat", Int::class.javaPrimitiveType)
        val parseInt =
            Integer::class.java.getMethod("parseInt", String::class.java)
        val getInteger =
            Integer::class.java.getMethod("getInteger", String::class.java)
        val waitMethod = Integer::class.java.getMethod("wait")

        assertTrue(JsJavaBridgeDelegates.isJavaBridgeConstructorAllowed(emptyBuilderConstructor))
        assertTrue(JsJavaBridgeDelegates.isJavaBridgeConstructorAllowed(emptyListConstructor))
        assertFalse(JsJavaBridgeDelegates.isJavaBridgeConstructorAllowed(capacityBuilderConstructor))
        assertFalse(JsJavaBridgeDelegates.isJavaBridgeConstructorAllowed(charSequenceBuilderConstructor))
        assertFalse(JsJavaBridgeDelegates.isJavaBridgeConstructorAllowed(capacityListConstructor))
        assertTrue(JsJavaBridgeDelegates.isJavaBridgeMethodAllowed(StringBuilder::class.java, append))
        assertFalse(
            JsJavaBridgeDelegates.isJavaBridgeMethodAllowed(
                StringBuilder::class.java,
                appendCharSequence
            )
        )
        assertTrue(JsJavaBridgeDelegates.isJavaBridgeMethodAllowed(Integer::class.java, parseInt))
        assertFalse(JsJavaBridgeDelegates.isJavaBridgeMethodAllowed(Integer::class.java, getInteger))
        assertFalse(JsJavaBridgeDelegates.isJavaBridgeMethodAllowed(Integer::class.java, waitMethod))
        assertFalse(
            JsJavaBridgeDelegates.isJavaBridgeMethodAllowed(
                StringBuilder::class.java,
                ensureCapacity
            )
        )
        assertFalse(JsJavaBridgeDelegates.isJavaBridgeMethodAllowed(String::class.java, repeat))

        val constructorError =
            assertThrows(IllegalArgumentException::class.java) {
                JsJavaBridgeDelegates.validateJavaBridgeConstructorInvocation(
                    capacityBuilderConstructor,
                    arrayOf(Int.MAX_VALUE)
                )
            }
        assertTrue(constructorError.message.orEmpty().contains("constructor"))

        val methodError =
            assertThrows(IllegalArgumentException::class.java) {
                JsJavaBridgeDelegates.validateJavaBridgeMethodInvocation(
                    targetClass = StringBuilder::class.java,
                    instance = StringBuilder(),
                    method = ensureCapacity,
                    args = arrayOf(Int.MAX_VALUE)
                )
            }
        assertTrue(methodError.message.orEmpty().contains("method"))

        val lengthCalls = AtomicInteger()
        val statefulSequence =
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(CharSequence::class.java)
            ) { _, method, _ ->
                when (method.name) {
                    "length" -> {
                        lengthCalls.incrementAndGet()
                        100_000_000
                    }
                    "charAt" -> 'x'
                    "subSequence" -> "x"
                    else -> "x"
                }
            } as CharSequence
        assertThrows(IllegalArgumentException::class.java) {
            JsJavaBridgeDelegates.validateJavaBridgeConstructorInvocation(
                charSequenceBuilderConstructor,
                arrayOf(statefulSequence)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            JsJavaBridgeDelegates.validateJavaBridgeMethodInvocation(
                targetClass = StringBuilder::class.java,
                instance = StringBuilder(),
                method = appendCharSequence,
                args = arrayOf(statefulSequence)
            )
        }
        assertEquals(0, lengthCalls.get())
    }

    @Test
    fun bridgeInputBudgetRunsBeforeJsonParsing() {
        val deepJson = "[".repeat(65) + "0" + "]".repeat(65)
        val oversizedText = "x".repeat(1_048_577)
        val tooManyElements = List(65_537) { "0" }.joinToString(prefix = "[", postfix = "]")
        val tooManyOmittedSlots = "[" + ",".repeat(65_537) + "]"

        val depthError =
            assertThrows(IllegalArgumentException::class.java) {
                JsJavaBridgeDelegates.validateBridgeInputJsonText(deepJson)
            }
        assertTrue(depthError.message.orEmpty().contains("depth limit"))

        val textError =
            assertThrows(IllegalArgumentException::class.java) {
                JsJavaBridgeDelegates.validateBridgeInputJsonText(oversizedText)
            }
        assertTrue(textError.message.orEmpty().contains("JSON text limit"))

        val elementError =
            assertThrows(IllegalArgumentException::class.java) {
                JsJavaBridgeDelegates.validateBridgeInputJsonText(tooManyElements)
            }
        assertTrue(elementError.message.orEmpty().contains("element limit"))

        val omittedSlotError =
            assertThrows(IllegalArgumentException::class.java) {
                JsJavaBridgeDelegates.validateBridgeInputJsonText(tooManyOmittedSlots)
            }
        assertTrue(omittedSlotError.message.orEmpty().contains("element limit"))

        val parseArgs =
            JsJavaBridgeDelegates::class.java.declaredMethods.single {
                it.name == "parseArgsJson"
            }.apply { isAccessible = true }
        val canonicalArgs =
            parseArgs.invoke(
                JsJavaBridgeDelegates,
                "[\"value,[,]\"]",
                ConcurrentHashMap<String, Any>()
            ) as List<*>
        assertEquals(listOf("value,[,]"), canonicalArgs)
        listOf(
            "['value']",
            "[1;2]",
            "[{\"key\"=1}]",
            "[//comment\n1]",
            "[#comment\n1]",
            "[/*comment*/1]"
        ).forEach { lenientJson ->
            val lenientError =
                assertThrows(IllegalArgumentException::class.java) {
                    JsJavaBridgeDelegates.validateBridgeInputJsonText(lenientJson)
                }
            assertTrue(lenientError.message.orEmpty().contains("non-canonical JSON extension"))
        }
        val whitespaceError =
            assertThrows(InvocationTargetException::class.java) {
                parseArgs.invoke(
                    JsJavaBridgeDelegates,
                    " ".repeat(1_048_577) + "[]",
                    ConcurrentHashMap<String, Any>()
                )
            }
        assertTrue(whitespaceError.cause?.message.orEmpty().contains("JSON text limit"))
    }

    @Test
    fun quadraticCollectionMembersAreOutsideTheRestrictedProfile() {
        val indexOfSubList =
            Collections::class.java.getMethod(
                "indexOfSubList",
                List::class.java,
                List::class.java
            )
        val disjoint =
            Collections::class.java.getMethod(
                "disjoint",
                Collection::class.java,
                Collection::class.java
            )
        val removeAll =
            ArrayList::class.java.getMethod("removeAll", Collection::class.java)
        val addAll =
            ArrayList::class.java.getMethod("addAll", Collection::class.java)

        assertFalse(
            JsJavaBridgeDelegates.isJavaBridgeMethodAllowed(Collections::class.java, indexOfSubList)
        )
        assertFalse(
            JsJavaBridgeDelegates.isJavaBridgeMethodAllowed(Collections::class.java, disjoint)
        )
        assertFalse(
            JsJavaBridgeDelegates.isJavaBridgeMethodAllowed(ArrayList::class.java, removeAll)
        )
        assertTrue(JsJavaBridgeDelegates.isJavaBridgeMethodAllowed(ArrayList::class.java, addAll))
    }

    @Test
    fun mutableBridgeObjectsCannotGrowPastTheMemberBudget() {
        val append = StringBuilder::class.java.getMethod("append", String::class.java)
        val fullBuilder = StringBuilder("x".repeat(65_536))

        val builderError =
            assertThrows(IllegalArgumentException::class.java) {
                JsJavaBridgeDelegates.validateJavaBridgeMethodInvocation(
                    targetClass = StringBuilder::class.java,
                    instance = fullBuilder,
                    method = append,
                    args = arrayOf("y")
                )
            }
        assertTrue(builderError.message.orEmpty().contains("character limit"))

        val addMethod = ArrayList::class.java.getMethod("add", Any::class.java)
        val fullList = ArrayList<Any>(65_536).apply { repeat(65_536) { add("x") } }
        val collectionError =
            assertThrows(IllegalArgumentException::class.java) {
                JsJavaBridgeDelegates.validateJavaBridgeMethodInvocation(
                    targetClass = ArrayList::class.java,
                    instance = fullList,
                    method = addMethod,
                    args = arrayOf("y")
                )
            }
        assertTrue(collectionError.message.orEmpty().contains("element limit"))
    }

    @Test
    fun liveJavaObjectHandlesHaveACumulativeRegistryLimit() {
        val objectRegistry = ConcurrentHashMap<String, Any>()
        repeat(1_024) { index -> objectRegistry["reserved-$index"] = Any() }

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                JsJavaBridgeDelegates.registerBridgeObjectHandle(
                    StringBuilder("bounded"),
                    objectRegistry
                )
            }
        assertTrue(error.message.orEmpty().contains("live object handle limit"))

        objectRegistry.remove("reserved-0")
        val handle =
            JsJavaBridgeDelegates.registerBridgeObjectHandle(
                StringBuilder("released-slot"),
                objectRegistry
            )
        assertTrue(objectRegistry[handle] is StringBuilder)
    }

    @Test
    fun productionCloneSerializationRollsBackAndReusesRepeatedIdentity() {
        val repeated = StringBuilder("same")
        val repeatedList = ArrayList<Any>(List(1_024) { repeated })
        val repeatedRegistry = ConcurrentHashMap<String, Any>()
        repeatedRegistry["list-handle"] = repeatedList

        val repeatedResult =
            Mockito.mockStatic(AppLogger::class.java).use {
                JsJavaBridgeDelegates.callInstance(
                    instanceHandle = "list-handle",
                    methodName = "clone",
                    argsJson = "[]",
                    objectRegistry = repeatedRegistry
                )
            }
        assertTrue(repeatedResult, repeatedResult.contains("\"success\":true"))
        assertEquals(2, repeatedRegistry.size)

        val distinctList = ArrayList<Any>(List(1_024) { index -> StringBuilder("value-$index") })
        val failedRegistry = ConcurrentHashMap<String, Any>()
        failedRegistry["list-handle"] = distinctList
        val failedResult =
            Mockito.mockStatic(AppLogger::class.java).use {
                JsJavaBridgeDelegates.callInstance(
                    instanceHandle = "list-handle",
                    methodName = "clone",
                    argsJson = "[]",
                    objectRegistry = failedRegistry
                )
            }
        assertTrue(failedResult.contains("\"success\":false"))
        assertTrue(failedResult.contains("live object handle limit"))
        assertEquals(setOf("list-handle"), failedRegistry.keys)

        val nextResult =
            JsJavaBridgeDelegates.newInstance(
                className = "java.lang.StringBuilder",
                argsJson = "[\"after-rollback\"]",
                objectRegistry = failedRegistry
            )
        assertTrue(nextResult.contains("\"success\":true"))
        assertEquals(2, failedRegistry.size)
    }

    @Test
    fun blockedStaticSuspendCallReportsFailureExactlyOnce() {
        val callbacks = mutableListOf<String>()

        JsJavaBridgeDelegates.callStaticSuspend(
            className = "java.lang.Runtime",
            methodName = "getRuntime",
            argsJson = "[]",
            objectRegistry = ConcurrentHashMap(),
            callback = callbacks::add
        )

        assertEquals(1, callbacks.size)
        assertTrue(callbacks.single().contains("\"success\":false"))
        assertTrue(callbacks.single().contains("is not allowed"))
    }

    @Test
    fun blockedInstanceSuspendCallReportsFailureExactlyOnce() {
        val callbacks = mutableListOf<String>()
        val objectRegistry =
            ConcurrentHashMap<String, Any>().apply {
                put("runtime", Runtime.getRuntime())
            }

        JsJavaBridgeDelegates.callInstanceSuspend(
            instanceHandle = "runtime",
            methodName = "exec",
            argsJson = "[]",
            objectRegistry = objectRegistry,
            callback = callbacks::add
        )

        assertEquals(1, callbacks.size)
        assertTrue(callbacks.single().contains("\"success\":false"))
        assertTrue(callbacks.single().contains("is not allowed"))
    }

    @Test
    fun malformedSuspendArgumentsReportFailureExactlyOnce() {
        val callbacks = mutableListOf<String>()

        JsJavaBridgeDelegates.callStaticSuspend(
            className = "java.lang.Integer",
            methodName = "parseInt",
            argsJson = "not-json",
            objectRegistry = ConcurrentHashMap(),
            callback = callbacks::add
        )

        assertEquals(1, callbacks.size)
        assertTrue(callbacks.single().contains("\"success\":false"))
    }

    @Test
    fun inferredInterfaceProxyCannotBypassTheClassAllowlist() {
        val proxyFactory =
            JsJavaBridgeDelegates::class.java.declaredMethods.single {
                it.name == "createJsInterfaceProxyFromMap"
            }.apply { isAccessible = true }
        val callbackInvoker: JsInterfaceCallbackInvoker = { _, _, _ -> "null" }

        val error =
            assertThrows(InvocationTargetException::class.java) {
                proxyFactory.invoke(
                    JsJavaBridgeDelegates,
                    mapOf("compare" to "unused"),
                    Comparator::class.java,
                    ConcurrentHashMap<String, Any>(),
                    callbackInvoker,
                    null
                )
            }

        assertTrue(error.cause is IllegalArgumentException)
        assertTrue(error.cause?.message.orEmpty().contains("java.util.Comparator"))
        assertTrue(error.cause?.message.orEmpty().contains("is not allowed"))
    }

    @Test
    fun currentProfileRejectsCharSequenceInterfaceProxies() {
        val proxyFactory =
            JsJavaBridgeDelegates::class.java.declaredMethods.single {
                it.name == "createJsInterfaceProxyFromMap"
            }.apply { isAccessible = true }
        val callbackInvoker: JsInterfaceCallbackInvoker = { _, _, _ -> "null" }
        val error =
            assertThrows(InvocationTargetException::class.java) {
                proxyFactory.invoke(
                    JsJavaBridgeDelegates,
                    mapOf("length" to 0),
                    CharSequence::class.java,
                    ConcurrentHashMap<String, Any>(),
                    callbackInvoker,
                    null
                )
            }

        assertTrue(error.cause is IllegalArgumentException)
        assertTrue(error.cause?.message.orEmpty().contains("interface proxy"))
        assertTrue(error.cause?.message.orEmpty().contains("is not allowed"))
    }

    @Test
    fun sandboxPackageInstallerReadsFinalApkAssetsAndWritesThroughTools() {
        val repositoryDirectory = locateRepositoryDirectory()
        val installer = repositoryDirectory.resolve("tools/sandboxpackage_dev_install_or_update.js").readText()
        val engine =
            repositoryDirectory.resolve(
                "app/src/main/java/com/ai/assistance/operit/core/tools/javascript/JsEngine.kt"
            ).readText()

        assertTrue(installer.contains("Tools.Files.download"))
        assertTrue(installer.contains("NativeInterface.listSandboxPackageDevPackageAssets()"))
        assertTrue(installer.contains("NativeInterface.readSandboxPackageDevPackageAssetBase64(fileName)"))
        assertTrue(installer.contains("Tools.Files.writeBinary(destination, readResult.base64, ENVIRONMENT)"))
        assertFalse(installer.contains("app/src/main/assets/packages/${'$'}{fileName}"))
        assertFalse(installer.contains("BUILTIN_PACKAGE_FILES"))
        assertFalse(installer.contains("Java.type("))
        assertFalse(installer.contains("Java.getApplicationContext("))
        assertTrue(engine.contains("fun listSandboxPackageDevPackageAssets(): String"))
        assertTrue(engine.contains("fun readSandboxPackageDevPackageAssetBase64(fileName: String): String"))
        assertTrue(engine.contains("context.assets.open(\"packages/${'$'}normalized\")"))
        assertFalse(engine.contains("fun copySandboxPackageDevPackageAssets(): String"))
        assertFalse(engine.contains("AssetCopyUtils.copyAssetDirRecursive("))
        assertFalse(engine.contains("Environment.getExternalStoragePublicDirectory"))
        assertFalse(installer.contains("deleteRecursively"))
    }

    @Test
    fun bridgeReturnSerializationRejectsCyclesAndOversizedLazyCollections() {
        val cyclic = ArrayList<Any>()
        cyclic.add(cyclic)

        val cycleError =
            assertThrows(IllegalArgumentException::class.java) {
                JsJavaBridgeDelegates.validateBridgeReturnValue(cyclic)
            }
        assertTrue(cycleError.message.orEmpty().contains("cyclic"))

        val keyPartOne = ArrayList<Any>()
        val keyPartTwo = ArrayList<Any>()
        val cyclicKeyMap = HashMap<Any, String>()
        cyclicKeyMap[keyPartOne] = "value"
        keyPartOne.add(keyPartTwo)
        keyPartTwo.add(keyPartOne)
        val keyCycleError =
            assertThrows(IllegalArgumentException::class.java) {
                JsJavaBridgeDelegates.validateBridgeReturnValue(cyclicKeyMap)
            }
        assertTrue(keyCycleError.message.orEmpty().contains("cyclic"))

        val oversized = Collections.nCopies(Int.MAX_VALUE, "x")
        val sizeError =
            assertThrows(IllegalArgumentException::class.java) {
                JsJavaBridgeDelegates.validateBridgeReturnValue(oversized)
            }
        assertTrue(sizeError.message.orEmpty().contains("element limit"))

        val repeatedLargeScalar = Collections.nCopies(65_536, "x".repeat(32))
        val scalarTextError =
            assertThrows(IllegalArgumentException::class.java) {
                JsJavaBridgeDelegates.validateBridgeReturnValue(repeatedLargeScalar)
            }
        assertTrue(scalarTextError.message.orEmpty().contains("scalar text limit"))

        var deeplyNested: Any? = "leaf"
        repeat(65) {
            deeplyNested = listOf(deeplyNested)
        }
        val depthError =
            assertThrows(IllegalArgumentException::class.java) {
                JsJavaBridgeDelegates.validateBridgeReturnValue(deeplyNested)
            }
        assertTrue(depthError.message.orEmpty().contains("depth limit"))

        JsJavaBridgeDelegates.validateBridgeReturnValue(listOf("bridge", "still", "works"))
    }

    @Test
    fun plainJsonConversionRetainsLargeNonBridgePayloads() {
        val largeComposeDslPayload = Collections.nCopies(65_537, "item")

        JsJavaBridgeDelegates.validateBridgeReturnValueForMode(
            largeComposeDslPayload,
            bridgeAware = false
        )

        val bridgeError =
            assertThrows(IllegalArgumentException::class.java) {
                JsJavaBridgeDelegates.validateBridgeReturnValueForMode(
                    largeComposeDslPayload,
                    bridgeAware = true
                )
            }
        assertTrue(bridgeError.message.orEmpty().contains("element limit"))
    }

    @Test
    fun unrestrictedBridgePackagesAreExcludedFromReleaseAndBuildCheck() {
        val repositoryDirectory = locateRepositoryDirectory()
        fun readList(relativePath: String): Set<String> =
            repositoryDirectory.resolve(relativePath).readLines()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toSet()

        val incompatible =
            readList("tools/example_packages/legacy_incompatible_packages.txt")
        val releaseWhitelist =
            readList("tools/example_packages/packages_whitelist.txt")
        val syncScript =
            repositoryDirectory.resolve("tools/example_packages/sync_example_packages.py").readText()

        assertEquals(
            setOf(
                "apktool",
                "deepsearching",
                "message_insert",
                "pdf_vision_parser.js",
                "qqbot",
                "subagent"
            ),
            incompatible
        )
        assertTrue(incompatible.intersect(releaseWhitelist).isEmpty())
        assertTrue(syncScript.contains("legacy_incompatible_packages.txt"))
        assertTrue(syncScript.contains("SKIP-EXPECTED-INCOMPATIBLE"))
    }

    @Test
    fun distributedAndRuntimeBridgeExamplesMatchTheRestrictedContract() {
        val repositoryDirectory = locateRepositoryDirectory()
        val distributedExample = repositoryDirectory.resolve("examples/java_bridge.js").readText()
        val runtimeSuite =
            repositoryDirectory.resolve(
                "app/src/androidTest/js/com/ai/assistance/operit/core/tools/javascript/" +
                    "restricted_bridge/restricted_bridge.js"
            ).readText()
        val bridgeContract =
            repositoryDirectory.resolve(
                "docs/doc-src/dev-core/JAVA_BRIDGE_INTERFACE.md"
            ).readText()
        val bridgeTypes =
            repositoryDirectory.resolve("examples/types/java-bridge.d.ts").readText()

        assertTrue(distributedExample.contains("restricted_boundary"))
        assertFalse(distributedExample.contains("Java.android"))
        assertFalse(distributedExample.contains("Java.getApplicationContext"))
        assertFalse(distributedExample.contains("Java.implement"))
        assertFalse(distributedExample.contains("Java.proxy"))

        assertTrue(runtimeSuite.contains("Java.type('java.lang.StringBuilder')"))
        assertTrue(runtimeSuite.contains("Java.java.lang.Runtime.getRuntime()"))
        assertTrue(runtimeSuite.contains("Java.getApplicationContext()"))
        assertTrue(runtimeSuite.contains("Java.loadJar("))
        assertTrue(runtimeSuite.contains("inferred Comparator proxy"))
        assertTrue(runtimeSuite.contains("cyclic collection result"))
        assertTrue(runtimeSuite.contains("oversized lazy collection result"))
        assertTrue(runtimeSuite.contains("bridge did not recover after rejection"))
        assertTrue(runtimeSuite.contains("await expectRejectedAsync"))
        assertTrue(runtimeSuite.contains("capacity StringBuilder constructor"))
        assertTrue(runtimeSuite.contains("CharSequence proxy"))
        assertTrue(runtimeSuite.contains("quadratic collection helper"))
        assertTrue(runtimeSuite.contains("live object handle limit"))
        assertTrue(runtimeSuite.contains("unannotated NativeInterface lifecycle method"))
        assertTrue(bridgeContract.contains("64 层、65,536 个容器元素"))
        assertTrue(bridgeContract.contains("1,048,576 个标量字符"))
        assertTrue(bridgeContract.contains("1,024 个 live Java object handles"))
        assertTrue(bridgeContract.contains("检测到循环或超限时"))
        assertTrue(bridgeContract.contains("没有开放任何可消费代理的接口类型"))
        assertTrue(bridgeContract.contains("当前 profile 没有允许任何嵌套类"))
        assertFalse(bridgeTypes.contains("bigint |"))
    }

    private fun locateRepositoryDirectory(): File {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(workingDirectory, requireNotNull(workingDirectory.parentFile))
            .firstOrNull {
                it.resolve("tools/sandboxpackage_dev_install_or_update.js").isFile &&
                    it.resolve("app/src/main/assets/packages").isDirectory
            }
            ?: error("Unable to locate repository from ${workingDirectory.absolutePath}")
    }
}
