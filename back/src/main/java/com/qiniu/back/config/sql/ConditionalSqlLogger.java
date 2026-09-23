package com.qiniu.back.config.sql;

import com.p6spy.engine.logging.Category;
import com.p6spy.engine.spy.appender.P6Logger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P6Spy 日志出口。默认静默，仅在 {@code app.sql-log.enabled=true} 时输出 SQL 及耗时。
 */
public final class ConditionalSqlLogger implements P6Logger {

    private static final Logger LOG = LoggerFactory.getLogger("sql.timing");
    private static volatile boolean enabled;

    static void setEnabled(boolean enabled) {
        ConditionalSqlLogger.enabled = enabled;
    }

    @Override
    public void logSQL(int connectionId, String now, long elapsed, Category category,
                       String prepared, String sql, String url) {
        if (enabled && sql != null && !sql.isBlank()) {
            LOG.info("SQL 耗时 {} ms | {}", elapsed, sql.replaceAll("\\s+", " "));
        }
    }

    @Override
    public void logException(Exception exception) {
        if (enabled) {
            LOG.warn("SQL 执行异常", exception);
        }
    }

    @Override
    public void logText(String text) {
        if (enabled && text != null && !text.isBlank()) {
            LOG.info(text);
        }
    }

    @Override
    public boolean isCategoryEnabled(Category category) {
        return enabled;
    }
}
