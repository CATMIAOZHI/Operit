package com.rainy.operitry.provider

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.*
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.util.Xml
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.ai.assistance.operit.provider.IAccessibilityProvider
import java.io.StringWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Binder is exported, but every transaction authenticates the calling package and signature. */
class ProviderBridgeService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var focusedNode: AccessibilityNodeInfo? = null
    private var focusedToken = ""
    private fun authorize() {
        val uid = Binder.getCallingUid()
        val packages = packageManager.getPackagesForUid(uid).orEmpty()
        check(packages.any { it in setOf("com.rainy.operitry", "com.rainy.operitry.dev", "com.rainy.operitry.clone") } &&
            packageManager.checkSignatures(uid, Process.myUid()) == PackageManager.SIGNATURE_MATCH) {
            "Untrusted accessibility client"
        }
    }
    private fun service(): ControlAccessibilityService? {
        authorize()
        return ControlAccessibilityService.current
    }
    private fun <T> onMain(fallback: T, action: () -> T): T {
        val result = AtomicReference(fallback)
        val done = CountDownLatch(1)
        val expired = AtomicBoolean(false)
        main.post {
            try { if (!expired.get()) result.set(runCatching(action).getOrDefault(fallback)) }
            finally { done.countDown() }
        }
        if (!done.await(3, TimeUnit.SECONDS)) expired.set(true)
        return result.get()
    }
    private fun gesture(x: Int, y: Int, endX: Int, endY: Int, duration: Long): Boolean {
        val svc = service() ?: return false
        if (minOf(x, y, endX, endY) < 0 || duration !in 1..5000) return false
        val done = CountDownLatch(1)
        val success = AtomicBoolean(false)
        val expired = AtomicBoolean(false)
        main.post {
            if (expired.get() || ControlAccessibilityService.current !== svc) {
                done.countDown(); return@post
            }
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()); lineTo(endX.toFloat(), endY.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
            if (!svc.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) { success.set(true); done.countDown() }
                    override fun onCancelled(gestureDescription: GestureDescription?) { done.countDown() }
                }, main)) done.countDown()
        }
        if (!done.await(duration + 3000, TimeUnit.MILLISECONDS)) expired.set(true)
        return success.get()
    }
    private val binder = object : IAccessibilityProvider.Stub() {
        override fun isAccessibilityServiceEnabled() = service() != null
        override fun getCurrentActivityName(): String = service()?.activityName.orEmpty().substringAfter('/')
        override fun getForegroundIdentity(): String {
            val svc = service() ?: return ""
            return onMain("") {
                val root = svc.rootInActiveWindow ?: return@onMain ""
                try { "${root.packageName}/${root.windowId}" } finally { root.recycle() }
            }
        }
        override fun performClick(x: Int, y: Int) = gesture(x, y, x, y, 60)
        override fun performLongPress(x: Int, y: Int) = gesture(x, y, x, y, 650)
        override fun performSwipe(x: Int, y: Int, endX: Int, endY: Int, duration: Long) =
            gesture(x, y, endX, endY, duration)
        override fun performGlobalAction(actionId: Int): Boolean {
            val svc = service() ?: return false
            return onMain(false) { svc.performGlobalAction(actionId) }
        }
        override fun findFocusedNodeId(): String {
            val svc = service() ?: return ""
            return onMain("") {
                val root = svc.rootInActiveWindow ?: return@onMain ""
                try {
                    val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return@onMain ""
                    try {
                        focusedNode?.recycle()
                        focusedNode = null
                        focusedToken = ""
                        if (focused.isEditable) {
                            focusedNode = AccessibilityNodeInfo.obtain(focused)
                            focusedToken = java.util.UUID.randomUUID().toString()
                        }
                        focusedToken
                    }
                    finally { focused.recycle() }
                } finally { root.recycle() }
            }
        }
        override fun setTextOnNode(nodeId: String?, text: String?): Boolean {
            val svc = service() ?: return false
            if (text == null || text.length > 100_000) return false
            return onMain(false) {
                val root = svc.rootInActiveWindow ?: return@onMain false
                try {
                    val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return@onMain false
                    try {
                        val matched = focused.isEditable && nodeId == focusedToken && focused == focusedNode
                        focusedNode?.recycle(); focusedNode = null; focusedToken = ""
                        matched &&
                            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                            })
                    } finally { focused.recycle() }
                } finally { root.recycle() }
            }
        }
        override fun getUiHierarchy(): String {
            val svc = service() ?: return ""
            return onMain("") {
                val root = svc.rootInActiveWindow ?: return@onMain ""
                try {
                    val output = StringWriter()
                    val xml = Xml.newSerializer().apply { setOutput(output); startDocument("UTF-8", true); startTag("", "hierarchy") }
                    var count = 0
                    fun visit(node: AccessibilityNodeInfo, depth: Int) {
                        if (depth > 40 || count++ >= 1500) return
                        val bounds = Rect().also { node.getBoundsInScreen(it) }
                        xml.startTag("", "node")
                        mapOf("package" to node.packageName, "class" to node.className,
                            "text" to if (node.isPassword) "" else node.text,
                            "content-desc" to node.contentDescription, "resource-id" to node.viewIdResourceName,
                            "clickable" to node.isClickable.toString(), "focused" to node.isFocused.toString(),
                            "enabled" to node.isEnabled.toString(),
                            "bounds" to "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
                            .forEach { (key, value) ->
                                val safe = StringBuilder()
                                value?.toString()?.codePoints()?.limit(500)?.forEach { code ->
                                    if (code == 9 || code == 10 || code == 13 ||
                                        code in 0x20..0xD7FF || code in 0xE000..0xFFFD || code in 0x10000..0x10FFFF)
                                        safe.appendCodePoint(code)
                                }
                                xml.attribute("", key, safe.toString())
                            }
                        for (i in 0 until node.childCount) {
                            val child = node.getChild(i) ?: continue
                            try { visit(child, depth + 1) } finally { child.recycle() }
                        }
                        xml.endTag("", "node")
                    }
                    visit(root, 0)
                    xml.endTag("", "hierarchy"); xml.endDocument()
                    output.toString().takeIf { it.length < 350_000 }.orEmpty()
                } finally { root.recycle() }
            }
        }
        override fun takeScreenshot(destination: ParcelFileDescriptor?, format: String?): Boolean {
            if (destination == null) { authorize(); return false }
            val done = CountDownLatch(1)
            val success = AtomicBoolean(false)
            destination.use { descriptor ->
                val svc = service() ?: return false
                if (Build.VERSION.SDK_INT < 30) return false
                svc.takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        try {
                            val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            try {
                                val copy = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                try {
                                    ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use {
                                        success.set(copy?.compress(Bitmap.CompressFormat.PNG, 100, it) == true)
                                    }
                                } finally { copy?.recycle() }
                            } finally { bitmap?.recycle(); result.hardwareBuffer.close() }
                        } catch (_: Exception) { success.set(false) }
                        finally { done.countDown() }
                    }
                    override fun onFailure(errorCode: Int) { done.countDown() }
                })
                done.await(5, TimeUnit.SECONDS)
            }
            return success.get()
        }
    }
    override fun onBind(intent: Intent?) = binder
    override fun onDestroy() {
        focusedNode?.recycle()
        focusedNode = null
        super.onDestroy()
    }
}
