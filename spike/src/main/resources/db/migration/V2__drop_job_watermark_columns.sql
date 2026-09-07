-- V2__drop_job_watermark_columns.sql
-- 删除 job 表残流水位列（watermark_type / watermark_column）。
--
-- 背景：水位（水位策略/水位列）已迁至 READ_SQL 动作配置（见 docs/action-chain-design.md），
--       V1 起不再创建这两列；本迁移针对 745bb99 之前初始化的存量库（旧 init.sql 曾带这两列）。
-- 幂等：列不存在时跳过（information_schema 判断），新建库与已手工清理过的库执行均为 no-op。

SET @db := DATABASE();

SET @sql := IF(
    EXISTS (SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'job' AND COLUMN_NAME = 'watermark_type'),
    'ALTER TABLE job DROP COLUMN watermark_type',
    'DO 0'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
    EXISTS (SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'job' AND COLUMN_NAME = 'watermark_column'),
    'ALTER TABLE job DROP COLUMN watermark_column',
    'DO 0'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
