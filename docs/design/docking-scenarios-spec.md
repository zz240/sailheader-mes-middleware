# 对接场景规格（S0）：MES 侧 + 工业互联网侧模拟

> **验收状态（2026-09-19）**：**九条链 + B1 全部跑通**。六条主链（A5/A2/A3/A4/A7/B6）＋ 三个变体：
> **A2MQ**（MES 主动发 MQ → 映射 → SQL Server `MERGE`）、**A4PUSH**（工位终端直推
> `POST /api/external/jobs/{id}/trigger`，请求体作链首输入 `sourceStep=0`，错 key 返回 401）、
> **A7BIG**（整份大报文：SQL 侧 `FOR JSON PATH` 聚合 + 映射侧 `$parse`/`$map`，3000 行 / 344 KB 一条消息）。
> 断言合计：`verify-scenario.ps1` **33 项**（十条链；其中 B6 段有 **4 项**是 #123 加的"响应回写"断言）
> + `verify-b1.ps1` **19 项**（含**掉线注入两种模式**：
> 默认停用任务 / `-GatewayOutage` 真停进程再起，两者都断言「掉线期间攒住、恢复后补齐且计数连续」，
> 以及设备掉线 → STALE）。
> **本轮从场景里刨出四个真问题**：#123 响应回链、#124 明细级水位列、#125 多列幂等键、
> **#126 `HTTP_CALL` 换掉消息体（紧随的 `WRITE_UPSERT` 必 NPE）**、**#127 报文 >64 KB 写不进消息历史**（由 **#141** 修：快照列提到 `MEDIUMTEXT`）；
> 并为「一单多行 → 一条报文」补了产品函数 `$parse`。
> **#123 / #126 的修复已落地并真机复验**（`HTTP_CALL` 响应进 `context[本动作序号]`、消息体不再被顶替，
> 决策见 `docs/adr/0015`）：2026-09-20 在影子站点真机环境跑 `verify-scenario.ps1` **33/33** +
> `verify-b1.ps1` **19/19** 全绿。B6 链加了 `map-handle` + `write-handle` 两步，`handle` 回写
> `MDM_Code.FHandle`（断言从消息历史读，不再靠替身 `GET /__handles` 旁证）；B1 链还原成现场最常见的
> 「先推上云、再落本地库」顺序，不再需要调换顺序。
> 覆盖 SQL Server 读分页、`MERGE` 幂等 upsert、240 行分 5 批、组合键展平、`EXPRESSION` 模式；
> 实测报文基线在 `tools/shadow-site/expected/`，跑法见 `tools/shadow-site/README.md`。
>
> **补记（#143 真机验收）**：`verify-scenario.ps1` 已增至 **79 项**（含 #143 新增 46 项：B6 批量 20 条 /
> 1000 条各一次请求、A2 头行分表、批量失败重发）、`verify-split-140.ps1` **21 项**（#140 不回归）；
> 跑法与三个真机发现见 `tools/shadow-site/README.md`。

> 本文件是这批场景的**验收靶子**：每条场景都必须能回答「谁触发、什么形状、落到哪、怎么算对上、失败怎么办」。
> 编号沿用 `real-docking-cases.md`（本批选中：A2 / A3 / A4 / A5 / A7 / B1 / B6）。
> 产品当前能力边界与差距见那份目录的 §E；本文只写「要模拟成什么样」。未定项集中列在 §6。

## 1. 替身拓扑

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

说明：**本地看板库放 MySQL**（产品今天就能写），云上/上云落点用 HTTP 替身；设备侧用 ESP32 走 Modbus TCP。

## 2. 共享数据模型

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

客户侧排序规则、`DATETIME2` 精度、以及中文列内容按客户实例定（§6 待定 7）。

### 2.2 MES 侧替身接口

| 接口 | 用途 | 形状 |
|---|---|---|
| `POST /mes/api/purchase-order` | A5 收采购/收料 | 单据头 + 行，返回 `{code,message}` |
| `POST /mes/api/report` | A4 收报工 | 单条小报文（工单+工序+数量+人+设备+时间） |
| `POST /mes/api/qc-report` | A7 收试模/质检报告 | 头 + 明细数组（数百行） |
| `POST /mes/api/id-register` | B6 标识注册 | `{codes:[{code,objectType,objectNo,batch}]}`，返回 `{code,handle}` 或批量结果 |
| `GET  /mes/api/id/resolve/{handle}` | B6 标识解析 | 返回对象信息（用于回读核对） |
| exchange `mes.to.edge`（RabbitMQ） | A2/A3 的 MQ 下发那条路 | 单条 JSON，`businessKey` 取单号 |

> **MES 下发的入口只有两种，别设计 HTTP 拉取**：网关只能**主动出站** HTTP（`HTTP_CALL`）与**订阅** MQ（`MQ_CONSUME`），没有「定时 GET 对方接口」这个动作。所以 A2/A3 的入口只能是 ① 网关用 `READ_SQL` 轮询 MES 的库，或 ② MES 侧主动发 MQ 给网关。上面那张表里若出现「HTTP 拉取」一律按这两种改写。

MES 侧收单替身要能：记录每一次请求（供逐字段核对）、按开关返回 5xx/超时/慢响应（供失败面用）。

### 2.3 设备侧（B1，ESP32）

| 项 | 约定 |
|---|---|
| 协议 | Modbus TCP 从站（保持寄存器），ESP32 当仪表/DTU；可选同时 MQTT 发布 |
| 点位 | 设备号 × 点位：电压/电流/温度/转速/状态字，10~30 点位/台，1~3 台 |
| 频率 | 1 s（原始）→ 边缘按 5 s 聚合上报；量级 ~100 点位/分钟 |
| 真实特征 | 断网 30~120 s 重连、重启后寄存器清零、时钟漂移 ±N 秒（这三样是 mock 造不出来的） |
| 落点 | ① HTTP 上云（MES 收单替身或自建服务）② upsert 本地看板库 `dev_metric(device_no, point, ts, value, quality)` |

## 3. 场景规格

每条六栏：触发与入口 / 数据形态与量级 / 键·幂等·水位 / 期望落点 / 怎么算对上 / 失败与边界。

### A2 物料主数据 / 模具 BOM 下发（MES → 客户库）

- **触发与入口**：两条路都要验，先做 (a)。(a) MES 库 `READ_SQL` 轮询 + `TIMESTAMP` 水位（`FModifyDate`，含边界重读靠幂等消化）；(b) MES 主动 `MQ_PUBLISH` 到 `mes.to.edge`，边缘 `MQ_CONSUME`。
- **数据形态与量级**：物料 2000 条/全量、日常增量 <50 条/次；BOM 单份 1 头 + 20~80 子行（数组层级）。
- **键·幂等·水位**：`t_Item` 幂等键 `FNumber`；`BOM` 头键 `FBOMNo`、子行 `(FBOMNo,FSeq)`；水位 `FModifyDate`。
- **期望落点**：客户 SQL Server 的 `t_Item` / `BOM` / `BOMChild`。
- **怎么算对上**：① 行数与源一致；② 抽查 10 行逐字段相等（含空值）；③ **连续执行两次，行数与字段不变**（幂等）；④ BOM 子行 `FSeq` 与源顺序一致、行号从 1 起。
- **失败与边界**：目标库不可达（整批不发、水位不推进）；单条脏数据（`FSpec` 超长/空 `FUnit`）；子件行号在二次下发时被覆盖而非追加。

### A3 生产工单 / 派工单下发（MES → 客户库）

- **触发与入口**：(a) 轮询 MES 库（`SEQUENCE` 水位 `FInterID`）；(b) MQ 下发。
- **数据形态与量级**：工单 50~200 张/天；每张 1 头 + 5~30 工序行。
- **键·幂等·水位**：头 `FBillNo`；行 `(FBillNo,FSeq)`；水位 `FInterID`。
- **期望落点**：`MFG_WorkOrder` / `MFG_WorkOrderRouting`。
- **怎么算对上**：① 工单张数与源一致；② 每张的工序行数与源一致、`FSeq` 升序；③ 已下发工单**内容变更**后再下发 → 更新而非新增（头行都要）。
- **失败与边界**：一张工单的下发行写失败（整条消息失败，不应半张落库）；`FPlanStart` 时区与格式（`DATE` 转换 + 时区）。

> **落库形态的硬约束（A2/A3 共有）**：`RouteGenerator.upsert` 把消息体当 `Map` 写一行，所以默认是**一条消息 = 一行、一张表**。头表 + 行表有三种做法：
> ① **读源时展平**：`READ_SQL` 用 join 把子行摊成一行一行（头字段冗余在每行上），逐行 upsert 进行表；头表由另一条任务（或同链再读一次）负责。现场常见做法，零产品改动。
> ② **读源时聚合**：源库侧 `JSON_ARRAYAGG` / `FOR JSON PATH` 把子件折成一列字符串，映射侧 `$parse` 还原成数组——一份报文一条消息（推 HTTP 可以；要落**行表**仍得再拆）。
> ③ **链上拆分（`SPLIT`，#140 已落地）**：把报文里那片数组摊成 N 条子消息，此后逐条 `WRITE_UPSERT` 落行表；头行由链上另一个动作写。配置与整条批量链的配法见 `docs/action-chain-design.md` §1 / `docs/user-guide.md`「批量接口怎么做」，决策见 `docs/adr/0017-cardinality-change-and-message-history.md`。
> A2 的 BOM 与 A3 的工单照这三条选一条；影子站点的 A2-HL 变体走的就是 ③。
> 原「② 产品新增『分裂』动作，作为独立票」**已落地**（#140 拆分 / #142 合并 + 扇回）。

### A4 报工与工序进度回传（客户侧 → MES）

- **触发与入口**：两个变体都要验。(a) 车间系统写客户库 `MFG_Report` → 网关 `READ_SQL` 轮询（先做）；(b) 工位终端/ESP32 直接 HTTP 到 `/api/external/jobs/{id}/trigger`（请求体作链首输入）。
- **数据形态与量级**：小报文 200~500 B；峰值按工位数：20 工位 × 每 5 min 一件 → 240 条/h；压测取 10 倍。
- **键·幂等·水位**：源水位 `FInterID`；**业务键 = `FBillNo + FSeq`（组合键）**。
- **期望落点**：MES `POST /mes/api/report`。
- **怎么算对上**：① MES 收到的条数与源新行数一致；② 逐字段（数量/人员/设备/时间格式）；③ **同一条源行被读两次（水位回拨）→ MES 侧业务键去重后仍只有一条**；④ 断网 60 s 恢复后，缺口条数在下一轮补上，不丢不重。
- **失败与边界**：MES 5xx/超时（重试退避 → 失败步 → 修好后断点重投）；**MQ 入口链的映射步重投静默出空**（`reliability-flow.md:178`）——A4(b) 若走 MQ 就会踩，是本条的必测项；组合业务键如何在报文里表达（§6 待定 5）。

### A5 采购订单 / 收料入库回传（客户 ERP → MES）

- **触发与入口**：客户 SQL Server `POOrder` join `t_Supplier` → `READ_SQL`（`SEQUENCE` 水位 `FInterID`）→ 字段映射 → `HTTP_CALL` 到 MES。
- **数据形态与量级**：50~500 张/天；每张 1 头 + 1~20 行；含金额（两位小数）与日期。
- **键·幂等·水位**：水位 `FInterID`；业务键 `FBillNo`。
- **期望落点**：MES `POST /mes/api/purchase-order`。
- **怎么算对上**：① 报文条数与新增单数一致；② 逐字段比对（订单号/日期/供应商编码/状态码/金额精度）；③ 目标端返回非 2xx 时**该批水位不推进**，修好后重跑补齐；④ 重复推送同单据时目标端按 `FBillNo` 去重。
- **失败与边界**：**SQL Server 方言与驱动（本条的真正考点）**：读分页、upsert、探测、校验四处；`FDate` 与任务时区；供应商被删导致 join 出空（不允许静默出空）。

### A7 质检 / 试模报告回传（客户 → MES）

- **触发与入口**：`QC_Report` + `QC_ReportDetail` **按明细展平**（join 后一行一明细、头字段冗余在每行）→ `READ_SQL`（`SEQUENCE` 水位 **`FDetailID`**）→ 映射（12 个字段，含 `detailKey = FBillNo#FSeq`）→ `HTTP_CALL`。
- **数据形态与量级**：单份 1 头 + 100~500 明细行（CMM 点位）；10~50 份/天；一条消息 = 一明细行，`batchSize` 50 → 240 行分 5 批。
- **键·幂等·水位**：水位 **明细级 `FDetailID`**；业务键 `FBillNo`；明细行号 `FSeq`；组合键以 `CONCAT` 展平成 `detailKey` 一列。
- **为什么走展平而不是「整份大报文」（实测撞出来的）**：产品是**一条消息 = 一行 = 一张表**（`RouteGenerator.upsert`），把明细数组塞进一条报文只能推 HTTP、落表写不了；更要命的是**拿头级 `FInterID` 当水位会在批边界漏单**——240 行分 5 批时，第一批读完水位就推到该报告的 `FInterID`，剩下 190 行永远读不到。所以明细表必须有明细级自增列（本项目给 `QC_ReportDetail.FDetailID`）。
- **期望落点**：MES `POST /mes/api/qc-report`。
- **怎么算对上**：① 明细条数与源一致（240）；② `detailKey` 与 `FSeq`/测量值一一对应、数值精度不丢；③ 240 条分 5 批读，**每批都推进水位且不漏单**；④ 同一份重推目标端按 `detailKey` 去重。
- **失败与边界**：大 payload 下的超时与重试放大；明细行部分缺失（必须整份失败而非半份）；空测量值（`NULL` 不得变 0）。
- **「整份大报文」变体（本轮已实测，与展平并列保留）**：源库侧 `FOR JSON PATH` 聚合 + 映射侧 `$parse`/`$map`，
  一份报告 = 一条消息 = 一次 HTTP。实测 3000 行 / **344 KB** 一条报文推通（0.3 s）；水位只能到报告级（`FInterID`），
  重试也是整份放大。**旧硬限制（#141 已修）**：报文 > 64 KB 时 `message_step.data_snapshot` 写不进去（MySQL `TEXT`），
  表现是「动作成功却被判失败」；#141 把承载报文的三列提到 `MEDIUMTEXT`（16 MB，迁移 `V6__snapshot_mediumtext.sql`），
  344 KB 这条现在按迁移建库就能跑通，不用再手工改列（本轮之前是临时改 `MEDIUMTEXT` 才跑通的）；
  为此新增产品函数 `$parse`（引擎自带函数里没有）。

### B1 设备数据上云 + 本地看板（设备 → 边缘 → 云 + 本地库）

- **触发与入口**：设备（Modbus TCP 从站）→ **采集桥** → 网关 `HTTP_CALL` 型入口（`/api/external/jobs/{id}/trigger`，请求体作链首输入）→ 映射 → ① `HTTP_CALL` 上云 ② `WRITE_UPSERT` 本地看板库（现场最常见的形状，正是 #126 修复前必 NPE 的那个顺序；现已全绿）。
- **数据形态与量级**：本项目按 2 台 × 4 点位、5 s 一周期（=96 点位/分钟）实测；现场 1~3 台 × 10~30 点位时约 100~360 点位/分钟，报文小而频，**一条消息 = 一个点位**（#140 之前产品没有「一分多」，多点位只能来自多条消息；现在一份多点位报文可以用 `SPLIT` 摊成多条，见 `docs/user-guide.md`「批量接口怎么做」）。
- **键·幂等·水位**：看板库幂等键 `device_no:point:ts` 用 `CONCAT` 展平成一列（`dev_metric.metric_key`）；上云按同一 `metric_key` 去重。
- **期望落点**：本地 `dev_metric`；上云 HTTP 服务。
- **怎么算对上（本轮已实测）**：① 看板库行数 = 采样数（48 行 = 6 轮 × 8 点位）；② 逐点位值与设备寄存器**完全对齐**（设备的轮询计数 1..39 连续）；③ **拔网 35 s 再恢复**：桥攒下 56 条，恢复后 `buffered=0`、行数补齐、**计数序列无断档无重复**；④ 设备重启瞬间质量位 **STALE**（值沿用上一次，不是把 0 当真实值）、传感器故障置位后 **BAD**、正常 **GOOD**。
- **实测发现（#126，已随 #123 修复）**：这条链最初写成 `FIELD_MAP → HTTP_CALL(上云) → WRITE_UPSERT(看板库)`，**86/86 全失败**——`HTTP_CALL` 会把消息体换成响应体，紧随其后的 `WRITE_UPSERT` 拿到 null 直接 NPE。当时靠改顺序（先写库再上云）绕开；现在 `HTTP_CALL` 出站后消息体保持为本次请求体，两种顺序都成立。
- **失败与边界**：ESP32 时钟漂移（时间戳乱序 → 水位/排序行为）；重复上报；断网续传（D1）；上云失败但本地已写（两条落点的一致性口径要写清）。

### B6 一物一码 / 标识解析（客户侧 → 标识服务 → 回写）

- **触发与入口**：客户侧赋码数据（`MDM_Code` 或扫码上报）→ 映射 → `HTTP_CALL` 到标识服务替身（注册）；解析回读核对。
- **数据形态与量级**：批量 1000 条/次；含中文名称与规格；单条 <1 KB。
- **键·幂等·水位**：幂等键 `FCode`；水位 **对象级自增列 `FID`**（本项目的 `MDM_Code.FID`）。
- **期望落点**：标识服务替身 `POST /mes/api/id-register`，报文形状 `{"codes":[{...}]}`——用 `mode=EXPRESSION` 手写 JSONata 把一行包成 `codes[1]`（规则树编不出数组字面量）。试算已验证：4/4 字段、`arrayRows=1`。
- **回写（#123 已实现）**：标识服务返回的 `FHandle` 在**同一条链**里回写库——`HTTP_CALL` 完成后响应进 `context[本动作序号]`，下一步 `FIELD_MAP` 以 `sourceStep=<HTTP_CALL 序号>` 取 `results[0].handle`，再 `WRITE_UPSERT` 进 `MDM_Code.FHandle`。影子站点 B6 链已按此加 `map-handle` + `write-handle` 两步，断言从消息历史读回写结果（不再靠替身 `GET /__handles` 旁证）。
- **怎么算对上**：① 注册成功条数 = 提交条数；② 同一批重复提交，返回同一 `FHandle`（幂等）；③ `GET /id/resolve/{handle}` 回读的字段与提交一致（含中文编码）；④ 部分失败时能定位到具体 `FCode`。
- **失败与边界**：批量部分失败（重投范围）；中文编码（UTF-8 往返）；标识服务限流（429）与退避。

## 4. 统一失败面（每条场景都要过一遍）

| 编号 | 失败面 | 判定口径 |
|---|---|---|
| F1 | 目标端不可达 / 5xx / 超时 | 重试退避到位；失败步落 `FAILED`；水位不推进；修好后断点重投成功。**实测时延**：目标库不可达时，每条消息要等 HikariCP `connectionTimeout`（默认 30 s）才失败一次——`retryCount=1` 的一条消息就是 60 s（本轮 A2 实测：run 314 耗时 60 s），联调时别当成卡死 |
| F2 | 重复投递 | 目标端按业务键去重，或南向幂等键 upsert；不允许重复落行 |
| F3 | 断网 30~120 s（D1） | 恢复后缺口补齐，不丢不重 |
| F4 | 脏数据（空值/超长/类型不符/越界） | 明确失败或明确跳过，**不允许静默写空** |
| F5 | 时钟漂移与乱序（D2） | 时间戳回退不乱序、不丢单；质量位正确 |
| F6 | 大 payload / 高并发 | 不截断、内存可控、无超时雪崩 |
| F7 | 重启恢复 | 启用任务路由自动重建、水位续上、进行中运行有明确终态 |

## 5. 核对手段（统一用这几处，不引入新工具）

1. **目标端记录接口**：MES 替身/标识替身提供「已收到什么」查询；逐字段 diff 期望报文。
2. **落点表**：直连客户 SQL Server / 看板库查行数与关键字段（避开只能看 UI 的核对）。
3. **产品自身证据**：`GET /api/history/runs|steps`（状态、`attempt`、`business_key`、快照）、`watermark` 表。
4. **替身侧故障开关**：5xx/超时/慢响应/限流，按场景需要打开。
5. **ESP32 侧**：串口日志 + （可选）MQTT 旁路监听。

## 6. 已定 / 待定

**已定（本轮落地，写在这里免得下次再问一遍）**：

1. **ESP32 用法**：Modbus TCP 从站 + 手上现有的真实传感器（DS18B20/INA219），不折腾 RS485；网关没有 Modbus 连接器，所以中间必有「采集桥」这一层（现场那层就是设备网关/DTU）。
2. **A2/A3 入口形态**：两条都验（`READ_SQL` 轮询 MES 库 + MES 主动发 MQ）。
3. **A4 入口**：两条都验过了——「车间系统写库 → 轮询」（A4）与「工位终端直推 HTTP」（A4PUSH，请求体作链首输入 `sourceStep=0`），两条都绿。
4. **头行分表**：走「读源展平」（零改动）——A7 的 240 行明细就是这么落的。
5. **组合幂等键**：先「展平成一列」——A4 的 `reportKey`、A7 的 `detailKey` 用 `CONCAT` 编出（试算已验证）；B1 看板库的 `dev_metric.metric_key` 同法。产品原生支持多列已开票：**#125**。
6. **SQL Server 环境**：Docker `mcr.microsoft.com/mssql/server:2022-latest`（ADR-0014 按它验证）。

**待定**：

7. **报工与点位的真实峰值**（条/分、并发任务数），用于定 `batchSize` 与压测档位。
8. **客户实例的排序规则**（中文大小写敏感与否）——影响 `t_Item.FNumber` 这类键的比较行为。
9. **标识解析接口形状**：用自建替身，还是要对齐国家/行业二级节点（若对齐，给我一份接口文档）。
10. **HTTP 响应回写**（B6 的 `FHandle`）：**#123 已实现**（选了「恒捕获 + 消息体还原」：响应进 `context[本动作序号]`，不新增动作种类、不加开关）。
11. **头级/明细级水位的约定**：像 A7 这种「一份报告多行明细」的源表，**明细级自增列**是否写进实施前置清单（拿头级 id 当水位会在批边界漏单，本轮实测）——已开票 **#124**。
