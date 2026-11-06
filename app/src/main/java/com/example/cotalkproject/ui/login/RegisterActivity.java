package com.example.cotalkproject.ui.login;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.example.cotalkproject.CoTalkApp;
import com.example.cotalkproject.MainActivity;
import com.example.cotalkproject.R;
import com.example.cotalkproject.data.AppDatabase;
import com.example.cotalkproject.data.LocalUserDao;
import com.example.cotalkproject.data.Prefs;
import com.example.cotalkproject.model.LocalUser;
import com.example.cotalkproject.util.SecurityUtil;
import com.example.cotalkproject.util.UiUtil;

import java.util.UUID;
import java.util.regex.Pattern;

public class RegisterActivity extends AppCompatActivity {

    private static final Pattern USERNAME_PATTERN =
            Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private EditText etUsername;
    private EditText etNickname;
    private EditText etPassword;
    private EditText etConfirmPassword;
    private Button btnRegister;
    private ProgressBar pbRegister;
    private ImageButton btnTogglePassword;

    private LocalUserDao userDao;
    private Prefs prefs;
    private boolean passwordVisible;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_register);

        UiUtil.applyLightSystemBars(this);
        UiUtil.applyEdgeToEdgeInsetsWithIme(
                findViewById(R.id.register_root),
                findViewById(R.id.title_bar),
                findViewById(R.id.register_scroll)
        );

        TextView btnBack = findViewById(R.id.btn_back);
        btnBack.setVisibility(View.VISIBLE);
        btnBack.setOnClickListener(v -> finish());
        TextView title = findViewById(R.id.tv_title);
        title.setText(R.string.register_title);

        etUsername = findViewById(R.id.et_username);
        etNickname = findViewById(R.id.et_nickname);
        etPassword = findViewById(R.id.et_password);
        etConfirmPassword = findViewById(R.id.et_confirm_password);
        btnRegister = findViewById(R.id.btn_register);
        pbRegister = findViewById(R.id.pb_register);
        btnTogglePassword = findViewById(R.id.btn_toggle_password);

        userDao = new LocalUserDao(CoTalkApp.getInstance().getDatabase());
        prefs = CoTalkApp.getInstance().getPrefs();

        btnRegister.setOnClickListener(v -> attemptRegister());
        btnTogglePassword.setOnClickListener(v -> togglePasswordVisibility());
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkExistingAccount();
    }

    private void checkExistingAccount() {
        AppDatabase database = CoTalkApp.getInstance().getDatabase();
        database.execute(() -> {
            LocalUser existing = userDao.get();
            database.runOnMain(() -> {
                if (existing != null) {
                    btnRegister.setEnabled(false);
                    toast(R.string.error_account_exists);
                }
            });
        });
    }

    private void attemptRegister() {
        String username = etUsername.getText().toString().trim();
        String nickname = etNickname.getText().toString().trim();
        String password = etPassword.getText().toString();
        String confirm = etConfirmPassword.getText().toString();

        if (!USERNAME_PATTERN.matcher(username).matches()) {
            toast(R.string.error_username_format);
            return;
        }
        if (nickname.isEmpty()) {
            toast(R.string.error_nickname_empty);
            return;
        }
        if (nickname.length() > 16) {
            toast(R.string.error_nickname_format);
            return;
        }
        if (password.length() < 6 || password.length() > 20) {
            toast(R.string.error_password_format);
            return;
        }
        if (TextUtils.isEmpty(confirm)) {
            toast(R.string.error_confirm_empty);
            return;
        }
        if (!password.equals(confirm)) {
            toast(R.string.error_password_mismatch);
            return;
        }

        setLoading(true);
        AppDatabase database = CoTalkApp.getInstance().getDatabase();
        database.execute(() -> {
            if (userDao.get() != null) {
                database.runOnMain(() -> {
                    setLoading(false);
                    toast(R.string.error_account_exists);
                });
                return;
            }

            try {
                long now = System.currentTimeMillis();
                String salt = SecurityUtil.generateSalt();
                String hash = SecurityUtil.hashPassword(password, salt);

                LocalUser user = new LocalUser();
                user.id = 1;
                user.username = username;
                user.passwordHash = hash;
                user.salt = salt;
                user.nickname = nickname;
                user.signature = "";
                user.avatarPath = null;
                user.clientTag = UUID.randomUUID().toString();
                user.createdAt = now;
                user.lastLogin = now;

                long rowId = userDao.insert(user);
                database.runOnMain(() -> {
                    setLoading(false);
                    if (rowId == -1) {
                        toast(R.string.error_register_failed);
                        return;
                    }
                    CoTalkApp.getInstance().setAuthenticated(true);
                    prefs.setCurrentUserId(1);
                    toast(R.string.register_success);
                    Intent intent = new Intent(this, MainActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                });
            } catch (Exception e) {
                database.runOnMain(() -> {
                    setLoading(false);
                    toast(R.string.error_register_failed);
                });
            }
        });
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
        etNickname.setEnabled(!loading);
        etPassword.setEnabled(!loading);
        etConfirmPassword.setEnabled(!loading);
        btnRegister.setEnabled(!loading);
        btnTogglePassword.setEnabled(!loading);
        pbRegister.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }
}
