
create database if not exists yl_database;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;


<<<<<<< HEAD
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
    `todo_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '待办ID',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',

    `title` VARCHAR(255) NOT NULL COMMENT '待办标题',
    `content` TEXT DEFAULT NULL COMMENT '待办详情',
    `todo_time` DATETIME NOT NULL COMMENT '提醒时间',

    `priority` TINYINT(1) DEFAULT 1 COMMENT '优先级：1低 2中 3高',
    `status` TINYINT(1) DEFAULT 0 COMMENT '状态：0未完成 1已完成',
    `voice_text` TEXT DEFAULT NULL COMMENT '原始语音识别文本',
    `deleted_flag` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '删除状态',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`todo_id`),
    KEY `idx_user_time` (`user_id`, `todo_time`),
    KEY `idx_status` (`status`),
    CONSTRAINT `fk_todo_user`
        FOREIGN KEY (`user_id`)
            REFERENCES `yl_user` (`user_id`)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='待办表';



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


=======
>>>>>>> 7021a3b4328a0b85361bde9cf5d6f3a88e2d8cc2
