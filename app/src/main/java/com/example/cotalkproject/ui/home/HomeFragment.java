package com.example.cotalkproject.ui.home;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cotalkproject.CoTalkApp;
import com.example.cotalkproject.R;
import com.example.cotalkproject.data.AppDatabase;
import com.example.cotalkproject.data.SessionDao;
import com.example.cotalkproject.model.Session;
import com.example.cotalkproject.net.SessionManager;
import com.example.cotalkproject.ui.chat.ChatActivity;

import java.util.List;

public class HomeFragment extends Fragment implements SessionManager.Listener {

    private RecyclerView recyclerView;
    private LinearLayout emptyView;
    private SessionAdapter adapter;
    private SessionDao sessionDao;
    private SessionManager sessionManager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        recyclerView = view.findViewById(R.id.rv_sessions);
        emptyView = view.findViewById(R.id.layout_empty);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        sessionManager = CoTalkApp.getInstance().getSessionManager();
        sessionDao = new SessionDao(CoTalkApp.getInstance().getDatabase());
        adapter = new SessionAdapter(sessionManager, this::openSession);
        recyclerView.setAdapter(adapter);

        loadSessions();
    }

    @Override
    public void onStart() {
        super.onStart();
        sessionManager.addListener(this);
        loadSessions();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (sessionManager != null) {
            loadSessions();
        }
    }

    @Override
    public void onStop() {
        sessionManager.removeListener(this);
        super.onStop();
    }

    private void loadSessions() {
        AppDatabase database = CoTalkApp.getInstance().getDatabase();
        database.execute(() -> {
            List<Session> sessions = sessionDao.queryAll();
            database.runOnMain(() -> {
                if (!isAdded() || getView() == null) {
                    return;
                }
                adapter.setSessions(sessions);
                boolean empty = sessions.isEmpty();
                emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
                recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
            });
        });
    }

    private void openSession(Session session) {
        startActivity(ChatActivity.createIntent(
                requireContext(), session.id, session.displayName()));
    }

    @Override
    public void onSessionsChanged() {
        loadSessions();
    }

    @Override
    public void onMembersChanged(long sessionId) {
        // 首页只需要会话摘要；成员变化在联系人页展示。
    }

    @Override
    public void onConnectionError(String message) {
        if (isAdded() && isVisible()) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
        }
    }
}
