-- 仅对旧版 yl_agent_flow_state 执行一次。发布前停止旧版应用写入。
-- 新建数据库直接使用 tables.sql；不要在现有数据库执行其中的 DROP TABLE。
ALTER TABLE yl_agent_flow_state
    ADD COLUMN image_instruction TEXT NULL COMMENT 'Accumulated image generation feedback',
    ADD COLUMN processing TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'Claimed or uncertain write',
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT 'Optimistic conversation version',
    MODIFY COLUMN stage VARCHAR(32) NOT NULL DEFAULT 'CHAT' COMMENT 'CHAT/PLAN/EXECUTE/IMAGE';

-- 先保留旧 PROCESSING 的占用状态，禁止迁移后意外重复执行。
UPDATE yl_agent_flow_state SET processing = 1 WHERE stage = 'PROCESSING';
UPDATE yl_agent_flow_state
SET stage = CASE
    WHEN stage = 'IDLE' THEN 'CHAT'
    WHEN stage = 'WAIT_FEEDBACK' THEN 'PLAN'
    WHEN stage = 'WAIT_CONFIRM' AND next_agent = 'PLANNER' THEN 'PLAN'
    WHEN stage = 'WAIT_CONFIRM' AND next_agent IN ('CHAT', 'EXECUTOR') THEN 'EXECUTE'
    WHEN stage = 'PROCESSING' AND pending_draft_id IS NOT NULL THEN 'PLAN'
    WHEN stage = 'PROCESSING' AND next_agent = 'PLANNER' THEN 'PLAN'
    WHEN stage = 'PROCESSING' THEN 'EXECUTE'
    ELSE stage
END;

-- 发布前检查结果：未知旧阶段不自动解释为空闲，必须核对记录后再处理。
SELECT stage, processing, COUNT(*) FROM yl_agent_flow_state GROUP BY stage, processing;
