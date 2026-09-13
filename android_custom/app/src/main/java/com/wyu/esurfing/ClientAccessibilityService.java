package com.wyu.esurfing;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;

public class ClientAccessibilityService extends AccessibilityService {

    public static final String CLIENT_PACKAGE = "com.cndatacom.campus.cdccportalgd";
    private static final String TAG = "aiqin-access";
    private static ClientAccessibilityService instance = null;
    public static boolean isRunning() { return instance != null; }

    // Button texts from actual app screenshots
    private static final String BTN_LOGIN = "点我登录";
    private static final String BTN_RETRY = "重新检测";
    private static final String BTN_DISCONNECT = "断开网络";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastClickAt = 0L;
    private String lastState = "unknown";

    @Override
    protected void onServiceConnected() {
        instance = this;
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 300;
        info.packageNames = new String[] { CLIENT_PACKAGE };
        setServiceInfo(info);
        Log.d(TAG, "accessibility service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null ||
            !CLIENT_PACKAGE.equals(event.getPackageName().toString())) {
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        if (System.currentTimeMillis() - lastClickAt < 5000) return;

        // Detect current state
        boolean hasLogin = hasVisibleText(root, BTN_LOGIN);
        boolean hasRetry = hasVisibleText(root, BTN_RETRY);
        boolean hasDisconnect = hasVisibleText(root, BTN_DISCONNECT);

        String state;
        if (hasDisconnect) {
            state = "connected";
        } else if (hasLogin) {
            state = "need_login";
        } else if (hasRetry) {
            state = "need_retry";
        } else {
            state = "unknown";
        }

        if (!state.equals(lastState)) {
            lastState = state;
            Log.d(TAG, "client state: " + state);
        }

        switch (state) {
            case "need_login":
                if (clickFirstVisible(root, BTN_LOGIN)) {
                    lastClickAt = System.currentTimeMillis();
                    Log.d(TAG, "clicked: " + BTN_LOGIN);
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() { returnHome(); }
                    }, 4000L);
                }
                break;
            case "need_retry":
                if (clickFirstVisible(root, BTN_RETRY)) {
                    lastClickAt = System.currentTimeMillis();
                    Log.d(TAG, "clicked: " + BTN_RETRY);
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() { returnHome(); }
                    }, 4000L);
                }
                break;
            case "connected":
                Log.d(TAG, "already connected, returning home");
                returnHome();
                break;
            default:
                // Unknown state, do nothing
                break;
        }
    }

    private void returnHome() {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(home);
        Log.d(TAG, "returned home");
    }

    private boolean hasVisibleText(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        for (AccessibilityNodeInfo node : nodes) {
            if (node.isVisibleToUser()) return true;
        }
        return false;
    }

    private boolean clickFirstVisible(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        for (AccessibilityNodeInfo node : nodes) {
            if (node.isVisibleToUser() && node.isEnabled()) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                return true;
            }
        }
        return false;
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        instance = null;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
