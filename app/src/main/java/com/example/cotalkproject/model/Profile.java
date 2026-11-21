package com.example.cotalkproject.model;

/**
 * 本机资料。只保存展示与连接所需的本地信息，不包含登录密码。
 */
public class Profile {

    public String username;
    public String nickname;
    public String signature;
    public String avatarPath;
    public String clientTag;
    public long createdAt;
    public long updatedAt;
}
