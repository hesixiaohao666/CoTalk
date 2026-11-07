package com.example.cotalkproject.net;

import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 单个服务器的 TCP 连接：join、NDJSON 收发与心跳。
 * 重连策略由 SessionManager 统一管理。
 */
public class ServerConnection {

    private final String host;
    private final int port;
    private final ConnectionListener listener;

    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;
    private ScheduledExecutorService heartbeat;

    private volatile boolean connected;
    private volatile boolean manuallyClosed;

    public ServerConnection(String host, int port, ConnectionListener listener) {
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    /**
     * 同步连接并完成 join，返回 join_ok 帧。调用方应在 IO 线程执行。
     */
    public JSONObject connectAndJoin(String nickname,
                                     String clientTag,
                                     String avatarData,
                                     String signature,
                                     String authKey,
                                     int clientVersionCode,
                                     String clientVersionName) throws Exception {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 8000);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(10000);

        reader = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.UTF_8));
        writer = new BufferedWriter(new OutputStreamWriter(
                socket.getOutputStream(), StandardCharsets.UTF_8));

        handshake(authKey, clientVersionCode, clientVersionName);

        JSONObject joinData = new JSONObject();
        joinData.put("nickname", nickname);
        joinData.put("clientTag", clientTag);
        if (signature != null && !signature.isEmpty()) {
            joinData.put("signature", signature);
        }
        if (socket.getLocalAddress() != null) {
            joinData.put("clientIp",
                    socket.getLocalAddress().getHostAddress());
        }
        if (avatarData != null && !avatarData.isEmpty()) {
            joinData.put("avatar", avatarData);
        }
        JSONObject joinFrame = Protocol.frame(Protocol.JOIN, joinData);
        joinFrame.put("reqId", "join-" + System.currentTimeMillis());
        send(joinFrame);

        String line = reader.readLine();
        if (line == null) {
            throw new IOException("服务器关闭了连接");
        }

        JSONObject response = new JSONObject(line);
        String type = response.optString("type", "");
        if (Protocol.ERROR.equals(type)) {
            throwServerError(response, "加入失败");
        }
        if (!Protocol.JOIN_OK.equals(type)) {
            throw new IOException("服务器返回异常：" + type);
        }

        socket.setSoTimeout(0);
        return response;
    }

    private void handshake(String authKey,
                           int clientVersionCode,
                           String clientVersionName) throws Exception {
        JSONObject helloData = new JSONObject();
        helloData.put("clientVersionCode", clientVersionCode);
        helloData.put("clientVersionName", clientVersionName);

        String reqId = "hello-" + System.currentTimeMillis();
        JSONObject hello = Protocol.frame(Protocol.HELLO, helloData);
        hello.put("reqId", reqId);
        send(hello);

        JSONObject challenge = readFrame();
        throwIfError(challenge);
        if (!Protocol.AUTH_CHALLENGE.equals(challenge.optString("type", ""))) {
            throw new IOException("服务器未返回密钥挑战");
        }

        JSONObject challengeData = challenge.optJSONObject("data");
        String nonceBase64 = challengeData == null
                ? ""
                : challengeData.optString("nonce", "");
        if (nonceBase64.isEmpty()) {
            throw new IOException("服务器密钥挑战为空");
        }

        byte[] nonce = Base64.decode(nonceBase64, Base64.NO_WRAP);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                authKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String proof = Base64.encodeToString(
                mac.doFinal(nonce), Base64.NO_WRAP);

        JSONObject authData = new JSONObject();
        authData.put("proof", proof);
        JSONObject auth = Protocol.frame(Protocol.AUTH_RESPONSE, authData);
        auth.put("reqId", reqId);
        send(auth);

        JSONObject ok = readFrame();
        throwIfError(ok);
        if (!Protocol.HELLO_OK.equals(ok.optString("type", ""))) {
            throw new IOException("服务器未确认密钥验证");
        }
    }

    private JSONObject readFrame() throws Exception {
        String line = reader.readLine();
        if (line == null) {
            throw new IOException("服务器关闭了连接");
        }
        return new JSONObject(line);
    }

    private void throwIfError(JSONObject frame) throws IOException {
        if (!Protocol.ERROR.equals(frame.optString("type", ""))) {
            return;
        }
        throwServerError(frame, "服务器拒绝连接");
    }

    private void throwServerError(JSONObject frame, String fallback) throws IOException {
        JSONObject data = frame.optJSONObject("data");
        String message = data == null
                ? fallback
                : data.optString("message", fallback);
        int code = data == null ? 0 : data.optInt("code", 0);
        if (code == Protocol.ERR_AUTH_FAILED) {
            throw new AuthKeyException(message);
        }
        throw new IOException(message);
    }

    /**
     * join 成功后开始读线程与心跳。
     */
    public void start() {
        connected = true;
        manuallyClosed = false;

        Thread readThread = new Thread(this::readLoop, "cotalk-client-reader");
        readThread.start();
        startHeartbeat();
    }

    private void readLoop() {
        String reason = "连接已断开";
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                try {
                    JSONObject frame = new JSONObject(line);
                    listener.onFrame(this, frame);
                } catch (Exception ignored) {
                    // 跳过坏帧
                }
            }
        } catch (Exception e) {
            if (e.getMessage() != null && !e.getMessage().isEmpty()) {
                reason = e.getMessage();
            }
        } finally {
            closeSocket();
            stopHeartbeat();
            if (!manuallyClosed) {
                listener.onDisconnected(this, reason);
            }
        }
    }

    private void startHeartbeat() {
        heartbeat = Executors.newSingleThreadScheduledExecutor();
        heartbeat.scheduleAtFixedRate(() -> {
            if (!connected || manuallyClosed) {
                return;
            }
            try {
                JSONObject data = new JSONObject();
                data.put("t", System.currentTimeMillis());
                send(Protocol.frame(Protocol.PING, data));
            } catch (Exception e) {
                closeSocket();
                stopHeartbeat();
                if (!manuallyClosed) {
                    listener.onDisconnected(this, "心跳发送失败");
                }
            }
        }, 25, 25, TimeUnit.SECONDS);
    }

    public synchronized void send(JSONObject frame) throws IOException {
        if (writer == null) {
            throw new IOException("尚未连接服务器");
        }
        writer.write(frame.toString());
        writer.write('\n');
        writer.flush();
    }

    public void close() {
        manuallyClosed = true;
        connected = false;
        stopHeartbeat();
        closeSocket();
    }

    private void closeSocket() {
        connected = false;
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // 忽略
        }
    }

    private void stopHeartbeat() {
        if (heartbeat != null) {
            heartbeat.shutdownNow();
            heartbeat = null;
        }
    }

    public boolean isConnected() {
        return connected && !manuallyClosed;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }
}
