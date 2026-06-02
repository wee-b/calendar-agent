package com.qiniu.back.module.chat.mcp;

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

    /** MCP inputSchema（等价于 OpenAI function parameters） */
    private Map<String, Object> inputSchema;

    /** 执行函数：输入 arguments JSON Map，输出结果字符串 */
    private Function<Map<String, Object>, String> executor;

}
