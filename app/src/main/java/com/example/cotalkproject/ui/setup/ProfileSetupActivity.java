package com.example.cotalkproject.ui.setup;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.example.cotalkproject.BuildConfig;
import com.example.cotalkproject.CoTalkApp;
import com.example.cotalkproject.MainActivity;
import com.example.cotalkproject.R;
import com.example.cotalkproject.data.AppDatabase;
import com.example.cotalkproject.data.ProfileDao;
import com.example.cotalkproject.model.Profile;
import com.example.cotalkproject.ui.widget.AvatarView;
import com.example.cotalkproject.util.ImageUtil;
import com.example.cotalkproject.util.UiUtil;

import java.util.UUID;

/**
 * 首次使用初始化：头像、昵称、本地用户名。
 */
public class ProfileSetupActivity extends AppCompatActivity {

    private AvatarView ivAvatar;
    private EditText etNickname;
    private EditText etUsername;
    private View btnChooseAvatar;
    private View btnSave;
    private ProgressBar progress;

    private AppDatabase database;
    private ProfileDao profileDao;
    private ActivityResultLauncher<String> pickImage;
    private Uri pendingAvatarUri;
    private Bitmap pendingAvatarBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_profile_setup);
        UiUtil.applyLightSystemBars(this);
        UiUtil.applyPageInsetsWithIme(findViewById(R.id.profile_setup_root));

        ivAvatar = findViewById(R.id.iv_profile_setup_avatar);
        etNickname = findViewById(R.id.et_profile_setup_nickname);
        etUsername = findViewById(R.id.et_profile_setup_username);
        btnChooseAvatar = findViewById(R.id.btn_profile_setup_avatar);
        btnSave = findViewById(R.id.btn_profile_setup_save);
        progress = findViewById(R.id.pb_profile_setup);
        TextView version = findViewById(R.id.tv_profile_setup_version);
        version.setText(getString(R.string.version_format,
                BuildConfig.VERSION_NAME));

        database = CoTalkApp.getInstance().getDatabase();
        profileDao = new ProfileDao(database);
        pickImage = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        chooseAvatar(uri);
                    }
                });

        btnChooseAvatar.setOnClickListener(v -> pickImage.launch("image/*"));
        btnSave.setOnClickListener(v -> save());
        ivAvatar.setAvatar("CoTalk", null);
    }

    private void chooseAvatar(Uri uri) {
        btnChooseAvatar.setEnabled(false);
        database.execute(() -> {
            try {
                Bitmap bitmap = ImageUtil.decodeUriSampled(
                        this, uri, 512);
                database.runOnMain(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    btnChooseAvatar.setEnabled(true);
                    pendingAvatarUri = uri;
                    pendingAvatarBitmap = bitmap;
                    ivAvatar.setAvatar(
                            etNickname.getText().toString(), bitmap);
                });
            } catch (Exception e) {
                database.runOnMain(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    btnChooseAvatar.setEnabled(true);
                    Toast.makeText(this, R.string.avatar_save_failed,
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void save() {
        String nickname = etNickname.getText().toString().trim();
        String username = etUsername.getText().toString().trim();
        if (pendingAvatarUri == null) {
            Toast.makeText(this, R.string.profile_setup_avatar_required,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (nickname.isEmpty() || nickname.length() > 16) {
            Toast.makeText(this, R.string.error_nickname_format,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (!username.matches("^[A-Za-z][A-Za-z0-9]{2,15}$")) {
            Toast.makeText(this, R.string.profile_setup_username_invalid,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        setLoading(true);
        Uri avatarUri = pendingAvatarUri;
        database.execute(() -> {
            try {
                String path = ImageUtil.saveAvatar(this, avatarUri, 1);
                Profile profile = new Profile();
                profile.username = username;
                profile.nickname = nickname;
                profile.signature = "";
                profile.avatarPath = path;
                profile.clientTag = UUID.randomUUID().toString();
                profile.createdAt = System.currentTimeMillis();
                long id = profileDao.insert(profile);
                if (id == -1) {
                    throw new IllegalStateException("保存资料失败");
                }
                database.runOnMain(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    Intent intent = new Intent(this, MainActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                });
            } catch (Exception e) {
                database.runOnMain(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    setLoading(false);
                    Toast.makeText(this,
                            R.string.profile_setup_save_failed,
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void setLoading(boolean loading) {
        etNickname.setEnabled(!loading);
        etUsername.setEnabled(!loading);
        btnChooseAvatar.setEnabled(!loading);
        btnSave.setEnabled(!loading);
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pendingAvatarBitmap != null) {
            pendingAvatarBitmap.recycle();
            pendingAvatarBitmap = null;
        }
    }
}
