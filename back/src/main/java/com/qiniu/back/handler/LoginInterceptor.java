package com.qiniu.back.handler;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.strategy.SaAnnotationStrategy;
import com.qiniu.back.annotation.NoNeedLogin;
import com.qiniu.back.domain.ErrorCode;
import com.qiniu.back.domain.ResponseDTO;
import com.qiniu.back.domain.user.User;
import com.qiniu.back.module.user.mapper.UserMapper;
import com.qiniu.back.util.SaTokenUtil;
import com.qiniu.back.util.ResponseUtil;
import com.qiniu.back.util.LoginUserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.lang.reflect.Method;

@Component
@Slf4j
public class LoginInterceptor implements HandlerInterceptor {

    @Autowired
    private SaTokenUtil saTokenUtil;

    @Autowired
    private UserMapper userMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. OPTIONS 放行
        if (HttpMethod.OPTIONS.toString().equals(request.getMethod())) {
            response.setStatus(HttpStatus.NO_CONTENT.value());
            return false;
        }

        // 2. 非 HandlerMethod 放行
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        Method method = ((HandlerMethod) handler).getMethod();

        // 3. @NoNeedLogin 放行
        if (method.isAnnotationPresent(NoNeedLogin.class)) {
            return true;
        }

        try {
            // 4. 提取并解析 token
            String tokenValue = SaTokenUtil.getTokenFromRequest(request);
            if (tokenValue == null || tokenValue.trim().isEmpty()) {
                ResponseUtil.write(response, ResponseDTO.error(ErrorCode.UNAUTHORIZED, "未登录"));
                return false;
            }

            Long loginId = SaTokenUtil.getUserIdFromToken(tokenValue);
            if (loginId == null) {
                ResponseUtil.write(response, ResponseDTO.error(ErrorCode.UNAUTHORIZED, "token 无效"));
                return false;
            }

            // 5. 查询用户
            User user = userMapper.selectById(loginId);
            if (user == null) {
                ResponseUtil.write(response, ResponseDTO.error(ErrorCode.UNAUTHORIZED, "用户不存在"));
                return false;
            }

            // 6. 校验 token 有效性
            if (!SaTokenUtil.validateToken(tokenValue)) {
                ResponseUtil.write(response, ResponseDTO.error(ErrorCode.UNAUTHORIZED, "登录已过期"));
                return false;
            }

            // 7. 更新活跃时间
            saTokenUtil.updateActiveTimeout(user.getUserId());

            // 8. 存入上下文，后续业务通过 SmartRequestUtil.getUser() 获取
            LoginUserContext.setUser(user);

            // 9. @SaIgnore 跳过权限校验
            if (SaAnnotationStrategy.instance.isAnnotationPresent.apply(method, SaIgnore.class)) {
                return true;
            }

            // 10. Sa-Token 注解权限校验
            SaAnnotationStrategy.instance.checkMethodAnnotation.accept(method);

        } catch (SaTokenException e) {
            int code = e.getCode();
            if (code == 11041 || code == 11051) {
                ResponseUtil.write(response, ResponseDTO.error(ErrorCode.FORBIDDEN, "没有访问权限"));
            } else if (code == 11016) {
                ResponseUtil.write(response, ResponseDTO.error(ErrorCode.UNAUTHORIZED, "登录已超时"));
            } else if (code >= 11011 && code <= 11015) {
                ResponseUtil.write(response, ResponseDTO.error(ErrorCode.UNAUTHORIZED, "登录状态无效"));
            } else {
                ResponseUtil.write(response, ResponseDTO.error(ErrorCode.BAD_REQUEST, e.getMessage()));
            }
            return false;
        } catch (Throwable e) {
            ResponseUtil.write(response, ResponseDTO.error(ErrorCode.INTERNAL_SERVER_ERROR));
            log.error("登录拦截器异常", e);
            return false;
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        LoginUserContext.remove();
    }
}
