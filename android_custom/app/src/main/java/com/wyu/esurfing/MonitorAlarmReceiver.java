package com.wyu.esurfing;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * AlarmManager 的轻量桥接器。
 * 使用省电唤醒闹钟，避免屏幕熄灭后普通 Handler 被 Doze 延迟。
 */
public class MonitorAlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (MonitorService.ACTION_FIRST_RESET.equals(action)
                || MonitorService.ACTION_PERIODIC_RESET.equals(action)) {
            MonitorService.handleAlarm(context, action);
        }
    }
}
