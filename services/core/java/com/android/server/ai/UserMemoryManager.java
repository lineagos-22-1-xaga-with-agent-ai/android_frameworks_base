package com.android.server.ai;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import com.android.server.LocalServices;
import com.android.server.UiThread;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

public class UserMemoryManager {
    private static final String TAG = "UserMemoryManager";
    private static final boolean DEBUG = true;

    private static final String DATABASE_NAME = "agent_memory.db";
    private static final int DATABASE_VERSION = 1;

    private static final String TABLE_MEMORIES = "memories";
    private static final String TABLE_PREFERENCES = "preferences";
    private static final String TABLE_CONVERSATIONS = "conversations";
    private static final String TABLE_SCREEN_HISTORY = "screen_history";
    private static final String TABLE_ACTION_HISTORY = "action_history";

    private final Context mContext;
    private final Executor mExecutor;
    private final DatabaseHelper mDbHelper;

    private static final int MAX_SCREEN_HISTORY = 10;
    private static final int MAX_ACTION_HISTORY = 50;
    private static final int MAX_CONVERSATION_TURNS = 20;

    public UserMemoryManager(Context context) {
        mContext = context;
        mExecutor = UiThread.getExecutor();
        mDbHelper = new DatabaseHelper(context);
        
        if (DEBUG) {
            Log.d(TAG, "UserMemoryManager initialized");
        }
    }

    public void remember(String key, String value, String category) {
        mExecutor.execute(() -> {
            SQLiteDatabase db = mDbHelper.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("key", key);
            values.put("value", value);
            values.put("category", category);
            values.put("timestamp", System.currentTimeMillis());
            
            db.delete(TABLE_MEMORIES, "key=? AND category=?", new String[]{key, category});
            db.insert(TABLE_MEMORIES, null, values);
            
            if (DEBUG) {
                Log.d(TAG, "remember: key=" + key + ", category=" + category);
            }
        });
    }

    public void remember(String key, String value) {
        remember(key, value, "general");
    }

    public interface RecallCallback {
        void onResult(String value);
        void onNotFound();
    }

    public void recall(String key, RecallCallback callback) {
        recall(key, "general", callback);
    }

    public void recall(String key, String category, RecallCallback callback) {
        mExecutor.execute(() -> {
            SQLiteDatabase db = mDbHelper.getReadableDatabase();
            Cursor cursor = db.query(TABLE_MEMORIES, 
                    new String[]{"value"}, 
                    "key=? AND category=?", 
                    new String[]{key, category}, 
                    null, null, "timestamp DESC", "1");
            
            if (cursor != null && cursor.moveToFirst()) {
                String value = cursor.getString(0);
                cursor.close();
                if (DEBUG) {
                    Log.d(TAG, "recall: key=" + key + ", found value=" + value);
                }
                callback.onResult(value);
            } else {
                if (cursor != null) {
                    cursor.close();
                }
                if (DEBUG) {
                    Log.d(TAG, "recall: key=" + key + ", not found");
                }
                callback.onNotFound();
            }
        });
    }

    public interface AllMemoriesCallback {
        void onResult(Map<String, String> memories);
    }

    public void getAllMemories(String category, AllMemoriesCallback callback) {
        mExecutor.execute(() -> {
            SQLiteDatabase db = mDbHelper.getReadableDatabase();
            Cursor cursor = db.query(TABLE_MEMORIES, 
                    new String[]{"key", "value", "timestamp"}, 
                    "category=?", 
                    new String[]{category}, 
                    null, null, "timestamp DESC");
            
            Map<String, String> memories = new HashMap<>();
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    memories.put(cursor.getString(0), cursor.getString(1));
                }
                cursor.close();
            }
            
            if (DEBUG) {
                Log.d(TAG, "getAllMemories: category=" + category + ", count=" + memories.size());
            }
            callback.onResult(memories);
        });
    }

    public void setPreference(String category, String key, String value) {
        mExecutor.execute(() -> {
            SQLiteDatabase db = mDbHelper.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("category", category);
            values.put("pref_key", key);
            values.put("pref_value", value);
            values.put("updated_at", System.currentTimeMillis());
            
            db.delete(TABLE_PREFERENCES, "category=? AND pref_key=?", 
                    new String[]{category, key});
            db.insert(TABLE_PREFERENCES, null, values);
            
            if (DEBUG) {
                Log.d(TAG, "setPreference: category=" + category + ", key=" + key + ", value=" + value);
            }
        });
    }

    public void getPreference(String category, String key, RecallCallback callback) {
        mExecutor.execute(() -> {
            SQLiteDatabase db = mDbHelper.getReadableDatabase();
            Cursor cursor = db.query(TABLE_PREFERENCES, 
                    new String[]{"pref_value"}, 
                    "category=? AND pref_key=?", 
                    new String[]{category, key}, 
                    null, null, "updated_at DESC", "1");
            
            if (cursor != null && cursor.moveToFirst()) {
                String value = cursor.getString(0);
                cursor.close();
                callback.onResult(value);
            } else {
                if (cursor != null) {
                    cursor.close();
                }
                callback.onNotFound();
            }
        });
    }

    public void addScreenHistory(String description, byte[] screenshotHash) {
        mExecutor.execute(() -> {
            SQLiteDatabase db = mDbHelper.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("description", description);
            values.put("screenshot_hash", screenshotHash != null ? 
                    new String(screenshotHash) : null);
            values.put("timestamp", System.currentTimeMillis());
            
            db.insert(TABLE_SCREEN_HISTORY, null, values);
            
            Cursor cursor = db.query(TABLE_SCREEN_HISTORY, 
                    new String[]{"count(*) as cnt"}, null, null, null, null, null);
            int count = 0;
            if (cursor != null && cursor.moveToFirst()) {
                count = cursor.getInt(0);
                cursor.close();
            }
            
            if (count > MAX_SCREEN_HISTORY) {
                db.execSQL("DELETE FROM " + TABLE_SCREEN_HISTORY + 
                        " WHERE rowid IN (SELECT rowid FROM " + TABLE_SCREEN_HISTORY + 
                        " ORDER BY timestamp ASC LIMIT " + (count - MAX_SCREEN_HISTORY) + ")");
            }
            
            if (DEBUG) {
                Log.d(TAG, "addScreenHistory: description=" + description);
            }
        });
    }

    public void addActionHistory(String action, String target, String result) {
        mExecutor.execute(() -> {
            SQLiteDatabase db = mDbHelper.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("action", action);
            values.put("target", target);
            values.put("result", result);
            values.put("timestamp", System.currentTimeMillis());
            
            db.insert(TABLE_ACTION_HISTORY, null, values);
            
            Cursor cursor = db.query(TABLE_ACTION_HISTORY, 
                    new String[]{"count(*) as cnt"}, null, null, null, null, null);
            int count = 0;
            if (cursor != null && cursor.moveToFirst()) {
                count = cursor.getInt(0);
                cursor.close();
            }
            
            if (count > MAX_ACTION_HISTORY) {
                db.execSQL("DELETE FROM " + TABLE_ACTION_HISTORY + 
                        " WHERE rowid IN (SELECT rowid FROM " + TABLE_ACTION_HISTORY + 
                        " ORDER BY timestamp ASC LIMIT " + (count - MAX_ACTION_HISTORY) + ")");
            }
            
            if (DEBUG) {
                Log.d(TAG, "addActionHistory: action=" + action + ", target=" + target);
            }
        });
    }

    public interface ScreenHistoryCallback {
        void onResult(java.util.List<ScreenSnapshot> history);
    }

    public void getScreenHistory(ScreenHistoryCallback callback) {
        mExecutor.execute(() -> {
            SQLiteDatabase db = mDbHelper.getReadableDatabase();
            Cursor cursor = db.query(TABLE_SCREEN_HISTORY, 
                    null, null, null, null, null, "timestamp DESC", 
                    String.valueOf(MAX_SCREEN_HISTORY));
            
            java.util.List<ScreenSnapshot> history = new java.util.ArrayList<>();
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    ScreenSnapshot snapshot = new ScreenSnapshot();
                    snapshot.description = cursor.getString(cursor.getColumnIndexOrThrow("description"));
                    snapshot.screenshotHash = cursor.getString(cursor.getColumnIndexOrThrow("screenshot_hash"));
                    snapshot.timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp"));
                    history.add(snapshot);
                }
                cursor.close();
            }
            
            callback.onResult(history);
        });
    }

    public interface ActionHistoryCallback {
        void onResult(java.util.List<ActionRecord> history);
    }

    public void getActionHistory(ActionHistoryCallback callback) {
        mExecutor.execute(() -> {
            SQLiteDatabase db = mDbHelper.getReadableDatabase();
            Cursor cursor = db.query(TABLE_ACTION_HISTORY, 
                    null, null, null, null, null, "timestamp DESC", 
                    String.valueOf(MAX_ACTION_HISTORY));
            
            java.util.List<ActionRecord> history = new java.util.ArrayList<>();
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    ActionRecord record = new ActionRecord();
                    record.action = cursor.getString(cursor.getColumnIndexOrThrow("action"));
                    record.target = cursor.getString(cursor.getColumnIndexOrThrow("target"));
                    record.result = cursor.getString(cursor.getColumnIndexOrThrow("result"));
                    record.timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp"));
                    history.add(record);
                }
                cursor.close();
            }
            
            callback.onResult(history);
        });
    }

    public void addConversationTurn(String role, String content) {
        mExecutor.execute(() -> {
            SQLiteDatabase db = mDbHelper.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("role", role);
            values.put("content", content);
            values.put("timestamp", System.currentTimeMillis());
            
            db.insert(TABLE_CONVERSATIONS, null, values);
            
            Cursor cursor = db.query(TABLE_CONVERSATIONS, 
                    new String[]{"count(*) as cnt"}, null, null, null, null, null);
            int count = 0;
            if (cursor != null && cursor.moveToFirst()) {
                count = cursor.getInt(0);
                cursor.close();
            }
            
            if (count > MAX_CONVERSATION_TURNS) {
                db.execSQL("DELETE FROM " + TABLE_CONVERSATIONS + 
                        " WHERE rowid IN (SELECT rowid FROM " + TABLE_CONVERSATIONS + 
                        " ORDER BY timestamp ASC LIMIT " + (count - MAX_CONVERSATION_TURNS) + ")");
            }
            
            if (DEBUG) {
                Log.d(TAG, "addConversationTurn: role=" + role);
            }
        });
    }

    public interface ConversationHistoryCallback {
        void onResult(java.util.List<ConversationTurn> history);
    }

    public void getConversationHistory(ConversationHistoryCallback callback) {
        mExecutor.execute(() -> {
            SQLiteDatabase db = mDbHelper.getReadableDatabase();
            Cursor cursor = db.query(TABLE_CONVERSATIONS, 
                    null, null, null, null, null, "timestamp ASC");
            
            java.util.List<ConversationTurn> history = new java.util.ArrayList<>();
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    ConversationTurn turn = new ConversationTurn();
                    turn.role = cursor.getString(cursor.getColumnIndexOrThrow("role"));
                    turn.content = cursor.getString(cursor.getColumnIndexOrThrow("content"));
                    turn.timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp"));
                    history.add(turn);
                }
                cursor.close();
            }
            
            callback.onResult(history);
        });
    }

    public void clearMemory() {
        mExecutor.execute(() -> {
            SQLiteDatabase db = mDbHelper.getWritableDatabase();
            db.delete(TABLE_MEMORIES, null, null);
            db.delete(TABLE_CONVERSATIONS, null, null);
            db.delete(TABLE_SCREEN_HISTORY, null, null);
            db.delete(TABLE_ACTION_HISTORY, null, null);
            
            if (DEBUG) {
                Log.d(TAG, "clearMemory: all memories cleared");
            }
        });
    }

    public void clearPreferences() {
        mExecutor.execute(() -> {
            SQLiteDatabase db = mDbHelper.getWritableDatabase();
            db.delete(TABLE_PREFERENCES, null, null);
            
            if (DEBUG) {
                Log.d(TAG, "clearPreferences: all preferences cleared");
            }
        });
    }

    public static class ScreenSnapshot {
        public String description;
        public String screenshotHash;
        public long timestamp;
    }

    public static class ActionRecord {
        public String action;
        public String target;
        public String result;
        public long timestamp;
    }

    public static class ConversationTurn {
        public String role;
        public String content;
        public long timestamp;
    }

    private static class DatabaseHelper extends SQLiteOpenHelper {
        DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE_MEMORIES + " (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "key TEXT NOT NULL," +
                    "value TEXT NOT NULL," +
                    "category TEXT NOT NULL," +
                    "timestamp INTEGER NOT NULL," +
                    "UNIQUE(key, category))");
            
            db.execSQL("CREATE TABLE " + TABLE_PREFERENCES + " (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "category TEXT NOT NULL," +
                    "pref_key TEXT NOT NULL," +
                    "pref_value TEXT NOT NULL," +
                    "updated_at INTEGER NOT NULL," +
                    "UNIQUE(category, pref_key))");
            
            db.execSQL("CREATE TABLE " + TABLE_CONVERSATIONS + " (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "role TEXT NOT NULL," +
                    "content TEXT NOT NULL," +
                    "timestamp INTEGER NOT NULL)");
            
            db.execSQL("CREATE TABLE " + TABLE_SCREEN_HISTORY + " (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "description TEXT," +
                    "screenshot_hash TEXT," +
                    "timestamp INTEGER NOT NULL)");
            
            db.execSQL("CREATE TABLE " + TABLE_ACTION_HISTORY + " (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "action TEXT NOT NULL," +
                    "target TEXT," +
                    "result TEXT," +
                    "timestamp INTEGER NOT NULL)");
            
            if (DEBUG) {
                Log.d(TAG, "Database tables created");
            }
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (DEBUG) {
                Log.w(TAG, "Upgrading database from version " + oldVersion + " to " + newVersion);
            }
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_MEMORIES);
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_PREFERENCES);
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_CONVERSATIONS);
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_SCREEN_HISTORY);
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_ACTION_HISTORY);
            onCreate(db);
        }
    }
}
