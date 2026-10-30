package com.example.cotalkproject.model;

/**
 * 房间成员缓存。userId 仅内部路由/头像使用，UI 只显示昵称。
 */
public class Member {

    public long sessionId;
    public String userId;
    public String nickname;
    public int avatarVersion;
    public boolean online;
    public long lastSeen;
}
