package com.example.cotalkproject.net;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 客户端协议常量与帧封装。
 */
public final class Protocol {

    public static final int VERSION = 3000;

    public static final String HELLO = "hello";
    public static final String AUTH_CHALLENGE = "auth_challenge";
    public static final String AUTH_RESPONSE = "auth_response";
    public static final String HELLO_OK = "hello_ok";

    public static final String JOIN = "join";
    public static final String CHAT = "chat";
    public static final String HISTORY_REQ = "history_req";
    public static final String PROFILE_UPDATE = "profile_update";
    public static final String AVATAR_REQ = "avatar_req";
    public static final String PING = "ping";
    public static final String LEAVE = "leave";

    public static final String JOIN_OK = "join_ok";
    public static final String MESSAGE_ACK = "message_ack";
    public static final String MESSAGE = "message";
    public static final String HISTORY_RES = "history_res";
    public static final String MEMBER_LIST = "member_list";
    public static final String AVATAR_RES = "avatar_res";
    public static final String SYSTEM = "system";
    public static final String ERROR = "error";
    public static final String PONG = "pong";

    public static final int ERR_AUTH_FAILED = 3001;
    public static final int ERR_CLIENT_VERSION = 3002;
    public static final int ERR_PROTOCOL = 3003;
    public static final int ERR_HELLO_TIMEOUT = 3004;
    public static final int ERR_AUTH_TIMEOUT = 3005;

    private Protocol() {
    }

    public static JSONObject frame(String type, JSONObject data) throws JSONException {
        JSONObject frame = new JSONObject();
        frame.put("v", VERSION);
        frame.put("type", type);
        frame.put("data", data == null ? new JSONObject() : data);
        return frame;
    }
}
