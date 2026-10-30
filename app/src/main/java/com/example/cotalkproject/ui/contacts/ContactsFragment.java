package com.example.cotalkproject.ui.contacts;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
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
import com.example.cotalkproject.data.SessionDao;
import com.example.cotalkproject.model.Member;
import com.example.cotalkproject.model.Session;
import com.example.cotalkproject.net.SessionManager;

import java.util.Collections;
import java.util.List;

public class ContactsFragment extends Fragment implements SessionManager.Listener {

    private EditText etHost;
    private EditText etPort;
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
        btnConnect = view.findViewById(R.id.btn_connect_server);
        rvServers = view.findViewById(R.id.rv_servers);
        rvMembers = view.findViewById(R.id.rv_members);
        tvServersEmpty = view.findViewById(R.id.tv_servers_empty);
        tvMembersEmpty = view.findViewById(R.id.tv_members_empty);

        etHost.setText(R.string.default_server_host);

        sessionManager = CoTalkApp.getInstance().getSessionManager();
        sessionDao = new SessionDao(CoTalkApp.getInstance().getDatabase());
        memberDao = new MemberDao(CoTalkApp.getInstance().getDatabase());

        rvServers.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvMembers.setLayoutManager(new LinearLayoutManager(requireContext()));
        serverAdapter = new ServerAdapter(sessionManager, new ServerAdapter.Listener() {
            @Override
            public void onSelect(Session session) {
                selectedSessionId = session.id;
                loadMembers();
            }

            @Override
            public void onConnect(Session session) {
                confirmAndConnect(session.host, session.port);
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
        memberAdapter = new MemberAdapter();
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
            confirmAndConnect(host, port);
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

    private void confirmAndConnect(String host, int port) {
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
                            (dialog, which) -> doConnect(host, port))
                    .show();
            return;
        }
        doConnect(host, port);
    }

    private void doConnect(String host, int port) {
        btnConnect.setEnabled(false);
        sessionManager.connect(host, port, new SessionManager.ConnectCallback() {
            @Override
            public void onSuccess(Session session) {
                if (!isAdded()) {
                    return;
                }
                btnConnect.setEnabled(true);
                selectedSessionId = session.id;
                Toast.makeText(requireContext(), R.string.connect_success,
                        Toast.LENGTH_SHORT).show();
                loadSessions();
                loadMembers();
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
