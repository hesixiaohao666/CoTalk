package com.example.cotalkproject.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.example.cotalkproject.model.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地消息 DAO：乐观发送、ack 更新、历史合并。
 */
public class MessageDao {

    private static final String TABLE = "t_message";

    private final AppDatabase helper;

    public MessageDao(AppDatabase helper) {
        this.helper = helper;
    }

    public long insert(Message message) {
        SQLiteDatabase db = helper.getWritableDatabase();
        return db.insert(TABLE, null, toValues(message));
    }

    public long insertOrIgnore(Message message) {
        SQLiteDatabase db = helper.getWritableDatabase();
        return db.insertWithOnConflict(
                TABLE, null, toValues(message),
                SQLiteDatabase.CONFLICT_IGNORE);
    }

    public List<Message> queryAll(long sessionId) {
        SQLiteDatabase db = helper.getReadableDatabase();
        List<Message> messages = new ArrayList<>();
        try (Cursor cursor = db.query(
                TABLE,
                null,
                "session_id = ?",
                new String[]{String.valueOf(sessionId)},
                null,
                null,
                // 以时间为主排序；服务器重启导致 seq 不连续时也不会打乱时间顺序。
                "timestamp ASC, CASE WHEN seq = 0 THEN 1 ELSE 0 END ASC, "
                        + "seq ASC, _id ASC")) {
            while (cursor.moveToNext()) {
                messages.add(map(cursor));
            }
        }
        return messages;
    }

    public int updateStatus(long sessionId,
                            String clientMsgId,
                            int status,
                            String msgId,
                            long seq) {
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("status", status);
        if (msgId != null && !msgId.isEmpty()) {
            values.put("msg_id", msgId);
        }
        if (seq > 0) {
            values.put("seq", seq);
        }
        try {
            return db.update(
                    TABLE,
                    values,
                    "session_id = ? AND client_msg_id = ?",
                    new String[]{String.valueOf(sessionId), clientMsgId}
            );
        } catch (android.database.sqlite.SQLiteConstraintException e) {
            // 历史同步先插入了同 msgId 的记录：删掉乐观副本，保留历史记录。
            db.delete(TABLE,
                    "session_id = ? AND client_msg_id = ?",
                    new String[]{String.valueOf(sessionId), clientMsgId});
            return 0;
        }
    }

    public int updateSent(long sessionId,
                          String clientMsgId,
                          String msgId,
                          long seq,
                          String fileId,
                          String fileName,
                          String fileMime,
                          long fileSize,
                          boolean fileExpired) {
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("status", Message.STATUS_SENT);
        if (msgId != null && !msgId.isEmpty()) {
            values.put("msg_id", msgId);
        }
        if (seq > 0) {
            values.put("seq", seq);
        }
        if (fileId != null && !fileId.isEmpty()) {
            values.put("file_id", fileId);
            values.put("file_name", fileName);
            values.put("file_mime", fileMime);
            values.put("file_size", fileSize);
            values.put("file_expired", fileExpired ? 1 : 0);
        }
        try {
            return db.update(
                    TABLE,
                    values,
                    "session_id = ? AND client_msg_id = ?",
                    new String[]{String.valueOf(sessionId), clientMsgId}
            );
        } catch (android.database.sqlite.SQLiteConstraintException e) {
            db.delete(TABLE,
                    "session_id = ? AND client_msg_id = ?",
                    new String[]{String.valueOf(sessionId), clientMsgId});
            return 0;
        }
    }

    public int replaceWithSystem(long sessionId,
                                 String msgId,
                                 String notice,
                                 long seq,
                                 long timestamp) {
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("content", notice);
        values.put("msg_type", "system");
        values.put("file_id", (String) null);
        values.put("file_name", (String) null);
        values.put("file_mime", (String) null);
        values.put("file_size", 0);
        values.put("file_expired", 0);
        values.put("local_path", (String) null);
        values.put("timestamp", timestamp);
        values.put("is_self", 0);
        values.put("status", Message.STATUS_SENT);
        if (seq > 0) {
            values.put("seq", seq);
        }
        return db.update(
                TABLE,
                values,
                "session_id = ? AND msg_id = ?",
                new String[]{String.valueOf(sessionId), msgId}
        );
    }

    public int updateLocalPathByFileId(long sessionId,
                                       String fileId,
                                       String localPath) {
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("local_path", localPath);
        return db.update(
                TABLE,
                values,
                "session_id = ? AND file_id = ?",
                new String[]{String.valueOf(sessionId), fileId}
        );
    }

    public int markFileExpiredByFileId(long sessionId, String fileId) {
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("file_expired", 1);
        return db.update(
                TABLE,
                values,
                "session_id = ? AND file_id = ?",
                new String[]{String.valueOf(sessionId), fileId}
        );
    }

    public void clearSession(long sessionId) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.delete(TABLE, "session_id = ?", new String[]{String.valueOf(sessionId)});
    }

    public void deleteAll() {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.delete(TABLE, null, null);
    }

    private ContentValues toValues(Message message) {
        ContentValues values = new ContentValues();
        values.put("session_id", message.sessionId);
        values.put("msg_id", message.msgId);
        values.put("client_msg_id", message.clientMsgId);
        values.put("seq", message.seq);
        values.put("sender_id", message.senderId);
        values.put("sender_name", message.senderName);
        values.put("sender_avatar_version", message.senderAvatarVersion);
        values.put("content", message.content);
        values.put("msg_type", message.msgType);
        values.put("file_id", message.fileId);
        values.put("file_name", message.fileName);
        values.put("file_mime", message.fileMime);
        values.put("file_size", message.fileSize);
        values.put("file_expired", message.fileExpired ? 1 : 0);
        values.put("local_path", message.localPath);
        values.put("timestamp", message.timestamp);
        values.put("is_self", message.isSelf ? 1 : 0);
        values.put("status", message.status);
        return values;
    }

    private Message map(Cursor cursor) {
        Message message = new Message();
        message.id = cursor.getLong(cursor.getColumnIndexOrThrow("_id"));
        message.sessionId = cursor.getLong(cursor.getColumnIndexOrThrow("session_id"));
        message.msgId = cursor.getString(cursor.getColumnIndexOrThrow("msg_id"));
        message.clientMsgId = cursor.getString(cursor.getColumnIndexOrThrow("client_msg_id"));
        message.seq = cursor.getLong(cursor.getColumnIndexOrThrow("seq"));
        message.senderId = cursor.getString(cursor.getColumnIndexOrThrow("sender_id"));
        message.senderName = cursor.getString(cursor.getColumnIndexOrThrow("sender_name"));
        message.senderAvatarVersion = cursor.getInt(
                cursor.getColumnIndexOrThrow("sender_avatar_version"));
        message.content = cursor.getString(cursor.getColumnIndexOrThrow("content"));
        message.msgType = cursor.getString(cursor.getColumnIndexOrThrow("msg_type"));
        message.fileId = cursor.getString(cursor.getColumnIndexOrThrow("file_id"));
        message.fileName = cursor.getString(cursor.getColumnIndexOrThrow("file_name"));
        message.fileMime = cursor.getString(cursor.getColumnIndexOrThrow("file_mime"));
        message.fileSize = cursor.getLong(cursor.getColumnIndexOrThrow("file_size"));
        message.fileExpired = cursor.getInt(
                cursor.getColumnIndexOrThrow("file_expired")) == 1;
        message.localPath = cursor.getString(cursor.getColumnIndexOrThrow("local_path"));
        message.timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp"));
        message.isSelf = cursor.getInt(cursor.getColumnIndexOrThrow("is_self")) == 1;
        message.status = cursor.getInt(cursor.getColumnIndexOrThrow("status"));
        return message;
    }
}
