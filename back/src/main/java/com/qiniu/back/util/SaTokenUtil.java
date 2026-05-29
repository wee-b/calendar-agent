package com.qiniu.back.util;

import cn.dev33.satoken.exception.NotLoginException;

import com.qiniu.back.config.ClientTokenConfig;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 客户端Token工具类
 * 修复点：
 * 1. 核心方法改为静态方法，符合工具类使用习惯
 * 2. 增加clientTokenLogic非空校验，避免NPE
 * 3. 优化日志打印（脱敏Token），降低安全风险
 * 4. 安全的类型转换，避免ClassCastException
 * 5. 调整方法访问权限，开放必要的功能
 */
@Component
@Slf4j
public class SaTokenUtil {


    // 1. 注入Spring托管的ClientTokenConfig实例（关键：避免手动new）
    @Autowired
    private ClientTokenConfig clientTokenConfig;

    // 2. 静态变量：供工具类静态方法使用
    private static ClientTokenConfig clientTokenLogic;

    /**
     * 初始化：将Spring注入的实例赋值给静态变量
     * PostConstruct注解：在Bean初始化完成后执行
     */
    @PostConstruct
    public void initStpLogic() {
        // 直接使用Spring注入的实例，而非手动new
        clientTokenLogic = this.clientTokenConfig;

        // 打印配置信息，验证初始化结果
        log.info("StpLogic实例初始化完成：");
        log.info("ClientToken - token-name={}, active-timeout={}, timeout={}",
                clientTokenLogic.getClientTokenConfig().getTokenName(),
                clientTokenLogic.getClientTokenConfig().getActiveTimeout(),
                clientTokenLogic.getClientTokenConfig().getTimeout());
    }

    // ==========================  私有工具方法  ==========================
    /**
     * 校验clientTokenLogic是否初始化完成，未初始化则抛异常
     */
    private static void checkStpLogicInitialized() {
        if (clientTokenLogic == null) {
            throw new RuntimeException("ClientTokenConfig未初始化，请检查Spring配置是否正确");
        }
    }

    /**
     * Token脱敏（只保留前6位和后4位），避免日志泄露完整Token
     */
    private static String maskToken(String token) {
        if (token == null || token.length() < 10) {
            return token;
        }
        return token.substring(0, 6) + "****" + token.substring(token.length() - 4);
    }

    /**
     * 解析NotLoginException异常信息，返回友好提示
     */
    private static String getNotLoginMsg(NotLoginException e) {
        return switch (e.getType()) {
            case NotLoginException.NOT_TOKEN -> "Token不存在";
            case NotLoginException.INVALID_TOKEN -> "Token无效";
            case NotLoginException.TOKEN_TIMEOUT -> "Token已过期";
            case NotLoginException.BE_REPLACED -> "Token已被顶下线";
            case NotLoginException.KICK_OUT -> "Token已被踢下线";
            default -> "Token未登录";
        };
    }

    // ==========================  公开静态工具方法  ==========================
    /**
     * 从 HTTP 请求中提取 Token（完全适配 Sa-Token 框架默认的 Token 获取规则）
     * 优先级：Header > 请求参数 > Cookie
     * @param request HttpServletRequest 请求对象
     * @return Token 字符串（null 表示未获取到）
     */
    public static String getTokenFromRequest(HttpServletRequest request) {
        checkStpLogicInitialized();
        if (request == null) {
            log.warn("提取Token失败：request对象为null");
            return null;
        }

        // 1. 第一步：从请求头中获取（优先级最高，推荐前端用这种方式传递）
        String tokenName = clientTokenLogic.getClientTokenConfig().getTokenName();
        String token = request.getHeader(tokenName);
        if (token != null && !token.trim().isEmpty()) {
            return token.trim();
        }

        // 2. 第二步：从 URL 请求参数中获取（备用方式）
        token = request.getParameter(tokenName);
        if (token != null && !token.trim().isEmpty()) {
            return token.trim();
        }

        // 3. 第三步：从 Cookie 中获取（适合前后端同域场景，可选）
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (tokenName.equals(cookie.getName()) && cookie.getValue() != null) {
                    return cookie.getValue().trim();
                }
            }
        }

        // 4. 所有位置都没找到，返回 null
        log.debug("请求中未提取到Token，tokenName={}", tokenName);
        return null;
    }

    /**
     * 验证Token有效性
     * @param token 待验证的Token
     * @return true-有效，false-无效
     */
    public static boolean validateToken(String token) {
        checkStpLogicInitialized();
        if (token == null || token.trim().isEmpty()) {
            log.warn("Token验证失败：token为空");
            return false;
        }

        try {
            // 核心：使用clientTokenLogic校验，而非默认的StpUtil
            clientTokenLogic.getLoginIdByToken(token);
            return true;
        } catch (NotLoginException e) {
            log.error("Token验证失败: token={}, 原因={}", maskToken(token), getNotLoginMsg(e));
            return false;
        } catch (Exception e) {
            log.error("Token验证异常: token={}", maskToken(token), e);
            return false;
        }
    }

    /**
     * 从Token中获取原始登录ID（用户ID）
     * @param token 客户端Token
     * @return 登录ID（null表示失败）
     */
    public static Long getUserIdFromToken(String token) {
        checkStpLogicInitialized();
        if (token == null || token.trim().isEmpty()) {
            log.warn("解析Token用户ID失败：token为空");
            return null;
        }

        try {
            // 核心：使用clientTokenLogic解析
            Object loginId = clientTokenLogic.getLoginIdByToken(token);
            if (loginId == null) {
                log.warn("客户端Token已失效或不存在: token={}", maskToken(token));
                return null;
            }
            // 安全的类型转换：先判断类型再转换
            if (loginId instanceof Long) {
                return (Long) loginId;
            } else if (loginId instanceof String) {
                try {
                    return Long.valueOf((String) loginId);
                } catch (NumberFormatException e) {
                    log.error("Token中登录ID不是合法的Long类型: loginId={}, token={}", loginId, maskToken(token));
                    return null;
                }
            } else {
                log.error("Token中登录ID类型不支持: type={}, loginId={}, token={}",
                        loginId.getClass().getName(), loginId, maskToken(token));
                return null;
            }
        } catch (NotLoginException e) {
            log.warn("客户端Token无效，无法解析登录ID: token={}, 原因={}", maskToken(token), getNotLoginMsg(e));
            return null;
        } catch (Exception e) {
            log.error("解析客户端Token登录ID异常: token={}", maskToken(token), e);
            return null;
        }
    }

    /**
     * 生成客户端Token（生成前先注销该用户的旧Token，保证单Token登录）
     * @param userId 用户ID
     * @return 生成的Token
     */
    public static String generateClientToken(Long userId) {
        checkStpLogicInitialized();
        if (userId == null) {
            throw new IllegalArgumentException("生成客户端Token失败：userId不能为空");
        }

        // 关键优化：生成新Token前，先注销该用户的所有旧clientToken
        try {
            // 注销该loginId下的所有clientToken（仅影响clientTokenLogic体系）
            clientTokenLogic.logout(userId);
            log.debug("注销用户旧的客户端Token成功: userId={}", userId);
        } catch (Exception e) {
            log.warn("注销用户旧客户端Token失败（无旧Token时忽略）: userId={}", userId, e);
        }

        // 生成新Token（此时该loginId下只有这一个有效Token）
        clientTokenLogic.login(userId);
        String newToken = clientTokenLogic.getTokenValue();
        log.info("生成新客户端Token成功: userId={}, token={}", userId, maskToken(newToken));
        return newToken;
    }

    /**
     * 更新活跃时间
     */
    public void updateActiveTimeout(Long userId) {
        if (userId == null) {
            return;
        }
        clientTokenLogic.updateLastActiveToNow(userId.toString());
    }

    /**
     * 注销客户端Token
     * @param token 待注销的Token
     */
    public static void logoutClientToken(String token) {
        checkStpLogicInitialized();
        if (token == null || token.trim().isEmpty()) {
            log.warn("注销Token失败：token为空");
            return;
        }

        try {
            clientTokenLogic.logout(token);
            log.info("注销客户端Token成功: token={}", maskToken(token));
        } catch (Exception e) {
            log.error("注销客户端Token失败: token={}", maskToken(token), e);
        }
    }

    /**
     * 退出登录
     */
    public static void logoutClientTokenByUserId(Long userId) {
        checkStpLogicInitialized();
        if (userId == null) {
            throw new IllegalArgumentException("退出登录失败：userId不能为空");
        }

        // 关键优化：生成新Token前，先注销该用户的所有旧clientToken
        try {
            // 注销该loginId下的所有clientToken（仅影响clientTokenLogic体系）
            clientTokenLogic.logout(userId);
            log.debug("注销用户旧的客户端Token成功: userId={}", userId);
        } catch (Exception e) {
            log.warn("注销用户旧客户端Token失败（无旧Token时忽略）: userId={}", userId, e);
        }
    }
}