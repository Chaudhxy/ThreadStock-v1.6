package com.threadstock.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fires every morning (and after the phone restarts) to show due-date and supplier reminders. */
public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (Notifier.ACTION_MORNING.equals(a)) Notifier.morning(context);
        Notifier.schedule(context); // plan tomorrow's alarm (also done after a restart)
    }
}
