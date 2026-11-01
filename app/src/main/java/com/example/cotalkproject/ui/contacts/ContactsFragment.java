package com.example.cotalkproject.ui.contacts;

import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cotalkproject.CoTalkApp;
import com.example.cotalkproject.R;
import com.example.cotalkproject.data.AppDatabase;
import com.example.cotalkproject.data.MemberDao;
import com.example.cotalkproject.data.Prefs;
import com.example.cotalkproject.data.SessionDao;
import com.example.cotalkproject.model.Member;
import com.example.cotalkproject.model.Session;
import com.example.cotalkproject.net.SessionManager;

import java.util.Collections;
import java.util.List;

public class ContactsFragment extends Fragment implements SessionManager.Listener {

    private EditText etHost;
    private EditText etPort;
    private EditText etAuthKey;
    private CheckBox cbRememberKey;
    private Button btnConnect;
    private RecyclerView rvServers;
    private RecyclerView rvMembers;
    private TextView tvServersEmpty;
    private TextView tvMembersEmpty;

    private ServerAdapter serverAdapter;
    private MemberAdapter memberAdapter;
    private SessionDao sessionDao;
    private MemberDao memberDao;
    private SessionManager sessionManager;
    private Prefs prefs;

    private long selectedSessionId = -1;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_contacts, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        etHost = view.findViewById(R.id.et_host);
        etPort = view.findViewById(R.id.et_port);
        etAuthKey = view.findViewById(R.id.et_auth_key);
        cbRememberKey = view.findViewById(R.id.cb_remember_key);
        btnConnect = view.findViewById(R.id.btn_connect_server);
        rvServers = view.findViewById(R.id.rv_servers);
        rvMembers = view.findViewById(R.id.rv_members);
        tvServersEmpty = view.findViewById(R.id.tv_servers_empty);
        tvMembersEmpty = view.findViewById(R.id.tv_members_empty);

        etHost.setText(R.string.default_server_host);

        sessionManager = CoTalkApp.getInstance().getSessionManager();
        prefs = CoTalkApp.getInstance().getPrefs();
        sessionDao = new SessionDao(CoTalkApp.getInstance().getDatabase());
        memberDao = new MemberDao(CoTalkApp.getInstance().getDatabase());

        rvServers.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvMembers.setLayoutManager(new LinearLayoutManager(requireContext()));
        serverAdapter = new ServerAdapter(sessionManager, new ServerAdapter.Listener() {
            @Override
            public void onSelect(Session session) {
                selectedSessionId = session.id;
                etHost.setText(session.host);
                etPort.setText(String.valueOf(session.port));
                String savedKey = prefs.getServerAuthKey(session.host, session.port);
                if (!savedKey.isEmpty()) {
                    etAuthKey.setText(savedKey);
                }
                loadMembers();
            }

            @Override
            public void onConnect(Session session) {
                String savedKey = prefs.getServerAuthKey(session.host, session.port);
                if (!savedKey.isEmpty()) {
                    confirmAndConnect(session.host, session.port, savedKey);
                } else {
                    promptForAuthKey(session.host, session.port);
                }
            }

            @Override
            public void onDisconnect(Session session) {
                if (sessionManager.isActive(session.id)) {
                    sessionManager.disconnect();
                }
                selectedSessionId = session.id;
            }

            @Override
            public void onRemove(Session session) {
                confirmRemove(session);
            }
        });
        memberAdapter = new MemberAdapter(
                CoTalkApp.getInstance().getAvatarLoader());
        rvServers.setAdapter(serverAdapter);
        rvMembers.setAdapter(memberAdapter);

        btnConnect.setOnClickListener(v -> {
            String host = etHost.getText().toString().trim();
            if (TextUtils.isEmpty(host)) {
                Toast.makeText(requireContext(), R.string.input_host_required,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            int port;
            try {
                port = Integer.parseInt(etPort.getText().toString().trim());
            } catch (NumberFormatException e) {
                Toast.makeText(requireContext(), R.string.input_port_invalid,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (port < 1 || port > 65535) {
                Toast.makeText(requireContext(), R.string.input_port_invalid,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            String authKey = etAuthKey.getText().toString().trim();
            if (authKey.isEmpty()) {
                Toast.makeText(requireContext(), R.string.input_auth_key_required,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            confirmAndConnect(host, port, authKey);
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        sessionManager.addListener(this);
        loadSessions();
    }

    @Override
    public void onStop() {
        sessionManager.removeListener(this);
        super.onStop();
    }

    private void confirmAndConnect(String host, int port, String authKey) {
        confirmAndConnect(host, port, authKey, true);
    }

    private void confirmAndConnect(String host, int port, String authKey,
                                   boolean allowAuthPrompt) {
        if (TextUtils.isEmpty(authKey)) {
            Toast.makeText(requireContext(), R.string.input_auth_key_required,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (sessionManager.isConnecting()) {
            Toast.makeText(requireContext(), "正在连接，请稍候",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (sessionManager.getActiveSessionId() > 0) {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.switch_server_title)
                    .setMessage(R.string.switch_server_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.confirm,
                            (dialog, which) -> doConnect(
                                    host, port, authKey, allowAuthPrompt))
                    .show();
            return;
        }
        doConnect(host, port, authKey, allowAuthPrompt);
    }

    private void doConnect(String host, int port, String authKey,
                           boolean allowAuthPrompt) {
        btnConnect.setEnabled(false);
        sessionManager.connect(host, port, authKey,
                new SessionManager.ConnectCallback() {
                    @Override
                    public void onSuccess(Session session) {
                        if (!isAdded()) {
                            return;
                        }
                        btnConnect.setEnabled(true);
                        selectedSessionId = session.id;
                        if (cbRememberKey.isChecked()) {
                            if (!prefs.saveServerAuthKey(host, port, authKey)) {
                                Toast.makeText(requireContext(),
                                        R.string.auth_key_save_failed,
                                        Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            prefs.clearServerAuthKey(host, port);
                        }
                        Toast.makeText(requireContext(), R.string.connect_success,
                                Toast.LENGTH_SHORT).show();
                        loadSessions();
                        loadMembers();
                    }

                    @Override
                    public void onAuthKeyError(String message) {
                        if (!isAdded()) {
                            return;
                        }
                        btnConnect.setEnabled(true);
                        boolean remembered = cbRememberKey.isChecked()
                                || !prefs.getServerAuthKey(host, port).isEmpty();
                        prefs.clearServerAuthKey(host, port);
                        etAuthKey.setText("");
                        if (allowAuthPrompt && remembered) {
                            cbRememberKey.setChecked(true);
                            Toast.makeText(requireContext(),
                                    R.string.auth_key_retry_hint,
                                    Toast.LENGTH_SHORT).show();
                            promptForAuthKey(host, port, true);
                        } else {
                            cbRememberKey.setChecked(false);
                            Toast.makeText(requireContext(),
                                    getString(R.string.connect_failed, message),
                                    Toast.LENGTH_LONG).show();
                        }
                        loadSessions();
                    }

                    @Override
                    public void onError(String message) {
                        if (!isAdded()) {
                            return;
                        }
                        btnConnect.setEnabled(true);
                        Toast.makeText(requireContext(),
                                getString(R.string.connect_failed, message),
                                Toast.LENGTH_LONG).show();
                        loadSessions();
                    }
                });
    }

    private void promptForAuthKey(String host, int port) {
        promptForAuthKey(host, port, false);
    }

    private void promptForAuthKey(String host, int port, boolean afterFailure) {
        EditText input = new EditText(requireContext());
        input.setHint(R.string.hint_auth_key);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout container = new LinearLayout(requireContext());
        container.setPadding(padding, padding / 2, padding, 0);
        container.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(requireContext())
                .setTitle(afterFailure
                        ? R.string.auth_key_retry_title
                        : R.string.server_key_title)
                .setMessage(afterFailure
                        ? getString(R.string.auth_key_retry_message,
                        host + ":" + port)
                        : getString(R.string.server_key_message,
                        host + ":" + port))
                .setView(container)
                .setNegativeButton(R.string.cancel, (dialog, which) -> {
                    cbRememberKey.setChecked(false);
                    prefs.clearServerAuthKey(host, port);
                    etAuthKey.setText("");
                    if (afterFailure) {
                        Toast.makeText(requireContext(),
                                R.string.auth_key_remember_cancelled,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    String key = input.getText().toString().trim();
                    if (key.isEmpty()) {
                        cbRememberKey.setChecked(false);
                        prefs.clearServerAuthKey(host, port);
                        Toast.makeText(requireContext(),
                                R.string.input_auth_key_required,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    etHost.setText(host);
                    etPort.setText(String.valueOf(port));
                    etAuthKey.setText(key);
                    confirmAndConnect(host, port, key, !afterFailure);
                })
                .show();
    }

    private void confirmRemove(Session session) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.remove_server_title)
                .setMessage(R.string.remove_server_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    if (selectedSessionId == session.id) {
                        selectedSessionId = -1;
                    }
                    sessionManager.removeSession(session.id);
                    loadSessions();
                })
                .show();
    }

    private void loadSessions() {
        AppDatabase database = CoTalkApp.getInstance().getDatabase();
        database.execute(() -> {
            List<Session> sessions = sessionDao.queryAll();
            database.runOnMain(() -> {
                if (!isAdded() || getView() == null) {
                    return;
                }
                serverAdapter.setSessions(sessions);
                boolean empty = sessions.isEmpty();
                tvServersEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                rvServers.setVisibility(empty ? View.GONE : View.VISIBLE);

                if (!containsSession(sessions, selectedSessionId)) {
                    long active = sessionManager.getActiveSessionId();
                    if (active > 0 && containsSession(sessions, active)) {
                        selectedSessionId = active;
                    } else if (!sessions.isEmpty()) {
                        selectedSessionId = sessions.get(0).id;
                    } else {
                        selectedSessionId = -1;
                    }
                }
                loadMembers();
            });
        });
    }

    private void loadMembers() {
        long sessionId = selectedSessionId;
        if (sessionId <= 0) {
            memberAdapter.setMembers(Collections.emptyList());
            memberAdapter.setSelfUserId("");
            tvMembersEmpty.setVisibility(View.VISIBLE);
            rvMembers.setVisibility(View.GONE);
            return;
        }

        AppDatabase database = CoTalkApp.getInstance().getDatabase();
        database.execute(() -> {
            List<Member> members = memberDao.queryBySession(sessionId);
            database.runOnMain(() -> {
                if (!isAdded() || getView() == null) {
                    return;
                }
                memberAdapter.setSessionId(sessionId);
                memberAdapter.setMembers(members);
                memberAdapter.setSelfUserId(sessionManager.isActive(sessionId)
                        ? sessionManager.getSelfUserId()
                        : "");
                boolean empty = members.isEmpty();
                tvMembersEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                rvMembers.setVisibility(empty ? View.GONE : View.VISIBLE);
            });
        });
    }

    private boolean containsSession(List<Session> sessions, long sessionId) {
        for (Session session : sessions) {
            if (session.id == sessionId) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onSessionsChanged() {
        loadSessions();
    }

    @Override
    public void onMembersChanged(long sessionId) {
        if (sessionId == selectedSessionId) {
            loadMembers();
        }
    }

    @Override
    public void onConnectionError(String message) {
        if (isAdded() && isVisible()) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
        }
        loadSessions();
    }
}
