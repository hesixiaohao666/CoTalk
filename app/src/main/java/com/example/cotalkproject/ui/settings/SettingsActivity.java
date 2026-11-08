package com.example.cotalkproject.ui.settings;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.example.cotalkproject.BuildConfig;
import com.example.cotalkproject.CoTalkApp;
import com.example.cotalkproject.R;
import com.example.cotalkproject.data.AppDatabase;
import com.example.cotalkproject.data.LocalUserDao;
import com.example.cotalkproject.data.MessageDao;
import com.example.cotalkproject.data.Prefs;
import com.example.cotalkproject.model.LocalUser;
import com.example.cotalkproject.net.SessionManager;
import com.example.cotalkproject.ui.login.LoginActivity;
import com.example.cotalkproject.ui.login.RegisterActivity;
import com.example.cotalkproject.util.ImageUtil;
import com.example.cotalkproject.util.SecurityUtil;
import com.example.cotalkproject.util.UiUtil;

public class SettingsActivity extends AppCompatActivity {

    private AppDatabase database;
    private LocalUserDao userDao;
    private MessageDao messageDao;
    private Prefs prefs;
    private SessionManager sessionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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

        SwitchCompat autoReconnect = findViewById(R.id.switch_auto_reconnect);
        autoReconnect.setChecked(prefs.isAutoReconnect());
        autoReconnect.setOnCheckedChangeListener((buttonView, isChecked) ->
                sessionManager.setAutoReconnectEnabled(isChecked));

        SwitchCompat messageNotify = findViewById(R.id.switch_message_notify);
        messageNotify.setChecked(prefs.isMessageNotify());
        messageNotify.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.setMessageNotify(isChecked));

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

        SwitchCompat darkMode = findViewById(R.id.switch_dark_mode);
        darkMode.setChecked(false);
        darkMode.setOnClickListener(v -> {
            darkMode.setChecked(false);
            Toast.makeText(this, R.string.dark_mode_not_available,
                    Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_clear_history).setOnClickListener(v -> confirmClearHistory());
        findViewById(R.id.btn_remove_all_servers).setOnClickListener(v -> confirmRemoveAllServers());
        findViewById(R.id.btn_reset_account).setOnClickListener(v -> showResetDialog());
        findViewById(R.id.btn_logout).setOnClickListener(v -> confirmLogout());
    }

    private void confirmClearHistory() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.clear_history)
                .setMessage(R.string.clear_history_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    sessionManager.clearAllHistory();
                    Toast.makeText(this, R.string.clear_history_done,
                            Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void confirmRemoveAllServers() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.remove_all_servers)
                .setMessage(R.string.remove_all_servers_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    sessionManager.removeAllSessions();
                    database.execute(() -> {
                        messageDao.deleteAll();
                        ImageUtil.deleteRemoteAvatarCache(this);
                    });
                    Toast.makeText(this, R.string.remove_all_servers_done,
                            Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void showResetDialog() {
        EditText input = new EditText(this);
        input.setHint(R.string.reset_password_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout container = new LinearLayout(this);
        container.setPadding(padding, padding / 2, padding, 0);
        container.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle(R.string.reset_confirm_title)
                .setMessage(R.string.reset_confirm_message)
                .setView(container)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm,
                        (dialog, which) -> verifyAndReset(input.getText().toString()))
                .show();
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
        new AlertDialog.Builder(this)
                .setTitle(R.string.logout)
                .setMessage(R.string.logout_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    sessionManager.disconnect();
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
                })
                .show();
    }
}
