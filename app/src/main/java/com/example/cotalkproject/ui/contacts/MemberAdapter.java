package com.example.cotalkproject.ui.contacts;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cotalkproject.R;
import com.example.cotalkproject.model.Member;
import com.example.cotalkproject.ui.widget.AvatarView;
import com.example.cotalkproject.util.AvatarLoader;

import java.util.ArrayList;
import java.util.List;

public class MemberAdapter extends RecyclerView.Adapter<MemberAdapter.ViewHolder> {

    private final List<Member> members = new ArrayList<>();
    private final AvatarLoader avatarLoader;
    private long sessionId;
    private String selfUserId = "";

    public MemberAdapter(AvatarLoader avatarLoader) {
        this.avatarLoader = avatarLoader;
    }

    public void setSessionId(long sessionId) {
        this.sessionId = sessionId;
    }

    public void setMembers(List<Member> newMembers) {
        members.clear();
        if (newMembers != null) {
            members.addAll(newMembers);
        }
        notifyDataSetChanged();
    }

    public void setSelfUserId(String selfUserId) {
        this.selfUserId = selfUserId == null ? "" : selfUserId;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_member, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Member member = members.get(position);
        bindAvatar(holder.tvAvatar, member);
        holder.tvName.setText(member.nickname);
        holder.tvAi.setVisibility(member.ai ? View.VISIBLE : View.GONE);
        holder.tvAdmin.setVisibility(member.admin ? View.VISIBLE : View.GONE);

        boolean self = !TextUtils.isEmpty(selfUserId)
                && selfUserId.equals(member.userId);
        holder.tvMe.setVisibility(self ? View.VISIBLE : View.GONE);
        holder.vStatus.setBackgroundResource(member.online
                ? R.drawable.bg_status_online
                : R.drawable.bg_status_offline);
    }

    private void bindAvatar(AvatarView avatar, Member member) {
        avatar.setAvatar(member.nickname, null);
        String token = member.userId + "|" + member.avatarVersion;
        avatar.setTag(token);
        if (avatarLoader == null || member.userId == null) {
            return;
        }
        avatarLoader.load(
                sessionId,
                member.userId,
                member.avatarVersion,
                member.nickname,
                bitmap -> {
                    if (token.equals(avatar.getTag())) {
                        avatar.setAvatar(member.nickname, bitmap);
                    }
                }
        );
    }

    @Override
    public int getItemCount() {
        return members.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final AvatarView tvAvatar;
        final TextView tvName;
        final TextView tvAi;
        final TextView tvAdmin;
        final TextView tvMe;
        final View vStatus;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvAvatar = itemView.findViewById(R.id.tv_member_avatar);
            tvName = itemView.findViewById(R.id.tv_member_name);
            tvAi = itemView.findViewById(R.id.tv_member_ai);
            tvAdmin = itemView.findViewById(R.id.tv_member_admin);
            tvMe = itemView.findViewById(R.id.tv_member_me);
            vStatus = itemView.findViewById(R.id.v_member_status);
        }
    }
}
