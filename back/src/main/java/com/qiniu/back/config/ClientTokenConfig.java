package com.qiniu.back.config;

import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.stp.StpLogic;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 用户端Token专属StpLogic
 */
@Component
@Slf4j // 增加日志，方便排查配置问题
public class ClientTokenConfig extends StpLogic {

    // 从配置文件读取token名称（Spring注入）
    @Value("${app.sa-token.token-name:yvli-token}")
    private String tokenName;

    // 从配置文件读取token有效期
    @Value("${app.sa-token.timeout:604800}")
    private long timeout;

    // 延迟初始化：先声明，不直接在构造方法中初始化
    private SaTokenConfig clientTokenConfig;

    // 构造方法仅做基础初始化，不依赖注入值
    public ClientTokenConfig() {
        super("client"); // 仅设置loginType，不处理其他配置
    }

    /**
     * 初始化Token配置（Spring注入完成后执行）
     * @PostConstruct：在构造方法执行后、注解注入完成后调用
     */
    @PostConstruct
    public void initTokenConfig() {
        // 1. 初始化配置对象
        clientTokenConfig = new SaTokenConfig();

        // 2. 设置配置项（此时tokenName/timeout已被Spring注入）
        clientTokenConfig.setTokenName(this.tokenName);
        clientTokenConfig.setActiveTimeout(-1); // 关闭active-timeout
        clientTokenConfig.setTimeout(this.timeout);
        clientTokenConfig.setIsConcurrent(false); // 禁止多端登录
        clientTokenConfig.setIsShare(false); // 不共用Token
        clientTokenConfig.setTokenStyle("simple-uuid"); // Token风格
        clientTokenConfig.setAutoRenew(true); // 自动续签
        clientTokenConfig.setIsReadCookie(false); // 不从Cookie读Token

        // 3. 反射绑定config字段到当前StpLogic
        try {
            java.lang.reflect.Field configField = StpLogic.class.getDeclaredField("config");
            configField.setAccessible(true);
            configField.set(this, clientTokenConfig);

            // 打印配置日志，验证注入结果
            log.info("ClientTokenConfig初始化完成：");
            log.info("token-name={}, timeout={}秒, active-timeout={}",
                    tokenName, timeout, clientTokenConfig.getActiveTimeout());
        } catch (Exception e) {
            log.error("绑定ClientToken配置失败", e);
            throw new RuntimeException("绑定短期Token配置失败", e);
        }
    }

    // 暴露配置获取方法（方便外部使用）
    public SaTokenConfig getClientTokenConfig() {
        return clientTokenConfig;
    }
}