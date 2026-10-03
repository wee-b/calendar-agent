package com.qiniu.back.module.mcp.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.function.Function;

/**
 * 单个 MCP 工具的完整定义：元数据 + 执行逻辑
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpToolDefinition {

    /** 工具名称（唯一标识） */
    private String name;

    /** 工具描述 */
    private String description;

    private boolean readOnly;
    private boolean idempotent;
    private boolean parallelSafe;
    private boolean confirmationRequired;
    private long timeoutMs;

    /** MCP inputSchema（等价于 OpenAI function parameters） */
    private Map<String, Object> inputSchema;

    /** 执行函数：输入 arguments JSON Map，输出可序列化的结构化结果 */
    private Function<Map<String, Object>, Object> executor;

}
