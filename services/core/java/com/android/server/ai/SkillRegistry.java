package com.android.server.ai;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import com.android.server.LocalServices;
import com.android.server.UiThread;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 技能注册表
 *
 * 负责管理AI Agent的所有技能（Skill），包括：
 * - 从JSON配置文件加载技能
 * - 维护技能列表和触发词索引
 * - 提供技能的增删改查操作
 * - 支持技能的启用/禁用
 * - 技能的持久化存储
 *
 * 技能通过触发词（trigger）匹配用户输入，当用户输入包含某个技能的触发词时，
 * 该技能会被匹配并返回给调用者。
 *
 * @see Skill
 */
public class SkillRegistry {
    private static final String TAG = "SkillRegistry";
    private static final boolean DEBUG = true;

    /** 技能配置目录 */
    private static final String SKILLS_CONFIG_DIR = "/data/system/agent/";
    /** 技能配置文件路径 */
    private static final String SKILLS_CONFIG_FILE = SKILLS_CONFIG_DIR + "skills.json";

    /** 触发词最大长度 */
    private static final int MAX_TRIGGER_LENGTH = 100;

    private final Context mContext;
    private final Executor mExecutor;
    private final Handler mHandler;
    private final Executor mFileIoExecutor;

    /** 技能映射表：技能ID -> 技能对象 */
    private final ConcurrentHashMap<String, Skill> mSkills = new ConcurrentHashMap<>();
    /** 触发词索引表：触发词 -> 技能列表 */
    private final Map<String, List<Skill>> mTriggerToSkills = new ConcurrentHashMap<>();

    /** 技能变更监听器 */
    private SkillChangeListener mSkillChangeListener;

    /**
     * 技能变更监听器接口
     *
     * 用于监听技能注册表中技能的变化事件。
     */
    public interface SkillChangeListener {
        /**
         * 技能添加时回调
         * @param skill 新添加的技能
         */
        void onSkillAdded(Skill skill);
        /**
         * 技能更新时回调
         * @param skill 更新的技能
         */
        void onSkillUpdated(Skill skill);
        /**
         * 技能移除时回调
         * @param skillId 被移除的技能ID
         */
        void onSkillRemoved(String skillId);
        /**
         * 技能重新加载时回调
         */
        void onSkillsReloaded();
    }

    /**
     * 构造函数
     *
     * 初始化技能注册表，创建必要的目录结构，并加载技能配置。
     *
     * @param context 应用上下文
     */
    public SkillRegistry(Context context) {
        mContext = context;
        mExecutor = command -> UiThread.getHandler().post(command);
        mHandler = new Handler(Looper.getMainLooper());
        mFileIoExecutor = Executors.newSingleThreadExecutor();

        if (DEBUG) {
            Slog.d(TAG, "SkillRegistry initializing...");
        }

        loadSkills();

        if (DEBUG) {
            Slog.d(TAG, "SkillRegistry initialized with " + mSkills.size() + " skills");
        }
    }

    /**
     * 设置技能变更监听器
     *
     * @param listener 技能变更监听器
     */
    public void setSkillChangeListener(SkillChangeListener listener) {
        mSkillChangeListener = listener;
        Slog.d(TAG, "SkillChangeListener set");
    }

    /**
     * 加载技能配置
     *
     * 从JSON配置文件中加载所有技能。如果配置文件不存在，则创建默认技能。
     * 加载过程在后台线程执行。
     */
    public void loadSkills() {
        mFileIoExecutor.execute(() -> {
            File configDir = new File(SKILLS_CONFIG_DIR);
            if (!configDir.exists()) {
                configDir.mkdirs();
                if (DEBUG) {
                    Slog.d(TAG, "Created skills config directory: " + SKILLS_CONFIG_DIR);
                }
            }

            File configFile = new File(SKILLS_CONFIG_FILE);
            if (!configFile.exists()) {
                Slog.i(TAG, "Skills config file not found, creating default skills");
                createDefaultSkills();
                return;
            }

            try (FileInputStream fis = new FileInputStream(configFile)) {
                byte[] data = new byte[(int) configFile.length()];
                int bytesRead = fis.read(data);
                String content = new String(data, 0, bytesRead, StandardCharsets.UTF_8);
                parseSkillsJson(content);
                if (DEBUG) {
                    Slog.d(TAG, "Loaded skills from: " + SKILLS_CONFIG_FILE);
                }
            } catch (IOException e) {
                Slog.e(TAG, "Failed to load skills from " + SKILLS_CONFIG_FILE, e);
                createDefaultSkills();
            }
        });
    }

    /**
     * 解析技能JSON配置
     *
     * 将JSON格式的配置内容解析为技能对象列表。
     *
     * @param json JSON配置字符串
     */
    private void parseSkillsJson(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray skillsArray = root.optJSONArray("skills");

            // 清空现有技能
            mSkills.clear();
            mTriggerToSkills.clear();

            if (skillsArray == null) {
                Slog.w(TAG, "No skills array found in config");
                return;
            }

            for (int i = 0; i < skillsArray.length(); i++) {
                JSONObject skillJson = skillsArray.getJSONObject(i);
                Skill skill = parseSkill(skillJson);
                if (skill != null) {
                    addSkillInternal(skill);
                }
            }

            Slog.i(TAG, "Parsed " + mSkills.size() + " skills from config");
        } catch (JSONException e) {
            Slog.e(TAG, "Failed to parse skills JSON", e);
        }
    }

    /**
     * 解析单个技能JSON对象
     *
     * 从JSONObject中提取技能的各项属性。
     *
     * @param json 技能的JSON对象
     * @return 解析后的技能对象，解析失败返回null
     */
    private Skill parseSkill(JSONObject json) {
        try {
            String id = json.optString("id");
            if (id == null || id.isEmpty()) {
                Slog.w(TAG, "Skill missing id, skipping");
                return null;
            }

            Skill skill = new Skill();
            skill.id = id;
            skill.name = json.optString("name", id);
            skill.description = json.optString("description", "");
            skill.enabled = json.optBoolean("enabled", true);

            // 解析触发词数组
            JSONArray triggerArray = json.optJSONArray("trigger");
            if (triggerArray != null) {
                List<String> triggers = new ArrayList<>();
                for (int i = 0; i < triggerArray.length(); i++) {
                    String trigger = triggerArray.getString(i);
                    // 过滤掉过长或为空的触发词
                    if (trigger != null && trigger.length() <= MAX_TRIGGER_LENGTH) {
                        triggers.add(trigger);
                    }
                }
                skill.trigger = triggers.toArray(new String[0]);
            } else {
                skill.trigger = new String[0];
            }

            // 解析权限数组
            JSONArray permissionArray = json.optJSONArray("permission");
            if (permissionArray != null) {
                List<String> permissions = new ArrayList<>();
                for (int i = 0; i < permissionArray.length(); i++) {
                    permissions.add(permissionArray.getString(i));
                }
                skill.permission = permissions.toArray(new String[0]);
            } else {
                skill.permission = new String[0];
            }

            skill.config = json.optJSONObject("config");
            skill.updatedAt = System.currentTimeMillis();

            return skill;
        } catch (JSONException e) {
            Slog.e(TAG, "Failed to parse skill", e);
            return null;
        }
    }

    /**
     * 内部添加技能
     *
     * 将技能添加到映射表和触发词索引中，不触发保存和回调。
     *
     * @param skill 要添加的技能
     */
    private void addSkillInternal(Skill skill) {
        mSkills.put(skill.id, skill);

        // 建立触发词到技能的索引
        for (String trigger : skill.trigger) {
            List<Skill> skills = mTriggerToSkills.get(trigger);
            if (skills == null) {
                skills = new ArrayList<>();
                mTriggerToSkills.put(trigger, skills);
            }
            if (!skills.contains(skill)) {
                skills.add(skill);
            }
        }

        if (DEBUG) {
            Slog.d(TAG, "Added skill: " + skill.id + " with " + skill.trigger.length + " triggers");
        }
    }

    /**
     * 创建默认技能
     *
     * 当配置文件不存在时，创建一组内置的默认技能，包括：
     * - 点外卖：帮助用户点外卖
     * - 查看日程：查看和管理日程安排
     * - 发消息：发送消息给联系人
     * - 查天气：查询天气信息
     * - 控制音乐：播放控制相关操作
     * - 设置提醒：定时提醒功能
     */
    private void createDefaultSkills() {
        Slog.i(TAG, "Creating default skills");

        List<Skill> defaultSkills = new ArrayList<>();

        // 点外卖技能
        Skill orderFood = new Skill();
        orderFood.id = "order_food";
        orderFood.name = "点外卖";
        orderFood.description = "帮我点外卖，选择最优惠的选项";
        orderFood.trigger = new String[]{"点外卖", "饿了", "叫外卖", "帮我点个饭"};
        orderFood.enabled = true;
        orderFood.permission = new String[]{"camera", "storage"};
        orderFood.updatedAt = System.currentTimeMillis();
        defaultSkills.add(orderFood);

        // 查看日程技能
        Skill checkSchedule = new Skill();
        checkSchedule.id = "check_schedule";
        checkSchedule.name = "查看日程";
        checkSchedule.description = "查看和管理日程安排";
        checkSchedule.trigger = new String[]{"日程", " schedule", "今天有什么", "明天的安排"};
        checkSchedule.enabled = true;
        checkSchedule.updatedAt = System.currentTimeMillis();
        defaultSkills.add(checkSchedule);

        // 发消息技能
        Skill sendMessage = new Skill();
        sendMessage.id = "send_message";
        sendMessage.name = "发消息";
        sendMessage.description = "发送消息给指定联系人";
        sendMessage.trigger = new String[]{"发消息", "发短信", "发微信", "给xxx发消息"};
        sendMessage.enabled = true;
        sendMessage.permission = new String[]{"sms", "contacts"};
        sendMessage.updatedAt = System.currentTimeMillis();
        defaultSkills.add(sendMessage);

        // 查天气技能
        Skill checkWeather = new Skill();
        checkWeather.id = "check_weather";
        checkWeather.name = "查天气";
        checkWeather.description = "查询当前和未来天气";
        checkWeather.trigger = new String[]{"天气", "今天天气", "明天天气", "会不会下雨"};
        checkWeather.enabled = true;
        checkWeather.updatedAt = System.currentTimeMillis();
        defaultSkills.add(checkWeather);

        // 控制音乐技能
        Skill controlMusic = new Skill();
        controlMusic.id = "control_music";
        controlMusic.name = "控制音乐";
        controlMusic.description = "播放、暂停、切歌、调音量";
        controlMusic.trigger = new String[]{"播放音乐", "暂停", "下一首", "上一首", "调大音量"};
        controlMusic.enabled = true;
        controlMusic.updatedAt = System.currentTimeMillis();
        defaultSkills.add(controlMusic);

        // 设置提醒技能
        Skill setReminder = new Skill();
        setReminder.id = "set_reminder";
        setReminder.name = "设置提醒";
        setReminder.description = "设置定时提醒";
        setReminder.trigger = new String[]{"提醒我", "定时提醒", "闹钟", " minute 后提醒我"};
        setReminder.enabled = true;
        setReminder.updatedAt = System.currentTimeMillis();
        defaultSkills.add(setReminder);

        // 添加所有默认技能
        for (Skill skill : defaultSkills) {
            addSkillInternal(skill);
        }

        // 保存到文件
        saveSkills();
    }

    /**
     * 保存技能配置
     *
     * 将当前所有技能序列化并保存到JSON配置文件。
     * 保存过程在后台线程执行。
     */
    public void saveSkills() {
        mFileIoExecutor.execute(() -> {
            try {
                File configDir = new File(SKILLS_CONFIG_DIR);
                if (!configDir.exists()) {
                    configDir.mkdirs();
                }

                JSONObject root = new JSONObject();
                JSONArray skillsArray = new JSONArray();

                for (Skill skill : mSkills.values()) {
                    JSONObject skillJson = new JSONObject();
                    skillJson.put("id", skill.id);
                    skillJson.put("name", skill.name);
                    skillJson.put("description", skill.description);
                    skillJson.put("enabled", skill.enabled);
                    skillJson.put("trigger", new JSONArray(skill.trigger));
                    skillJson.put("permission", new JSONArray(skill.permission));
                    if (skill.config != null) {
                        skillJson.put("config", skill.config);
                    }
                    skillsArray.put(skillJson);
                }

                root.put("skills", skillsArray);

                FileOutputStream fos = new FileOutputStream(SKILLS_CONFIG_FILE);
                fos.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
                fos.close();

                Slog.i(TAG, "Saved " + mSkills.size() + " skills to: " + SKILLS_CONFIG_FILE);
            } catch (IOException | JSONException e) {
                Slog.e(TAG, "Failed to save skills", e);
            }
        });
    }

    /**
     * 获取所有技能
     *
     * @return 所有技能的列表（包含已禁用的技能）
     */
    public List<Skill> getAllSkills() {
        return new ArrayList<>(mSkills.values());
    }

    /**
     * 获取所有已启用的技能
     *
     * @return 已启用技能的列表
     */
    public List<Skill> getEnabledSkills() {
        List<Skill> enabled = new ArrayList<>();
        for (Skill skill : mSkills.values()) {
            if (skill.enabled) {
                enabled.add(skill);
            }
        }
        if (DEBUG) {
            Slog.d(TAG, "getEnabledSkills: returning " + enabled.size() + " skills");
        }
        return enabled;
    }

    /**
     * 获取指定ID的技能
     *
     * @param id 技能ID
     * @return 技能对象，如果不存在返回null
     */
    public Skill getSkill(String id) {
        return mSkills.get(id);
    }

    /**
     * 匹配触发词
     *
     * 根据用户输入匹配所有符合条件的技能。支持双向匹配：
     * - 用户输入包含技能的触发词
     * - 技能的触发词包含用户输入
     *
     * 只返回已启用的技能。
     *
     * @param input 用户输入
     * @return 匹配到的技能列表
     */
    public List<Skill> matchTrigger(String input) {
        List<Skill> matched = new ArrayList<>();

        for (Skill skill : mSkills.values()) {
            if (!skill.enabled) {
                continue;
            }

            for (String trigger : skill.trigger) {
                // 双向包含匹配
                if (input.contains(trigger) || trigger.contains(input)) {
                    if (!matched.contains(skill)) {
                        matched.add(skill);
                    }
                    break;
                }
            }
        }

        if (DEBUG) {
            Slog.d(TAG, "matchTrigger: input='" + input + "' matched=" + matched.size());
        }

        return matched;
    }

    /**
     * 添加技能
     *
     * 添加一个新技能到注册表。如果技能ID已存在或ID为空，则添加失败。
     *
     * @param skill 要添加的技能
     * @return 是否添加成功
     */
    public boolean addSkill(Skill skill) {
        if (skill.id == null || skill.id.isEmpty()) {
            Slog.w(TAG, "Cannot add skill with empty id");
            return false;
        }

        if (mSkills.containsKey(skill.id)) {
            Slog.w(TAG, "Skill already exists: " + skill.id);
            return false;
        }

        skill.updatedAt = System.currentTimeMillis();
        addSkillInternal(skill);
        saveSkills();

        if (mSkillChangeListener != null) {
            mHandler.post(() -> mSkillChangeListener.onSkillAdded(skill));
        }

        Slog.i(TAG, "Added skill: " + skill.id);
        return true;
    }

    /**
     * 更新技能
     *
     * 更新已存在技能的信息。如果技能不存在，则更新失败。
     *
     * @param skill 更新后的技能对象（必须包含已存在的ID）
     * @return 是否更新成功
     */
    public boolean updateSkill(Skill skill) {
        if (skill.id == null || !mSkills.containsKey(skill.id)) {
            Slog.w(TAG, "Cannot update non-existent skill: " + skill.id);
            return false;
        }

        skill.updatedAt = System.currentTimeMillis();

        // 移除旧的触发词索引
        mSkills.remove(skill.id);
        for (String trigger : skill.trigger) {
            List<Skill> skills = mTriggerToSkills.get(trigger);
            if (skills != null) {
                skills.remove(skill);
            }
        }

        // 添加更新后的技能
        addSkillInternal(skill);
        saveSkills();

        if (mSkillChangeListener != null) {
            mHandler.post(() -> mSkillChangeListener.onSkillUpdated(skill));
        }

        Slog.i(TAG, "Updated skill: " + skill.id);
        return true;
    }

    /**
     * 移除技能
     *
     * 从注册表中移除指定ID的技能。
     *
     * @param id 要移除的技能ID
     * @return 是否移除成功
     */
    public boolean removeSkill(String id) {
        Skill skill = mSkills.remove(id);
        if (skill == null) {
            Slog.w(TAG, "Cannot remove non-existent skill: " + id);
            return false;
        }

        // 移除触发词索引
        for (String trigger : skill.trigger) {
            List<Skill> skills = mTriggerToSkills.get(trigger);
            if (skills != null) {
                skills.remove(skill);
            }
        }

        saveSkills();

        if (mSkillChangeListener != null) {
            mHandler.post(() -> mSkillChangeListener.onSkillRemoved(id));
        }

        Slog.i(TAG, "Removed skill: " + id);
        return true;
    }

    /**
     * 启用/禁用技能
     *
     * @param id 技能ID
     * @param enabled true启用，false禁用
     * @return 是否操作成功
     */
    public boolean enableSkill(String id, boolean enabled) {
        Skill skill = mSkills.get(id);
        if (skill == null) {
            Slog.w(TAG, "Cannot enable/disable non-existent skill: " + id);
            return false;
        }

        skill.enabled = enabled;
        skill.updatedAt = System.currentTimeMillis();
        saveSkills();

        if (mSkillChangeListener != null) {
            mHandler.post(() -> mSkillChangeListener.onSkillUpdated(skill));
        }

        Slog.i(TAG, "Set skill " + id + " enabled=" + enabled);
        return true;
    }

    /**
     * 获取技能总数
     *
     * @return 注册表中的技能总数（包括已禁用的）
     */
    public int getSkillCount() {
        return mSkills.size();
    }

    /**
     * 获取已启用技能数量
     *
     * @return 已启用状态的技能数量
     */
    public int getEnabledSkillCount() {
        int count = 0;
        for (Skill skill : mSkills.values()) {
            if (skill.enabled) {
                count++;
            }
        }
        return count;
    }

    /**
     * 重新加载技能
     *
     * 重新从配置文件加载所有技能，并通知监听器。
     */
    public void reloadSkills() {
        loadSkills();

        if (mSkillChangeListener != null) {
            mHandler.post(() -> mSkillChangeListener.onSkillsReloaded());
        }

        Slog.i(TAG, "Skills reloaded");
    }

    /**
     * 导出技能为JSON格式
     *
     * 将所有技能导出为格式化的JSON字符串，用于备份或传输。
     *
     * @return JSON格式的技能配置字符串
     */
    public String exportSkillsToJson() {
        JSONObject root = new JSONObject();
        JSONArray skillsArray = new JSONArray();

        for (Skill skill : mSkills.values()) {
            try {
                JSONObject skillJson = new JSONObject();
                skillJson.put("id", skill.id);
                skillJson.put("name", skill.name);
                skillJson.put("description", skill.description);
                skillJson.put("enabled", skill.enabled);
                skillJson.put("trigger", new JSONArray(skill.trigger));
                skillJson.put("permission", new JSONArray(skill.permission));
                if (skill.config != null) {
                    skillJson.put("config", skill.config);
                }
                skillsArray.put(skillJson);
            } catch (JSONException e) {
                Slog.e(TAG, "Failed to export skill: " + skill.id, e);
            }
        }

        try {
            root.put("skills", skillsArray);
        } catch (JSONException e) {
            Slog.e(TAG, "Failed to create export JSON", e);
        }

        try {
            return root.toString(2);
        } catch (JSONException e) {
            Slog.e(TAG, "Failed to format JSON", e);
            return "{}";
        }
    }

    /**
     * 技能数据结构
     *
     * 包含技能的所有属性信息。
     */
    public static class Skill {
        /** 技能唯一标识符 */
        public String id;
        /** 技能名称 */
        public String name;
        /** 技能描述 */
        public String description;
        /** 触发词数组 */
        public String[] trigger;
        /** 所需权限数组 */
        public String[] permission;
        /** 是否启用 */
        public boolean enabled;
        /** 技能配置（JSON格式） */
        public JSONObject config;
        /** 最后更新时间戳 */
        public long updatedAt;

        @Override
        public String toString() {
            return "Skill{id='" + id + "', name='" + name + "', enabled=" + enabled +
                    ", triggers=" + (trigger != null ? trigger.length : 0) + "}";
        }
    }
}
