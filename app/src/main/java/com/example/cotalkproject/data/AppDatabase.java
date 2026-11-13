package com.example.cotalkproject.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 客户端 SQLite：本地账号、会话、消息与成员缓存。
 */
public class AppDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME = "cotalk.db";
    private static final int DB_VERSION = 3200;

    private static volatile AppDatabase instance;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private AppDatabase(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    public static AppDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = new AppDatabase(context);
                }
            }
        }
        return instance;
    }

    public ExecutorService getIoExecutor() {
        return ioExecutor;
    }

    public void execute(@NonNull Runnable task) {
        ioExecutor.execute(task);
    }

    public void runOnMain(@NonNull Runnable task) {
        mainHandler.post(task);
    }

    @Override
    public void onConfigure(@NonNull SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(@NonNull SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS t_local_user ("
                + "id INTEGER PRIMARY KEY CHECK (id = 1), "
                + "username TEXT NOT NULL UNIQUE, "
                + "password_hash TEXT NOT NULL, "
                + "salt TEXT NOT NULL, "
                + "nickname TEXT NOT NULL, "
                + "signature TEXT, "
                + "avatar_path TEXT, "
                + "client_tag TEXT NOT NULL, "
                + "created_at INTEGER NOT NULL, "
                + "last_login INTEGER"
                + ")");

        db.execSQL("CREATE TABLE IF NOT EXISTS t_session ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "host TEXT NOT NULL, "
                + "port INTEGER NOT NULL, "
                + "room_name TEXT, "
                + "announcement TEXT, "
                + "last_seq INTEGER NOT NULL DEFAULT 0, "
                + "last_read_seq INTEGER NOT NULL DEFAULT 0, "
                + "last_message TEXT, "
                + "last_time INTEGER, "
                + "unread INTEGER NOT NULL DEFAULT 0, "
                + "last_connected INTEGER, "
                + "ai_enabled INTEGER NOT NULL DEFAULT 0, "
                + "server_version_name TEXT NOT NULL DEFAULT '', "
                + "server_version_code INTEGER NOT NULL DEFAULT 0, "
                + "room_avatar_id TEXT NOT NULL DEFAULT '', "
                + "room_avatar_version INTEGER NOT NULL DEFAULT 0, "
                + "admin_enabled INTEGER NOT NULL DEFAULT 0, "
                + "push_enabled INTEGER NOT NULL DEFAULT 1, "
                + "UNIQUE(host, port)"
                + ")");

        db.execSQL("CREATE TABLE IF NOT EXISTS t_message ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "session_id INTEGER NOT NULL, "
                + "msg_id TEXT UNIQUE, "
                + "client_msg_id TEXT, "
                + "seq INTEGER NOT NULL DEFAULT 0, "
                + "sender_id TEXT NOT NULL, "
                + "sender_name TEXT, "
                + "sender_avatar_version INTEGER NOT NULL DEFAULT 0, "
                + "content TEXT NOT NULL, "
                + "msg_type TEXT NOT NULL DEFAULT 'text', "
                + "timestamp INTEGER NOT NULL, "
                + "is_self INTEGER NOT NULL DEFAULT 0, "
                + "status INTEGER NOT NULL DEFAULT 1, "
                + "file_id TEXT, "
                + "file_name TEXT, "
                + "file_mime TEXT, "
                + "file_size INTEGER NOT NULL DEFAULT 0, "
                + "file_expired INTEGER NOT NULL DEFAULT 0, "
                + "local_path TEXT, "
                + "sender_admin INTEGER NOT NULL DEFAULT 0, "
                + "FOREIGN KEY(session_id) REFERENCES t_session(id) ON DELETE CASCADE"
                + ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_message_session_seq "
                + "ON t_message(session_id, seq)");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_message_client "
                + "ON t_message(session_id, client_msg_id)");

        db.execSQL("CREATE TABLE IF NOT EXISTS t_member ("
                + "session_id INTEGER NOT NULL, "
                + "user_id TEXT NOT NULL, "
                + "nickname TEXT NOT NULL, "
                + "avatar_version INTEGER NOT NULL DEFAULT 0, "
                + "online INTEGER NOT NULL DEFAULT 1, "
                + "is_ai INTEGER NOT NULL DEFAULT 0, "
                + "is_admin INTEGER NOT NULL DEFAULT 0, "
                + "last_seen INTEGER, "
                + "PRIMARY KEY(session_id, user_id), "
                + "FOREIGN KEY(session_id) REFERENCES t_session(id) ON DELETE CASCADE"
                + ")");
    }

    @Override
    public void onUpgrade(@NonNull SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE t_session ADD COLUMN "
                    + "ai_enabled INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE t_member ADD COLUMN "
                    + "is_ai INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE t_session ADD COLUMN "
                    + "server_version_name TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE t_session ADD COLUMN "
                    + "server_version_code INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE t_message ADD COLUMN file_id TEXT");
            db.execSQL("ALTER TABLE t_message ADD COLUMN file_name TEXT");
            db.execSQL("ALTER TABLE t_message ADD COLUMN file_mime TEXT");
            db.execSQL("ALTER TABLE t_message ADD COLUMN "
                    + "file_size INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE t_message ADD COLUMN "
                    + "file_expired INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE t_message ADD COLUMN local_path TEXT");
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE t_session ADD COLUMN "
                    + "room_avatar_id TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE t_session ADD COLUMN "
                    + "room_avatar_version INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 3200) {
            db.execSQL("ALTER TABLE t_session ADD COLUMN "
                    + "admin_enabled INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE t_session ADD COLUMN "
                    + "push_enabled INTEGER NOT NULL DEFAULT 1");
            db.execSQL("ALTER TABLE t_member ADD COLUMN "
                    + "is_admin INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE t_message ADD COLUMN "
                    + "sender_admin INTEGER NOT NULL DEFAULT 0");
        }
    }

    /**
     * 重置本地账号：清空本机四张表，不改服务器数据。
     */
    public void clearAllLocalData() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("t_message", null, null);
            db.delete("t_member", null, null);
            db.delete("t_session", null, null);
            db.delete("t_local_user", null, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
}
