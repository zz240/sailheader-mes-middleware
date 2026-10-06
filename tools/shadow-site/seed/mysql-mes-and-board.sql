-- MES 侧库（替身，MySQL 版）+ 本地看板库
--
-- 为什么是 MySQL：网关今天只认 MySQL（ADR-0008 / DataSourceManager），
-- MES 侧先给 MySQL 版把 A2/A3 的「轮询 MES 库」这条路跑通；等 SQL Server 支持落地，
-- 同一份表结构换到 SQL Server 上就是 A2/A3 的另一种现场形态。
--
-- 跑法（在仓库根；建库要 root，跑完给 gateway 授权）：
--   docker cp tools/shadow-site/seed/mysql-mes-and-board.sql edge-gateway-mysql:/tmp/seed.sql
--   docker exec edge-gateway-mysql sh -c "mysql -uroot -proot --default-character-set=utf8mb4 < /tmp/seed.sql"

CREATE DATABASE IF NOT EXISTS mes_db   DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS board_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

GRANT ALL PRIVILEGES ON mes_db.*   TO 'gateway'@'%';
GRANT ALL PRIVILEGES ON board_db.* TO 'gateway'@'%';
FLUSH PRIVILEGES;

/* ================= MES 侧：A2 主数据源 ================= */
USE mes_db;

CREATE TABLE IF NOT EXISTS mes_item (
    FNumber     VARCHAR(64)  NOT NULL PRIMARY KEY,     -- 幂等键
    FName       VARCHAR(128),
    FSpec       VARCHAR(255),
    FUnit       VARCHAR(16),
    FMaterial   VARCHAR(64),
    FVersion    VARCHAR(32),
    FModifyDate DATETIME     NOT NULL                  -- 水位列（TIMESTAMP 刷量）
);

CREATE TABLE IF NOT EXISTS mes_bom_head (
    FBOMNo      VARCHAR(64) NOT NULL PRIMARY KEY,
    FMoldNo     VARCHAR(64),
    FVersion    VARCHAR(32),
    FModifyDate DATETIME    NOT NULL
);

CREATE TABLE IF NOT EXISTS mes_bom_line (
    FBOMNo      VARCHAR(64) NOT NULL,
    FSeq        INT         NOT NULL,
    FItemNumber VARCHAR(64),
    FQty        DECIMAL(18,4),
    FUnit       VARCHAR(16),
    PRIMARY KEY (FBOMNo, FSeq)
);

/* ================= MES 侧：A3 工单源 ================= */
CREATE TABLE IF NOT EXISTS mes_work_order (
    FInterID    BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,  -- 水位列（SEQUENCE 增量）
    FBillNo     VARCHAR(64) NOT NULL UNIQUE,
    FItemNumber VARCHAR(64),
    FQty        DECIMAL(18,4),
    FPlanStart  DATETIME,
    FPlanEnd    DATETIME,
    FStatus     VARCHAR(16),
    FModifyDate DATETIME
);

CREATE TABLE IF NOT EXISTS mes_work_order_routing (
    FBillNo     VARCHAR(64) NOT NULL,
    FSeq        INT         NOT NULL,
    FOpCode     VARCHAR(32),
    FWorkCenter VARCHAR(64),
    FStdHour    DECIMAL(18,2),
    PRIMARY KEY (FBillNo, FSeq)
);

/* ---------- MES 侧造数（幂等） ---------- */
DELETE FROM mes_bom_line         WHERE FBOMNo  LIKE 'BOM-SH-%';
DELETE FROM mes_bom_head         WHERE FBOMNo  LIKE 'BOM-SH-%';
DELETE FROM mes_work_order_routing WHERE FBillNo LIKE 'WO-SH-%';
DELETE FROM mes_work_order       WHERE FBillNo LIKE 'WO-SH-%';
DELETE FROM mes_item             WHERE FNumber LIKE 'IT-SH-%';

INSERT INTO mes_item (FNumber, FName, FSpec, FUnit, FMaterial, FVersion, FModifyDate) VALUES
    ('IT-SH-001', '前保险杠模具型腔', '1450x850x420', 'PCS', '718H',  'V1.2', '2026-09-01 08:00:00'),
    ('IT-SH-002', '后保险杠模具型芯', '1380x820x400', 'PCS', 'SKD61', 'V1.0', '2026-09-01 08:00:00'),
    ('IT-SH-003', '滑块组件',         'SPEC-A-11',    'SET', 'SKD61', 'V1.1', '2026-09-02 09:30:00'),
    ('IT-SH-004', '热流道喷嘴',       'HN-22',        'PCS', 'S136',  'V1.0', '2026-09-02 09:30:00'),
    ('IT-SH-005', '顶针 φ6x200',      'EJ-6-200',     'PCS', 'SKD61', 'V1.0', '2026-09-03 10:15:00');

INSERT INTO mes_bom_head (FBOMNo, FMoldNo, FVersion, FModifyDate) VALUES
    ('BOM-SH-0001', 'MD-SH-1001', 'V1.2', '2026-09-03 11:00:00'),
    ('BOM-SH-0002', 'MD-SH-1002', 'V1.0', '2026-09-04 15:20:00');

INSERT INTO mes_bom_line (FBOMNo, FSeq, FItemNumber, FQty, FUnit) VALUES
    ('BOM-SH-0001', 1, 'IT-SH-001', 1.0000, 'PCS'),
    ('BOM-SH-0001', 2, 'IT-SH-002', 1.0000, 'PCS'),
    ('BOM-SH-0001', 3, 'IT-SH-003', 4.0000, 'SET'),
    ('BOM-SH-0001', 4, 'IT-SH-005', 24.0000, 'PCS'),
    ('BOM-SH-0002', 1, 'IT-SH-002', 1.0000, 'PCS'),
    ('BOM-SH-0002', 2, 'IT-SH-004', 8.0000, 'PCS');

INSERT INTO mes_work_order (FInterID, FBillNo, FItemNumber, FQty, FPlanStart, FPlanEnd, FStatus, FModifyDate) VALUES
    (1, 'WO-SH-0001', 'IT-SH-001', 1.0000, '2026-09-05 08:00:00', '2026-09-20 18:00:00', 'RELEASED', '2026-09-04 17:00:00'),
    (2, 'WO-SH-0002', 'IT-SH-002', 1.0000, '2026-09-06 08:00:00', '2026-09-22 18:00:00', 'RELEASED', '2026-09-05 09:00:00'),
    (3, 'WO-SH-0003', 'IT-SH-003', 4.0000, '2026-09-08 08:00:00', '2026-09-18 18:00:00', 'RELEASED', '2026-09-06 10:00:00');

INSERT INTO mes_work_order_routing (FBillNo, FSeq, FOpCode, FWorkCenter, FStdHour) VALUES
    ('WO-SH-0001', 10, 'CNC',   'CNC-01',   36.00),
    ('WO-SH-0001', 20, 'EDM',   'EDM-02',   24.00),
    ('WO-SH-0001', 30, 'WEDM',  'WEDM-01',  16.00),
    ('WO-SH-0001', 40, 'GRIND', 'GRIND-01',  8.00),
    ('WO-SH-0002', 10, 'CNC',   'CNC-03',   40.00),
    ('WO-SH-0002', 20, 'EDM',   'EDM-01',   28.00),
    ('WO-SH-0002', 30, 'WEDM',  'WEDM-01',  12.00),
    ('WO-SH-0003', 10, 'MILL',  'MILL-02',  20.00),
    ('WO-SH-0003', 20, 'HEAT',  'HEAT-01',   6.00),
    ('WO-SH-0003', 30, 'GRIND', 'GRIND-01', 10.00);

/* ================= 本地看板库（B1 落点） ================= */
USE board_db;

CREATE TABLE IF NOT EXISTS dev_metric (
    metric_key VARCHAR(128) NOT NULL PRIMARY KEY,   -- device_no:point:ts（组合幂等键，展平成一列）
    device_no  VARCHAR(32)  NOT NULL,
    point      VARCHAR(32)  NOT NULL,
    ts         DATETIME     NOT NULL,
    value      DECIMAL(18,4),
    quality    VARCHAR(8),                          -- GOOD / BAD / STALE
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS dev_alarm (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_no  VARCHAR(32),
    point      VARCHAR(32),
    level      VARCHAR(8),
    message    VARCHAR(255),
    ts         DATETIME,
    cleared    TINYINT DEFAULT 0
);

/* A2 「头行分表」落点（#143 真机验收）：一份报文 -> 头一行 + 子 N 行（走 SPLIT 拆出去逐条落库）。
   放这里是因为**新机器上任务创建时目标表就得存在**（`verify-scenario.ps1` 里那份 `CREATE ... IF NOT EXISTS`
   只是让那一段能单独跑，两边**逐列保持一致**；列型与 `verify-split-140.ps1` 里那份相同）。 */
CREATE TABLE IF NOT EXISTS bom_head_t (
    FBOMNo     VARCHAR(64) NOT NULL PRIMARY KEY,   -- 幂等键
    FMoldNo    VARCHAR(64),
    FVersion   VARCHAR(32),
    FLineCount INT,
    FBatchId   VARCHAR(64),
    FModifyDate DATETIME
);

CREATE TABLE IF NOT EXISTS bom_line_t (
    FLineKey        VARCHAR(128) NOT NULL PRIMARY KEY,  -- FBOMNo#FSeq（CONCAT 展平）
    FBOMNo          VARCHAR(64),
    FSeq            INT,
    FItemNumber     VARCHAR(64),
    FQty            DECIMAL(18,4),
    FUnit           VARCHAR(16),
    FLineCount      INT,
    -- 这三格与 verify-split-140.ps1 的 DDL 逐列一致（批次戳落在**消息历史**上，落点表里留空）
    FBatchId        VARCHAR(64),
    FBatchSeq       INT,
    FParentMessageId VARCHAR(64)
);

SELECT 'mes_item' AS T, COUNT(*) AS cnt FROM mes_db.mes_item
UNION ALL SELECT 'mes_bom_head', COUNT(*) FROM mes_db.mes_bom_head
UNION ALL SELECT 'mes_bom_line', COUNT(*) FROM mes_db.mes_bom_line
UNION ALL SELECT 'mes_work_order', COUNT(*) FROM mes_db.mes_work_order
UNION ALL SELECT 'mes_work_order_routing', COUNT(*) FROM mes_db.mes_work_order_routing
UNION ALL SELECT 'dev_metric', COUNT(*) FROM board_db.dev_metric;
