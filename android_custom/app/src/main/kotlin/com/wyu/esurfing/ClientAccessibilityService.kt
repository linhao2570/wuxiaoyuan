package com.wyu.esurfing

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ClientAccessibilityService : AccessibilityService() {

    companion object {
        const val CLIENT_PACKAGE = "com.cndatacom.campus.cdccportalgd"
        private const val TAG = "aiqin-service"
        private var instance: ClientAccessibilityService? = null
        fun isRunning(): Boolean = instance != null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastClickAt = 0L
    private var lastLaunchAt = 0L

    // Check every 3 minutes; launch client only when WiFi is not validated
    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!wifiValidated()) {
                Log.d(TAG, "WiFi not validated, launching client")
                launchClientIfNeeded()
            } else {
                Log.d(TAG, "WiFi OK")
            }
            handler.postDelayed(this, 3 * 60 * 1000L)
        }
    }

    override fun onServiceConnected() {
        instance = this
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 300
            packageNames = arrayOf(CLIENT_PACKAGE)
        }
        Log.d(TAG, "service connected")
        handler.postDelayed(checkRunnable, 2000L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName != CLIENT_PACKAGE) return
        val root = rootInActiveWindow ?: return
        if (System.currentTimeMillis() - lastClickAt < 5000) return

        // Button text placeholders; will be adjusted after build success
        val loginNode = findByText(root, "login")
        val retryNode = findByText(root, "retry")
        val target = loginNode ?: retryNode

        if (target != null && target.isEnabled && target.isVisibleToUser) {
            lastClickAt = System.currentTimeMillis()
            Log.d(TAG, "clicking button")
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            handler.postDelayed({ maybeReturnHome() }, 4000L)
        }
    }

    private fun maybeReturnHome() {
        if (wifiValidated()) {
            Log.d(TAG, "validated, returning home")
            val home = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(home)
        }
    }

    private fun findByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        return root.findAccessibilityNodeInfosByText(text)
            .firstOrNull { it.isVisibleToUser }
    }

    private fun launchClientIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastLaunchAt < 60 * 1000L) return
        lastLaunchAt = now
        val intent = packageManager.getLaunchIntentForPackage(CLIENT_PACKAGE)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Log.d(TAG, "launched client")
        } else {
            Log.d(TAG, "client not found")
        }
    }

    private fun wifiValidated(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
        }
        return true
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
