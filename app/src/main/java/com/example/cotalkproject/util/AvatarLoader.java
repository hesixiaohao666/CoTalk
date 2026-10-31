package com.example.cotalkproject.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;
import android.util.LruCache;

import com.example.cotalkproject.net.Protocol;
import com.example.cotalkproject.net.SessionManager;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 远端头像缓存：内存 + 磁盘，缺失时发 avatar_req 按版本拉取。
 */
public class AvatarLoader implements SessionManager.AvatarListener {

    public interface Callback {
        void onAvatar(Bitmap bitmap);
    }

    private final Context context;
    private final SessionManager sessionManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    // 单位是 KB，上限约 16MB。
    private final LruCache<String, Bitmap> memoryCache =
            new LruCache<String, Bitmap>(16 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return Math.max(1, value.getByteCount() / 1024);
                }
            };

    private final Map<String, List<Callback>> pending = new HashMap<>();
    private final Set<String> requested = new HashSet<>();
    private final Map<String, String> requestKeys = new HashMap<>();

    public AvatarLoader(Context context, SessionManager sessionManager) {
        this.context = context.getApplicationContext();
        this.sessionManager = sessionManager;
        sessionManager.addAvatarListener(this);
    }

    public void load(long sessionId,
                     String userId,
                     int version,
                     String nickname,
                     Callback callback) {
        if (callback == null) {
            return;
        }
        if (version <= 0 || TextUtils.isEmpty(userId)) {
            mainHandler.post(() -> callback.onAvatar(null));
            return;
        }
        String key = key(sessionId, userId, version);
        Bitmap cached = memoryCache.get(key);
        if (cached != null) {
            mainHandler.post(() -> callback.onAvatar(cached));
            return;
        }

        ioExecutor.execute(() -> {
            Bitmap disk = loadDisk(sessionId, userId, version);
            mainHandler.post(() -> {
                if (disk != null) {
                    memoryCache.put(key, disk);
                    callback.onAvatar(disk);
                } else {
                    addPending(key, callback);
                    request(sessionId, userId, version, key);
                }
            });
        });
    }

    private void request(long sessionId, String userId, int version, String key) {
        if (sessionManager.getActiveSessionId() != sessionId) {
            fail(key);
            return;
        }
        if (!requested.add(key)) {
            return;
        }
        try {
            JSONObject data = new JSONObject();
            data.put("userId", userId);
            data.put("version", version);
            JSONObject frame = Protocol.frame(Protocol.AVATAR_REQ, data);
            String reqId = "avatar|" + sessionId + "|" + userId + "|" + version;
            frame.put("reqId", reqId);
            requestKeys.put(reqId, key);
            sessionManager.sendFrame(frame, new SessionManager.SendCallback() {
                @Override
                public void onSuccess() {
                }

                @Override
                public void onError(String error) {
                    fail(key);
                }
            });
        } catch (Exception e) {
            fail(key);
        }
    }

    @Override
    public void onAvatarFrame(JSONObject frame) {
        JSONObject data = frame.optJSONObject("data");
        if (data == null) {
            return;
        }
        String reqId = frame.optString("reqId", "");
        String key = requestKeys.remove(reqId);
        String userId = data.optString("userId", "");
        int version = data.optInt("version", 0);
        String dataUri = data.optString("data", "");
        if (version <= 0 || dataUri.isEmpty() || key == null) {
            if (key != null) {
                fail(key);
            }
            return;
        }

        String[] parts = key.split("\\|");
        if (parts.length < 2) {
            fail(key);
            return;
        }
        long sessionId;
        try {
            sessionId = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            fail(key);
            return;
        }
        String actualKey = key(sessionId, userId, version);

        ioExecutor.execute(() -> {
            Bitmap bitmap = null;
            try {
                byte[] bytes = decodeBytes(dataUri);
                bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap != null) {
                    saveDisk(sessionId, userId, version, bytes);
                }
            } catch (Exception ignored) {
                // 解码失败按无头像处理
            }
            final Bitmap result = bitmap;
            mainHandler.post(() -> {
                requested.remove(key);
                if (result == null) {
                    fail(key);
                    return;
                }
                memoryCache.put(actualKey, result);
                deliver(key, result);
                if (!key.equals(actualKey)) {
                    deliver(actualKey, result);
                }
            });
        });
    }

    private void deliver(String key, Bitmap bitmap) {
        List<Callback> callbacks = pending.remove(key);
        if (callbacks != null) {
            for (Callback callback : callbacks) {
                callback.onAvatar(bitmap);
            }
        }
    }

    private void fail(String key) {
        requested.remove(key);
        List<Callback> callbacks = pending.remove(key);
        if (callbacks != null) {
            for (Callback callback : callbacks) {
                callback.onAvatar(null);
            }
        }
    }

    private void addPending(String key, Callback callback) {
        List<Callback> callbacks = pending.get(key);
        if (callbacks == null) {
            callbacks = new ArrayList<>();
            pending.put(key, callbacks);
        }
        callbacks.add(callback);
    }

    private Bitmap loadDisk(long sessionId, String userId, int version) {
        File file = diskFile(sessionId, userId, version);
        if (!file.exists()) {
            return null;
        }
        return BitmapFactory.decodeFile(file.getAbsolutePath());
    }

    private void saveDisk(long sessionId, String userId, int version, byte[] bytes) {
        try {
            File file = diskFile(sessionId, userId, version);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(bytes);
            }
        } catch (Exception ignored) {
            // 磁盘缓存失败不影响显示
        }
    }

    private File diskFile(long sessionId, String userId, int version) {
        File dir = new File(new File(context.getFilesDir(), "avatars"),
                String.valueOf(sessionId));
        return new File(dir, safe(userId) + "_" + version + ".img");
    }

    private String key(long sessionId, String userId, int version) {
        return sessionId + "|" + userId + "|" + version;
    }

    private String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private byte[] decodeBytes(String dataUri) {
        String payload = dataUri;
        int comma = dataUri.indexOf(',');
        if (dataUri.startsWith("data:") && comma > 0) {
            payload = dataUri.substring(comma + 1);
        }
        return Base64.decode(payload, Base64.NO_WRAP);
    }
}
