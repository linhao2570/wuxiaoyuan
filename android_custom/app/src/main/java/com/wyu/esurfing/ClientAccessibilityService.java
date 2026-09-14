package com.wyu.esurfing;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 广东校园无障碍辅助服务。
 *
 * 核心功能：在熄屏重置流程中，可靠地点击"断开网络"按钮。
 *
 * 点击策略（按优先级）：
 *   1. findAccessibilityNodeInfosByText 精确查找 -> performAction 点击
 *   2. 递归遍历所有节点，模糊匹配文字 -> performAction 点击
 *   3. 递归查找所有可点击节点，逐个点击测试（暴力兜底）
 *   4. 按预估坐标 dispatchGesture 点击（最后手段）
 *
 * 注意：熄屏时 dispatchGesture 可能无效（触摸控制器断电），
 *      所以优先使用 performAction（系统注入，不依赖触摸硬件）。
 */
public class ClientAccessibilityService extends AccessibilityService {

    private static void logAccess(String msg) {
        Log.d(TAG, msg);
        try {
            java.lang.reflect.Method m = Class.forName("com.wyu.esurfing.MonitorService")
                    .getMethod("logAccessEvent", String.class);
            m.invoke(null, msg);
        } catch (Exception e) {
            // 忽略
        }
    }

    public static final String CLIENT_PACKAGE = "com.cndatacom.campus.cdccportalgd";
    private static final String TAG = "aiqin-access";

    // 目标按钮文字（多个候选，提高匹配率）
    private static final String[] DISCONNECT_TEXTS = {
            "断开网络", "断开连接", "断网", "断开", "退出登录"
    };

    private static volatile ClientAccessibilityService instance;

    private static final int STATE_IDLE = 0;
    private static final int STATE_WAIT_DISCONNECT = 1;

    private int flowState = STATE_IDLE;
    private long flowStartedAt;
    private int clickAttempts = 0;
    private static final int MAX_CLICK_ATTEMPTS = 10;
    private static final long CLICK_INTERVAL_MS = 1200L;
    private static final long FLOW_TIMEOUT_MS = 20_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    public static boolean isRunning() {
        return instance != null;
    }

    /**
     * 启动断开网络点击流程。
     * 成功或超时都会回调 MonitorService.onDisconnectClicked()。
     */
    public static boolean startDisconnectOnly() {
        ClientAccessibilityService svc = instance;
        if (svc == null) {
            Log.d(TAG, "无障碍未运行");
            return false;
        }
        svc.handler.post(new Runnable() {
            @Override
            public void run() {
                svc.flowState = STATE_WAIT_DISCONNECT;
                svc.flowStartedAt = System.currentTimeMillis();
                svc.clickAttempts = 0;
                svc.handler.removeCallbacks(svc.flowTimeoutRunnable);
                svc.handler.removeCallbacks(svc.clickRunnable);
                svc.handler.post(svc.clickRunnable);
                svc.handler.postDelayed(svc.flowTimeoutRunnable, FLOW_TIMEOUT_MS);
                Log.d(TAG, "启动断开流程，最多尝试 " + MAX_CLICK_ATTEMPTS + " 次");
                logAccess("启动断开网络流程");
            }
        });
        return true;
    }

    public static void cancelReloginFlow() {
        ClientAccessibilityService svc = instance;
        if (svc == null) return;
        svc.handler.post(new Runnable() {
            @Override
            public void run() {
                if (svc.flowState != STATE_IDLE) {
                    Log.d(TAG, "断开流程已取消");
                    svc.flowState = STATE_IDLE;
                    svc.handler.removeCallbacks(svc.clickRunnable);
                    svc.handler.removeCallbacks(svc.flowTimeoutRunnable);
                }
            }
        });
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        info.packageNames = new String[]{CLIENT_PACKAGE};
        setServiceInfo(info);
        Log.d(TAG, "无障碍服务已连接");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 事件仅作参考，点击逻辑由定时循环驱动，更可靠
    }

    /**
     * 点击任务：每 1.2 秒尝试一次点击"断开网络"。
     */
    private final Runnable clickRunnable = new Runnable() {
        @Override
        public void run() {
            if (flowState != STATE_WAIT_DISCONNECT) return;
            if (clickAttempts >= MAX_CLICK_ATTEMPTS) {
                Log.d(TAG, "已达最大点击次数，回调 MonitorService");
                finishFlow();
                return;
            }

            clickAttempts++;
            boolean clicked = tryClickDisconnect();
            Log.d(TAG, "第 " + clickAttempts + " 次尝试，结果=" + clicked);
            logAccess("第" + clickAttempts + "次尝试=" + (clicked ? "成功" : "失败"));

            if (clicked) {
                // 点到了，等 2 秒确认页面变化，然后回调
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (flowState != STATE_WAIT_DISCONNECT) return;
                        Log.d(TAG, "断开网络已点击，回调 MonitorService");
                        logAccess("已点击断开网络，开始重启");
                        finishFlow();
                    }
                }, 2000L);
                return;
            }

            handler.postDelayed(this, CLICK_INTERVAL_MS);
        }
    };

    private final Runnable flowTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (flowState == STATE_IDLE) return;
            Log.d(TAG, "断开流程超时，兜底回调");
            logAccess("点击超时，兜底杀进程重启");
            finishFlow();
        }
    };

    private void finishFlow() {
        flowState = STATE_IDLE;
        handler.removeCallbacks(clickRunnable);
        handler.removeCallbacks(flowTimeoutRunnable);
        MonitorService.onDisconnectClicked();
    }

    /**
     * 四层策略点击"断开网络"：
     *   1. findAccessibilityNodeInfosByText 精确查找 + performAction
     *   2. 递归遍历，模糊匹配文字 + performAction
     *   3. 所有可点击节点中找包含目标文字的 + performAction
     *   4. 坐标手势点击（兜底）
     */
    private boolean tryClickDisconnect() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            Log.d(TAG, "无法获取根节点，尝试坐标兜底");
            return clickByEstimatedPosition();
        }

        // 检查是否是广东校园的窗口
        CharSequence rootPkg = root.getPackageName();
        if (rootPkg != null && !CLIENT_PACKAGE.equals(rootPkg.toString())) {
            Log.d(TAG, "当前窗口不是广东校园: " + rootPkg);
            // 不是目标窗口也试试坐标，万一在前台呢
            return clickByEstimatedPosition();
        }

        // 方式 1：按文字精确查找
        for (String target : DISCONNECT_TEXTS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(target);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo node : nodes) {
                    // 熄屏时 isVisibleToUser 可能返回 false，所以不检查
                    if (clickNodeByAction(node)) {
                        Log.d(TAG, "方式1成功: findAccessibilityNodeInfosByText(" + target + ")");
                        logAccess("文字匹配成功:" + target);
                        return true;
                    }
                }
            }
        }

        // 方式 2：递归遍历所有节点，模糊匹配文字
        List<AccessibilityNodeInfo> allNodes = new ArrayList<>();
        collectAllNodes(root, allNodes);
        Log.d(TAG, "遍历到 " + allNodes.size() + " 个节点");

        for (AccessibilityNodeInfo node : allNodes) {
            for (String target : DISCONNECT_TEXTS) {
                if (textContains(node, target)) {
                    if (clickNodeByAction(node)) {
                        Log.d(TAG, "方式2成功: 递归遍历匹配到 " + target);
                        logAccess("递归匹配成功:" + target);
                        return true;
                    }
                    // 节点本身不可点击，试试父节点
                    AccessibilityNodeInfo parent = node.getParent();
                    int depth = 0;
                    while (parent != null && depth < 5) {
                        if (parent.isClickable() && parent.isEnabled()) {
                            boolean ok = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            if (ok) {
                                Log.d(TAG, "方式2成功: 父节点点击 " + target + " depth=" + depth);
                                logAccess("父节点点击成功:" + target);
                                return true;
                            }
                        }
                        parent = parent.getParent();
                        depth++;
                    }
                }
            }
        }

        // 方式 3：收集所有可点击节点，逐个检查文字/描述
        List<AccessibilityNodeInfo> clickables = new ArrayList<>();
        collectClickableNodes(root, clickables);
        Log.d(TAG, "找到 " + clickables.size() + " 个可点击节点");
        for (AccessibilityNodeInfo node : clickables) {
            CharSequence t = node.getText();
            CharSequence d = node.getContentDescription();
            String ts = t != null ? t.toString() : "";
            String ds = d != null ? d.toString() : "";
            for (String target : DISCONNECT_TEXTS) {
                if (ts.contains(target) || ds.contains(target)) {
                    boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    if (ok) {
                        Log.d(TAG, "方式3成功: 可点击节点匹配 " + target);
                        logAccess("可点击节点匹配:" + target);
                        return true;
                    }
                }
            }
            // 检查子节点文字（Button 里包 TextView 的情况）
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    CharSequence ct = child.getText();
                    CharSequence cd = child.getContentDescription();
                    String cts = ct != null ? ct.toString() : "";
                    String cds = cd != null ? cd.toString() : "";
                    for (String target : DISCONNECT_TEXTS) {
                        if (cts.contains(target) || cds.contains(target)) {
                            boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            if (ok) {
                                Log.d(TAG, "方式3成功: 子节点文字匹配 " + target);
                                logAccess("子节点匹配点击成功:" + target);
                                return true;
                            }
                        }
                    }
                }
            }
        }

        // 打印前 5 个可点击节点的信息（调试用）
        int debugCount = 0;
        for (AccessibilityNodeInfo node : clickables) {
            if (debugCount >= 5) break;
            CharSequence t = node.getText();
            CharSequence d = node.getContentDescription();
            String cn = node.getClassName() != null ? node.getClassName().toString() : "?";
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            Log.d(TAG, "可点击节点[" + debugCount + "]: " + cn
                    + " text=" + (t != null ? t : "null")
                    + " desc=" + (d != null ? d : "null")
                    + " bounds=" + r);
            debugCount++;
        }

        // 方式 4：坐标兜底
        Log.d(TAG, "所有文字匹配失败，用坐标兜底");
        logAccess("文字匹配失败，用坐标兜底");
        return clickByEstimatedPosition();
    }

    /**
     * 收集所有节点（不限是否可点击）。
     */
    private void collectAllNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectAllNodes(child, out);
            }
        }
    }

    /**
     * 收集所有可点击节点。
     */
    private void collectClickableNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        if (node.isClickable() && node.isEnabled()) {
            out.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectClickableNodes(child, out);
            }
        }
    }

    /**
     * 检查节点文字是否包含目标字符串（text + contentDescription + 子节点）。
     */
    private boolean textContains(AccessibilityNodeInfo node, String target) {
        CharSequence text = node.getText();
        if (text != null && text.toString().contains(target)) {
            return true;
        }
        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.toString().contains(target)) {
            return true;
        }
        return false;
    }

    /**
     * 通过 performAction 点击节点（优先方式，熄屏时也能工作）。
     * 先节点本身，再向上找 5 层父节点。
     */
    private boolean clickNodeByAction(AccessibilityNodeInfo node) {
        if (node == null) return false;
        // 节点本身可点击
        if (node.isClickable() && node.isEnabled()) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        // 向上找可点击的父节点（文字在 Button 内的 TextView 上时需要）
        AccessibilityNodeInfo parent = node.getParent();
        int depth = 0;
        while (parent != null && depth < 5) {
            if (parent.isClickable() && parent.isEnabled()) {
                boolean ok = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (ok) return true;
            }
            parent = parent.getParent();
            depth++;
        }
        return false;
    }

    /**
     * 预估"断开网络"按钮位置，dispatchGesture 点击。
     * 熄屏时可能无效，作为最后兜底。
     */
    private boolean clickByEstimatedPosition() {
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int width = dm.widthPixels;
            int height = dm.heightPixels;
            // 多个候选位置，按尝试次数轮流
            // 从截图看按钮在大圆内部下方，约屏幕高度 28%~45%
            float[] yRatios = {
                    0.30f, 0.33f, 0.36f, 0.39f, 0.42f,
                    0.28f, 0.35f, 0.45f, 0.32f, 0.38f
            };
            int idx = clickAttempts % yRatios.length;
            float yRatio = yRatios[idx];
            int x = width / 2;
            int y = (int) (height * yRatio);
            Log.d(TAG, "坐标点击(" + idx + "): " + x + "," + y + " 屏幕" + width + "x" + height);
            logAccess("坐标点击: " + x + "," + y);
            return clickAt(x, y);
        } catch (Exception e) {
            Log.d(TAG, "坐标点击异常: " + e.getMessage());
            return false;
        }
    }

    /**
     * 通过 GestureDescription 发送精确坐标点击。
     */
    private boolean clickAt(int x, int y) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
            return false;
        }
        try {
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(x, y);
            android.accessibilityservice.GestureDescription.Builder builder =
                    new android.accessibilityservice.GestureDescription.Builder();
            builder.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(
                    path, 0, 120));
            return dispatchGesture(builder.build(), null, null);
        } catch (Exception e) {
            Log.d(TAG, "dispatchGesture 失败: " + e.getMessage());
            return false;
        }
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
        flowState = STATE_IDLE;
        handler.removeCallbacksAndMessages(null);
        Log.d(TAG, "无障碍服务已停止");
        super.onDestroy();
    }
}
