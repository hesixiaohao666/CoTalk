package com.example.cotalkproject.ui.contacts;

import android.content.Context;
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

import java.util.ArrayList;
import java.util.List;

public class ServerAdapter extends RecyclerView.Adapter<ServerAdapter.ViewHolder> {

    public interface Listener {
        void onSelect(Session session);

        void onConnect(Session session);

        void onDisconnect(Session session);

        void onRemove(Session session);
    }

    private final List<Session> sessions = new ArrayList<>();
    private final SessionManager sessionManager;
    private final Listener listener;

    public ServerAdapter(SessionManager sessionManager, Listener listener) {
        this.sessionManager = sessionManager;
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
                .inflate(R.layout.item_server, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Session session = sessions.get(position);
        Context context = holder.itemView.getContext();
        boolean active = sessionManager.isActive(session.id);

        holder.tvRoom.setText(session.displayName());
        holder.tvAi.setVisibility(session.aiEnabled ? View.VISIBLE : View.GONE);
        holder.tvAi.setText(session.aiProEnabled
                ? R.string.server_supports_ai_pro
                : R.string.server_supports_ai);
        holder.tvExperimental.setVisibility(
                session.aiProEnabled || session.adminEnabled
                        ? View.VISIBLE : View.GONE);
        holder.tvEndpoint.setText(session.endpoint());
        holder.tvStatus.setText(active ? R.string.server_online : R.string.server_offline);
        holder.tvStatus.setTextColor(ContextCompat.getColor(
                context,
                active ? R.color.online : R.color.offline
        ));
        holder.btnConnect.setText(active
                ? R.string.action_disconnect
                : R.string.action_connect);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onSelect(session);
            }
        });
        holder.btnConnect.setOnClickListener(v -> {
            if (listener != null) {
                if (active) {
                    listener.onDisconnect(session);
                } else {
                    listener.onConnect(session);
                }
            }
        });
        holder.btnRemove.setOnClickListener(v -> {
            if (listener != null) {
                listener.onRemove(session);
            }
        });
    }

    @Override
    public int getItemCount() {
        return sessions.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView tvRoom;
        final TextView tvAi;
        final TextView tvExperimental;
        final TextView tvEndpoint;
        final TextView tvStatus;
        final TextView btnConnect;
        final TextView btnRemove;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvRoom = itemView.findViewById(R.id.tv_server_room);
            tvAi = itemView.findViewById(R.id.tv_server_ai);
            tvExperimental = itemView.findViewById(
                    R.id.tv_server_experimental);
            tvEndpoint = itemView.findViewById(R.id.tv_server_endpoint);
            tvStatus = itemView.findViewById(R.id.tv_server_status);
            btnConnect = itemView.findViewById(R.id.btn_server_connect);
            btnRemove = itemView.findViewById(R.id.btn_server_remove);
        }
    }
}
