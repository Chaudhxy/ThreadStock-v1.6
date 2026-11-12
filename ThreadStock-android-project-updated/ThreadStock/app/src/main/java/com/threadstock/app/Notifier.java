package com.threadstock.app;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Notifications for ThreadStock.
 *  - post(): shows one notification in the phone's notification bar (used for sales made on other devices).
 *  - The morning alarm: the page hands over a small list (customers with due dates, suppliers we owe) and the hour.
 *    Android wakes this class once each morning, even when the app is closed, and it shows the reminders.
 */
public final class Notifier {
    static final String CHANNEL = "threadstock_alerts";
    static final String PREFS = "ts_reminders";
    static final String ACTION_MORNING = "com.threadstock.app.MORNING";

    private Notifier() { }

    static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "ThreadStock alerts", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("Sales on other devices, customer due dates and supplier payments");
                nm.createNotificationChannel(ch);
            }
        }
    }

    static boolean allowed(Context c) {
        if (Build.VERSION.SDK_INT >= 33) {
            return c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    static void post(Context c, int id, String title, String body) {
        if (!allowed(c)) return;
        ensureChannel(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Intent open = new Intent(c, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_bell)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi);
        try {
            nm.notify(id, b.build());
        } catch (SecurityException ignored) { }
    }

    /** Stores what the page sent and (re)schedules the next morning alarm. */
    static void save(Context c, String json) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("data", json).apply();
        schedule(c);
    }

    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(c, ReminderReceiver.class).setAction(ACTION_MORNING);
        PendingIntent pi = PendingIntent.getBroadcast(c, 77, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            JSONObject d = new JSONObject(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("data", "{}"));
            if (!d.optBoolean("on", false)) {
                am.cancel(pi);
                return;
            }
            int hour = Math.max(0, Math.min(23, d.optInt("hour", 9)));
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, hour);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            if (cal.getTimeInMillis() <= System.currentTimeMillis() + 60000L) cal.add(Calendar.DAY_OF_YEAR, 1);
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        } catch (Exception ignored) { }
    }

    private static String names(JSONArray a, java.util.List<Integer> idx) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < idx.size() && k < 3; k++) {
            if (k > 0) sb.append(", ");
            sb.append(a.optJSONObject(idx.get(k)).optString("n"));
        }
        if (idx.size() > 3) sb.append(" +").append(idx.size() - 3);
        return sb.toString();
    }

    private static String money(String cur, double v) {
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
        return (cur == null || cur.isEmpty() ? "" : cur + " ") + nf.format(Math.round(v));
    }

    /** Builds and shows the morning reminders from the stored list. */
    static void morning(Context c) {
        try {
            JSONObject d = new JSONObject(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("data", "{}"));
            if (!d.optBoolean("on", false)) return;
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            String cur = d.optString("cur", "");
            JSONObject tx = d.optJSONObject("tx");
            if (tx == null) return;
            if (d.optBoolean("due", false)) {
                JSONArray cs = d.optJSONArray("c");
                java.util.List<Integer> dueToday = new java.util.ArrayList<>();
                java.util.List<Integer> over = new java.util.ArrayList<>();
                double overSum = 0;
                if (cs != null) {
                    for (int k = 0; k < cs.length(); k++) {
                        JSONObject o = cs.getJSONObject(k);
                        String dd = o.optString("d", "");
                        if (dd.isEmpty()) continue;
                        int cmp = dd.compareTo(today);
                        if (cmp == 0) dueToday.add(k);
                        else if (cmp < 0) { over.add(k); overSum += o.optDouble("a", 0); }
                    }
                }
                if (!dueToday.isEmpty() || !over.isEmpty()) {
                    StringBuilder body = new StringBuilder();
                    if (!dueToday.isEmpty()) body.append(tx.optString("dueToday").replace("{names}", names(cs, dueToday)));
                    if (!over.isEmpty()) {
                        if (body.length() > 0) body.append(". ");
                        body.append(tx.optString("over").replace("{n}", String.valueOf(over.size())).replace("{amt}", money(cur, overSum)));
                    }
                    post(c, 9101, tx.optString("dueTitle"), body.toString());
                }
            }
            if (d.optBoolean("sup", false)) {
                JSONArray ss = d.optJSONArray("s");
                if (ss != null && ss.length() > 0) {
                    double sum = 0;
                    java.util.List<Integer> all = new java.util.ArrayList<>();
                    for (int k = 0; k < ss.length(); k++) { sum += ss.getJSONObject(k).optDouble("a", 0); all.add(k); }
                    String body = tx.optString("sup").replace("{amt}", money(cur, sum)).replace("{n}", String.valueOf(ss.length())).replace("{names}", names(ss, all));
                    post(c, 9102, tx.optString("supTitle"), body);
                }
            }
        } catch (Exception ignored) { }
    }
}
