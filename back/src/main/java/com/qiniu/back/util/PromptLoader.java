package com.qiniu.back.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 从 resources/prompt/ 目录加载提示词文件
 */
@Slf4j
public class PromptLoader {

    private PromptLoader() {}

    public static String load(String fileName) {
        try {
            ClassPathResource resource = new ClassPathResource("prompt/" + fileName);
            return resource.getContentAsString(StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            log.error("加载提示词文件失败: {}", fileName, e);
            return "";
        }
    }
}
