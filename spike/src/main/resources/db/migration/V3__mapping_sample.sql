-- V3__mapping_sample.sql
-- 字段映射编辑器里**粘进来的**那份样例落库（#119）：一格一行，按「任务 + 用途 + 归属」唯一。
--
-- 只存**原文**（sample_json），不存推出来的字段清单——清单是原文的纯函数（每次现推，与探测那条路
-- 共用同一份展开），原文还能给试算（#116）用；存两份只会互相打架。
--
-- 归属（ref_id）：SOURCE = 动作序号（0 = 任务输入，即链上没有更早的动作）；TARGET = 写目标动作 id。
-- 归属键这么定是因为探测那条路本来就有「某一步最近一次成功运行」这个语义：同一步被两个 FIELD_MAP
-- 读时共用一份，而不是各粘各的。
--
-- 探测那份（运行快照 / 连库读列）**不落库**（修订 ADR-0012 决策 5）：它随时能再取一次（来源侧是
-- message_step 的报文快照，那本来就落库；目标侧是写目标那张表的列结构），存它等于把同一份报文在库里
-- 放两遍。库里只放「只有你手上才有」的那份东西。
CREATE TABLE IF NOT EXISTS mapping_sample (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL,
    ref_id BIGINT NOT NULL,
    sample_json TEXT NOT NULL,
    updated_at DATETIME,
    CONSTRAINT uk_mapping_sample UNIQUE (job_id, kind, ref_id)
);
