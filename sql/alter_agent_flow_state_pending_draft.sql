ALTER TABLE `yl_agent_flow_state`
    ADD COLUMN `pending_draft_id` BIGINT NULL DEFAULT NULL
        COMMENT 'Plan draft bound to the pending confirmation'
        AFTER `pending_payload`;
