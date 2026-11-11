package com.example.cotalkproject.ui.chat;

import android.Manifest;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cotalkproject.CoTalkApp;
import com.example.cotalkproject.R;
import com.example.cotalkproject.data.AppDatabase;
import com.example.cotalkproject.data.LocalUserDao;
import com.example.cotalkproject.data.MessageDao;
import com.example.cotalkproject.data.Prefs;
import com.example.cotalkproject.data.SessionDao;
import com.example.cotalkproject.model.LocalUser;
import com.example.cotalkproject.model.Message;
import com.example.cotalkproject.model.Session;
import com.example.cotalkproject.net.Protocol;
import com.example.cotalkproject.net.SessionManager;
import com.example.cotalkproject.ui.serverinfo.ServerInfoActivity;
import com.example.cotalkproject.ui.widget.BottomSheetMenu;
import com.example.cotalkproject.ui.widget.ZoomImageView;
import com.example.cotalkproject.util.ChatFileStore;
import com.example.cotalkproject.util.EmojiStore;
import com.example.cotalkproject.util.ImageUtil;
import com.example.cotalkproject.util.MediaSaver;
import com.example.cotalkproject.util.UiUtil;
import com.example.cotalkproject.util.VoicePlayer;
import com.example.cotalkproject.util.VoiceRecorder;
import com.google.android.material.imageview.ShapeableImageView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ChatActivity extends AppCompatActivity implements SessionManager.ChatListener {

    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_ROOM_NAME = "room_name";

    private static final int HISTORY_LIMIT = 50;
    private static final int MAX_MESSAGE_LENGTH = 500;
    private static final long SEND_TIMEOUT_MS = 10_000L;
    private static final long RECALL_WINDOW_MS = 2 * 60 * 1000L;

    private long sessionId;
    private String roomName;
    private String selfNickname = "我";

    private RecyclerView rvMessages;
    private ChatAdapter adapter;
    private LinearLayoutManager layoutManager;
    private EditText etMessage;
    private Button btnSend;
    private ImageButton btnVoice;
    private ImageButton btnKeyboard;
    private ImageButton btnEmoji;
    private ImageButton btnMore;
    private TextView btnHoldVoice;
    private View voiceRecordOverlay;
    private TextView tvVoiceTimer;
    private TextView tvVoiceHint;
    private View inputActions;
    private View llEmptyActions;
    private TextView tvBanner;
    private TextView tvEmpty;
    private TextView tvHistoryHint;
    private TextView tvTitle;
    private RecyclerView rvEmoji;
    private View emojiPanel;
    private View morePanel;
    private EmojiStore emojiStore;
    private EmojiAdapter emojiAdapter;

    private AppDatabase database;
    private MessageDao messageDao;
    private SessionDao sessionDao;
    private SessionManager sessionManager;
    private Prefs prefs;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Runnable> pendingTimeouts = new HashMap<>();
    private final Map<String, String> pendingPreviews = new HashMap<>();
    private final Set<String> pendingFileRequests = new HashSet<>();
    private final Set<String> failedFileRequests = new HashSet<>();

    private File pendingCameraFile;
    private Uri pendingCameraUri;

    private final VoiceRecorder voiceRecorder = new VoiceRecorder();
    private final VoicePlayer voicePlayer = new VoicePlayer();
    private boolean voiceRecording;
    private boolean voiceFinishing;
    private boolean voiceCancel;
    private float voiceDownRawY;
    private Runnable voiceTimerRunnable;
    private Message pendingVoicePlayback;
    private Message pendingEmojiSave;
    private Message pendingImageSaveMessage;
    private File pendingSaveImageFile;
    private String playingVoiceKey = "";
    private String pausedVoiceKey = "";

    private final ActivityResultLauncher<String> storagePermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        File pending = pendingSaveImageFile;
                        pendingSaveImageFile = null;
                        if (granted && pending != null) {
                            saveImageFile(pending);
                        } else if (!granted) {
                            Toast.makeText(this,
                                    R.string.chat_storage_permission_denied,
                                    Toast.LENGTH_SHORT).show();
                        }
                    });

    private final ActivityResultLauncher<String> recordAudioPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted) {
                            Toast.makeText(this,
                                    R.string.chat_voice_permission_granted,
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this,
                                    R.string.chat_voice_permission_denied,
                                    Toast.LENGTH_SHORT).show();
                        }
                    });

    private final ActivityResultLauncher<String> galleryLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.GetContent(),
                    uri -> {
                        if (uri != null) {
                            processPickedImage(uri, false);
                        }
                    });

    private final ActivityResultLauncher<String> emojiPickerLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.GetContent(),
                    uri -> {
                        if (uri != null) {
                            processPickedImage(uri, true);
                        }
                    });

    private final ActivityResultLauncher<Uri> cameraLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.TakePicture(),
                    success -> handleCameraResult(success));

    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted) {
                            launchCamera();
                        } else {
                            Toast.makeText(this,
                                    R.string.chat_camera_permission_denied,
                                    Toast.LENGTH_SHORT).show();
                        }
                    });

    private boolean historyHasMore = true;
    private boolean loadingHistory;
    private boolean atBottom = true;
    private boolean voiceMode;
    private Runnable draftSaveRunnable;
    private long lastSendFailureToastAt;

    public static Intent createIntent(Context context, long sessionId, String roomName) {
        Intent intent = new Intent(context, ChatActivity.class);
        intent.putExtra(EXTRA_SESSION_ID, sessionId);
        intent.putExtra(EXTRA_ROOM_NAME, roomName);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sessionId = getIntent().getLongExtra(EXTRA_SESSION_ID, -1);
        roomName = getIntent().getStringExtra(EXTRA_ROOM_NAME);
        if (sessionId <= 0) {
            finish();
            return;
        }
        historyHasMore = !historyCleared();

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_chat);
        UiUtil.applyLightSystemBars(this);
        UiUtil.applyChatInsets(
                findViewById(R.id.chat_root),
                findViewById(R.id.title_bar),
                findViewById(R.id.input_bar)
        );

        TextView btnBack = findViewById(R.id.btn_back);
        btnBack.setVisibility(View.VISIBLE);
        btnBack.setOnClickListener(v -> finish());

        if (TextUtils.isEmpty(roomName)) {
            roomName = getString(R.string.chat_title_default);
        }
        tvTitle = findViewById(R.id.tv_title);
        tvTitle.setText(roomName);

        View actions = findViewById(R.id.ll_actions);
        View serverInfoButton = findViewById(R.id.btn_action_server_info);
        actions.setVisibility(View.VISIBLE);
        serverInfoButton.setVisibility(View.VISIBLE);
        serverInfoButton.setOnClickListener(v -> startActivity(
                ServerInfoActivity.createIntent(this, sessionId)));

        rvMessages = findViewById(R.id.rv_messages);
        etMessage = findViewById(R.id.et_message);
        btnSend = findViewById(R.id.btn_send);
        btnVoice = findViewById(R.id.btn_voice);
        btnKeyboard = findViewById(R.id.btn_keyboard);
        btnEmoji = findViewById(R.id.btn_emoji);
        btnMore = findViewById(R.id.btn_more);
        btnHoldVoice = findViewById(R.id.btn_hold_voice);
        voiceRecordOverlay = findViewById(R.id.voice_record_overlay);
        tvVoiceTimer = findViewById(R.id.tv_voice_timer);
        tvVoiceHint = findViewById(R.id.tv_voice_hint);
        inputActions = findViewById(R.id.ll_input_actions);
        llEmptyActions = findViewById(R.id.ll_empty_actions);
        tvBanner = findViewById(R.id.tv_connection_banner);
        tvEmpty = findViewById(R.id.tv_chat_empty);
        tvHistoryHint = findViewById(R.id.tv_history_hint);
        rvEmoji = findViewById(R.id.rv_emoji);
        emojiPanel = findViewById(R.id.emoji_panel);
        morePanel = findViewById(R.id.more_panel);

        prefs = CoTalkApp.getInstance().getPrefs();
        emojiStore = new EmojiStore(this);
        setupComposer();
        setupPanels();

        database = CoTalkApp.getInstance().getDatabase();
        messageDao = new MessageDao(database);
        sessionDao = new SessionDao(database);
        sessionManager = CoTalkApp.getInstance().getSessionManager();

        layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        rvMessages.setLayoutManager(layoutManager);
        adapter = new ChatAdapter(
                sessionId,
                CoTalkApp.getInstance().getAvatarLoader(),
                this::retryMessage,
                this::insertMention,
                this::pokeUser,
                this::showMessageActions,
                this::showImageFullscreen,
                this::playVoice,
                this::loadMedia);
        rvMessages.setAdapter(adapter);
        rvMessages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                atBottom = !recyclerView.canScrollVertically(1);
                if (dy < 0
                        && layoutManager.findFirstVisibleItemPosition() == 0
                        && adapter.getItemCount() > 0) {
                    loadOlder();
                }
            }
        });

        btnSend.setOnClickListener(v -> sendMessage());
        etMessage.setOnClickListener(v -> {
            closePanels();
            showKeyboard();
        });
        etMessage.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage();
                return true;
            }
            return false;
        });

        loadSelfNickname();
        loadSessionTitle();
        loadLocalMessages(true, -1);
        markRead();
    }

    private void setupComposer() {
        etMessage.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start,
                                          int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start,
                                      int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateComposerState();
                scheduleDraftSave();
            }
        });

        btnVoice.setOnClickListener(v -> enterVoiceMode());
        btnKeyboard.setOnClickListener(v -> exitVoiceMode());
        btnHoldVoice.setOnTouchListener(this::onHoldVoiceTouch);

        if (prefs.isAutoSaveDraft()) {
            String draft = prefs.getDraft(sessionId);
            if (!TextUtils.isEmpty(draft)) {
                etMessage.setText(draft);
                etMessage.setSelection(draft.length());
            }
        }
        updateComposerState();
    }

    private void setupPanels() {
        emojiAdapter = new EmojiAdapter(new EmojiAdapter.Listener() {
            @Override
            public void onAdd() {
                emojiPickerLauncher.launch("image/*");
            }

            @Override
            public void onEmoji(File file) {
                sendMediaFile(file, "emoji");
            }

            @Override
            public void onEmojiLongPress(File file) {
                confirmDeleteEmoji(file);
            }
        });
        rvEmoji.setLayoutManager(new GridLayoutManager(this, 6));
        rvEmoji.setAdapter(emojiAdapter);

        btnEmoji.setOnClickListener(v -> toggleEmojiPanel());
        btnMore.setOnClickListener(v -> toggleMorePanel());
        findViewById(R.id.btn_gallery).setOnClickListener(v ->
                galleryLauncher.launch("image/*"));
        findViewById(R.id.btn_camera).setOnClickListener(v ->
                requestCameraPermission());
    }

    private void confirmDeleteEmoji(File file) {
        BottomSheetMenu.showConfirm(
                this,
                getString(R.string.chat_emoji_delete_title),
                getString(R.string.chat_emoji_delete_message),
                getString(R.string.chat_emoji_delete_action),
                () -> {
                    if (emojiStore.delete(file)) {
                        emojiAdapter.setFiles(emojiStore.list());
                        Toast.makeText(this,
                                R.string.chat_emoji_deleted,
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void toggleEmojiPanel() {
        boolean open = emojiPanel.getVisibility() != View.VISIBLE;
        closePanels();
        if (!open) {
            showKeyboard();
            return;
        }
        hideKeyboard();
        emojiAdapter.setFiles(emojiStore.list());
        emojiPanel.setVisibility(View.VISIBLE);
    }

    private void toggleMorePanel() {
        boolean open = morePanel.getVisibility() != View.VISIBLE;
        closePanels();
        if (!open) {
            showKeyboard();
            return;
        }
        hideKeyboard();
        morePanel.setVisibility(View.VISIBLE);
    }

    private void closePanels() {
        if (emojiPanel != null) {
            emojiPanel.setVisibility(View.GONE);
        }
        if (morePanel != null) {
            morePanel.setVisibility(View.GONE);
        }
    }

    private void showKeyboard() {
        etMessage.requestFocus();
        etMessage.post(() -> {
            InputMethodManager manager = (InputMethodManager)
                    getSystemService(INPUT_METHOD_SERVICE);
            if (manager != null) {
                manager.showSoftInput(etMessage, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private void updateComposerState() {
        if (voiceMode) {
            return;
        }
        boolean hasText = etMessage != null
                && !TextUtils.isEmpty(etMessage.getText());
        if (hasText) {
            closePanels();
        }
        etMessage.setVisibility(View.VISIBLE);
        btnHoldVoice.setVisibility(View.GONE);
        btnKeyboard.setVisibility(View.GONE);
        btnVoice.setVisibility(View.VISIBLE);
        inputActions.setVisibility(View.VISIBLE);
        llEmptyActions.setVisibility(hasText ? View.GONE : View.VISIBLE);
        btnSend.setVisibility(hasText ? View.VISIBLE : View.GONE);
    }

    private void enterVoiceMode() {
        voiceMode = true;
        closePanels();
        hideKeyboard();
        etMessage.setVisibility(View.GONE);
        btnHoldVoice.setVisibility(View.VISIBLE);
        btnVoice.setVisibility(View.GONE);
        btnKeyboard.setVisibility(View.VISIBLE);
        inputActions.setVisibility(View.GONE);
        llEmptyActions.setVisibility(View.GONE);
        btnSend.setVisibility(View.GONE);
        if (!hasRecordPermission()) {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void exitVoiceMode() {
        voiceMode = false;
        updateComposerState();
        showKeyboard();
    }

    private boolean onHoldVoiceTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!hasRecordPermission()) {
                    recordAudioPermissionLauncher.launch(
                            Manifest.permission.RECORD_AUDIO);
                    return true;
                }
                startVoiceRecording(event.getRawY());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (voiceRecording) {
                    updateVoiceCancel(event.getRawY());
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (voiceRecording) {
                    finishVoiceRecording(voiceCancel);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (voiceRecording) {
                    finishVoiceRecording(true);
                }
                return true;
            default:
                return false;
        }
    }

    private boolean hasRecordPermission() {
        return ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startVoiceRecording(float rawY) {
        if (voiceRecording) {
            return;
        }
        File file;
        try {
            file = VoiceRecorder.createOutputFile(this);
        } catch (IOException e) {
            Toast.makeText(this, R.string.chat_voice_start_failed,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (!voiceRecorder.start(file)) {
            Toast.makeText(this, R.string.chat_voice_start_failed,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        voiceRecording = true;
        voiceFinishing = false;
        voiceCancel = false;
        voiceDownRawY = rawY;
        voiceRecordOverlay.setVisibility(View.VISIBLE);
        tvVoiceTimer.setText(formatVoiceDuration(0));
        updateVoiceCancelUi(false);
        startVoiceTimer();
    }

    private void updateVoiceCancel(float rawY) {
        float threshold = 80 * getResources().getDisplayMetrics().density;
        boolean cancel = voiceDownRawY - rawY > threshold;
        if (cancel != voiceCancel) {
            voiceCancel = cancel;
            updateVoiceCancelUi(cancel);
        }
    }

    private void updateVoiceCancelUi(boolean cancel) {
        tvVoiceHint.setText(cancel
                ? R.string.chat_voice_release_cancel
                : R.string.chat_voice_slide_cancel);
        tvVoiceHint.setTextColor(ContextCompat.getColor(
                this, cancel ? R.color.danger : R.color.white));
        btnHoldVoice.setText(cancel
                ? R.string.chat_voice_release_cancel
                : R.string.chat_voice_release_send);
    }

    private void startVoiceTimer() {
        stopVoiceTimer();
        voiceTimerRunnable = new Runnable() {
            @Override
            public void run() {
                if (!voiceRecording) {
                    return;
                }
                long elapsed = voiceRecorder.elapsedMs();
                tvVoiceTimer.setText(formatVoiceDuration(elapsed));
                if (elapsed >= VoiceRecorder.MAX_DURATION_MS) {
                    finishVoiceRecording(false);
                    Toast.makeText(ChatActivity.this,
                            R.string.chat_voice_max_reached,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                handler.postDelayed(this, 100L);
            }
        };
        handler.post(voiceTimerRunnable);
    }

    private void stopVoiceTimer() {
        if (voiceTimerRunnable != null) {
            handler.removeCallbacks(voiceTimerRunnable);
            voiceTimerRunnable = null;
        }
    }

    private static String formatVoiceDuration(long ms) {
        long seconds = Math.max(0L, ms) / 1000L;
        long minutes = Math.min(99L, seconds / 60L);
        return String.format(Locale.ROOT, "%02d:%02d",
                minutes, seconds % 60L);
    }

    private void finishVoiceRecording(boolean cancel) {
        if (!voiceRecording || voiceFinishing) {
            return;
        }
        voiceFinishing = true;
        voiceRecording = false;
        voiceCancel = false;
        stopVoiceTimer();
        voiceRecordOverlay.setVisibility(View.GONE);
        btnHoldVoice.setText(R.string.chat_hold_to_talk);

        if (cancel) {
            voiceRecorder.cancel();
            Toast.makeText(this, R.string.chat_voice_cancelled,
                    Toast.LENGTH_SHORT).show();
            voiceFinishing = false;
            return;
        }

        long duration = voiceRecorder.stop();
        if (duration <= 0) {
            Toast.makeText(this, R.string.chat_voice_too_short,
                    Toast.LENGTH_SHORT).show();
            voiceFinishing = false;
            return;
        }
        File file = voiceRecorder.getOutput();
        if (file == null || !file.isFile()) {
            Toast.makeText(this, R.string.chat_voice_too_short,
                    Toast.LENGTH_SHORT).show();
            voiceFinishing = false;
            return;
        }
        sendMediaFile(file, "voice", "audio/mp4",
                String.valueOf(duration), file.getName());
        voiceFinishing = false;
    }

    private void cancelVoiceRecording() {
        if (!voiceRecording) {
            return;
        }
        voiceRecording = false;
        voiceFinishing = false;
        voiceCancel = false;
        stopVoiceTimer();
        voiceRecordOverlay.setVisibility(View.GONE);
        btnHoldVoice.setText(R.string.chat_hold_to_talk);
        voiceRecorder.cancel();
    }

    private void playVoice(Message message) {
        if (message == null) {
            return;
        }
        if (message.fileExpired) {
            Toast.makeText(this, R.string.chat_voice_unavailable,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String key = ChatAdapter.messageKey(message);
        File file = resolveLocalFile(message);
        if (file == null) {
            if (!sessionManager.isActive(sessionId)) {
                Toast.makeText(this, R.string.chat_voice_unavailable,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (TextUtils.isEmpty(message.fileId)) {
                Toast.makeText(this, R.string.chat_voice_unavailable,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            pendingVoicePlayback = message;
            if (pendingFileRequests.contains(message.fileId)) {
                Toast.makeText(this, R.string.chat_voice_loading,
                        Toast.LENGTH_SHORT).show();
            } else {
                failedFileRequests.remove(message.fileId);
                requestFile(message.fileId);
                Toast.makeText(this, R.string.chat_voice_loading,
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (key.equals(playingVoiceKey) && voicePlayer.isPlaying()) {
            voicePlayer.pause();
            playingVoiceKey = "";
            pausedVoiceKey = key;
            adapter.setPlayingMessage("");
            return;
        }
        if (key.equals(pausedVoiceKey) && voicePlayer.isPaused()) {
            voicePlayer.resume();
            playingVoiceKey = key;
            pausedVoiceKey = "";
            adapter.setPlayingMessage(key);
            return;
        }

        pendingVoicePlayback = null;
        playingVoiceKey = "";
        pausedVoiceKey = "";
        adapter.setPlayingMessage("");
        voicePlayer.play(file, new VoicePlayer.Listener() {
            @Override
            public void onStarted() {
                playingVoiceKey = key;
                pausedVoiceKey = "";
                adapter.setPlayingMessage(key);
            }

            @Override
            public void onCompleted() {
                playingVoiceKey = "";
                pausedVoiceKey = "";
                adapter.setPlayingMessage("");
            }

            @Override
            public void onError(String detail) {
                playingVoiceKey = "";
                pausedVoiceKey = "";
                adapter.setPlayingMessage("");
                Toast.makeText(ChatActivity.this,
                        R.string.chat_voice_play_failed,
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void stopVoicePlayback() {
        voicePlayer.stop();
        playingVoiceKey = "";
        pausedVoiceKey = "";
        pendingVoicePlayback = null;
        if (adapter != null) {
            adapter.setPlayingMessage("");
        }
    }

    private void hideKeyboard() {
        InputMethodManager manager = (InputMethodManager)
                getSystemService(INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.hideSoftInputFromWindow(etMessage.getWindowToken(), 0);
        }
    }

    private void scheduleDraftSave() {
        if (draftSaveRunnable != null) {
            handler.removeCallbacks(draftSaveRunnable);
            draftSaveRunnable = null;
        }
        if (!prefs.isAutoSaveDraft()) {
            return;
        }
        draftSaveRunnable = () -> {
            draftSaveRunnable = null;
            prefs.setDraft(sessionId, etMessage.getText().toString());
        };
        handler.postDelayed(draftSaveRunnable, 300);
    }

    private void saveDraftNow() {
        if (draftSaveRunnable != null) {
            handler.removeCallbacks(draftSaveRunnable);
            draftSaveRunnable = null;
        }
        if (!prefs.isAutoSaveDraft()) {
            return;
        }
        prefs.setDraft(sessionId, etMessage.getText().toString());
    }

    private void clearDraftNow() {
        if (draftSaveRunnable != null) {
            handler.removeCallbacks(draftSaveRunnable);
            draftSaveRunnable = null;
        }
        prefs.clearDraft(sessionId);
    }

    private void processPickedImage(Uri uri, boolean addEmoji) {
        Toast.makeText(this, R.string.chat_image_compressing,
                Toast.LENGTH_SHORT).show();
        database.execute(() -> {
            try {
                File output = addEmoji
                        ? emojiStore.add(uri)
                        : ImageUtil.compressForChat(this, uri);
                deleteCameraTempIfNeeded(uri);
                database.runOnMain(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    if (addEmoji) {
                        emojiAdapter.setFiles(emojiStore.list());
                        Toast.makeText(this, R.string.chat_emoji_added,
                                Toast.LENGTH_SHORT).show();
                    } else {
                        sendMediaFile(output, "image");
                    }
                });
            } catch (IOException e) {
                deleteCameraTempIfNeeded(uri);
                String message = e.getMessage();
                int text;
                if (message != null && message.contains("1MB")) {
                    text = R.string.chat_image_too_large;
                } else if (addEmoji
                        && EmojiStore.ERROR_LIMIT.equals(message)) {
                    text = R.string.chat_emoji_limit;
                } else {
                    text = R.string.chat_image_pick_failed;
                }
                database.runOnMain(() -> Toast.makeText(
                        this, text, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void deleteCameraTempIfNeeded(Uri uri) {
        if (pendingCameraFile == null || uri == null
                || !"file".equalsIgnoreCase(uri.getScheme())) {
            return;
        }
        if (pendingCameraFile.getAbsolutePath().equals(uri.getPath())) {
            //noinspection ResultOfMethodCallIgnored
            pendingCameraFile.delete();
            pendingCameraFile = null;
            pendingCameraUri = null;
        }
    }

    private void requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            launchCamera();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void launchCamera() {
        try {
            File dir = new File(getCacheDir(), "chat_camera");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("无法创建拍摄目录");
            }
            pendingCameraFile = new File(dir,
                    "camera_" + System.currentTimeMillis() + ".jpg");
            pendingCameraUri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", pendingCameraFile);
            cameraLauncher.launch(pendingCameraUri);
        } catch (Exception e) {
            pendingCameraFile = null;
            pendingCameraUri = null;
            Toast.makeText(this, R.string.chat_camera_failed,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void handleCameraResult(boolean success) {
        if (!success || pendingCameraFile == null
                || !pendingCameraFile.isFile()) {
            if (pendingCameraFile != null) {
                //noinspection ResultOfMethodCallIgnored
                pendingCameraFile.delete();
            }
            pendingCameraFile = null;
            pendingCameraUri = null;
            if (!success) {
                Toast.makeText(this, R.string.chat_camera_failed,
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
        processPickedImage(Uri.fromFile(pendingCameraFile), false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        sessionManager.setChatListener(this);
        sessionManager.setOpenChatSession(sessionId);
        loadSessionTitle();
        if (sessionManager.isActive(sessionId)) {
            hideBanner();
            btnSend.setEnabled(true);
            if (!historyCleared() && adapter.getItemCount() == 0) {
                requestHistory(null, "history-latest", true);
            }
        } else {
            showBanner(getString(R.string.offline_banner));
            btnSend.setEnabled(true);
        }
        markRead();
    }

    @Override
    protected void onStop() {
        cancelVoiceRecording();
        stopVoicePlayback();
        pendingEmojiSave = null;
        pendingImageSaveMessage = null;
        pendingSaveImageFile = null;
        if (etMessage != null) {
            saveDraftNow();
        }
        sessionManager.setOpenChatSession(-1);
        markRead();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        sessionManager.clearChatListener(this);
        if (draftSaveRunnable != null) {
            handler.removeCallbacks(draftSaveRunnable);
            draftSaveRunnable = null;
        }
        for (Runnable runnable : pendingTimeouts.values()) {
            handler.removeCallbacks(runnable);
        }
        if (!pendingTimeouts.isEmpty()) {
            String[] pendingIds = pendingTimeouts.keySet().toArray(new String[0]);
            database.execute(() -> {
                for (String clientMsgId : pendingIds) {
                    messageDao.updateStatus(sessionId, clientMsgId,
                            Message.STATUS_FAILED, null, 0);
                }
            });
        }
        pendingTimeouts.clear();
        voicePlayer.stop();
        if (voiceRecording) {
            voiceRecorder.cancel();
        }
        super.onDestroy();
    }

    private void loadSelfNickname() {
        LocalUserDao userDao = new LocalUserDao(database);
        database.execute(() -> {
            LocalUser user = userDao.get();
            if (user != null) {
                String nickname = user.nickname;
                database.runOnMain(() -> selfNickname = nickname);
            }
        });
    }

    private void loadSessionTitle() {
        database.execute(() -> {
            Session session = sessionDao.getById(sessionId);
            database.runOnMain(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (session != null && !TextUtils.isEmpty(session.displayName())) {
                    roomName = session.displayName();
                }
                tvTitle.setText(roomName);
            });
        });
    }

    private void loadLocalMessages(boolean scrollToBottom, int keepPosition) {
        database.execute(() -> {
            List<Message> messages = messageDao.queryAll(sessionId);
            database.runOnMain(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                adapter.setMessages(messages);
                toggleEmpty();
                if (scrollToBottom) {
                    scrollToBottom();
                } else if (keepPosition >= 0 && !messages.isEmpty()) {
                    rvMessages.scrollToPosition(
                            Math.min(keepPosition, messages.size() - 1));
                }
            });
        });
    }

    private void loadMedia(Message message,
                           ShapeableImageView imageView,
                           TextView placeholder,
                           boolean emoji) {
        String token = !TextUtils.isEmpty(message.msgId)
                ? message.msgId
                : message.clientMsgId;
        if (TextUtils.isEmpty(token)) {
            token = "msg-" + message.id;
        }
        imageView.setTag(token);
        imageView.setVisibility(View.VISIBLE);
        imageView.setImageDrawable(null);
        placeholder.setVisibility(View.GONE);
        placeholder.setOnClickListener(null);

        if (message.fileExpired) {
            showMediaPlaceholder(imageView, placeholder,
                    R.string.file_expired, null);
            return;
        }

        File local = resolveLocalFile(message);
        if (local != null) {
            showLocalMedia(local, imageView, placeholder, token, emoji);
            return;
        }

        String fileId = message.fileId;
        if (TextUtils.isEmpty(fileId)) {
            showMediaPlaceholder(imageView, placeholder,
                    R.string.chat_image_unavailable, null);
            return;
        }
        if (!sessionManager.isActive(sessionId)) {
            failedFileRequests.add(fileId);
            showMediaPlaceholder(imageView, placeholder,
                    R.string.chat_image_failed, fileId);
            return;
        }
        if (failedFileRequests.contains(fileId)) {
            showMediaPlaceholder(imageView, placeholder,
                    R.string.chat_image_failed, fileId);
            return;
        }
        if (pendingFileRequests.contains(fileId)) {
            showMediaPlaceholder(imageView, placeholder,
                    R.string.chat_image_loading, null);
            return;
        }
        showMediaPlaceholder(imageView, placeholder,
                R.string.chat_image_loading, null);
        requestFile(fileId);
    }

    private File resolveLocalFile(Message message) {
        if (!TextUtils.isEmpty(message.localPath)) {
            File local = new File(message.localPath);
            if (local.isFile()) {
                return local;
            }
        }
        return ChatFileStore.findCached(this, message.fileId, message.fileMime);
    }

    private void showLocalMedia(File file,
                                ShapeableImageView imageView,
                                TextView placeholder,
                                String token,
                                boolean emoji) {
        database.execute(() -> {
            Bitmap bitmap = ImageUtil.decodeFileSampled(
                    file, emoji ? 512 : 1600);
            database.runOnMain(() -> {
                if (!token.equals(imageView.getTag())) {
                    return;
                }
                if (bitmap == null) {
                    showMediaPlaceholder(imageView, placeholder,
                            R.string.chat_image_unavailable, null);
                    return;
                }
                imageView.setImageBitmap(bitmap);
                imageView.setVisibility(View.VISIBLE);
                placeholder.setVisibility(View.GONE);
            });
        });
    }

    private void showMediaPlaceholder(ShapeableImageView imageView,
                                      TextView placeholder,
                                      int textRes,
                                      String retryFileId) {
        imageView.setVisibility(View.GONE);
        imageView.setImageDrawable(null);
        placeholder.setVisibility(View.VISIBLE);
        placeholder.setText(textRes);
        if (retryFileId == null) {
            placeholder.setOnClickListener(null);
        } else {
            placeholder.setOnClickListener(v -> {
                failedFileRequests.remove(retryFileId);
                pendingFileRequests.remove(retryFileId);
                adapter.notifyDataSetChanged();
            });
        }
    }

    private void requestFile(String fileId) {
        if (TextUtils.isEmpty(fileId)) {
            return;
        }
        if (!sessionManager.isActive(sessionId)) {
            failedFileRequests.add(fileId);
            return;
        }
        pendingFileRequests.add(fileId);
        try {
            JSONObject data = new JSONObject();
            data.put("fileId", fileId);
            JSONObject frame = Protocol.frame(Protocol.FILE_REQ, data);
            frame.put("reqId", "file|" + fileId);
            sessionManager.sendFrame(frame, new SessionManager.SendCallback() {
                @Override
                public void onSuccess() {
                }

                @Override
                public void onError(String error) {
                    database.runOnMain(() -> {
                        pendingFileRequests.remove(fileId);
                        failedFileRequests.add(fileId);
                        adapter.notifyDataSetChanged();
                    });
                }
            });
        } catch (JSONException e) {
            pendingFileRequests.remove(fileId);
            failedFileRequests.add(fileId);
        }
    }

    private void handleFileResponse(JSONObject data) {
        String fileId = data.optString("fileId", "");
        if (fileId.isEmpty()) {
            return;
        }
        pendingFileRequests.remove(fileId);
        if (data.optBoolean("expired", false)) {
            failedFileRequests.remove(fileId);
            if (pendingVoicePlayback != null
                    && fileId.equals(pendingVoicePlayback.fileId)) {
                pendingVoicePlayback = null;
                Toast.makeText(this, R.string.chat_voice_unavailable,
                        Toast.LENGTH_SHORT).show();
            }
            if (pendingEmojiSave != null
                    && fileId.equals(pendingEmojiSave.fileId)) {
                pendingEmojiSave = null;
                Toast.makeText(this, R.string.chat_emoji_unavailable,
                        Toast.LENGTH_SHORT).show();
            }
            if (pendingImageSaveMessage != null
                    && fileId.equals(pendingImageSaveMessage.fileId)) {
                pendingImageSaveMessage = null;
                Toast.makeText(this, R.string.chat_image_unavailable,
                        Toast.LENGTH_SHORT).show();
            }
            database.execute(() -> messageDao.markFileExpiredByFileId(
                    sessionId, fileId));
            adapter.markFileExpired(fileId);
            return;
        }

        String encoded = data.optString("data", "");
        String mime = data.optString("mime", "image/jpeg");
        if (encoded.isEmpty()) {
            failedFileRequests.add(fileId);
            if (pendingVoicePlayback != null
                    && fileId.equals(pendingVoicePlayback.fileId)) {
                pendingVoicePlayback = null;
                Toast.makeText(this, R.string.chat_voice_unavailable,
                        Toast.LENGTH_SHORT).show();
            }
            if (pendingEmojiSave != null
                    && fileId.equals(pendingEmojiSave.fileId)) {
                pendingEmojiSave = null;
                Toast.makeText(this, R.string.chat_emoji_unavailable,
                        Toast.LENGTH_SHORT).show();
            }
            if (pendingImageSaveMessage != null
                    && fileId.equals(pendingImageSaveMessage.fileId)) {
                pendingImageSaveMessage = null;
                Toast.makeText(this, R.string.chat_image_unavailable,
                        Toast.LENGTH_SHORT).show();
            }
            adapter.notifyFileChanged(fileId);
            return;
        }
        database.execute(() -> {
            try {
                byte[] bytes = decodeBase64(encoded);
                File file = ChatFileStore.save(this, fileId, mime, bytes);
                messageDao.updateLocalPathByFileId(
                        sessionId, fileId, file.getAbsolutePath());
                database.runOnMain(() -> {
                    failedFileRequests.remove(fileId);
                    adapter.updateFilePath(fileId, file.getAbsolutePath());
                    Message pendingEmoji = pendingEmojiSave;
                    if (pendingEmoji != null
                            && fileId.equals(pendingEmoji.fileId)) {
                        pendingEmojiSave = null;
                        pendingEmoji.localPath = file.getAbsolutePath();
                        saveEmojiFile(file);
                    }
                    Message pendingImage = pendingImageSaveMessage;
                    if (pendingImage != null
                            && fileId.equals(pendingImage.fileId)) {
                        pendingImageSaveMessage = null;
                        pendingImage.localPath = file.getAbsolutePath();
                        saveImageToGallery(file);
                    }
                    Message pendingVoice = pendingVoicePlayback;
                    if (pendingVoice != null
                            && fileId.equals(pendingVoice.fileId)) {
                        pendingVoicePlayback = null;
                        pendingVoice.localPath = file.getAbsolutePath();
                        playVoice(pendingVoice);
                    }
                });
            } catch (Exception e) {
                database.runOnMain(() -> {
                    failedFileRequests.add(fileId);
                    adapter.notifyFileChanged(fileId);
                });
            }
        });
    }

    private static byte[] decodeBase64(String encoded) {
        String payload = encoded;
        int comma = payload.indexOf(',');
        if (payload.startsWith("data:") && comma > 0) {
            payload = payload.substring(comma + 1);
        }
        return Base64.decode(payload, Base64.DEFAULT);
    }

    private void handleFileRequestError(JSONObject frame) {
        String reqId = frame.optString("reqId", "");
        if (!reqId.startsWith("file|")) {
            return;
        }
        String fileId = reqId.substring("file|".length());
        pendingFileRequests.remove(fileId);
        failedFileRequests.add(fileId);
        if (pendingVoicePlayback != null
                && fileId.equals(pendingVoicePlayback.fileId)) {
            pendingVoicePlayback = null;
            Toast.makeText(this, R.string.chat_voice_unavailable,
                    Toast.LENGTH_SHORT).show();
        }
        if (pendingEmojiSave != null
                && fileId.equals(pendingEmojiSave.fileId)) {
            pendingEmojiSave = null;
            Toast.makeText(this, R.string.chat_emoji_unavailable,
                    Toast.LENGTH_SHORT).show();
        }
        if (pendingImageSaveMessage != null
                && fileId.equals(pendingImageSaveMessage.fileId)) {
            pendingImageSaveMessage = null;
            Toast.makeText(this, R.string.chat_image_unavailable,
                    Toast.LENGTH_SHORT).show();
        }
        adapter.notifyDataSetChanged();
    }

    private void toggleEmpty() {
        tvEmpty.setVisibility(adapter.getItemCount() == 0
                ? View.VISIBLE
                : View.GONE);
    }

    private void scrollToBottom() {
        rvMessages.post(() -> {
            if (adapter.getItemCount() > 0) {
                rvMessages.scrollToPosition(adapter.getItemCount() - 1);
                atBottom = true;
            }
        });
    }

    private void markRead() {
        if (sessionId > 0) {
            database.execute(() -> sessionDao.markRead(sessionId));
        }
    }

    private void insertMention(String nickname) {
        String name = TextUtils.isEmpty(nickname)
                ? getString(R.string.member_default)
                : nickname;
        String mention = getString(R.string.chat_mention_format, name);
        if (!mention.endsWith(" ")) {
            mention += " ";
        }
        Editable editable = etMessage.getText();
        int start = etMessage.getSelectionStart();
        if (start < 0) {
            start = editable.length();
        }
        boolean needSpace = start > 0
                && !Character.isWhitespace(editable.charAt(start - 1));
        String insertion = (needSpace ? " " : "") + mention;
        editable.insert(start, insertion);
        etMessage.setSelection(start + insertion.length());
        etMessage.requestFocus();
        etMessage.post(() -> {
            InputMethodManager manager = (InputMethodManager)
                    getSystemService(INPUT_METHOD_SERVICE);
            if (manager != null) {
                manager.showSoftInput(etMessage, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private void pokeUser(String userId, String nickname) {
        if (TextUtils.isEmpty(userId)) {
            return;
        }
        if (!sessionManager.isActive(sessionId)) {
            Toast.makeText(this, R.string.send_offline, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONObject data = new JSONObject();
            data.put("targetId", userId);
            JSONObject frame = Protocol.frame(Protocol.POKE_REQ, data);
            frame.put("reqId", "poke-" + System.currentTimeMillis());
            sessionManager.sendFrame(frame, new SessionManager.SendCallback() {
                @Override
                public void onSuccess() {
                }

                @Override
                public void onError(String error) {
                    Toast.makeText(ChatActivity.this,
                            R.string.poke_failed, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (JSONException e) {
            Toast.makeText(this, R.string.poke_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void showMessageActions(Message message) {
        String type = message.msgType == null ? "text" : message.msgType;
        boolean system = "system".equals(type);
        boolean canCopy = !TextUtils.isEmpty(message.content)
                && ("text".equals(type) || "ai".equals(type) || system);
        boolean canSaveImage = "image".equals(type) && !message.fileExpired;
        boolean canSaveEmoji = "emoji".equals(type) && !message.fileExpired;
        boolean canReply = !system;
        boolean canRecall = message.isSelf
                && !system
                && !TextUtils.isEmpty(message.msgId)
                && message.status == Message.STATUS_SENT
                && System.currentTimeMillis() - message.timestamp
                <= RECALL_WINDOW_MS;

        ArrayList<BottomSheetMenu.Action> actions = new ArrayList<>();
        if (canCopy) {
            actions.add(new BottomSheetMenu.Action(
                    getString(R.string.message_copy),
                    () -> copyMessage(message)));
        }
        if (canSaveImage) {
            actions.add(new BottomSheetMenu.Action(
                    getString(R.string.chat_image_save),
                    () -> saveImageMessage(message)));
        }
        if (canSaveEmoji) {
            actions.add(new BottomSheetMenu.Action(
                    getString(R.string.chat_emoji_save),
                    () -> saveEmojiMessage(message)));
        }
        if (canReply) {
            actions.add(new BottomSheetMenu.Action(
                    getString(R.string.message_reply),
                    this::replyPlaceholder));
        }
        if (canRecall) {
            actions.add(new BottomSheetMenu.Action(
                    getString(R.string.message_recall),
                    () -> requestRecall(message)));
        }
        if (!actions.isEmpty()) {
            BottomSheetMenu.showActions(this, null, actions);
        }
    }

    private void replyPlaceholder() {
        Toast.makeText(this, R.string.reply_not_available,
                Toast.LENGTH_SHORT).show();
    }

    private void saveImageMessage(Message message) {
        if (message == null || message.fileExpired) {
            Toast.makeText(this, R.string.chat_image_unavailable,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        File local = resolveLocalFile(message);
        if (local != null) {
            saveImageToGallery(local);
            return;
        }
        if (!sessionManager.isActive(sessionId)
                || TextUtils.isEmpty(message.fileId)) {
            Toast.makeText(this, R.string.chat_image_unavailable,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        pendingImageSaveMessage = message;
        failedFileRequests.remove(message.fileId);
        if (!pendingFileRequests.contains(message.fileId)) {
            requestFile(message.fileId);
        }
        Toast.makeText(this, R.string.chat_image_loading,
                Toast.LENGTH_SHORT).show();
    }

    private void saveEmojiMessage(Message message) {
        if (message == null || message.fileExpired) {
            Toast.makeText(this, R.string.chat_emoji_unavailable,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        File local = resolveLocalFile(message);
        if (local != null) {
            saveEmojiFile(local);
            return;
        }
        if (!sessionManager.isActive(sessionId)
                || TextUtils.isEmpty(message.fileId)) {
            Toast.makeText(this, R.string.chat_emoji_unavailable,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        pendingEmojiSave = message;
        failedFileRequests.remove(message.fileId);
        if (!pendingFileRequests.contains(message.fileId)) {
            requestFile(message.fileId);
        }
        Toast.makeText(this, R.string.chat_emoji_saving,
                Toast.LENGTH_SHORT).show();
    }

    private void saveEmojiFile(File source) {
        database.execute(() -> {
            try {
                emojiStore.importFile(source);
                database.runOnMain(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    emojiAdapter.setFiles(emojiStore.list());
                    Toast.makeText(this, R.string.chat_emoji_saved,
                            Toast.LENGTH_SHORT).show();
                });
            } catch (IOException e) {
                int text = EmojiStore.ERROR_LIMIT.equals(e.getMessage())
                        ? R.string.chat_emoji_limit
                        : R.string.chat_emoji_save_failed;
                database.runOnMain(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void copyMessage(Message message) {
        ClipboardManager manager = (ClipboardManager)
                getSystemService(CLIPBOARD_SERVICE);
        if (manager != null) {
            manager.setPrimaryClip(ClipData.newPlainText(
                    "CoTalk", message.content));
            Toast.makeText(this, R.string.message_copied,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void requestRecall(Message message) {
        if (!sessionManager.isActive(sessionId)) {
            Toast.makeText(this, R.string.send_offline, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONObject data = new JSONObject();
            data.put("msgId", message.msgId);
            JSONObject frame = Protocol.frame(Protocol.RECALL_REQ, data);
            frame.put("reqId", "recall-" + System.currentTimeMillis());
            sessionManager.sendFrame(frame, new SessionManager.SendCallback() {
                @Override
                public void onSuccess() {
                }

                @Override
                public void onError(String error) {
                    Toast.makeText(ChatActivity.this,
                            getString(R.string.message_recall_failed, error),
                            Toast.LENGTH_SHORT).show();
                }
            });
        } catch (JSONException e) {
            Toast.makeText(this,
                    getString(R.string.message_recall_failed, ""),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void showImageFullscreen(Message message) {
        if (!"image".equals(message.msgType) || message.fileExpired) {
            return;
        }
        File file = resolveLocalFile(message);
        if (file == null) {
            Toast.makeText(this, R.string.chat_image_loading,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        database.execute(() -> {
            Bitmap bitmap = ImageUtil.decodeFileSampled(file, 2048);
            database.runOnMain(() -> {
                if (bitmap == null || isFinishing() || isDestroyed()) {
                    if (bitmap != null) {
                        bitmap.recycle();
                    }
                    Toast.makeText(this, R.string.chat_image_loading_failed,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                Dialog dialog = new Dialog(this,
                        android.R.style.Theme_Black_NoTitleBar_Fullscreen);
                ZoomImageView imageView = new ZoomImageView(this);
                imageView.setImageBitmap(bitmap);
                imageView.setOnClickListener(v -> dialog.dismiss());
                imageView.setOnLongPressListener(() ->
                        showImageSaveDialog(file));
                dialog.setContentView(imageView,
                        new ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                dialog.setOnDismissListener(d -> bitmap.recycle());
                dialog.show();
                Window window = dialog.getWindow();
                if (window != null) {
                    window.setBackgroundDrawable(
                            new ColorDrawable(Color.BLACK));
                    window.setLayout(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT);
                }
                imageView.post(imageView::resetMatrix);
            });
        });
    }

    private void showImageSaveDialog(File file) {
        ArrayList<BottomSheetMenu.Action> actions = new ArrayList<>();
        actions.add(new BottomSheetMenu.Action(
                getString(R.string.chat_image_save),
                () -> saveImageToGallery(file)));
        BottomSheetMenu.showActions(this, null, actions);
    }

    private void saveImageToGallery(File file) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingSaveImageFile = file;
            storagePermissionLauncher.launch(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }
        saveImageFile(file);
    }

    private void saveImageFile(File file) {
        database.execute(() -> {
            try {
                MediaSaver.saveImageToGallery(this, file);
                database.runOnMain(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(this, R.string.chat_image_saved,
                                Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                database.runOnMain(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(this, R.string.chat_image_save_failed,
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void sendMessage() {
        String content = etMessage.getText().toString().trim();
        if (content.isEmpty()) {
            return;
        }
        if (content.length() > MAX_MESSAGE_LENGTH) {
            Toast.makeText(this, R.string.message_too_long, Toast.LENGTH_SHORT).show();
            return;
        }
        boolean online = sessionManager.isActive(sessionId);

        String clientMsgId = "cm-" + UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 16);
        Message message = new Message();
        message.sessionId = sessionId;
        message.clientMsgId = clientMsgId;
        message.senderId = sessionManager.getSelfUserId();
        message.senderName = selfNickname;
        message.senderAvatarVersion = sessionManager.getSelfAvatarVersion();
        message.content = content;
        message.msgType = "text";
        message.timestamp = System.currentTimeMillis();
        message.isSelf = true;
        message.status = online
                ? Message.STATUS_SENDING : Message.STATUS_FAILED;

        etMessage.setText("");
        clearDraftNow();
        if (online) {
            pendingPreviews.put(clientMsgId, content);
        }

        database.execute(() -> {
            long id = messageDao.insert(message);
            database.runOnMain(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (id == -1) {
                    pendingPreviews.remove(clientMsgId);
                    etMessage.setText(content);
                    Toast.makeText(this, R.string.send_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                message.id = id;
                adapter.addMessage(message);
                toggleEmpty();
                scrollToBottom();
                if (!online) {
                    showSendFailureToast();
                    return;
                }
                scheduleTimeout(message);
                sendChatFrame(message);
            });
        });
    }

    private void sendMediaFile(File file, String type) {
        sendMediaFile(file, type, "image/jpeg", "", file == null
                ? "" : file.getName());
    }

    private void sendMediaFile(File file,
                               String type,
                               String mime,
                               String content,
                               String fileName) {
        if (file == null || !file.isFile()) {
            Toast.makeText(this, R.string.chat_image_pick_failed,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        long size = file.length();
        if (size <= 0 || size > ImageUtil.MAX_CHAT_BYTES) {
            Toast.makeText(this, R.string.chat_image_too_large,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        boolean online = sessionManager.isActive(sessionId);
        boolean featureEnabled = isFeatureEnabled(type);

        String clientMsgId = "cm-" + UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 16);
        Message message = new Message();
        message.sessionId = sessionId;
        message.clientMsgId = clientMsgId;
        message.senderId = sessionManager.getSelfUserId();
        message.senderName = selfNickname;
        message.senderAvatarVersion = sessionManager.getSelfAvatarVersion();
        message.content = content == null ? "" : content;
        message.msgType = type;
        message.fileName = fileName == null ? file.getName() : fileName;
        message.fileMime = mime == null || mime.isEmpty()
                ? "image/jpeg" : mime;
        message.fileSize = size;
        message.localPath = file.getAbsolutePath();
        message.timestamp = System.currentTimeMillis();
        message.isSelf = true;
        message.status = online && featureEnabled
                ? Message.STATUS_SENDING : Message.STATUS_FAILED;
        if (online && featureEnabled) {
            pendingPreviews.put(clientMsgId, previewFor(type));
        }

        database.execute(() -> {
            long id = messageDao.insert(message);
            database.runOnMain(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (id == -1) {
                    pendingPreviews.remove(clientMsgId);
                    Toast.makeText(this, R.string.send_failed,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                message.id = id;
                adapter.addMessage(message);
                toggleEmpty();
                scrollToBottom();
                if (!online) {
                    showSendFailureToast();
                    return;
                }
                if (!featureEnabled) {
                    Toast.makeText(this, R.string.room_feature_disabled,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                scheduleTimeout(message);
                sendMediaFrame(message);
            });
        });
    }

    private boolean isFeatureEnabled(String type) {
        if ("image".equals(type)) {
            return sessionManager.isImageEnabled();
        }
        if ("voice".equals(type)) {
            return sessionManager.isVoiceEnabled();
        }
        if ("emoji".equals(type)) {
            return sessionManager.isEmojiEnabled();
        }
        return true;
    }

    private void sendMediaFrame(Message message) {
        database.execute(() -> {
            String dataUri;
            try {
                dataUri = ImageUtil.fileToDataUri(
                        message.localPath, message.fileMime);
            } catch (Exception e) {
                dataUri = "";
            }
            if (dataUri.isEmpty()) {
                database.runOnMain(() -> markFailed(message.clientMsgId, false));
                return;
            }
            try {
                JSONObject data = new JSONObject();
                data.put("content", message.content == null
                        ? "" : message.content);
                data.put("clientMsgId", message.clientMsgId);
                data.put("type", message.msgType);
                data.put("mime", message.fileMime);
                data.put("fileName", message.fileName);
                data.put("data", dataUri);
                JSONObject frame = Protocol.frame(Protocol.CHAT, data);
                frame.put("reqId", "chat-" + message.clientMsgId);
                sessionManager.sendFrame(frame, new SessionManager.SendCallback() {
                    @Override
                    public void onSuccess() {
                        // 等 message_ack。
                    }

                    @Override
                    public void onError(String error) {
                        markFailed(message.clientMsgId, false);
                    }
                });
            } catch (JSONException e) {
                database.runOnMain(() ->
                        markFailed(message.clientMsgId, false));
            }
        });
    }

    private static String previewFor(String type) {
        if ("emoji".equals(type)) {
            return "[表情]";
        }
        if ("voice".equals(type)) {
            return "[语音]";
        }
        return "[图片]";
    }

    private void sendChatFrame(Message message) {
        try {
            JSONObject data = new JSONObject();
            data.put("content", message.content);
            data.put("clientMsgId", message.clientMsgId);
            JSONObject frame = Protocol.frame(Protocol.CHAT, data);
            frame.put("reqId", "chat-" + message.clientMsgId);

            sessionManager.sendFrame(frame, new SessionManager.SendCallback() {
                @Override
                public void onSuccess() {
                    // 等 message_ack。
                }

                @Override
                public void onError(String error) {
                    markFailed(message.clientMsgId, false);
                }
            });
        } catch (JSONException e) {
            markFailed(message.clientMsgId, false);
        }
    }

    private void scheduleTimeout(Message message) {
        Runnable timeout = () -> {
            if (pendingTimeouts.remove(message.clientMsgId) != null) {
                markFailed(message.clientMsgId, true);
            }
        };
        pendingTimeouts.put(message.clientMsgId, timeout);
        handler.postDelayed(timeout, SEND_TIMEOUT_MS);
    }

    private void markFailed(String clientMsgId, boolean timeout) {
        markFailed(clientMsgId, timeout, true);
    }

    private void markFailed(String clientMsgId,
                            boolean timeout,
                            boolean showToast) {
        Runnable pending = pendingTimeouts.remove(clientMsgId);
        if (pending != null) {
            handler.removeCallbacks(pending);
        }
        pendingPreviews.remove(clientMsgId);
        adapter.updateStatus(clientMsgId, Message.STATUS_FAILED);
        database.execute(() -> messageDao.updateStatus(
                sessionId, clientMsgId, Message.STATUS_FAILED, null, 0));
        if (!showToast) {
            return;
        }
        if (timeout) {
            Toast.makeText(this, R.string.send_timeout, Toast.LENGTH_SHORT).show();
        } else {
            showSendFailureToast();
        }
    }

    private void showSendFailureToast() {
        long now = System.currentTimeMillis();
        if (now - lastSendFailureToastAt < 1000L) {
            return;
        }
        lastSendFailureToastAt = now;
        Toast.makeText(this, R.string.send_failed_connection,
                Toast.LENGTH_SHORT).show();
    }

    private void retryMessage(Message message) {
        if (!sessionManager.isActive(sessionId)) {
            showSendFailureToast();
            return;
        }
        if (message.clientMsgId == null) {
            return;
        }
        boolean media = "image".equals(message.msgType)
                || "emoji".equals(message.msgType)
                || "voice".equals(message.msgType);
        if (media && !isFeatureEnabled(message.msgType)) {
            adapter.updateStatus(message.clientMsgId, Message.STATUS_FAILED);
            Toast.makeText(this, R.string.room_feature_disabled,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (media && (TextUtils.isEmpty(message.localPath)
                || !new File(message.localPath).isFile())) {
            Toast.makeText(this, "voice".equals(message.msgType)
                            ? R.string.chat_voice_unavailable
                            : R.string.chat_image_unavailable,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        pendingPreviews.put(message.clientMsgId,
                media ? previewFor(message.msgType) : message.content);
        adapter.updateStatus(message.clientMsgId, Message.STATUS_SENDING);
        database.execute(() -> messageDao.updateStatus(
                sessionId, message.clientMsgId, Message.STATUS_SENDING, null, 0));
        scheduleTimeout(message);
        if (media) {
            sendMediaFrame(message);
        } else {
            sendChatFrame(message);
        }
    }

    private void handleAck(JSONObject data) {
        String clientMsgId = data.optString("clientMsgId", "");
        if (clientMsgId.isEmpty()) {
            return;
        }
        Runnable pending = pendingTimeouts.remove(clientMsgId);
        if (pending != null) {
            handler.removeCallbacks(pending);
        }
        String preview = pendingPreviews.remove(clientMsgId);
        String msgId = data.optString("msgId", "");
        long seq = data.optLong("seq", 0);
        long ts = data.optLong("ts", System.currentTimeMillis());
        String fileId = data.optString("fileId", "");
        String fileName = data.optString("fileName", "");
        String fileMime = data.optString("fileMime", "");
        long fileSize = data.optLong("fileSize", 0);
        boolean fileExpired = data.optBoolean("fileExpired", false);

        database.execute(() -> {
            int updated = messageDao.updateSent(
                    sessionId, clientMsgId, msgId, seq,
                    fileId, fileName, fileMime, fileSize, fileExpired);
            database.runOnMain(() -> {
                Message current = adapter.findMessage(clientMsgId);
                if (updated == 0 || current == null) {
                    loadLocalMessages(false, -1);
                } else {
                    current.msgId = msgId;
                    if (seq > 0) {
                        current.seq = seq;
                    }
                    current.status = Message.STATUS_SENT;
                    if (!TextUtils.isEmpty(fileId)) {
                        current.fileId = fileId;
                        current.fileName = fileName;
                        current.fileMime = fileMime;
                        current.fileSize = fileSize;
                        current.fileExpired = fileExpired;
                    }
                    adapter.updateMessage(current);
                }
                if (preview != null) {
                    sessionManager.updateLocalSummary(
                            sessionId, "我: " + preview, ts, seq);
                }
            });
        });
    }

    private void handleIncoming(JSONObject data) {
        String msgId = data.optString("msgId", "");
        if (msgId.isEmpty()) {
            return;
        }
        Message message = new Message();
        message.sessionId = sessionId;
        message.msgId = msgId;
        message.seq = data.optLong("seq", 0);
        message.senderId = data.optString("senderId", "");
        message.senderName = data.optString("senderName", "");
        message.senderAvatarVersion = data.optInt("senderAvatarVersion", 0);
        message.content = data.optString("content", "");
        message.msgType = data.optString("type", "text");
        message.fileId = data.optString("fileId", "");
        message.fileName = data.optString("fileName", "");
        message.fileMime = data.optString("fileMime", "");
        message.fileSize = data.optLong("fileSize", 0);
        message.fileExpired = data.optBoolean("fileExpired", false);
        message.timestamp = data.optLong("ts", System.currentTimeMillis());
        message.isSelf = false;
        message.status = Message.STATUS_SENT;

        if (CoTalkApp.getInstance().getPrefs().isMessageNotify()) {
            Vibrator vibrator;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager manager = (VibratorManager)
                        getSystemService(VIBRATOR_MANAGER_SERVICE);
                vibrator = manager == null ? null : manager.getDefaultVibrator();
            } else {
                vibrator = getSystemService(Vibrator.class);
            }
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createOneShot(
                        40, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        }

        boolean scroll = atBottom;
        database.execute(() -> {
            // SessionManager 已经负责把该消息写入本地库；这里再次 insertOrIgnore
            // 只作兜底，不能因为唯一键冲突（返回 -1）就跳过 UI 插入。
            messageDao.insertOrIgnore(message);
            sessionDao.markRead(sessionId);
            database.runOnMain(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                adapter.addMessage(message);
                toggleEmpty();
                if (scroll) {
                    scrollToBottom();
                }
            });
        });
    }

    private void handleHistory(JSONObject frame) {
        JSONObject data = frame.optJSONObject("data");
        if (data == null) {
            return;
        }
        JSONArray array = data.optJSONArray("messages");
        boolean latest = frame.optString("reqId", "").contains("latest");
        boolean hasMore = data.optBoolean("hasMore", false);
        int keepPosition = adapter.getItemCount();

        long clearSeq = CoTalkApp.getInstance().getPrefs()
                .getHistoryClearSeq(sessionId);
        database.execute(() -> {
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject object = array.optJSONObject(i);
                    if (object == null) {
                        continue;
                    }
                    String msgId = object.optString("msgId", "");
                    long seq = object.optLong("seq", 0);
                    if (msgId.isEmpty()
                            || (clearSeq >= 0 && seq <= clearSeq)) {
                        continue;
                    }
                    Message message = new Message();
                    message.sessionId = sessionId;
                    message.msgId = msgId;
                    message.seq = object.optLong("seq", 0);
                    message.senderId = object.optString("senderId", "");
                    message.senderName = object.optString("senderName", "");
                    message.senderAvatarVersion = object.optInt(
                            "senderAvatarVersion", 0);
                    message.content = object.optString("content", "");
                    message.msgType = object.optString("type", "text");
                    message.fileId = object.optString("fileId", "");
                    message.fileName = object.optString("fileName", "");
                    message.fileMime = object.optString("fileMime", "");
                    message.fileSize = object.optLong("fileSize", 0);
                    message.fileExpired = object.optBoolean("fileExpired", false);
                    message.timestamp = object.optLong("ts", System.currentTimeMillis());
                    message.isSelf = object.optBoolean("self", false);
                    message.status = Message.STATUS_SENT;
                    messageDao.insertOrIgnore(message);
                }
            }
            sessionDao.markRead(sessionId);
            database.runOnMain(() -> {
                loadingHistory = false;
                historyHasMore = hasMore;
                tvHistoryHint.setVisibility(View.GONE);
                loadLocalMessages(latest, latest ? -1 : keepPosition);
            });
        });
    }

    private void requestHistory(Long beforeSeq, String reqId, boolean latest) {
        if (!sessionManager.isActive(sessionId)) {
            return;
        }
        try {
            JSONObject data = new JSONObject();
            data.put("limit", HISTORY_LIMIT);
            if (beforeSeq != null) {
                data.put("beforeSeq", beforeSeq);
            }
            JSONObject frame = Protocol.frame(Protocol.HISTORY_REQ, data);
            frame.put("reqId", reqId);
            sessionManager.sendFrame(frame, new SessionManager.SendCallback() {
                @Override
                public void onSuccess() {
                }

                @Override
                public void onError(String error) {
                    if (!latest) {
                        loadingHistory = false;
                        tvHistoryHint.setVisibility(View.GONE);
                    }
                }
            });
        } catch (JSONException e) {
            loadingHistory = false;
            tvHistoryHint.setVisibility(View.GONE);
        }
    }

    private void loadOlder() {
        if (loadingHistory || !historyHasMore || historyCleared()
                || !sessionManager.isActive(sessionId)) {
            return;
        }
        long oldestSeq = 0;
        for (Message message : adapter.getMessages()) {
            if (message.seq > 0) {
                oldestSeq = oldestSeq == 0
                        ? message.seq
                        : Math.min(oldestSeq, message.seq);
            }
        }
        if (oldestSeq <= 0) {
            historyHasMore = false;
            return;
        }
        loadingHistory = true;
        tvHistoryHint.setText(R.string.history_loading);
        tvHistoryHint.setVisibility(View.VISIBLE);
        requestHistory(oldestSeq, "history-older", false);
    }

    private void showBanner(String text) {
        tvBanner.setText(text);
        tvBanner.setVisibility(View.VISIBLE);
    }

    private void hideBanner() {
        tvBanner.setVisibility(View.GONE);
    }

    @Override
    public void onChatFrame(JSONObject frame) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        // 只处理当前活动会话的帧；离线会话可能打开着另一个活动连接。
        if (!sessionManager.isActive(sessionId)) {
            return;
        }
        String type = frame.optString("type", "");
        if (Protocol.MESSAGE.equals(type)) {
            JSONObject data = frame.optJSONObject("data");
            if (data != null) {
                handleIncoming(data);
            }
        } else if (Protocol.MESSAGE_ACK.equals(type)) {
            JSONObject data = frame.optJSONObject("data");
            if (data != null) {
                handleAck(data);
            }
        } else if (Protocol.HISTORY_RES.equals(type)) {
            handleHistory(frame);
        } else if (Protocol.FILE_RES.equals(type)) {
            JSONObject data = frame.optJSONObject("data");
            if (data != null) {
                handleFileResponse(data);
            }
        } else if (Protocol.MESSAGE_RECALL.equals(type)) {
            JSONObject data = frame.optJSONObject("data");
            if (data != null) {
                String msgId = data.optString("msgId", "");
                String notice = data.optString("notice", "");
                if (!msgId.isEmpty() && !notice.isEmpty()) {
                    adapter.replaceWithSystem(
                            msgId,
                            notice,
                            data.optLong("seq", 0),
                            data.optLong("ts", System.currentTimeMillis()));
                    toggleEmpty();
                }
            }
        } else if (Protocol.SYSTEM.equals(type)) {
            JSONObject data = frame.optJSONObject("data");
            if (data != null) {
                Toast.makeText(this, data.optString("content", ""),
                        Toast.LENGTH_SHORT).show();
            }
        } else if (Protocol.ERROR.equals(type)) {
            JSONObject data = frame.optJSONObject("data");
            String errorMessage = data == null
                    ? "服务器错误"
                    : data.optString("message", "服务器错误");
            String reqId = frame.optString("reqId", "");
            if (reqId.startsWith("chat-")) {
                markFailed(reqId.substring("chat-".length()), false, false);
            } else {
                handleFileRequestError(frame);
            }
            Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onChatDisconnected(String reason) {
        cancelVoiceRecording();
        stopVoicePlayback();
        pendingEmojiSave = null;
        pendingImageSaveMessage = null;
        for (Runnable runnable : pendingTimeouts.values()) {
            handler.removeCallbacks(runnable);
        }
        pendingTimeouts.clear();
        pendingPreviews.clear();
        showBanner(getString(R.string.connection_banner, reason));
        btnSend.setEnabled(true);
        loadingHistory = false;
        tvHistoryHint.setVisibility(View.GONE);
        adapter.markAllSendingFailed();
        database.execute(() -> {
            int failed = messageDao.markAllSendingFailed(sessionId);
            database.runOnMain(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (failed > 0) {
                    showSendFailureToast();
                }
            });
        });
    }

    @Override
    public void onChatReconnected() {
        hideBanner();
        btnSend.setEnabled(true);
        loadingHistory = false;
        pendingFileRequests.clear();
        failedFileRequests.clear();
        pendingVoicePlayback = null;
        pendingEmojiSave = null;
        pendingImageSaveMessage = null;
        tvHistoryHint.setVisibility(View.GONE);
        loadSessionTitle();
        loadLocalMessages(true, -1);
        if (!historyCleared() && adapter.getItemCount() == 0) {
            requestHistory(null, "history-latest", true);
        }
        markRead();
    }

    private boolean historyCleared() {
        return CoTalkApp.getInstance().getPrefs()
                .getHistoryClearSeq(sessionId) >= 0;
    }
}
