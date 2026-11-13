package com.example.cotalkproject.ui.admin;

import android.content.Context;
import android.content.Intent;
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
import com.example.cotalkproject.data.SessionDao;
import com.example.cotalkproject.model.Session;
import com.example.cotalkproject.net.SessionManager;
import com.example.cotalkproject.ui.widget.AvatarView;
import com.example.cotalkproject.util.ImageUtil;
import com.example.cotalkproject.util.UiUtil;

/**
 * 管理员修改房间头像、名称与公告。
 */
public class RoomAdminActivity extends AppCompatActivity {

    public static final String EXTRA_SESSION_ID = "session_id";

    private long sessionId;
    private AvatarView ivAvatar;
    private EditText etName;
    private EditText etAnnouncement;
    private Button btnSave;

    private AppDatabase database;
    private SessionDao sessionDao;
    private SessionManager sessionManager;
    private ActivityResultLauncher<String> pickImage;
    private String pendingAvatarPath;

    public static Intent createIntent(Context context, long sessionId) {
        Intent intent = new Intent(context, RoomAdminActivity.class);
        intent.putExtra(EXTRA_SESSION_ID, sessionId);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sessionId = getIntent().getLongExtra(EXTRA_SESSION_ID, -1);
        if (sessionId <= 0) {
            finish();
            return;
        }

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_room_admin);
        UiUtil.applyLightSystemBars(this);
        UiUtil.applyEdgeToEdgeInsets(
                findViewById(R.id.room_admin_root),
                findViewById(R.id.title_bar)
        );

        TextView btnBack = findViewById(R.id.btn_back);
        btnBack.setVisibility(View.VISIBLE);
        btnBack.setOnClickListener(v -> finish());
        TextView title = findViewById(R.id.tv_title);
        title.setText(R.string.room_admin_title);

        ivAvatar = findViewById(R.id.iv_room_admin_avatar);
        etName = findViewById(R.id.et_room_admin_name);
        etAnnouncement = findViewById(R.id.et_room_admin_announcement);
        btnSave = findViewById(R.id.btn_room_admin_save);

        database = CoTalkApp.getInstance().getDatabase();
        sessionDao = new SessionDao(database);
        sessionManager = CoTalkApp.getInstance().getSessionManager();

        pickImage = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        importAvatar(uri);
                    }
                });
        findViewById(R.id.btn_room_admin_avatar).setOnClickListener(
                v -> pickImage.launch("image/*"));
        btnSave.setOnClickListener(v -> save());

        loadSession();
    }

    private void loadSession() {
        database.execute(() -> {
            Session session = sessionDao.getById(sessionId);
            database.runOnMain(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (session == null) {
                    finish();
                    return;
                }
                if (!sessionManager.isActive(sessionId)) {
                    Toast.makeText(this, R.string.room_admin_offline,
                            Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                if (!sessionManager.isSelfAdmin()) {
                    Toast.makeText(this, R.string.room_admin_not_admin,
                            Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                etName.setText(session.displayName());
                etAnnouncement.setText(session.announcement == null
                        ? "" : session.announcement);
                bindRoomAvatar(session);
            });
        });
    }

    private void bindRoomAvatar(Session session) {
        String name = session.displayName();
        ivAvatar.setAvatar(name, null);
        if (session.roomAvatarVersion <= 0
                || TextUtils.isEmpty(session.roomAvatarId)) {
            return;
        }
        String token = "room-admin|" + session.id + "|"
                + session.roomAvatarVersion;
        ivAvatar.setTag(token);
        CoTalkApp.getInstance().getAvatarLoader().load(
                session.id,
                session.roomAvatarId,
                session.roomAvatarVersion,
                name,
                bitmap -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    if (token.equals(ivAvatar.getTag())) {
                        ivAvatar.setAvatar(name, bitmap);
                    }
                });
    }

    private void importAvatar(Uri uri) {
        btnSave.setEnabled(false);
        database.execute(() -> {
            try {
                String path = ImageUtil.saveRoomAvatar(this, uri);
                Bitmap bitmap = ImageUtil.loadBitmap(path);
                database.runOnMain(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    btnSave.setEnabled(true);
                    pendingAvatarPath = path;
                    ivAvatar.setTag(null);
                    ivAvatar.setAvatar(etName.getText().toString(), bitmap);
                    Toast.makeText(this,
                            R.string.room_admin_avatar_selected,
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                database.runOnMain(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    btnSave.setEnabled(true);
                    Toast.makeText(this,
                            R.string.room_admin_avatar_failed,
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void save() {
        String name = etName.getText().toString().trim();
        String announcement = etAnnouncement.getText().toString().trim();
        if (name.isEmpty() || name.length() > 32) {
            Toast.makeText(this, R.string.room_admin_name_invalid,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (announcement.length() > 200) {
            Toast.makeText(this, R.string.room_admin_announcement_too_long,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (!sessionManager.isActive(sessionId)
                || !sessionManager.isSelfAdmin()) {
            Toast.makeText(this, R.string.room_admin_not_admin,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        btnSave.setEnabled(false);
        String avatarPath = pendingAvatarPath;
        database.execute(() -> {
            String avatarData = avatarPath == null
                    ? "" : ImageUtil.fileToDataUri(avatarPath);
            database.runOnMain(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                sessionManager.updateRoomInfo(
                        name, announcement, avatarData,
                        new SessionManager.RoomUpdateCallback() {
                            @Override
                            public void onSuccess() {
                                if (isFinishing() || isDestroyed()) {
                                    return;
                                }
                                Toast.makeText(RoomAdminActivity.this,
                                        R.string.room_admin_saved,
                                        Toast.LENGTH_SHORT).show();
                                finish();
                            }

                            @Override
                            public void onError(String message, int code) {
                                if (isFinishing() || isDestroyed()) {
                                    return;
                                }
                                btnSave.setEnabled(true);
                                Toast.makeText(RoomAdminActivity.this,
                                        getString(R.string.room_admin_failed,
                                                message),
                                        Toast.LENGTH_LONG).show();
                            }
                        });
            });
        });
    }
}
