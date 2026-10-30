package com.example.cotalkproject.net;

import org.json.JSONObject;

public interface ConnectionListener {

    void onFrame(ServerConnection source, JSONObject frame);

    void onDisconnected(ServerConnection source, String reason);
}
