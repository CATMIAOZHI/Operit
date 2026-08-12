package com.ai.assistance.operit.core.tools.javascript

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

class ScriptExecutionReceiverManifestTest {
    private val androidNamespace = "http://schemas.android.com/apk/res/android"
    private val toolsNamespace = "http://schemas.android.com/tools"
    private val receiverClass =
        "com.ai.assistance.operit.core.tools.javascript.ScriptExecutionReceiver"

    @Test
    fun mainManifestKeepsScriptExecutionReceiverPrivate() {
        val manifest = parseManifest(appDirectory.resolve("src/main/AndroidManifest.xml"))
        val receiver = manifest.receiver(receiverClass)

        assertEquals("false", receiver.androidAttribute("exported"))
        assertFalse(receiver.hasAttributeNS(androidNamespace, "permission"))
        assertTrue(
            receiver.getElementsByTagName("action").let { actions ->
                (0 until actions.length)
                    .map { actions.item(it) as Element }
                    .any {
                        it.androidAttribute("name") ==
                            "com.ai.assistance.operit.EXECUTE_JS"
                    }
            }
        )
    }

    @Test
    fun debugManifestRequiresTheShellDumpPermission() {
        val manifest = parseManifest(appDirectory.resolve("src/debug/AndroidManifest.xml"))
        val receiver = manifest.receiver(receiverClass)

        assertEquals("true", receiver.androidAttribute("exported"))
        assertEquals("android:exported", receiver.getAttributeNS(toolsNamespace, "replace"))
        assertEquals("android.permission.DUMP", receiver.androidAttribute("permission"))
    }

    @Test
    fun adbLaunchersUseConfigurableApplicationIdAndFullReceiverClass() {
        val scripts =
            listOf(
                "tools/adb/execute_js.sh",
                "tools/adb/execute_js.bat",
                "tools/adb/execute_js_dir.sh",
                "tools/adb/execute_js_dir.bat",
                "tools/adb/run_sandbox_script.sh",
                "tools/adb/run_sandbox_script.bat"
            )

        scripts.forEach { relativePath ->
            val script = repositoryDirectory.resolve(relativePath)
            val content = script.readText()
            val receiverReference =
                if (relativePath.endsWith(".bat")) "%RECEIVER_COMPONENT%" else "${'$'}RECEIVER_COMPONENT"
            val receiverAssignment =
                if (relativePath.endsWith(".bat")) {
                    "set \"RECEIVER_COMPONENT=%APP_PACKAGE%/$receiverClass\""
                } else {
                    "RECEIVER_COMPONENT=\"${'$'}{APP_PACKAGE}/$receiverClass\""
                }
            val activeLines =
                content.lineSequence()
                    .map(String::trim)
                    .filterNot {
                        it.startsWith("#") ||
                            it.startsWith("::") ||
                            it.equals("REM", ignoreCase = true) ||
                            it.startsWith("REM ", ignoreCase = true)
                    }
                    .filter(String::isNotEmpty)
                    .toList()
            val packageAssignments =
                if (relativePath.endsWith(".bat")) {
                    listOf(
                        "set \"APP_PACKAGE=com.rainy.operitry.dev\"",
                        "set \"APP_PACKAGE=%OPERIT_APP_PACKAGE%\""
                    )
                } else {
                    listOf(
                        "APP_PACKAGE=\"${'$'}{OPERIT_APP_PACKAGE:-com.rainy.operitry.dev}\""
                    )
                }
            val storageVariable =
                if (relativePath.contains("execute_js_dir")) "TARGET_BASE" else "TARGET_DIR"
            val storageAssignment =
                if (relativePath.endsWith(".bat")) {
                    "set \"$storageVariable=/sdcard/Android/data/%APP_PACKAGE%/js_temp\""
                } else {
                    "$storageVariable=\"/sdcard/Android/data/${'$'}{APP_PACKAGE}/js_temp\""
                }
            val broadcastPrefix =
                "am broadcast -a com.ai.assistance.operit.EXECUTE_JS -n $receiverReference"
            val broadcastLines = activeLines.filter { it.contains("am broadcast") }

            packageAssignments.forEach { assignment ->
                assertEquals(
                    "$relativePath must contain exactly one active package assignment: $assignment",
                    1,
                    activeLines.count { it == assignment }
                )
            }
            assertEquals(
                "$relativePath must contain exactly one active receiver assignment",
                1,
                activeLines.count { it == receiverAssignment }
            )
            assertEquals(
                "$relativePath must contain exactly one dynamic storage assignment",
                1,
                activeLines.count { it == storageAssignment }
            )
            assertEquals(
                "$relativePath must contain exactly two execution broadcasts",
                2,
                broadcastLines.size
            )
            assertTrue(
                "$relativePath must route every execution broadcast through the configured receiver",
                broadcastLines.all { it.contains(broadcastPrefix) }
            )
            val firstBroadcastIndex = activeLines.indexOfFirst { it.contains("am broadcast") }
            packageAssignments.forEach { assignment ->
                assertTrue(
                    "$relativePath must assign the app package before broadcasting",
                    activeLines.indexOf(assignment) in 0 until firstBroadcastIndex
                )
            }
            assertTrue(
                "$relativePath must assign the receiver before broadcasting",
                activeLines.indexOf(receiverAssignment) in 0 until firstBroadcastIndex
            )
            assertTrue(
                "$relativePath must assign the storage path before broadcasting",
                activeLines.indexOf(storageAssignment) in 0 until firstBroadcastIndex
            )
            assertFalse(
                "$relativePath must not retain the legacy relative component",
                content.contains("com.ai.assistance.operit/.core.tools.javascript.ScriptExecutionReceiver")
            )
            assertFalse(
                "$relativePath must not retain the legacy storage package",
                content.contains("Android/data/com.ai.assistance.operit/js_temp")
            )
        }
    }

    private val appDirectory: File by lazy {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        listOf(workingDirectory, workingDirectory.resolve("app"))
            .firstOrNull { it.resolve("src/main/AndroidManifest.xml").isFile }
            ?: error("Unable to locate the app module from ${workingDirectory.absolutePath}")
    }

    private val repositoryDirectory: File
        get() = requireNotNull(appDirectory.parentFile)

    private fun parseManifest(file: File): Document {
        assertTrue("Manifest does not exist: ${file.absolutePath}", file.isFile)
        return DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)
    }

    private fun Document.elements(tagName: String): List<Element> {
        val nodes = getElementsByTagName(tagName)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Document.receiver(className: String): Element {
        return elements("receiver").single {
            val declaredName = it.androidAttribute("name")
            declaredName == className || className.endsWith(declaredName)
        }
    }

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(androidNamespace, name)
}
