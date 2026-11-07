package com.example.cotalkproject.ui.profile;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.example.cotalkproject.CoTalkApp;
import com.example.cotalkproject.R;
import com.example.cotalkproject.data.AppDatabase;
import com.example.cotalkproject.data.LocalUserDao;
import com.example.cotalkproject.model.LocalUser;
import com.example.cotalkproject.net.Protocol;
import com.example.cotalkproject.net.SessionManager;
import com.example.cotalkproject.ui.widget.AvatarView;
import com.example.cotalkproject.util.ImageUtil;
import com.example.cotalkproject.util.UiUtil;

import org.json.JSONObject;

public class EditProfileActivity extends AppCompatActivity {

    private AvatarView ivAvatar;
    private EditText etNickname;
    private EditText etSignature;
    private Button btnPickAvatar;
    private Button btnSave;

    private AppDatabase database;
    private LocalUserDao userDao;
    private SessionManager sessionManager;

    private ActivityResultLauncher<String> pickImage;
    private String avatarPath;
    private String pendingAvatarPath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_edit_profile);

        UiUtil.applyLightSystemBars(this);
        UiUtil.applyEdgeToEdgeInsets(
                findViewById(R.id.edit_profile_root),
                findViewById(R.id.title_bar)
        );

        TextView btnBack = findViewById(R.id.btn_back);
        btnBack.setVisibility(View.VISIBLE);
        btnBack.setOnClickListener(v -> finish());
        TextView title = findViewById(R.id.tv_title);
        title.setText(R.string.edit_profile_title);

        ivAvatar = findViewById(R.id.iv_avatar);
        etNickname = findViewById(R.id.et_nickname);
        etSignature = findViewById(R.id.et_signature);
        btnPickAvatar = findViewById(R.id.btn_pick_avatar);
        btnSave = findViewById(R.id.btn_save_profile);

        database = CoTalkApp.getInstance().getDatabase();
        userDao = new LocalUserDao(database);
        sessionManager = CoTalkApp.getInstance().getSessionManager();

        pickImage = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        importAvatar(uri);
                    }
                });

        btnPickAvatar.setOnClickListener(v -> pickImage.launch("image/*"));
        btnSave.setOnClickListener(v -> saveProfile());

        loadUser();
    }

    private void loadUser() {
        database.execute(() -> {
            LocalUser user = userDao.get();
            if (user == null) {
                return;
            }
            Bitmap bitmap = ImageUtil.loadBitmap(user.avatarPath);
            String nickname = user.nickname;
            String signature = user.signature;
            String path = user.avatarPath;
            database.runOnMain(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                etNickname.setText(nickname);
                etSignature.setText(signature == null ? "" : signature);
                avatarPath = path;
                ivAvatar.setAvatar(nickname, bitmap);
            });
        });
    }

    private void importAvatar(Uri uri) {
        btnPickAvatar.setEnabled(false);
        database.execute(() -> {
            try {
                String path = ImageUtil.saveAvatar(this, uri, 1);
                Bitmap bitmap = ImageUtil.loadBitmap(path);
                database.runOnMain(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    btnPickAvatar.setEnabled(true);
                    pendingAvatarPath = path;
                    ivAvatar.setAvatar(etNickname.getText().toString(), bitmap);
                    Toast.makeText(this, R.string.avatar_updated,
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                database.runOnMain(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    btnPickAvatar.setEnabled(true);
                    Toast.makeText(this, R.string.avatar_save_failed,
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void saveProfile() {
        String nickname = etNickname.getText().toString().trim();
        String signature = etSignature.getText().toString().trim();
        if (TextUtils.isEmpty(nickname)) {
            Toast.makeText(this, R.string.error_nickname_empty,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (nickname.length() > 16) {
            Toast.makeText(this, R.string.error_nickname_format,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (signature.length() > 40) {
            Toast.makeText(this, R.string.signature_too_long,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        boolean avatarChanged = pendingAvatarPath != null;
        String path = avatarChanged ? pendingAvatarPath : avatarPath;
        btnSave.setEnabled(false);
        database.execute(() -> {
            userDao.updateProfile(nickname, signature, path);
            String avatarData = avatarChanged && path != null
                    ? ImageUtil.fileToDataUri(path)
                    : "";
            database.runOnMain(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                btnSave.setEnabled(true);
                Toast.makeText(this, R.string.profile_saved,
                        Toast.LENGTH_SHORT).show();
                sendProfileUpdate(nickname, signature, avatarData);
                finish();
            });
        });
    }

    private void sendProfileUpdate(String nickname,
                                   String signature,
                                   String avatarData) {
        if (sessionManager.getActiveSessionId() <= 0) {
            return;
        }
        try {
            JSONObject data = new JSONObject();
            data.put("nickname", nickname);
            data.put("signature", signature == null ? "" : signature);
            if (!TextUtils.isEmpty(avatarData)) {
                data.put("avatar", avatarData);
            }
            JSONObject frame = Protocol.frame(Protocol.PROFILE_UPDATE, data);
            frame.put("reqId", "profile-" + System.currentTimeMillis());
            sessionManager.sendFrame(frame, new SessionManager.SendCallback() {
                @Override
                public void onSuccess() {
                }

                @Override
                public void onError(String error) {
                    // 本地已保存；下次 join 会重新上传头像。
                }
            });
        } catch (Exception ignored) {
            // 本地已保存
        }
    }
}
