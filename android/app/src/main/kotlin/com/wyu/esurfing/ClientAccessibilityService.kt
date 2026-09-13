package com.wyu.esurfing
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ClientAccessibilityService : AccessibilityService() {
  companion object { const val CLIENT_PACKAGE = "com.cndatacom.campus.cdccportalgd" }
  private val handler = Handler(Looper.getMainLooper())
  private var lastAction = 0L
  private val cycle = object : Runnable { override fun run() { if (!networkValidated()) launchClient(); handler.postDelayed(this, 90 * 60 * 1000L) } }
  override fun onServiceConnected() { serviceInfo = AccessibilityServiceInfo().apply { eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED; feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC; notificationTimeout = 300; packageNames = arrayOf(CLIENT_PACKAGE) }; handler.postDelayed({ if (!networkValidated()) launchClient() }, 1500L); handler.postDelayed(cycle, 90 * 60 * 1000L) }
  private fun networkValidated(): Boolean { val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager; val n = cm.activeNetwork ?: return false; val c = cm.getNetworkCapabilities(n) ?: return false; return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
  private fun launchClient() { packageManager.getLaunchIntentForPackage(CLIENT_PACKAGE)?.let { startActivity(it) } }
  override fun onAccessibilityEvent(event: AccessibilityEvent) {
    if (event.packageName != CLIENT_PACKAGE) return
    val root = rootInActiveWindow ?: return
    if (System.currentTimeMillis() - lastAction < 5000) return
    val node = find(root, "点我登录") ?: find(root, "重新检测")
    if (node != null && node.isEnabled) { lastAction = System.currentTimeMillis(); node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
  }
  private fun find(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? = root.findAccessibilityNodeInfosByText(text).firstOrNull { it.isVisibleToUser }
  override fun onInterrupt() {}
  override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
