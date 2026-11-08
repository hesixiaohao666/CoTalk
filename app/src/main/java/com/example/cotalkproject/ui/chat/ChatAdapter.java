package com.example.cotalkproject.ui.chat;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cotalkproject.R;
import com.example.cotalkproject.model.Message;
import com.example.cotalkproject.ui.widget.AvatarView;
import com.example.cotalkproject.util.AvatarLoader;
import com.example.cotalkproject.util.TimeUtil;
import com.google.android.material.imageview.ShapeableImageView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.BaseHolder> {

    public interface OnRetryListener {
        void onRetry(Message message);
    }

    public interface OnAvatarLongClickListener {
        void onAvatarLongClick(String nickname);
    }

    public interface OnAvatarDoubleClickListener {
        void onAvatarDoubleClick(String userId, String nickname);
    }

    public interface OnMessageLongClickListener {
        void onMessageLongClick(Message message);
    }

    public interface OnImageClickListener {
        void onImageClick(Message message);
    }

    public interface OnVoiceClickListener {
        void onVoiceClick(Message message);
    }

    public interface MediaLoader {
        void loadMedia(Message message,
                       ShapeableImageView imageView,
                       TextView placeholder,
                       boolean emoji);
    }

    private static final int TYPE_OTHER = 0;
    private static final int TYPE_SELF = 1;
    private static final int TYPE_SYSTEM = 2;
    private static final long TIME_GAP_MS = 5 * 60 * 1000L;

    private final List<Message> messages = new ArrayList<>();
    private final long sessionId;
    private final AvatarLoader avatarLoader;
    private final OnRetryListener retryListener;
    private final OnAvatarLongClickListener avatarLongClickListener;
    private final OnAvatarDoubleClickListener avatarDoubleClickListener;
    private final OnMessageLongClickListener messageLongClickListener;
    private final OnImageClickListener imageClickListener;
    private final OnVoiceClickListener voiceClickListener;
    private final MediaLoader mediaLoader;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Long> lastAvatarTap = new HashMap<>();
    private String playingMessageKey = "";

    public ChatAdapter(long sessionId,
                       AvatarLoader avatarLoader,
                       OnRetryListener retryListener,
                       OnAvatarLongClickListener avatarLongClickListener,
                       OnAvatarDoubleClickListener avatarDoubleClickListener,
                       OnMessageLongClickListener messageLongClickListener,
                       OnImageClickListener imageClickListener,
                       OnVoiceClickListener voiceClickListener,
                       MediaLoader mediaLoader) {
        this.sessionId = sessionId;
        this.avatarLoader = avatarLoader;
        this.retryListener = retryListener;
        this.avatarLongClickListener = avatarLongClickListener;
        this.avatarDoubleClickListener = avatarDoubleClickListener;
        this.messageLongClickListener = messageLongClickListener;
        this.imageClickListener = imageClickListener;
        this.voiceClickListener = voiceClickListener;
        this.mediaLoader = mediaLoader;
    }

    public void setPlayingMessage(String key) {
        String next = key == null ? "" : key;
        if (next.equals(playingMessageKey)) {
            return;
        }
        playingMessageKey = next;
        notifyDataSetChanged();
    }

    public static String messageKey(Message message) {
        if (message == null) {
            return "";
        }
        if (!TextUtils.isEmpty(message.clientMsgId)) {
            return message.clientMsgId;
        }
        if (!TextUtils.isEmpty(message.msgId)) {
            return message.msgId;
        }
        return "msg-" + message.id;
    }

    public void setMessages(List<Message> newMessages) {
        messages.clear();
        if (newMessages != null) {
            messages.addAll(newMessages);
        }
        notifyDataSetChanged();
    }

    public void addMessage(Message message) {
        messages.add(message);
        notifyItemInserted(messages.size() - 1);
    }

    public List<Message> getMessages() {
        return new ArrayList<>(messages);
    }

    @Override
    public int getItemViewType(int position) {
        Message message = messages.get(position);
        if ("system".equals(message.msgType)) {
            return TYPE_SYSTEM;
        }
        return message.isSelf ? TYPE_SELF : TYPE_OTHER;
    }

    @NonNull
    @Override
    public BaseHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_SELF) {
            return new SelfHolder(inflater.inflate(
                    R.layout.item_message_self, parent, false));
        }
        if (viewType == TYPE_SYSTEM) {
            return new SystemHolder(inflater.inflate(
                    R.layout.item_message_system, parent, false));
        }
        return new OtherHolder(inflater.inflate(
                R.layout.item_message_other, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull BaseHolder holder, int position) {
        Message message = messages.get(position);
        bindTime(holder, message, position);

        if (holder instanceof SelfHolder) {
            SelfHolder self = (SelfHolder) holder;
            bindAvatar(self.tvAvatar, message);
            bindContent(self, message);

            if (message.status == Message.STATUS_SENDING) {
                self.tvStatus.setText(R.string.message_sending);
                self.tvStatus.setTextColor(ContextCompat.getColor(
                        self.itemView.getContext(), R.color.text_hint));
                self.tvStatus.setVisibility(View.VISIBLE);
                self.tvStatus.setOnClickListener(null);
                self.ivFailed.setVisibility(View.GONE);
                self.ivFailed.setOnClickListener(null);
            } else if (message.status == Message.STATUS_FAILED) {
                self.tvStatus.setVisibility(View.GONE);
                self.tvStatus.setOnClickListener(null);
                self.ivFailed.setVisibility(View.VISIBLE);
                self.ivFailed.setOnClickListener(v -> {
                    if (retryListener != null) {
                        retryListener.onRetry(message);
                    }
                });
            } else {
                self.tvStatus.setVisibility(View.GONE);
                self.tvStatus.setOnClickListener(null);
                self.ivFailed.setVisibility(View.GONE);
                self.ivFailed.setOnClickListener(null);
            }
        } else if (holder instanceof OtherHolder) {
            OtherHolder other = (OtherHolder) holder;
            String senderName = TextUtils.isEmpty(message.senderName)
                    ? other.itemView.getContext().getString(R.string.member_default)
                    : message.senderName;
            bindAvatar(other.tvAvatar, message);
            other.tvName.setText(senderName);
            other.tvNameAi.setVisibility("ai".equals(message.msgType)
                    ? View.VISIBLE : View.GONE);
            bindContent(other, message);
        } else if (holder instanceof SystemHolder) {
            SystemHolder system = (SystemHolder) holder;
            system.tvContent.setText(message.content);
            bindMessageLongClick(system.tvContent, message);
        }
    }

    private void bindContent(ContentHolder holder, Message message) {
        String type = message.msgType == null ? "text" : message.msgType;
        holder.ivMedia.setTag(null);
        holder.ivMedia.setImageDrawable(null);
        holder.ivMedia.setOnClickListener(null);
        holder.ivMedia.setOnLongClickListener(null);
        holder.tvContent.setOnClickListener(null);
        holder.tvContent.setOnLongClickListener(null);
        holder.tvContent.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);

        if ("image".equals(type) || "emoji".equals(type)) {
            boolean emoji = "emoji".equals(type);
            holder.ivMedia.setVisibility(View.GONE);
            if (message.fileExpired) {
                showPlaceholder(holder.tvContent, R.string.file_expired);
                bindMessageLongClick(holder.tvContent, message);
                return;
            }
            holder.ivMedia.setVisibility(View.VISIBLE);
            holder.ivMedia.setAdjustViewBounds(true);
            holder.ivMedia.setMaxWidth(dp(holder.itemView, emoji ? 120 : 220));
            holder.ivMedia.setMaxHeight(dp(holder.itemView, emoji ? 120 : 260));
            holder.tvContent.setVisibility(View.GONE);
            bindMessageLongClick(holder.ivMedia, message);
            if (!emoji && imageClickListener != null) {
                holder.ivMedia.setOnClickListener(
                        v -> imageClickListener.onImageClick(message));
            }
            if (mediaLoader == null) {
                holder.ivMedia.setVisibility(View.GONE);
                showPlaceholder(holder.tvContent, R.string.chat_image_unavailable);
                bindMessageLongClick(holder.tvContent, message);
            } else {
                mediaLoader.loadMedia(
                        message, holder.ivMedia, holder.tvContent, emoji);
                bindMessageLongClick(holder.tvContent, message);
            }
            return;
        }

        holder.ivMedia.setVisibility(View.GONE);
        holder.tvContent.setVisibility(View.VISIBLE);
        if ("voice".equals(type)) {
            if (message.fileExpired) {
                holder.tvContent.setText(R.string.file_expired);
                bindMessageLongClick(holder.tvContent, message);
                return;
            }
            int seconds = voiceSeconds(message.content);
            holder.tvContent.setText(seconds > 0
                    ? holder.itemView.getContext().getString(
                    R.string.chat_voice_duration_format, seconds)
                    : holder.itemView.getContext().getString(
                    R.string.voice_message_short));
            boolean playing = messageKey(message).equals(playingMessageKey);
            holder.tvContent.setCompoundDrawablesWithIntrinsicBounds(
                    playing ? R.drawable.ic_pause_voice : R.drawable.ic_play_voice,
                    0, 0, 0);
            holder.tvContent.setCompoundDrawablePadding(dp(holder.itemView, 6));
            bindMessageLongClick(holder.tvContent, message);
            if (voiceClickListener != null) {
                holder.tvContent.setOnClickListener(
                        v -> voiceClickListener.onVoiceClick(message));
            }
            return;
        }

        holder.tvContent.setText(message.content);
        bindMessageLongClick(holder.tvContent, message);
    }

    private void bindMessageLongClick(View view, Message message) {
        view.setOnLongClickListener(v -> {
            if (messageLongClickListener != null) {
                messageLongClickListener.onMessageLongClick(message);
                return true;
            }
            return false;
        });
    }

    private void bindAvatarGestures(AvatarView avatar, Message message) {
        String nickname = TextUtils.isEmpty(message.senderName)
                ? avatar.getContext().getString(R.string.member_default)
                : message.senderName;
        String userId = message.senderId;
        avatar.setOnClickListener(v -> {
            if (TextUtils.isEmpty(userId) || avatarDoubleClickListener == null) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            Long last = lastAvatarTap.get(userId);
            if (last != null && now - last <= 320L) {
                lastAvatarTap.remove(userId);
                avatarDoubleClickListener.onAvatarDoubleClick(userId, nickname);
            } else {
                lastAvatarTap.put(userId, now);
                mainHandler.postDelayed(() -> lastAvatarTap.remove(userId), 350L);
            }
        });
        avatar.setOnLongClickListener(v -> {
            if (avatarLongClickListener != null) {
                avatarLongClickListener.onAvatarLongClick(nickname);
                return true;
            }
            return false;
        });
    }

    private static int voiceSeconds(String content) {
        if (TextUtils.isEmpty(content)) {
            return 0;
        }
        try {
            long ms = Long.parseLong(content.trim());
            if (ms <= 0) {
                return 0;
            }
            return (int) Math.max(1L, (ms + 999L) / 1000L);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void showPlaceholder(TextView view, int textRes) {
        view.setText(textRes);
        view.setVisibility(View.VISIBLE);
        view.setOnLongClickListener(null);
    }

    private static int dp(View view, int value) {
        return Math.round(view.getResources().getDisplayMetrics().density * value);
    }

    private void bindAvatar(AvatarView avatar, Message message) {
        bindAvatarGestures(avatar, message);
        avatar.setAvatar(message.senderName, null);
        String token = message.msgId != null ? message.msgId : message.clientMsgId;
        avatar.setTag(token);
        if (avatarLoader == null || message.senderId == null) {
            return;
        }
        avatarLoader.load(
                sessionId,
                message.senderId,
                message.senderAvatarVersion,
                message.senderName,
                bitmap -> {
                    if (token != null && token.equals(avatar.getTag())) {
                        avatar.setAvatar(message.senderName, bitmap);
                    }
                }
        );
    }

    private void bindTime(BaseHolder holder, Message message, int position) {
        boolean show = position == 0;
        if (!show) {
            Message previous = messages.get(position - 1);
            show = message.timestamp - previous.timestamp > TIME_GAP_MS;
        }
        if (show) {
            holder.tvTime.setVisibility(View.VISIBLE);
            holder.tvTime.setText(TimeUtil.format(message.timestamp));
        } else {
            holder.tvTime.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class BaseHolder extends RecyclerView.ViewHolder {
        final TextView tvTime;

        BaseHolder(@NonNull View itemView) {
            super(itemView);
            tvTime = itemView.findViewById(R.id.tv_item_time);
        }
    }

    static class ContentHolder extends BaseHolder {
        final TextView tvContent;
        final ShapeableImageView ivMedia;

        ContentHolder(@NonNull View itemView) {
            super(itemView);
            tvContent = itemView.findViewById(R.id.tv_content);
            ivMedia = itemView.findViewById(R.id.iv_media);
        }
    }

    static class SelfHolder extends ContentHolder {
        final AvatarView tvAvatar;
        final TextView tvStatus;
        final ImageView ivFailed;

        SelfHolder(@NonNull View itemView) {
            super(itemView);
            tvAvatar = itemView.findViewById(R.id.tv_avatar);
            tvStatus = itemView.findViewById(R.id.tv_status);
            ivFailed = itemView.findViewById(R.id.iv_failed);
        }
    }

    static class OtherHolder extends ContentHolder {
        final AvatarView tvAvatar;
        final TextView tvName;
        final TextView tvNameAi;

        OtherHolder(@NonNull View itemView) {
            super(itemView);
            tvAvatar = itemView.findViewById(R.id.tv_avatar);
            tvName = itemView.findViewById(R.id.tv_name);
            tvNameAi = itemView.findViewById(R.id.tv_name_ai);
        }
    }

    static class SystemHolder extends BaseHolder {
        final TextView tvContent;

        SystemHolder(@NonNull View itemView) {
            super(itemView);
            tvContent = itemView.findViewById(R.id.tv_system_content);
        }
    }
}
