package com.qiniu.back.util;

import com.qiniu.back.domain.user.User;

public class LoginUserContext {

    private static final ThreadLocal<User> USER_HOLDER = new ThreadLocal<>();

    public static void setUser(User user) {
        USER_HOLDER.set(user);
    }

    public static User getUser() {
        return USER_HOLDER.get();
    }

    public static Long getUserId() {
        User user = getUser();
        return user != null ? user.getUserId() : null;
    }

    /** 在异步线程中注入 userId，避免 ThreadLocal 丢失 */
    public static void setUserId(Long userId) {
        User user = new User();
        user.setUserId(userId);
        USER_HOLDER.set(user);
    }

    public static void remove() {
        USER_HOLDER.remove();
    }
}
