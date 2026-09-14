package com.wyu.esurfing;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * AlarmManager receiver.
 * All alarms are forwarded to MonitorService.
 */
public class MonitorAlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action != null && action.startsWith("com.wyu.esurfing.action.")) {
            MonitorService.handleAlarm(context, action);
        }
    }
}
