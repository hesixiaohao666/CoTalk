package com.example.cotalkproject.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.example.cotalkproject.model.Profile;

import java.util.UUID;

/**
 * 本机资料 DAO：t_profile 永远只有 id=1 的一行。
 * 旧 t_local_user 中的用户名/昵称/头像/签名/clientTag 会迁移为本地资料。
 */
public class ProfileDao {

    private static final String TABLE = "t_profile";

    private final AppDatabase helper;

    public ProfileDao(AppDatabase helper) {
        this.helper = helper;
    }

    public Profile get() {
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
            Profile profile = new Profile();
            profile.username = cursor.getString(
                    cursor.getColumnIndexOrThrow("username"));
            profile.nickname = cursor.getString(
                    cursor.getColumnIndexOrThrow("nickname"));
            profile.signature = cursor.getString(
                    cursor.getColumnIndexOrThrow("signature"));
            profile.avatarPath = cursor.getString(
                    cursor.getColumnIndexOrThrow("avatar_path"));
            profile.clientTag = cursor.getString(
                    cursor.getColumnIndexOrThrow("client_tag"));
            profile.createdAt = cursor.getLong(
                    cursor.getColumnIndexOrThrow("created_at"));
            profile.updatedAt = cursor.getLong(
                    cursor.getColumnIndexOrThrow("updated_at"));
            return profile;
        }
    }

    public long insert(Profile profile) {
        if (profile == null) {
            return -1;
        }
        SQLiteDatabase db = helper.getWritableDatabase();
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("id", 1);
        values.put("username", profile.username);
        values.put("nickname", profile.nickname);
        values.put("signature", profile.signature == null
                ? "" : profile.signature);
        values.put("avatar_path", profile.avatarPath);
        values.put("client_tag", profile.clientTag == null
                || profile.clientTag.trim().isEmpty()
                ? UUID.randomUUID().toString() : profile.clientTag);
        values.put("created_at", profile.createdAt > 0
                ? profile.createdAt : now);
        values.put("updated_at", now);
        return db.insertWithOnConflict(
                TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public int updateProfile(String nickname,
                             String signature,
                             String avatarPath) {
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("nickname", nickname);
        values.put("signature", signature == null ? "" : signature);
        values.put("avatar_path", avatarPath);
        values.put("updated_at", System.currentTimeMillis());
        return db.update(TABLE, values, "id = 1", null);
    }

    /**
     * 旧版本升级：把 t_local_user 的档案字段迁移到 t_profile。
     * 迁移只执行一次，不覆盖已有 t_profile。
     */
    public void ensureMigrated() {
        if (get() != null) {
            return;
        }
        SQLiteDatabase db = helper.getWritableDatabase();
        if (!tableExists(db, "t_local_user")) {
            return;
        }
        try (Cursor cursor = db.query(
                "t_local_user",
                new String[]{"username", "nickname", "signature",
                        "avatar_path", "client_tag", "created_at"},
                "id = 1", null, null, null, null)) {
            if (!cursor.moveToFirst()) {
                return;
            }
            Profile profile = new Profile();
            profile.username = cursor.getString(0);
            profile.nickname = cursor.getString(1);
            profile.signature = cursor.getString(2);
            profile.avatarPath = cursor.getString(3);
            profile.clientTag = cursor.getString(4);
            profile.createdAt = cursor.getLong(5);
            insert(profile);
        }
    }

    private static boolean tableExists(SQLiteDatabase db, String table) {
        try (Cursor cursor = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                new String[]{table})) {
            return cursor.moveToFirst();
        }
    }
}
