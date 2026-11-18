package com.example.cotalkproject.model;

/**
 * 入群审核申请。
 */
public class JoinRequest {
    public String identityId = "";
    public String username = "";
    public String nickname = "";
    public boolean admin;
    public long requestedAt;
}
