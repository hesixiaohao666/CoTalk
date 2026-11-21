package com.example.cotalkproject.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.example.cotalkproject.BuildConfig;
import com.example.cotalkproject.CoTalkApp;
import com.example.cotalkproject.data.AppDatabase;
import com.example.cotalkproject.data.ProfileDao;
import com.example.cotalkproject.data.MemberDao;
import com.example.cotalkproject.data.MessageDao;
import com.example.cotalkproject.data.Prefs;
import com.example.cotalkproject.data.SessionDao;
import com.example.cotalkproject.model.Profile;
import com.example.cotalkproject.model.JoinRequest;
import com.example.cotalkproject.model.Member;
import com.example.cotalkproject.model.Message;
import com.example.cotalkproject.model.Session;
import com.example.cotalkproject.util.AdminKeyUtil;
import com.example.cotalkproject.util.AppForegroundTracker;
import com.example.cotalkproject.util.ImageUtil;
import com.example.cotalkproject.util.NotificationHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
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

        /**
         * 管理员模式房间在密钥验证后需要群聊身份验证。
         */
        default void onIdentityRequired(IdentityRequest request) {
            onError("需要群聊身份验证");
        }
    }

    public static final class IdentityCredentials {
        public final String username;
        public final String password;

        IdentityCredentials(String username, String password) {
            this.username = username;
            this.password = password;
        }
    }

    public static final class IdentityRequest {
        public final String host;
        public final String portHost;
        public final int port;
        public final String authKey;
        public final boolean joinReviewRequired;

        IdentityRequest(String host, int port, String authKey,
                        boolean joinReviewRequired) {
            this.host = host;
            this.port = port;
            this.portHost = host + ":" + port;
            this.authKey = authKey;
            this.joinReviewRequired = joinReviewRequired;
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

    public interface AdminAuthCallback {
        void onSuccess();

        void onError(String message, int code);
    }

    public interface RoomUpdateCallback {
        void onSuccess();

        void onError(String message, int code);
    }

    public interface JoinRequestsCallback {
        void onSuccess(List<JoinRequest> requests);

        void onError(String message, int code);
    }

    public interface AvatarListener {
        void onAvatarFrame(JSONObject frame);
    }

    private final AppDatabase database;
    private final Prefs prefs;
    private final ProfileDao profileDao;
    private final SessionDao sessionDao;
    private final MemberDao memberDao;
    private final MessageDao messageDao;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, IdentityCredentials> ephemeralIdentities =
            new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<AvatarListener> avatarListeners =
            new CopyOnWriteArrayList<>();
    private final AtomicLong connectGeneration = new AtomicLong();
    private final Object adminAuthLock = new Object();
    // 连接/join 会阻塞最多数秒，不能占用单线程的数据库队列。
    private final ExecutorService connectExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService cryptoExecutor = Executors.newSingleThreadExecutor();

    private volatile ServerConnection connection;
    private volatile long activeSessionId = -1;
    private volatile String activeRoomName = "";
    private volatile String selfUserId = "";
    private volatile int selfAvatarVersion;
    private volatile boolean pushEnabled = true;
    private volatile boolean selfAdmin;
    private volatile boolean identityMode;
    private volatile AdminAuthCallback pendingAdminAuthCallback;
    private volatile AdminAuthCallback pendingAdminUnbindCallback;
    private volatile String pendingAdminKey = "";
    private volatile ServerConnection pendingAdminConnection;
    private volatile RoomUpdateCallback pendingRoomUpdateCallback;
    private volatile JoinRequestsCallback pendingJoinRequestsCallback;
    private volatile AdminAuthCallback pendingJoinReviewCallback;
    private volatile AdminAuthCallback pendingKickCallback;
    private volatile AdminAuthCallback pendingVerifyCallback;
    private volatile AdminAuthCallback pendingExitCallback;
    private volatile String activeIdentityUsername = "";
    private volatile boolean imageEnabled = true;
    private volatile boolean voiceEnabled = true;
    private volatile boolean emojiEnabled = true;
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
    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    if (shouldReconnect
                            && prefs.isAutoReconnect()
                            && reconnectSessionId > 0
                            && !connecting
                            && connection == null) {
                        mainHandler.removeCallbacks(reconnectTask);
                        mainHandler.postDelayed(reconnectTask, 500L);
                    }
                }
            };

    public SessionManager(AppDatabase database, Prefs prefs) {
        this.database = database;
        this.prefs = prefs;
        this.profileDao = new ProfileDao(database);
        this.sessionDao = new SessionDao(database);
        this.memberDao = new MemberDao(database);
        this.messageDao = new MessageDao(database);
        registerNetworkCallback();
    }

    private void registerNetworkCallback() {
        CoTalkApp app = CoTalkApp.getInstance();
        if (app == null) {
            return;
        }
        ConnectivityManager manager = (ConnectivityManager)
                app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return;
        }
        try {
            manager.registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException ignored) {
            // 系统限制或缺少网络状态权限时退化为普通重连。
        }
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

    /**
     * 身份页面完成后提供一个仅本次连接使用的凭据，避免把“记住密码”当作强制持久化。
     */
    public void setEphemeralIdentity(String host, int port,
                                     String username, String password) {
        ephemeralIdentities.put(host + ":" + port,
                new IdentityCredentials(username, password));
    }

    private IdentityCredentials takeEphemeralIdentity(String host, int port) {
        return ephemeralIdentities.remove(host + ":" + port);
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


    public String getActiveIdentityUsername() {
        return activeIdentityUsername;
    }

    /**
     * 危险操作前在线校验最后一个活跃身份密码。
     */
    public void verifyIdentityPassword(String username,
                                       String password,
                                       AdminAuthCallback callback) {
        if (callback == null) {
            return;
        }
        ServerConnection current = connection;
        if (current == null || !current.isConnected()
                || activeSessionId <= 0) {
            postAdminError(callback, "请先连接群聊后再操作", 0);
            return;
        }
        synchronized (adminAuthLock) {
            if (pendingVerifyCallback != null) {
                postAdminError(callback, "正在校验，请稍候", 0);
                return;
            }
            pendingVerifyCallback = callback;
        }
        try {
            JSONObject data = new JSONObject();
            data.put("username", username == null ? "" : username);
            data.put("password", password == null ? "" : password);
            JSONObject frame = Protocol.frame(
                    Protocol.IDENTITY_VERIFY_REQ, data);
            frame.put("reqId", "verify-" + System.currentTimeMillis());
            sendFrame(frame, new SendCallback() {
                @Override
                public void onSuccess() {
                }

                @Override
                public void onError(String message) {
                    AdminAuthCallback pending;
                    synchronized (adminAuthLock) {
                        pending = pendingVerifyCallback;
                        pendingVerifyCallback = null;
                    }
                    if (pending != null) {
                        postAdminError(pending, message, 0);
                    }
                }
            });
        } catch (JSONException e) {
            AdminAuthCallback pending;
            synchronized (adminAuthLock) {
                pending = pendingVerifyCallback;
                pendingVerifyCallback = null;
            }
            if (pending != null) {
                postAdminError(pending, "身份密码校验失败", 0);
            }
        }
    }

    public void exitRoom(String username,
                         String password,
                         AdminAuthCallback callback) {
        if (callback == null) {
            return;
        }
        ServerConnection current = connection;
        if (current == null || !current.isConnected()
                || activeSessionId <= 0) {
            postAdminError(callback, "请先连接群聊", 0);
            return;
        }
        synchronized (adminAuthLock) {
            if (pendingExitCallback != null) {
                postAdminError(callback, "正在退出，请稍候", 0);
                return;
            }
            pendingExitCallback = callback;
        }
        try {
            JSONObject data = new JSONObject();
            data.put("username", username == null ? "" : username);
            data.put("password", password == null ? "" : password);
            JSONObject frame = Protocol.frame(
                    Protocol.EXIT_ROOM_REQ, data);
            frame.put("reqId", "exit-" + System.currentTimeMillis());
            sendFrame(frame, new SendCallback() {
                @Override
                public void onSuccess() {
                }

                @Override
                public void onError(String message) {
                    AdminAuthCallback pending;
                    synchronized (adminAuthLock) {
                        pending = pendingExitCallback;
                        pendingExitCallback = null;
                    }
                    if (pending != null) {
                        postAdminError(pending, message, 0);
                    }
                }
            });
        } catch (JSONException e) {
            AdminAuthCallback pending;
            synchronized (adminAuthLock) {
                pending = pendingExitCallback;
                pendingExitCallback = null;
            }
            if (pending != null) {
                postAdminError(pending, "退出群聊失败", 0);
            }
        }
    }

    private void handleIdentityVerifyResponse(JSONObject frame) {
        JSONObject data = frame.optJSONObject("data");
        boolean success = data != null && data.optBoolean("success", false);
        String message = data == null ? "身份密码不正确"
                : data.optString("message", "身份密码不正确");
        AdminAuthCallback callback;
        synchronized (adminAuthLock) {
            callback = pendingVerifyCallback;
            pendingVerifyCallback = null;
        }
        if (callback == null) {
            return;
        }
        if (success) {
            mainHandler.post(callback::onSuccess);
        } else {
            postAdminError(callback, message, 0);
        }
    }

    private void handleExitRoomResponse(JSONObject frame) {
        JSONObject data = frame.optJSONObject("data");
        boolean success = data == null || data.optBoolean("success", false);
        AdminAuthCallback callback;
        synchronized (adminAuthLock) {
            callback = pendingExitCallback;
            pendingExitCallback = null;
        }
        if (!success) {
            String message = data == null ? "退出群聊失败"
                    : data.optString("message", "退出群聊失败");
            if (callback != null) {
                postAdminError(callback, message, 0);
            }
            return;
        }
        long sessionId = activeSessionId;
        String host = reconnectHost;
        int port = reconnectPort;
        prefs.clearIdentityCredentials(host, port);
        disconnect();
        if (sessionId > 0) {
            database.execute(() -> {
                messageDao.clearSession(sessionId);
                memberDao.deleteBySession(sessionId);
                sessionDao.resetAfterExit(sessionId);
                database.runOnMain(() -> {
                    notifySessionsChanged();
                    if (callback != null) {
                        callback.onSuccess();
                    }
                });
            });
        } else if (callback != null) {
            mainHandler.post(callback::onSuccess);
        }
    }

    public void authenticateAdmin(String adminKey, AdminAuthCallback callback) {
        if (callback == null) {
            return;
        }
        ServerConnection current = connection;
        if (current == null || !current.isConnected() || activeSessionId <= 0) {
            postAdminError(callback, "请先连接启用了管理员模式的房间", 0);
            return;
        }
        if (adminKey == null || adminKey.trim().isEmpty()) {
            postAdminError(callback, "请输入管理员密钥", 0);
            return;
        }
        synchronized (adminAuthLock) {
            if (pendingAdminAuthCallback != null
                    || pendingAdminUnbindCallback != null) {
                postAdminError(callback, "正在处理管理员身份，请稍候", 0);
                return;
            }
            pendingAdminAuthCallback = callback;
            pendingAdminKey = adminKey.trim();
            pendingAdminConnection = current;
        }

        try {
            JSONObject data = new JSONObject();
            JSONObject frame = Protocol.frame(Protocol.ADMIN_AUTH_BEGIN, data);
            frame.put("reqId", "admin-begin-" + System.currentTimeMillis());
            sendFrame(frame, new SendCallback() {
                @Override
                public void onSuccess() {
                }

                @Override
                public void onError(String message) {
                    if (clearPendingAdmin(callback)) {
                        postAdminError(callback, message, 0);
                    }
                }
            });
        } catch (Exception e) {
            if (clearPendingAdmin(callback)) {
                postAdminError(callback, "管理员认证请求失败", 0);
            }
        }
    }

    public void unbindAdmin(AdminAuthCallback callback) {
        if (callback == null) {
            return;
        }
        ServerConnection current = connection;
        if (current == null || !current.isConnected() || activeSessionId <= 0) {
            postAdminError(callback, "请先连接房间", 0);
            return;
        }
        synchronized (adminAuthLock) {
            if (pendingAdminAuthCallback != null
                    || pendingAdminUnbindCallback != null) {
                postAdminError(callback, "正在处理管理员身份，请稍候", 0);
                return;
            }
            pendingAdminUnbindCallback = callback;
        }

        try {
            JSONObject data = new JSONObject();
            JSONObject frame = Protocol.frame(Protocol.ADMIN_UNBIND, data);
            frame.put("reqId", "admin-unbind-" + System.currentTimeMillis());
            sendFrame(frame, new SendCallback() {
                @Override
                public void onSuccess() {
                }

                @Override
                public void onError(String message) {
                    if (clearPendingAdminUnbind(callback)) {
                        postAdminError(callback, message, 0);
                    }
                }
            });
        } catch (Exception e) {
            if (clearPendingAdminUnbind(callback)) {
                postAdminError(callback, "解除管理员绑定请求失败", 0);
            }
        }
    }

    public void requestJoinRequests(JoinRequestsCallback callback) {
        if (callback == null) {
            return;
        }
        if (!canModerate()) {
            postJoinRequestsError(callback, moderationError(), 0);
            return;
        }
        synchronized (adminAuthLock) {
            if (pendingJoinRequestsCallback != null) {
                postJoinRequestsError(callback, "正在读取申请，请稍候", 0);
                return;
            }
            pendingJoinRequestsCallback = callback;
        }
        sendModerationFrame(Protocol.ADMIN_JOIN_REQUESTS_REQ,
                new JSONObject(), callback);
    }

    public void reviewJoinRequest(String identityId,
                                  boolean approve,
                                  AdminAuthCallback callback) {
        if (!canModerate()) {
            postAdminError(callback, moderationError(), 0);
            return;
        }
        if (identityId == null || identityId.isEmpty()) {
            postAdminError(callback, "申请不存在", 0);
            return;
        }
        synchronized (adminAuthLock) {
            if (pendingJoinReviewCallback != null) {
                postAdminError(callback, "正在处理申请，请稍候", 0);
                return;
            }
            pendingJoinReviewCallback = callback;
        }
        try {
            JSONObject data = new JSONObject();
            data.put("identityId", identityId);
            data.put("approve", approve);
            sendModerationFrame(Protocol.ADMIN_JOIN_REVIEW_REQ,
                    data, callback);
        } catch (Exception e) {
            clearJoinReview(callback);
            postAdminError(callback, "提交审核失败", 0);
        }
    }

    public void kickMember(String identityId, AdminAuthCallback callback) {
        if (!canModerate()) {
            postAdminError(callback, moderationError(), 0);
            return;
        }
        if (identityId == null || identityId.isEmpty()) {
            postAdminError(callback, "用户不存在", 0);
            return;
        }
        synchronized (adminAuthLock) {
            if (pendingKickCallback != null) {
                postAdminError(callback, "正在移除用户，请稍候", 0);
                return;
            }
            pendingKickCallback = callback;
        }
        try {
            JSONObject data = new JSONObject();
            data.put("identityId", identityId);
            sendModerationFrame(Protocol.ADMIN_KICK_REQ, data, callback);
        } catch (Exception e) {
            clearKick(callback);
            postAdminError(callback, "移除用户失败", 0);
        }
    }

    private boolean canModerate() {
        ServerConnection current = connection;
        return current != null && current.isConnected()
                && activeSessionId > 0 && selfAdmin;
    }

    private String moderationError() {
        ServerConnection current = connection;
        if (current == null || !current.isConnected() || activeSessionId <= 0) {
            return "请先连接房间";
        }
        return "当前不是管理员";
    }

    private void sendModerationFrame(String type,
                                     JSONObject data,
                                     Object callback) {
        try {
            JSONObject frame = Protocol.frame(type, data);
            frame.put("reqId", "moderation-" + System.currentTimeMillis());
            sendFrame(frame, new SendCallback() {
                @Override
                public void onSuccess() {
                }

                @Override
                public void onError(String message) {
                    if (Protocol.ADMIN_JOIN_REQUESTS_REQ.equals(type)) {
                        JoinRequestsCallback cb = pendingJoinRequestsCallback;
                        pendingJoinRequestsCallback = null;
                        if (cb != null) {
                            postJoinRequestsError(cb, message, 0);
                        }
                    } else if (Protocol.ADMIN_JOIN_REVIEW_REQ.equals(type)) {
                        AdminAuthCallback cb = pendingJoinReviewCallback;
                        pendingJoinReviewCallback = null;
                        if (cb != null) {
                            postAdminError(cb, message, 0);
                        }
                    } else if (Protocol.ADMIN_KICK_REQ.equals(type)) {
                        AdminAuthCallback cb = pendingKickCallback;
                        pendingKickCallback = null;
                        if (cb != null) {
                            postAdminError(cb, message, 0);
                        }
                    }
                }
            });
        } catch (Exception e) {
            if (callback instanceof JoinRequestsCallback) {
                pendingJoinRequestsCallback = null;
                postJoinRequestsError((JoinRequestsCallback) callback,
                        "请求发送失败", 0);
            } else if (callback instanceof AdminAuthCallback) {
                AdminAuthCallback cb = (AdminAuthCallback) callback;
                clearJoinReview(cb);
                clearKick(cb);
                postAdminError(cb, "请求发送失败", 0);
            }
        }
    }

    private void clearJoinReview(AdminAuthCallback callback) {
        synchronized (adminAuthLock) {
            if (pendingJoinReviewCallback == callback) {
                pendingJoinReviewCallback = null;
            }
        }
    }

    private void clearKick(AdminAuthCallback callback) {
        synchronized (adminAuthLock) {
            if (pendingKickCallback == callback) {
                pendingKickCallback = null;
            }
        }
    }

    private void postJoinRequestsError(JoinRequestsCallback callback,
                                       String message, int code) {
        mainHandler.post(() -> callback.onError(message, code));
    }

    public void updateRoomInfo(String name,
                               String announcement,
                               String avatarData,
                               RoomUpdateCallback callback) {
        if (callback == null) {
            return;
        }
        ServerConnection current = connection;
        if (current == null || !current.isConnected() || activeSessionId <= 0) {
            postRoomError(callback, "请先连接房间", 0);
            return;
        }
        if (!selfAdmin) {
            postRoomError(callback, "当前不是管理员身份", Protocol.ERR_ADMIN_FORBIDDEN);
            return;
        }
        synchronized (adminAuthLock) {
            if (pendingRoomUpdateCallback != null) {
                postRoomError(callback, "正在保存房间信息，请稍候", 0);
                return;
            }
            pendingRoomUpdateCallback = callback;
        }

        try {
            JSONObject data = new JSONObject();
            data.put("name", name == null ? "" : name);
            data.put("announcement", announcement == null ? "" : announcement);
            if (avatarData != null && !avatarData.isEmpty()) {
                data.put("avatar", avatarData);
            }
            JSONObject frame = Protocol.frame(Protocol.ROOM_ADMIN_UPDATE, data);
            frame.put("reqId", "room-update-" + System.currentTimeMillis());
            sendFrame(frame, new SendCallback() {
                @Override
                public void onSuccess() {
                }

                @Override
                public void onError(String message) {
                    if (clearPendingRoom(callback)) {
                        postRoomError(callback, message, 0);
                    }
                }
            });
        } catch (Exception e) {
            if (clearPendingRoom(callback)) {
                postRoomError(callback, "房间信息提交失败", 0);
            }
        }
    }

    private boolean clearPendingAdmin(AdminAuthCallback callback) {
        synchronized (adminAuthLock) {
            if (pendingAdminAuthCallback != callback) {
                return false;
            }
            pendingAdminAuthCallback = null;
            pendingAdminKey = "";
            pendingAdminConnection = null;
            return true;
        }
    }

    private boolean clearPendingAdminUnbind(AdminAuthCallback callback) {
        synchronized (adminAuthLock) {
            if (pendingAdminUnbindCallback != callback) {
                return false;
            }
            pendingAdminUnbindCallback = null;
            return true;
        }
    }

    private boolean clearPendingRoom(RoomUpdateCallback callback) {
        synchronized (adminAuthLock) {
            if (pendingRoomUpdateCallback != callback) {
                return false;
            }
            pendingRoomUpdateCallback = null;
            return true;
        }
    }

    private void postAdminError(AdminAuthCallback callback,
                                String message,
                                int code) {
        mainHandler.post(() -> callback.onError(message, code));
    }

    private void postRoomError(RoomUpdateCallback callback,
                               String message,
                               int code) {
        mainHandler.post(() -> callback.onError(message, code));
    }

    private void failPendingOperations(String message) {
        AdminAuthCallback adminCallback;
        AdminAuthCallback unbindCallback;
        RoomUpdateCallback roomCallback;
        synchronized (adminAuthLock) {
            adminCallback = pendingAdminAuthCallback;
            unbindCallback = pendingAdminUnbindCallback;
            roomCallback = pendingRoomUpdateCallback;
            pendingAdminAuthCallback = null;
            pendingAdminUnbindCallback = null;
            pendingAdminKey = "";
            pendingAdminConnection = null;
            pendingRoomUpdateCallback = null;
        }
        if (adminCallback != null) {
            postAdminError(adminCallback, message, 0);
        }
        if (unbindCallback != null) {
            postAdminError(unbindCallback, message, 0);
        }
        if (roomCallback != null) {
            postRoomError(roomCallback, message, 0);
        }
    }

    private void handleAdminChallenge(ServerConnection source, JSONObject frame) {
        AdminAuthCallback callback;
        String adminKey;
        ServerConnection expected;
        synchronized (adminAuthLock) {
            callback = pendingAdminAuthCallback;
            adminKey = pendingAdminKey;
            expected = pendingAdminConnection;
        }
        if (callback == null || adminKey.isEmpty()
                || expected == null || source != expected) {
            return;
        }

        JSONObject data = frame.optJSONObject("data");
        if (data == null) {
            if (clearPendingAdmin(callback)) {
                postAdminError(callback, "管理员挑战为空", 0);
            }
            return;
        }
        String salt = data.optString("salt", "");
        String nonce = data.optString("nonce", "");
        int iterations = data.optInt("iterations", 0);
        if (salt.isEmpty() || nonce.isEmpty() || iterations <= 0) {
            if (clearPendingAdmin(callback)) {
                postAdminError(callback, "管理员挑战格式错误", 0);
            }
            return;
        }

        cryptoExecutor.execute(() -> {
            String proof;
            try {
                proof = AdminKeyUtil.createProof(
                        adminKey, salt, iterations, nonce);
            } catch (Exception e) {
                if (clearPendingAdmin(callback)) {
                    postAdminError(callback, "管理员密钥处理失败", 0);
                }
                return;
            }

            synchronized (adminAuthLock) {
                if (pendingAdminAuthCallback != callback) {
                    return;
                }
                if (connection != expected) {
                    if (clearPendingAdmin(callback)) {
                        postAdminError(callback, "连接已断开", 0);
                    }
                    return;
                }
                pendingAdminKey = "";
            }

            try {
                JSONObject responseData = new JSONObject();
                responseData.put("proof", proof);
                JSONObject response = Protocol.frame(
                        Protocol.ADMIN_AUTH_RESPONSE, responseData);
                response.put("reqId",
                        "admin-response-" + System.currentTimeMillis());
                sendFrame(response, new SendCallback() {
                    @Override
                    public void onSuccess() {
                    }

                    @Override
                    public void onError(String message) {
                        if (clearPendingAdmin(callback)) {
                            postAdminError(callback, message, 0);
                        }
                    }
                });
            } catch (Exception e) {
                if (clearPendingAdmin(callback)) {
                    postAdminError(callback, "管理员认证响应失败", 0);
                }
            }
        });
    }

    private void handleAdminAuthOk() {
        AdminAuthCallback callback;
        synchronized (adminAuthLock) {
            callback = pendingAdminAuthCallback;
            pendingAdminAuthCallback = null;
            pendingAdminKey = "";
            pendingAdminConnection = null;
        }
        selfAdmin = true;
        if (callback != null) {
            mainHandler.post(callback::onSuccess);
        }
        notifySessionsChanged();
    }

    private void handleAdminUnbindOk() {
        AdminAuthCallback callback;
        synchronized (adminAuthLock) {
            callback = pendingAdminUnbindCallback;
            pendingAdminUnbindCallback = null;
        }
        selfAdmin = false;
        notifySessionsChanged();
        if (callback != null) {
            mainHandler.post(callback::onSuccess);
        }
    }

    private void handleAdminRevoked(JSONObject frame) {
        AdminAuthCallback authCallback;
        AdminAuthCallback unbindCallback;
        RoomUpdateCallback roomCallback;
        synchronized (adminAuthLock) {
            authCallback = pendingAdminAuthCallback;
            unbindCallback = pendingAdminUnbindCallback;
            roomCallback = pendingRoomUpdateCallback;
            pendingAdminAuthCallback = null;
            pendingAdminUnbindCallback = null;
            pendingRoomUpdateCallback = null;
            pendingAdminKey = "";
            pendingAdminConnection = null;
        }
        selfAdmin = false;
        if (authCallback != null) {
            postAdminError(authCallback, "管理员身份已被解除", 0);
        }
        if (unbindCallback != null) {
            mainHandler.post(unbindCallback::onSuccess);
        }
        if (roomCallback != null) {
            postRoomError(roomCallback, "管理员身份已被解除", 0);
        }
        notifySessionsChanged();
        notifyChatFrame(frame);
    }

    private void handleRoomUpdate(JSONObject frame) {
        JSONObject data = frame.optJSONObject("data");
        if (data == null) {
            return;
        }
        long sessionId = activeSessionId;
        String name = data.optString("name", "");
        String announcement = data.optString("announcement", "");
        String avatarId = data.optString("avatarId", "");
        int avatarVersion = data.optInt("avatarVersion", 0);
        RoomUpdateCallback callback;
        synchronized (adminAuthLock) {
            callback = pendingRoomUpdateCallback;
            pendingRoomUpdateCallback = null;
        }

        if (!name.isEmpty()) {
            activeRoomName = name;
            updatePersistentRoom(name);
        }
        database.execute(() -> {
            if (sessionId > 0) {
                sessionDao.updateRoomInfo(
                        sessionId, name, announcement, avatarId, avatarVersion);
            }
            database.runOnMain(() -> {
                notifySessionsChanged();
                notifyChatFrame(frame);
                if (callback != null) {
                    callback.onSuccess();
                }
            });
        });
    }

    private void handleServerError(JSONObject frame, JSONObject data) {
        AdminAuthCallback adminCallback;
        AdminAuthCallback unbindCallback;
        RoomUpdateCallback roomCallback;
        synchronized (adminAuthLock) {
            adminCallback = pendingAdminAuthCallback;
            unbindCallback = pendingAdminUnbindCallback;
            roomCallback = pendingRoomUpdateCallback;
            pendingAdminAuthCallback = null;
            pendingAdminUnbindCallback = null;
            pendingAdminKey = "";
            pendingAdminConnection = null;
            pendingRoomUpdateCallback = null;
        }
        String message = data == null
                ? "服务器错误"
                : data.optString("message", "服务器错误");
        int code = data == null ? 0 : data.optInt("code", 0);
        if (adminCallback != null) {
            postAdminError(adminCallback, message, code);
            return;
        }
        if (unbindCallback != null) {
            postAdminError(unbindCallback, message, code);
            return;
        }
        if (roomCallback != null) {
            postRoomError(roomCallback, message, code);
            return;
        }
        JoinRequestsCallback joinRequests;
        AdminAuthCallback joinReview;
        AdminAuthCallback kick;
        synchronized (adminAuthLock) {
            joinRequests = pendingJoinRequestsCallback;
            joinReview = pendingJoinReviewCallback;
            kick = pendingKickCallback;
            pendingJoinRequestsCallback = null;
            pendingJoinReviewCallback = null;
            pendingKickCallback = null;
        }
        if (joinRequests != null) {
            postJoinRequestsError(joinRequests, message, code);
            return;
        }
        if (joinReview != null) {
            postAdminError(joinReview, message, code);
            return;
        }
        if (kick != null) {
            postAdminError(kick, message, code);
            return;
        }
        notifyConnectionError(message);
        notifyChatFrame(frame);
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

    private void promptIdentity(ConnectCallback callback,
                                String host,
                                int port,
                                String authKey,
                                boolean joinReviewRequired) {
        connecting = false;
        final IdentityRequest request = new IdentityRequest(
                host, port, authKey, joinReviewRequired);
        database.runOnMain(() -> {
            notifySessionsChanged();
            if (callback != null) {
                callback.onIdentityRequired(request);
            }
        });
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
            Profile user = profileDao.get();
            if (user == null) {
                connecting = false;
                database.runOnMain(() -> {
                    if (callback != null) {
                        callback.onError("请先完成资料初始化");
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
                JSONObject helloOk = conn.connectHandshake(
                        authKey, BuildConfig.VERSION_CODE,
                        BuildConfig.VERSION_NAME);
                JSONObject helloData = helloOk.optJSONObject("data");
                boolean identityRequired = helloData != null
                        && helloData.optBoolean("identityRequired", false);
                String verifiedIdentityUsername = "";
                if (identityRequired) {
                    IdentityCredentials ephemeral =
                            takeEphemeralIdentity(host, port);
                    String identityUsername = ephemeral != null
                            ? ephemeral.username
                            : prefs.isIdentityAutoLogin(host, port)
                            ? prefs.getIdentityUsername(host, port) : "";
                    String identityPassword = ephemeral != null
                            ? ephemeral.password
                            : prefs.isIdentityAutoLogin(host, port)
                            ? prefs.getIdentityPassword(host, port) : "";
                    if (!identityUsername.isEmpty()
                            && !identityPassword.isEmpty()) {
                        JSONObject identity;
                        try {
                            identity = conn.identityLogin(
                                    identityUsername, identityPassword);
                            String identityType = identity.optString("type", "");
                            if (Protocol.IDENTITY_OK.equals(identityType)) {
                                verifiedIdentityUsername = identityUsername;
                            } else if (Protocol.IDENTITY_PENDING.equals(identityType)) {
                                conn.close();
                                promptIdentity(callback, host, port, authKey,
                                        helloData.optBoolean(
                                                "joinReviewRequired", true));
                                return;
                            } else {
                                prefs.clearIdentityCredentials(host, port);
                                conn.close();
                                promptIdentity(callback, host, port, authKey,
                                        helloData.optBoolean(
                                                "joinReviewRequired", true));
                                return;
                            }
                        } catch (Exception loginError) {
                            prefs.clearIdentityCredentials(host, port);
                            conn.close();
                            promptIdentity(callback, host, port, authKey,
                                    helloData.optBoolean(
                                            "joinReviewRequired", true));
                            return;
                        }
                    } else {
                        conn.close();
                        promptIdentity(callback, host, port, authKey,
                                helloData.optBoolean(
                                        "joinReviewRequired", true));
                        return;
                    }
                }
                JSONObject joinOk = conn.join(
                        user.nickname, user.clientTag, avatarData,
                        user.signature);
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
                boolean adminEnabled = server != null
                        && server.optBoolean("adminEnabled", false);
                boolean pushEnabled = server == null
                        || server.optBoolean("pushEnabled", true);
                boolean aiProEnabled = server != null
                        && server.optBoolean("aiProEnabled", false);

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
                JSONObject features = server == null
                        ? null : server.optJSONObject("features");
                imageEnabled = features == null
                        || features.optBoolean("image", true);
                voiceEnabled = features == null
                        || features.optBoolean("voice", true);
                emojiEnabled = features == null
                        || features.optBoolean("emoji", true);
                String serverVersionName = server == null
                        ? ""
                        : server.optString("versionName", "");
                int serverVersionCode = server == null
                        ? 0
                        : server.optInt("versionCode", 0);
                if (serverVersionCode < Protocol.MIN_SERVER_VERSION_CODE
                        || serverVersionCode > Protocol.MAX_SERVER_VERSION_CODE) {
                    throw new IllegalStateException("服务端版本不兼容");
                }
                Session session = sessionDao.upsert(
                        host, port, roomName, announcement, lastSeq,
                        lastMessage, lastTime, System.currentTimeMillis(),
                        aiEnabled, serverVersionName, serverVersionCode,
                        roomAvatarId, roomAvatarVersion,
                        adminEnabled, pushEnabled, aiProEnabled);
                if (session == null) {
                    throw new IllegalStateException("保存会话失败");
                }
                sessionDao.setIdentityRequired(session.id, identityRequired);
                if (!verifiedIdentityUsername.isEmpty()) {
                    sessionDao.setIdentityUsername(
                            session.id, verifiedIdentityUsername);
                }
                activeIdentityUsername = verifiedIdentityUsername;

                List<Member> members = parseMembers(membersArray, session.id);
                memberDao.replaceAll(session.id, members);
                persistRecent(recent, session.id);

                selfUserId = self == null ? "" : self.optString("id", "");
                selfAvatarVersion = self == null ? 0 : self.optInt("avatarVersion", 0);
                selfAdmin = self != null && self.optBoolean("admin", false);
                identityMode = identityRequired;
                activeSessionId = session.id;
                activeRoomName = roomName;
                this.pushEnabled = pushEnabled;
                connection = conn;
                connecting = false;
                shouldReconnect = true;
                reconnectHost = host;
                reconnectPort = port;
                reconnectAuthKey = authKey;
                reconnectSessionId = session.id;
                reconnectAttempt = 0;
                conn.start();
                CoTalkApp app = CoTalkApp.getInstance();
                if (app != null) {
                    NotificationHelper.updatePersistent(app, activeRoomName);
                }

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
                activeRoomName = "";
                selfUserId = "";
                selfAdmin = false;
                identityMode = false;
                activeIdentityUsername = "";
                connecting = false;
                failPendingOperations("连接服务器失败");
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
        activeRoomName = "";
        selfUserId = "";
        selfAdmin = false;
        identityMode = false;
        activeIdentityUsername = "";
        connecting = false;
        failPendingOperations("连接已断开");
        if (old != null) {
            old.close();
        }
        updatePersistentRoom("");
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
        activeRoomName = "";
        selfUserId = "";
        selfAvatarVersion = 0;
        selfAdmin = false;
        identityMode = false;
        activeIdentityUsername = "";
        connecting = false;
        failPendingOperations("连接已断开");
        if (old != null) {
            old.close();
        }
        updatePersistentRoom("");
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
        message.senderAdmin = object.optBoolean("senderAdmin", false);
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

    private void maybeNotifyMessage(JSONObject data,
                                    long sessionId,
                                    String preview,
                                    boolean system) {
        if (system
                || !prefs.isMessageNotify()
                || !pushEnabled
                || AppForegroundTracker.isForeground()) {
            return;
        }
        String senderId = data.optString("senderId", "");
        if (!selfUserId.isEmpty() && selfUserId.equals(senderId)) {
            return;
        }
        CoTalkApp app = CoTalkApp.getInstance();
        if (app == null) {
            return;
        }
        NotificationHelper.notifyMessage(
                app,
                sessionId,
                activeRoomName,
                data.optString("senderName", ""),
                preview);
    }

    private void updatePersistentRoom(String roomName) {
        CoTalkApp app = CoTalkApp.getInstance();
        if (app != null) {
            NotificationHelper.updatePersistent(app, roomName);
        }
    }

    private void closeActiveOnIo() {
        ServerConnection old = connection;
        long oldSessionId = activeSessionId;
        connection = null;
        activeSessionId = -1;
        activeRoomName = "";
        selfUserId = "";
        selfAdmin = false;
        identityMode = false;
        failPendingOperations("连接已断开");
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

        if (Protocol.ADMIN_AUTH_CHALLENGE.equals(type)) {
            handleAdminChallenge(source, frame);
            return;
        }

        if (Protocol.ADMIN_AUTH_OK.equals(type)) {
            handleAdminAuthOk();
            return;
        }

        if (Protocol.ADMIN_UNBIND_OK.equals(type)) {
            handleAdminUnbindOk();
            return;
        }

        if (Protocol.ADMIN_REVOKED.equals(type)) {
            handleAdminRevoked(frame);
            return;
        }

        if (Protocol.ROOM_UPDATE.equals(type)) {
            handleRoomUpdate(frame);
            return;
        }

        if (Protocol.ADMIN_JOIN_REQUESTS_RES.equals(type)) {
            handleJoinRequests(frame);
            return;
        }

        if (Protocol.ADMIN_JOIN_REVIEW_RES.equals(type)) {
            AdminAuthCallback callback;
            synchronized (adminAuthLock) {
                callback = pendingJoinReviewCallback;
                pendingJoinReviewCallback = null;
            }
            if (callback != null) {
                mainHandler.post(callback::onSuccess);
            }
            return;
        }

        if (Protocol.ADMIN_KICK_RES.equals(type)) {
            AdminAuthCallback callback;
            synchronized (adminAuthLock) {
                callback = pendingKickCallback;
                pendingKickCallback = null;
            }
            if (callback != null) {
                mainHandler.post(callback::onSuccess);
            }
            return;
        }

        if (Protocol.IDENTITY_VERIFY_RES.equals(type)) {
            handleIdentityVerifyResponse(frame);
            return;
        }

        if (Protocol.EXIT_ROOM_RES.equals(type)) {
            handleExitRoomResponse(frame);
            return;
        }

        if (Protocol.KICKED.equals(type)) {
            handleKicked(frame);
            return;
        }

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
                    selfAdmin = member.admin;
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
            maybeNotifyMessage(data, sessionId, content, system);
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
            handleServerError(frame, frame.optJSONObject("data"));
        }
    }

    private void handleJoinRequests(JSONObject frame) {
        JSONObject data = frame.optJSONObject("data");
        JSONArray array = data == null ? null
                : data.optJSONArray("requests");
        List<JoinRequest> requests = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                JoinRequest request = new JoinRequest();
                request.identityId = item.optString("identityId", "");
                request.username = item.optString("username", "");
                request.nickname = item.optString("nickname", "");
                request.admin = item.optBoolean("admin", false);
                request.requestedAt = item.optLong("requestedAt", 0);
                requests.add(request);
            }
        }
        JoinRequestsCallback callback;
        synchronized (adminAuthLock) {
            callback = pendingJoinRequestsCallback;
            pendingJoinRequestsCallback = null;
        }
        if (callback != null) {
            final List<JoinRequest> result = requests;
            mainHandler.post(() -> callback.onSuccess(result));
        }
    }

    private void handleKicked(JSONObject frame) {
        long sessionId = activeSessionId;
        shouldReconnect = false;
        cancelReconnect();
        closeActiveOnIo();
        selfAdmin = false;
        identityMode = false;
        if (sessionId > 0) {
            database.execute(() -> {
                sessionDao.setKicked(sessionId, true);
                memberDao.markAllOffline(sessionId);
                database.runOnMain(() -> {
                    notifySessionsChanged();
                    notifyMembersChanged(sessionId);
                });
            });
        }
        notifyConnectionError("您已被移出群聊");
        notifyChatFrame(frame);
        notifyChatDisconnected("您已被移出群聊");
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
        activeRoomName = "";
        selfUserId = "";
        selfAdmin = false;
        identityMode = false;
        activeIdentityUsername = "";
        connecting = false;
        failPendingOperations("连接已断开");
        updatePersistentRoom("");

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
            member.username = object.optString("username", "");
            member.avatarVersion = object.optInt("avatarVersion", 0);
            member.online = object.optBoolean("online", true);
            member.ai = object.optBoolean("ai", false);
            member.admin = object.optBoolean("admin", false);
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
        long baseDelay = RECONNECT_DELAYS[index];
        // 0–20% 随机抖动，避免多个客户端同时重连冲击服务器。
        long jitter = (long) (baseDelay * 0.2
                * ThreadLocalRandom.current().nextDouble());
        reconnectAttempt++;
        mainHandler.removeCallbacks(reconnectTask);
        mainHandler.postDelayed(reconnectTask, baseDelay + jitter);
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

    public String getActiveRoomName() {
        return activeRoomName;
    }

    public boolean isSelfAdmin() {
        return selfAdmin;
    }

    /**
     * 当前连接是否由服务端身份系统接管（此时隐藏旧的房间管理员密钥绑定）。
     */
    public boolean isIdentityMode() {
        return identityMode;
    }

    public String getSelfUserId() {
        return selfUserId;
    }

    public int getSelfAvatarVersion() {
        return selfAvatarVersion;
    }

    public boolean isImageEnabled() {
        return imageEnabled;
    }

    public boolean isVoiceEnabled() {
        return voiceEnabled;
    }

    public boolean isEmojiEnabled() {
        return emojiEnabled;
    }
}
