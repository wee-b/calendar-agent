-- 迁移前消息表的固定样本结构，只用于隔离测试数据库。
CREATE TABLE yl_ai_dialogue (
    dialogue_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(64) NOT NULL DEFAULT '',
    role VARCHAR(16) NOT NULL DEFAULT 'user',
    user_text TEXT NULL,
    ai_result TEXT NULL,
    ai_audio_url VARCHAR(500) NULL,
    response_time_ms BIGINT NULL,
    intent VARCHAR(50) NULL,
    execute_result VARCHAR(255) NULL,
    deleted_flag TINYINT(1) NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_user_session (user_id, session_id),
    KEY idx_user_create_time (user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
