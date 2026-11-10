package com.example.cotalkproject;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

import com.example.cotalkproject.data.AppDatabase;
import com.example.cotalkproject.data.Prefs;
import com.example.cotalkproject.net.SessionManager;
import com.example.cotalkproject.util.AvatarLoader;

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
