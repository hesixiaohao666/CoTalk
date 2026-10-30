package com.example.cotalkproject.ui.chat;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.example.cotalkproject.R;
import com.example.cotalkproject.util.UiUtil;

/**
 * Step 5 占位聊天页；Step 6 接入消息列表、气泡和输入栏。
 */
public class ChatActivity extends AppCompatActivity {

    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_ROOM_NAME = "room_name";

    public static Intent createIntent(Context context, long sessionId, String roomName) {
        Intent intent = new Intent(context, ChatActivity.class);
        intent.putExtra(EXTRA_SESSION_ID, sessionId);
        intent.putExtra(EXTRA_ROOM_NAME, roomName);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_chat);

        UiUtil.applyLightSystemBars(this);
        UiUtil.applyEdgeToEdgeInsets(
                findViewById(R.id.chat_root),
                findViewById(R.id.title_bar)
        );

        TextView btnBack = findViewById(R.id.btn_back);
        btnBack.setVisibility(View.VISIBLE);
        btnBack.setOnClickListener(v -> finish());

        String roomName = getIntent().getStringExtra(EXTRA_ROOM_NAME);
        if (TextUtils.isEmpty(roomName)) {
            roomName = getString(R.string.chat_title_default);
        }
        TextView title = findViewById(R.id.tv_title);
        title.setText(roomName);

        TextView placeholder = findViewById(R.id.tv_chat_placeholder);
        placeholder.setText(getString(R.string.chat_placeholder, roomName));
    }
}
