package com.wyu.esurfing

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.flutter.plugin.common.MethodChannel

class ClientAccessibilityService : AccessibilityService() {

  companion object {
    const val CLIENT_PACKAGE = "com.cndatacom.campus.cdccportalgd"
    private var instance: ClientAccessibilityService? = null
    fun isRunning(): Boolean = instance != null
  }

  private val handler = Handler(Looper.getMainLooper())
  private var lastClickAt = 0L
  private var lastLaunchAt = 0L

  // 每 3 分钟检查一次 WiFi 连通性，仅在未验证时唤起客户端
  private val checkRunnable = object : Runnable {
    override fun run() {
      if (!wifiValidated()) {
        log("WiFi 未验证，尝试唤起广东校园")
        launchClientIfNeeded()
      } else {
        log("WiFi 正常，无需操作")
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
    log("无障碍服务已启动")
    sendStatus(true)
    handler.postDelayed(checkRunnable, 2000L)
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent) {
    if (event.packageName != CLIENT_PACKAGE) return
    val root = rootInActiveWindow ?: return
    if (System.currentTimeMillis() - lastClickAt < 5000) return

    val loginNode = findByText(root, "点我登录")
    val retryNode = findByText(root, "重新检测")
    val target = loginNode ?: retryNode

    if (target != null && target.isEnabled && target.isVisibleToUser) {
      lastClickAt = System.currentTimeMillis()
      val which = if (loginNode != null) "点我登录" else "重新检测"
      log("检测到按钮：" + which + "，执行点击")
      target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
      handler.postDelayed({ maybeReturnHome() }, 4000L)
    }
  }

  private fun maybeReturnHome() {
    if (wifiValidated()) {
      log("网络已验证，回到桌面")
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

  fun launchClientIfNeeded() {
    val now = System.currentTimeMillis()
    if (now - lastLaunchAt < 60 * 1000L) return
    lastLaunchAt = now
    val intent = packageManager.getLaunchIntentForPackage(CLIENT_PACKAGE)
    if (intent != null) {
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      startActivity(intent)
      log("已唤起广东校园客户端")
    } else {
      log("未找到广东校园客户端")
    }
  }

  private fun wifiValidated(): Boolean {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
      caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
      caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
  }

  private fun log(msg: String) {
    handler.post {
      MainActivity.channel?.invokeMethod("log", msg)
    }
  }

  private fun sendStatus(on: Boolean) {
    handler.post {
      MainActivity.channel?.invokeMethod("status", on)
    }
  }

  override fun onInterrupt() {}

  override fun onDestroy() {
    instance = null
    handler.removeCallbacksAndMessages(null)
    sendStatus(false)
    super.onDestroy()
  }
}
