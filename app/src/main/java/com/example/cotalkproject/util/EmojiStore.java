package com.example.cotalkproject.util;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 本地表情包目录：filesDir/emojis，本期只新增和发送，不做删除。
 */
public class EmojiStore {

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
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("无法创建表情包目录");
        }
        String name = "emoji_" + System.currentTimeMillis() + "_"
                + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 6).toLowerCase(Locale.ROOT) + ".jpg";
        File output = new File(directory, name);
        try {
            return ImageUtil.compressEmojiToFile(context, uri, output);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            output.delete();
            throw e;
        }
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
