package com.qiniu.back.module.assistant.service;

import org.springframework.stereotype.Component;

import java.util.function.Consumer;
import java.util.function.Supplier;

@Component
public class ProgressReporter {
    public <T> T report(Consumer<String> progress, String step, Supplier<T> action) {
        send(progress, "开始：" + step);
        try {
            T result = action.get();
            send(progress, "完成：" + step);
            return result;
        } catch (RuntimeException exception) {
            send(progress, "失败：" + step);
            throw exception;
        }
    }

    public void report(Consumer<String> progress, String step, Runnable action) {
        report(progress, step, () -> { action.run(); return null; });
    }

    public void start(Consumer<String> progress, String step) { send(progress, "开始：" + step); }
    public void success(Consumer<String> progress, String step) { send(progress, "完成：" + step); }

    private void send(Consumer<String> progress, String message) {
        if (progress != null) progress.accept(message);
    }
}
