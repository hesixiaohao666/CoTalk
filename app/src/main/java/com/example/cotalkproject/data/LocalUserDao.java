package com.example.cotalkproject.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.example.cotalkproject.model.LocalUser;

/**
 * 单本地账号 DAO：表中永远只有 id=1 的一行。
 */
public class LocalUserDao {

    private static final String TABLE = "t_local_user";

    private final AppDatabase helper;

    public LocalUserDao(AppDatabase helper) {
        this.helper = helper;
    }

    public LocalUser get() {
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor cursor = db.query(
                TABLE,
                null,
                "id = 1",
                null,
                null,
                null,
                null
        )) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            LocalUser user = new LocalUser();
            user.id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
            user.username = cursor.getString(cursor.getColumnIndexOrThrow("username"));
            user.passwordHash = cursor.getString(cursor.getColumnIndexOrThrow("password_hash"));
            user.salt = cursor.getString(cursor.getColumnIndexOrThrow("salt"));
            user.nickname = cursor.getString(cursor.getColumnIndexOrThrow("nickname"));
            user.signature = cursor.getString(cursor.getColumnIndexOrThrow("signature"));
            user.avatarPath = cursor.getString(cursor.getColumnIndexOrThrow("avatar_path"));
            user.clientTag = cursor.getString(cursor.getColumnIndexOrThrow("client_tag"));
            user.createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"));
            user.lastLogin = cursor.getLong(cursor.getColumnIndexOrThrow("last_login"));
            return user;
        }
    }

    public long insert(LocalUser user) {
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("id", 1);
        values.put("username", user.username);
        values.put("password_hash", user.passwordHash);
        values.put("salt", user.salt);
        values.put("nickname", user.nickname);
        values.put("signature", user.signature);
        values.put("avatar_path", user.avatarPath);
        values.put("client_tag", user.clientTag);
        values.put("created_at", user.createdAt);
        values.put("last_login", user.lastLogin);
        return db.insertWithOnConflict(
                TABLE,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
        );
    }

    public int updateLastLogin(long userId, long lastLogin) {
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("last_login", lastLogin);
        return db.update(TABLE, values, "id = ?", new String[]{String.valueOf(userId)});
    }

    public int updateProfile(String nickname, String signature, String avatarPath) {
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("nickname", nickname);
        values.put("signature", signature);
        values.put("avatar_path", avatarPath);
        return db.update(TABLE, values, "id = 1", null);
    }

    public int deleteAll() {
        SQLiteDatabase db = helper.getWritableDatabase();
        return db.delete(TABLE, null, null);
    }
}
