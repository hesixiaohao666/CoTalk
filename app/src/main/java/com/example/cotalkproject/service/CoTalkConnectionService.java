package com.example.cotalkproject.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.example.cotalkproject.CoTalkApp;
import com.example.cotalkproject.data.Prefs;
import com.example.cotalkproject.util.NotificationHelper;

/**
 * 后台常驻前台服务：只负责保持进程与常驻通知，TCP 连接仍由 SessionManager 持有。
 */
public class CoTalkConnectionService extends Service {

    public static void start(Context context) {
        if (context == null) {
            return;
        }
        try {
            ContextCompat.startForegroundService(
                    context, new Intent(context, CoTalkConnectionService.class));
        } catch (RuntimeException ignored) {
            // 系统限制后台启动前台服务时保持静默，用户可在应用内重试。
        }
    }

    public static void stop(Context context) {
        if (context == null) {
            return;
        }
        context.stopService(new Intent(context, CoTalkConnectionService.class));
    }

    public static void sync(Context context, Prefs prefs) {
        if (prefs != null && prefs.isBackgroundResident()) {
            start(context);
        } else {
            stop(context);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationHelper.createChannels(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        CoTalkApp app = CoTalkApp.getInstance();
        String roomName = app == null
                ? ""
                : app.getSessionManager().getActiveRoomName();
        NotificationHelper.markPersistentActive(true);
        startForeground(NotificationHelper.PERSISTENT_NOTIFICATION_ID,
                NotificationHelper.buildPersistent(this, roomName));
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        NotificationHelper.markPersistentActive(false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
