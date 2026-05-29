
create database if not exists yl_database;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;



-- yl_user
DROP TABLE IF EXISTS `yl_user`;
CREATE TABLE `yl_user`
(
    `user_id` bigint NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `user_code` varchar(50)  NOT NULL COMMENT '用户编码(6位累加)',
    `user_name` varchar(50)  NOT NULL COMMENT '用户昵称',
    `phone` varchar(20)  NOT NULL COMMENT '手机号',
    `password` varchar(100)  NOT NULL COMMENT '密码（加密）',
    `avatar` varchar(500)  NULL DEFAULT NULL COMMENT '头像',
    `gender` tinyint(1) NULL DEFAULT 0 COMMENT '性别：0-未知 1-男 2-女',
    `birthday` datetime NULL DEFAULT NULL COMMENT '生日',

    `status` tinyint(1) NOT NULL DEFAULT 1 COMMENT '用户状态：0-待审核 1-启用 2-下架 5-禁用',
    `deleted_flag` tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除状态：0-未删除 1-已删除',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`user_id`),
    UNIQUE KEY `uk_user_code` (`user_code`),
    UNIQUE KEY `uk_phone` (`phone`),

    KEY `idx_deleted_status_createtime` (`deleted_flag`, `status`, `create_time`)
) ENGINE = InnoDB AUTO_INCREMENT = 36 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户表' ROW_FORMAT = Dynamic;







DROP TABLE IF EXISTS `yl_todo`;

CREATE TABLE `yl_todo`
(
    `todo_id`   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '待办ID',
    `user_id`   BIGINT       NOT NULL COMMENT '用户ID',

    `title`     VARCHAR(255) NOT NULL COMMENT '待办标题（目标名称）',
    `color`     VARCHAR(20)  DEFAULT '#5c4b37' COMMENT '高亮颜色（hex）',

    `start_date` DATE        NOT NULL COMMENT '开始日期',
    `end_date`   DATE        NOT NULL COMMENT '结束日期（距开始日期最多180天）',
    `week_days`  VARCHAR(30) NOT NULL DEFAULT '1,2,3,4,5,6,7' COMMENT '每周执行日：1=周一 7=周日，逗号分隔',

    `priority`   TINYINT(1)  DEFAULT 1 COMMENT '优先级：1低 2中 3高',
    `status`     TINYINT(1)  DEFAULT 0 COMMENT '状态：0未完成 1已完成',
    `voice_text` TEXT        DEFAULT NULL COMMENT '原始语音识别文本',
    `deleted_flag` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '删除状态',
    `create_time` DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`todo_id`),
    KEY `idx_user_status` (`user_id`, `status`),
    CONSTRAINT `fk_todo_user`
        FOREIGN KEY (`user_id`)
            REFERENCES `yl_user` (`user_id`)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='待办表（目标）';


-- yl_todo_date（待办-日期关联表，支持一个目标跨多天）
--
-- 【功能1：获取某天所有待办】
--   查询某一天的所有待办：按 todo_date 查询，JOIN yl_todo 拿 title/color，
--   返回每个待办的 day_content（当日具体任务），前端展示在日历格子点击后的详情面板中。
--
-- 【功能2：获取某个月每天的待办数量（日历小圆点）】
--   SELECT todo_date, COUNT(*) AS cnt
--   FROM yl_todo_date td
--   JOIN yl_todo t ON td.todo_id = t.todo_id
--   WHERE t.user_id = ? AND t.deleted_flag = 0 AND td.todo_date BETWEEN '2026-06-01' AND '2026-06-30'
--   GROUP BY todo_date
--   返回每一天有几个待办，前端在日历格子上渲染对应数量的小圆点。
--
-- 【功能3：点击左侧待办 → 日历高亮该目标的所有日期】
--   查询该 todo_id 的所有 todo_date，前端用该待办的 color 高亮这些日期格子。
--
-- 【功能4：每日任务 ≠ 待办标题】
--   yl_todo.title = "学英语"（目标名称，显示在左侧列表）
--   yl_todo_date.day_content = "背Unit3单词50个"（当天具体任务，点开日期时看到）
--   同一个目标每天可以有不同的 day_content，也可以为空（空则只显示标题）。
DROP TABLE IF EXISTS `yl_todo_date`;

CREATE TABLE `yl_todo_date`
(
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `todo_id`     BIGINT   NOT NULL COMMENT '待办ID',
    `todo_date`   DATE     NOT NULL COMMENT '执行日期',
    `day_content` TEXT     DEFAULT NULL COMMENT '当日具体任务内容（不同于待办标题，可为空）',
    `status`      TINYINT(1) DEFAULT 0 COMMENT '当天完成状态：0未完成 1已完成',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_todo_date` (`todo_id`, `todo_date`),
    KEY `idx_date` (`todo_date`),
    CONSTRAINT `fk_tododate_todo`
        FOREIGN KEY (`todo_id`)
            REFERENCES `yl_todo` (`todo_id`)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='待办日期关联表';


-- yl_daily_note（每日日记/备注）
--
-- 【功能：点击日历每一天 → 写日记/备注】
--   每个用户每天最多一条日记记录（uk_user_date 唯一约束）。
--   点击日历某天 → 前端调 GET /daily-note?date=2026-06-01
--   有记录就展示日记内容，没有就显示空编辑器。
--   保存时调 PUT /daily-note，upsert（INSERT ON DUPLICATE KEY UPDATE 或 service 层判断）。
--
--   查看某天时，前端同时请求三个接口并排展示：
--     1. GET /todo/day?date=xxx        → 该天的待办列表（来自 yl_todo_date）
--     2. GET /daily-note?date=xxx      → 该天的日记（来自 yl_daily_note）
--     3. GET /todo/month-count?year=2026&month=6 → 当月每日待办数量（日历小圆点）
DROP TABLE IF EXISTS `yl_daily_note`;

CREATE TABLE `yl_daily_note`
(
    `note_id`    BIGINT   NOT NULL AUTO_INCREMENT COMMENT '日记ID',
    `user_id`    BIGINT   NOT NULL COMMENT '用户ID',
    `note_date`  DATE     NOT NULL COMMENT '日期',
    `content`    TEXT     DEFAULT NULL COMMENT '日记内容',

    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    PRIMARY KEY (`note_id`),
    UNIQUE KEY `uk_user_date` (`user_id`, `note_date`),
    CONSTRAINT `fk_dailynote_user`
        FOREIGN KEY (`user_id`)
            REFERENCES `yl_user` (`user_id`)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='每日日记表';



-- yl_ai_dialogue（对话记录表）
DROP TABLE IF EXISTS `yl_ai_dialogue`;

CREATE TABLE `yl_ai_dialogue`
(
    `dialogue_id`   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '对话记录ID',
    `user_id`       BIGINT       NOT NULL COMMENT '用户ID',

    `session_id`    VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '会话ID，分组多轮对话',
    `role`          VARCHAR(16)  NOT NULL DEFAULT 'user' COMMENT '角色：user-用户 assistant-助手',

    `user_text`     TEXT         NULL DEFAULT NULL COMMENT '用户输入文本（语音转文字）',
    `ai_result`     TEXT         NULL DEFAULT NULL COMMENT 'AI返回文本',
    `ai_audio_url`  VARCHAR(500) NULL DEFAULT NULL COMMENT 'AI回复TTS音频URL',

    `intent`         VARCHAR(50)  NULL DEFAULT NULL COMMENT '识别意图：add_todo/delete_todo/query_todo/update_todo/chat',
    `execute_result` VARCHAR(255) NULL DEFAULT NULL COMMENT '执行结果摘要',

    `deleted_flag`  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '删除状态：0-未删除 1-已删除',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    PRIMARY KEY (`dialogue_id`),
    KEY `idx_user_session` (`user_id`, `session_id`),
    KEY `idx_user_create_time` (`user_id`, `create_time`),

    CONSTRAINT `fk_dialogue_user`
        FOREIGN KEY (`user_id`)
            REFERENCES `yl_user` (`user_id`)
)ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'AI对话记录表';

SET FOREIGN_KEY_CHECKS = 1;



