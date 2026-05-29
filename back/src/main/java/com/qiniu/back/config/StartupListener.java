package com.qiniu.back.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class StartupListener {

    @Value("${server.port:8080}")
    private int port;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        String docUrl = "http://localhost:" + port + "/doc.html";
        log.info("============================================");
        log.info("  应用启动成功！");
        log.info("  Knife4j 接口文档: {}", docUrl);
        log.info("============================================");
    }
}
