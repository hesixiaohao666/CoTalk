package com.example.cotalkproject;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.example.cotalkproject.data.AppDatabase;
import com.example.cotalkproject.data.Prefs;
import com.example.cotalkproject.data.SessionDao;
import com.example.cotalkproject.model.Session;
import com.example.cotalkproject.net.SessionManager;
import com.example.cotalkproject.service.CoTalkConnectionService;
import com.example.cotalkproject.ui.chat.ChatActivity;
import com.example.cotalkproject.ui.contacts.ContactsFragment;
import com.example.cotalkproject.ui.home.HomeFragment;
import com.example.cotalkproject.ui.login.LoginActivity;
import com.example.cotalkproject.ui.profile.ProfileFragment;
import com.example.cotalkproject.util.UiUtil;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    private static final String TAG_HOME = "home";
    private static final String TAG_CONTACTS = "contacts";
    private static final String TAG_PROFILE = "profile";
    private static final String KEY_AUTO_CONNECT_ATTEMPTED = "auto_connect_attempted";

    private Fragment homeFragment;
    private Fragment contactsFragment;
    private Fragment profileFragment;
    private TextView titleView;
    private SessionDao sessionDao;
    private Prefs prefs;
    private SessionManager sessionManager;
    private boolean autoConnectStarted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        CoTalkApp app = CoTalkApp.getInstance();
        prefs = app.getPrefs();
        sessionManager = app.getSessionManager();
        if (prefs.getCurrentUserId() <= 0 || !app.isAuthenticated()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        UiUtil.applyLightSystemBars(this);
        UiUtil.applyEdgeToEdgeInsets(
                findViewById(R.id.main),
                findViewById(R.id.title_bar)
        );

        titleView = findViewById(R.id.tv_title);
        BottomNavigationView navigation = findViewById(R.id.navigation_bar);
        sessionDao = new SessionDao(app.getDatabase());
        autoConnectStarted = savedInstanceState != null
                && savedInstanceState.getBoolean(KEY_AUTO_CONNECT_ATTEMPTED, false);

        restoreFragments(savedInstanceState);

        navigation.setOnItemSelectedListener(item -> {
            showTab(item.getItemId());
            return true;
        });

        int initialItem = R.id.navigation_home;
        if (savedInstanceState != null && navigation.getSelectedItemId() != View.NO_ID) {
            initialItem = navigation.getSelectedItemId();
        }
        showTab(initialItem);
        CoTalkConnectionService.sync(this, prefs);
        maybeAutoConnect();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(KEY_AUTO_CONNECT_ATTEMPTED, autoConnectStarted);
    }

    /**
     * 登录后自动连接最近一次成功连接的服务器；无密钥、失败或超时都不自动重试。
     */
    private void maybeAutoConnect() {
        if (autoConnectStarted) {
            return;
        }
        autoConnectStarted = true;
        if (!prefs.isAutoConnectAfterLogin()) {
            return;
        }
        if (sessionManager.isConnecting()
                || sessionManager.getActiveSessionId() > 0) {
            return;
        }
        AppDatabase database = CoTalkApp.getInstance().getDatabase();
        database.execute(() -> {
            Session session = sessionDao.findLatestConnected();
            if (session == null) {
                return;
            }
            String authKey = prefs.getServerAuthKey(
                    session.host, session.port);
            if (TextUtils.isEmpty(authKey)) {
                return;
            }
            database.runOnMain(() -> {
                if (isFinishing() || isDestroyed()
                        || sessionManager.isConnecting()
                        || sessionManager.getActiveSessionId() > 0) {
                    return;
                }
                sessionManager.connect(session.host, session.port, authKey,
                        new SessionManager.ConnectCallback() {
                            @Override
                            public void onSuccess(Session connected) {
                                if (isFinishing() || isDestroyed()) {
                                    return;
                                }
                                startActivity(ChatActivity.createIntent(
                                        MainActivity.this,
                                        connected.id,
                                        connected.displayName()));
                            }

                            @Override
                            public void onAuthKeyError(String message) {
                                prefs.clearServerAuthKey(
                                        session.host, session.port);
                                if (!isFinishing() && !isDestroyed()) {
                                    Toast.makeText(MainActivity.this,
                                            R.string.auto_connect_failed,
                                            Toast.LENGTH_LONG).show();
                                }
                            }

                            @Override
                            public void onError(String message) {
                                if (!isFinishing() && !isDestroyed()) {
                                    Toast.makeText(MainActivity.this,
                                            R.string.auto_connect_failed,
                                            Toast.LENGTH_LONG).show();
                                }
                            }
                        });
            });
        });
    }

    private void restoreFragments(Bundle savedInstanceState) {
        FragmentManager fm = getSupportFragmentManager();
        homeFragment = fm.findFragmentByTag(TAG_HOME);
        contactsFragment = fm.findFragmentByTag(TAG_CONTACTS);
        profileFragment = fm.findFragmentByTag(TAG_PROFILE);

        if (homeFragment == null) {
            homeFragment = new HomeFragment();
        }
        if (contactsFragment == null) {
            contactsFragment = new ContactsFragment();
        }
        if (profileFragment == null) {
            profileFragment = new ProfileFragment();
        }

        if (savedInstanceState == null) {
            fm.beginTransaction()
                    .add(R.id.fragment_container, homeFragment, TAG_HOME)
                    .add(R.id.fragment_container, contactsFragment, TAG_CONTACTS)
                    .hide(contactsFragment)
                    .add(R.id.fragment_container, profileFragment, TAG_PROFILE)
                    .hide(profileFragment)
                    .commitNow();
        }
    }

    private void showTab(int itemId) {
        Fragment target;
        String tag;
        int titleRes;

        if (itemId == R.id.navigation_contacts) {
            target = contactsFragment;
            tag = TAG_CONTACTS;
            titleRes = R.string.title_contacts;
        } else if (itemId == R.id.navigation_profile) {
            target = profileFragment;
            tag = TAG_PROFILE;
            titleRes = R.string.title_profile;
        } else {
            target = homeFragment;
            tag = TAG_HOME;
            titleRes = R.string.title_home;
        }

        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction tx = fm.beginTransaction();
        for (Fragment fragment : new Fragment[]{homeFragment, contactsFragment, profileFragment}) {
            if (fragment != null && fragment != target && fragment.isAdded() && !fragment.isHidden()) {
                tx.hide(fragment);
            }
        }
        if (!target.isAdded()) {
            tx.add(R.id.fragment_container, target, tag);
        } else if (target.isHidden()) {
            tx.show(target);
        }
        tx.commit();

        titleView.setText(titleRes);
    }
}
