package org.exomind.androbody.nativeapp;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Posts and cancels only the isolated AndroidTest package's synthetic notification. */
public final class NotificationFixtureReceiver extends BroadcastReceiver {
    public static final String ACTION_POST = "org.exomind.andromorph.qa.POST_NOTIFICATION_FIXTURE";
    public static final String ACTION_CANCEL = "org.exomind.andromorph.qa.CANCEL_NOTIFICATION_FIXTURE";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_BODY = "body";
    public static final String EXTRA_NOTIFICATION_ID = "notificationId";
    public static final int NOTIFICATION_ID = 91_731;
    private static final String CHANNEL_ID = "andromorph-notification-read-fixture";

    @Override public void onReceive(Context context, Intent intent) {
        int notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1);
        if (notificationId < 0) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (ACTION_POST.equals(intent.getAction())) {
            String title = intent.getStringExtra(EXTRA_TITLE);
            String body = intent.getStringExtra(EXTRA_BODY);
            if (title == null || body == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID, "QA notification read", NotificationManager.IMPORTANCE_DEFAULT));
            }
            manager.notify(notificationId, new Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setAutoCancel(false)
                    .build());
            boolean enabled = manager.areNotificationsEnabled();
            setResultData("notificationsEnabled=" + enabled + ";postRequested=true");
            setResultCode(enabled ? Activity.RESULT_OK : Activity.RESULT_CANCELED);
        } else if (ACTION_CANCEL.equals(intent.getAction())) {
            manager.cancel(notificationId);
            long deadline = android.os.SystemClock.elapsedRealtime() + 2_000;
            boolean stillActive;
            do {
                stillActive = false;
                for (android.service.notification.StatusBarNotification active : manager.getActiveNotifications()) {
                    if (active.getId() == notificationId) {
                        stillActive = true;
                        break;
                    }
                }
                if (stillActive) android.os.SystemClock.sleep(50);
            } while (stillActive && android.os.SystemClock.elapsedRealtime() < deadline);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.deleteNotificationChannel(CHANNEL_ID);
            setResultData("cancelled=true;stillActive=" + stillActive);
            setResultCode(stillActive ? Activity.RESULT_CANCELED : Activity.RESULT_OK);
        }
    }
}
