package com.example.cotalkproject.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.cotalkproject.util.SecurityUtil;

/**
 * 记住密码、当前登录用户等轻量偏好。
 */
public class Prefs {

    private static final String NAME = "cotalk_prefs";
    private static final String KEY_REMEMBER = "remember_password";
    private static final String KEY_USERNAME = "remembered_username";
    private static final String KEY_PASSWORD = "remembered_password";
    private static final String KEY_CURRENT_USER = "current_user_id";
    private static final String KEY_AUTO_RECONNECT = "auto_reconnect";
    private static final String KEY_MESSAGE_NOTIFY = "message_notify";
    private static final String KEY_HISTORY_CLEAR_PREFIX = "history_clear_seq_";

    private final SharedPreferences preferences;

    public Prefs(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public boolean isRememberPassword() {
        return preferences.getBoolean(KEY_REMEMBER, false);
    }

    public void setRememberPassword(boolean remember) {
        if (remember) {
            preferences.edit().putBoolean(KEY_REMEMBER, true).apply();
        } else {
            clearRemembered();
        }
    }

    /**
     * 只有加密成功才保存；失败返回 false，不落明文。
     */
    public boolean saveCredentials(String username, String password) {
        String encrypted = SecurityUtil.encrypt(password);
        if (encrypted == null) {
            return false;
        }
        preferences.edit()
                .putBoolean(KEY_REMEMBER, true)
                .putString(KEY_USERNAME, username)
                .putString(KEY_PASSWORD, encrypted)
                .apply();
        return true;
    }

    public String getRememberedUsername() {
        return preferences.getString(KEY_USERNAME, "");
    }

    public String getRememberedPassword() {
        String encrypted = preferences.getString(KEY_PASSWORD, "");
        if (encrypted == null || encrypted.isEmpty()) {
            return "";
        }
        String password = SecurityUtil.decrypt(encrypted);
        if (password == null) {
            clearRemembered();
            return "";
        }
        return password;
    }

    public void clearRemembered() {
        preferences.edit()
                .remove(KEY_USERNAME)
                .remove(KEY_PASSWORD)
                .putBoolean(KEY_REMEMBER, false)
                .apply();
    }

    public void setCurrentUserId(long userId) {
        preferences.edit().putLong(KEY_CURRENT_USER, userId).apply();
    }

    public long getCurrentUserId() {
        return preferences.getLong(KEY_CURRENT_USER, 0L);
    }

    public void logout() {
        preferences.edit().putLong(KEY_CURRENT_USER, 0L).apply();
    }

    public boolean isAutoReconnect() {
        return preferences.getBoolean(KEY_AUTO_RECONNECT, true);
    }

    public void setAutoReconnect(boolean enabled) {
        preferences.edit().putBoolean(KEY_AUTO_RECONNECT, enabled).apply();
    }

    public boolean isMessageNotify() {
        return preferences.getBoolean(KEY_MESSAGE_NOTIFY, true);
    }

    public void setMessageNotify(boolean enabled) {
        preferences.edit().putBoolean(KEY_MESSAGE_NOTIFY, enabled).apply();
    }

    public void markHistoryCleared(long sessionId, long sequence) {
        preferences.edit()
                .putLong(KEY_HISTORY_CLEAR_PREFIX + sessionId, Math.max(0L, sequence))
                .apply();
    }

    public long getHistoryClearSeq(long sessionId) {
        return preferences.getLong(KEY_HISTORY_CLEAR_PREFIX + sessionId, -1L);
    }

    public void clearHistoryMarker(long sessionId) {
        preferences.edit()
                .remove(KEY_HISTORY_CLEAR_PREFIX + sessionId)
                .apply();
    }
}
