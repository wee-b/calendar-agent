-- 让 yl_file 同时记录知识库文档与生成图片，图片不参与文档去重。
ALTER TABLE `yl_file`
  ADD COLUMN `file_kind` VARCHAR(20) NOT NULL DEFAULT 'document' AFTER `user_id`,
  ADD COLUMN `dedupe_key` CHAR(64) NULL AFTER `sha256`,
  ADD COLUMN `object_bucket` VARCHAR(63) NOT NULL DEFAULT 'calendar-documents' AFTER `object_key`;

UPDATE `yl_file` SET `dedupe_key` = `sha256` WHERE `file_kind` = 'document';

ALTER TABLE `yl_file`
  DROP INDEX `uk_user_sha256`,
  ADD UNIQUE KEY `uk_user_dedupe` (`user_id`, `dedupe_key`);
