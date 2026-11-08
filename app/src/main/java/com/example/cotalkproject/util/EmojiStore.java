package com.example.cotalkproject.util;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 本地表情包目录：filesDir/emojis。
 */
public class EmojiStore {

    public static final int MAX_COUNT = 89;
    public static final String ERROR_LIMIT = "EMOJI_LIMIT";

    private final Context context;
    private final File directory;

    public EmojiStore(Context context) {
        this.context = context.getApplicationContext();
        this.directory = new File(this.context.getFilesDir(), "emojis");
    }

    public File add(Uri uri) throws IOException {
        if (uri == null) {
            throw new IOException("未选择图片");
        }
        if (list().size() >= MAX_COUNT) {
            throw new IOException(ERROR_LIMIT);
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("无法创建表情包目录");
        }
        File output = ImageUtil.createImageFile(directory, ".jpg");
        try {
            return ImageUtil.compressEmojiToFile(context, uri, output);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            output.delete();
            throw e;
        }
    }

    /**
     * 把聊天中的表情保存到本地表情库；重复表情会复用已有文件。
     */
    public File importFile(File source) throws IOException {
        if (source == null || !source.isFile()) {
            throw new IOException("表情文件不存在");
        }
        if (list().size() >= MAX_COUNT) {
            throw new IOException(ERROR_LIMIT);
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("无法创建表情包目录");
        }
        File output = ImageUtil.createImageFile(directory, ".jpg");
        try {
            ImageUtil.compressEmojiToFile(context, Uri.fromFile(source), output);
            String hash = md5(output);
            if (!hash.isEmpty()) {
                for (File existing : list()) {
                    if (existing.equals(output)) {
                        continue;
                    }
                    if (hash.equals(md5(existing))) {
                        //noinspection ResultOfMethodCallIgnored
                        output.delete();
                        return existing;
                    }
                }
            }
            return output;
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            output.delete();
            throw e;
        }
    }

    private static String md5(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            int length;
            while ((length = in.read(buffer)) != -1) {
                digest.update(buffer, 0, length);
            }
            StringBuilder builder = new StringBuilder();
            for (byte value : digest.digest()) {
                builder.append(String.format("%02x", value & 0xFF));
            }
            return builder.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public boolean delete(File file) {
        if (file == null) {
            return false;
        }
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent == null || !parent.equals(directory.getAbsoluteFile())) {
            return false;
        }
        return file.delete();
    }

    public List<File> list() {
        File[] files = directory.listFiles((dir, name) ->
                name.endsWith(".jpg") || name.endsWith(".jpeg")
                        || name.endsWith(".png") || name.endsWith(".webp"));
        if (files == null || files.length == 0) {
            return new ArrayList<>();
        }
        List<File> result = new ArrayList<>(Arrays.asList(files));
        result.sort(Comparator.comparingLong(File::lastModified).reversed());
        return result;
    }
}
