package com.example.cotalkproject.ui.settings;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.cotalkproject.CoTalkApp;
import com.example.cotalkproject.R;
import com.example.cotalkproject.data.AppDatabase;
import com.example.cotalkproject.data.Prefs;
import com.example.cotalkproject.ui.login.RegisterActivity;
import com.example.cotalkproject.util.ImageUtil;
import com.example.cotalkproject.util.UiUtil;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_settings);

        UiUtil.applyLightSystemBars(this);
        UiUtil.applyEdgeToEdgeInsets(
                findViewById(R.id.settings_root),
                findViewById(R.id.title_bar)
        );

        TextView btnBack = findViewById(R.id.btn_back);
        btnBack.setVisibility(View.VISIBLE);
        btnBack.setOnClickListener(v -> finish());
        TextView title = findViewById(R.id.tv_title);
        title.setText(R.string.settings_title);

        findViewById(R.id.btn_reset_account).setOnClickListener(v -> confirmReset());
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.reset_confirm_title)
                .setMessage(R.string.reset_confirm_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, (dialog, which) -> resetAccount())
                .show();
    }

    private void resetAccount() {
        AppDatabase database = CoTalkApp.getInstance().getDatabase();
        Prefs prefs = CoTalkApp.getInstance().getPrefs();
        database.execute(() -> {
            database.clearAllLocalData();
            ImageUtil.deleteLocalAvatar(this, 1);
            prefs.logout();
            prefs.clearRemembered();
            database.runOnMain(() -> {
                Toast.makeText(this, R.string.reset_success, Toast.LENGTH_SHORT).show();
                Intent intent = new Intent(this, RegisterActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finishAffinity();
            });
        });
    }
}
