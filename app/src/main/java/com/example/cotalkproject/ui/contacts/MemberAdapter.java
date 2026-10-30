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
import com.example.cotalkproject.util.AvatarUtil;

import java.util.ArrayList;
import java.util.List;

public class MemberAdapter extends RecyclerView.Adapter<MemberAdapter.ViewHolder> {

    private final List<Member> members = new ArrayList<>();
    private String selfUserId = "";

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
        AvatarUtil.bindInitial(holder.tvAvatar, member.nickname);
        holder.tvName.setText(member.nickname);

        boolean self = !TextUtils.isEmpty(selfUserId)
                && selfUserId.equals(member.userId);
        holder.tvMe.setVisibility(self ? View.VISIBLE : View.GONE);
        holder.vStatus.setBackgroundResource(member.online
                ? R.drawable.bg_status_online
                : R.drawable.bg_status_offline);
    }

    @Override
    public int getItemCount() {
        return members.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView tvAvatar;
        final TextView tvName;
        final TextView tvMe;
        final View vStatus;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvAvatar = itemView.findViewById(R.id.tv_member_avatar);
            tvName = itemView.findViewById(R.id.tv_member_name);
            tvMe = itemView.findViewById(R.id.tv_member_me);
            vStatus = itemView.findViewById(R.id.v_member_status);
        }
    }
}
