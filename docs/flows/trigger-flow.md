# 触发流（手动执行 / 定时 / 外部 API 触发）

## 场景说明

任务如何被启动：**手动执行（界面/接口）、定时（timer，cron 待接入）、外部调用（API Key 鉴权）**。
入口 = 前端按钮 / REST / 定时器 / `POST /api/external/jobs/{id}/trigger`；落点 = `direct:job-<id>` 路由执行并回写
最近执行状态。

### 交互清单

| 角色 | 动作 | 触发方式 | 期望结果 |
|---|---|---|---|
| 实施工程师 | 手动执行 | 任务页"执行" / `POST /api/jobs/{id}/execute` | `direct:job-N` 执行，`lastRunStatus` 回写 |
| 定时器 | 周期触发 | timer 路由 | `direct:job-N` 每 60s 执行（cron 未接入） |
| 外部系统 | 触发任务 | `POST /api/external/jobs/{id}/trigger` + `X-API-Key` | 鉴权通过则执行，否则 `UNAUTHORIZED` |

## 流程详解

### 时序图

```mermaid
sequenceDiagram
    participant USER as 实施工程师
    participant UI as 前端
    participant API as Controller
    participant EXEC as JobExecutionService
    participant EXT as ExternalTriggerService
    participant KEY as ApiKeyService
    participant ROUTE as direct:job-N
    participant DB as MySQL(job)

    USER->>UI: 点"执行"
    UI->>API: POST /api/jobs/{id}/execute
    API->>EXEC: execute(id,input)
    EXEC->>DB: selectById + 校验 status=1
    EXEC->>ROUTE: producer.requestBody("direct:job-N", input)
    ROUTE-->>EXEC: 成功/失败
    EXEC->>DB: update lastRunStatus/At/Error
    EXEC-->>UI: 结果
    EXT->>KEY: 外部触发 trigger() → ApiKeyService.matches(siteId,key)
    KEY-->>EXT: 通过/拒绝
    EXT->>EXEC: execute(jobId,input)
    Note over ROUTE: 定时(timer)同样投 direct:job-N
```

### 逐环节代码走查

| 触发方式 | 程序入口 | 关键类/方法 | 数据形态 | 鉴权 | 同步/异步 |
|---|---|---|---|---|---|
| 手动执行 | `POST /api/jobs/{id}/execute` | `JobExecutionService.execute`（`service/JobExecutionService.java:32`） | `Map` 入参 → `direct:job-N` | 无 | 同步等待 + 回写 `lastRunStatus/At/Error` |
| 定时 | `timer:job-N`（`service/RouteGenerator.java:92`，`period=60000`） | `buildPollingEntry` | 无入参 | 无 | Camel 调度 |
| 外部触发 | `POST /api/external/jobs/{id}/trigger` | `ExternalTriggerService.trigger`（`service/ExternalTriggerService.java:31`）→ `ApiKeyService.matches`（`service/ApiKeyService.java:45`） | `Map` 入参体 | `X-API-Key` 与任务站点匹配 | 同步等待 |

`JobExecutionService.execute`：未启用抛 `JOB_NOT_ENABLED`；成功写 `SUCCESS`、失败写 `FAILED` + 最深异常信息
（`rootMessage` 跳过 Camel 包装）。`ExternalTriggerService` 校验失败抛 `UNAUTHORIZED`。

## 测试环境

- 应用后端（8080）在跑；任务已启用；站点已建且已生成 API Key；
- 定时验证需观察 timer 路由每 60s 触发（或用日志）。

## 测试数据与造数指导

| 触发方式 | 入 | 出 | 验证 |
|---|---|---|---|
| 手动 | `POST /api/jobs/{id}/execute {}` | `lastRunStatus=SUCCESS/FAILED` | 响应 `jobView` + 任务列表状态 |
| 未启用（失败） | 停用后 execute | `JOB_NOT_ENABLED` | 400 |
| 外部（成功） | `curl -X POST /api/external/jobs/1/trigger -H "X-API-Key: <key>" -d '{"字段":"值"}'` | 执行并回写 | 目标端收到 |
| 外部（鉴权失败） | 错/缺 API Key | `UNAUTHORIZED` | 401 |
| 定时 | 启用任务 | 每 60s 一次 `direct:job-N` | 日志 |

## 验证操作

```powershell
curl -X POST http://localhost:8080/api/jobs/{id}/execute -H "Content-Type: application/json" -d '{}'
curl -X POST http://localhost:8080/api/external/jobs/<id>/trigger -H "X-API-Key: <key>" -H "Content-Type: application/json" -d '{"字段":"值"}'
```

## 建议表

| 现象 | 建议 | 理由 | 类型 | 涉及功能 | 状态 |
|---|---|---|---|---|---|
| C3 `crontab` 存而未用，定时仅固定 60s | 接 cron 或写 ADR 暂缓并同步用户侧文档 | 文档承诺定时（"留空=不自动"），实现与文档不符 | 缺陷/文档偏差 | job-action-chain | 待评估 |
| 外部触发对无 MQ_CONSUME/READ_SQL 链首的任务语义不清 | 文档明确边界 | 反例场景（MQ 消费 + 北向）已支持 | 业务改进 | 触发 | 待评估 |
| API Key 仅站点级，无轮换/过期 | 观察项 | 现状单站点单 Key | 业务改进 | 站点 | 待评估 |
