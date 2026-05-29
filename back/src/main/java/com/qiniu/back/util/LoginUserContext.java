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

    public static void remove() {
        USER_HOLDER.remove();
    }
}
