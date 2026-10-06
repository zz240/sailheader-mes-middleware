-- V6__snapshot_mediumtext.sql
-- 快照列容量（#141 = #127 补范围）：承载报文的**三列**从 TEXT（65535 字节）提到 MEDIUMTEXT（16777215 字节）。
--
-- 为什么必须提：一条批量报文注定超过 64 KB——「一份报告 = 一条报文」那种形状（A7BIG）实测 3000 行 / 344 KB。
-- 更糟的是装不下时的**失败方式**：动作本身已经成功，只是快照写不进去，于是这次调用在历史里变成 FAILED
-- ——目标端明明收到了，页面上却是一条失败。运行库那边有人手工把这三列改成了 MEDIUMTEXT，而迁移文件里
-- 还是 TEXT：两边口径不一致，新站点按迁移建库就会重新踩同一个坑。这一票把口径收回来。
--
-- 哪三列：message_step.data_snapshot / message_step.input_snapshot / job_run.input_snapshot。
-- error 那两列不在内：它们装的是错误文案，不是报文。
--
-- 为什么是 MEDIUMTEXT 而不是 LONGTEXT：真实报文在几百 KB 量级（344 KB 是实测最大值），MEDIUMTEXT 的
-- 16 MB 有约 48 倍余量；再大的一列也写不进去——单条语句还受服务端 max_allowed_packet 约束
-- （本仓库 dev/影子站点是默认的 64 MB），LONGTEXT 那 4 GB 是够不着的上限，只会让「一次合并能装多少条」
-- 这个上界说不清（ADR-0017 决策 8 要的是**说得出、拦得住**的界线）。容量常量跟着这一处走：
-- MessageHistoryService.SNAPSHOT_LIMIT_BYTES = 16777215。
--
-- 幂等：判断的是「这一列**已经够宽**」（mediumtext / longtext），不是「列在不在」——
--   · 手工改宽过的库上执行是 no-op（本票的一条验收项就是这个）；
--   · 已经有人改成 LONGTEXT 的库**不往回改窄**：更宽的列没道理被这一票压回来。
-- 写法与 V4 / V5 同一套（information_schema + PREPARE），列已够宽就 DO 0。
--
-- 代价：TEXT 家族改列型**不是 INSTANT**，这一步会重建表（这张表是历史流水，行会很多）。
-- 现网执行挑低峰期，或按现场习惯先建新表再换名。
SET @db := DATABASE();

SET @sql := IF(
    EXISTS (SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'message_step'
              AND COLUMN_NAME = 'data_snapshot'
              AND DATA_TYPE NOT IN ('mediumtext', 'longtext')),
    'ALTER TABLE message_step MODIFY COLUMN data_snapshot MEDIUMTEXT NULL COMMENT ''本步输出快照（报文）；上限由列型定 = MEDIUMTEXT 16 MB（#141）''',
    'DO 0'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
    EXISTS (SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'message_step'
              AND COLUMN_NAME = 'input_snapshot'
              AND DATA_TYPE NOT IN ('mediumtext', 'longtext')),
    'ALTER TABLE message_step MODIFY COLUMN input_snapshot MEDIUMTEXT NULL COMMENT ''进入本步的报文；上限由列型定 = MEDIUMTEXT 16 MB（#141）''',
    'DO 0'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
    EXISTS (SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'job_run'
              AND COLUMN_NAME = 'input_snapshot'
              AND DATA_TYPE NOT IN ('mediumtext', 'longtext')),
    'ALTER TABLE job_run MODIFY COLUMN input_snapshot MEDIUMTEXT NULL COMMENT ''本次运行的输入报文；上限由列型定 = MEDIUMTEXT 16 MB（#141）''',
    'DO 0'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
