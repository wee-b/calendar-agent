-- 为历史回复保留本轮业务分发类型；已有消息保持 NULL。
ALTER TABLE `yl_ai_dialogue`
    ADD COLUMN `dispatch_type` VARCHAR(32) NULL COMMENT '助手回复的业务分发类型'
    AFTER `response_time_ms`;
