-- 部署此版本 AI 服务前执行一次；旧消息保留 NULL，历史接口返回空数组。
ALTER TABLE `yl_ai_dialogue`
    ADD COLUMN `document_references` JSON NULL COMMENT '用户消息引用的文档 ID 和名称快照'
    AFTER `agent_steps`;
