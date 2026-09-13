package com.wyu.esurfing;

import android.content.Intent;
import android.provider.Settings;
import androidx.annotation.NonNull;
import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.MethodChannel;

public class MainActivity extends FlutterActivity {

    private static final String CHANNEL = "aiqin/client_control";
    static MethodChannel channel;

    @Override
    public void configureFlutterEngine(@NonNull FlutterEngine flutterEngine) {
        super.configureFlutterEngine(flutterEngine);
        channel = new MethodChannel(flutterEngine.getDartExecutor().getBinaryMessenger(), CHANNEL);
        channel.setMethodCallHandler(
            (call, result) -> {
                switch (call.method) {
                    case "openClient":
                        openClient(result);
                        break;
                    case "openAccessibility":
                        openAccessibility(result);
                        break;
                    case "startMonitor":
                        startMonitor(result);
                        break;
                    case "stopMonitor":
                        stopMonitor(result);
                        break;
                    default:
                        result.notImplemented();
                }
            }
        );
    }

    private void openClient(MethodChannel.Result result) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(
            ClientAccessibilityService.CLIENT_PACKAGE);
        if (intent != null) {
            startActivity(intent);
            result.success(true);
        } else {
            result.error("NOT_INSTALLED", "Guangdong Campus app not found", null);
        }
    }

    private void openAccessibility(MethodChannel.Result result) {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        result.success(true);
    }

    private void startMonitor(MethodChannel.Result result) {
        if (ClientAccessibilityService.isRunning()) {
            result.success(true);
            return;
        }
        Intent intent = new Intent(this, ClientAccessibilityService.class);
        startService(intent);
        result.success(true);
    }

    private void stopMonitor(MethodChannel.Result result) {
        Intent intent = new Intent(this, ClientAccessibilityService.class);
        stopService(intent);
        result.success(true);
    }

    @Override
    protected void onDestroy() {
        channel = null;
        super.onDestroy();
    }
}
