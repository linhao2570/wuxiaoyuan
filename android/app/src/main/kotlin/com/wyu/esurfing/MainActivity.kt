package com.wyu.esurfing

import android.content.Intent
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

  companion object {
    var channel: MethodChannel? = null
  }

  override fun configureFlutterEngine(engine: FlutterEngine) {
    super.configureFlutterEngine(engine)
    val methodChannel = MethodChannel(engine.dartExecutor.binaryMessenger, "aiqin/client_control")
    channel = methodChannel
    methodChannel.setMethodCallHandler { call, result ->
      when (call.method) {
        "openClient" -> {
          val intent = packageManager.getLaunchIntentForPackage(ClientAccessibilityService.CLIENT_PACKAGE)
          if (intent != null) {
            startActivity(intent)
            result.success(true)
          } else {
            result.error("NOT_INSTALLED", "未找到广东校园客户端", null)
          }
        }
        "openAccessibility" -> {
          startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
          result.success(true)
        }
        "startMonitor" -> {
          if (ClientAccessibilityService.isRunning()) {
            result.success(true)
          } else {
            val intent = Intent(this, ClientAccessibilityService::class.java)
            startService(intent)
            result.success(true)
          }
        }
        "stopMonitor" -> {
          val intent = Intent(this, ClientAccessibilityService::class.java)
          stopService(intent)
          result.success(true)
        }
        else -> result.notImplemented()
      }
    }
  }

  override fun onDestroy() {
    channel = null
    super.onDestroy()
  }
}
