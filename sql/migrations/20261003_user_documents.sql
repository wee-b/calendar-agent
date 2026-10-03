-- User uploaded source files and the chunks indexed in Qdrant.
CREATE TABLE IF NOT EXISTS `yl_file` (
  `file_id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `file_name` VARCHAR(255) NOT NULL,
  `content_type` VARCHAR(100) NOT NULL,
  `size_bytes` BIGINT NOT NULL,
  `sha256` CHAR(64) NOT NULL,
  `object_key` VARCHAR(500) NOT NULL,
  `status` VARCHAR(20) NOT NULL DEFAULT 'uploading',
  `error_message` VARCHAR(500) NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`file_id`),
  UNIQUE KEY `uk_user_sha256` (`user_id`, `sha256`),
  KEY `idx_user_status` (`user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户上传文件';

CREATE TABLE IF NOT EXISTS `yl_document` (
  `document_id` BIGINT NOT NULL AUTO_INCREMENT,
  `file_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `chunk_index` INT NOT NULL,
  `section` VARCHAR(500) NOT NULL,
  `content` TEXT NOT NULL,
  `content_hash` CHAR(64) NOT NULL,
  `vector_id` CHAR(36) NOT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`document_id`),
  UNIQUE KEY `uk_file_chunk` (`file_id`, `chunk_index`),
  UNIQUE KEY `uk_vector_id` (`vector_id`),
  KEY `idx_user_file` (`user_id`, `file_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='上传文档片段';

-- 对已执行旧版迁移的数据库同步默认值；已有 ready/failed 文件状态保持不变。
ALTER TABLE `yl_file` MODIFY COLUMN `status` VARCHAR(20) NOT NULL DEFAULT 'uploading';
