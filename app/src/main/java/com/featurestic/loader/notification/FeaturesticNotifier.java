package com.featurestic.loader.notification;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.featurestic.loader.R;
import com.featurestic.loader.ui.MainActivity;

/**
 * Handles System Push Notifications for:
 * 1. Loader In-App Update Alerts (Status bar heads-up push alert)
 * 2. System Broadcast Announcements from the Admin Panel
 */
public final class FeaturesticNotifier {

    private static final String TAG = "FeaturesticNotifier";

    public static final String CHANNEL_UPDATES = "channel_loader_updates";
    public static final String CHANNEL_BROADCASTS = "channel_system_broadcasts";

    public static final int NOTIFICATION_ID_UPDATE = 1001;
    public static final int NOTIFICATION_ID_BROADCAST_BASE = 2000;

    public static final String EXTRA_ACTION_UPDATE = "extra_action_update";
    public static final String EXTRA_BROADCAST_MSG = "extra_broadcast_msg";
    public static final String EXTRA_LATEST_VER = "extra_latest_ver";
    public static final String EXTRA_APK_URL = "extra_apk_url";
    public static final String EXTRA_CHANGELOG = "extra_changelog";

    private static final String PREF_NOTIF = "featurestic_broadcast_prefs";
    private static final String KEY_LAST_SEEN_BROADCAST_ID = "last_seen_broadcast_id";
    private static final String KEY_LAST_SEEN_BROADCAST_MSG = "last_seen_broadcast_msg";

    private FeaturesticNotifier() {}

    /**
     * Initializes notification channels for Android 8.0+ (API 26+)
     */
    public static void initChannels(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            // Channel 1: Loader Updates (High Priority)
            NotificationChannel updateChannel = new NotificationChannel(
                    CHANNEL_UPDATES,
                    "Loader Updates",
                    NotificationManager.IMPORTANCE_HIGH
            );
            updateChannel.setDescription("Notifications when a new Loader version is published");
            updateChannel.enableLights(true);
            updateChannel.setLightColor(Color.BLUE);
            updateChannel.enableVibration(true);
            updateChannel.setShowBadge(true);
            nm.createNotificationChannel(updateChannel);

            // Channel 2: System Broadcast Announcements (High Priority)
            NotificationChannel broadcastChannel = new NotificationChannel(
                    CHANNEL_BROADCASTS,
                    "System Announcements",
                    NotificationManager.IMPORTANCE_HIGH
            );
            broadcastChannel.setDescription("Important broadcast notices and announcements from administrator");
            broadcastChannel.enableLights(true);
            broadcastChannel.setLightColor(Color.RED);
            broadcastChannel.enableVibration(true);
            broadcastChannel.setShowBadge(true);
            nm.createNotificationChannel(broadcastChannel);
        } catch (Throwable t) {
            Log.e(TAG, "initChannels error: " + t.getMessage());
        }
    }

    /**
     * Shows a system notification in status bar when a new loader update is available.
     */
    public static void showUpdateNotification(Context context, String latestVer, String apkUrl, String changelog) {
        if (context == null) return;
        try {
            initChannels(context);

            Intent intent = new Intent(context, MainActivity.class);
            intent.setAction(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra(EXTRA_ACTION_UPDATE, true);
            intent.putExtra(EXTRA_LATEST_VER, latestVer != null ? latestVer : "");
            intent.putExtra(EXTRA_APK_URL, apkUrl != null ? apkUrl : "");
            intent.putExtra(EXTRA_CHANGELOG, changelog != null ? changelog : "");

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pendingIntent = PendingIntent.getActivity(context, 101, intent, flags);

            String title = "🚀 New Loader Update Available (v" + latestVer + ")";
            String summary = "Loader v" + latestVer + " is ready with new features & security updates. Tap to install!";

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_UPDATES)
                    .setSmallIcon(R.drawable.ic_bell)
                    .setContentTitle(title)
                    .setContentText(summary)
                    .setStyle(new NotificationCompat.BigTextStyle()
                            .setBigContentTitle(title)
                            .bigText(summary + (changelog != null && !changelog.isEmpty() ? "\n\nChanges:\n" + changelog : ""))
                    )
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setAutoCancel(true)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setContentIntent(pendingIntent);

            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_UPDATE, builder.build());
            Log.i(TAG, "Update notification displayed for v" + latestVer);
        } catch (Throwable t) {
            Log.e(TAG, "showUpdateNotification error: " + t.getMessage());
        }
    }

    /**
     * Shows a system broadcast notification in status bar when an admin announcement is sent.
     */
    public static void showBroadcastNotification(Context context, String message, int noticeId) {
        if (context == null || message == null || message.trim().isEmpty()) return;
        try {
            initChannels(context);

            Intent intent = new Intent(context, MainActivity.class);
            intent.setAction(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra(EXTRA_BROADCAST_MSG, message);

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pendingIntent = PendingIntent.getActivity(context, 202, intent, flags);

            String title = "📢 System Announcement";

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_BROADCASTS)
                    .setSmallIcon(R.drawable.ic_bell)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setStyle(new NotificationCompat.BigTextStyle()
                            .setBigContentTitle(title)
                            .bigText(message)
                    )
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setAutoCancel(true)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setContentIntent(pendingIntent);

            int notifId = NOTIFICATION_ID_BROADCAST_BASE + (noticeId % 100);
            NotificationManagerCompat.from(context).notify(notifId, builder.build());
            Log.i(TAG, "Broadcast announcement notification displayed: " + message);
        } catch (Throwable t) {
            Log.e(TAG, "showBroadcastNotification error: " + t.getMessage());
        }
    }

    /**
     * Checks if this broadcast message is newly published and hasn't been shown yet.
     * If new, triggers system notification and returns true.
     */
    public static boolean checkAndNotifyBroadcast(Context context, String noticeMessage, int noticeId) {
        if (context == null || noticeMessage == null || noticeMessage.trim().isEmpty()) return false;
        String cleanMsg = noticeMessage.trim();
        SharedPreferences sp = context.getSharedPreferences(PREF_NOTIF, Context.MODE_PRIVATE);
        int lastId = sp.getInt(KEY_LAST_SEEN_BROADCAST_ID, -1);
        String lastMsg = sp.getString(KEY_LAST_SEEN_BROADCAST_MSG, "");

        boolean isNew = (noticeId > 0 && noticeId > lastId) || (!cleanMsg.equals(lastMsg));
        if (isNew) {
            sp.edit()
                    .putInt(KEY_LAST_SEEN_BROADCAST_ID, noticeId)
                    .putString(KEY_LAST_SEEN_BROADCAST_MSG, cleanMsg)
                    .apply();
            showBroadcastNotification(context, cleanMsg, noticeId);
            return true;
        }
        return false;
    }
}
