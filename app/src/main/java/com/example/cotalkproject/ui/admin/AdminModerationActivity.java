package com.example.cotalkproject.ui.admin;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.example.cotalkproject.CoTalkApp;
import com.example.cotalkproject.R;
import com.example.cotalkproject.data.AppDatabase;
import com.example.cotalkproject.data.MemberDao;
import com.example.cotalkproject.model.JoinRequest;
import com.example.cotalkproject.model.Member;
import com.example.cotalkproject.net.SessionManager;
import com.example.cotalkproject.util.UiUtil;
import androidx.appcompat.widget.AppCompatButton;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理员入群审核与成员管理。
 */
public class AdminModerationActivity extends AppCompatActivity {

    public static final String EXTRA_SESSION_ID = "session_id";

    private long sessionId;
    private LinearLayout content;
    private LinearLayout pendingCard;
    private LinearLayout membersCard;
    private SessionManager sessionManager;
    private MemberDao memberDao;
    private int dp;

    public static Intent createIntent(Context context, long sessionId) {
        Intent intent = new Intent(context, AdminModerationActivity.class);
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
        setContentView(R.layout.activity_admin_moderation);
        UiUtil.applyLightSystemBars(this);
        UiUtil.applyEdgeToEdgeInsets(
                findViewById(R.id.admin_moderation_root),
                findViewById(R.id.title_bar));

        TextView back = findViewById(R.id.btn_back);
        back.setVisibility(View.VISIBLE);
        back.setOnClickListener(v -> finish());
        TextView title = findViewById(R.id.tv_title);
        title.setText(R.string.admin_moderation_title);
        TextView refresh = findViewById(R.id.btn_action_refresh);
        refresh.setVisibility(View.VISIBLE);
        refresh.setOnClickListener(v -> refresh());

        content = findViewById(R.id.admin_moderation_content);
        dp = Math.round(getResources().getDisplayMetrics().density);
        sessionManager = CoTalkApp.getInstance().getSessionManager();
        AppDatabase database = CoTalkApp.getInstance().getDatabase();
        memberDao = new MemberDao(database);

        if (!sessionManager.isActive(sessionId)
                || !sessionManager.isSelfAdmin()) {
            Toast.makeText(this, R.string.room_admin_not_admin,
                    Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        refresh();
    }

    private void refresh() {
        content.removeAllViews();
        pendingCard = addSection(R.string.admin_moderation_pending);
        membersCard = addSection(R.string.admin_moderation_members);
        sessionManager.requestJoinRequests(
                new SessionManager.JoinRequestsCallback() {
                    @Override
                    public void onSuccess(List<JoinRequest> requests) {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        if (requests.isEmpty()) {
                            addNote(pendingCard, getString(
                                    R.string.admin_moderation_no_pending));
                        } else {
                            for (JoinRequest request : requests) {
                                addRequestRow(request);
                            }
                        }
                        loadMembers();
                    }

                    @Override
                    public void onError(String message, int code) {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        addNote(pendingCard, getString(
                                R.string.admin_moderation_action_failed,
                                message));
                        loadMembers();
                    }
                });
    }

    private void loadMembers() {
        AppDatabase database = CoTalkApp.getInstance().getDatabase();
        database.execute(() -> {
            List<Member> members = memberDao.queryBySession(sessionId);
            database.runOnMain(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (members.isEmpty()) {
                    addNote(membersCard,
                            getString(R.string.admin_moderation_no_members));
                    return;
                }
                String selfId = sessionManager.getSelfUserId();
                for (Member member : members) {
                    addMemberRow(member, selfId);
                }
            });
        });
    }

    private void addRequestRow(JoinRequest request) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp * 8, 0, dp * 8);

        TextView info = new TextView(this);
        info.setText(getString(R.string.admin_moderation_pending_item,
                request.nickname, request.username));
        info.setTextSize(15);
        info.setTextColor(getColor(R.color.text_primary));
        row.addView(info, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button approve = smallButton(getString(
                R.string.admin_moderation_approve), true);
        approve.setOnClickListener(v -> review(request, true));
        row.addView(approve);
        Button reject = smallButton(getString(
                R.string.admin_moderation_reject), false);
        reject.setOnClickListener(v -> review(request, false));
        row.addView(reject);
        pendingCard.addView(row);
    }

    private void review(JoinRequest request, boolean approve) {
        sessionManager.reviewJoinRequest(request.identityId, approve,
                new SessionManager.AdminAuthCallback() {
                    @Override
                    public void onSuccess() {
                        if (!isFinishing() && !isDestroyed()) {
                            refresh();
                        }
                    }

                    @Override
                    public void onError(String message, int code) {
                        toast(getString(
                                R.string.admin_moderation_action_failed,
                                message));
                    }
                });
    }

    private void addMemberRow(Member member, String selfId) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp * 8, 0, dp * 8);

        TextView info = new TextView(this);
        String username = TextUtils.isEmpty(member.username)
                ? "" : "（@" + member.username + "）";
        String state = member.online
                ? getString(R.string.admin_moderation_online)
                : getString(R.string.admin_moderation_offline);
        info.setText(member.nickname + username + " · " + state);
        info.setTextSize(15);
        info.setTextColor(getColor(R.color.text_primary));
        row.addView(info, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        boolean self = !TextUtils.isEmpty(selfId)
                && selfId.equals(member.userId);
        if (!self && !member.ai && !TextUtils.isEmpty(member.userId)) {
            Button kick = smallButton(getString(
                    R.string.admin_moderation_kick), false);
            kick.setTextColor(getColor(R.color.danger));
            kick.setOnClickListener(v -> confirmKick(member));
            row.addView(kick);
        }
        membersCard.addView(row);
    }

    private void confirmKick(Member member) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.admin_moderation_kick_title)
                .setMessage(getString(
                        R.string.admin_moderation_kick_message,
                        member.nickname))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.admin_moderation_kick,
                        (dialog, which) -> kick(member))
                .show();
    }

    private void kick(Member member) {
        sessionManager.kickMember(member.userId,
                new SessionManager.AdminAuthCallback() {
                    @Override
                    public void onSuccess() {
                        if (!isFinishing() && !isDestroyed()) {
                            toast(getString(
                                    R.string.admin_moderation_kick_done,
                                    member.nickname));
                            refresh();
                        }
                    }

                    @Override
                    public void onError(String message, int code) {
                        toast(getString(
                                R.string.admin_moderation_action_failed,
                                message));
                    }
                });
    }

    private LinearLayout addSection(int titleRes) {
        TextView header = new TextView(this);
        header.setText(titleRes);
        header.setTextSize(14);
        header.setTextColor(getColor(R.color.text_secondary));
        header.setPadding(0, dp * 12, 0, dp * 8);
        content.addView(header);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp * 16, dp * 12, dp * 16, dp * 12);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        content.addView(card, params);
        return card;
    }

    private void addNote(LinearLayout card, String text) {
        TextView note = new TextView(this);
        note.setText(text);
        note.setTextSize(14);
        note.setTextColor(getColor(R.color.text_hint));
        note.setPadding(0, dp * 6, 0, dp * 6);
        card.addView(note);
    }

    private Button smallButton(String text, boolean primary) {
        AppCompatButton button = new AppCompatButton(this);
        button.setText(text);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setMinWidth(dp * 68);
        button.setMinHeight(0);
        button.setPadding(dp * 12, 0, dp * 12, 0);
        button.setBackgroundResource(primary
                ? R.drawable.btn_round_dark : R.drawable.btn_round_light);
        button.setTextColor(getColor(primary
                ? R.color.text_on_accent : R.color.text_primary));
        button.setStateListAnimator(null);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp * 38);
        params.setMarginStart(dp * 6);
        button.setLayoutParams(params);
        return button;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
