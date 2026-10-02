-- 仅对旧版 yl_ai_dialogue 执行一次，MySQL 8.0。
-- 先停止所有 Java/Python 消息写入、删除和摘要任务；DDL 不支持整体事务回滚。
-- 不要使用 mysql --force 忽略错误。新建数据库直接使用 tables.sql。
-- 旧 Java 读写逻辑不能直接使用迁移后的表，配套工作见 docs/10-java-chat-history-migration.md。
-- 本脚本保留完整旧表副本，核验及回滚窗口结束后人工清理。
SET NAMES utf8mb4;

DELIMITER $$
CREATE PROCEDURE migrate_chat_session_content_20261002()
BEGIN
    -- 不猜测异常记录所属角色；应先整理异常数据后重新执行。
    IF EXISTS (
        SELECT 1 FROM yl_ai_dialogue
        WHERE role NOT IN ('user', 'assistant') OR TRIM(session_id) = ''
           OR (role = 'user' AND COALESCE(ai_result, '') <> '')
           OR (role = 'assistant' AND COALESCE(user_text, '') <> '')
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Unexpected role, empty session ID or text in opposite role column; repair before migration';
    END IF;

    CREATE TABLE yl_ai_dialogue_backup_20261002 LIKE yl_ai_dialogue;
    INSERT INTO yl_ai_dialogue_backup_20261002 SELECT * FROM yl_ai_dialogue;

    CREATE TABLE yl_ai_session (
        id BIGINT NOT NULL AUTO_INCREMENT,
        user_id BIGINT NOT NULL,
        session_id VARCHAR(64) NOT NULL,
        title VARCHAR(128) NULL COMMENT '会话标题，NULL表示尚未生成',
        last_message_id BIGINT NULL,
        last_message_time DATETIME NULL,
        message_count INT NOT NULL DEFAULT 0 COMMENT '有效消息条数，非轮数',
        deleted_flag TINYINT(1) NOT NULL DEFAULT 0,
        create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        PRIMARY KEY (id),
        UNIQUE KEY uk_user_session (user_id, session_id),
        KEY idx_user_recent (user_id, deleted_flag, last_message_time, id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI会话表';

    ALTER TABLE yl_ai_dialogue ADD COLUMN content TEXT NULL COMMENT '消息正文' AFTER role;
    -- 旧空正文转为空字符串；ID、角色、创建时间和其他消息字段全部保留。
    UPDATE yl_ai_dialogue
    SET content = COALESCE(CASE WHEN role = 'user' THEN user_text ELSE ai_result END, ''),
        update_time = update_time;

    INSERT INTO yl_ai_session (
        user_id, session_id, title, last_message_id, last_message_time,
        message_count, deleted_flag, create_time, update_time
    )
    SELECT grouped.user_id, grouped.session_id,
           CASE WHEN first_user.dialogue_id IS NULL THEN NULL
                WHEN CHAR_LENGTH(first_user.content) > 30 THEN CONCAT(LEFT(first_user.content, 30), '...')
                ELSE first_user.content END,
           latest.dialogue_id, latest.create_time, grouped.message_count,
           CASE WHEN grouped.message_count = 0 THEN 1 ELSE 0 END,
           grouped.create_time, grouped.update_time
    FROM (
        SELECT user_id, session_id,
               MIN(CASE WHEN deleted_flag = 0 AND role = 'user' THEN dialogue_id END) AS first_user_id,
               MAX(CASE WHEN deleted_flag = 0 THEN dialogue_id END) AS last_message_id,
               SUM(CASE WHEN deleted_flag = 0 THEN 1 ELSE 0 END) AS message_count,
               MIN(create_time) AS create_time, MAX(update_time) AS update_time
        FROM yl_ai_dialogue
        GROUP BY user_id, session_id
    ) grouped
    LEFT JOIN yl_ai_dialogue first_user ON first_user.dialogue_id = grouped.first_user_id
    LEFT JOIN yl_ai_dialogue latest ON latest.dialogue_id = grouped.last_message_id;

    IF (SELECT COUNT(*) FROM yl_ai_dialogue) <>
       (SELECT COUNT(*) FROM yl_ai_dialogue_backup_20261002)
       OR (SELECT COUNT(*) FROM yl_ai_dialogue WHERE deleted_flag = 0) <>
          (SELECT COALESCE(SUM(message_count), 0) FROM yl_ai_session) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Message/session counts do not match; keep backup and inspect';
    END IF;

    ALTER TABLE yl_ai_dialogue
        MODIFY COLUMN content TEXT NOT NULL COMMENT '消息正文',
        MODIFY COLUMN role VARCHAR(16) NOT NULL COMMENT '角色：user-用户 assistant-助手',
        MODIFY COLUMN session_id VARCHAR(64) NOT NULL COMMENT '会话ID',
        DROP COLUMN user_text, DROP COLUMN ai_result,
        DROP COLUMN intent, DROP COLUMN execute_result,
        DROP INDEX idx_user_session, DROP INDEX idx_user_create_time,
        ADD INDEX idx_history (user_id, session_id, deleted_flag, dialogue_id);
END$$
DELIMITER ;

CALL migrate_chat_session_content_20261002();
DROP PROCEDURE migrate_chat_session_content_20261002;

SELECT COUNT(*) AS message_count FROM yl_ai_dialogue;
SELECT COUNT(*) AS session_count, COALESCE(SUM(message_count), 0) AS active_message_count
FROM yl_ai_session;
