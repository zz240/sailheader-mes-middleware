-- 客户侧 SQL Server（金蝶/用友形状）建库建表 + 造数
-- 库：CustomerERP   实例：edge-shadow-mssql（tools/shadow-site/docker-compose.yml）
--
-- 跑法（在仓库根，先 docker cp 再执行，避开 PowerShell 管道的编码问题）：
--   docker cp tools/shadow-site/seed/sqlserver-customer-erp.sql edge-shadow-mssql:/tmp/seed.sql
--   docker exec edge-shadow-mssql /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "EdgeShadow#2026" -C -f 65001 -i /tmp/seed.sql
--
-- 表与场景的对应：见 docs/design/docking-scenarios-spec.md §2.1
--   A5 源：POOrder + t_Supplier      A2 落点：t_Item / BOM / BOMChild
--   A3 落点：MFG_WorkOrder(+Routing) A4 源：MFG_Report
--   A7 源：QC_Report(+Detail)        B6 源/回写：MDM_Code
-- 造数是幂等的：先按本场景前缀删，再插；水位列用 IDENTITY_INSERT 固定值，便于核对。

IF DB_ID('CustomerERP') IS NULL
    CREATE DATABASE CustomerERP;
GO
USE CustomerERP;
GO

SET NOCOUNT ON;
GO

/* ---------- 表 ---------- */

IF OBJECT_ID('dbo.t_Supplier') IS NULL
CREATE TABLE dbo.t_Supplier (
    FItemID   BIGINT       NOT NULL PRIMARY KEY,
    FNumber   NVARCHAR(64) NOT NULL,
    FName     NVARCHAR(128) NULL
);
GO

IF OBJECT_ID('dbo.POOrder') IS NULL
CREATE TABLE dbo.POOrder (
    FInterID    BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,  -- 水位列（增量）
    FBillNo     NVARCHAR(64)  NOT NULL UNIQUE,
    FDate       DATETIME2(0)  NOT NULL,
    FSupplyID   BIGINT        NULL,
    FStatus     NVARCHAR(16)  NULL,
    FNote       NVARCHAR(255) NULL,
    FModifyDate DATETIME2(0)  NOT NULL DEFAULT SYSUTCDATETIME()
);
GO

IF OBJECT_ID('dbo.t_Item') IS NULL
CREATE TABLE dbo.t_Item (
    FNumber     NVARCHAR(64)  NOT NULL PRIMARY KEY,          -- 幂等键
    FName       NVARCHAR(128) NULL,
    FSpec       NVARCHAR(255) NULL,
    FUnit       NVARCHAR(16)  NULL,
    FMaterial   NVARCHAR(64)  NULL,
    FVersion    NVARCHAR(32)  NULL,
    FModifyDate DATETIME2(0)  NULL
);
GO

IF OBJECT_ID('dbo.BOM') IS NULL
CREATE TABLE dbo.BOM (
    FBOMNo      NVARCHAR(64) NOT NULL PRIMARY KEY,
    FMoldNo     NVARCHAR(64) NULL,
    FVersion    NVARCHAR(32) NULL,
    FModifyDate DATETIME2(0) NULL
);
GO

IF OBJECT_ID('dbo.BOMChild') IS NULL
CREATE TABLE dbo.BOMChild (
    FBOMNo      NVARCHAR(64) NOT NULL,
    FSeq        INT          NOT NULL,
    FItemNumber NVARCHAR(64) NULL,
    FQty        DECIMAL(18,4) NULL,
    FUnit       NVARCHAR(16) NULL,
    CONSTRAINT PK_BOMChild PRIMARY KEY (FBOMNo, FSeq)
);
GO

IF OBJECT_ID('dbo.MFG_WorkOrder') IS NULL
CREATE TABLE dbo.MFG_WorkOrder (
    FBillNo     NVARCHAR(64) NOT NULL PRIMARY KEY,
    FItemNumber NVARCHAR(64) NULL,
    FQty        DECIMAL(18,4) NULL,
    FPlanStart  DATETIME2(0) NULL,
    FPlanEnd    DATETIME2(0) NULL,
    FStatus     NVARCHAR(16) NULL,
    FModifyDate DATETIME2(0) NULL
);
GO

IF OBJECT_ID('dbo.MFG_WorkOrderRouting') IS NULL
CREATE TABLE dbo.MFG_WorkOrderRouting (
    FBillNo     NVARCHAR(64) NOT NULL,
    FSeq        INT          NOT NULL,
    FOpCode     NVARCHAR(32) NULL,
    FWorkCenter NVARCHAR(64) NULL,
    FStdHour    DECIMAL(18,2) NULL,
    CONSTRAINT PK_MFG_WorkOrderRouting PRIMARY KEY (FBillNo, FSeq)
);
GO

IF OBJECT_ID('dbo.MFG_Report') IS NULL
CREATE TABLE dbo.MFG_Report (
    FInterID    BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,   -- 水位列（增量）
    FBillNo     NVARCHAR(64) NOT NULL,
    FSeq        INT          NOT NULL,
    FQtyOK      DECIMAL(18,2) NULL,
    FQtyNG      DECIMAL(18,2) NULL,
    FWorker     NVARCHAR(32) NULL,
    FEquipment  NVARCHAR(64) NULL,
    FReportTime DATETIME2(0) NULL
);
GO

IF OBJECT_ID('dbo.QC_Report') IS NULL
CREATE TABLE dbo.QC_Report (
    FInterID   BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,    -- 水位列（增量）
    FBillNo    NVARCHAR(64) NOT NULL UNIQUE,
    FMoldNo    NVARCHAR(64) NULL,
    FSampleNo  NVARCHAR(32) NULL,
    FTestDate  DATETIME2(0) NULL,
    FResult    NVARCHAR(16) NULL,
    FInspector NVARCHAR(32) NULL
);
GO

IF OBJECT_ID('dbo.QC_ReportDetail') IS NULL
CREATE TABLE dbo.QC_ReportDetail (
    FDetailID BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,     -- 明细级水位列（A7 展平读用；拿头级 FInterID 当水位会在批边界漏掉同一份报告的其余明细）
    FBillNo   NVARCHAR(64) NOT NULL,
    FSeq      INT          NOT NULL,
    FItemCode NVARCHAR(64) NULL,
    FNominal  DECIMAL(18,4) NULL,
    FUpper    DECIMAL(18,4) NULL,
    FLower    DECIMAL(18,4) NULL,
    FMeasured DECIMAL(18,4) NULL,
    FJudge    NVARCHAR(8)  NULL,
    CONSTRAINT UQ_QC_ReportDetail UNIQUE (FBillNo, FSeq)
);
GO

IF OBJECT_ID('dbo.MDM_Code') IS NULL
CREATE TABLE dbo.MDM_Code (
    FID         BIGINT IDENTITY(1,1) NOT NULL,               -- 对象级水位列（B6 增量读用）
    FCode       NVARCHAR(64) NOT NULL PRIMARY KEY,           -- 幂等键
    FObjectType NVARCHAR(16) NULL,                           -- MOLD / PART / TOOL
    FObjectNo   NVARCHAR(64) NULL,
    FBatch      NVARCHAR(32) NULL,
    FStatus     NVARCHAR(16) NULL,
    FPrintTime  DATETIME2(0) NULL,
    FHandle     NVARCHAR(128) NULL,                          -- 标识服务返回，回写位
    CONSTRAINT UQ_MDM_Code_FID UNIQUE (FID)
);
GO

/* ---------- 造数（幂等） ---------- */

DELETE FROM dbo.BOMChild        WHERE FBOMNo     LIKE 'BOM-SH-%';
DELETE FROM dbo.MFG_WorkOrderRouting WHERE FBillNo LIKE 'WO-SH-%';
DELETE FROM dbo.QC_ReportDetail WHERE FBillNo    LIKE 'QC-SH-%';
DELETE FROM dbo.MDM_Code        WHERE FCode      LIKE 'SH-%';
DELETE FROM dbo.BOM            WHERE FBOMNo      LIKE 'BOM-SH-%';
DELETE FROM dbo.MFG_WorkOrder  WHERE FBillNo     LIKE 'WO-SH-%';
DELETE FROM dbo.QC_Report      WHERE FBillNo     LIKE 'QC-SH-%';
DELETE FROM dbo.MFG_Report     WHERE FBillNo     LIKE 'WO-SH-%';
DELETE FROM dbo.POOrder        WHERE FBillNo     LIKE 'PO-SH-%';
DELETE FROM dbo.t_Supplier     WHERE FNumber     LIKE 'V-SH-%';
GO

INSERT INTO dbo.t_Supplier (FItemID, FNumber, FName) VALUES
    (1, N'V-SH-001', N'东莞精铸钢材'),
    (2, N'V-SH-002', N'深圳标准件'),
    (3, N'V-SH-003', N'苏州热处理外协');
GO

-- A5 源：5 张采购单（FInterID 1..5，水位从 5 起算；后续增量插第 6 张）
SET IDENTITY_INSERT dbo.POOrder ON;
INSERT INTO dbo.POOrder (FInterID, FBillNo, FDate, FSupplyID, FStatus, FNote, FModifyDate) VALUES
    (1, N'PO-SH-0001', '2026-09-01T09:15:00', 1, N'ACTIVE',  N'718H 预硬钢 2 件',        '2026-09-01T09:15:00'),
    (2, N'PO-SH-0002', '2026-09-01T14:40:00', 2, N'ACTIVE',  N'顶针/司筒一批',            '2026-09-01T14:40:00'),
    (3, N'PO-SH-0003', '2026-09-02T10:05:00', 1, N'ACTIVE',  N'SKD61 淬火料 4 件',        '2026-09-02T10:05:00'),
    (4, N'PO-SH-0004', '2026-09-03T08:30:00', 3, N'CLOSED',  N'氮化处理外协（已到货）',    '2026-09-03T08:30:00'),
    (5, N'PO-SH-0005', '2026-09-04T16:20:00', 2, N'ACTIVE',  N'热流道配件',              '2026-09-04T16:20:00');
SET IDENTITY_INSERT dbo.POOrder OFF;
GO

-- A2/A3 落点表留空：由场景下发后核对（行数从 0 起）
-- A4 源：6 条报工（FInterID 1..6）
SET IDENTITY_INSERT dbo.MFG_Report ON;
INSERT INTO dbo.MFG_Report (FInterID, FBillNo, FSeq, FQtyOK, FQtyNG, FWorker, FEquipment, FReportTime) VALUES
    (1, N'WO-SH-0001', 10, 1.0, 0.0, N'E1021', N'CNC-01',   '2026-09-05T08:35:00'),
    (2, N'WO-SH-0001', 20, 1.0, 0.0, N'E1021', N'EDM-02',   '2026-09-05T11:10:00'),
    (3, N'WO-SH-0002', 10, 2.0, 0.0, N'E1033', N'CNC-03',   '2026-09-05T09:05:00'),
    (4, N'WO-SH-0002', 30, 1.0, 1.0, N'E1033', N'WEDM-01',  '2026-09-05T15:45:00'),
    (5, N'WO-SH-0003', 10, 4.0, 0.0, N'E1055', N'MILL-02',  '2026-09-06T09:20:00'),
    (6, N'WO-SH-0003', 40, 1.0, 0.0, N'E1055', N'GRIND-01', '2026-09-06T17:00:00');
SET IDENTITY_INSERT dbo.MFG_Report OFF;
GO

-- A7 源：2 份试模报告，每份 120 条测量明细（展平那条链用）
-- 外加 1 份 3000 条明细的报告（「整份大报文」那条链用：JSON 聚合后约 300 KB）
SET IDENTITY_INSERT dbo.QC_Report ON;
INSERT INTO dbo.QC_Report (FInterID, FBillNo, FMoldNo, FSampleNo, FTestDate, FResult, FInspector) VALUES
    (1, N'QC-SH-0001', N'MD-SH-1001', N'S01', '2026-09-06T10:00:00', N'PASS', N'Q2001'),
    (2, N'QC-SH-0002', N'MD-SH-1002', N'S01', '2026-09-07T14:30:00', N'FAIL', N'Q2002'),
    (3, N'QC-SH-0003', N'MD-SH-1003', N'S01', '2026-09-08T09:00:00', N'PASS', N'Q2003');
SET IDENTITY_INSERT dbo.QC_Report OFF;
GO

DECLARE @i INT = 1;
WHILE @i <= 120
BEGIN
    INSERT INTO dbo.QC_ReportDetail (FBillNo, FSeq, FItemCode, FNominal, FUpper, FLower, FMeasured, FJudge)
    VALUES (N'QC-SH-0001', @i,
            N'DIM-' + RIGHT('000' + CAST(@i AS NVARCHAR(3)), 3),
            100.0000 + @i, 100.0500 + @i, 99.9500 + @i,
            100.0000 + @i + CASE WHEN @i % 17 = 0 THEN 0.0800 ELSE 0.0100 END,
            CASE WHEN @i % 17 = 0 THEN N'NG' ELSE N'OK' END);
    INSERT INTO dbo.QC_ReportDetail (FBillNo, FSeq, FItemCode, FNominal, FUpper, FLower, FMeasured, FJudge)
    VALUES (N'QC-SH-0002', @i,
            N'DIM-' + RIGHT('000' + CAST(@i AS NVARCHAR(3)), 3),
            50.0000 + @i, 50.0300 + @i, 49.9700 + @i,
            50.0000 + @i + CASE WHEN @i % 5 = 0 THEN 0.0600 ELSE 0.0050 END,
            CASE WHEN @i % 5 = 0 THEN N'NG' ELSE N'OK' END);
    SET @i = @i + 1;
END
GO

-- 「整份大报文」那条链的源：3000 条明细（CMM 点位规模），交给 FOR JSON PATH 聚合成一列
DECLARE @k INT = 1;
WHILE @k <= 3000
BEGIN
    INSERT INTO dbo.QC_ReportDetail (FBillNo, FSeq, FItemCode, FNominal, FUpper, FLower, FMeasured, FJudge)
    VALUES (N'QC-SH-0003', @k,
            N'DIM-' + RIGHT('0000' + CAST(@k AS NVARCHAR(4)), 4),
            200.0000 + @k, 200.0500 + @k, 199.9500 + @k,
            200.0000 + @k + CASE WHEN @k % 23 = 0 THEN 0.0700 ELSE 0.0080 END,
            CASE WHEN @k % 23 = 0 THEN N'NG' ELSE N'OK' END);
    SET @k = @k + 1;
END
GO

-- B6 源：20 个待赋码对象
DECLARE @j INT = 1;
WHILE @j <= 20
BEGIN
    INSERT INTO dbo.MDM_Code (FCode, FObjectType, FObjectNo, FBatch, FStatus, FPrintTime) VALUES
        (N'SH-CODE-' + RIGHT('0000' + CAST(@j AS NVARCHAR(4)), 4),
         CASE WHEN @j % 3 = 0 THEN N'TOOL' WHEN @j % 2 = 0 THEN N'PART' ELSE N'MOLD' END,
         CASE WHEN @j % 3 = 0 THEN N'T-SH-' WHEN @j % 2 = 0 THEN N'P-SH-' ELSE N'MD-SH-' END
             + RIGHT('0000' + CAST(@j AS NVARCHAR(4)), 4),
         N'B2026Q3', N'NEW', SYSUTCDATETIME());
    SET @j = @j + 1;
END
GO

SELECT 'POOrder' AS T, COUNT(*) AS cnt FROM dbo.POOrder
UNION ALL SELECT 't_Supplier', COUNT(*) FROM dbo.t_Supplier
UNION ALL SELECT 'MFG_Report', COUNT(*) FROM dbo.MFG_Report
UNION ALL SELECT 'QC_Report', COUNT(*) FROM dbo.QC_Report
UNION ALL SELECT 'QC_ReportDetail', COUNT(*) FROM dbo.QC_ReportDetail
UNION ALL SELECT 'MDM_Code', COUNT(*) FROM dbo.MDM_Code;
GO
