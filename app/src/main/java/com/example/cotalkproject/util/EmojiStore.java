package com.example.cotalkproject.util;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
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
