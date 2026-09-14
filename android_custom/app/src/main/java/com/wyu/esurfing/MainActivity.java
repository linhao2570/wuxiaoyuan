package com.wyu.esurfing;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.os.Build;
import android.provider.Settings;

import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.MethodChannel;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends FlutterActivity {

    private static final String CHANNEL = "aiqin/client_control";
    private final ExecutorService statusExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void configureFlutterEngine(FlutterEngine flutterEngine) {
        super.configureFlutterEngine(flutterEngine);
        MethodChannel channel = new MethodChannel(
                flutterEngine.getDartExecutor().getBinaryMessenger(), CHANNEL);
        channel.setMethodCallHandler((call, result) -> {
            switch (call.method) {
                case "startMonitor":
                    startMonitor(result);
                    break;
                case "stopMonitor":
                    stopMonitor(result);
                    break;
                case "openAccessibility":
                    openAccessibility(result);
                    break;
                case "openUsageAccess":
                    openUsageAccess(result);
                    break;
                case "getStatus":
                    getStatus(result);
                    break;
                default:
                    result.notImplemented();
            }
        });
    }

    private void startMonitor(MethodChannel.Result result) {
        try {
            Intent intent = new Intent(this, MonitorService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            result.success(true);
        } catch (Exception e) {
            result.error("START_FAILED", e.getMessage(), null);
        }
    }

    private void stopMonitor(MethodChannel.Result result) {
        try {
            stopService(new Intent(this, MonitorService.class));
            result.success(true);
        } catch (Exception e) {
            result.error("STOP_FAILED", e.getMessage(), null);
        }
    }

    private void openAccessibility(MethodChannel.Result result) {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            result.success(true);
        } catch (Exception e) {
            result.error("SETTINGS_FAILED", e.getMessage(), null);
        }
    }

    /**
     * 状态查询放到后台线程，避免实际联网探测卡住 Flutter 主线程。
     * 这个方法只在应用打开、回到前台或用户点击后调用，不做高频轮询。
     */
        private void openUsageAccess(MethodChannel.Result result) {
        try {
            Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            startActivity(intent);
            result.success(true);
        } catch (Exception e) {
            result.error("USAGE_FAILED", e.getMessage(), null);
        }
    }

    private boolean hasUsagePermission() {
        try {
            AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) return false;
            int mode = ops.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

private void getStatus(MethodChannel.Result result) {
        statusExecutor.execute(() -> {
            MonitorService.NetworkState state =
                    MonitorService.checkNetwork(getApplicationContext(), true);
            Map<String, Object> map = new HashMap<>();
            map.put("wifiConnected", state.wifiConnected);
            map.put("wifiOk", state.wifiConnected);
            map.put("internetOk", state.internetOk);
            map.put("networkDetail", state.detail);
            map.put("monitorRunning", MonitorService.isRunning());
            map.put("accessibilityRunning", ClientAccessibilityService.isRunning());
            map.put("usageAccessOk", hasUsagePermission());
            map.putAll(MonitorService.getStatusSnapshot());
            runOnUiThread(() -> result.success(map));
        });
    }

    @Override
    protected void onDestroy() {
        statusExecutor.shutdownNow();
        super.onDestroy();
    }
}
