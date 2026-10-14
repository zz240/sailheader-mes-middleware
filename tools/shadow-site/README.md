# 影子站点（S1）：把「客户侧 + MES 侧」的替身搭起来

这是 `docs/design/docking-scenarios-spec.md`（S0 规格）里那批场景的**运行环境**。
本目录只管把替身和造数准备好；**配置与场景链**由后面的脚本建（见文末「下一步」）。

## 目录

| 文件 | 作用 |
|---|---|
| `docker-compose.yml` | 客户侧 SQL Server 2022（容器 `edge-shadow-mssql`，端口 1433） |
| `seed/sqlserver-customer-erp.sql` | 客户库 `CustomerERP`：金蝶/用友形状 11 张表 + 造数（A5/A4/A7 源，A2/A3/B6 落点与源） |
| `seed/mysql-mes-and-board.sql` | MES 侧库 `mes_db`（A2/A3 轮询源）+ 本地看板库 `board_db`（B1 落点）+ 造数 |
| `mes-mock.cjs` | MES 侧替身（9100）：收单、标识注册/解析、故障注入、收到什么可查 |

客户侧 HTTP 替身仍用仓库原有的 `tools/mock-9099.cjs`（9099）。

## 起环境（三步，都在仓库根跑）

**1. 客户侧 SQL Server**

```powershell
docker compose -f tools/shadow-site/docker-compose.yml up -d
# 首次要等它就绪（约 20~40 秒），再建库建表：
docker cp tools/shadow-site/seed/sqlserver-customer-erp.sql edge-shadow-mssql:/tmp/seed.sql
docker exec edge-shadow-mssql /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "EdgeShadow#2026" -C -f 65001 -i /tmp/seed.sql
```

> `sqlcmd` 路径在旧镜像里是 `/opt/mssql-tools/bin/sqlcmd`；报「找不到文件」就换那条路径再跑。

**2. MES 侧库与看板库（在已经跑着的 edge-gateway-mysql 容器里）**

```powershell
docker cp tools/shadow-site/seed/mysql-mes-and-board.sql edge-gateway-mysql:/tmp/seed.sql
docker exec edge-gateway-mysql sh -c "mysql -uroot -proot --default-character-set=utf8mb4 < /tmp/seed.sql"
```

> 用 `root` 是因为要 `CREATE DATABASE` 与给 `gateway` 授权；建完之后网关用 `gateway/gateway` 就能读写这两个库。

**3. MES 侧替身**

```powershell
node tools/shadow-site/mes-mock.cjs        # 监听 9100；Ctrl+C 停
# 另开一个窗口核对：
curl.exe http://localhost:9100/__health
```

## 建完之后应该看到什么

| 检查 | 命令 | 期望 |
|---|---|---|
| 客户库有数据 | `docker exec edge-shadow-mssql /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "EdgeShadow#2026" -C -Q "SELECT COUNT(*) FROM CustomerERP.dbo.POOrder"` | 5 |
| 试模明细 | 同上，把表换成 `QC_ReportDetail` | 240 |
| MES 库有数据 | `docker exec edge-gateway-mysql mysql -uroot -proot -e "SELECT COUNT(*) FROM mes_db.mes_work_order"` | 3 |
| MES 替身活着 | `curl.exe http://localhost:9100/__health` | `{"ok":true,...}` |

## 端口占用

| 端口 | 谁 | 备注 |
|---|---|---|
| 8080 / 5173 | 网关后端 / 前端 | 已在跑 |
| 3306 / 5672 / 15672 | 仓库现有 MySQL / RabbitMQ | 已在跑 |
| **1433** | 本目录的 SQL Server | 新增 |
| **9100** | 本目录的 MES 替身 | 新增 |
| 9099 | 客户侧 HTTP 替身（`tools/mock-9099.cjs`） | 按需起 |

## 验收：一条命令跑完十条链

```powershell
powershell -File tools/shadow-site/verify-scenario.ps1              # 33 项断言：读/写/幂等/分页/组合键/三种入口/大报文/响应回写
powershell -File tools/shadow-site/verify-scenario.ps1 -SkipWrite   # 只跑读侧（A5/A4/A7/A4PUSH；B6 自 #123 起含回写）
```

### 拆分动作的真机验收（#140）

```powershell
powershell -File tools/shadow-site/verify-split-140.ps1              # 21 项断言：一份报文 -> 头一行 + 子 N 行
powershell -File tools/shadow-site/verify-split-140.ps1 -KeepJob    # 跑完留着任务，便于去界面看消息历史
```

**这条链不依赖 SQL Server（1433）与 MES 替身（9100）**：源是本机 MySQL 的 `mes_db`（A2 那条主数据源），
落点是同容器里的 `board_db`（脚本自己建 `bom_head_t` / `bom_line_t` 两张表）。链是：
`READ_SQL`（头表 join 子表 + `JSON_ARRAYAGG` 聚成一列字符串）→ `FIELD_MAP`（`mode=EXPRESSION`，用 `` 把那一列
还原成**真数组**）→ **`ONE_TO_MANY`（拆分档；#140 拆分动作）** → `FIELD_MAP` → `WRITE_UPSERT`。

它验的是拆分动作那一票的口径：拆出来的 N 条子消息各记一行、**五样齐全**（消息标识 / 业务键 /
批内序号 / 父消息标识 / 批号）、**批号在拆分这一步换手**，以及拆完之后逐条落库、水位推进后重跑幂等。

**结果（2026-10-06 复验；真 MySQL + 真网关 + 真前端）：21 项通过 / 0 失败。**

可重复跑：每段先清该任务的水位与替身记录（B6 段还先把 `MDM_Code.FHandle` 清空），所以连跑两次结果一致（改动前实测连跑多次 29/29；B6 回写的 4 项断言见 #123）。

**结果（2026-09-19 复验；真 SQL Server 2022 + 真 MySQL + 真 RabbitMQ/MES 替身）：29 项通过 / 0 失败**

**2026-09-20 复验（#123 落地后，同一套真机环境）：`verify-scenario.ps1` 33 项通过 / 0 失败 + `verify-b1.ps1` 19 项通过 / 0 失败。**
B6 的回写闭环真机成立（`step 4` 从链上响应取到 `handle` → `step 5` MERGE 回写 20 行 `MDM_Code.FHandle`）；
B1 链已还原成现场最常见的「先推上云、再落本地库」顺序（不再靠调换顺序绕 #126）。

**2026-09-22 复验（#123 回归，同一套真机环境）：`verify-scenario.ps1` 33 项通过 / 0 失败 + `verify-b1.ps1` 19 项通过 / 0 失败。**
B6 那 4 项「回写从链上读」的断言（`step 3` 注册 → `step 4` 取 `results[0].handle` → `step 5` MERGE 20 行 `FHandle`）全绿；
B1 的 19 项含「拔网攒队列 → 恢复补齐」与「设备离线记 STALE」两段。

| 链 | 入口 | 验到了什么 |
|---|---|---|
| A5 | SQL Server 读（轮询） | 5 张采购单逐字段对上（含 `CLOSED→"2"` 与中文备注往返）；`batchSize=2` **分 3 批走 `OFFSET/FETCH`**；水位推进到 5；**重跑 0 条新增** |
| A2 | MySQL 读（轮询） | 首跑插 5 行；**重跑仍 5 行**（幂等）；改源后重跑**行数不变、值被更新**（是 UPDATE 不是 INSERT） |
| A3 | MySQL 读（轮询） | 3 张工单落库 |
| A4 | SQL Server 读（轮询） | 6 条报工；组合键展平成 `reportKey = WO-SH-0001#10` |
| A7 | SQL Server 读（轮询，240 行 / 5 批） | 明细全部送达、两份报告都覆盖、`detailKey` 与行号一一对应 |
| B6 | SQL Server 读 + `EXPRESSION` 模式 | 20 条 `{"codes":[{...}]}` 注册；替身侧 20 个 handle 都能解析回自身；**回写**：`handle` 从**链上响应**（`sourceStep` = HTTP 步序号）写回 `dbo.MDM_Code.FHandle`，断言从消息历史读、不再靠替身 `GET /__handles` 旁证（#123） |
| **A2MQ** | **MES 主动发 MQ** | 消息经 `MQ_CONSUME` → 映射 → **SQL Server `MERGE`**：首次落行，**同键再发换值 → 原地更新、仍 1 行** |
| **A4PUSH** | **工位终端直推 HTTP** | `POST /api/external/jobs/{id}/trigger`（`X-API-Key`）→ 请求体成为**链首输入**（`sourceStep=0`）→ 映射 → MES 收到 `reportKey = WO-SH-0009#10`；**错 key 返回 401** |
| **A7BIG** | SQL Server 读（整份报文） | SQL 侧 `FOR JSON PATH` 聚合 + 映射侧 `$parse`/`$map`：**3000 行 / 344 KB 一条报文**推通（0.3 s）；为此补了产品函数 `$parse`（并撞出 #127 的 64 KB 快照限制） |
| **B6-BATCH** | SQL Server 读（一批） | `#143`：20 条码**一次请求**（读库那一步的批大小就是「一次请求几条」）、响应按 `FCode` 还原**原来那 20 条消息**、逐条回写 `MDM_Code.FHandle`；摘要行（整份请求 + 整份响应）落在**出站**那一步（#147 已删除摘要行：出站那一步记的是打包出来那条信封消息自己的标识，失败恢复统一为「重跑任务」） |
| **A2-HL** | MySQL 读（头 + 子件聚合） | `#143`：一份报文 → `bom_head_t` **头一行** + `bom_line_t` **子 N 行**（走 `ONE_TO_MANY` 的**拆分档**）；历史里父子谱系（子行的父消息标识 = 拆分那一行的消息标识）与批号（拆分处换手）都看得见 |
| **B6-BULK** | SQL Server 读（1000 行一批） | `#143`：现场量级 **1000 条码只发 1 次请求**（报文 ≈ 80 KB 完整落进摘要行——#147 起落在那条信封消息自己的行上）、1000 行逐行回写 |

**期望基线已固化**（实测报文原文，以后回归照它比）：`expected/a5-purchase-orders.json`（5 条）、
`expected/a4-work-reports.json`（6 条）、`expected/a7-qc-details.json`（240 条 / 187 KB）、
`expected/b6-id-registrations.json`（20 条）。

**两个入口变体的现场结论**：
- MQ 入口（A2MQ）把「MES 主动发消息」这条路走通了；它对应 `docs/flows/trigger-flow.md` 里
  「入口由链首动作种类决定」那句话，产品侧不需要为它改任何东西。
- 终端直推（A4PUSH）证明 `sourceStep=0`（任务输入/请求体）在手写规则与 `CONCAT` 组合键里都能用；
  这条路绕开了 MQ，因此**不会**撞上 `docs/flows/reliability-flow.md:178` 那条「MQ 入口链映射步重投静默出空」的未闭环缺陷。
- 验收脚本每次会**重新签发站点 API Key**（明文只返回一次，库里只存哈希，拿不回旧的），会作废上一把。

## A7 的「整份大报文」变体（一条消息 = 一份报告）

```powershell
# 源库侧用 FOR JSON PATH 把明细聚成一列字符串，映射侧用 $parse + $map 还原成数组
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/jobs/<A7BIG id>/execute" -ContentType 'application/json' -Body '{}'
```

链：`READ_SQL`（头 + `(select … for json path) as lines_json`，水位 `FInterID`）→ `FIELD_MAP`（`mode=EXPRESSION`：
`$count($parse(...))` 与 `$map($parse(...), function($l,$i){…})`）→ `HTTP_CALL`。

| 报告 | 明细行 | 报文 | 结果 |
|---|---|---|---|
| QC-SH-0001 / 0002 | 120 / 120 | 13 KB | ✓ |
| **QC-SH-0003** | **3000** | **344 KB** | ✓（一条消息、0.3 s） |

**为此补了一个产品函数 `$parse`**：引擎自带的函数里没有它（实测 `$parse(...)` 报 `Attempted to invoke a non-function`），
所以 `JsonataFunctions.parseJson()` 是新加的（签名 `<j:j>`；已经是 Map/List 就原样返回；坏 JSON 抛错不静默），
配 5 个单测（`JsonataParseFunctionTest`）。**没有它，「一单多行 → 一条报文」这个形状根本编不出来。**

> **踩到的硬限制（#127，已由 #141 修）**：`message_step.data_snapshot/input_snapshot` 与 `job_run.input_snapshot`
> 是 MySQL `TEXT`（64 KB）。3000 行那份（382 KB）第一次跑直接 FAILED：
> `Data truncation: Data too long for column 'data_snapshot'`——**已执行成功的动作被记成失败**，目标端也收不到。
> 本轮为了跑通把三列手工改成 `MEDIUMTEXT`（就是上面这条记录）；**产品侧现在走迁移**
> `V6__snapshot_mediumtext.sql`（三列提到 `MEDIUMTEXT` = 16 MB，幂等），
> 新站点按迁移建库就是对的列型，**不再需要任何手工操作**；已经手工改宽过的库上执行是 no-op。

**这条形状与展平那条的取舍**：整份报文把 3240 行压成 3 条消息（网络/请求数少、报文完整），代价是重试放大
（一份 344 KB 失败就整份重推）与水位粒度只能到报告级；展平那条是一行一条消息（重试精确到行、水位到行），代价是消息数多。
两条都留在场景里，按现场目标端的接口形态选。

**同时量到的事实**：目标库不可达时每条消息要等 HikariCP `connectionTimeout`（默认 30 s）才失败一次，
`retryCount=1` 的一条消息就是 60 s（清场前 job 75 / run 314 实测）——联调时别当成卡死。

## 批量注册 / 头行分表 / 1000 条量级的真机验收（#143）

`#142` 的合并 + 还原档（合并只打包 → 链上现成出站动作发一次 → 还原档摊回原来那几条）在真机上到底能不能用，
由这一票回答。两条命令覆盖**接口**与**界面**两侧：

```powershell
powershell -ExecutionPolicy Bypass -File tools/shadow-site/verify-scenario.ps1   # 79 项（含 #143 新增 46 项）
cd tools/ui; node batch-rerun-147.cjs                                            # 界面那一半：重跑任务入口——二次确认 → POST /api/jobs/{id}/execute，替身只多收到一次该批请求、写回被补齐
powershell -ExecutionPolicy Bypass -File tools/shadow-site/verify-split-140.ps1   # 21 项（#140 不回归）
powershell -ExecutionPolicy Bypass -File tools/shadow-site/verify-b1.ps1          # 19 项（设备侧不回归）
```

**它验的四件事**（新链由 `seed-scenario.ps1` 建：`SH-B6-Register-Batch` / `SH-B6-Register-Bulk` /
`SH-A2-Bom-Head-Lines`）：

| 验收项 | 怎么验的 | 结果 |
|---|---|---|
| 20 条码**一次请求** + N 行逐行回写 + 逐条归因 | 替身收到 **1** 次 `id-register`、body 里 20 条码； `MDM_Code` 20 行 `FHandle` 与替身返回的 handle 逐条相等；还原那一步 20 行各带自己的业务键与自己的 handle | ✓ 12 项 |
| A2 头行分表：头一行 + 子 N 行（2 份报文 → 2 头 + 6 子），父子谱系与批号在历史里 | `bom_head_t` / `bom_line_t` 行数与字段；拆分那一行（输入父报文、输出子件数组）；4 条子行的父消息标识 = 那一行的消息标识、批号 1 个且与上游不同、批内序号 1..4。**页面级**（`batch-rerun-147.cjs`）：子件的链从步骤 6 起（它是拆分新造的消息）、子件那一格带批徽标、点开是「读取批 · 一次请求 4 条 · 失败 0 条」与那 4 个编号，父消息链上拆分那一格的**数据快照原文里就是那 4 条子件** | ✓ 13 项 + 页面 6 项 |
| 失败路径：目标端 500 → 整批同因 `FAILED` → 一次点击只发一次请求 | 出站那一步 1 行摘要 + 20 行同因 FAILED（各自带业务键）；水位不推进、库里没写；`POST /api/history/batches/{摘要行 id}/redeliver` 之后替身只多 1 次请求、旧批标 `REDELIVERED`、新运行 SUCCESS（该端点与摘要行已由 #147 删除，失败恢复统一为重跑任务） | ✓ 11 项 |
| 1000 条量级：只发 1 次、报文完整落历史 | 1 次请求装 1000 条码；摘要行 `input_snapshot` ≈ 80 KB / 1000 项、`data_snapshot` 1000 项（用快照面板那条 `GET /api/history/steps/{id}` 读回来）；1000 行回写 | ✓ 10 项 |

**结果（2026-10-07，真 SQL Server 2022 + 真 MySQL + 真网关 8080 + 真前端 5173 + MES 替身 9100）**：
`verify-scenario.ps1` **79 项通过 / 0 失败**（含 #143 新增 46 项，连跑三次结果一致）、
`batch-rerun-147.cjs` **29 项通过 / 0 失败**（界面：重跑任务按钮那一档 + A2 头行分表那一档的谱系与批号）——这是 #143 当时的记录，那时的脚本叫 `batch-redeliver-143.cjs`；#147 把它改写成「重跑任务」口径并改了名，**改写后的这 29 项尚未重跑**、
`verify-split-140.ps1` 21 项、
`verify-b1.ps1` 19 项全绿（不回归）；**全量测试**：`mvn test` **623 项 / 0 失败 / BUILD SUCCESS**、
前端纯模块自测 617 + 213 + 102 + 42 + 46 全绿、`vue-tsc --noEmit` 干净。
可重复跑：1000 条那一段自己造数、自己收尾（含 `DELETE /__handles` 清替身账本），所以连跑两次结果一致。

### 真机才发现的三件事（都在 issue #143 上记着，本票不改产品代码）

1. **「一次请求 N 条」多算一条**：合并打包出来那一条**信封消息**（`AGGREGATE` 自己记的那一行，
   消息标识是新生成的、不是摘要行）也带着这一批的批号，于是 `history/batch.ts` 按「该批号下非摘要行的
   不同消息标识数」数出来是 **21**，而现场只有 **20** 条码——组头因此出现「一次请求 21 条 · 失败 20 条」
   这种自相矛盾。纯模块自测的 fixture 里没有打包那一行，所以只有真机才撞得到；
   `tools/ui/batch-redeliver-143.cjs` 把现状钉住了（修掉之后那几条断言会一起变红）。（#147 已删除摘要行，本条现象随之消失。）
2. **整批重发不跑下游**：`redeliverBatch` 只把摘要行上那份报文**再发一次**并把响应还原记账
   （`direct:job-<id>-batch-resend-<出站序号>` 这条路由里只有出站与配对），**出站之后的动作不再执行**。
   在 B6 这条链上就是：重发之后目标端收到了，但**写回 `MDM_Code.FHandle` 的那一步不会补跑**——
   「回写闭环」恰好在重发按钮存在的那一幕断掉。要不要「重发后接着跑下游」得先定口径。
   （这条路已由 #147 删除：失败恢复统一为「重跑任务」，水位未推进即整批重走，下游因此被补上。）
3. **重发接口返回的 `status` 是创建那一刻的快照**：`POST /api/history/batches/{id}/redeliver` 回
   `{"status":"RUNNING","runId":N}`，而同一次调用里那条运行其实已经 `SUCCESS`（`finishRun` 更新的是
   另取的一份实体）。库里是对的、界面读回来也是对的，只有那个响应字段在说假话；
   `BatchRedeliverTest` 之所以断言到 `SUCCESS`，是因为它的假 mapper 返回同一个对象实例。（该端点与 `BatchRedeliverTest` 已由 #147 删除。）

## 那台 SQL Server 是什么

`docker compose -f tools/shadow-site/docker-compose.yml up -d` 起的 `mcr.microsoft.com/mssql/server:2022-latest`。
本地首次拉取 597 MB，这台机器 0.18~0.53 MB/s、花了约 45 分钟；**代理并不比直连快**（两头都测过，都是 0.18 MB/s）。

## B1 设备侧（一条命令验收）

```powershell
powershell -File tools/shadow-site/verify-b1.ps1                   # 19 项断言；自己起替身与桥，跑完收摊
powershell -File tools/shadow-site/verify-b1.ps1 -KeepRunning       # 留着替身与桥，便于手工继续看
powershell -File tools/shadow-site/verify-b1.ps1 -Seconds 40        # 拉长稳态观察窗口
powershell -File tools/shadow-site/verify-b1.ps1 -GatewayOutage     # 掉线注入用「真停网关进程 + 重启」（默认是停用任务）
```

断网续传（D1）两种注入方式，断言一样：

| 模式 | 怎么注入 | 说明 |
|---|---|---|
| 默认（安全、快） | `PUT /api/jobs/{id}/status {status:0}` → 外部触发返回 **400** | 桥走的是同一条失败路径（非 2xx → 进队列 → 下轮补发），不动进程 |
| `-GatewayOutage`（最接近现场） | 按端口找到 8080 的监听进程 **kill 掉**，再用 maven 把它起回来 | 会真杀进程；网关日志落在 `.scratch/gateway-restart.*.log`，而且**起回来的网关是游离进程**（不再挂在原来那个后台任务上） |

脚本覆盖：桥能否读到从站、看板库行数是否等于上报点数、每设备每点位样本数、**轮询计数是否单调（无断档无重复）**、
稳态是否全 GOOD、上云替身条数是否与看板一致、组合键形状，**掉线期间桥是否攒住（看板停涨）**、
**恢复后是否补发干净（队列归零 + 计数仍然连续）**，以及**把设备替身停掉一段 → 质量位必须是 STALE 且值沿用上一次（不是 0）**。

设备侧这条链是：**Modbus 从站替身 →（采集桥）→ 网关 → 写看板库 + 推上云**。产品没有 Modbus/MQTT 连接器，
所以中间那层「采集桥」是必然的（现场就是设备网关/DTU 那一层）。

```powershell
node tools/shadow-site/modbus-device.cjs                       # Modbus TCP 从站替身（1502，FC3；管理口 9201）
$env:JOB_ID='<SH-B1-DEVICE-UP 的 id>'; $env:API_KEY='<站点 API Key>'
$env:DEVICE_UNITS='1,2'; $env:POLL_MS='5000'
node tools/shadow-site/modbus-bridge.cjs                       # 采集桥（管理口 9202）
```

替身与固件共用一张寄存器表（`modbus-device.cjs` ↔ `esp32/modbus_device.ino`）：`0..1` uptime、`2` 状态字
（bit0 运行 / bit1 传感器故障 / bit2 刚重启）、`3` 温度 ×100、`4` 电流 ×100、`5` 电压 ×100、`6` 轮询计数。
换成真机只需把桥的 `DEVICE_HOST` 指到 ESP32 的 IP。

**实测结果**（2 台设备 × 4 点位，5 s 一周期）：

| 验收项 | 结果 |
|---|---|
| 看板库行数 = 采样数 | 正常运行时逐轮对得上（48 行 = 6 轮 × 8 点位；后续跑到 232 行） |
| 逐点位值一致 | 设备寄存器与看板库的 `counter` **完全对齐**（轮询计数 1..39 连续），温度/电流/电压沿曲线同步 |
| **拔网 35 s 再恢复**（D1） | 桥攒下 **56 条**（7 轮 × 8 点位），看板库停住不涨；网关拉起后 `buffered=0`、行数补到 **232**，**counter 序列 11..39 连续无断档、无重复** |
| 设备重启 / 传感器故障时的质量位 | 重启瞬间 8 条 **STALE**（值沿用上一次，**不是把 0 当真实值**）；故障位置起后 56 条 **BAD**；正常 248 条 GOOD |

采集桥的断网策略：POST 失败就进内存队列（上限 5000 条，先入先出），每轮先补发队列再采新点——
**所以断网期间设备照采、数据不丢，恢复后按原采样时刻补写**（`metric_key` 里带 `ts`，重复补发也只会 upsert 同一行）。

> **这条链第一次跑是 86/86 全失败**：`WRITE_UPSERT` 紧跟 `HTTP_CALL` 时拿到 null → NPE。
> 根因是 `HTTP_CALL` 会把消息体换成响应体（**#126**，已随 #123 修复：出站后消息体保持为请求体）。
> 链现在**就是**当时失败的那个顺序（`FIELD_MAP → HTTP_CALL(上云) → WRITE_UPSERT(看板库)`，也是现场最
> 常见的形状），19 项断言全绿——不再需要调换顺序。
>
> 顺序仍值得按可靠性取舍：`失败即停` 之下，写在前面的那步失败会挡住后面那步。想「云断了本地照写」，
> 就把 `WRITE_UPSERT` 放前面；想「没推上云就别落库」，就保持现在这个顺序。

## Windows PowerShell 5.1 的坑（重要，会坑到现场机器）

本仓库的脚本由 **Windows PowerShell 5.1**（`powershell.exe`，默认编码 `gb2312`）执行，它有两个行为要注意：

1. **按 ANSI 解析没有 BOM 的 `.ps1`**：中文注释会被解成乱码，严重时（乱码里凑出引号或括号）直接把语法搞坏——
   本轮 `seed-scenario.ps1` 一开始就是这样报 `Unexpected token ')'` 的。
2. **`Invoke-RestMethod` 用字符串 body 时按 Latin-1 编码**：JSON 里的中文值进库就成了乱码
   （库里早期那些乱码数据源名就是这么来的）。

所以本目录的 `.ps1` **一律写成纯 ASCII**；确实要写中文，把文件存成 **UTF-8 with BOM**。
发 JSON body 用显式 UTF-8 字节（见 `seed-scenario.ps1` 里 `Invoke-Api` 的注释与实现）。

## 下一步（这批还没做的）

| 项 | 说明 |
|---|---|
| **整批重发不跑下游**（#143 真机发现） | `redeliverBatch` 只把摘要行那份报文**再发一次**、把响应还原记账，**出站之后的动作不执行**——B6 批量这条链上就是「重发之后 `FHandle` 不写回」。要不要「重发后接着跑下游」得先定口径（已由 #147 删除，失败恢复统一为重跑任务） |
| **「一次请求 N 条」多算一条**（#143 真机发现） | 合并打包出来那条**信封消息**也带着这一批的批号，`history/batch.ts` 数出来是 21，而现场只有 20 条码；纯模块 fixture 里没有打包那一行，所以只有真机撞得到。修它要连 `batch-checks.ts` 一起改 |
| **重发接口的 `status` 字段**（#143 真机发现） | `POST /api/history/batches/{id}/redeliver` 回的是**创建那一刻**那个对象（`RUNNING`），库里那条运行其实已经 `SUCCESS`；界面读回来是对的，只有响应里那个字段在说假话（该端点已由 #147 删除） |
| B1 设备侧 | **已手工验收**（见上节：224 行看板数据 / 断网续传 / 质量位）；**尚未脚本化**——把「起替身 + 起桥 + 断言」并进 `verify-scenario.ps1` 是下一步 |
| A5/A7 的「整份大报文」变体 | 本轮走展平；整份报文要先把「明细数组放进一条报文的映射」跑通（JSON 聚合 + `$parse`） |
| #126 的修复 | **已并入 #123**：`HTTP_CALL` 不再换掉消息体，B1 链不必靠「调顺序」绕（单测/链路测试覆盖） |
| 标识回写 | **已并入 #123 并真机复验**：`FHandle` 从链上响应回写 `dbo.MDM_Code`，B6 链加回写断言与 `write-handle` 步；2026-09-20 与 2026-09-22 两次复验均 33 项绿（原先写的「真机复验待跑」已作废——那次改完当天就在同一套环境复验过） |
| 幂等键组合 | 本轮用 `CONCAT` 展平成一列；产品原生支持多列仍是候选票 |
