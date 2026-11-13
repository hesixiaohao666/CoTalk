package com.example.cotalkproject.model;

/**
 * 一个服务器 = 一个房间会话。
 */
public class Session {

    public long id;
    public String host;
    public int port;
    public String roomName;
    public String announcement;
    public long lastSeq;
    public long lastReadSeq;
    public String lastMessage;
    public long lastTime;
    public int unread;
    public long lastConnected;
    public boolean aiEnabled;
    public String serverVersionName = "";
    public int serverVersionCode;
    public String roomAvatarId = "";
    public int roomAvatarVersion;
    public boolean adminEnabled;
    public boolean pushEnabled = true;
    public boolean aiProEnabled;

    public String endpoint() {
        return host + ":" + port;
    }

    public String displayName() {
        if (roomName == null || roomName.trim().isEmpty()) {
            return endpoint();
        }
        return roomName;
    }
}
