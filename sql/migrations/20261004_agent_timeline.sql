-- Run once before deploying the AI service version that writes Agent timelines.
ALTER TABLE `yl_ai_dialogue`
    ADD COLUMN `agent_steps` JSON NULL COMMENT '该助手回复的脱敏 Agent 过程时间线'
    AFTER `response_time_ms`;
