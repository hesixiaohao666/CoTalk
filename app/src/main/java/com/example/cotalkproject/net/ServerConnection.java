package com.example.cotalkproject.net;

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
                                     String avatarData) throws Exception {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 8000);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(10000);

        reader = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.UTF_8));
        writer = new BufferedWriter(new OutputStreamWriter(
                socket.getOutputStream(), StandardCharsets.UTF_8));

        JSONObject joinData = new JSONObject();
        joinData.put("nickname", nickname);
        joinData.put("clientTag", clientTag);
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
            JSONObject data = response.optJSONObject("data");
            String message = data == null ? "加入失败" : data.optString("message", "加入失败");
            throw new IOException(message);
        }
        if (!Protocol.JOIN_OK.equals(type)) {
            throw new IOException("服务器返回异常：" + type);
        }

        socket.setSoTimeout(0);
        return response;
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
