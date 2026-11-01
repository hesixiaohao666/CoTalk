package com.example.cotalkproject.net;

import java.io.IOException;

/**
 * 服务端明确返回连接密钥错误（3001）。
 */
public class AuthKeyException extends IOException {

    public AuthKeyException(String message) {
        super(message);
    }
}
