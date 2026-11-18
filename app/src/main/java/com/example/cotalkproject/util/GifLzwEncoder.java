package com.example.cotalkproject.util;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * GIF LZW 编码（纯 Java，便于单元验证）。输出为 255 字节分块的原始数据。
 */
public final class GifLzwEncoder {

    private GifLzwEncoder() {
    }

    public static byte[] encode(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BitWriter writer = new BitWriter(out);
        int minCodeSize = 8;
        int clear = 1 << minCodeSize;
        int end = clear + 1;
        int next = end + 1;
        int codeSize = minCodeSize + 1;
        Map<Integer, Integer> dictionary = new HashMap<>();
        writer.write(clear, codeSize);
        if (data == null || data.length == 0) {
            writer.write(end, codeSize);
            writer.flush();
            return out.toByteArray();
        }
        int prefix = data[0] & 0xFF;
        for (int i = 1; i < data.length; i++) {
            int k = data[i] & 0xFF;
            int key = (prefix << 8) | k;
            Integer code = dictionary.get(key);
            if (code != null) {
                prefix = code;
                continue;
            }
            writer.write(prefix, codeSize);
            if (next < 4096) {
                dictionary.put(key, next);
                next++;
                if (next > (1 << codeSize) && codeSize < 12) {
                    codeSize++;
                }
            } else {
                writer.write(clear, codeSize);
                dictionary.clear();
                next = end + 1;
                codeSize = minCodeSize + 1;
            }
            prefix = k;
        }
        writer.write(prefix, codeSize);
        writer.write(end, codeSize);
        writer.flush();
        return out.toByteArray();
    }

    private static final class BitWriter {
        private final ByteArrayOutputStream out;
        private int current;
        private int bits;

        BitWriter(ByteArrayOutputStream out) {
            this.out = out;
        }

        void write(int code, int codeSize) {
            current |= code << bits;
            bits += codeSize;
            while (bits >= 8) {
                out.write(current & 0xFF);
                current >>>= 8;
                bits -= 8;
            }
        }

        void flush() {
            if (bits > 0) {
                out.write(current & 0xFF);
                current = 0;
                bits = 0;
            }
        }
    }
}
