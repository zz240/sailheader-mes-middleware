-- V8__drop_mapping_suggester.sql
-- 移除未启用的映射建议器链路（#151）：删掉映射历史表与站点级建议器覆盖列。
--
-- 背景：建议器（rule / llm 两档）从未接进界面，只留下表与概念；整体删除后 mapping_history
-- 与 site.suggester 都不再被代码使用。V1__init_schema.sql 是历史迁移，不能回头改，
-- 所以在这里补一次性的删表删列。
--
-- 幂等：表 / 列不存在时跳过（列用 information_schema 判断，与 V2 同一套写法），重跑是 no-op。

DROP TABLE IF EXISTS mapping_history;

SET @db := DATABASE();

SET @sql := IF(
    EXISTS (SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'site' AND COLUMN_NAME = 'suggester'),
    'ALTER TABLE site DROP COLUMN suggester',
    'DO 0'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
