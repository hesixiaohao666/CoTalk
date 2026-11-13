package com.example.cotalkproject;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;

import com.example.cotalkproject.data.AppDatabase;
import com.example.cotalkproject.data.Prefs;
import com.example.cotalkproject.net.SessionManager;
import com.example.cotalkproject.util.AppForegroundTracker;
import com.example.cotalkproject.util.AvatarLoader;
import com.example.cotalkproject.util.NotificationHelper;

public class CoTalkApp extends Application {

    private static CoTalkApp instance;

    private AppDatabase database;
    private Prefs prefs;
    private SessionManager sessionManager;
    private AvatarLoader avatarLoader;
    private boolean authenticated;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        database = AppDatabase.getInstance(this);
        prefs = new Prefs(this);
        applyNightMode(prefs);
        sessionManager = new SessionManager(database, prefs);
        avatarLoader = new AvatarLoader(this, sessionManager);
        NotificationHelper.createChannels(this);
        registerActivityLifecycleCallbacks(new ForegroundCallbacks());
    }

    private static final class ForegroundCallbacks implements ActivityLifecycleCallbacks {

        @Override
        public void onActivityCreated(@NonNull Activity activity,
                                      @Nullable Bundle savedInstanceState) {
        }

        @Override
        public void onActivityStarted(@NonNull Activity activity) {
            AppForegroundTracker.onActivityStarted();
        }

        @Override
        public void onActivityResumed(@NonNull Activity activity) {
        }

        @Override
        public void onActivityPaused(@NonNull Activity activity) {
        }

        @Override
        public void onActivityStopped(@NonNull Activity activity) {
            AppForegroundTracker.onActivityStopped();
        }

        @Override
        public void onActivitySaveInstanceState(@NonNull Activity activity,
                                                @NonNull Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(@NonNull Activity activity) {
        }
    }

    public static void applyNightMode(Prefs prefs) {
        if (prefs.isDarkFollowSystem()) {
            AppCompatDelegate.setDefaultNightMode(
                    AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        } else if (prefs.isDarkMode()) {
            AppCompatDelegate.setDefaultNightMode(
                    AppCompatDelegate.MODE_NIGHT_YES);
        } else {
            AppCompatDelegate.setDefaultNightMode(
                    AppCompatDelegate.MODE_NIGHT_NO);
        }
    }

    public static CoTalkApp getInstance() {
        return instance;
    }

    public AppDatabase getDatabase() {
        return database;
    }

    public Prefs getPrefs() {
        return prefs;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public AvatarLoader getAvatarLoader() {
        return avatarLoader;
    }

    /**
     * 仅存在于进程内：切后台保持登录，进程重建后必须重新登录。
     */
    public boolean isAuthenticated() {
        return authenticated;
    }

    public void setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
    }
}
