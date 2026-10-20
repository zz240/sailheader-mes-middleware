# 对接场景规格（S0）：MES 侧 + 工业互联网侧模拟

> - **是什么**：本产品要跑通哪些对接场景、每个场景怎么算跑对。这份文档是影子站点的验收靶子，不是实施手册。
> - **到哪了**：九条链 + B1 已真机跑通（2026-09-20 复验）；断言 79 + 19 + 21 项，账见 §8。
> - **怎么跑**：`tools/shadow-site/README.md`；报文基线在 `tools/shadow-site/expected/`。

## 1. 怎么用这份文档

每条场景只回答五个问题，顺序固定：

1. **谁触发**——入口与触发方式
2. **什么形状**——数据形态与量级
3. **落到哪**——目标系统与表
4. **怎么算对上**——可断言的验收项
5. **失败怎么办**——失败面与边界

按你要做的事挑入口：

| 你要做的事 | 看哪 |
|---|---|
| 搭环境、造数 | §2 拓扑与数据模型；跑法见 `tools/shadow-site/README.md` |
| 验收一条场景 | §4 对应小节，逐条跑「怎么算对上」 |
| 判断产品能力够不够 | §4 每条的「失败与边界」「已知坑」；总表见 `real-docking-cases.md` §E |
| 新增一条场景 | 照 §4 的模板补齐五问；缺一栏就是规格没写完 |

不写在这份文档里的：配置步骤（`docs/user-guide.md`）、变更记录（§8）、产品能力边界（`real-docking-cases.md` §E）。

## 2. 环境与数据模型

### 替身拓扑

```text
        ┌──────────────────────────── 云上 MES（替身）────────────────────────────┐
        │  收单 HTTP：/mes/api/{purchase-order,report,qc-report,id-register}        │
        │  下发 MQ ：exchange mes.to.edge（工单/主数据/派工）                        │
        │  下发库   ：MES_DB.mfg_*（READ_SQL 轮询那条路，与 MQ 二选一）              │
        └───────────────▲──────────────────────────────────┬──────────────────────┘
                        │ HTTP 出站（北向）                  │ MQ 消费（南向）
        ┌───────────────┴──────────────────────────────────▼──────────────────────┐
        │                     边缘网关（本机 8080，Camel + Spring Boot）            │
        └───────────────▲──────────────────────────────────┬──────────────────────┘
                        │ JDBC 读源（水位）                  │ JDBC upsert 落点
        ┌───────────────┴──────────────────────────────────▼──────────────────────┐
        │   客户侧 SQL Server 2022（Docker，金蝶/用友形状：POOrder / t_Item /      │
        │   MFG_WorkOrder(+Routing) / MFG_Report / QC_Report(+Detail) / MDM_Code）  │
        └──────────────────────────────────────────────────────────────────────────┘
                        ▲ 设备数据（B1）
        ┌───────────────┴───────────────┐        ┌───────────────────────────────┐
        │ ESP32：Modbus TCP 从站（寄存器）│──桥──▶ │ 本地看板库 dev_metric（MySQL） │
        │       + 少量真实传感器          │        │ 供本地看板查询                  │
        └───────────────────────────────┘        └───────────────────────────────┘
```

本地看板库放 MySQL（产品今天就能写）；云上/上云落点用 HTTP 替身；设备侧用 ESP32 走 Modbus TCP。

### 2.1 客户侧（SQL Server，金蝶/用友命名风格）

| 表 | 用途 | 关键列 | 键 / 水位 |
|---|---|---|---|
| `POOrder` | 采购订单（A5 源） | `FInterID, FBillNo, FDate, FSupplyID, FStatus, FNote, FModifyDate` | PK `FInterID`（identity，水位列） |
| `t_Supplier` | 供应商（A5 关联） | `FItemID, FNumber, FName` | PK `FItemID` |
| `t_Item` | 物料主数据（A2 落点） | `FNumber, FName, FSpec, FUnit, FMaterial, FVersion, FModifyDate` | PK `FNumber`（幂等键） |
| `BOM` / `BOMChild` | 模具 BOM 头 / 子件（A2 落点，数组层级） | 头：`FBOMNo, FMoldNo, FVersion, FModifyDate`；子：`FBOMNo, FSeq, FItemNumber, FQty, FUnit` | 头 PK `FBOMNo`；子 PK `(FBOMNo, FSeq)` |
| `MFG_WorkOrder` / `MFG_WorkOrderRouting` | 工单头 / 工序行（A3 落点，数组层级） | 头：`FBillNo, FItemNumber, FQty, FPlanStart, FPlanEnd, FStatus, FModifyDate`；行：`FBillNo, FSeq, FOpCode, FWorkCenter, FStdHour` | 头 PK `FBillNo`；行 PK `(FBillNo, FSeq)` |
| `MFG_Report` | 报工记录（A4 源） | `FInterID, FBillNo, FSeq, FQtyOK, FQtyNG, FWorker, FEquipment, FReportTime` | PK `FInterID`（水位列）；业务键 `FBillNo + FSeq` |
| `QC_Report` / `QC_ReportDetail` | 试模/质检报告与测量明细（A7 源，行数大） | 头：`FInterID, FBillNo, FMoldNo, FSampleNo, FTestDate, FResult, FInspector`；明细：`FBillNo, FSeq, FItemCode, FNominal, FUpper, FLower, FMeasured, FJudge` | 头 PK `FInterID`（水位列）；明细 PK `(FBillNo, FSeq)` |
| `MDM_Code` | 一物一码台账（B6 源/回写） | `FCode, FObjectType, FObjectNo, FBatch, FStatus, FPrintTime, FHandle` | PK `FCode`；`FHandle` 存标识服务返回值 |

客户实例的排序规则、`DATETIME2` 精度、中文列内容都按现场定（排序规则见 §7 待定 9）。

### 2.2 MES 侧替身接口

| 接口 | 用途 | 形状 |
|---|---|---|
| `POST /mes/api/purchase-order` | A5 收采购/收料 | 单据头 + 行，返回 `{code,message}` |
| `POST /mes/api/report` | A4 收报工 | 单条小报文（工单+工序+数量+人+设备+时间） |
| `POST /mes/api/qc-report` | A7 收试模/质检报告 | 头 + 明细数组（数百行） |
| `POST /mes/api/id-register` | B6 标识注册 | `{codes:[{code,objectType,objectNo,batch}]}`，返回 `{code,handle}` 或批量结果 |
| `GET  /mes/api/id/resolve/{handle}` | B6 标识解析 | 返回对象信息（用于回读核对） |
| exchange `mes.to.edge`（RabbitMQ） | A2/A3 的 MQ 下发那条路 | 单条 JSON，`businessKey` 取单号 |

替身要能记录每一次请求（供逐字段核对），并按开关返回 5xx / 超时 / 慢响应（供失败面用）。入口形态见 §3.3。

### 2.3 设备侧（B1，ESP32）

| 项 | 约定 |
|---|---|
| 协议 | Modbus TCP 从站（保持寄存器），ESP32 当仪表/DTU；可选同时 MQTT 发布 |
| 点位 | 设备号 × 点位：电压/电流/温度/转速/状态字，10~30 点位/台，1~3 台 |
| 频率 | 1 s（原始）→ 边缘按 5 s 聚合上报；量级 ~100 点位/分钟 |
| 真实特征 | 断网 30~120 s 重连、重启后寄存器清零、时钟漂移 ±N 秒（这三样 mock 造不出来） |
| 落点 | ① HTTP 上云（MES 收单替身或自建服务）② upsert 本地看板库 `dev_metric(device_no, point, ts, value, quality)` |

## 3. 三条公共约束（所有场景共用）

### 3.1 落库形态的硬约束：一条消息 = 一行 = 一张表

`ReadWriteFragments.upsert`（`service/ReadWriteFragments.java:124`；#145 之前这个方法在 `RouteGenerator`）把消息体当 `Map` 写一行。所以默认**一条消息 = 一行 = 一张表**，头表 + 行表只能三选一：

| 做法 | 怎么做 | 代价 |
|---|---|---|
| ① 读源展平 | `READ_SQL` 用 join 把子行摊成一行一行（头字段冗余在每行），逐行 upsert 进行表；头表另用一条任务，或同链再读一次 | 零产品改动，现场最常见 |
| ② 读源聚合 | 源库侧 `JSON_ARRAYAGG` / `FOR JSON PATH` 把子件折成一列字符串，映射侧 `$parse` 还原成数组 | 适合推 HTTP；要落**行表**仍得再拆 |
| ③ 链上拆分 | 把报文里那片数组摊成 N 条子消息，逐条 `WRITE_UPSERT` 落行表；头行由链上另一个动作写 | #140 已落地；配法见 `docs/action-chain-design.md` §1 / `docs/user-guide.md`「批量接口怎么做」，决策见 ADR-0017 |

A2 的 BOM、A3 的工单照这三条选一条；影子站点的 A2-HL 变体走 ③。原「产品新增分裂动作」已落地（#140 拆分 / #142 合并 + 还原）。

### 3.2 水位必须细到一行

水位是「上次读到哪」的游标，粒度必须和消息粒度一致。

反例（实测）：A7 拿报告头级 `FInterID` 当水位，240 行分 5 批读，第一批读完水位就推到该报告 → 后 190 行永远读不到。所以明细表必须有明细级自增列（本项目给 `QC_ReportDetail.FDetailID`）。这一条要不要进实施前置清单，见 §7 待定 10（#124）。

### 3.3 入口只有三种，没有「定时 GET 对方接口」

设计场景时先在这三种里挑：

1. **轮询对方库**：`READ_SQL` + 水位（对方库可读）。
2. **订阅 MQ**：`MQ_CONSUME`，只能做链首。
3. **对方主动推**：`POST /api/external/jobs/{id}/trigger`（带 `X-API-Key`，请求体作链首输入 `sourceStep=0`）。

所以 MES 下发那两条路只能是「网关轮询 MES 库」或「MES 主动发 MQ」，写成 HTTP 拉取是错的。

## 4. 场景规格

七条场景 + 四个变体均已跑通：

| 编号 | 场景 | 方向 | 入口 |
|---|---|---|---|
| A2 | 物料主数据 / 模具 BOM 下发 | MES → 客户库 | 轮询 / MQ（变体 A2MQ） |
| A3 | 生产工单 / 派工单下发 | MES → 客户库 | 轮询 / MQ |
| A4 | 报工与工序进度回传 | 客户 → MES | 轮询 / HTTP 直推（变体 A4PUSH） |
| A5 | 采购订单 / 收料入库回传 | 客户 ERP → MES | 轮询 + HTTP 出站 |
| A7 | 质检 / 试模报告回传 | 客户 → MES | 轮询 + HTTP 出站（变体 A7BIG） |
| B1 | 设备数据上云 + 本地看板 | 设备 → 边缘 → 云 + 本地库 | Modbus + HTTP 直推 |
| B6 | 一物一码 / 标识解析 | 客户 → 标识服务 → 回写 | HTTP |

以下每节用同一张表：入口 / 形状 / 键·水位 / 落点；表下是断言、失败与已知坑。

### A2 物料主数据 / 模具 BOM 下发（MES → 客户库）

| 项 | 规格 |
|---|---|
| 入口 | (a) `READ_SQL` 轮询 MES 库，`TIMESTAMP` 水位 `FModifyDate`（边界重读靠幂等消化）；(b) MES 主动 `MQ_PUBLISH` 到 `mes.to.edge`，边缘 `MQ_CONSUME`。两条都已验。 |
| 形状 | 物料 2000 条/全量，日常增量 <50 条/次；BOM 一份 1 头 + 20~80 子行（数组层级）。 |
| 键·水位 | `t_Item` 幂等键 `FNumber`；BOM 头 `FBOMNo`、子行 `(FBOMNo,FSeq)`；水位 `FModifyDate`。 |
| 落点 | 客户 SQL Server：`t_Item` / `BOM` / `BOMChild`。 |

**怎么算对上**

1. 行数与源一致。
2. 抽查 10 行逐字段相等（含空值）。
3. 连续执行两次，行数与字段不变（幂等）。
4. BOM 子行 `FSeq` 与源顺序一致、行号从 1 起。

**失败与边界**：目标库不可达（整批不发、水位不推进）；单条脏数据（`FSpec` 超长 / 空 `FUnit`）；子件行号在二次下发时被覆盖而非追加。

**变体 A2MQ**：MES 主动发 MQ → 映射 → SQL Server `MERGE`。

### A3 生产工单 / 派工单下发（MES → 客户库）

| 项 | 规格 |
|---|---|
| 入口 | (a) 轮询 MES 库，`SEQUENCE` 水位 `FInterID`；(b) MQ 下发。两条都已验。 |
| 形状 | 工单 50~200 张/天；每张 1 头 + 5~30 工序行。 |
| 键·水位 | 头 `FBillNo`；行 `(FBillNo,FSeq)`；水位 `FInterID`。 |
| 落点 | `MFG_WorkOrder` / `MFG_WorkOrderRouting`。 |

**怎么算对上**

1. 工单张数与源一致。
2. 每张的工序行数与源一致，`FSeq` 升序。
3. 已下发工单**内容变更**后再下发 → 更新而非新增（头行都要）。

**失败与边界**：一张工单的某行写失败 → 整条消息失败，不得半张落库；`FPlanStart` 的时区与格式（`DATE` 转换 + 时区）。

### A4 报工与工序进度回传（客户侧 → MES）

| 项 | 规格 |
|---|---|
| 入口 | 两个变体都已验：(a) 车间系统写客户库 `MFG_Report` → 网关 `READ_SQL` 轮询；(b) 工位终端 / ESP32 直接 `POST /api/external/jobs/{id}/trigger`，请求体作链首输入（`sourceStep=0`，错 key 返回 401）。 |
| 形状 | 小报文 200~500 B；峰值按工位数：20 工位 × 每 5 min 一件 ≈ 240 条/h；压测取 10 倍。 |
| 键·水位 | 源水位 `FInterID`；业务键 `FBillNo + FSeq`（组合键），报文里用 `CONCAT` 展平成 `reportKey` 一列。 |
| 落点 | MES `POST /mes/api/report`。 |

**怎么算对上**

1. MES 收到的条数与源新行数一致。
2. 逐字段：数量 / 人员 / 设备 / 时间格式。
3. 同一条源行被读两次（水位回拨）→ MES 侧按业务键去重后仍只有一条。
4. 断网 60 s 恢复后，缺口条数在下一轮补上，不丢不重。

**失败与边界**：MES 5xx / 超时（重试退避 → 失败步 → 修好后断点重投）；组合业务键如何进报文（§7 已定 5）。

**已知坑**：**MQ 入口链的映射步重投会静默出空**（`docs/flows/reliability-flow.md:178`，状态「待评估」）。A4 若走 MQ 入口就会踩，是必测项。

### A5 采购订单 / 收料入库回传（客户 ERP → MES）

| 项 | 规格 |
|---|---|
| 入口 | 客户 SQL Server `POOrder` join `t_Supplier` → `READ_SQL`（`SEQUENCE` 水位 `FInterID`）→ 字段映射 → `HTTP_CALL` 到 MES。 |
| 形状 | 50~500 张/天；每张 1 头 + 1~20 行；含金额（两位小数）与日期。 |
| 键·水位 | 水位 `FInterID`；业务键 `FBillNo`。 |
| 落点 | MES `POST /mes/api/purchase-order`。 |

**怎么算对上**

1. 报文条数与新增单数一致。
2. 逐字段比对：订单号 / 日期 / 供应商编码 / 状态码 / 金额精度。
3. 目标端返回非 2xx 时该批水位不推进，修好后重跑补齐。
4. 重复推送同单据时，目标端按 `FBillNo` 去重。

**失败与边界**：**SQL Server 方言与驱动是本条的真正考点**——读分页、upsert、结构探测、SQL 校验四处；`FDate` 与任务时区；供应商被删导致 join 出空（不允许静默出空）。

### A7 质检 / 试模报告回传（客户 → MES）

| 项 | 规格 |
|---|---|
| 入口 | `QC_Report` + `QC_ReportDetail` **按明细展平**（join 后一行一明细，头字段冗余在每行）→ `READ_SQL`（`SEQUENCE` 水位 **`FDetailID`**）→ 映射 12 个字段（含 `detailKey = FBillNo#FSeq`）→ `HTTP_CALL`。 |
| 形状 | 单份 1 头 + 100~500 明细行（CMM 点位）；10~50 份/天；一条消息 = 一明细行，`batchSize` 50 → 240 行分 5 批。 |
| 键·水位 | 水位 **明细级 `FDetailID`**；业务键 `FBillNo`；明细行号 `FSeq`；组合键以 `CONCAT` 展平成 `detailKey`。 |
| 落点 | MES `POST /mes/api/qc-report`。 |

**怎么算对上**

1. 明细条数与源一致（240）。
2. `detailKey` 与 `FSeq` / 测量值一一对应，数值精度不丢。
3. 240 条分 5 批读，每批都推进水位且不漏单。
4. 同一份重推时，目标端按 `detailKey` 去重。

**失败与边界**：大 payload 下的超时与重试放大；明细行部分缺失（必须整份失败而非半份）；空测量值（`NULL` 不得变 0）。

**变体 A7BIG（整份大报文）**：源库侧 `FOR JSON PATH` 聚合 + 映射侧 `$parse` / `$map`，一份报告 = 一条消息 = 一次 HTTP。

- 实测 3000 行 / 344 KB 一条报文推通（0.3 s）。
- 代价：水位只能到报告级（`FInterID`），重试整份放大；要落行表仍得再拆。
- #141 之前，报文 >64 KB 时 `message_step.data_snapshot`（MySQL `TEXT`）写不进去，表现为「动作成功却被判失败」；#141 把承载报文的三列提到 `MEDIUMTEXT`（16 MB，迁移 `V6__snapshot_mediumtext.sql`），按迁移建库即可，不用手工改列。
- 为此新增产品函数 `$parse`（引擎自带函数里没有）。

### B1 设备数据上云 + 本地看板（设备 → 边缘 → 云 + 本地库）

| 项 | 规格 |
|---|---|
| 入口 | 设备（Modbus TCP 从站）→ 采集桥 → 网关 `POST /api/external/jobs/{id}/trigger`（请求体作链首输入）→ 映射 → ① `HTTP_CALL` 上云 ② `WRITE_UPSERT` 本地看板库。顺序是「先上云、再写库」——现场最常见；#126 修复前这个顺序必 NPE，现已全绿。 |
| 形状 | 本项目实测 2 台 × 4 点位、5 s 一周期（≈96 点位/分钟）；现场 1~3 台 × 10~30 点位 ≈ 100~360 点位/分钟；报文小而频。一条消息 = 一个点位（#140 前产品没有「一分多」；现在多点位报文可用拆分档摊成多条，见 `docs/user-guide.md`「批量接口怎么做」）。 |
| 键·水位 | 看板库幂等键 `device_no:point:ts` 用 `CONCAT` 展平成 `dev_metric.metric_key`；上云按同一 `metric_key` 去重。 |
| 落点 | 本地 `dev_metric`；上云 HTTP 服务。 |

**怎么算对上（已实测）**

1. 看板库行数 = 采样数（48 行 = 6 轮 × 8 点位）。
2. 逐点位值与设备寄存器完全对齐（设备轮询计数 1..39 连续）。
3. 拔网 35 s 再恢复：桥攒下 56 条，恢复后 `buffered=0`、行数补齐、计数序列无断档无重复。
4. 设备重启瞬间质量位 `STALE`（值沿用上一次，不是把 0 当真实值）；传感器故障置位后 `BAD`；正常 `GOOD`。

**失败与边界**：ESP32 时钟漂移（时间戳乱序 → 水位/排序行为）；重复上报；断网续传（D1）；上云失败但本地已写（两条落点的一致性口径要写清）。

**已知坑（#126，已修）**：链最初写成 `FIELD_MAP → HTTP_CALL(上云) → WRITE_UPSERT(看板库)`，**86/86 全失败**——`HTTP_CALL` 把消息体换成响应体，紧随的 `WRITE_UPSERT` 拿到 null 直接 NPE。当时靠「先写库再上云」绕开；现在 `HTTP_CALL` 出站后消息体保持为本次请求体，两种顺序都成立，链已还原成先上云再写库。

### B6 一物一码 / 标识解析（客户侧 → 标识服务 → 回写）

| 项 | 规格 |
|---|---|
| 入口 | 客户侧赋码数据（`MDM_Code` 或扫码上报）→ 映射 → `HTTP_CALL` 到标识服务替身（注册）；再回读解析核对。 |
| 形状 | 批量 1000 条/次；含中文名称与规格；单条 <1 KB。 |
| 键·水位 | 幂等键 `FCode`；水位 **对象级自增列 `FID`**（本项目的 `MDM_Code.FID`）。 |
| 落点 | 标识服务替身 `POST /mes/api/id-register`，报文 `{"codes":[{...}]}`；用 `mode=EXPRESSION` 手写 JSONata 把一行包成 `codes[1]`（规则树编不出数组字面量）。试算已验证：4/4 字段、`arrayRows=1`。 |

**怎么算对上**

1. 注册成功条数 = 提交条数。
2. 同一批重复提交，返回同一 `FHandle`（幂等）。
3. `GET /id/resolve/{handle}` 回读的字段与提交一致（含中文编码）。
4. 部分失败时能定位到具体 `FCode`。

**回写（#123 已实现）**：标识服务返回的 `FHandle` 在同一条链里回写库——`HTTP_CALL` 完成后响应进 `context[本动作序号]`；下一步 `FIELD_MAP` 以 `sourceStep=<HTTP_CALL 序号>` 取 `results[0].handle`，再 `WRITE_UPSERT` 进 `MDM_Code.FHandle`。影子站点 B6 链已按此加 `map-handle` + `write-handle` 两步，断言从消息历史读回写结果（不再靠替身 `GET /__handles` 旁证）。

**失败与边界**：批量部分失败（重投范围）；中文编码（UTF-8 往返）；标识服务限流（429）与退避。

## 5. 统一失败面（每条场景都要过一遍）

| 编号 | 失败面 | 判定口径 |
|---|---|---|
| F1 | 目标端不可达 / 5xx / 超时 | 重试退避到位；失败步落 `FAILED`；水位不推进；修好后断点重投成功。**实测时延**：目标库不可达时，每条消息要等 HikariCP `connectionTimeout`（默认 30 s）才失败一次——`retryCount=1` 的一条消息就是 60 s（A2 实测 run 314 耗时 60 s），联调时别当卡死 |
| F2 | 重复投递 | 目标端按业务键去重，或南向幂等键 upsert；不允许重复落行 |
| F3 | 断网 30~120 s（D1） | 恢复后缺口补齐，不丢不重 |
| F4 | 脏数据（空值/超长/类型不符/越界） | 明确失败或明确跳过，**不允许静默写空** |
| F5 | 时钟漂移与乱序（D2） | 时间戳回退不乱序、不丢单；质量位正确 |
| F6 | 大 payload / 高并发 | 不截断、内存可控、无超时雪崩 |
| F7 | 重启恢复 | 启用任务路由自动重建、水位续上、进行中运行有明确终态 |

## 6. 核对手段（统一用这几处，不引入新工具）

1. **目标端记录接口**：MES 替身 / 标识替身提供「已收到什么」查询，逐字段 diff 期望报文。
2. **落点表**：直连客户 SQL Server / 看板库查行数与关键字段（避开只能看 UI 的核对）。
3. **产品自身证据**：`/api/history/runs` 与 `/api/history/steps`（状态、`attempt`、`business_key`、快照）、`watermark` 表。
4. **替身侧故障开关**：5xx / 超时 / 慢响应 / 限流，按场景需要打开。
5. **ESP32 侧**：串口日志 +（可选）MQTT 旁路监听。

## 7. 已定 / 待定

**已定**（写在这里免得下次再问一遍）：

| 编号 | 问题 | 结论 |
|---|---|---|
| 1 | ESP32 怎么用 | Modbus TCP 从站 + 手上现有的真实传感器（DS18B20 / INA219），不折腾 RS485；网关没有 Modbus 连接器，所以中间必有「采集桥」这一层（现场那层就是设备网关 / DTU） |
| 2 | A2 / A3 入口形态 | 两条都验：`READ_SQL` 轮询 MES 库 + MES 主动发 MQ |
| 3 | A4 入口 | 两条都验：车间系统写库 → 轮询（A4）；工位终端直推 HTTP（A4PUSH，请求体作链首输入 `sourceStep=0`） |
| 4 | 头行分表 | 走「读源展平」（零改动）——A7 的 240 行明细就是这么落的 |
| 5 | 组合幂等键 | 先「展平成一列」：A4 的 `reportKey`、A7 的 `detailKey` 用 `CONCAT` 编出（试算已验证），B1 的 `dev_metric.metric_key` 同法。产品原生支持多列已开票 **#125** |
| 6 | SQL Server 环境 | Docker `mcr.microsoft.com/mssql/server:2022-latest`（ADR-0014 按它验证） |
| 7 | HTTP 响应回写（B6 的 `FHandle`） | **#123 已实现**：选了「恒捕获 + 消息体还原」——响应进 `context[本动作序号]`，不新增动作种类、不加开关 |

**待定**：

| 编号 | 待定问题 | 影响 |
|---|---|---|
| 8 | 报工与点位的真实峰值（条/分、并发任务数） | 定 `batchSize` 与压测档位 |
| 9 | 客户实例的排序规则（中文大小写敏感与否） | 影响 `t_Item.FNumber` 这类键的比较行为 |
| 10 | 明细级自增列要不要写进实施前置清单 | A7 这种「一份报告多行明细」的源表，拿头级 id 当水位会在批边界漏单（本轮实测）；已开票 **#124** |
| 11 | 标识解析接口形状 | 用自建替身，还是对齐国家 / 行业二级节点；若对齐，需要一份接口文档 |

## 8. 变更记录

**断言账本**

| 脚本 | 断言数 | 覆盖 |
|---|---|---|
| `verify-scenario.ps1` | 79 | 十条链；33 是基线，#143 加了 46 项（B6 批量 20 条 / 1000 条各一次请求、A2 头行分表、批量失败重发） |
| `verify-b1.ps1` | 19 | B1；含两种掉线注入（默认停用任务 / `-GatewayOutage` 真停进程）与设备掉线 → `STALE` |
| `verify-split-140.ps1` | 21 | #140 拆分不回归 |

**时间线**

- **2026-09-19**：九条链（A5 / A2 / A3 / A4 / A7 / B6）+ 三个变体（A2MQ / A4PUSH / A7BIG）+ B1 全部跑通；断言 33 + 19。覆盖 SQL Server 读分页、`MERGE` 幂等 upsert、240 行分 5 批、组合键展平、`EXPRESSION` 模式。
- **2026-09-20**：影子站点真机复验 33/33 + 19/19。B6 链加 `map-handle` + `write-handle`，回写断言改从消息历史读；B1 链还原成「先上云、再落本地库」。
- **#143**：`verify-scenario.ps1` 增至 79 项，新增 `verify-split-140.ps1` 21 项。
- **#145**：`RouteGenerator` 拆分，upsert 逻辑移入 `ReadWriteFragments`（§3.1 的引用位置随之更新）。
- **#140 / #142**：拆分 / 合并 + 还原动作落地，原「新增分裂动作」票收口。
- **2026-10-20**：文档重组——每场景统一成五问模板，公共约束前置，变更记录移到 §8；事实未改，只补正了旧编号引用（§6 → §7）。

**从场景里刨出的问题**

- **#123**：`HTTP_CALL` 响应没法回链 → 现在响应进 `context[本动作序号]`（决策见 ADR-0015）；B6 段为此加了 4 项断言。
- **#124**：明细级水位列。
- **#125**：多列幂等键（当前用 `CONCAT` 展平绕过）。
- **#126**：`HTTP_CALL` 换掉消息体，紧随的 `WRITE_UPSERT` 必 NPE → 出站后消息体保持为请求体。
- **#127 → #141**：报文 >64 KB 写不进消息历史 → 快照列提到 `MEDIUMTEXT`（`V6__snapshot_mediumtext.sql`）。
