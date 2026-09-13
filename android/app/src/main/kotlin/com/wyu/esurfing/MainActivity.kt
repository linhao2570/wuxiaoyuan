package com.wyu.esurfing
import android.content.Intent
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
  override fun configureFlutterEngine(engine: FlutterEngine) {
    super.configureFlutterEngine(engine)
    MethodChannel(engine.dartExecutor.binaryMessenger, "aiqin/client_control").setMethodCallHandler { call, result ->
      when (call.method) {
        "openClient" -> packageManager.getLaunchIntentForPackage(ClientAccessibilityService.CLIENT_PACKAGE)?.let { startActivity(it); result.success(true) } ?: result.error("NOT_INSTALLED", "未找到广东校园客户端", null)
        "openAccessibility" -> { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); result.success(true) }
        "startMonitor" -> { startService(Intent(this, ClientAccessibilityService::class.java)); result.success(true) }
        "stopMonitor" -> { stopService(Intent(this, ClientAccessibilityService::class.java)); result.success(true) }
        else -> result.notImplemented()
      }
    }
  }
}
