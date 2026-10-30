package com.example.cotalkproject.net;

import android.os.Handler;
import android.os.Looper;

import com.example.cotalkproject.data.AppDatabase;
import com.example.cotalkproject.data.LocalUserDao;
import com.example.cotalkproject.data.MemberDao;
import com.example.cotalkproject.data.SessionDao;
import com.example.cotalkproject.model.LocalUser;
import com.example.cotalkproject.model.Member;
import com.example.cotalkproject.model.Session;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单活动连接管理器：负责连接、join、成员/消息摘要更新和会话列表通知。
 * Step 7 会在此基础上加重连。
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
    }

    private final AppDatabase database;
    private final LocalUserDao localUserDao;
    private final SessionDao sessionDao;
    private final MemberDao memberDao;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong connectGeneration = new AtomicLong();
    // 连接/join 会阻塞最多数秒，不能占用单线程的数据库队列。
    private final ExecutorService connectExecutor = Executors.newSingleThreadExecutor();

    private volatile ServerConnection connection;
    private volatile long activeSessionId = -1;
    private volatile String selfUserId = "";
    private volatile boolean connecting;

    public SessionManager(AppDatabase database) {
        this.database = database;
        this.localUserDao = new LocalUserDao(database);
        this.sessionDao = new SessionDao(database);
        this.memberDao = new MemberDao(database);
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void connect(String host, int port, ConnectCallback callback) {
        if (connecting) {
            callback.onError("正在连接，请稍候");
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
                    callback.onError("本地账号不存在");
                    notifySessionsChanged();
                    notifyConnectionError("本地账号不存在");
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
                JSONObject joinOk = conn.connectAndJoin(user.nickname, user.clientTag);
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
                JSONArray membersArray = data.optJSONArray("members");
                JSONArray recent = data.optJSONArray("recent");

                String roomName = room == null ? "" : room.optString("name", "");
                String announcement = room == null ? "" : room.optString("announcement", "");
                long lastSeq = room == null ? 0 : room.optLong("lastSeq", 0);

                String lastMessage = null;
                long lastTime = 0;
                if (recent != null && recent.length() > 0) {
                    JSONObject last = recent.optJSONObject(recent.length() - 1);
                    if (last != null) {
                        String sender = last.optString("senderName", "");
                        String content = last.optString("content", "");
                        boolean system = "system".equals(last.optString("type", "text"));
                        lastMessage = system || sender.isEmpty()
                                ? content
                                : sender + ": " + content;
                        lastTime = last.optLong("ts", 0);
                    }
                }

                Session session = sessionDao.upsert(
                        host, port, roomName, announcement, lastSeq,
                        lastMessage, lastTime, System.currentTimeMillis());
                if (session == null) {
                    throw new IllegalStateException("保存会话失败");
                }

                List<Member> members = parseMembers(membersArray, session.id);
                memberDao.replaceAll(session.id, members);

                selfUserId = self == null ? "" : self.optString("id", "");
                activeSessionId = session.id;
                connection = conn;
                connecting = false;
                conn.start();

                database.runOnMain(() -> {
                    callback.onSuccess(session);
                    notifySessionsChanged();
                    notifyMembersChanged(session.id);
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
                database.runOnMain(() -> {
                    callback.onError(message);
                    notifySessionsChanged();
                });
            }
        });
    }

    public void disconnect() {
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
        database.execute(() -> {
            sessionDao.delete(sessionId);
            memberDao.deleteBySession(sessionId);
            database.runOnMain(this::notifySessionsChanged);
        });
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
            String content = data.optString("content", "");
            String sender = data.optString("senderName", "");
            boolean system = "system".equals(data.optString("type", "text"));
            String preview = system || sender.isEmpty()
                    ? content
                    : sender + ": " + content;
            long ts = data.optLong("ts", System.currentTimeMillis());
            long seq = data.optLong("seq", 0);
            // 系统提示更新摘要，但不计入未读。
            boolean incrementUnread = !system;
            database.execute(() -> {
                sessionDao.updateLastMessage(
                        sessionId, preview, ts, seq, incrementUnread);
                database.runOnMain(this::notifySessionsChanged);
            });
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
            return;
        }

        if (Protocol.ERROR.equals(type)) {
            JSONObject data = frame.optJSONObject("data");
            String message = data == null ? "服务器错误" : data.optString("message", "服务器错误");
            notifyConnectionError(message);
        }
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

        database.execute(() -> {
            if (sessionId > 0) {
                memberDao.markAllOffline(sessionId);
            }
            database.runOnMain(() -> {
                notifySessionsChanged();
                if (sessionId > 0) {
                    notifyMembersChanged(sessionId);
                }
                notifyConnectionError("连接已断开：" + reason);
            });
        });
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
}
