package com.wyu.esurfing

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ClientAccessibilityService : AccessibilityService() {

    companion object {
        const val CLIENT_PACKAGE = "com.cndatacom.campus.cdccportalgd"
        private const val TAG = "aiqin-access"
        private var instance: ClientAccessibilityService? = null
        fun isRunning(): Boolean = instance != null

        // Button texts - adjust based on actual client UI
        // We search for partial text matches
        private const val TEXT_LOGIN = "点我登录"
        private const val TEXT_RETRY = "重新检测"
        private const val TEXT_DISCONNECT = "断开连接"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastClickAt = 0L
    private var lastState: String = "unknown"

    override fun onServiceConnected() {
        instance = this
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 300
            packageNames = arrayOf(CLIENT_PACKAGE)
        }
        Log.d(TAG, "accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName != CLIENT_PACKAGE) return
        val root = rootInActiveWindow ?: return

        if (System.currentTimeMillis() - lastClickAt < 5000) return

        // Detect state
        val hasLogin = hasVisibleText(root, TEXT_LOGIN)
        val hasRetry = hasVisibleText(root, TEXT_RETRY)
        val hasDisconnect = hasVisibleText(root, TEXT_DISCONNECT)

        val state = when {
            hasLogin -> "need_login"
            hasRetry -> "need_retry"
            hasDisconnect -> "connected"
            else -> "unknown"
        }

        if (state != lastState) {
            lastState = state
            Log.d(TAG, "client state: $state")
        }

        when (state) {
            "need_login" -> {
                clickFirstVisible(root, TEXT_LOGIN)
                lastClickAt = System.currentTimeMillis()
                Log.d(TAG, "clicked login button")
                // Return home after a delay if network comes back
                handler.postDelayed({ maybeReturnHome() }, 4000L)
            }
            "need_retry" -> {
                clickFirstVisible(root, TEXT_RETRY)
                lastClickAt = System.currentTimeMillis()
                Log.d(TAG, "clicked retry button")
                handler.postDelayed({ maybeReturnHome() }, 4000L)
            }
            "connected" -> {
                // Already connected, just go back to home
                Log.d(TAG, "already connected, returning home")
                maybeReturnHome()
            }
            else -> {
                // Unknown state, do nothing
            }
        }
    }

    private fun maybeReturnHome() {
        Log.d(TAG, "returning to home screen")
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(home)
    }

    private fun hasVisibleText(root: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        return nodes.any { it.isVisibleToUser }
    }

    private fun clickFirstVisible(root: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        val target = nodes.firstOrNull { it.isVisibleToUser && it.isEnabled }
        if (target != null) {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        return false
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
