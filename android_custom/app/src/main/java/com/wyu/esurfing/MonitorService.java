package com.wyu.esurfing;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.net.InetAddress;

public class MonitorService extends Service {

    private static final String TAG = "aiqin-monitor";
    private static final String CHANNEL_ID = "aiqin_monitor";
    private static final int NOTIFICATION_ID = 1001;

    // How long after screen off to do first reset (30 seconds)
    private static final long SCREEN_OFF_RESET_DELAY_MS = 30 * 1000L;

    // How often to reset while screen stays off (40 minutes)
    private static final long SCREEN_OFF_RESET_INTERVAL_MS = 40 * 60 * 1000L;

    private static boolean running = false;
    public static boolean isRunning() { return running; }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean screenOn = true;
    private long screenOffTime = 0L;
    private long lastResetTime = 0L;
    private boolean initialCheckDone = false;

    private BroadcastReceiver screenReceiver;

    private final Runnable screenOffResetRunnable = new Runnable() {
        @Override
        public void run() {
            if (!screenOn) {
                Log.d(TAG, "screen off for 30s, doing reset");
                resetClient();
                // Schedule the next reset for 40 minutes later
                schedulePeriodicReset();
            }
        }
    };

    private final Runnable periodicResetRunnable = new Runnable() {
        @Override
        public void run() {
            if (!screenOn) {
                Log.d(TAG, "periodic screen-off reset");
                resetClient();
                handler.postDelayed(this, SCREEN_OFF_RESET_INTERVAL_MS);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("监测中"));
        Log.d(TAG, "monitor service created");
        registerScreenReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!initialCheckDone) {
            initialCheckDone = true;
            // Do an initial check right after start
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    initialNetworkCheck();
                }
            }, 1000L);
        }
        return START_STICKY;
    }

    private void registerScreenReceiver() {
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    screenOn = false;
                    screenOffTime = System.currentTimeMillis();
                    Log.d(TAG, "screen off, scheduling reset in 30s");
                    // Cancel any existing periodic reset and schedule the 30s one
                    handler.removeCallbacks(periodicResetRunnable);
                    handler.postDelayed(screenOffResetRunnable, SCREEN_OFF_RESET_DELAY_MS);
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    screenOn = true;
                    Log.d(TAG, "screen on, canceling screen-off resets");
                    // Cancel all screen-off reset tasks
                    handler.removeCallbacks(screenOffResetRunnable);
                    handler.removeCallbacks(periodicResetRunnable);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, filter);
    }

    private void schedulePeriodicReset() {
        handler.removeCallbacks(periodicResetRunnable);
        handler.postDelayed(periodicResetRunnable, SCREEN_OFF_RESET_INTERVAL_MS);
    }

    private void initialNetworkCheck() {
        Log.d(TAG, "initial network check");
        if (wifiValidated()) {
            Log.d(TAG, "already connected, no action needed");
            updateNotification("网络正常");
            return;
        }

        Log.d(TAG, "not connected on start, launching client quickly");
        updateNotification("重连中...");

        if (!isClientRunning()) {
            launchClient();
            // Return to current app as quickly as possible
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    returnToPreviousApp();
                }
            }, 2000L);
        }
    }

    private void resetClient() {
        lastResetTime = System.currentTimeMillis();
        Log.d(TAG, "=== reset client ===");
        updateNotification("重置广东校园...");
        killClient();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                launchClient();
                Log.d(TAG, "client relaunched");
                updateNotification("监测中");
            }
        }, 1500L);
    }

    private void killClient() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            am.killBackgroundProcesses(ClientAccessibilityService.CLIENT_PACKAGE);
            Log.d(TAG, "killed client");
        } catch (Exception e) {
            Log.d(TAG, "kill client failed: " + e.getMessage());
        }
    }

    private void launchClient() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(
            ClientAccessibilityService.CLIENT_PACKAGE);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            Log.d(TAG, "launched client");
        } else {
            Log.d(TAG, "client not found");
        }
    }

    private void returnToPreviousApp() {
        // Move the client task to back so user sees their previous app
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        try {
            // Move the client task to background
            // This works by finding the client task and moving it to back
            // Actually, we can not directly move another app's task.
            // Instead, we bring our own activity to front then immediately move it to back.
            // Simpler approach: send user to home - no, user wants previous app.
            // Best we can do from a service: do nothing and let the user press back.
            // But the accessibility service can send GLOBAL_ACTION_BACK when client connects.
            Log.d(TAG, "waiting for accessibility to handle return-to-previous-app");
        } catch (Exception e) {
            Log.d(TAG, "return failed: " + e.getMessage());
        }
    }

    private boolean wifiValidated() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        Network net = cm.getActiveNetwork();
        if (net == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
        if (caps == null) return false;
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false;
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false;
        }
        // Additional DNS check to avoid captive portal false positives
        try {
            InetAddress addr = InetAddress.getByName("www.baidu.com");
            return addr != null && !addr.getHostAddress().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isClientRunning() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : am.getRunningServices(64)) {
            if (ClientAccessibilityService.CLIENT_PACKAGE.equals(service.service.getPackageName())) {
                return true;
            }
        }
        return false;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "aiqin monitor",
                NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("aiqin")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        try {
            if (screenReceiver != null) {
                unregisterReceiver(screenReceiver);
            }
        } catch (Exception ignored) {}
        Log.d(TAG, "monitor service destroyed");
        super.onDestroy();
    }
}
