package com.example.cotalkproject.ui.chat;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cotalkproject.R;
import com.example.cotalkproject.util.ImageUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 表情包面板：第 0 格固定为“新增”，其余为本地表情包。
 */
public class EmojiAdapter extends RecyclerView.Adapter<EmojiAdapter.Holder> {

    public interface Listener {
        void onAdd();

        void onEmoji(File file);

        void onEmojiLongPress(File file);
    }

    private final Listener listener;
    private final List<File> files = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "cotalk-emoji-loader");
                thread.setDaemon(true);
                return thread;
            });

    public EmojiAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setFiles(List<File> newFiles) {
        files.clear();
        if (newFiles != null) {
            files.addAll(newFiles);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return files.size() + 1;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_emoji, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        ImageView image = holder.imageView;
        if (position == 0) {
            image.setTag(null);
            image.setBackgroundResource(R.drawable.bg_input);
            image.setPadding(dp(image, 12), dp(image, 12),
                    dp(image, 12), dp(image, 12));
            image.setImageResource(R.drawable.ic_add_image);
            image.setOnClickListener(v -> listener.onAdd());
            image.setOnLongClickListener(null);
            return;
        }

        File file = files.get(position - 1);
        image.setBackground(null);
        image.setPadding(dp(image, 2), dp(image, 2), dp(image, 2), dp(image, 2));
        image.setImageDrawable(null);
        image.setTag(file.getAbsolutePath());
        image.setOnClickListener(v -> listener.onEmoji(file));
        image.setOnLongClickListener(v -> {
            listener.onEmojiLongPress(file);
            return true;
        });

        ioExecutor.execute(() -> {
            Bitmap bitmap = ImageUtil.decodeFileSampled(file, 256);
            mainHandler.post(() -> {
                if (file.getAbsolutePath().equals(image.getTag())) {
                    image.setImageBitmap(bitmap);
                }
            });
        });
    }

    private static int dp(View view, int value) {
        return Math.round(view.getResources().getDisplayMetrics().density * value);
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ImageView imageView;

        Holder(@NonNull View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.iv_emoji);
        }
    }
}
