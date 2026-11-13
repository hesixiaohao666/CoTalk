package com.example.cotalkproject.util;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.example.cotalkproject.MainActivity;
import com.example.cotalkproject.R;
import com.example.cotalkproject.ui.chat.ChatActivity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 前台服务常驻通知与本地消息通知。
 */
public final class NotificationHelper {

    public static final String CHANNEL_BACKGROUND = "cotalk_background";
    /**
     * 使用新的渠道 ID：Android 不允许应用在渠道创建后提升重要性，
     * 升级时必须新建渠道，才能让“悬浮通知”默认生效。
     */
    public static final String CHANNEL_MESSAGES = "cotalk_messages_v2";
    private static final String LEGACY_CHANNEL_MESSAGES = "cotalk_messages";

    public static final int PERSISTENT_NOTIFICATION_ID = 1001;
    private static final int MESSAGE_NOTIFICATION_BASE = 2000;
    private static final int MAX_LINES = 5;

    private static final Map<Long, List<String>> MESSAGE_LINES =
            new LinkedHashMap<Long, List<String>>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, List<String>> eldest) {
                    return size() > 32;
                }
            };

    private static volatile boolean persistentActive;

    private NotificationHelper() {
    }

    public static void createChannels(Context context) {
        NotificationManager manager =
                context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }

        NotificationChannel background = new NotificationChannel(
                CHANNEL_BACKGROUND,
                context.getString(R.string.notification_channel_background),
                NotificationManager.IMPORTANCE_LOW);
        background.setDescription(context.getString(
                R.string.notification_channel_background_desc));
        background.setShowBadge(false);
        background.enableVibration(false);
        background.setSound(null, null);
        manager.createNotificationChannel(background);

        // 删除旧的低重要性渠道，避免设置里出现两个“CoTalk 消息通知”。
        manager.deleteNotificationChannel(LEGACY_CHANNEL_MESSAGES);
        NotificationChannel messages = new NotificationChannel(
                CHANNEL_MESSAGES,
                context.getString(R.string.notification_channel_messages),
                NotificationManager.IMPORTANCE_HIGH);
        messages.setDescription(context.getString(
                R.string.notification_channel_messages_desc));
        messages.setShowBadge(true);
        messages.enableVibration(true);
        messages.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        manager.createNotificationChannel(messages);
    }

    public static Notification buildPersistent(Context context, String roomName) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context,
                PERSISTENT_NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);

        String text = roomName == null || roomName.trim().isEmpty()
                ? context.getString(R.string.background_notification_text)
                : context.getString(
                        R.string.background_notification_room_format,
                        roomName.trim());
        return new NotificationCompat.Builder(context, CHANNEL_BACKGROUND)
                .setSmallIcon(R.drawable.ic_stat_cotalk)
                .setColor(context.getColor(R.color.blue_ty))
                .setContentTitle(context.getString(
                        R.string.background_notification_title))
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    public static void markPersistentActive(boolean active) {
        persistentActive = active;
    }

    @SuppressLint("MissingPermission")
    public static void updatePersistent(Context context, String roomName) {
        if (!persistentActive || !canPostNotifications(context)) {
            return;
        }
        try {
            NotificationManagerCompat.from(context).notify(
                    PERSISTENT_NOTIFICATION_ID,
                    buildPersistent(context, roomName));
        } catch (RuntimeException ignored) {
            // 厂商 ROM 的通知限制不应影响连接本身。
        }
    }

    public static void hidePersistent(Context context) {
        persistentActive = false;
        NotificationManagerCompat.from(context).cancel(PERSISTENT_NOTIFICATION_ID);
    }

    /**
     * 同一房间复用同一个通知 ID，并在展开视图中聚合最近几条消息。
     */
    @SuppressLint("MissingPermission")
    public static void notifyMessage(Context context,
                                     long sessionId,
                                     String roomName,
                                     String sender,
                                     String preview) {
        if (!canPostNotifications(context)
                || !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return;
        }
        String room = roomName == null ? "" : roomName.trim();
        if (room.isEmpty()) {
            room = context.getString(R.string.app_name);
        }
        String who = sender == null ? "" : sender.trim();
        String body = preview == null ? "" : preview;
        String line = who.isEmpty()
                ? body
                : context.getString(
                        R.string.message_notification_line_format, who, body);

        List<String> lines;
        synchronized (MESSAGE_LINES) {
            List<String> stored = MESSAGE_LINES.get(sessionId);
            if (stored == null) {
                stored = new ArrayList<>();
                MESSAGE_LINES.put(sessionId, stored);
            }
            stored.add(line);
            while (stored.size() > MAX_LINES) {
                stored.remove(0);
            }
            lines = new ArrayList<>(stored);
        }

        NotificationCompat.InboxStyle style =
                new NotificationCompat.InboxStyle()
                        .setBigContentTitle(room);
        for (String item : lines) {
            style.addLine(item);
        }
        if (lines.size() > 1) {
            style.setSummaryText(context.getString(
                    R.string.message_notification_summary, lines.size()));
        }

        Intent intent = ChatActivity.createIntent(context, sessionId, room);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int notificationId = messageNotificationId(sessionId);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);

        Notification notification =
                new NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                        .setSmallIcon(R.drawable.ic_stat_cotalk)
                        .setColor(context.getColor(R.color.blue_ty))
                        .setContentTitle(room)
                        .setContentText(line)
                        .setStyle(style)
                        .setContentIntent(contentIntent)
                        .setAutoCancel(true)
                        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setDefaults(NotificationCompat.DEFAULT_ALL)
                        .build();
        try {
            NotificationManagerCompat.from(context).notify(
                    notificationId, notification);
        } catch (RuntimeException ignored) {
            // 通知失败不能阻断消息入库与聊天页显示。
        }
    }

    public static void cancelMessage(Context context, long sessionId) {
        synchronized (MESSAGE_LINES) {
            MESSAGE_LINES.remove(sessionId);
        }
        NotificationManagerCompat.from(context).cancel(
                messageNotificationId(sessionId));
    }

    private static int messageNotificationId(long sessionId) {
        return MESSAGE_NOTIFICATION_BASE + (int) Math.abs(sessionId % 10000);
    }

    private static boolean canPostNotifications(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }
}
