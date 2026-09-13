package com.wyu.esurfing

import android.content.Intent
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    companion object {
        var channel: MethodChannel? = null
        private const val CHANNEL = "aiqin/client_control"
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        channel = methodChannel
        methodChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "openClient" -> openClient(result)
                "openAccessibility" -> openAccessibility(result)
                "startMonitor" -> startMonitor(result)
                "stopMonitor" -> stopMonitor(result)
                else -> result.notImplemented()
            }
        }
    }

    private fun openClient(result: MethodChannel.Result) {
        val intent = packageManager.getLaunchIntentForPackage(
            ClientAccessibilityService.CLIENT_PACKAGE
        )
        if (intent != null) {
            startActivity(intent)
            result.success(true)
        } else {
            result.error("NOT_INSTALLED", "Guangdong Campus app not found", null)
        }
    }

    private fun openAccessibility(result: MethodChannel.Result) {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        result.success(true)
    }

    private fun startMonitor(result: MethodChannel.Result) {
        if (ClientAccessibilityService.isRunning()) {
            result.success(true)
            return
        }
        val intent = Intent(this, ClientAccessibilityService::class.java)
        startService(intent)
        result.success(true)
    }

    private fun stopMonitor(result: MethodChannel.Result) {
        val intent = Intent(this, ClientAccessibilityService::class.java)
        stopService(intent)
        result.success(true)
    }

    override fun onDestroy() {
        channel = null
        super.onDestroy()
    }
}
