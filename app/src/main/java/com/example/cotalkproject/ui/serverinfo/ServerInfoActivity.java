package com.example.cotalkproject.ui.serverinfo;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cotalkproject.CoTalkApp;
import com.example.cotalkproject.R;
import com.example.cotalkproject.data.AppDatabase;
import com.example.cotalkproject.data.MemberDao;
import com.example.cotalkproject.data.SessionDao;
import com.example.cotalkproject.model.Member;
import com.example.cotalkproject.model.Session;
import com.example.cotalkproject.net.SessionManager;
import com.example.cotalkproject.ui.contacts.MemberAdapter;
import com.example.cotalkproject.util.UiUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 房间信息：房间资料、AI 状态、服务端版本与在线成员。
 */
public class ServerInfoActivity extends AppCompatActivity
        implements SessionManager.Listener {

    public static final String EXTRA_SESSION_ID = "session_id";

    private long sessionId;
    private TextView tvRoomName;
    private TextView tvAnnouncement;
    private TextView tvServerIp;
    private TextView tvPort;
    private TextView tvAiStatus;
    private TextView tvServerVersion;
    private TextView tvMemberTitle;
    private TextView tvMembersEmpty;
    private RecyclerView rvMembers;

    private AppDatabase database;
    private SessionDao sessionDao;
    private MemberDao memberDao;
    private SessionManager sessionManager;
    private MemberAdapter memberAdapter;

    public static Intent createIntent(Context context, long sessionId) {
        Intent intent = new Intent(context, ServerInfoActivity.class);
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
        setContentView(R.layout.activity_server_info);
        UiUtil.applyLightSystemBars(this);
        UiUtil.applyEdgeToEdgeInsets(
                findViewById(R.id.server_info_root),
                findViewById(R.id.title_bar)
        );

        TextView btnBack = findViewById(R.id.btn_back);
        btnBack.setVisibility(View.VISIBLE);
        btnBack.setOnClickListener(v -> finish());
        TextView title = findViewById(R.id.tv_title);
        title.setText(R.string.server_info_title);

        tvRoomName = findViewById(R.id.tv_room_name);
        tvAnnouncement = findViewById(R.id.tv_announcement);
        tvServerIp = findViewById(R.id.tv_server_ip);
        tvPort = findViewById(R.id.tv_port);
        tvAiStatus = findViewById(R.id.tv_ai_status);
        tvServerVersion = findViewById(R.id.tv_server_version);
        tvMemberTitle = findViewById(R.id.tv_member_title);
        tvMembersEmpty = findViewById(R.id.tv_members_empty);
        rvMembers = findViewById(R.id.rv_members);

        database = CoTalkApp.getInstance().getDatabase();
        sessionDao = new SessionDao(database);
        memberDao = new MemberDao(database);
        sessionManager = CoTalkApp.getInstance().getSessionManager();

        rvMembers.setLayoutManager(new LinearLayoutManager(this));
        memberAdapter = new MemberAdapter(
                CoTalkApp.getInstance().getAvatarLoader());
        rvMembers.setAdapter(memberAdapter);
    }

    @Override
    protected void onStart() {
        super.onStart();
        sessionManager.addListener(this);
        loadSessionInfo();
    }

    @Override
    protected void onStop() {
        sessionManager.removeListener(this);
        super.onStop();
    }

    private void loadSessionInfo() {
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
                bindSession(session);
                if (sessionManager.isActive(sessionId)) {
                    loadMembers();
                } else {
                    showOfflineMembers();
                }
            });
        });
    }

    private void bindSession(Session session) {
        tvRoomName.setText(session.displayName());
        tvAnnouncement.setText(TextUtils.isEmpty(session.announcement)
                ? getString(R.string.server_info_no_announcement)
                : session.announcement);
        tvServerIp.setText(TextUtils.isEmpty(session.host) ? "-" : session.host);
        tvPort.setText(String.valueOf(session.port));
        tvAiStatus.setText(session.aiEnabled
                ? R.string.server_info_ai_enabled
                : R.string.server_info_ai_disabled);

        if (TextUtils.isEmpty(session.serverVersionName)) {
            tvServerVersion.setText(R.string.server_info_unknown_version);
        } else {
            tvServerVersion.setText(session.serverVersionName);
        }
    }

    private void loadMembers() {
        if (!sessionManager.isActive(sessionId)) {
            showOfflineMembers();
            return;
        }
        database.execute(() -> {
            List<Member> all = memberDao.queryBySession(sessionId);
            List<Member> online = new ArrayList<>();
            for (Member member : all) {
                if (member.online) {
                    online.add(member);
                }
            }
            database.runOnMain(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (!sessionManager.isActive(sessionId)) {
                    showOfflineMembers();
                    return;
                }
                memberAdapter.setSessionId(sessionId);
                memberAdapter.setSelfUserId(sessionManager.getSelfUserId());
                memberAdapter.setMembers(online);
                tvMemberTitle.setText(getString(
                        R.string.server_info_members_format, online.size()));
                boolean empty = online.isEmpty();
                tvMembersEmpty.setText(R.string.server_info_no_members);
                tvMembersEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                rvMembers.setVisibility(empty ? View.GONE : View.VISIBLE);
            });
        });
    }

    private void showOfflineMembers() {
        memberAdapter.setSessionId(sessionId);
        memberAdapter.setSelfUserId("");
        memberAdapter.setMembers(new ArrayList<>());
        tvMemberTitle.setText(R.string.online_members);
        tvMembersEmpty.setText(R.string.server_info_offline_members);
        tvMembersEmpty.setVisibility(View.VISIBLE);
        rvMembers.setVisibility(View.GONE);
    }

    @Override
    public void onSessionsChanged() {
        loadSessionInfo();
    }

    @Override
    public void onMembersChanged(long changedSessionId) {
        if (changedSessionId == sessionId) {
            loadMembers();
        }
    }

    @Override
    public void onConnectionError(String message) {
        loadSessionInfo();
    }
}
