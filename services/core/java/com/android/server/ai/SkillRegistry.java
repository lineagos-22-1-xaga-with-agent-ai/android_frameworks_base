package com.android.server.ai;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.FileUtils;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

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

public class SkillRegistry {
    private static final String TAG = "SkillRegistry";
    private static final boolean DEBUG = true;

    private static final String SKILLS_CONFIG_DIR = "/data/system/agent/";
    private static final String SKILLS_CONFIG_FILE = SKILLS_CONFIG_DIR + "skills.json";

    private static final int MAX_TRIGGER_LENGTH = 100;

    private final Context mContext;
    private final Executor mExecutor;
    private final Handler mHandler;

    private final ConcurrentHashMap<String, Skill> mSkills = new ConcurrentHashMap<>();
    private final Map<String, List<Skill>> mTriggerToSkills = new ConcurrentHashMap<>();

    private SkillChangeListener mSkillChangeListener;

    public interface SkillChangeListener {
        void onSkillAdded(Skill skill);
        void onSkillUpdated(Skill skill);
        void onSkillRemoved(String skillId);
        void onSkillsReloaded();
    }

    public SkillRegistry(Context context) {
        mContext = context;
        mExecutor = UiThread.getExecutor();
        mHandler = new Handler(Looper.getMainLooper());

        if (DEBUG) {
            Log.d(TAG, "SkillRegistry initializing...");
        }

        loadSkills();

        if (DEBUG) {
            Log.d(TAG, "SkillRegistry initialized with " + mSkills.size() + " skills");
        }
    }

    public void setSkillChangeListener(SkillChangeListener listener) {
        mSkillChangeListener = listener;
    }

    public void loadSkills() {
        mExecutor.execute(() -> {
            File configDir = new File(SKILLS_CONFIG_DIR);
            if (!configDir.exists()) {
                configDir.mkdirs();
                if (DEBUG) {
                    Log.d(TAG, "Created skills config directory: " + SKILLS_CONFIG_DIR);
                }
            }

            File configFile = new File(SKILLS_CONFIG_FILE);
            if (!configFile.exists()) {
                createDefaultSkills();
                return;
            }

            try (FileInputStream fis = new FileInputStream(configFile)) {
                byte[] data = new byte[(int) configFile.length()];
                int bytesRead = fis.read(data);
                String content = new String(data, 0, bytesRead, StandardCharsets.UTF_8);
                parseSkillsJson(content);
                if (DEBUG) {
                    Log.d(TAG, "Loaded skills from: " + SKILLS_CONFIG_FILE);
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to load skills from " + SKILLS_CONFIG_FILE, e);
                createDefaultSkills();
            }
        });
    }

    private void parseSkillsJson(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray skillsArray = root.optJSONArray("skills");

            mSkills.clear();
            mTriggerToSkills.clear();

            if (skillsArray == null) {
                if (DEBUG) {
                    Log.w(TAG, "No skills array found in config");
                }
                return;
            }

            for (int i = 0; i < skillsArray.length(); i++) {
                JSONObject skillJson = skillsArray.getJSONObject(i);
                Skill skill = parseSkill(skillJson);
                if (skill != null) {
                    addSkillInternal(skill);
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse skills JSON", e);
        }
    }

    private Skill parseSkill(JSONObject json) {
        try {
            String id = json.optString("id");
            if (id == null || id.isEmpty()) {
                if (DEBUG) {
                    Log.w(TAG, "Skill missing id, skipping");
                }
                return null;
            }

            Skill skill = new Skill();
            skill.id = id;
            skill.name = json.optString("name", id);
            skill.description = json.optString("description", "");
            skill.enabled = json.optBoolean("enabled", true);

            JSONArray triggerArray = json.optJSONArray("trigger");
            if (triggerArray != null) {
                List<String> triggers = new ArrayList<>();
                for (int i = 0; i < triggerArray.length(); i++) {
                    String trigger = triggerArray.getString(i);
                    if (trigger != null && trigger.length() <= MAX_TRIGGER_LENGTH) {
                        triggers.add(trigger);
                    }
                }
                skill.trigger = triggers.toArray(new String[0]);
            } else {
                skill.trigger = new String[0];
            }

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
            Log.e(TAG, "Failed to parse skill", e);
            return null;
        }
    }

    private void addSkillInternal(Skill skill) {
        mSkills.put(skill.id, skill);

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
            Log.d(TAG, "Added skill: " + skill.id + " with " + skill.trigger.length + " triggers");
        }
    }

    private void createDefaultSkills() {
        if (DEBUG) {
            Log.d(TAG, "Creating default skills");
        }

        List<Skill> defaultSkills = new ArrayList<>();

        Skill orderFood = new Skill();
        orderFood.id = "order_food";
        orderFood.name = "点外卖";
        orderFood.description = "帮我点外卖，选择最优惠的选项";
        orderFood.trigger = new String[]{"点外卖", "饿了", "叫外卖", "帮我点个饭"};
        orderFood.enabled = true;
        orderFood.permission = new String[]{"camera", "storage"};
        orderFood.updatedAt = System.currentTimeMillis();
        defaultSkills.add(orderFood);

        Skill checkSchedule = new Skill();
        checkSchedule.id = "check_schedule";
        checkSchedule.name = "查看日程";
        checkSchedule.description = "查看和管理日程安排";
        checkSchedule.trigger = new String[]{"日程", " schedule", "今天有什么", "明天的安排"};
        checkSchedule.enabled = true;
        checkSchedule.updatedAt = System.currentTimeMillis();
        defaultSkills.add(checkSchedule);

        Skill sendMessage = new Skill();
        sendMessage.id = "send_message";
        sendMessage.name = "发消息";
        sendMessage.description = "发送消息给指定联系人";
        sendMessage.trigger = new String[]{"发消息", "发短信", "发微信", "给xxx发消息"};
        sendMessage.enabled = true;
        sendMessage.permission = new String[]{"sms", "contacts"};
        sendMessage.updatedAt = System.currentTimeMillis();
        defaultSkills.add(sendMessage);

        Skill checkWeather = new Skill();
        checkWeather.id = "check_weather";
        checkWeather.name = "查天气";
        checkWeather.description = "查询当前和未来天气";
        checkWeather.trigger = new String[]{"天气", "今天天气", "明天天气", "会不会下雨"};
        checkWeather.enabled = true;
        checkWeather.updatedAt = System.currentTimeMillis();
        defaultSkills.add(checkWeather);

        Skill controlMusic = new Skill();
        controlMusic.id = "control_music";
        controlMusic.name = "控制音乐";
        controlMusic.description = "播放、暂停、切歌、调音量";
        controlMusic.trigger = new String[]{"播放音乐", "暂停", "下一首", "上一首", "调大音量"};
        controlMusic.enabled = true;
        controlMusic.updatedAt = System.currentTimeMillis();
        defaultSkills.add(controlMusic);

        Skill setReminder = new Skill();
        setReminder.id = "set_reminder";
        setReminder.name = "设置提醒";
        setReminder.description = "设置定时提醒";
        setReminder.trigger = new String[]{"提醒我", "定时提醒", "闹钟", " minute 后提醒我"};
        setReminder.enabled = true;
        setReminder.updatedAt = System.currentTimeMillis();
        defaultSkills.add(setReminder);

        for (Skill skill : defaultSkills) {
            addSkillInternal(skill);
        }

        saveSkills();
    }

    public void saveSkills() {
        mExecutor.execute(() -> {
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

                if (DEBUG) {
                    Log.d(TAG, "Saved " + mSkills.size() + " skills to: " + SKILLS_CONFIG_FILE);
                }
            } catch (IOException | JSONException e) {
                Log.e(TAG, "Failed to save skills", e);
            }
        });
    }

    public List<Skill> getAllSkills() {
        return new ArrayList<>(mSkills.values());
    }

    public List<Skill> getEnabledSkills() {
        List<Skill> enabled = new ArrayList<>();
        for (Skill skill : mSkills.values()) {
            if (skill.enabled) {
                enabled.add(skill);
            }
        }
        return enabled;
    }

    public Skill getSkill(String id) {
        return mSkills.get(id);
    }

    public List<Skill> matchTrigger(String input) {
        List<Skill> matched = new ArrayList<>();

        for (Skill skill : mSkills.values()) {
            if (!skill.enabled) {
                continue;
            }

            for (String trigger : skill.trigger) {
                if (input.contains(trigger) || trigger.contains(input)) {
                    if (!matched.contains(skill)) {
                        matched.add(skill);
                    }
                    break;
                }
            }
        }

        if (DEBUG) {
            Log.d(TAG, "matchTrigger: input='" + input + "' matched=" + matched.size());
        }

        return matched;
    }

    public boolean addSkill(Skill skill) {
        if (skill.id == null || skill.id.isEmpty()) {
            if (DEBUG) {
                Log.w(TAG, "Cannot add skill with empty id");
            }
            return false;
        }

        if (mSkills.containsKey(skill.id)) {
            if (DEBUG) {
                Log.w(TAG, "Skill already exists: " + skill.id);
            }
            return false;
        }

        skill.updatedAt = System.currentTimeMillis();
        addSkillInternal(skill);
        saveSkills();

        if (mSkillChangeListener != null) {
            mHandler.post(() -> mSkillChangeListener.onSkillAdded(skill));
        }

        if (DEBUG) {
            Log.d(TAG, "Added skill: " + skill.id);
        }

        return true;
    }

    public boolean updateSkill(Skill skill) {
        if (skill.id == null || !mSkills.containsKey(skill.id)) {
            if (DEBUG) {
                Log.w(TAG, "Cannot update non-existent skill: " + skill.id);
            }
            return false;
        }

        skill.updatedAt = System.currentTimeMillis();

        mSkills.remove(skill.id);
        for (String trigger : skill.trigger) {
            List<Skill> skills = mTriggerToSkills.get(trigger);
            if (skills != null) {
                skills.remove(skill);
            }
        }

        addSkillInternal(skill);
        saveSkills();

        if (mSkillChangeListener != null) {
            mHandler.post(() -> mSkillChangeListener.onSkillUpdated(skill));
        }

        if (DEBUG) {
            Log.d(TAG, "Updated skill: " + skill.id);
        }

        return true;
    }

    public boolean removeSkill(String id) {
        Skill skill = mSkills.remove(id);
        if (skill == null) {
            if (DEBUG) {
                Log.w(TAG, "Cannot remove non-existent skill: " + id);
            }
            return false;
        }

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

        if (DEBUG) {
            Log.d(TAG, "Removed skill: " + id);
        }

        return true;
    }

    public boolean enableSkill(String id, boolean enabled) {
        Skill skill = mSkills.get(id);
        if (skill == null) {
            if (DEBUG) {
                Log.w(TAG, "Cannot enable/disable non-existent skill: " + id);
            }
            return false;
        }

        skill.enabled = enabled;
        skill.updatedAt = System.currentTimeMillis();
        saveSkills();

        if (mSkillChangeListener != null) {
            mHandler.post(() -> mSkillChangeListener.onSkillUpdated(skill));
        }

        if (DEBUG) {
            Log.d(TAG, "Set skill " + id + " enabled=" + enabled);
        }

        return true;
    }

    public int getSkillCount() {
        return mSkills.size();
    }

    public int getEnabledSkillCount() {
        int count = 0;
        for (Skill skill : mSkills.values()) {
            if (skill.enabled) {
                count++;
            }
        }
        return count;
    }

    public void reloadSkills() {
        loadSkills();

        if (mSkillChangeListener != null) {
            mHandler.post(() -> mSkillChangeListener.onSkillsReloaded());
        }

        if (DEBUG) {
            Log.d(TAG, "Skills reloaded");
        }
    }

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
                Log.e(TAG, "Failed to export skill: " + skill.id, e);
            }
        }

        try {
            root.put("skills", skillsArray);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to create export JSON", e);
        }

        return root.toString(2);
    }

    public static class Skill {
        public String id;
        public String name;
        public String description;
        public String[] trigger;
        public String[] permission;
        public boolean enabled;
        public JSONObject config;
        public long updatedAt;

        @Override
        public String toString() {
            return "Skill{id='" + id + "', name='" + name + "', enabled=" + enabled + 
                    ", triggers=" + (trigger != null ? trigger.length : 0) + "}";
        }
    }
}
