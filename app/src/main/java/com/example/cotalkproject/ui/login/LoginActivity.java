package com.example.cotalkproject.ui.login;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.example.cotalkproject.BuildConfig;
import com.example.cotalkproject.CoTalkApp;
import com.example.cotalkproject.MainActivity;
import com.example.cotalkproject.R;
import com.example.cotalkproject.data.AppDatabase;
import com.example.cotalkproject.data.LocalUserDao;
import com.example.cotalkproject.data.Prefs;
import com.example.cotalkproject.model.LocalUser;
import com.example.cotalkproject.util.SecurityUtil;
import com.example.cotalkproject.util.UiUtil;

import java.util.regex.Pattern;

public class LoginActivity extends AppCompatActivity {

    private static final Pattern USERNAME_PATTERN =
            Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private EditText etUsername;
    private EditText etPassword;
    private CheckBox cbRemember;
    private Button btnLogin;
    private ProgressBar pbLogin;
    private TextView tvRegister;
    private ImageButton btnTogglePassword;

    private LocalUserDao userDao;
    private Prefs prefs;
    private boolean passwordVisible;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_login);

        UiUtil.applyLightSystemBars(this);
        UiUtil.applyPageInsetsWithIme(findViewById(R.id.login_root));

        TextView versionView = findViewById(R.id.tv_version);
        versionView.setText(getString(R.string.version_format, BuildConfig.VERSION_NAME));

        etUsername = findViewById(R.id.et_username);
        etPassword = findViewById(R.id.et_password);
        cbRemember = findViewById(R.id.cb_remember);
        btnLogin = findViewById(R.id.btn_login);
        pbLogin = findViewById(R.id.pb_login);
        tvRegister = findViewById(R.id.tv_register);
        btnTogglePassword = findViewById(R.id.btn_toggle_password);

        AppDatabase database = CoTalkApp.getInstance().getDatabase();
        prefs = CoTalkApp.getInstance().getPrefs();
        userDao = new LocalUserDao(database);

        restoreRemembered();

        btnLogin.setOnClickListener(v -> attemptLogin());
        tvRegister.setOnClickListener(v ->
                startActivity(new Intent(this, RegisterActivity.class)));
        btnTogglePassword.setOnClickListener(v -> togglePasswordVisibility());
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadAccountState();
    }

    private void restoreRemembered() {
        if (!prefs.isRememberPassword()) {
            return;
        }
        String username = prefs.getRememberedUsername();
        String password = prefs.getRememberedPassword();
        if (!TextUtils.isEmpty(username)) {
            etUsername.setText(username);
        }
        if (!TextUtils.isEmpty(password)) {
            etPassword.setText(password);
        }
        cbRemember.setChecked(true);
    }

    private void loadAccountState() {
        AppDatabase database = CoTalkApp.getInstance().getDatabase();
        database.execute(() -> {
            LocalUser user = userDao.get();
            database.runOnMain(() -> {
                boolean accountExists = user != null;
                tvRegister.setVisibility(accountExists ? View.GONE : View.VISIBLE);
                if (accountExists && TextUtils.isEmpty(etUsername.getText())) {
                    etUsername.setText(user.username);
                }
                if (!accountExists) {
                    prefs.clearRemembered();
                    cbRemember.setChecked(false);
                }
            });
        });
    }

    private void attemptLogin() {
        String username = etUsername.getText().toString().trim();
        String password = etPassword.getText().toString();

        if (!USERNAME_PATTERN.matcher(username).matches()) {
            toast(R.string.error_username_format);
            return;
        }
        if (password.length() < 6 || password.length() > 20) {
            toast(R.string.error_password_format);
            return;
        }

        setLoading(true);
        AppDatabase database = CoTalkApp.getInstance().getDatabase();
        database.execute(() -> {
            LocalUser user = userDao.get();
            boolean success = false;
            if (user != null && user.username.equals(username)) {
                success = SecurityUtil.verifyPassword(password, user.salt, user.passwordHash);
            }
            if (success) {
                long now = System.currentTimeMillis();
                userDao.updateLastLogin(user.id, now);
                user.lastLogin = now;
            }

            final boolean loginOk = success;
            final LocalUser loggedIn = user;
            database.runOnMain(() -> {
                setLoading(false);
                if (!loginOk) {
                    toast(loggedIn == null ? R.string.error_account_missing
                            : R.string.error_login_failed);
                    return;
                }
                onLoginSuccess(loggedIn, password);
            });
        });
    }

    private void onLoginSuccess(LocalUser user, String password) {
        prefs.setCurrentUserId(user.id);
        if (cbRemember.isChecked()) {
            if (!prefs.saveCredentials(user.username, password)) {
                prefs.clearRemembered();
                toast(R.string.error_remember_password);
            }
        } else {
            prefs.clearRemembered();
        }

        toast(R.string.login_success);
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private void togglePasswordVisibility() {
        passwordVisible = !passwordVisible;
        int selection = etPassword.getSelectionEnd();
        etPassword.setInputType(passwordVisible
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (selection >= 0) {
            etPassword.setSelection(Math.min(selection, etPassword.getText().length()));
        }
        btnTogglePassword.setContentDescription(getString(
                passwordVisible ? R.string.hide_password : R.string.show_password));
        btnTogglePassword.setAlpha(passwordVisible ? 1f : 0.65f);
    }

    private void setLoading(boolean loading) {
        etUsername.setEnabled(!loading);
        etPassword.setEnabled(!loading);
        cbRemember.setEnabled(!loading);
        btnLogin.setEnabled(!loading);
        tvRegister.setEnabled(!loading);
        btnTogglePassword.setEnabled(!loading);
        pbLogin.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }
}
