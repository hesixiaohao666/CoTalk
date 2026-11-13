package com.example.cotalkproject.ui.settings;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;

import com.example.cotalkproject.BuildConfig;
import com.example.cotalkproject.CoTalkApp;
import com.example.cotalkproject.R;
import com.example.cotalkproject.data.AppDatabase;
import com.example.cotalkproject.data.LocalUserDao;
import com.example.cotalkproject.data.MessageDao;
import com.example.cotalkproject.data.Prefs;
import com.example.cotalkproject.model.LocalUser;
import com.example.cotalkproject.net.SessionManager;
import com.example.cotalkproject.service.CoTalkConnectionService;
import com.example.cotalkproject.ui.login.LoginActivity;
import com.example.cotalkproject.ui.login.RegisterActivity;
import com.example.cotalkproject.ui.widget.BottomSheetMenu;
import com.example.cotalkproject.util.ImageUtil;
import com.example.cotalkproject.util.NotificationHelper;
import com.example.cotalkproject.util.SecurityUtil;
import com.example.cotalkproject.util.UiUtil;
import com.google.android.material.bottomsheet.BottomSheetDialog;

public class SettingsActivity extends AppCompatActivity {

    private AppDatabase database;
    private LocalUserDao userDao;
    private MessageDao messageDao;
    private Prefs prefs;
    private SessionManager sessionManager;
    private boolean updatingSwitches;
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private SwitchCompat messageNotifySwitch;
    private boolean pendingBackgroundResidentHint;
    private boolean pendingMessageNotifyEnable;
    private boolean pendingMessageNotifyShowHint;
    private boolean pendingPushHintAfterBattery;
    private boolean showPushHintOnResume;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        if (pendingMessageNotifyEnable) {
                            boolean showHint = pendingMessageNotifyShowHint;
                            pendingMessageNotifyEnable = false;
                            pendingMessageNotifyShowHint = false;
                            applyMessageNotifyEnabled(showHint);
                            if (!showHint) {
                                pendingPushHintAfterBattery = true;
                            }
                        }
                    } else {
                        if (pendingMessageNotifyEnable) {
                            pendingMessageNotifyEnable = false;
                            pendingMessageNotifyShowHint = false;
                            setMessageNotifyOff();
                        }
                        prefs.setMessageNotify(false);
                        Toast.makeText(this,
                                R.string.notification_permission_denied,
                                Toast.LENGTH_LONG).show();
                    }
                    if (pendingBackgroundResidentHint) {
                        pendingBackgroundResidentHint = false;
                        showBackgroundResidentHint();
                    }
                });
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_settings);

        UiUtil.applyLightSystemBars(this);
        UiUtil.applyEdgeToEdgeInsets(
                findViewById(R.id.settings_root),
                findViewById(R.id.title_bar)
        );

        TextView btnBack = findViewById(R.id.btn_back);
        btnBack.setVisibility(View.VISIBLE);
        btnBack.setOnClickListener(v -> finish());
        TextView title = findViewById(R.id.tv_title);
        title.setText(R.string.settings_title);

        TextView version = findViewById(R.id.tv_version);
        version.setText(getString(
                R.string.version_format, BuildConfig.VERSION_NAME));

        database = CoTalkApp.getInstance().getDatabase();
        userDao = new LocalUserDao(database);
        messageDao = new MessageDao(database);
        prefs = CoTalkApp.getInstance().getPrefs();
        sessionManager = CoTalkApp.getInstance().getSessionManager();

        SwitchCompat backgroundResident =
                findViewById(R.id.switch_background_resident);
        backgroundResident.setChecked(prefs.isBackgroundResident());
        backgroundResident.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.setBackgroundResident(isChecked);
            if (isChecked) {
                CoTalkConnectionService.start(this);
                boolean waitingForPermission = false;
                if (!messageNotifySwitch.isChecked()) {
                    if (needNotificationPermission()) {
                        pendingMessageNotifyEnable = true;
                        pendingMessageNotifyShowHint = false;
                        pendingBackgroundResidentHint = true;
                        waitingForPermission = true;
                        ensureNotificationPermission();
                    } else {
                        applyMessageNotifyEnabled(false);
                        pendingPushHintAfterBattery = true;
                    }
                }
                if (!waitingForPermission) {
                    showBackgroundResidentHint();
                }
            } else {
                pendingBackgroundResidentHint = false;
                pendingPushHintAfterBattery = false;
                pendingMessageNotifyEnable = false;
                pendingMessageNotifyShowHint = false;
                CoTalkConnectionService.stop(this);
            }
        });

        SwitchCompat autoReconnect = findViewById(R.id.switch_auto_reconnect);
        autoReconnect.setChecked(prefs.isAutoReconnect());
        autoReconnect.setOnCheckedChangeListener((buttonView, isChecked) ->
                sessionManager.setAutoReconnectEnabled(isChecked));

        messageNotifySwitch = findViewById(R.id.switch_message_notify);
        boolean messageNotifyEnabled = prefs.isMessageNotify();
        if (messageNotifyEnabled && needNotificationPermission()) {
            // 未授予通知权限时，消息通知在界面上保持关闭。
            messageNotifyEnabled = false;
            prefs.setMessageNotify(false);
        }
        messageNotifySwitch.setChecked(messageNotifyEnabled);
        messageNotifySwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingSwitches) {
                return;
            }
            if (!isChecked) {
                pendingMessageNotifyEnable = false;
                pendingMessageNotifyShowHint = false;
                prefs.setMessageNotify(false);
                return;
            }
            if (needNotificationPermission()) {
                pendingMessageNotifyEnable = true;
                pendingMessageNotifyShowHint = true;
                ensureNotificationPermission();
            } else {
                applyMessageNotifyEnabled(true);
            }
        });

        SwitchCompat autoConnectAfterLogin = findViewById(
                R.id.switch_auto_connect_after_login);
        autoConnectAfterLogin.setChecked(prefs.isAutoConnectAfterLogin());
        autoConnectAfterLogin.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.setAutoConnectAfterLogin(isChecked));

        SwitchCompat autoSaveDraft = findViewById(
                R.id.switch_auto_save_draft);
        autoSaveDraft.setChecked(prefs.isAutoSaveDraft());
        autoSaveDraft.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.setAutoSaveDraft(isChecked));

        SwitchCompat darkFollow = findViewById(R.id.switch_dark_follow_system);
        SwitchCompat darkMode = findViewById(R.id.switch_dark_mode);
        SwitchCompat adminMode = findViewById(R.id.switch_admin_mode);

        boolean followSystem = prefs.isDarkFollowSystem();
        boolean darkEnabled = prefs.isDarkMode();
        updatingSwitches = true;
        darkFollow.setChecked(followSystem);
        darkMode.setEnabled(!followSystem);
        darkMode.setChecked(followSystem ? isSystemNight() : darkEnabled);
        adminMode.setChecked(false);
        updatingSwitches = false;

        darkFollow.setOnCheckedChangeListener((buttonView, checked) -> {
            if (updatingSwitches) {
                return;
            }
            prefs.setDarkFollowSystem(checked);
            if (checked) {
                updatingSwitches = true;
                darkMode.setChecked(isSystemNight());
                updatingSwitches = false;
                darkMode.setEnabled(false);
                AppCompatDelegate.setDefaultNightMode(
                        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                showDarkExperimentalToast();
            } else {
                darkMode.setEnabled(true);
                boolean enabled = prefs.isDarkMode();
                updatingSwitches = true;
                darkMode.setChecked(enabled);
                updatingSwitches = false;
                AppCompatDelegate.setDefaultNightMode(enabled
                        ? AppCompatDelegate.MODE_NIGHT_YES
                        : AppCompatDelegate.MODE_NIGHT_NO);
            }
        });

        darkMode.setOnCheckedChangeListener((buttonView, checked) -> {
            if (updatingSwitches || prefs.isDarkFollowSystem()) {
                return;
            }
            prefs.setDarkMode(checked);
            AppCompatDelegate.setDefaultNightMode(checked
                    ? AppCompatDelegate.MODE_NIGHT_YES
                    : AppCompatDelegate.MODE_NIGHT_NO);
            if (checked) {
                showDarkExperimentalToast();
            }
        });

        adminMode.setOnClickListener(v -> {
            adminMode.setChecked(false);
            Toast.makeText(this, R.string.admin_mode_not_available,
                    Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_clear_history).setOnClickListener(v -> confirmClearHistory());
        findViewById(R.id.btn_remove_all_servers).setOnClickListener(v -> confirmRemoveAllServers());
        findViewById(R.id.btn_reset_account).setOnClickListener(v -> showResetDialog());
        findViewById(R.id.btn_logout).setOnClickListener(v -> confirmLogout());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (messageNotifySwitch != null
                && messageNotifySwitch.isChecked()
                && needNotificationPermission()) {
            setMessageNotifyOff();
        }
        if (showPushHintOnResume) {
            showPushHintOnResume = false;
            showNotificationPushHint();
        }
    }

    private void applyMessageNotifyEnabled(boolean showHint) {
        boolean previous = updatingSwitches;
        updatingSwitches = true;
        messageNotifySwitch.setChecked(true);
        updatingSwitches = previous;
        prefs.setMessageNotify(true);
        // 同步创建高重要性消息渠道：锁屏可见 + 允许悬浮通知。
        NotificationHelper.createChannels(this);
        if (showHint) {
            showNotificationPushHint();
        }
    }

    private void setMessageNotifyOff() {
        boolean previous = updatingSwitches;
        updatingSwitches = true;
        messageNotifySwitch.setChecked(false);
        updatingSwitches = previous;
        prefs.setMessageNotify(false);
    }

    private void showNotificationPushHint() {
        BottomSheetMenu.showConfirm(
                this,
                getString(R.string.message_notify),
                getString(R.string.notification_push_permission_hint),
                getString(R.string.go_to_settings),
                this::openAppNotificationSettings);
    }

    private void openAppNotificationSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } catch (RuntimeException ignored) {
            try {
                startActivity(new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException ignoredAgain) {
                Toast.makeText(this,
                        R.string.notification_push_permission_hint,
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private boolean isSystemNight() {
        int mode = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private boolean needNotificationPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(
                        this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;
    }

    private void ensureNotificationPermission() {
        if (!needNotificationPermission()) {
            return;
        }
        prefs.setNotificationPermissionAsked(true);
        notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS);
    }

    private void showBackgroundResidentHint() {
        BottomSheetDialog dialog = BottomSheetMenu.showConfirm(
                this,
                getString(R.string.background_resident),
                getString(R.string.background_resident_battery_hint),
                getString(R.string.go_to_settings),
                this::openBatteryOptimizationSettings);
        View root = findViewById(R.id.settings_root);
        dialog.setOnDismissListener(d -> {
            if (!pendingPushHintAfterBattery) {
                return;
            }
            pendingPushHintAfterBattery = false;
            // 等底部弹层完全收起后再显示下一个提示，避免窗口切换闪烁。
            root.postDelayed(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (getLifecycle().getCurrentState()
                        .isAtLeast(Lifecycle.State.RESUMED)) {
                    showNotificationPushHint();
                } else {
                    // 用户跳转到系统设置时，等返回本页后再提示。
                    showPushHintOnResume = true;
                }
            }, 250L);
        });
    }

    private void openBatteryOptimizationSettings() {
        try {
            startActivity(new Intent(
                    Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (RuntimeException ignored) {
            try {
                startActivity(new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException ignoredAgain) {
                Toast.makeText(this,
                        R.string.background_resident_battery_hint,
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void showDarkExperimentalToast() {
        Toast.makeText(this, R.string.dark_experimental_warning,
                Toast.LENGTH_SHORT).show();
    }

    private void confirmClearHistory() {
        BottomSheetMenu.showConfirm(
                this,
                getString(R.string.clear_history),
                getString(R.string.clear_history_confirm),
                getString(R.string.confirm),
                () -> {
                    sessionManager.clearAllHistory();
                    Toast.makeText(this, R.string.clear_history_done,
                            Toast.LENGTH_SHORT).show();
                });
    }

    private void confirmRemoveAllServers() {
        BottomSheetMenu.showConfirm(
                this,
                getString(R.string.remove_all_servers),
                getString(R.string.remove_all_servers_confirm),
                getString(R.string.confirm),
                () -> {
                    sessionManager.removeAllSessions();
                    database.execute(() -> {
                        messageDao.deleteAll();
                        ImageUtil.deleteRemoteAvatarCache(this);
                    });
                    Toast.makeText(this, R.string.remove_all_servers_done,
                            Toast.LENGTH_SHORT).show();
                });
    }

    private void showResetDialog() {
        BottomSheetMenu.showInput(
                this,
                getString(R.string.reset_confirm_title),
                getString(R.string.reset_confirm_message),
                getString(R.string.reset_password_hint),
                true,
                new BottomSheetMenu.InputCallback() {
                    @Override
                    public boolean onInput(String text) {
                        verifyAndReset(text);
                        return true;
                    }
                });
    }

    private void verifyAndReset(String password) {
        database.execute(() -> {
            LocalUser user = userDao.get();
            boolean valid = user != null
                    && SecurityUtil.verifyPassword(password, user.salt, user.passwordHash);
            if (!valid) {
                database.runOnMain(() -> Toast.makeText(this,
                        R.string.reset_password_wrong, Toast.LENGTH_SHORT).show());
                return;
            }

            sessionManager.removeAllSessions();
            CoTalkConnectionService.stop(this);
            database.clearAllLocalData();
            ImageUtil.deleteAllAvatarCache(this);
            CoTalkApp.getInstance().setAuthenticated(false);
            prefs.logout();
            prefs.clearRemembered();
            prefs.setAutoLogin(false);
            prefs.clearDrafts();

            database.runOnMain(() -> {
                Toast.makeText(this, R.string.reset_success,
                        Toast.LENGTH_SHORT).show();
                Intent intent = new Intent(this, RegisterActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finishAffinity();
            });
        });
    }

    private void confirmLogout() {
        BottomSheetMenu.showConfirm(
                this,
                getString(R.string.logout),
                getString(R.string.logout_confirm),
                getString(R.string.confirm),
                () -> {
                    sessionManager.disconnect();
                    CoTalkConnectionService.stop(this);
                    CoTalkApp.getInstance().setAuthenticated(false);
                    prefs.logout();
                    prefs.setAutoLogin(false);
                    prefs.clearDrafts();
                    Toast.makeText(this, R.string.logout_done,
                            Toast.LENGTH_SHORT).show();
                    Intent intent = new Intent(this, LoginActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finishAffinity();
                });
    }
}
