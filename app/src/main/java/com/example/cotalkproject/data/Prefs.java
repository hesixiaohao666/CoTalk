package com.example.cotalkproject.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.cotalkproject.util.SecurityUtil;

import java.util.Locale;

/**
 * 记住密码、当前登录用户等轻量偏好。
 */
public class Prefs {

    private static final String NAME = "cotalk_prefs";
    private static final String KEY_REMEMBER = "remember_password";
    private static final String KEY_AUTO_LOGIN = "auto_login";
    private static final String KEY_USERNAME = "remembered_username";
    private static final String KEY_PASSWORD = "remembered_password";
    private static final String KEY_CURRENT_USER = "current_user_id";
    private static final String KEY_AUTO_RECONNECT = "auto_reconnect";
    private static final String KEY_MESSAGE_NOTIFY = "message_notify";
    private static final String KEY_BACKGROUND_RESIDENT = "background_resident";
    private static final String KEY_AUTO_CONNECT_AFTER_LOGIN = "auto_connect_after_login";
    private static final String KEY_AUTO_SAVE_DRAFT = "auto_save_draft";
    private static final String KEY_DARK_FOLLOW_SYSTEM = "dark_follow_system";
    private static final String KEY_DARK_MODE = "dark_mode";
    private static final String KEY_HISTORY_CLEAR_PREFIX = "history_clear_seq_";
    private static final String KEY_SERVER_AUTH_PREFIX = "server_auth_";
    private static final String KEY_DRAFT_PREFIX = "draft_";

    private final SharedPreferences preferences;

    public Prefs(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public boolean isRememberPassword() {
        return preferences.getBoolean(KEY_REMEMBER, false);
    }

    public boolean isAutoLogin() {
        return preferences.getBoolean(KEY_AUTO_LOGIN, false);
    }

    public void setAutoLogin(boolean enabled) {
        preferences.edit().putBoolean(KEY_AUTO_LOGIN, enabled).apply();
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
                .putBoolean(KEY_AUTO_LOGIN, false)
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

    public boolean isBackgroundResident() {
        return preferences.getBoolean(KEY_BACKGROUND_RESIDENT, false);
    }

    public void setBackgroundResident(boolean enabled) {
        preferences.edit().putBoolean(KEY_BACKGROUND_RESIDENT, enabled).apply();
    }

    public void setMessageNotify(boolean enabled) {
        preferences.edit().putBoolean(KEY_MESSAGE_NOTIFY, enabled).apply();
    }

    public boolean isAutoConnectAfterLogin() {
        return preferences.getBoolean(KEY_AUTO_CONNECT_AFTER_LOGIN, true);
    }

    public void setAutoConnectAfterLogin(boolean enabled) {
        preferences.edit().putBoolean(KEY_AUTO_CONNECT_AFTER_LOGIN, enabled).apply();
    }

    public boolean isAutoSaveDraft() {
        return preferences.getBoolean(KEY_AUTO_SAVE_DRAFT, true);
    }

    public void setAutoSaveDraft(boolean enabled) {
        preferences.edit().putBoolean(KEY_AUTO_SAVE_DRAFT, enabled).apply();
    }

    public boolean isDarkFollowSystem() {
        return preferences.getBoolean(KEY_DARK_FOLLOW_SYSTEM, false);
    }

    public void setDarkFollowSystem(boolean enabled) {
        preferences.edit().putBoolean(KEY_DARK_FOLLOW_SYSTEM, enabled).apply();
    }

    public boolean isDarkMode() {
        return preferences.getBoolean(KEY_DARK_MODE, false);
    }

    public void setDarkMode(boolean enabled) {
        preferences.edit().putBoolean(KEY_DARK_MODE, enabled).apply();
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

    public void setDraft(long sessionId, String text) {
        if (text == null || text.isEmpty()) {
            clearDraft(sessionId);
            return;
        }
        preferences.edit()
                .putString(KEY_DRAFT_PREFIX + sessionId, text)
                .apply();
    }

    public String getDraft(long sessionId) {
        return preferences.getString(KEY_DRAFT_PREFIX + sessionId, "");
    }

    public void clearDraft(long sessionId) {
        preferences.edit()
                .remove(KEY_DRAFT_PREFIX + sessionId)
                .apply();
    }

    /**
     * 退出或重置本地账号时清理所有会话草稿。
     */
    public void clearDrafts() {
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : preferences.getAll().keySet()) {
            if (key.startsWith(KEY_DRAFT_PREFIX)) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    public boolean saveServerAuthKey(String host, int port, String authKey) {
        String encrypted = SecurityUtil.encrypt(authKey);
        if (encrypted == null) {
            return false;
        }
        preferences.edit()
                .putString(serverAuthKeyName(host, port), encrypted)
                .apply();
        return true;
    }

    public String getServerAuthKey(String host, int port) {
        String encrypted = preferences.getString(serverAuthKeyName(host, port), "");
        if (encrypted == null || encrypted.isEmpty()) {
            return "";
        }
        String key = SecurityUtil.decrypt(encrypted);
        if (key == null) {
            clearServerAuthKey(host, port);
            return "";
        }
        return key;
    }

    public void clearServerAuthKey(String host, int port) {
        preferences.edit()
                .remove(serverAuthKeyName(host, port))
                .apply();
    }

    private String serverAuthKeyName(String host, int port) {
        String safeHost = host == null
                ? ""
                : host.trim().toLowerCase(Locale.ROOT);
        return KEY_SERVER_AUTH_PREFIX + safeHost + "_" + port;
    }
}
