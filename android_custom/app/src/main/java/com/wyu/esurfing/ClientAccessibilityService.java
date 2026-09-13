package com.wyu.esurfing;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * 只做一件事：aiqin 亮屏恢复流程启动广东校园后，
 * 广东校园窗口一出现就发送一次返回键，回到用户原来使用的页面。
 *
 * Android 普通后台 Service 没有可靠的“返回上一个 App”接口，
 * 所以这个小型无障碍服务是必要的系统能力适配。
 * 它不会扫描其它应用，也不会自动点击账号、密码或验证码。
 */
public class ClientAccessibilityService extends AccessibilityService {

    public static final String CLIENT_PACKAGE = "com.cndatacom.campus.cdccportalgd";
    private static final String TAG = "aiqin-access";

    private static volatile ClientAccessibilityService instance;
    private static volatile boolean returnOnNextClientWindow;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastReturnAt;

    public static boolean isRunning() {
        return instance != null;
    }

    /**
     * 立刻发送一次全局返回键。
     * 用于熄屏重置后把广东校园压回后台，让亮屏时用户仍看到之前的应用。
     */
    public static boolean performBackNow() {
        return performBackNow(1);
    }

    /**
     * 连续发送若干次返回键。
     * 广东校园可能有多个 Activity 栈，一次返回不一定能立刻回到原应用，
     * 所以最多尝试 maxAttempts 次，每次间隔一小段时间。
     */
    public static boolean performBackNow(int maxAttempts) {
        if (instance == null) {
            Log.d(TAG, "未启用无障碍，无法执行返回");
            return false;
        }
        final int attempts = Math.max(1, Math.min(maxAttempts, 4));
        instance.handler.post(new Runnable() {
            private int remaining = attempts;
            @Override
            public void run() {
                if (remaining <= 0) return;
                boolean sent = performGlobalAction(GLOBAL_ACTION_BACK);
                Log.d(TAG, "主动执行返回，剩余次数=" + remaining + "，结果=" + sent);
                remaining--;
                if (remaining > 0) {
                    instance.handler.postDelayed(this, 600L);
                }
            }
        });
        return true;
    }
    public static boolean requestReturnOnNextClientWindow() {
        if (instance == null) {
            returnOnNextClientWindow = false;
            Log.d(TAG, "未启用无障碍，无法自动返回原页面");
            return false;
        }
        returnOnNextClientWindow = true;
        Log.d(TAG, "已标记：广东校园窗口出现后立即返回");
        return true;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 0;
        info.packageNames = new String[]{CLIENT_PACKAGE};
        setServiceInfo(info);
        Log.d(TAG, "无障碍服务已连接");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!returnOnNextClientWindow || event.getPackageName() == null) {
            return;
        }
        if (!CLIENT_PACKAGE.equals(event.getPackageName().toString())) {
            return;
        }

        returnOnNextClientWindow = false;
        long now = System.currentTimeMillis();
        if (now - lastReturnAt < 500L) {
            return;
        }
        lastReturnAt = now;

        // 不设置固定等待时间，窗口事件到达后立即返回。
        handler.post(new Runnable() {
            @Override
            public void run() {
                boolean sent = performGlobalAction(GLOBAL_ACTION_BACK);
                Log.d(TAG, "已立即返回原页面，结果=" + sent);
            }
        });
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "无障碍服务被系统中断");
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        returnOnNextClientWindow = false;
        handler.removeCallbacksAndMessages(null);
        Log.d(TAG, "无障碍服务已停止");
        super.onDestroy();
    }
}
