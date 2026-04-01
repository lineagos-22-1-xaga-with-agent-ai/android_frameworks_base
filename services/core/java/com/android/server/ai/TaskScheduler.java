package com.android.server.ai;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.android.server.UiThread;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

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

    private final ConcurrentHashMap<String, ScheduledTask> mActiveTasks = new ConcurrentHashMap<>();
    private final AtomicLong mTaskIdGenerator = new AtomicLong(System.currentTimeMillis());

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_COMPLETED = 2;
    public static final int STATUS_FAILED = 3;
    public static final int STATUS_CANCELLED = 4;

    public static final int TYPE_ONE_TIME = 0;
    public static final int TYPE_RECURRING = 1;

    public interface TaskExecutor {
        void execute(String taskId, String taskDescription, TaskCallback callback);
    }

    public interface TaskCallback {
        void onSuccess(String result);
        void onFailure(String error);
    }

    public interface TaskChangeListener {
        void onTaskScheduled(ScheduledTask task);
        void onTaskStarted(ScheduledTask task);
        void onTaskCompleted(ScheduledTask task, String result);
        void onTaskFailed(ScheduledTask task, String error);
        void onTaskCancelled(ScheduledTask task);
    }

    private TaskChangeListener mTaskChangeListener;
    private TaskExecutor mTaskExecutor;

    public TaskScheduler(Context context) {
        mContext = context;
        mExecutor = UiThread.getExecutor();
        mHandler = new Handler(Looper.getMainLooper());
        mDbHelper = new DatabaseHelper(context);
        
        if (DEBUG) {
            Log.d(TAG, "TaskScheduler initialized");
        }
        
        loadPendingTasks();
    }

    public void setTaskChangeListener(TaskChangeListener listener) {
        mTaskChangeListener = listener;
    }

    public void setTaskExecutor(TaskExecutor executor) {
        mTaskExecutor = executor;
    }

    public String scheduleTask(String taskDescription, long triggerAtMillis) {
        return scheduleTask(taskDescription, triggerAtMillis, TYPE_ONE_TIME, 0);
    }

    public String scheduleTask(String taskDescription, long triggerAtMillis, 
            int type, long intervalMillis) {
        String taskId = generateTaskId();
        
        if (DEBUG) {
            Log.d(TAG, "scheduleTask: id=" + taskId + ", desc=" + taskDescription + 
                    ", triggerAt=" + triggerAtMillis + ", type=" + type);
        }

        ScheduledTask task = new ScheduledTask();
        task.taskId = taskId;
        task.description = taskDescription;
        task.triggerAtMillis = triggerAtMillis;
        task.type = type;
        task.intervalMillis = intervalMillis;
        task.status = STATUS_PENDING;
        task.createdAt = System.currentTimeMillis();

        mExecutor.execute(() -> {
            saveTaskToDb(task);
            
            mActiveTasks.put(taskId, task);
            
            scheduleTaskExecution(task);
            
            if (mTaskChangeListener != null) {
                mHandler.post(() -> mTaskChangeListener.onTaskScheduled(task));
            }
        });

        return taskId;
    }

    public String scheduleRecurringTask(String taskDescription, long intervalMillis) {
        long triggerAt = System.currentTimeMillis() + intervalMillis;
        return scheduleTask(taskDescription, triggerAt, TYPE_RECURRING, intervalMillis);
    }

    private void scheduleTaskExecution(ScheduledTask task) {
        long delay = task.triggerAtMillis - System.currentTimeMillis();
        if (delay < 0) {
            delay = 0;
        }

        mHandler.postDelayed(() -> {
            if (task.status == STATUS_CANCELLED) {
                if (DEBUG) {
                    Log.d(TAG, "Task cancelled, skipping: " + task.taskId);
                }
                return;
            }
            
            executeTask(task);
        }, delay);
    }

    private void executeTask(ScheduledTask task) {
        if (mTaskExecutor == null) {
            if (DEBUG) {
                Log.e(TAG, "No task executor set for task: " + task.taskId);
            }
            return;
        }

        if (DEBUG) {
            Log.d(TAG, "Executing task: " + task.taskId + ", desc=" + task.description);
        }

        task.status = STATUS_RUNNING;
        task.startedAt = System.currentTimeMillis();
        updateTaskInDb(task);

        if (mTaskChangeListener != null) {
            mHandler.post(() -> mTaskChangeListener.onTaskStarted(task));
        }

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

    private void handleTaskSuccess(ScheduledTask task, String result) {
        task.status = STATUS_COMPLETED;
        task.completedAt = System.currentTimeMillis();
        task.lastResult = result;
        updateTaskInDb(task);

        if (DEBUG) {
            Log.d(TAG, "Task completed: " + task.taskId + ", result=" + result);
        }

        if (mTaskChangeListener != null) {
            mHandler.post(() -> mTaskChangeListener.onTaskCompleted(task, result));
        }

        if (task.type == TYPE_RECURRING && task.status != STATUS_CANCELLED) {
            task.triggerAtMillis = System.currentTimeMillis() + task.intervalMillis;
            task.status = STATUS_PENDING;
            updateTaskInDb(task);
            scheduleTaskExecution(task);
            
            if (DEBUG) {
                Log.d(TAG, "Rescheduled recurring task: " + task.taskId);
            }
        } else {
            mActiveTasks.remove(task.taskId);
        }
    }

    private void handleTaskFailure(ScheduledTask task, String error) {
        task.status = STATUS_FAILED;
        task.completedAt = System.currentTimeMillis();
        task.lastError = error;
        updateTaskInDb(task);

        if (DEBUG) {
            Log.e(TAG, "Task failed: " + task.taskId + ", error=" + error);
        }

        if (mTaskChangeListener != null) {
            mHandler.post(() -> mTaskChangeListener.onTaskFailed(task, error));
        }

        if (task.type == TYPE_RECURRING && task.status != STATUS_CANCELLED) {
            int maxRetries = 3;
            int retryCount = task.retryCount;
            
            if (retryCount < maxRetries) {
                long backoffMillis = (long) Math.pow(2, retryCount) * 1000;
                task.triggerAtMillis = System.currentTimeMillis() + backoffMillis;
                task.status = STATUS_PENDING;
                task.retryCount = retryCount + 1;
                updateTaskInDb(task);
                scheduleTaskExecution(task);
                
                if (DEBUG) {
                    Log.d(TAG, "Retry scheduled for task: " + task.taskId + 
                            ", retry=" + task.retryCount + ", backoff=" + backoffMillis);
                }
            } else {
                if (DEBUG) {
                    Log.w(TAG, "Max retries reached for task: " + task.taskId);
                }
                mActiveTasks.remove(task.taskId);
            }
        } else {
            mActiveTasks.remove(task.taskId);
        }
    }

    public boolean cancelTask(String taskId) {
        if (DEBUG) {
            Log.d(TAG, "cancelTask: taskId=" + taskId);
        }

        ScheduledTask task = mActiveTasks.get(taskId);
        if (task == null) {
            if (DEBUG) {
                Log.w(TAG, "Task not found or already completed: " + taskId);
            }
            return false;
        }

        task.status = STATUS_CANCELLED;
        
        mExecutor.execute(() -> {
            updateTaskInDb(task);
            
            if (mTaskChangeListener != null) {
                mHandler.post(() -> mTaskChangeListener.onTaskCancelled(task));
            }
        });

        mActiveTasks.remove(taskId);
        return true;
    }

    public List<ScheduledTask> getPendingTasks() {
        List<ScheduledTask> tasks = new ArrayList<>();
        for (ScheduledTask task : mActiveTasks.values()) {
            if (task.status == STATUS_PENDING) {
                tasks.add(task);
            }
        }
        return tasks;
    }

    public List<ScheduledTask> getAllTasks() {
        return new ArrayList<>(mActiveTasks.values());
    }

    public ScheduledTask getTask(String taskId) {
        return mActiveTasks.get(taskId);
    }

    public int getPendingTaskCount() {
        int count = 0;
        for (ScheduledTask task : mActiveTasks.values()) {
            if (task.status == STATUS_PENDING) {
                count++;
            }
        }
        return count;
    }

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

            if (DEBUG) {
                Log.d(TAG, "Loaded " + mActiveTasks.size() + " pending tasks");
            }
        });
    }

    private void saveTaskToDb(ScheduledTask task) {
        SQLiteDatabase db = mDbHelper.getWritableDatabase();
        ContentValues values = taskToContentValues(task);
        db.insertWithOnConflict(TABLE_TASKS, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private void updateTaskInDb(ScheduledTask task) {
        SQLiteDatabase db = mDbHelper.getWritableDatabase();
        ContentValues values = taskToContentValues(task);
        db.update(TABLE_TASKS, values, "task_id=?", new String[]{task.taskId});
    }

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

    private String generateTaskId() {
        return "task_" + mTaskIdGenerator.incrementAndGet() + "_" + 
                Long.toHexString(SystemClock.elapsedRealtimeNanos());
    }

    public void clearAllTasks() {
        mExecutor.execute(() -> {
            SQLiteDatabase db = mDbHelper.getWritableDatabase();
            db.delete(TABLE_TASKS, null, null);
            mActiveTasks.clear();
            
            if (DEBUG) {
                Log.d(TAG, "clearAllTasks: all tasks cleared");
            }
        });
    }

    public static class ScheduledTask {
        public String taskId;
        public String description;
        public long triggerAtMillis;
        public int type;
        public long intervalMillis;
        public int status;
        public int retryCount;
        public long createdAt;
        public long startedAt;
        public long completedAt;
        public String lastResult;
        public String lastError;

        @Override
        public String toString() {
            return "ScheduledTask{taskId='" + taskId + "', description='" + description + 
                    "', status=" + status + ", type=" + type + '}';
        }
    }

    private static class DatabaseHelper extends SQLiteOpenHelper {
        DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

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

            if (DEBUG) {
                Log.d(TAG, "Task database table created");
            }
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (DEBUG) {
                Log.w(TAG, "Upgrading task database from version " + oldVersion + " to " + newVersion);
            }
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_TASKS);
            onCreate(db);
        }
    }
}
