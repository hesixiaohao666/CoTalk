package com.example.cotalkproject.model;

/**
 * 客户端消息。senderId/头像版本仅内部使用；UI 只显示昵称/头像。
 */
public class Message {

    public static final int STATUS_SENDING = 0;
    public static final int STATUS_SENT = 1;
    public static final int STATUS_FAILED = 2;

    public long id;
    public long sessionId;
    public String msgId;
    public String clientMsgId;
    public long seq;
    public String senderId;
    public String senderName;
    public int senderAvatarVersion;
    public String content;
    public String msgType = "text";
    public long timestamp;
    public boolean isSelf;
    public int status = STATUS_SENT;
}
