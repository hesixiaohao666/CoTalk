package com.example.cotalkproject;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.example.cotalkproject.ui.contacts.ContactsFragment;
import com.example.cotalkproject.ui.home.HomeFragment;
import com.example.cotalkproject.ui.profile.ProfileFragment;
import com.example.cotalkproject.util.UiUtil;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    private static final String TAG_HOME = "home";
    private static final String TAG_CONTACTS = "contacts";
    private static final String TAG_PROFILE = "profile";

    private Fragment homeFragment;
    private Fragment contactsFragment;
    private Fragment profileFragment;
    private TextView titleView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        UiUtil.applyLightSystemBars(this);
        UiUtil.applyEdgeToEdgeInsets(
                findViewById(R.id.main),
                findViewById(R.id.title_bar)
        );

        titleView = findViewById(R.id.tv_title);
        BottomNavigationView navigation = findViewById(R.id.navigation_bar);

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
