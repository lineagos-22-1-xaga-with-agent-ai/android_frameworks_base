package com.android.server.ai;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Slog;

import com.android.server.LocalServices;
import com.android.server.UiThread;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 用户记忆管理器
 *
 * 负责管理AI Agent的用户记忆数据，包括：
 * - 普通记忆存储（key-value形式，按类别组织）
 * - 用户偏好设置
 * - 屏幕快照历史
 * - 操作历史记录
 * - 对话历史记录
 *
 * 所有数据存储在本地SQLite数据库中，通过异步 executor 执行数据库操作。
 *
 * @see SQLiteOpenHelper
 */
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

    /** 屏幕历史最大保存条数 */
    private static final int MAX_SCREEN_HISTORY = 10;
    /** 操作历史最大保存条数 */
    private static final int MAX_ACTION_HISTORY = 50;
    /** 对话历史最大保存轮数 */
    private static final int MAX_CONVERSATION_TURNS = 20;

    /**
     * 构造函数
     *
     * 初始化用户记忆管理器，创建数据库帮助类，并在DEBUG模式下打印初始化日志。
     *
     * @param context 应用上下文，用于创建数据库
     */
    public UserMemoryManager(Context context) {
        mContext = context;
        mExecutor = command -> UiThread.getHandler().post(command);
        mDbHelper = new DatabaseHelper(context);

        if (DEBUG) {
            Slog.d(TAG, "UserMemoryManager initialized");
        }
    }

    /**
     * 存储记忆（带类别）
     *
     * 将指定的key-value对存储到指定类别的记忆中。如果已存在相同key和类别的记忆，
     * 会先删除旧记录再插入新记录，确保数据最新。
     *
     * @param key 记忆的键名
     * @param value 记忆的值
     * @param category 记忆的类别，用于分类管理记忆
     */
    public void remember(String key, String value, String category) {
        mExecutor.execute(() -> {
            SQLiteDatabase db = mDbHelper.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("key", key);
            values.put("value", value);
            values.put("category", category);
            values.put("timestamp", System.currentTimeMillis());

            // 先删除旧记录，再插入新记录
            db.delete(TABLE_MEMORIES, "key=? AND category=?", new String[]{key, category});
            db.insert(TABLE_MEMORIES, null, values);

            if (DEBUG) {
                Slog.d(TAG, "remember: key=" + key + ", category=" + category);
            }
        });
    }

    /**
     * 存储记忆（默认类别）
     *
     * 使用默认类别"general"存储记忆。
     *
     * @param key 记忆的键名
     * @param value 记忆的值
     * @see #remember(String, String, String)
     */
    public void remember(String key, String value) {
        remember(key, value, "general");
    }

    /**
     * 记忆回调接口
     *
     * 用于异步获取记忆查询结果。
     */
    public interface RecallCallback {
        /**
         * 找到记忆时的回调
         * @param value 记忆的值
         */
        void onResult(String value);
        /**
         * 未找到记忆时的回调
         */
        void onNotFound();
    }

    /**
     * 检索记忆（默认类别）
     *
     * 根据键名检索默认类别下的记忆值。
     *
     * @param key 记忆的键名
     * @param callback 查询结果的回调接口
     * @see #recall(String, String, RecallCallback)
     */
    public void recall(String key, RecallCallback callback) {
        recall(key, "general", callback);
    }

    /**
     * 检索记忆（带类别）
     *
     * 根据键名和类别检索记忆值。如果找到则调用onResult，否则调用onNotFound。
     * 查询结果按时间戳倒序排列，返回最新的记录。
     *
     * @param key 记忆的键名
     * @param category 记忆的类别
     * @param callback 查询结果的回调接口
     */
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
                    Slog.d(TAG, "recall: key=" + key + ", found value=" + value);
                }
                callback.onResult(value);
            } else {
                if (cursor != null) {
                    cursor.close();
                }
                if (DEBUG) {
                    Slog.d(TAG, "recall: key=" + key + ", not found");
                }
                callback.onNotFound();
            }
        });
    }

    /**
     * 获取所有记忆回调接口
     */
    public interface AllMemoriesCallback {
        /**
         * 返回所有记忆的映射
         * @param memories 键值对映射表
         */
        void onResult(Map<String, String> memories);
    }

    /**
     * 获取指定类别的所有记忆
     *
     * 查询指定类别下的所有记忆，返回按时间戳倒序排列的键值对映射。
     *
     * @param category 记忆的类别
     * @param callback 查询结果的回调接口，包含所有记忆的键值对映射
     */
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
                Slog.d(TAG, "getAllMemories: category=" + category + ", count=" + memories.size());
            }
            callback.onResult(memories);
        });
    }

    /**
     * 设置用户偏好
     *
     * 存储用户偏好设置，如已存在相同类别和键的偏好则更新，否则插入新记录。
     *
     * @param category 偏好类别
     * @param key 偏好键名
     * @param value 偏好值
     */
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
                Slog.d(TAG, "setPreference: category=" + category + ", key=" + key + ", value=" + value);
            }
        });
    }

    /**
     * 获取用户偏好
     *
     * 根据类别和键名检索偏好设置，返回按更新时间倒序的第一条记录。
     *
     * @param category 偏好类别
     * @param key 偏好键名
     * @param callback 查询结果的回调接口
     */
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

    /**
     * 添加屏幕历史记录
     *
     * 记录当前屏幕的描述信息和截图哈希值。系统会自动维护历史记录数量，
     * 超过MAX_SCREEN_HISTORY条时会自动清理最旧的记录。
     *
     * @param description 屏幕描述信息
     * @param screenshotHash 截图的哈希值，用于快速比对
     */
    public void addScreenHistory(String description, byte[] screenshotHash) {
        mExecutor.execute(() -> {
            SQLiteDatabase db = mDbHelper.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("description", description);
            values.put("screenshot_hash", screenshotHash != null ?
                    new String(screenshotHash) : null);
            values.put("timestamp", System.currentTimeMillis());

            db.insert(TABLE_SCREEN_HISTORY, null, values);

            // 检查并清理超过最大数量的记录
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
                if (DEBUG) {
                    Slog.i(TAG, "Screen history trimmed from " + count + " to " + MAX_SCREEN_HISTORY);
                }
            }

            if (DEBUG) {
                Slog.d(TAG, "addScreenHistory: description=" + description);
            }
        });
    }

    /**
     * 添加操作历史记录
     *
     * 记录AI Agent执行的操作，包括操作类型、目标和结果。系统会自动维护历史记录数量，
     * 超过MAX_ACTION_HISTORY条时会自动清理最旧的记录。
     *
     * @param action 操作类型/动作
     * @param target 操作目标
     * @param result 操作结果
     */
    public void addActionHistory(String action, String target, String result) {
        mExecutor.execute(() -> {
            SQLiteDatabase db = mDbHelper.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("action", action);
            values.put("target", target);
            values.put("result", result);
            values.put("timestamp", System.currentTimeMillis());

            db.insert(TABLE_ACTION_HISTORY, null, values);

            // 检查并清理超过最大数量的记录
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
                if (DEBUG) {
                    Slog.i(TAG, "Action history trimmed from " + count + " to " + MAX_ACTION_HISTORY);
                }
            }

            if (DEBUG) {
                Slog.d(TAG, "addActionHistory: action=" + action + ", target=" + target);
            }
        });
    }

    /**
     * 屏幕历史回调接口
     */
    public interface ScreenHistoryCallback {
        /**
         * 返回屏幕历史列表
         * @param history 屏幕快照列表，按时间倒序
         */
        void onResult(java.util.List<ScreenSnapshot> history);
    }

    /**
     * 获取屏幕历史记录
     *
     * 返回最近的屏幕历史记录列表，最多返回MAX_SCREEN_HISTORY条，
     * 按时间戳倒序排列（最新的在前）。
     *
     * @param callback 查询结果的回调接口
     */
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

            if (DEBUG) {
                Slog.d(TAG, "getScreenHistory: returned " + history.size() + " records");
            }
            callback.onResult(history);
        });
    }

    /**
     * 操作历史回调接口
     */
    public interface ActionHistoryCallback {
        /**
         * 返回操作历史列表
         * @param history 操作记录列表，按时间倒序
         */
        void onResult(java.util.List<ActionRecord> history);
    }

    /**
     * 获取操作历史记录
     *
     * 返回最近的操作历史记录列表，最多返回MAX_ACTION_HISTORY条，
     * 按时间戳倒序排列（最新的在前）。
     *
     * @param callback 查询结果的回调接口
     */
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

            if (DEBUG) {
                Slog.d(TAG, "getActionHistory: returned " + history.size() + " records");
            }
            callback.onResult(history);
        });
    }

    /**
     * 添加对话轮次
     *
     * 记录AI对话的一轮交互，包括角色（user/assistant）和内容。
     * 系统会自动维护对话历史长度，超过MAX_CONVERSATION_TURNS轮时会自动清理最早的对话。
     *
     * @param role 角色，如"user"或"assistant"
     * @param content 对话内容
     */
    public void addConversationTurn(String role, String content) {
        mExecutor.execute(() -> {
            SQLiteDatabase db = mDbHelper.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("role", role);
            values.put("content", content);
            values.put("timestamp", System.currentTimeMillis());

            db.insert(TABLE_CONVERSATIONS, null, values);

            // 检查并清理超过最大数量的对话
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
                if (DEBUG) {
                    Slog.i(TAG, "Conversation history trimmed from " + count + " to " + MAX_CONVERSATION_TURNS);
                }
            }

            if (DEBUG) {
                Slog.d(TAG, "addConversationTurn: role=" + role);
            }
        });
    }

    /**
     * 对话历史回调接口
     */
    public interface ConversationHistoryCallback {
        /**
         * 返回对话历史列表
         * @param history 对话轮次列表，按时间升序（最早的在前）
         */
        void onResult(java.util.List<ConversationTurn> history);
    }

    /**
     * 获取对话历史
     *
     * 返回完整的对话历史，按时间戳升序排列（最早的对话在前），
     * 以保持对话的上下文连贯性。
     *
     * @param callback 查询结果的回调接口
     */
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

            if (DEBUG) {
                Slog.d(TAG, "getConversationHistory: returned " + history.size() + " turns");
            }
            callback.onResult(history);
        });
    }

    /**
     * 清除所有记忆
     *
     * 清除记忆表、会话表、屏幕历史表和操作历史表中的所有数据。
     * 偏好设置表不受影响。
     */
    public void clearMemory() {
        mExecutor.execute(() -> {
            SQLiteDatabase db = mDbHelper.getWritableDatabase();
            db.delete(TABLE_MEMORIES, null, null);
            db.delete(TABLE_CONVERSATIONS, null, null);
            db.delete(TABLE_SCREEN_HISTORY, null, null);
            db.delete(TABLE_ACTION_HISTORY, null, null);

            Slog.i(TAG, "All memories cleared (memories, conversations, screen history, action history)");
        });
    }

    /**
     * 清除所有偏好设置
     *
     * 清除偏好表中的所有数据。记忆、会话等数据不受影响。
     */
    public void clearPreferences() {
        mExecutor.execute(() -> {
            SQLiteDatabase db = mDbHelper.getWritableDatabase();
            db.delete(TABLE_PREFERENCES, null, null);

            Slog.i(TAG, "All preferences cleared");
        });
    }

    /**
     * 屏幕快照数据结构
     *
     * 包含屏幕描述、截图哈希和时间戳。
     */
    public static class ScreenSnapshot {
        /** 屏幕描述信息 */
        public String description;
        /** 截图的哈希值 */
        public String screenshotHash;
        /** 时间戳（毫秒） */
        public long timestamp;
    }

    /**
     * 操作记录数据结构
     *
     * 包含操作类型、目标和结果。
     */
    public static class ActionRecord {
        /** 操作类型/动作 */
        public String action;
        /** 操作目标 */
        public String target;
        /** 操作结果 */
        public String result;
        /** 时间戳（毫秒） */
        public long timestamp;
    }

    /**
     * 对话轮次数据结构
     *
     * 包含角色、内容和时间戳。
     */
    public static class ConversationTurn {
        /** 角色（user/assistant） */
        public String role;
        /** 对话内容 */
        public String content;
        /** 时间戳（毫秒） */
        public long timestamp;
    }

    /**
     * 数据库帮助类
     *
     * 负责创建和管理SQLite数据库，包括创建所有必要的表。
     */
    private static class DatabaseHelper extends SQLiteOpenHelper {
        DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        /**
         * 创建数据库表
         *
         * 首次创建数据库时调用，创建以下表：
         * - memories: 普通记忆存储
         * - preferences: 用户偏好设置
         * - conversations: 对话历史
         * - screen_history: 屏幕历史
         * - action_history: 操作历史
         *
         * @param db 数据库实例
         */
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

            Slog.i(TAG, "Database tables created successfully");
        }

        /**
         * 升级数据库
         *
         * 当数据库版本升级时调用，先删除所有旧表，再重新创建。
         * 注意：这会导致所有现有数据丢失。
         *
         * @param db 数据库实例
         * @param oldVersion 旧版本号
         * @param newVersion 新版本号
         */
        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Slog.w(TAG, "Upgrading database from version " + oldVersion + " to " + newVersion);
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_MEMORIES);
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_PREFERENCES);
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_CONVERSATIONS);
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_SCREEN_HISTORY);
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_ACTION_HISTORY);
            onCreate(db);
        }
    }
}
