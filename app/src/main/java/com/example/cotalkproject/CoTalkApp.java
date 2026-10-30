package com.example.cotalkproject;

import android.app.Application;

import com.example.cotalkproject.data.AppDatabase;
import com.example.cotalkproject.data.Prefs;

public class CoTalkApp extends Application {

    private static CoTalkApp instance;

    private AppDatabase database;
    private Prefs prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        database = AppDatabase.getInstance(this);
        prefs = new Prefs(this);
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
}
