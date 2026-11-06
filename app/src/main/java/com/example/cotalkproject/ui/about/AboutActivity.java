package com.example.cotalkproject.ui.about;

import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.View;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.cotalkproject.BuildConfig;
import com.example.cotalkproject.R;
import com.example.cotalkproject.util.UiUtil;

public class AboutActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_about);

        UiUtil.applyLightSystemBars(this);
        UiUtil.applyEdgeToEdgeInsets(
                findViewById(R.id.about_root),
                findViewById(R.id.title_bar)
        );

        TextView btnBack = findViewById(R.id.btn_back);
        btnBack.setVisibility(View.VISIBLE);
        btnBack.setOnClickListener(v -> finish());
        TextView title = findViewById(R.id.tv_title);
        title.setText(R.string.about_title);

        TextView version = findViewById(R.id.tv_version);
        version.setText(getString(
                R.string.about_version_format, BuildConfig.VERSION_NAME));

        TextView changelogVersion = findViewById(R.id.tv_changelog_version);
        changelogVersion.setText(getString(
                R.string.about_changelog_version_format,
                BuildConfig.VERSION_NAME));
        TextView changelogDate = findViewById(R.id.tv_changelog_date);
        changelogDate.setText(getString(
                R.string.about_changelog_date_format,
                getString(R.string.about_changelog_date)));

        TextView author = findViewById(R.id.tv_author);
        Linkify.addLinks(author, Linkify.WEB_URLS);
        author.setMovementMethod(LinkMovementMethod.getInstance());
        author.setLinkTextColor(ContextCompat.getColor(this, R.color.blue_light));
    }
}
