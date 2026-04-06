package com.android.server.ai;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Slog;

import com.android.server.UiThread;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 任务调度器
 *
 * 负责调度和执行AI Agent的各类任务，支持：
 * - 一次性任务和周期性任务
 * - 任务状态管理（待执行、执行中、已完成、失败、已取消）
 * - 任务重试机制（指数退避）
 * - 任务持久化存储
 * - 任务变更监听
 *
 * 任务在指定的触发时间点执行，支持延迟执行和周期执行。
 * 系统启动时会自动加载未完成的任务。
 *
 * @see SQLiteOpenHelper
 */
public class TaskScheduler {
    private static final String TAG = "TaskScheduler";
    private static final boolean DEBUG = true;

    private static final String DATABASE_NAME = "agent_tasks.db";
    private static final int DATABASE_VERSION = 1;

    private static final String TABLE_TASKS = "scheduled_tasks";
    private static final String TABLE_TASK_RESULTS = "task_results";

    private final Context mContext;
    private final Executor mExecutor;
    private final Handler mHandler;
    private final DatabaseHelper mDbHelper;

    /** 活跃任务映射表 */
    private final ConcurrentHashMap<String, ScheduledTask> mActiveTasks = new ConcurrentHashMap<>();
    /** 任务ID生成器 */
    private final AtomicLong mTaskIdGenerator = new AtomicLong(System.currentTimeMillis());

    /** 任务状态：待执行 */
    public static final int STATUS_PENDING = 0;
    /** 任务状态：执行中 */
    public static final int STATUS_RUNNING = 1;
    /** 任务状态：已完成 */
    public static final int STATUS_COMPLETED = 2;
    /** 任务状态：执行失败 */
    public static final int STATUS_FAILED = 3;
    /** 任务状态：已取消 */
    public static final int STATUS_CANCELLED = 4;

    /** 任务类型：一次性任务 */
    public static final int TYPE_ONE_TIME = 0;
    /** 任务类型：周期性任务 */
    public static final int TYPE_RECURRING = 1;

    /**
     * 任务执行器接口
     *
     * 定义任务实际执行逻辑的接口，由调用者实现具体业务逻辑。
     */
    public interface TaskExecutor {
        /**
         * 执行任务
         *
         * @param taskId 任务ID
         * @param taskDescription 任务描述
         * @param callback 执行结果的回调
         */
        void execute(String taskId, String taskDescription, TaskCallback callback);
    }

    /**
     * 任务执行回调接口
     */
    public interface TaskCallback {
        /**
         * 任务执行成功
         * @param result 执行结果
         */
        void onSuccess(String result);
        /**
         * 任务执行失败
         * @param error 错误信息
         */
        void onFailure(String error);
    }

    /**
     * 任务变更监听器接口
     *
     * 用于监听任务调度器中任务状态的变化。
     */
    public interface TaskChangeListener {
        /**
         * 任务被调度时回调
         * @param task 被调度的任务
         */
        void onTaskScheduled(ScheduledTask task);
        /**
         * 任务开始执行时回调
         * @param task 开始执行的任务
         */
        void onTaskStarted(ScheduledTask task);
        /**
         * 任务执行完成时回调
         * @param task 完成的任务
         * @param result 执行结果
         */
        void onTaskCompleted(ScheduledTask task, String result);
        /**
         * 任务执行失败时回调
         * @param task 失败的任务
         * @param error 错误信息
         */
        void onTaskFailed(ScheduledTask task, String error);
        /**
         * 任务被取消时回调
         * @param task 被取消的任务
         */
        void onTaskCancelled(ScheduledTask task);
    }

    private TaskChangeListener mTaskChangeListener;
    private TaskExecutor mTaskExecutor;

    /**
     * 构造函数
     *
     * 初始化任务调度器，创建数据库帮助类，并加载待执行的任务。
     *
     * @param context 应用上下文
     */
    public TaskScheduler(Context context) {
        mContext = context;
        mExecutor = command -> UiThread.getHandler().post(command);
        mHandler = new Handler(Looper.getMainLooper());
        mDbHelper = new DatabaseHelper(context);

        if (DEBUG) {
            Slog.d(TAG, "TaskScheduler initialized");
        }

        // 加载未完成的任务
        loadPendingTasks();
    }

    /**
     * 设置任务变更监听器
     *
     * @param listener 任务变更监听器
     */
    public void setTaskChangeListener(TaskChangeListener listener) {
        mTaskChangeListener = listener;
        Slog.d(TAG, "TaskChangeListener set");
    }

    /**
     * 设置任务执行器
     *
     * 设置实际执行任务逻辑的处理器。必须在调度任务前设置，否则任务无法执行。
     *
     * @param executor 任务执行器
     */
    public void setTaskExecutor(TaskExecutor executor) {
        mTaskExecutor = executor;
        if (DEBUG) {
            Slog.d(TAG, "TaskExecutor set");
        }
    }

    /**
     * 调度一次性任务
     *
     * 在指定的触发时间点执行一次任务。
     *
     * @param taskDescription 任务描述
     * @param triggerAtMillis 触发时间戳（毫秒）
     * @return 任务ID
     */
    public String scheduleTask(String taskDescription, long triggerAtMillis) {
        return scheduleTask(taskDescription, triggerAtMillis, TYPE_ONE_TIME, 0);
    }

    /**
     * 调度任务
     *
     * 创建并调度一个任务，支持一次性或周期性执行。
     * 任务会被保存到数据库中，并在触发时执行。
     *
     * @param taskDescription 任务描述
     * @param triggerAtMillis 触发时间戳（毫秒）
     * @param type 任务类型（TYPE_ONE_TIME 或 TYPE_RECURRING）
     * @param intervalMillis 周期任务的间隔（毫秒），一次性任务设为0
     * @return 任务ID
     */
    public String scheduleTask(String taskDescription, long triggerAtMillis,
            int type, long intervalMillis) {
        String taskId = generateTaskId();

        if (DEBUG) {
            Slog.d(TAG, "scheduleTask: id=" + taskId + ", desc=" + taskDescription +
                    ", triggerAt=" + triggerAtMillis + ", type=" + type);
        }

        // 创建任务对象
        ScheduledTask task = new ScheduledTask();
        task.taskId = taskId;
        task.description = taskDescription;
        task.triggerAtMillis = triggerAtMillis;
        task.type = type;
        task.intervalMillis = intervalMillis;
        task.status = STATUS_PENDING;
        task.createdAt = System.currentTimeMillis();

        mExecutor.execute(() -> {
            // 保存到数据库
            saveTaskToDb(task);

            // 添加到活跃任务映射
            mActiveTasks.put(taskId, task);

            // 调度任务执行
            scheduleTaskExecution(task);

            // 通知监听器
            if (mTaskChangeListener != null) {
                mHandler.post(() -> mTaskChangeListener.onTaskScheduled(task));
            }
        });

        return taskId;
    }

    /**
     * 调度周期性任务
     *
     * 创建一个周期性重复执行的任务，首次执行在intervalMillis后。
     *
     * @param taskDescription 任务描述
     * @param intervalMillis 执行间隔（毫秒）
     * @return 任务ID
     */
    public String scheduleRecurringTask(String taskDescription, long intervalMillis) {
        long triggerAt = System.currentTimeMillis() + intervalMillis;
        if (DEBUG) {
            Slog.d(TAG, "scheduleRecurringTask: desc=" + taskDescription + ", interval=" + intervalMillis);
        }
        return scheduleTask(taskDescription, triggerAt, TYPE_RECURRING, intervalMillis);
    }

    /**
     * 调度任务执行
     *
     * 根据任务的触发时间计算延迟，并安排在适当的时间执行任务。
     * 如果触发时间已过，则立即执行。
     *
     * @param task 要调度的任务
     */
    private void scheduleTaskExecution(ScheduledTask task) {
        long delay = task.triggerAtMillis - System.currentTimeMillis();
        if (delay < 0) {
            delay = 0;
        }

        if (DEBUG) {
            Slog.d(TAG, "Scheduling task " + task.taskId + " to execute in " + delay + "ms");
        }

        mHandler.postDelayed(() -> {
            // 检查任务是否已被取消
            if (task.status == STATUS_CANCELLED) {
                if (DEBUG) {
                    Slog.d(TAG, "Task cancelled, skipping: " + task.taskId);
                }
                return;
            }

            // 执行任务
            executeTask(task);
        }, delay);
    }

    /**
     * 执行任务
     *
     * 实际执行任务逻辑。更新任务状态为执行中，然后调用任务执行器。
     * 如果未设置任务执行器，则记录错误并返回。
     *
     * @param task 要执行的任务
     */
    private void executeTask(ScheduledTask task) {
        if (mTaskExecutor == null) {
            Slog.e(TAG, "No task executor set for task: " + task.taskId);
            return;
        }

        if (DEBUG) {
            Slog.d(TAG, "Executing task: " + task.taskId + ", desc=" + task.description);
        }

        // 更新任务状态为执行中
        task.status = STATUS_RUNNING;
        task.startedAt = System.currentTimeMillis();
        updateTaskInDb(task);

        // 通知监听器任务开始执行
        if (mTaskChangeListener != null) {
            mHandler.post(() -> mTaskChangeListener.onTaskStarted(task));
        }

        // 调用任务执行器执行实际任务逻辑
        mTaskExecutor.execute(task.taskId, task.description, new TaskCallback() {
            @Override
            public void onSuccess(String result) {
                mExecutor.execute(() -> {
                    handleTaskSuccess(task, result);
                });
            }

            @Override
            public void onFailure(String error) {
                mExecutor.execute(() -> {
                    handleTaskFailure(task, error);
                });
            }
        });
    }

    /**
     * 处理任务成功
     *
     * 当任务执行成功时调用。更新任务状态，对于周期性任务会重新调度执行。
     *
     * @param task 成功的任务
     * @param result 执行结果
     */
    private void handleTaskSuccess(ScheduledTask task, String result) {
        task.status = STATUS_COMPLETED;
        task.completedAt = System.currentTimeMillis();
        task.lastResult = result;
        updateTaskInDb(task);

        Slog.i(TAG, "Task completed: " + task.taskId + ", result=" + result);

        // 通知监听器
        if (mTaskChangeListener != null) {
            mHandler.post(() -> mTaskChangeListener.onTaskCompleted(task, result));
        }

        // 对于周期性任务，重新调度执行
        if (task.type == TYPE_RECURRING && task.status != STATUS_CANCELLED) {
            task.triggerAtMillis = System.currentTimeMillis() + task.intervalMillis;
            task.status = STATUS_PENDING;
            updateTaskInDb(task);
            scheduleTaskExecution(task);

            if (DEBUG) {
                Slog.d(TAG, "Rescheduled recurring task: " + task.taskId);
            }
        } else {
            // 一次性任务完成后从活跃任务中移除
            mActiveTasks.remove(task.taskId);
        }
    }

    /**
     * 处理任务失败
     *
     * 当任务执行失败时调用。对于周期性任务，会根据重试次数决定是否重试
     * 或放弃。首次重试使用1秒延迟，之后每次重试延迟翻倍（指数退避）。
     * 最多重试3次。
     *
     * @param task 失败的任务
     * @param error 错误信息
     */
    private void handleTaskFailure(ScheduledTask task, String error) {
        task.status = STATUS_FAILED;
        task.completedAt = System.currentTimeMillis();
        task.lastError = error;
        updateTaskInDb(task);

        Slog.e(TAG, "Task failed: " + task.taskId + ", error=" + error);

        // 通知监听器
        if (mTaskChangeListener != null) {
            mHandler.post(() -> mTaskChangeListener.onTaskFailed(task, error));
        }

        // 对于周期性任务，尝试重试
        if (task.type == TYPE_RECURRING && task.status != STATUS_CANCELLED) {
            int maxRetries = 3;
            int retryCount = task.retryCount;

            if (retryCount < maxRetries) {
                // 指数退避计算延迟：2^retryCount * 1000ms
                long backoffMillis = (long) Math.pow(2, retryCount) * 1000;
                task.triggerAtMillis = System.currentTimeMillis() + backoffMillis;
                task.status = STATUS_PENDING;
                task.retryCount = retryCount + 1;
                updateTaskInDb(task);
                scheduleTaskExecution(task);

                Slog.w(TAG, "Retry scheduled for task: " + task.taskId +
                        ", retry=" + task.retryCount + ", backoff=" + backoffMillis + "ms");
            } else {
                Slog.e(TAG, "Max retries reached for task: " + task.taskId + ", giving up");
                mActiveTasks.remove(task.taskId);
            }
        } else {
            mActiveTasks.remove(task.taskId);
        }
    }

    /**
     * 取消任务
     *
     * 取消指定ID的任务。如果任务正在执行或已完成，将无法取消。
     *
     * @param taskId 任务ID
     * @return 是否成功取消任务
     */
    public boolean cancelTask(String taskId) {
        Slog.d(TAG, "cancelTask: taskId=" + taskId);

        ScheduledTask task;
        synchronized (mActiveTasks) {
            task = mActiveTasks.get(taskId);
            if (task == null) {
                Slog.w(TAG, "Task not found or already completed: " + taskId);
                return false;
            }

            task.status = STATUS_CANCELLED;
            mActiveTasks.remove(taskId);
        }

        mExecutor.execute(() -> {
            updateTaskInDb(task);

            if (mTaskChangeListener != null) {
                mHandler.post(() -> mTaskChangeListener.onTaskCancelled(task));
            }
        });

        return true;
    }

    /**
     * 获取待执行任务列表
     *
     * 返回所有状态为待执行的任务。
     *
     * @return 待执行任务列表
     */
    public List<ScheduledTask> getPendingTasks() {
        List<ScheduledTask> tasks = new ArrayList<>();
        for (ScheduledTask task : mActiveTasks.values()) {
            if (task.status == STATUS_PENDING) {
                tasks.add(task);
            }
        }
        if (DEBUG) {
            Slog.d(TAG, "getPendingTasks: returning " + tasks.size() + " tasks");
        }
        return tasks;
    }

    /**
     * 获取所有活跃任务
     *
     * 返回当前所有活跃任务（包括待执行、执行中等状态）。
     *
     * @return 所有活跃任务的列表
     */
    public List<ScheduledTask> getAllTasks() {
        return new ArrayList<>(mActiveTasks.values());
    }

    /**
     * 获取指定任务
     *
     * @param taskId 任务ID
     * @return 任务对象，如果不存在则返回null
     */
    public ScheduledTask getTask(String taskId) {
        return mActiveTasks.get(taskId);
    }

    /**
     * 获取待执行任务数量
     *
     * @return 待执行状态的任务数量
     */
    public int getPendingTaskCount() {
        int count = 0;
        for (ScheduledTask task : mActiveTasks.values()) {
            if (task.status == STATUS_PENDING) {
                count++;
            }
        }
        return count;
    }

    /**
     * 加载待执行任务
     *
     * 从数据库加载状态为待执行或执行中的任务，并重新调度执行。
     * 在系统启动时调用，确保之前未完成的任务能够继续执行。
     */
    private void loadPendingTasks() {
        mExecutor.execute(() -> {
            SQLiteDatabase db = mDbHelper.getReadableDatabase();
            Cursor cursor = db.query(TABLE_TASKS, null,
                    "status IN (?, ?)",
                    new String[]{String.valueOf(STATUS_PENDING), String.valueOf(STATUS_RUNNING)},
                    null, null, "trigger_at_millis ASC");

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    ScheduledTask task = cursorToTask(cursor);
                    mActiveTasks.put(task.taskId, task);

                    if (task.status == STATUS_PENDING) {
                        scheduleTaskExecution(task);
                    }
                }
                cursor.close();
            }

            Slog.i(TAG, "Loaded " + mActiveTasks.size() + " pending/running tasks from database");
        });
    }

    /**
     * 保存任务到数据库
     *
     * @param task 要保存的任务
     */
    private void saveTaskToDb(ScheduledTask task) {
        SQLiteDatabase db = mDbHelper.getWritableDatabase();
        ContentValues values = taskToContentValues(task);
        db.insertWithOnConflict(TABLE_TASKS, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /**
     * 更新数据库中的任务
     *
     * @param task 要更新的任务
     */
    private void updateTaskInDb(ScheduledTask task) {
        SQLiteDatabase db = mDbHelper.getWritableDatabase();
        ContentValues values = taskToContentValues(task);
        db.update(TABLE_TASKS, values, "task_id=?", new String[]{task.taskId});
    }

    /**
     * 将任务对象转换为ContentValues
     *
     * @param task 任务对象
     * @return 用于数据库操作的ContentValues
     */
    private ContentValues taskToContentValues(ScheduledTask task) {
        ContentValues values = new ContentValues();
        values.put("task_id", task.taskId);
        values.put("description", task.description);
        values.put("trigger_at_millis", task.triggerAtMillis);
        values.put("type", task.type);
        values.put("interval_millis", task.intervalMillis);
        values.put("status", task.status);
        values.put("retry_count", task.retryCount);
        values.put("created_at", task.createdAt);
        values.put("started_at", task.startedAt);
        values.put("completed_at", task.completedAt);
        values.put("last_result", task.lastResult);
        values.put("last_error", task.lastError);
        return values;
    }

    /**
     * 将数据库Cursor转换为任务对象
     *
     * @param cursor 数据库查询结果
     * @return 任务对象
     */
    private ScheduledTask cursorToTask(Cursor cursor) {
        ScheduledTask task = new ScheduledTask();
        task.taskId = cursor.getString(cursor.getColumnIndexOrThrow("task_id"));
        task.description = cursor.getString(cursor.getColumnIndexOrThrow("description"));
        task.triggerAtMillis = cursor.getLong(cursor.getColumnIndexOrThrow("trigger_at_millis"));
        task.type = cursor.getInt(cursor.getColumnIndexOrThrow("type"));
        task.intervalMillis = cursor.getLong(cursor.getColumnIndexOrThrow("interval_millis"));
        task.status = cursor.getInt(cursor.getColumnIndexOrThrow("status"));
        task.retryCount = cursor.getInt(cursor.getColumnIndexOrThrow("retry_count"));
        task.createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"));

        int startedAtIndex = cursor.getColumnIndexOrThrow("started_at");
        if (!cursor.isNull(startedAtIndex)) {
            task.startedAt = cursor.getLong(startedAtIndex);
        }

        int completedAtIndex = cursor.getColumnIndexOrThrow("completed_at");
        if (!cursor.isNull(completedAtIndex)) {
            task.completedAt = cursor.getLong(completedAtIndex);
        }

        int lastResultIndex = cursor.getColumnIndexOrThrow("last_result");
        if (!cursor.isNull(lastResultIndex)) {
            task.lastResult = cursor.getString(lastResultIndex);
        }

        int lastErrorIndex = cursor.getColumnIndexOrThrow("last_error");
        if (!cursor.isNull(lastErrorIndex)) {
            task.lastError = cursor.getString(lastErrorIndex);
        }

        return task;
    }

    /**
     * 生成唯一任务ID
     *
     * @return 格式为 "task_{递增ID}_{纳秒时间戳}" 的唯一ID
     */
    private String generateTaskId() {
        return "task_" + mTaskIdGenerator.incrementAndGet() + "_" +
                Long.toHexString(SystemClock.elapsedRealtimeNanos());
    }

    /**
     * 清除所有任务
     *
     * 从数据库和内存中删除所有任务。谨慎使用。
     */
    public void clearAllTasks() {
        mExecutor.execute(() -> {
            SQLiteDatabase db = mDbHelper.getWritableDatabase();
            db.delete(TABLE_TASKS, null, null);
            mActiveTasks.clear();

            Slog.w(TAG, "All tasks cleared");
        });
    }

    /**
     * 定时任务数据结构
     *
     * 包含任务的所有属性和状态信息。
     */
    public static class ScheduledTask {
        /** 任务唯一标识符 */
        public String taskId;
        /** 任务描述 */
        public String description;
        /** 触发时间戳（毫秒） */
        public long triggerAtMillis;
        /** 任务类型（TYPE_ONE_TIME 或 TYPE_RECURRING） */
        public int type;
        /** 周期任务的执行间隔（毫秒） */
        public long intervalMillis;
        /** 当前状态 */
        public int status;
        /** 重试次数 */
        public int retryCount;
        /** 创建时间戳 */
        public long createdAt;
        /** 开始执行时间戳 */
        public long startedAt;
        /** 完成时间戳 */
        public long completedAt;
        /** 最近一次执行结果 */
        public String lastResult;
        /** 最近一次错误信息 */
        public String lastError;

        @Override
        public String toString() {
            return "ScheduledTask{taskId='" + taskId + "', description='" + description +
                    "', status=" + status + ", type=" + type + '}';
        }
    }

    /**
     * 任务数据库帮助类
     *
     * 负责创建和管理任务相关的数据库表。
     */
    private static class DatabaseHelper extends SQLiteOpenHelper {
        DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        /**
         * 创建数据库表
         *
         * 首次创建数据库时调用，创建任务表。
         *
         * @param db 数据库实例
         */
        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE_TASKS + " (" +
                    "task_id TEXT PRIMARY KEY," +
                    "description TEXT NOT NULL," +
                    "trigger_at_millis INTEGER NOT NULL," +
                    "type INTEGER NOT NULL DEFAULT 0," +
                    "interval_millis INTEGER NOT NULL DEFAULT 0," +
                    "status INTEGER NOT NULL DEFAULT 0," +
                    "retry_count INTEGER NOT NULL DEFAULT 0," +
                    "created_at INTEGER NOT NULL," +
                    "started_at INTEGER," +
                    "completed_at INTEGER," +
                    "last_result TEXT," +
                    "last_error TEXT)");

            Slog.i(TAG, "Task database table created");
        }

        /**
         * 升级数据库
         *
         * 当数据库版本升级时调用，删除旧表并重新创建。
         *
         * @param db 数据库实例
         * @param oldVersion 旧版本号
         * @param newVersion 新版本号
         */
        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Slog.w(TAG, "Upgrading task database from version " + oldVersion + " to " + newVersion);
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_TASKS);
            onCreate(db);
        }
    }
}
