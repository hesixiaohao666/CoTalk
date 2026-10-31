package com.example.cotalkproject.ui.profile;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.cotalkproject.CoTalkApp;
import com.example.cotalkproject.R;
import com.example.cotalkproject.data.AppDatabase;
import com.example.cotalkproject.data.LocalUserDao;
import com.example.cotalkproject.model.LocalUser;
import com.example.cotalkproject.ui.about.AboutActivity;
import com.example.cotalkproject.ui.settings.SettingsActivity;
import com.example.cotalkproject.ui.widget.AvatarView;
import com.example.cotalkproject.util.ImageUtil;

public class ProfileFragment extends Fragment {

    private AvatarView tvAvatar;
    private TextView tvNickname;
    private TextView tvLocalAccount;
    private TextView tvSignature;
    private LocalUserDao userDao;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_profile, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvAvatar = view.findViewById(R.id.tv_avatar);
        tvNickname = view.findViewById(R.id.tv_nickname);
        tvLocalAccount = view.findViewById(R.id.tv_local_account);
        tvSignature = view.findViewById(R.id.tv_signature);
        userDao = new LocalUserDao(CoTalkApp.getInstance().getDatabase());

        view.findViewById(R.id.btn_edit_profile).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), EditProfileActivity.class)));

        view.findViewById(R.id.tv_settings_entry).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), SettingsActivity.class)));

        view.findViewById(R.id.tv_about_entry).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), AboutActivity.class)));
    }

    @Override
    public void onResume() {
        super.onResume();
        loadProfile();
    }

    private void loadProfile() {
        AppDatabase database = CoTalkApp.getInstance().getDatabase();
        database.execute(() -> {
            LocalUser user = userDao.get();
            database.runOnMain(() -> {
                if (!isAdded() || getView() == null) {
                    return;
                }
                if (user == null) {
                    tvAvatar.setAvatar("?", null);
                    tvNickname.setText("—");
                    tvLocalAccount.setText("");
                    tvSignature.setText(R.string.signature_empty);
                    return;
                }
                Bitmap avatar = ImageUtil.loadBitmap(user.avatarPath);
                tvAvatar.setAvatar(user.nickname, avatar);
                tvNickname.setText(user.nickname);
                tvLocalAccount.setText(getString(
                        R.string.profile_local_account, user.username));
                tvSignature.setText(TextUtils.isEmpty(user.signature)
                        ? getString(R.string.signature_empty)
                        : user.signature);
            });
        });
    }
}
