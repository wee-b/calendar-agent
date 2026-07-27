package com.qiniu.back.util;

public class ChatSessionContext {

    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();

    private ChatSessionContext() {
    }

    public static void setSessionId(String sessionId) {
        SESSION_ID.set(sessionId);
    }

    public static String getSessionId() {
        return SESSION_ID.get();
    }

    public static void remove() {
        SESSION_ID.remove();
    }
}
