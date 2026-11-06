package com.example.cotalkproject.util;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 聊天附件磁盘缓存：filesDir/chat_files。
 */
public final class ChatFileStore {

    private ChatFileStore() {
    }

    public static File findCached(Context context, String fileId, String mime) {
        if (fileId == null || fileId.isEmpty()) {
            return null;
        }
        File file = cacheFile(context, fileId, mime);
        return file.isFile() ? file : null;
    }

    public static File save(Context context, String fileId,
                            String mime, byte[] bytes) throws IOException {
        File file = cacheFile(context, fileId, mime);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建聊天文件目录");
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
        return file;
    }

    public static File cacheFile(Context context, String fileId, String mime) {
        File dir = new File(context.getFilesDir(), "chat_files");
        return new File(dir, safe(fileId) + extensionForMime(mime));
    }

    public static String extensionForMime(String mime) {
        if (mime == null) {
            return ".bin";
        }
        switch (mime.toLowerCase(java.util.Locale.ROOT)) {
            case "image/jpeg":
            case "image/jpg":
                return ".jpg";
            case "image/png":
                return ".png";
            case "image/gif":
                return ".gif";
            case "image/webp":
                return ".webp";
            case "audio/mpeg":
                return ".mp3";
            case "audio/mp4":
                return ".m4a";
            case "audio/wav":
                return ".wav";
            case "audio/ogg":
                return ".ogg";
            case "audio/aac":
                return ".aac";
            case "audio/amr":
                return ".amr";
            default:
                return ".bin";
        }
    }

    private static String safe(String value) {
        return value == null ? "file"
                : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
