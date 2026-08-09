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
    fun mainManifestProtectsExportedReceiverWithSignaturePermission() {
        val manifest = parseManifest(appDirectory.resolve("src/main/AndroidManifest.xml"))
        val permissionName = "${'$'}{applicationId}.permission.EXECUTE_JS"
        val permission =
            manifest.elements("permission").single {
                it.androidAttribute("name") == permissionName
            }
        val receiver = manifest.receiver(receiverClass)

        assertEquals("signature", permission.androidAttribute("protectionLevel"))
        assertEquals("true", receiver.androidAttribute("exported"))
        assertEquals(permissionName, receiver.androidAttribute("permission"))
    }

    @Test
    fun debugManifestRemovesOnlyTheReceiverPermission() {
        val manifest = parseManifest(appDirectory.resolve("src/debug/AndroidManifest.xml"))
        val receiver = manifest.receiver(receiverClass)

        assertEquals("android:permission", receiver.getAttributeNS(toolsNamespace, "remove"))
        assertFalse(receiver.hasAttributeNS(androidNamespace, "permission"))
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
            val packageReference =
                if (relativePath.endsWith(".bat")) "%APP_PACKAGE%" else "${'$'}{APP_PACKAGE}"
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
                    .map(String::trimStart)
                    .filterNot { it.startsWith("#") || it.startsWith("REM", ignoreCase = true) }
                    .toList()

            if (relativePath.endsWith(".bat")) {
                assertTrue(
                    "$relativePath must default to the personal debug app",
                    activeLines.contains("set \"APP_PACKAGE=com.rainy.operitry\"")
                )
                assertTrue(
                    "$relativePath must honor the package override",
                    activeLines.contains("set \"APP_PACKAGE=%OPERIT_APP_PACKAGE%\"")
                )
            } else {
                assertTrue(
                    "$relativePath must default to the personal debug app and honor the override",
                    activeLines.contains(
                        "APP_PACKAGE=\"${'$'}{OPERIT_APP_PACKAGE:-com.rainy.operitry}\""
                    )
                )
            }
            assertTrue(
                "$relativePath must build the receiver from the app package and full class name",
                activeLines.contains(receiverAssignment)
            )
            assertTrue(
                "$relativePath must target the configured receiver component",
                activeLines.any { it.contains("-n $receiverReference") }
            )
            assertTrue(
                "$relativePath must derive its storage path from the app package",
                activeLines.any { it.contains("Android/data/$packageReference/js_temp") }
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
