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

import java.util.ArrayList;
import java.util.List;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.BaseHolder> {

    public interface OnRetryListener {
        void onRetry(Message message);
    }

    private static final int TYPE_OTHER = 0;
    private static final int TYPE_SELF = 1;
    private static final int TYPE_SYSTEM = 2;
    private static final long TIME_GAP_MS = 5 * 60 * 1000L;

    private final List<Message> messages = new ArrayList<>();
    private final long sessionId;
    private final AvatarLoader avatarLoader;
    private final OnRetryListener retryListener;

    public ChatAdapter(long sessionId,
                       AvatarLoader avatarLoader,
                       OnRetryListener retryListener) {
        this.sessionId = sessionId;
        this.avatarLoader = avatarLoader;
        this.retryListener = retryListener;
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
            self.tvContent.setText(message.content);

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
            bindCopy(self.tvContent, message.content);
        } else if (holder instanceof OtherHolder) {
            OtherHolder other = (OtherHolder) holder;
            bindAvatar(other.tvAvatar, message);
            other.tvName.setText(TextUtils.isEmpty(message.senderName)
                    ? other.itemView.getContext().getString(R.string.member_default)
                    : message.senderName);
            other.tvContent.setText(message.content);
            bindCopy(other.tvContent, message.content);
        } else if (holder instanceof SystemHolder) {
            SystemHolder system = (SystemHolder) holder;
            system.tvContent.setText(message.content);
            bindCopy(system.tvContent, message.content);
        }
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

    static class SelfHolder extends BaseHolder {
        final TextView tvContent;
        final AvatarView tvAvatar;
        final TextView tvStatus;

        SelfHolder(@NonNull View itemView) {
            super(itemView);
            tvContent = itemView.findViewById(R.id.tv_content);
            tvAvatar = itemView.findViewById(R.id.tv_avatar);
            tvStatus = itemView.findViewById(R.id.tv_status);
        }
    }

    static class OtherHolder extends BaseHolder {
        final TextView tvContent;
        final AvatarView tvAvatar;
        final TextView tvName;

        OtherHolder(@NonNull View itemView) {
            super(itemView);
            tvContent = itemView.findViewById(R.id.tv_content);
            tvAvatar = itemView.findViewById(R.id.tv_avatar);
            tvName = itemView.findViewById(R.id.tv_name);
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
