package com.example.cotalkproject.ui.chat;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

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
import java.util.List;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.BaseHolder> {

    public interface OnRetryListener {
        void onRetry(Message message);
    }

    public interface OnAvatarLongClickListener {
        void onAvatarLongClick(String nickname);
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
    private final MediaLoader mediaLoader;

    public ChatAdapter(long sessionId,
                       AvatarLoader avatarLoader,
                       OnRetryListener retryListener,
                       OnAvatarLongClickListener avatarLongClickListener,
                       MediaLoader mediaLoader) {
        this.sessionId = sessionId;
        this.avatarLoader = avatarLoader;
        this.retryListener = retryListener;
        this.avatarLongClickListener = avatarLongClickListener;
        this.mediaLoader = mediaLoader;
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
            } else if (message.status == Message.STATUS_FAILED) {
                self.tvStatus.setText(R.string.message_failed);
                self.tvStatus.setTextColor(ContextCompat.getColor(
                        self.itemView.getContext(), R.color.danger));
                self.tvStatus.setVisibility(View.VISIBLE);
                self.tvStatus.setOnClickListener(v -> {
                    if (retryListener != null) {
                        retryListener.onRetry(message);
                    }
                });
            } else {
                self.tvStatus.setVisibility(View.GONE);
                self.tvStatus.setOnClickListener(null);
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
            other.tvAvatar.setOnLongClickListener(v -> {
                if (avatarLongClickListener != null) {
                    avatarLongClickListener.onAvatarLongClick(senderName);
                    return true;
                }
                return false;
            });
        } else if (holder instanceof SystemHolder) {
            SystemHolder system = (SystemHolder) holder;
            system.tvContent.setText(message.content);
            bindCopy(system.tvContent, message.content);
        }
    }

    private void bindContent(ContentHolder holder, Message message) {
        String type = message.msgType == null ? "text" : message.msgType;
        holder.ivMedia.setTag(null);
        holder.ivMedia.setImageDrawable(null);
        holder.tvContent.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);

        if ("image".equals(type) || "emoji".equals(type)) {
            boolean emoji = "emoji".equals(type);
            holder.tvContent.setOnLongClickListener(null);
            holder.ivMedia.setVisibility(View.GONE);
            if (message.fileExpired) {
                showPlaceholder(holder.tvContent, R.string.file_expired);
                return;
            }
            holder.ivMedia.setVisibility(View.VISIBLE);
            holder.ivMedia.setAdjustViewBounds(true);
            holder.ivMedia.setMaxWidth(dp(holder.itemView, emoji ? 120 : 220));
            holder.ivMedia.setMaxHeight(dp(holder.itemView, emoji ? 120 : 260));
            holder.tvContent.setVisibility(View.GONE);
            if (mediaLoader == null) {
                holder.ivMedia.setVisibility(View.GONE);
                showPlaceholder(holder.tvContent, R.string.chat_image_unavailable);
            } else {
                mediaLoader.loadMedia(
                        message, holder.ivMedia, holder.tvContent, emoji);
            }
            return;
        }

        holder.ivMedia.setVisibility(View.GONE);
        holder.tvContent.setVisibility(View.VISIBLE);
        if ("voice".equals(type)) {
            holder.tvContent.setText(R.string.voice_message_placeholder);
            holder.tvContent.setCompoundDrawablesWithIntrinsicBounds(
                    R.drawable.ic_play_voice, 0, 0, 0);
            holder.tvContent.setCompoundDrawablePadding(dp(holder.itemView, 6));
            holder.tvContent.setOnLongClickListener(null);
            return;
        }

        holder.tvContent.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
        holder.tvContent.setText(message.content);
        bindCopy(holder.tvContent, message.content);
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

    private void bindCopy(TextView view, String content) {
        view.setOnLongClickListener(v -> {
            Context context = v.getContext();
            ClipboardManager manager = (ClipboardManager) context.getSystemService(
                    Context.CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(ClipData.newPlainText("CoTalk", content));
                Toast.makeText(context, R.string.message_copied,
                        Toast.LENGTH_SHORT).show();
            }
            return true;
        });
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

        SelfHolder(@NonNull View itemView) {
            super(itemView);
            tvAvatar = itemView.findViewById(R.id.tv_avatar);
            tvStatus = itemView.findViewById(R.id.tv_status);
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
