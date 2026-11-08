package com.example.cotalkproject.ui.home;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cotalkproject.R;
import com.example.cotalkproject.model.Session;
import com.example.cotalkproject.net.SessionManager;
import com.example.cotalkproject.ui.widget.AvatarView;
import com.example.cotalkproject.util.AvatarLoader;
import com.example.cotalkproject.util.TimeUtil;

import java.util.ArrayList;
import java.util.List;

public class SessionAdapter extends RecyclerView.Adapter<SessionAdapter.ViewHolder> {

    public interface OnSessionClickListener {
        void onSessionClick(Session session);
    }

    private final List<Session> sessions = new ArrayList<>();
    private final SessionManager sessionManager;
    private final AvatarLoader avatarLoader;
    private final OnSessionClickListener listener;

    public SessionAdapter(SessionManager sessionManager,
                          AvatarLoader avatarLoader,
                          OnSessionClickListener listener) {
        this.sessionManager = sessionManager;
        this.avatarLoader = avatarLoader;
        this.listener = listener;
    }

    public void setSessions(List<Session> newSessions) {
        sessions.clear();
        if (newSessions != null) {
            sessions.addAll(newSessions);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_session, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Session session = sessions.get(position);
        Context context = holder.itemView.getContext();

        bindRoomAvatar(holder.tvAvatar, session);
        holder.tvRoomName.setText(session.displayName());
        holder.tvAiBadge.setVisibility(session.aiEnabled
                ? View.VISIBLE : View.GONE);
        holder.tvEndpoint.setText(session.endpoint());
        holder.tvLastMessage.setText(TextUtils.isEmpty(session.lastMessage)
                ? context.getString(R.string.home_no_message)
                : session.lastMessage);
        holder.tvTime.setText(TimeUtil.format(session.lastTime));

        boolean active = sessionManager.isActive(session.id);
        holder.tvStatus.setText(active ? R.string.server_online : R.string.server_offline);
        holder.tvStatus.setTextColor(ContextCompat.getColor(
                context,
                active ? R.color.online : R.color.offline
        ));

        if (session.unread > 0) {
            holder.tvUnread.setVisibility(View.VISIBLE);
            holder.tvUnread.setText(session.unread > 99
                    ? "99+"
                    : String.valueOf(session.unread));
        } else {
            holder.tvUnread.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onSessionClick(session);
            }
        });
    }

    private void bindRoomAvatar(AvatarView avatar, Session session) {
        String name = session.displayName();
        avatar.setAvatar(name, null);
        if (session.roomAvatarVersion <= 0
                || TextUtils.isEmpty(session.roomAvatarId)) {
            avatar.setTag(null);
            return;
        }
        String token = session.id + "|" + session.roomAvatarId
                + "|" + session.roomAvatarVersion;
        avatar.setTag(token);
        if (avatarLoader == null) {
            return;
        }
        avatarLoader.load(
                session.id,
                session.roomAvatarId,
                session.roomAvatarVersion,
                name,
                bitmap -> {
                    if (token.equals(avatar.getTag())) {
                        avatar.setAvatar(name, bitmap);
                    }
                });
    }

    @Override
    public int getItemCount() {
        return sessions.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final AvatarView tvAvatar;
        final TextView tvRoomName;
        final TextView tvAiBadge;
        final TextView tvEndpoint;
        final TextView tvLastMessage;
        final TextView tvTime;
        final TextView tvStatus;
        final TextView tvUnread;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvAvatar = itemView.findViewById(R.id.tv_session_avatar);
            tvRoomName = itemView.findViewById(R.id.tv_room_name);
            tvAiBadge = itemView.findViewById(R.id.tv_ai_badge);
            tvEndpoint = itemView.findViewById(R.id.tv_endpoint);
            tvLastMessage = itemView.findViewById(R.id.tv_last_message);
            tvTime = itemView.findViewById(R.id.tv_time);
            tvStatus = itemView.findViewById(R.id.tv_status);
            tvUnread = itemView.findViewById(R.id.tv_unread);
        }
    }
}
