package com.example.cotalkproject.model;

/**
 * 本地账号。只保存在本机 SQLite，不是服务端账号。
 */
public class LocalUser {

    public long id;
    public String username;
    public String passwordHash;
    public String salt;
    public String nickname;
    public String signature;
    public String avatarPath;
    public String clientTag;
    public long createdAt;
    public long lastLogin;
}
