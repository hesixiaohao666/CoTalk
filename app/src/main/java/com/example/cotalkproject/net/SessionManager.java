package com.example.cotalkproject.net;

import android.os.Handler;
import android.os.Looper;

import com.example.cotalkproject.BuildConfig;
import com.example.cotalkproject.data.AppDatabase;
import com.example.cotalkproject.data.LocalUserDao;
import com.example.cotalkproject.data.MemberDao;
import com.example.cotalkproject.data.MessageDao;
import com.example.cotalkproject.data.Prefs;
import com.example.cotalkproject.data.SessionDao;
import com.example.cotalkproject.model.LocalUser;
import com.example.cotalkproject.model.Member;
import com.example.cotalkproject.model.Message;
import com.example.cotalkproject.model.Session;
import com.example.cotalkproject.util.ImageUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单活动连接管理器：连接、加入、成员/消息缓存、自动重连与聊天帧分发。
 */
public class SessionManager {

    public interface Listener {
        void onSessionsChanged();

        void onMembersChanged(long sessionId);

        void onConnectionError(String message);
    }

    public interface ConnectCallback {
        void onSuccess(Session session);

        void onError(String message);

        /**
         * 服务端返回密钥错误（3001）。默认仍按普通错误处理。
         */
        default void onAuthKeyError(String message) {
            onError(message);
        }
    }

    public interface ChatListener {
        void onChatFrame(JSONObject frame);

        void onChatDisconnected(String reason);

        void onChatReconnected();
    }

    public interface SendCallback {
        void onSuccess();

        void onError(String message);
    }

    public interface AvatarListener {
        void onAvatarFrame(JSONObject frame);
    }

    private final AppDatabase database;
    private final Prefs prefs;
    private final LocalUserDao localUserDao;
    private final SessionDao sessionDao;
    private final MemberDao memberDao;
    private final MessageDao messageDao;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<AvatarListener> avatarListeners =
            new CopyOnWriteArrayList<>();
    private final AtomicLong connectGeneration = new AtomicLong();
    // 连接/join 会阻塞最多数秒，不能占用单线程的数据库队列。
    private final ExecutorService connectExecutor = Executors.newSingleThreadExecutor();

    private volatile ServerConnection connection;
    private volatile long activeSessionId = -1;
    private volatile String selfUserId = "";
    private volatile int selfAvatarVersion;
    private volatile boolean connecting;
    private volatile ChatListener chatListener;
    private volatile long openChatSessionId = -1;

    private static final long[] RECONNECT_DELAYS =
            {2000L, 4000L, 8000L, 15000L, 30000L};
    private volatile boolean shouldReconnect;
    private volatile int reconnectAttempt;
    private volatile String reconnectHost = "";
    private volatile int reconnectPort;
    private volatile String reconnectAuthKey = "";
    private volatile long reconnectSessionId = -1;
    private final Runnable reconnectTask = this::runReconnect;

    public SessionManager(AppDatabase database, Prefs prefs) {
        this.database = database;
        this.prefs = prefs;
        this.localUserDao = new LocalUserDao(database);
        this.sessionDao = new SessionDao(database);
        this.memberDao = new MemberDao(database);
        this.messageDao = new MessageDao(database);
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void addAvatarListener(AvatarListener listener) {
        if (listener != null && !avatarListeners.contains(listener)) {
            avatarListeners.add(listener);
        }
    }

    public void removeAvatarListener(AvatarListener listener) {
        avatarListeners.remove(listener);
    }

    public void setChatListener(ChatListener listener) {
        chatListener = listener;
    }

    public void clearChatListener(ChatListener listener) {
        if (chatListener == listener) {
            chatListener = null;
        }
    }

    public void setOpenChatSession(long sessionId) {
        openChatSessionId = sessionId;
    }

    public void sendFrame(JSONObject frame, SendCallback callback) {
        ServerConnection current = connection;
        if (current == null || !current.isConnected()) {
            if (callback != null) {
                callback.onError("未连接服务器");
            }
            return;
        }
        database.execute(() -> {
            try {
                current.send(frame);
                if (callback != null) {
                    database.runOnMain(callback::onSuccess);
                }
            } catch (Exception e) {
                String message = e.getMessage() == null ? "发送失败" : e.getMessage();
                if (callback != null) {
                    database.runOnMain(() -> callback.onError(message));
                }
            }
        });
    }

    public void updateLocalSummary(long sessionId,
                                   String preview,
                                   long time,
                                   long seq) {
        database.execute(() -> {
            sessionDao.updateLastMessage(sessionId, preview, time, seq, false);
            database.runOnMain(this::notifySessionsChanged);
        });
    }

    public void connect(String host, int port, String authKey,
                        ConnectCallback callback) {
        shouldReconnect = false;
        cancelReconnect();
        startConnect(host, port, authKey == null ? "" : authKey,
                callback, false);
    }

    private void startConnect(String host,
                              int port,
                              String authKey,
                              ConnectCallback callback,
                              boolean reconnect) {
        if (connecting) {
            if (callback != null) {
                callback.onError("正在连接，请稍候");
            } else if (reconnect) {
                scheduleReconnect();
            }
            return;
        }
        connecting = true;
        long generation = connectGeneration.incrementAndGet();
        notifySessionsChanged();

        connectExecutor.execute(() -> {
            LocalUser user = localUserDao.get();
            if (user == null) {
                connecting = false;
                database.runOnMain(() -> {
                    if (callback != null) {
                        callback.onError("本地账号不存在");
                    }
                    notifySessionsChanged();
                });
                return;
            }

            closeActiveOnIo();

            ServerConnection conn = new ServerConnection(host, port,
                    new ConnectionListener() {
                        @Override
                        public void onFrame(ServerConnection source, JSONObject frame) {
                            handleFrame(source, frame);
                        }

                        @Override
                        public void onDisconnected(ServerConnection source, String reason) {
                            handleDisconnected(source, reason);
                        }
                    });

            try {
                String avatarData = user.avatarPath == null
                        ? ""
                        : ImageUtil.fileToDataUri(user.avatarPath);
                JSONObject joinOk = conn.connectAndJoin(
                        user.nickname, user.clientTag, avatarData,
                        user.signature,
                        authKey, BuildConfig.VERSION_CODE,
                        BuildConfig.VERSION_NAME);
                if (generation != connectGeneration.get()) {
                    conn.close();
                    return;
                }

                JSONObject data = joinOk.optJSONObject("data");
                if (data == null) {
                    throw new IllegalStateException("join_ok 缺少 data");
                }
                JSONObject room = data.optJSONObject("room");
                JSONObject self = data.optJSONObject("self");
                JSONObject server = data.optJSONObject("server");
                JSONArray membersArray = data.optJSONArray("members");
                JSONArray recent = data.optJSONArray("recent");

                String roomName = room == null ? "" : room.optString("name", "");
                String announcement = room == null ? "" : room.optString("announcement", "");
                long lastSeq = room == null ? 0 : room.optLong("lastSeq", 0);
                String roomAvatarId = room == null
                        ? "" : room.optString("avatarId", "");
                int roomAvatarVersion = room == null
                        ? 0 : room.optInt("avatarVersion", 0);

                String lastMessage = null;
                long lastTime = 0;
                if (recent != null && recent.length() > 0) {
                    JSONObject last = recent.optJSONObject(recent.length() - 1);
                    if (last != null) {
                        String sender = last.optString("senderName", "");
                        String content = messagePreview(last);
                        boolean system = "system".equals(last.optString("type", "text"));
                        lastMessage = system || sender.isEmpty()
                                ? content
                                : sender + ": " + content;
                        lastTime = last.optLong("ts", 0);
                    }
                }

                boolean aiEnabled = server != null
                        && server.optBoolean("aiEnabled", false);
                String serverVersionName = server == null
                        ? ""
                        : server.optString("versionName", "");
                int serverVersionCode = server == null
                        ? 0
                        : server.optInt("versionCode", 0);
                Session session = sessionDao.upsert(
                        host, port, roomName, announcement, lastSeq,
                        lastMessage, lastTime, System.currentTimeMillis(),
                        aiEnabled, serverVersionName, serverVersionCode,
                        roomAvatarId, roomAvatarVersion);
                if (session == null) {
                    throw new IllegalStateException("保存会话失败");
                }

                List<Member> members = parseMembers(membersArray, session.id);
                memberDao.replaceAll(session.id, members);
                persistRecent(recent, session.id);

                selfUserId = self == null ? "" : self.optString("id", "");
                selfAvatarVersion = self == null ? 0 : self.optInt("avatarVersion", 0);
                activeSessionId = session.id;
                connection = conn;
                connecting = false;
                shouldReconnect = true;
                reconnectHost = host;
                reconnectPort = port;
                reconnectAuthKey = authKey;
                reconnectSessionId = session.id;
                reconnectAttempt = 0;
                conn.start();

                database.runOnMain(() -> {
                    if (callback != null) {
                        callback.onSuccess(session);
                    }
                    notifySessionsChanged();
                    notifyMembersChanged(session.id);
                    if (reconnect) {
                        notifyChatReconnected();
                    }
                });
            } catch (Exception e) {
                conn.close();
                if (generation != connectGeneration.get()) {
                    return;
                }
                connection = null;
                activeSessionId = -1;
                selfUserId = "";
                connecting = false;
                String message = e.getMessage() == null
                        ? "连接服务器失败"
                        : e.getMessage();
                boolean authKeyError = e instanceof AuthKeyException;
                if (reconnect && shouldReconnect && !authKeyError) {
                    scheduleReconnect();
                    database.runOnMain(this::notifySessionsChanged);
                } else {
                    if (authKeyError && reconnect) {
                        shouldReconnect = false;
                    }
                    database.runOnMain(() -> {
                        if (callback != null) {
                            if (authKeyError) {
                                callback.onAuthKeyError(message);
                            } else {
                                callback.onError(message);
                            }
                        } else if (authKeyError && reconnect) {
                            notifyConnectionError("连接密钥错误，请重新连接");
                        }
                        notifySessionsChanged();
                    });
                }
            }
        });
    }

    public void disconnect() {
        shouldReconnect = false;
        cancelReconnect();
        connectGeneration.incrementAndGet();
        ServerConnection old = connection;
        long sessionId = activeSessionId;
        connection = null;
        activeSessionId = -1;
        selfUserId = "";
        connecting = false;
        if (old != null) {
            old.close();
        }
        notifyChatDisconnected("已断开连接");

        database.execute(() -> {
            if (sessionId > 0) {
                memberDao.markAllOffline(sessionId);
            }
            database.runOnMain(() -> {
                notifySessionsChanged();
                if (sessionId > 0) {
                    notifyMembersChanged(sessionId);
                }
            });
        });
    }

    public void removeSession(long sessionId) {
        if (activeSessionId == sessionId) {
            disconnect();
        }
        if (reconnectSessionId == sessionId) {
            shouldReconnect = false;
            cancelReconnect();
        }
        database.execute(() -> {
            sessionDao.delete(sessionId);
            memberDao.deleteBySession(sessionId);
            prefs.clearHistoryMarker(sessionId);
            database.runOnMain(this::notifySessionsChanged);
        });
    }

    public void removeAllSessions() {
        shouldReconnect = false;
        cancelReconnect();
        connectGeneration.incrementAndGet();
        ServerConnection old = connection;
        connection = null;
        activeSessionId = -1;
        selfUserId = "";
        selfAvatarVersion = 0;
        connecting = false;
        if (old != null) {
            old.close();
        }
        notifyChatDisconnected("已断开连接");

        database.execute(() -> {
            List<Session> sessions = sessionDao.queryAll();
            for (Session session : sessions) {
                prefs.clearHistoryMarker(session.id);
            }
            sessionDao.deleteAll();
            database.runOnMain(this::notifySessionsChanged);
        });
    }

    public void setAutoReconnectEnabled(boolean enabled) {
        prefs.setAutoReconnect(enabled);
        if (!enabled) {
            shouldReconnect = false;
            cancelReconnect();
        }
    }

    public void refreshSessions() {
        notifySessionsChanged();
    }

    public void clearAllHistory() {
        database.execute(() -> {
            List<Session> sessions = sessionDao.queryAll();
            for (Session session : sessions) {
                long clearSeq = session.lastSeq;
                for (Message message : messageDao.queryAll(session.id)) {
                    clearSeq = Math.max(clearSeq, message.seq);
                }
                prefs.markHistoryCleared(session.id, clearSeq);
            }
            messageDao.deleteAll();
            sessionDao.clearSummaries();
            database.runOnMain(this::notifySessionsChanged);
        });
    }

    private void persistRecent(JSONArray recent, long sessionId) {
        if (recent == null) {
            return;
        }
        long clearSeq = prefs.getHistoryClearSeq(sessionId);
        for (int i = 0; i < recent.length(); i++) {
            JSONObject object = recent.optJSONObject(i);
            if (object == null) {
                continue;
            }
            long seq = object.optLong("seq", 0);
            if (clearSeq >= 0 && seq <= clearSeq) {
                continue;
            }
            Message message = messageFromJson(
                    object, sessionId, object.optBoolean("self", false));
            if (message != null) {
                messageDao.insertOrIgnore(message);
            }
        }
    }

    private Message messageFromJson(JSONObject object,
                                    long sessionId,
                                    boolean isSelf) {
        String msgId = object.optString("msgId", "");
        if (msgId.isEmpty()) {
            return null;
        }
        Message message = new Message();
        message.sessionId = sessionId;
        message.msgId = msgId;
        message.seq = object.optLong("seq", 0);
        message.senderId = object.optString("senderId", "");
        message.senderName = object.optString("senderName", "");
        message.senderAvatarVersion = object.optInt("senderAvatarVersion", 0);
        message.content = object.optString("content", "");
        message.msgType = object.optString("type", "text");
        message.fileId = object.optString("fileId", "");
        message.fileName = object.optString("fileName", "");
        message.fileMime = object.optString("fileMime", "");
        message.fileSize = object.optLong("fileSize", 0);
        message.fileExpired = object.optBoolean("fileExpired", false);
        message.timestamp = object.optLong("ts", System.currentTimeMillis());
        message.isSelf = isSelf;
        message.status = Message.STATUS_SENT;
        return message;
    }

    private static String messagePreview(JSONObject object) {
        String type = object.optString("type", "text");
        if ("image".equals(type)) {
            return "[图片]";
        }
        if ("emoji".equals(type)) {
            return "[表情]";
        }
        if ("voice".equals(type)) {
            return "[语音]";
        }
        return object.optString("content", "");
    }

    private void closeActiveOnIo() {
        ServerConnection old = connection;
        long oldSessionId = activeSessionId;
        connection = null;
        activeSessionId = -1;
        selfUserId = "";
        if (old != null) {
            old.close();
        }
        if (oldSessionId > 0) {
            memberDao.markAllOffline(oldSessionId);
        }
    }

    private void handleFrame(ServerConnection source, JSONObject frame) {
        if (source != connection) {
            return;
        }
        String type = frame.optString("type", "");

        if (Protocol.MEMBER_LIST.equals(type)) {
            long sessionId = activeSessionId;
            if (sessionId <= 0) {
                return;
            }
            JSONObject data = frame.optJSONObject("data");
            JSONArray membersArray = data == null ? null : data.optJSONArray("members");
            List<Member> members = parseMembers(membersArray, sessionId);
            for (Member member : members) {
                if (!selfUserId.isEmpty() && selfUserId.equals(member.userId)) {
                    selfAvatarVersion = member.avatarVersion;
                    break;
                }
            }
            database.execute(() -> {
                memberDao.replaceAll(sessionId, members);
                database.runOnMain(() -> notifyMembersChanged(sessionId));
            });
            return;
        }

        if (Protocol.MESSAGE.equals(type)) {
            JSONObject data = frame.optJSONObject("data");
            long sessionId = activeSessionId;
            if (data == null || sessionId <= 0) {
                return;
            }
            String content = messagePreview(data);
            String sender = data.optString("senderName", "");
            boolean system = "system".equals(data.optString("type", "text"));
            String preview = system || sender.isEmpty()
                    ? content
                    : sender + ": " + content;
            long ts = data.optLong("ts", System.currentTimeMillis());
            long seq = data.optLong("seq", 0);
            // 系统提示更新摘要；聊天页打开时也不计未读。
            boolean incrementUnread = !system && openChatSessionId != sessionId;
            Message stored = messageFromJson(data, sessionId, false);
            database.execute(() -> {
                if (stored != null) {
                    messageDao.insertOrIgnore(stored);
                }
                sessionDao.updateLastMessage(
                        sessionId, preview, ts, seq, incrementUnread);
                database.runOnMain(this::notifySessionsChanged);
            });
            notifyChatFrame(frame);
            return;
        }

        if (Protocol.MESSAGE_RECALL.equals(type)) {
            handleMessageRecall(frame);
            return;
        }

        if (Protocol.MESSAGE_ACK.equals(type)
                || Protocol.HISTORY_RES.equals(type)
                || Protocol.FILE_RES.equals(type)) {
            notifyChatFrame(frame);
            return;
        }

        if (Protocol.AVATAR_RES.equals(type)) {
            notifyAvatarFrame(frame);
            return;
        }

        if (Protocol.SYSTEM.equals(type)) {
            JSONObject data = frame.optJSONObject("data");
            long sessionId = activeSessionId;
            if (data == null || sessionId <= 0) {
                return;
            }
            String content = data.optString("content", "");
            long ts = data.optLong("ts", System.currentTimeMillis());
            database.execute(() -> {
                sessionDao.updateLastMessage(sessionId, content, ts, 0, false);
                database.runOnMain(this::notifySessionsChanged);
            });
            notifyChatFrame(frame);
            return;
        }

        if (Protocol.ERROR.equals(type)) {
            JSONObject data = frame.optJSONObject("data");
            String message = data == null ? "服务器错误" : data.optString("message", "服务器错误");
            notifyConnectionError(message);
            notifyChatFrame(frame);
        }
    }

    private void handleMessageRecall(JSONObject frame) {
        JSONObject data = frame.optJSONObject("data");
        long sessionId = activeSessionId;
        if (data == null || sessionId <= 0) {
            return;
        }
        String msgId = data.optString("msgId", "");
        String notice = data.optString("notice", "");
        long seq = data.optLong("seq", 0);
        long ts = data.optLong("ts", System.currentTimeMillis());
        if (msgId.isEmpty() || notice.isEmpty()) {
            return;
        }
        long clearSeq = prefs.getHistoryClearSeq(sessionId);
        database.execute(() -> {
            int updated = messageDao.replaceWithSystem(
                    sessionId, msgId, notice, seq, ts);
            if (updated == 0 && (clearSeq < 0 || seq > clearSeq)) {
                Message recalled = new Message();
                recalled.sessionId = sessionId;
                recalled.msgId = msgId;
                recalled.seq = seq;
                recalled.senderId = "system";
                recalled.senderName = "系统";
                recalled.content = notice;
                recalled.msgType = "system";
                recalled.timestamp = ts;
                recalled.isSelf = false;
                recalled.status = Message.STATUS_SENT;
                messageDao.insertOrIgnore(recalled);
            }
            if (seq > 0) {
                sessionDao.updateLastMessageIfLatest(
                        sessionId, notice, ts, seq);
            }
            database.runOnMain(() -> {
                notifySessionsChanged();
                notifyChatFrame(frame);
            });
        });
    }

    private void handleDisconnected(ServerConnection source, String reason) {
        if (source != connection) {
            return;
        }
        connection = null;
        long sessionId = activeSessionId;
        activeSessionId = -1;
        selfUserId = "";
        connecting = false;

        boolean canReconnect = shouldReconnect
                && prefs.isAutoReconnect()
                && sessionId > 0;
        if (canReconnect) {
            reconnectSessionId = sessionId;
        }
        String suffix = canReconnect ? "，正在重连…" : "";

        database.execute(() -> {
            if (sessionId > 0) {
                memberDao.markAllOffline(sessionId);
            }
            database.runOnMain(() -> {
                notifySessionsChanged();
                if (sessionId > 0) {
                    notifyMembersChanged(sessionId);
                }
                notifyConnectionError("连接已断开：" + reason + suffix);
                notifyChatDisconnected(reason + suffix);
            });
        });

        if (canReconnect) {
            scheduleReconnect();
        }
    }

    private List<Member> parseMembers(JSONArray array, long sessionId) {
        List<Member> members = new ArrayList<>();
        if (array == null) {
            return members;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.optJSONObject(i);
            if (object == null) {
                continue;
            }
            Member member = new Member();
            member.sessionId = sessionId;
            member.userId = object.optString("id", "");
            member.nickname = object.optString("nickname", "");
            member.avatarVersion = object.optInt("avatarVersion", 0);
            member.online = true;
            member.ai = object.optBoolean("ai", false);
            members.add(member);
        }
        return members;
    }

    private void notifySessionsChanged() {
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onSessionsChanged();
            }
        });
    }

    private void notifyMembersChanged(long sessionId) {
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onMembersChanged(sessionId);
            }
        });
    }

    private void notifyConnectionError(String message) {
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onConnectionError(message);
            }
        });
    }

    private void notifyChatFrame(JSONObject frame) {
        ChatListener listener = chatListener;
        if (listener != null) {
            mainHandler.post(() -> listener.onChatFrame(frame));
        }
    }

    private void notifyChatDisconnected(String reason) {
        ChatListener listener = chatListener;
        if (listener != null) {
            mainHandler.post(() -> listener.onChatDisconnected(reason));
        }
    }

    private void notifyChatReconnected() {
        ChatListener listener = chatListener;
        if (listener != null) {
            mainHandler.post(listener::onChatReconnected);
        }
    }

    private void notifyAvatarFrame(JSONObject frame) {
        mainHandler.post(() -> {
            for (AvatarListener listener : avatarListeners) {
                listener.onAvatarFrame(frame);
            }
        });
    }

    private void scheduleReconnect() {
        if (!shouldReconnect || reconnectSessionId <= 0 || reconnectHost.isEmpty()) {
            return;
        }
        int index = Math.min(reconnectAttempt, RECONNECT_DELAYS.length - 1);
        long delay = RECONNECT_DELAYS[index];
        reconnectAttempt++;
        mainHandler.removeCallbacks(reconnectTask);
        mainHandler.postDelayed(reconnectTask, delay);
        notifySessionsChanged();
    }

    private void runReconnect() {
        if (!shouldReconnect || reconnectSessionId <= 0) {
            return;
        }
        startConnect(reconnectHost, reconnectPort, reconnectAuthKey, null, true);
    }

    private void cancelReconnect() {
        mainHandler.removeCallbacks(reconnectTask);
        reconnectAttempt = 0;
        reconnectSessionId = -1;
    }

    public boolean isActive(long sessionId) {
        return activeSessionId > 0 && activeSessionId == sessionId;
    }

    public boolean isConnecting() {
        return connecting;
    }

    public long getActiveSessionId() {
        return activeSessionId;
    }

    public String getSelfUserId() {
        return selfUserId;
    }

    public int getSelfAvatarVersion() {
        return selfAvatarVersion;
    }
}
