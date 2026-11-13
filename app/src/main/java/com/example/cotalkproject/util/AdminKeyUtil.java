package com.example.cotalkproject.util;

import android.util.Base64;

import java.security.GeneralSecurityException;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 管理员挑战-响应：PBKDF2 verifier + HMAC-SHA256 proof。
 * 与服务端一致，salt/nonce/proof 使用 Base64 URL 无填充。
 */
public final class AdminKeyUtil {

    private static final int ENCODE_FLAGS =
            Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING;
    private static final int DECODE_FLAGS =
            Base64.URL_SAFE | Base64.NO_WRAP;

    private AdminKeyUtil() {
    }

    public static String createProof(String adminKey,
                                     String saltBase64,
                                     int iterations,
                                     String nonceBase64)
            throws GeneralSecurityException {
        if (adminKey == null || adminKey.isEmpty()) {
            throw new IllegalArgumentException("管理员密钥不能为空");
        }
        if (iterations <= 0) {
            throw new IllegalArgumentException("迭代次数非法");
        }
        byte[] salt = Base64.decode(saltBase64, DECODE_FLAGS);
        char[] password = adminKey.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        byte[] verifier;
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(
                    "PBKDF2WithHmacSHA256");
            verifier = factory.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
            java.util.Arrays.fill(password, '\0');
        }

        byte[] nonce = Base64.decode(nonceBase64, DECODE_FLAGS);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(verifier, "HmacSHA256"));
        return Base64.encodeToString(mac.doFinal(nonce), ENCODE_FLAGS);
    }
}
