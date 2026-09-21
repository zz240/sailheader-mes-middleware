-- V4__message_step_duration.sql
-- 消息步的动作耗时（#134）：一个动作从开始执行到返回的墙钟毫秒数，落在该步上了。
--
-- 可空、**旧数据不回溯**：这一列之前的消息步没测过，写 0 会把「没测」与「测得就是 0 ms」混成
-- 一个数（页面按可空渲染：空的那一格写 `—`，不是 `0 ms`）。失败步也记——失败那一步往往正是
-- 要看耗时的那一步。
--
-- 不是什么：相邻消息步 `createdAt` 的差值是**步间隔**（含排队与开销），与动作耗时不是一个量
-- （CONTEXT.md 两条术语已分开；ADR-0016 决策 7 明确拒绝用前者近似后者）。
--
-- 幂等：列已存在时跳过（information_schema 判断），与 V2 同一个写法——手工建过列的库执行也是 no-op。
SET @db := DATABASE();

SET @sql := IF(
    EXISTS (SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'message_step' AND COLUMN_NAME = 'duration_ms'),
    'DO 0',
    'ALTER TABLE message_step ADD COLUMN duration_ms BIGINT NULL COMMENT ''动作耗时（毫秒，#134），旧数据为空'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
