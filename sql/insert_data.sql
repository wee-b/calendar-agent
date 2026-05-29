-- 初始测试用户（密码是 BCrypt 加密的，需通过注册接口产生正确密码）
INSERT INTO `yl_user` (`user_id`,`user_code`, `user_name`, `phone`, `password`, `avatar`, `gender`, `status`)
VALUES (1,'U00001', '测试用户', '13800138000', '$2a$10$dummy_encrypted_password', NULL, 0, 1);

-- 待办（目标）
--   title      = 目标名称
--   color      = 日历高亮颜色
--   start_date = 开始日期
--   end_date   = 结束日期
--   week_days  = 每周执行天数（逗号分隔，1=周一 7=周日）
INSERT INTO `yl_todo` (`todo_id`, `user_id`, `title`, `color`, `start_date`, `end_date`, `week_days`, `priority`, `status`, `voice_text`)
VALUES (1, 1, '学英语', '#4CAF50', '2026-06-01', '2026-06-07', '1,2,3,4,5', 3, 0, '我周一到周五每天学英语'),
       (2, 1, '健身计划', '#2196F3', '2026-06-01', '2026-06-05', '1,3,5', 2, 0, '安排周一三五健身'),
       (3, 1, '项目汇报', '#FF9800', '2026-06-05', '2026-06-05', '5', 3, 1, '完成项目汇报');

-- 每日任务（由后端根据 week_days 自动计算生成，这里为测试数据）
INSERT INTO `yl_todo_date` (`todo_id`, `todo_date`, `day_content`, `status`)
VALUES (1, '2026-06-01', '背Unit1单词50个 + 跟读课文', 0),
       (1, '2026-06-02', '背Unit2单词50个 + 听力练习', 0),
       (1, '2026-06-03', '背Unit3单词50个 + 阅读理解', 0),
       (1, '2026-06-04', '复习Unit1-3单词 + 口语对话', 0),
       (1, '2026-06-05', '模拟测试 + 错题整理', 0),
       (2, '2026-06-01', '胸肌 + 三头肌训练', 0),
       (2, '2026-06-03', '背部 + 二头肌训练', 0),
       (2, '2026-06-05', '腿部 + 肩部训练', 1),
       (3, '2026-06-05', '完成PPT并邮件发送给领导', 1);

-- 每日日记
INSERT INTO `yl_daily_note` (`user_id`, `note_date`, `content`)
VALUES (1, '2026-06-01', '六月第一天，制定了新的学习计划，加油！'),
       (1, '2026-06-03', '健身时遇到一个好教练，给了我一些建议。');

-- AI 对话记录
INSERT INTO `yl_ai_dialogue` (`user_id`, `session_id`, `role`, `user_text`, `ai_result`, `intent`, `execute_result`)
VALUES (1, 'sess-001', 'user', '从六月一号开始，周一到周五每天学英语', NULL, 'add_todo', '已创建待办'),
       (1, 'sess-001', 'assistant', NULL, '好的，已创建目标"学英语"，6月1日至7日周一到周五，共5天。', 'add_todo', 'success');
