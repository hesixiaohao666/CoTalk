package com.example.cotalkproject.ui.identity;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.example.cotalkproject.BuildConfig;
import com.example.cotalkproject.CoTalkApp;
import com.example.cotalkproject.R;
import com.example.cotalkproject.data.AppDatabase;
import com.example.cotalkproject.data.LocalUserDao;
import com.example.cotalkproject.data.Prefs;
import com.example.cotalkproject.model.LocalUser;
import com.example.cotalkproject.net.Protocol;
import com.example.cotalkproject.net.ServerConnection;
import com.example.cotalkproject.net.SessionManager;
import com.example.cotalkproject.ui.widget.InsetCheckBox;
import com.example.cotalkproject.util.AdminKeyUtil;
import com.example.cotalkproject.util.UiUtil;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 群聊身份登录/注册。完成身份验证后返回 RESULT_OK，由调用方重新连接并 JOIN。
 */
public class IdentityActivity extends AppCompatActivity {

    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_AUTH_KEY = "auth_key";

    public static Intent createIntent(android.content.Context context,
                                      String host,
                                      int port,
                                      String authKey) {
        Intent intent = new Intent(context, IdentityActivity.class);
        intent.putExtra(EXTRA_HOST, host);
        intent.putExtra(EXTRA_PORT, port);
        intent.putExtra(EXTRA_AUTH_KEY, authKey);
        return intent;
    }

    private EditText etUsername;
    private EditText etPassword;
    private EditText etConfirm;
    private EditText etAdminKey;
    private TextView tvNickname;
    private TextView tvStatus;
    private ProgressBar progress;
    private RadioGroup modeGroup;
    private RadioButton modeLogin;
    private InsetCheckBox cbRemember;
    private InsetCheckBox cbAuto;
    private View btnSubmit;
    private View btnCancel;

    private String host;
    private int port;
    private String authKey;
    private String localNickname;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "cotalk-identity");
                thread.setDaemon(true);
                return thread;
            });
    private volatile ServerConnection connection;
    private volatile boolean finished;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        host = getIntent().getStringExtra(EXTRA_HOST);
        port = getIntent().getIntExtra(EXTRA_PORT, -1);
        authKey = getIntent().getStringExtra(EXTRA_AUTH_KEY);
        if (TextUtils.isEmpty(host) || port <= 0
                || TextUtils.isEmpty(authKey)) {
            finish();
            return;
        }

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_identity);
        UiUtil.applyLightSystemBars(this);
        UiUtil.applyEdgeToEdgeInsetsWithIme(
                findViewById(R.id.identity_root), null,
                (ScrollView) findViewById(R.id.identity_root));

        etUsername = findViewById(R.id.et_identity_username);
        etPassword = findViewById(R.id.et_identity_password);
        etConfirm = findViewById(R.id.et_identity_confirm);
        etAdminKey = findViewById(R.id.et_identity_admin_key);
        tvNickname = findViewById(R.id.tv_identity_nickname);
        tvStatus = findViewById(R.id.tv_identity_status);
        progress = findViewById(R.id.pb_identity);
        modeGroup = findViewById(R.id.rg_identity_mode);
        modeLogin = findViewById(R.id.rb_identity_login);
        cbRemember = findViewById(R.id.cb_identity_remember);
        cbAuto = findViewById(R.id.cb_identity_auto);
        btnSubmit = findViewById(R.id.btn_identity_submit);
        btnCancel = findViewById(R.id.btn_identity_cancel);

        findViewById(R.id.btn_identity_cancel).setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        modeGroup.setOnCheckedChangeListener((group, checkedId) ->
                updateMode());
        cbAuto.setOnCheckedChangeListener((buttonView, checked) -> {
            if (checked && !cbRemember.isChecked()) {
                cbRemember.setChecked(true);
            }
        });
        cbRemember.setOnCheckedChangeListener((buttonView, checked) -> {
            if (!checked && cbAuto.isChecked()) {
                cbAuto.setChecked(false);
            }
        });
        btnSubmit.setOnClickListener(v -> submit());

        Prefs prefs = CoTalkApp.getInstance().getPrefs();
        String remembered = prefs.getIdentityUsername(host, port);
        if (!TextUtils.isEmpty(remembered)) {
            etUsername.setText(remembered);
            etPassword.setText(prefs.getIdentityPassword(host, port));
            cbRemember.setChecked(prefs.isIdentityRemember(host, port));
            cbAuto.setChecked(prefs.isIdentityAutoLogin(host, port));
        }
        loadLocalUser();
        updateMode();
    }

    private void loadLocalUser() {
        AppDatabase database = CoTalkApp.getInstance().getDatabase();
        database.execute(() -> {
            LocalUser user = new LocalUserDao(database).get();
            database.runOnMain(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (user == null) {
                    toast(getString(R.string.identity_local_missing));
                    finish();
                    return;
                }
                localNickname = user.nickname;
                tvNickname.setText(getString(
                        R.string.identity_nickname_format, localNickname));
                if (TextUtils.isEmpty(etUsername.getText())) {
                    etUsername.setText(user.username);
                }
            });
        });
    }

    private void updateMode() {
        boolean register = modeGroup.getCheckedRadioButtonId()
                == R.id.rb_identity_register;
        etConfirm.setVisibility(register ? View.VISIBLE : View.GONE);
        etAdminKey.setVisibility(register ? View.VISIBLE : View.GONE);
        ((TextView) btnSubmit).setText(register
                ? R.string.identity_mode_register
                : R.string.identity_mode_login);
    }

    private void submit() {
        String username = etUsername.getText().toString().trim();
        String password = etPassword.getText().toString();
        boolean register = modeGroup.getCheckedRadioButtonId()
                == R.id.rb_identity_register;
        if (!username.matches("^[A-Za-z][A-Za-z0-9]{2,15}$")) {
            toast(getString(R.string.identity_username_invalid));
            return;
        }
        if (!passwordValid(password)) {
            toast(getString(R.string.identity_password_invalid));
            return;
        }
        if (register) {
            String confirm = etConfirm.getText().toString();
            if (TextUtils.isEmpty(confirm)) {
                toast(getString(R.string.identity_confirm_empty));
                return;
            }
            if (!password.equals(confirm)) {
                toast(getString(R.string.identity_password_mismatch));
                return;
            }
        }
        if (TextUtils.isEmpty(localNickname)) {
            toast(getString(R.string.identity_local_missing));
            return;
        }

        String adminKey = register
                ? etAdminKey.getText().toString().trim() : "";
        setLoading(true);
        setStatus(getString(R.string.identity_submitting));
        executor.execute(() -> runAuth(register, username, password,
                adminKey));
    }

    private void runAuth(boolean register,
                         String username,
                         String password,
                         String adminKey) {
        ServerConnection conn = new ServerConnection(host, port,
                new com.example.cotalkproject.net.ConnectionListener() {
                    @Override
                    public void onFrame(ServerConnection source,
                                        JSONObject frame) {
                    }

                    @Override
                    public void onDisconnected(ServerConnection source,
                                               String reason) {
                    }
                });
        connection = conn;
        try {
            conn.connectHandshake(authKey, BuildConfig.VERSION_CODE,
                    BuildConfig.VERSION_NAME);
            JSONObject response;
            if (register) {
                String proof = "";
                if (!adminKey.isEmpty()) {
                    JSONObject challenge = conn.identityAdminChallenge();
                    JSONObject data = challenge.optJSONObject("data");
                    String salt = data == null ? ""
                            : data.optString("salt", "");
                    String nonce = data == null ? ""
                            : data.optString("nonce", "");
                    int iterations = data == null ? 0
                            : data.optInt("iterations", 0);
                    if (salt.isEmpty() || nonce.isEmpty() || iterations <= 0) {
                        throw new IllegalStateException("管理员挑战格式错误");
                    }
                    proof = AdminKeyUtil.createProof(
                            adminKey, salt, iterations, nonce);
                }
                response = conn.identityRegister(username, password,
                        localNickname, proof);
            } else {
                response = conn.identityLogin(username, password);
            }
            String type = response.optString("type", "");
            if (Protocol.IDENTITY_PENDING.equals(type)) {
                postStatus(getString(R.string.identity_pending));
                conn.waitForIdentityApproved();
            } else if (!Protocol.IDENTITY_OK.equals(type)) {
                throw new IllegalStateException("身份服务返回异常");
            }
            saveAndFinish(username, password, register);
        } catch (Exception e) {
            String message = e.getMessage() == null
                    ? "身份验证失败" : e.getMessage();
            postFailure(getString(R.string.identity_failed, message));
            conn.close();
            connection = null;
        }
    }

    private void saveAndFinish(String username, String password,
                               boolean register) {
        if (finished) {
            return;
        }
        Prefs prefs = CoTalkApp.getInstance().getPrefs();
        boolean remember = cbRemember.isChecked();
        boolean auto = cbAuto.isChecked() && remember;
        if (remember) {
            prefs.saveIdentityCredentials(host, port, username, password,
                    true, auto);
        } else {
            prefs.clearIdentityCredentials(host, port);
        }
        CoTalkApp.getInstance().getSessionManager()
                .setEphemeralIdentity(host, port, username, password);
        ServerConnection current = connection;
        connection = null;
        if (current != null) {
            current.close();
        }
        databaseRunOnMain(() -> {
            if (finished) {
                return;
            }
            finished = true;
            setResult(RESULT_OK);
            finish();
        });
    }

    private void databaseRunOnMain(Runnable runnable) {
        CoTalkApp.getInstance().getDatabase().runOnMain(runnable);
    }

    private void postFailure(String message) {
        databaseRunOnMain(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            setLoading(false);
            setStatus(message);
            toast(message);
        });
    }

    private void postStatus(String message) {
        databaseRunOnMain(() -> {
            if (!isFinishing() && !isDestroyed()) {
                setStatus(message);
            }
        });
    }

    private void setStatus(String message) {
        tvStatus.setText(message);
        tvStatus.setVisibility(TextUtils.isEmpty(message)
                ? View.GONE : View.VISIBLE);
    }

    private void setLoading(boolean loading) {
        etUsername.setEnabled(!loading);
        etPassword.setEnabled(!loading);
        etConfirm.setEnabled(!loading);
        etAdminKey.setEnabled(!loading);
        modeGroup.setEnabled(!loading);
        cbRemember.setEnabled(!loading);
        cbAuto.setEnabled(!loading);
        btnSubmit.setEnabled(!loading);
        btnCancel.setEnabled(!loading);
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private static boolean passwordValid(String password) {
        if (password == null || password.length() < 8
                || password.length() > 16) {
            return false;
        }
        boolean upper = false;
        boolean lower = false;
        boolean digit = false;
        boolean special = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isUpperCase(c)) {
                upper = true;
            } else if (Character.isLowerCase(c)) {
                lower = true;
            } else if (Character.isDigit(c)) {
                digit = true;
            } else if (!Character.isWhitespace(c)) {
                special = true;
            }
        }
        int categories = (upper ? 1 : 0) + (lower ? 1 : 0)
                + (digit ? 1 : 0) + (special ? 1 : 0);
        return categories >= 2;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        finished = true;
        ServerConnection current = connection;
        connection = null;
        if (current != null) {
            current.close();
        }
        executor.shutdownNow();
        super.onDestroy();
    }
}
