package com.example.cotalkproject.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.example.cotalkproject.model.Session;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务器房间会话 DAO。
 */
public class SessionDao {

    private static final String TABLE = "t_session";

    private final AppDatabase helper;

    public SessionDao(AppDatabase helper) {
        this.helper = helper;
    }

    public Session upsert(String host,
                          int port,
                          String roomName,
                          String announcement,
                          long lastSeq,
                          String lastMessage,
                          long lastTime,
                          long lastConnected,
                          boolean aiEnabled) {
        SQLiteDatabase db = helper.getWritableDatabase();
        Session existing = getByHostPort(host, port);

        ContentValues values = new ContentValues();
        values.put("host", host);
        values.put("port", port);
        values.put("room_name", roomName);
        values.put("announcement", announcement);
        values.put("last_connected", lastConnected);
        values.put("ai_enabled", aiEnabled ? 1 : 0);

        if (existing == null) {
            values.put("last_seq", lastSeq);
            values.put("last_read_seq", 0);
            values.put("last_message", lastMessage);
            values.put("last_time", lastTime);
            values.put("unread", 0);
            long id = db.insert(TABLE, null, values);
            return getById(id);
        }

        values.put("last_seq", Math.max(existing.lastSeq, lastSeq));
        if (lastMessage != null) {
            values.put("last_message", lastMessage);
            values.put("last_time", lastTime);
        }
        db.update(TABLE, values, "id = ?", new String[]{String.valueOf(existing.id)});
        return getById(existing.id);
    }

    public Session getByHostPort(String host, int port) {
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor cursor = db.query(
                TABLE, null,
                "host = ? AND port = ?",
                new String[]{host, String.valueOf(port)},
                null, null, null)) {
            return cursor.moveToFirst() ? map(cursor) : null;
        }
    }

    public Session getById(long id) {
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor cursor = db.query(
                TABLE, null, "id = ?",
                new String[]{String.valueOf(id)},
                null, null, null)) {
            return cursor.moveToFirst() ? map(cursor) : null;
        }
    }

    public List<Session> queryAll() {
        SQLiteDatabase db = helper.getReadableDatabase();
        List<Session> sessions = new ArrayList<>();
        try (Cursor cursor = db.query(
                TABLE, null, null, null, null, null,
                "CASE WHEN last_time IS NULL THEN 0 ELSE last_time END DESC, "
                        + "last_connected DESC, id DESC")) {
            while (cursor.moveToNext()) {
                sessions.add(map(cursor));
            }
        }
        return sessions;
    }

    public void updateLastMessage(long sessionId,
                                  String message,
                                  long time,
                                  long seq,
                                  boolean incrementUnread) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.execSQL(
                "UPDATE t_session SET last_message = ?, last_time = ?, "
                        + "last_seq = MAX(last_seq, ?), unread = unread + ? "
                        + "WHERE id = ?",
                new Object[]{message, time, seq, incrementUnread ? 1 : 0, sessionId}
        );
    }

    public void markRead(long sessionId) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.execSQL(
                "UPDATE t_session SET last_read_seq = last_seq, unread = 0 WHERE id = ?",
                new Object[]{sessionId}
        );
    }

    public void clearSummaries() {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.execSQL("UPDATE t_session SET last_message = NULL, last_time = 0, "
                + "last_read_seq = 0, unread = 0");
    }

    public void delete(long sessionId) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.delete(TABLE, "id = ?", new String[]{String.valueOf(sessionId)});
    }

    public void deleteAll() {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.delete(TABLE, null, null);
    }

    private Session map(Cursor cursor) {
        Session session = new Session();
        session.id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
        session.host = cursor.getString(cursor.getColumnIndexOrThrow("host"));
        session.port = cursor.getInt(cursor.getColumnIndexOrThrow("port"));
        session.roomName = cursor.getString(cursor.getColumnIndexOrThrow("room_name"));
        session.announcement = cursor.getString(cursor.getColumnIndexOrThrow("announcement"));
        session.lastSeq = cursor.getLong(cursor.getColumnIndexOrThrow("last_seq"));
        session.lastReadSeq = cursor.getLong(cursor.getColumnIndexOrThrow("last_read_seq"));
        session.lastMessage = cursor.getString(cursor.getColumnIndexOrThrow("last_message"));
        session.lastTime = cursor.getLong(cursor.getColumnIndexOrThrow("last_time"));
        session.unread = cursor.getInt(cursor.getColumnIndexOrThrow("unread"));
        session.lastConnected = cursor.getLong(cursor.getColumnIndexOrThrow("last_connected"));
        session.aiEnabled = cursor.getInt(cursor.getColumnIndexOrThrow("ai_enabled")) == 1;
        return session;
    }
}
