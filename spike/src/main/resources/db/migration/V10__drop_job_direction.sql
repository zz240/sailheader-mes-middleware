-- V10__drop_job_direction.sql
-- 删除 job.direction（#159）：方向只是一个业务标签，不参与路由入口、传输、调度、校验、
-- 历史与筛选；创建任务时必填、保存之后没有任何消费者。V1 的建表语句不能回头改，
-- 这里补一次幂等删除。
--
-- 注意：mapping_history.direction 是另一张表的列（映射历史的键），不在本次范围。

SET @db := DATABASE();

SET @sql := IF(
    EXISTS (SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'job' AND COLUMN_NAME = 'direction'),
    'ALTER TABLE job DROP COLUMN direction',
    'DO 0'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
