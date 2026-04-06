package com.android.server.ai;

import android.os.Bundle;

import org.json.JSONObject;

/**
 * McpTool - MCP 工具描述类
 *
 * <p>用于存储从 MCP Server 获取的工具元数据，包括：
 * <ul>
 *   <li>name - 工具唯一名称</li>
 *   <li>description - 工具功能描述</li>
 *   <li>inputSchema - 输入参数的 JSON Schema 定义</li>
 * </ul>
 */
public class McpTool {
    private static final String TAG = "McpTool";

    /** 工具唯一名称 */
    public final String name;

    /** 工具功能描述，用于 AI 模型理解 */
    public final String description;

    /** 输入参数的 JSON Schema 定义 */
    public final JSONObject inputSchema;

    /**
     * 创建 MCP 工具描述
     * @param name 工具名称
     * @param description 工具描述
     * @param inputSchema 输入参数 Schema
     */
    public McpTool(String name, String description, JSONObject inputSchema) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    /**
     * 从 Bundle 创建 MCP 工具
     * @param bundle 包含工具信息的 Bundle
     */
    public McpTool(Bundle bundle) {
        this.name = bundle.getString("name", "");
        this.description = bundle.getString("description", "");
        JSONObject schema = null;
        try {
            String schemaStr = bundle.getString("inputSchema", "{}");
            schema = new JSONObject(schemaStr);
        } catch (Exception e) {
            // 使用默认空 Schema
        }
        this.inputSchema = (schema != null) ? schema : new JSONObject();
    }

    /**
     * 转换为 Bundle
     * @return 包含工具信息的 Bundle
     */
    public Bundle toBundle() {
        Bundle bundle = new Bundle();
        bundle.putString("name", name);
        bundle.putString("description", description);
        bundle.putString("inputSchema", inputSchema.toString());
        return bundle;
    }

    @Override
    public String toString() {
        return "McpTool{name='" + name + "', description='" + description + "'}";
    }
}
