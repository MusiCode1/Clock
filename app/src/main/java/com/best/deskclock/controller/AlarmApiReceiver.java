// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.controller;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.best.deskclock.DeskClockApplication;
import com.best.deskclock.alarms.AlarmStateManager;
import com.best.deskclock.data.DataModel;
import com.best.deskclock.provider.Alarm;
import com.best.deskclock.provider.AlarmInstance;
import com.best.deskclock.utils.LogUtils;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Headless alarm operations addressed by label, with no UI and no activity.
 *
 * <p>{@link HandleApiCalls} is the Android {@code ACTION_SET_ALARM} /
 * {@code DISMISS_ALARM} entry point, but it is an Activity: even with
 * {@code SKIP_UI} the dismiss path calls {@code startActivity(DeskClock)}.
 * This receiver is a {@link BroadcastReceiver} instead, so an automation
 * caller (e.g. {@code adb shell am broadcast}, uid 2000) can enable, disable
 * or delete an alarm by its label while the screen is locked and nothing
 * ever becomes visible.
 *
 * <p>It never creates an alarm: if no alarm matches the label it logs and
 * returns. Creation stays with {@code ACTION_SET_ALARM}. The label match is
 * exact equality against {@code Alarm.label}; callers that tag their alarms
 * (e.g. {@code "pa:ab12 Wake up"}) get a unique target.
 *
 * <p>Each outcome is logged as {@code "AlarmApi: <verb> alarm: " + alarm} so
 * an external caller can verify the result from logcat (tag
 * {@code AlarmApiReceiver}) rather than re-reading the UI.
 */
public class AlarmApiReceiver extends BroadcastReceiver {

    private static final LogUtils.Logger LOGGER = new LogUtils.Logger("AlarmApiReceiver");

    public static final String ACTION_ALARM_OP = "com.best.deskclock.action.ALARM_OP";
    public static final String EXTRA_LABEL = "com.best.deskclock.extra.ALARM_LABEL";
    public static final String EXTRA_OP = "com.best.deskclock.extra.ALARM_OP";

    public static final String OP_ENABLE = "enable";
    public static final String OP_DISABLE = "disable";
    public static final String OP_DELETE = "delete";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_ALARM_OP.equals(intent.getAction())) {
            return;
        }
        // 🔑 logged first and unconditionally — this is the caller's liveness
        //    proof that the broadcast reached the app, mirroring HandleApiCalls'
        //    "onCreate" line. Its absence means a dead channel, not "no alarm".
        LOGGER.i("onReceive: " + intent);

        final String label = intent.getStringExtra(EXTRA_LABEL);
        final String op = intent.getStringExtra(EXTRA_OP);

        if (label == null || label.isEmpty() || op == null) {
            LOGGER.i("AlarmApi: missing label or op; ignoring");
            return;
        }

        // DB + AlarmManager work must not block the main thread; goAsync keeps
        // the receiver alive on a worker thread until finish().
        final Context appContext = context.getApplicationContext();
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                handle(appContext, label, op);
            } catch (Throwable t) {
                LOGGER.e("AlarmApi: failed for label='" + label + "' op=" + op, t);
            } finally {
                pending.finish();
            }
        }, "AlarmApiReceiver").start();
    }

    private void handle(@NonNull Context context, @NonNull String label, @NonNull String op) {
        final ContentResolver cr = context.getContentResolver();
        final SharedPreferences prefs = DeskClockApplication.getDefaultSharedPreferences(context);

        final List<Alarm> matches = new ArrayList<>();
        for (Alarm a : Alarm.getAlarms(cr, null)) {
            if (label.equals(a.label)) {
                matches.add(a);
            }
        }

        if (matches.isEmpty()) {
            // 🛑 Not an error path that creates anything. "No alarm" is a real,
            //    reportable outcome — the caller distinguishes it from a dead
            //    channel by the presence of the onReceive line above.
            LOGGER.i("AlarmApi: no alarm for label='" + label + "'");
            return;
        }

        for (Alarm alarm : matches) {
            switch (op) {
                case OP_DELETE -> {
                    AlarmStateManager.deleteAllInstances(context, prefs, alarm.id);
                    Alarm.deleteAlarm(cr, alarm.id);
                    LOGGER.i("AlarmApi: deleted alarm: " + alarm);
                }
                case OP_DISABLE -> {
                    // Mirrors AlarmTimeClickHandler.setAlarmEnabled(false): flip
                    // the flag, persist, and drop the scheduled instance.
                    alarm.enabled = false;
                    alarm.updateAlarm(cr);
                    AlarmStateManager.deleteAllInstances(context, prefs, alarm.id);
                    LOGGER.i("AlarmApi: disabled alarm: " + alarm);
                }
                case OP_ENABLE -> {
                    alarm.enabled = true;
                    alarm.updateAlarm(cr);
                    // Rebuild the instance so it actually arms, same as
                    // HandleApiCalls.setupInstance does after a set.
                    AlarmStateManager.deleteAllInstances(context, prefs, alarm.id);
                    final Calendar now = DataModel.getDataModel().getCalendar();
                    final AlarmInstance instance = alarm.createInstanceAfter(now);
                    instance.addInstance(cr);
                    AlarmStateManager.registerInstance(context, prefs, instance, true);
                    LOGGER.i("AlarmApi: enabled alarm: " + alarm);
                }
                default -> LOGGER.i("AlarmApi: unknown op '" + op + "' for label='" + label + "'");
            }
        }
    }
}
