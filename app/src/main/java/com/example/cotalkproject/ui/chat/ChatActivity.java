package com.example.cotalkproject.ui.chat;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cotalkproject.CoTalkApp;
import com.example.cotalkproject.R;
import com.example.cotalkproject.data.AppDatabase;
import com.example.cotalkproject.data.LocalUserDao;
import com.example.cotalkproject.data.MessageDao;
import com.example.cotalkproject.data.SessionDao;
import com.example.cotalkproject.model.LocalUser;
import com.example.cotalkproject.model.Message;
import com.example.cotalkproject.net.Protocol;
import com.example.cotalkproject.net.SessionManager;
import com.example.cotalkproject.util.UiUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ChatActivity extends AppCompatActivity implements SessionManager.ChatListener {

    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_ROOM_NAME = "room_name";

    private static final int HISTORY_LIMIT = 50;
    private static final int MAX_MESSAGE_LENGTH = 500;
    private static final long SEND_TIMEOUT_MS = 10_000L;

    private long sessionId;
    private String roomName;
    private String selfNickname = "我";

    private RecyclerView rvMessages;
    private ChatAdapter adapter;
    private LinearLayoutManager layoutManager;
    private EditText etMessage;
    private Button btnSend;
    private TextView tvBanner;
    private TextView tvEmpty;
    private TextView tvHistoryHint;

    private AppDatabase database;
    private MessageDao messageDao;
    private SessionDao sessionDao;
    private SessionManager sessionManager;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Runnable> pendingTimeouts = new HashMap<>();
    private final Map<String, String> pendingPreviews = new HashMap<>();

    private boolean historyHasMore = true;
    private boolean loadingHistory;
    private boolean atBottom = true;

    public static Intent createIntent(Context context, long sessionId, String roomName) {
        Intent intent = new Intent(context, ChatActivity.class);
        intent.putExtra(EXTRA_SESSION_ID, sessionId);
        intent.putExtra(EXTRA_ROOM_NAME, roomName);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sessionId = getIntent().getLongExtra(EXTRA_SESSION_ID, -1);
        roomName = getIntent().getStringExtra(EXTRA_ROOM_NAME);
        if (sessionId <= 0) {
            finish();
            return;
        }

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_chat);
        UiUtil.applyLightSystemBars(this);
        UiUtil.applyChatInsets(
                findViewById(R.id.chat_root),
                findViewById(R.id.title_bar),
                findViewById(R.id.input_bar)
        );

        TextView btnBack = findViewById(R.id.btn_back);
        btnBack.setVisibility(View.VISIBLE);
        btnBack.setOnClickListener(v -> finish());

        if (TextUtils.isEmpty(roomName)) {
            roomName = getString(R.string.chat_title_default);
        }
        TextView title = findViewById(R.id.tv_title);
        title.setText(roomName);

        rvMessages = findViewById(R.id.rv_messages);
        etMessage = findViewById(R.id.et_message);
        btnSend = findViewById(R.id.btn_send);
        tvBanner = findViewById(R.id.tv_connection_banner);
        tvEmpty = findViewById(R.id.tv_chat_empty);
        tvHistoryHint = findViewById(R.id.tv_history_hint);

        database = CoTalkApp.getInstance().getDatabase();
        messageDao = new MessageDao(database);
        sessionDao = new SessionDao(database);
        sessionManager = CoTalkApp.getInstance().getSessionManager();

        layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        rvMessages.setLayoutManager(layoutManager);
        adapter = new ChatAdapter(this::retryMessage);
        rvMessages.setAdapter(adapter);
        rvMessages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                atBottom = !recyclerView.canScrollVertically(1);
                if (dy < 0
                        && layoutManager.findFirstVisibleItemPosition() == 0
                        && adapter.getItemCount() > 0) {
                    loadOlder();
                }
            }
        });

        btnSend.setOnClickListener(v -> sendMessage());
        etMessage.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage();
                return true;
            }
            return false;
        });

        loadSelfNickname();
        loadLocalMessages(true, -1);
        markRead();
    }

    @Override
    protected void onStart() {
        super.onStart();
        sessionManager.setChatListener(this);
        sessionManager.setOpenChatSession(sessionId);
        if (sessionManager.isActive(sessionId)) {
            hideBanner();
            btnSend.setEnabled(true);
            requestHistory(null, "history-latest", true);
        } else {
            showBanner(getString(R.string.offline_banner));
            btnSend.setEnabled(false);
        }
        markRead();
    }

    @Override
    protected void onStop() {
        sessionManager.setOpenChatSession(-1);
        markRead();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        sessionManager.clearChatListener(this);
        for (Runnable runnable : pendingTimeouts.values()) {
            handler.removeCallbacks(runnable);
        }
        pendingTimeouts.clear();
        super.onDestroy();
    }

    private void loadSelfNickname() {
        LocalUserDao userDao = new LocalUserDao(database);
        database.execute(() -> {
            LocalUser user = userDao.get();
            if (user != null) {
                String nickname = user.nickname;
                database.runOnMain(() -> selfNickname = nickname);
            }
        });
    }

    private void loadLocalMessages(boolean scrollToBottom, int keepPosition) {
        database.execute(() -> {
            List<Message> messages = messageDao.queryAll(sessionId);
            database.runOnMain(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                adapter.setMessages(messages);
                toggleEmpty();
                if (scrollToBottom) {
                    scrollToBottom();
                } else if (keepPosition >= 0 && !messages.isEmpty()) {
                    rvMessages.scrollToPosition(
                            Math.min(keepPosition, messages.size() - 1));
                }
            });
        });
    }

    private void toggleEmpty() {
        tvEmpty.setVisibility(adapter.getItemCount() == 0
                ? View.VISIBLE
                : View.GONE);
    }

    private void scrollToBottom() {
        rvMessages.post(() -> {
            if (adapter.getItemCount() > 0) {
                rvMessages.scrollToPosition(adapter.getItemCount() - 1);
                atBottom = true;
            }
        });
    }

    private void markRead() {
        if (sessionId > 0) {
            database.execute(() -> sessionDao.markRead(sessionId));
        }
    }

    private void sendMessage() {
        String content = etMessage.getText().toString().trim();
        if (content.isEmpty()) {
            return;
        }
        if (!sessionManager.isActive(sessionId)) {
            Toast.makeText(this, R.string.send_offline, Toast.LENGTH_SHORT).show();
            return;
        }
        if (content.length() > MAX_MESSAGE_LENGTH) {
            Toast.makeText(this, R.string.message_too_long, Toast.LENGTH_SHORT).show();
            return;
        }

        String clientMsgId = "cm-" + UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 16);
        Message message = new Message();
        message.sessionId = sessionId;
        message.clientMsgId = clientMsgId;
        message.senderId = sessionManager.getSelfUserId();
        message.senderName = selfNickname;
        message.senderAvatarVersion = 0;
        message.content = content;
        message.msgType = "text";
        message.timestamp = System.currentTimeMillis();
        message.isSelf = true;
        message.status = Message.STATUS_SENDING;

        etMessage.setText("");
        pendingPreviews.put(clientMsgId, content);

        database.execute(() -> {
            long id = messageDao.insert(message);
            database.runOnMain(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (id == -1) {
                    pendingPreviews.remove(clientMsgId);
                    etMessage.setText(content);
                    Toast.makeText(this, R.string.send_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                message.id = id;
                adapter.addMessage(message);
                toggleEmpty();
                scrollToBottom();
                scheduleTimeout(message);
                sendChatFrame(message);
            });
        });
    }

    private void sendChatFrame(Message message) {
        try {
            JSONObject data = new JSONObject();
            data.put("content", message.content);
            data.put("clientMsgId", message.clientMsgId);
            JSONObject frame = Protocol.frame(Protocol.CHAT, data);
            frame.put("reqId", "chat-" + message.clientMsgId);

            sessionManager.sendFrame(frame, new SessionManager.SendCallback() {
                @Override
                public void onSuccess() {
                    // 等 message_ack。
                }

                @Override
                public void onError(String error) {
                    markFailed(message.clientMsgId, false);
                }
            });
        } catch (JSONException e) {
            markFailed(message.clientMsgId, false);
        }
    }

    private void scheduleTimeout(Message message) {
        Runnable timeout = () -> {
            if (pendingTimeouts.remove(message.clientMsgId) != null) {
                markFailed(message.clientMsgId, true);
            }
        };
        pendingTimeouts.put(message.clientMsgId, timeout);
        handler.postDelayed(timeout, SEND_TIMEOUT_MS);
    }

    private void markFailed(String clientMsgId, boolean timeout) {
        Runnable pending = pendingTimeouts.remove(clientMsgId);
        if (pending != null) {
            handler.removeCallbacks(pending);
        }
        pendingPreviews.remove(clientMsgId);
        database.execute(() -> messageDao.updateStatus(
                sessionId, clientMsgId, Message.STATUS_FAILED, null, 0));
        loadLocalMessages(false, -1);
        if (timeout) {
            Toast.makeText(this, R.string.send_timeout, Toast.LENGTH_SHORT).show();
        }
    }

    private void retryMessage(Message message) {
        if (!sessionManager.isActive(sessionId)) {
            Toast.makeText(this, R.string.send_offline, Toast.LENGTH_SHORT).show();
            return;
        }
        if (message.clientMsgId == null) {
            return;
        }
        pendingPreviews.put(message.clientMsgId, message.content);
        database.execute(() -> messageDao.updateStatus(
                sessionId, message.clientMsgId, Message.STATUS_SENDING, null, 0));
        loadLocalMessages(false, -1);
        scheduleTimeout(message);
        sendChatFrame(message);
    }

    private void handleAck(JSONObject data) {
        String clientMsgId = data.optString("clientMsgId", "");
        if (clientMsgId.isEmpty()) {
            return;
        }
        Runnable pending = pendingTimeouts.remove(clientMsgId);
        if (pending != null) {
            handler.removeCallbacks(pending);
        }
        String preview = pendingPreviews.remove(clientMsgId);
        String msgId = data.optString("msgId", "");
        long seq = data.optLong("seq", 0);
        long ts = data.optLong("ts", System.currentTimeMillis());

        database.execute(() -> messageDao.updateStatus(
                sessionId, clientMsgId, Message.STATUS_SENT, msgId, seq));
        loadLocalMessages(false, -1);
        if (preview != null) {
            sessionManager.updateLocalSummary(
                    sessionId, "我: " + preview, ts, seq);
        }
    }

    private void handleIncoming(JSONObject data) {
        String msgId = data.optString("msgId", "");
        if (msgId.isEmpty()) {
            return;
        }
        Message message = new Message();
        message.sessionId = sessionId;
        message.msgId = msgId;
        message.seq = data.optLong("seq", 0);
        message.senderId = data.optString("senderId", "");
        message.senderName = data.optString("senderName", "");
        message.senderAvatarVersion = data.optInt("senderAvatarVersion", 0);
        message.content = data.optString("content", "");
        message.msgType = data.optString("type", "text");
        message.timestamp = data.optLong("ts", System.currentTimeMillis());
        message.isSelf = false;
        message.status = Message.STATUS_SENT;

        boolean scroll = atBottom;
        database.execute(() -> {
            messageDao.insertOrIgnore(message);
            sessionDao.markRead(sessionId);
            database.runOnMain(() -> loadLocalMessages(scroll, -1));
        });
    }

    private void handleHistory(JSONObject frame) {
        JSONObject data = frame.optJSONObject("data");
        if (data == null) {
            return;
        }
        JSONArray array = data.optJSONArray("messages");
        boolean latest = frame.optString("reqId", "").contains("latest");
        boolean hasMore = data.optBoolean("hasMore", false);
        int keepPosition = adapter.getItemCount();

        database.execute(() -> {
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject object = array.optJSONObject(i);
                    if (object == null) {
                        continue;
                    }
                    String msgId = object.optString("msgId", "");
                    if (msgId.isEmpty()) {
                        continue;
                    }
                    Message message = new Message();
                    message.sessionId = sessionId;
                    message.msgId = msgId;
                    message.seq = object.optLong("seq", 0);
                    message.senderId = object.optString("senderId", "");
                    message.senderName = object.optString("senderName", "");
                    message.senderAvatarVersion = object.optInt(
                            "senderAvatarVersion", 0);
                    message.content = object.optString("content", "");
                    message.msgType = object.optString("type", "text");
                    message.timestamp = object.optLong("ts", System.currentTimeMillis());
                    message.isSelf = object.optBoolean("self", false);
                    message.status = Message.STATUS_SENT;
                    messageDao.insertOrIgnore(message);
                }
            }
            sessionDao.markRead(sessionId);
            database.runOnMain(() -> {
                loadingHistory = false;
                historyHasMore = hasMore;
                tvHistoryHint.setVisibility(View.GONE);
                loadLocalMessages(latest, latest ? -1 : keepPosition);
            });
        });
    }

    private void requestHistory(Long beforeSeq, String reqId, boolean latest) {
        if (!sessionManager.isActive(sessionId)) {
            return;
        }
        try {
            JSONObject data = new JSONObject();
            data.put("limit", HISTORY_LIMIT);
            if (beforeSeq != null) {
                data.put("beforeSeq", beforeSeq);
            }
            JSONObject frame = Protocol.frame(Protocol.HISTORY_REQ, data);
            frame.put("reqId", reqId);
            sessionManager.sendFrame(frame, new SessionManager.SendCallback() {
                @Override
                public void onSuccess() {
                }

                @Override
                public void onError(String error) {
                    if (!latest) {
                        loadingHistory = false;
                        tvHistoryHint.setVisibility(View.GONE);
                    }
                }
            });
        } catch (JSONException e) {
            loadingHistory = false;
            tvHistoryHint.setVisibility(View.GONE);
        }
    }

    private void loadOlder() {
        if (loadingHistory || !historyHasMore
                || !sessionManager.isActive(sessionId)) {
            return;
        }
        long oldestSeq = 0;
        for (Message message : adapter.getMessages()) {
            if (message.seq > 0) {
                oldestSeq = oldestSeq == 0
                        ? message.seq
                        : Math.min(oldestSeq, message.seq);
            }
        }
        if (oldestSeq <= 0) {
            historyHasMore = false;
            return;
        }
        loadingHistory = true;
        tvHistoryHint.setText(R.string.history_loading);
        tvHistoryHint.setVisibility(View.VISIBLE);
        requestHistory(oldestSeq, "history-older", false);
    }

    private void showBanner(String text) {
        tvBanner.setText(text);
        tvBanner.setVisibility(View.VISIBLE);
    }

    private void hideBanner() {
        tvBanner.setVisibility(View.GONE);
    }

    @Override
    public void onChatFrame(JSONObject frame) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        // 只处理当前活动会话的帧；离线会话可能打开着另一个活动连接。
        if (!sessionManager.isActive(sessionId)) {
            return;
        }
        String type = frame.optString("type", "");
        if (Protocol.MESSAGE.equals(type)) {
            JSONObject data = frame.optJSONObject("data");
            if (data != null) {
                handleIncoming(data);
            }
        } else if (Protocol.MESSAGE_ACK.equals(type)) {
            JSONObject data = frame.optJSONObject("data");
            if (data != null) {
                handleAck(data);
            }
        } else if (Protocol.HISTORY_RES.equals(type)) {
            handleHistory(frame);
        } else if (Protocol.SYSTEM.equals(type)) {
            JSONObject data = frame.optJSONObject("data");
            if (data != null) {
                Toast.makeText(this, data.optString("content", ""),
                        Toast.LENGTH_SHORT).show();
            }
        } else if (Protocol.ERROR.equals(type)) {
            JSONObject data = frame.optJSONObject("data");
            Toast.makeText(this,
                    data == null ? "服务器错误" : data.optString("message", "服务器错误"),
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onChatDisconnected(String reason) {
        showBanner(getString(R.string.connection_banner, reason));
        btnSend.setEnabled(false);
        loadingHistory = false;
        tvHistoryHint.setVisibility(View.GONE);
    }
}
