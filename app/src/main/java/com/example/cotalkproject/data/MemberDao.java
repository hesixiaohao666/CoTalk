package com.example.cotalkproject.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.example.cotalkproject.model.Member;

import java.util.ArrayList;
import java.util.List;

/**
 * 房间成员缓存 DAO。UI 只展示昵称；userId 仅内部使用。
 */
public class MemberDao {

    private static final String TABLE = "t_member";

    private final AppDatabase helper;

    public MemberDao(AppDatabase helper) {
        this.helper = helper;
    }

    public void replaceAll(long sessionId, List<Member> members) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(TABLE, "session_id = ?", new String[]{String.valueOf(sessionId)});
            long now = System.currentTimeMillis();
            for (Member member : members) {
                ContentValues values = new ContentValues();
                values.put("session_id", sessionId);
                values.put("user_id", member.userId);
                values.put("nickname", member.nickname);
                values.put("avatar_version", member.avatarVersion);
                values.put("online", member.online ? 1 : 0);
                values.put("is_ai", member.ai ? 1 : 0);
                values.put("is_admin", member.admin ? 1 : 0);
                values.put("last_seen", now);
                db.insert(TABLE, null, values);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public List<Member> queryBySession(long sessionId) {
        SQLiteDatabase db = helper.getReadableDatabase();
        List<Member> members = new ArrayList<>();
        try (Cursor cursor = db.query(
                TABLE, null,
                "session_id = ?",
                new String[]{String.valueOf(sessionId)},
                null, null,
                "online DESC, last_seen DESC, nickname ASC")) {
            while (cursor.moveToNext()) {
                Member member = new Member();
                member.sessionId = cursor.getLong(cursor.getColumnIndexOrThrow("session_id"));
                member.userId = cursor.getString(cursor.getColumnIndexOrThrow("user_id"));
                member.nickname = cursor.getString(cursor.getColumnIndexOrThrow("nickname"));
                member.avatarVersion = cursor.getInt(cursor.getColumnIndexOrThrow("avatar_version"));
                member.online = cursor.getInt(cursor.getColumnIndexOrThrow("online")) == 1;
                member.ai = cursor.getInt(cursor.getColumnIndexOrThrow("is_ai")) == 1;
                member.admin = cursor.getInt(
                        cursor.getColumnIndexOrThrow("is_admin")) == 1;
                member.lastSeen = cursor.getLong(cursor.getColumnIndexOrThrow("last_seen"));
                members.add(member);
            }
        }
        return members;
    }

    public void markAllOffline(long sessionId) {
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("online", 0);
        db.update(TABLE, values,
                "session_id = ?", new String[]{String.valueOf(sessionId)});
    }

    public void deleteBySession(long sessionId) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.delete(TABLE, "session_id = ?", new String[]{String.valueOf(sessionId)});
    }
}
