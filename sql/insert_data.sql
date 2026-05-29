-- 初始测试用户
INSERT INTO `yl_user` (`user_id`,`user_code`, `user_name`, `phone`, `password`, `avatar`, `gender`, `status`)
VALUES (1,'U00001', '测试用户', '13800138000', '$2a$10$dummy_encrypted_password', NULL, 0, 1);

-- 初始测试待办
INSERT INTO `yl_todo` (`user_id`, `title`, `content`, `todo_time`, `priority`, `status`, `voice_text`)
VALUES (1, '开会', '产品评审会议，会议室A', '2026-06-01 09:00:00', 3, 0, '提醒我六月一号上午九点开会'),
       (1, '买菜', '去超市买牛奶和面包', '2026-05-30 17:00:00', 2, 0, '下午五点提醒我去买菜'),
       (1, '交报告', '提交月度工作报告给领导', '2026-05-29 18:00:00', 3, 1, '今天下午六点前交报告');

-- 初始对话记录
INSERT INTO `yl_ai_dialogue` (`user_id`, `session_id`, `role`, `user_text`, `ai_result`, `intent`, `execute_result`)
VALUES (1, 'sess-001', 'user', '提醒我六月一号上午九点开会', NULL, 'add_todo', '已添加待办：开会'),
       (1, 'sess-001', 'assistant', NULL, '好的，已为您添加6月1日上午9点的待办：开会，优先级为高。', 'add_todo', 'success'),
       (1, 'sess-002', 'user', '我明天有什么安排', NULL, 'query_todo', '查询明日待办'),
       (1, 'sess-002', 'assistant', NULL, '您明天有1个待办：下午5点-买菜。', 'query_todo', 'success');
