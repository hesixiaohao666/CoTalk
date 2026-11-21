package com.example.cotalkproject.net;

import java.io.IOException;

/**
 * 服务端版本不在客户端支持区间内；不应继续自动重连。
 */
public class VersionMismatchException extends IOException {

    public VersionMismatchException(String message) {
        super(message);
    }
}
