# 可靠性（失败/重试/死信/重投）

失败即停 + 按任务配置重试退避 + 死信记录与重投；不承诺跨系统补偿事务。

## 当前设计（现状）

- **失败即停（ADR-0003）**：`RouteGenerator.configureErrorHandling` 对全链 `onException(Exception.class)`；`maximumRedeliveries = job.retryCount`、`redeliveryDelay = job.backoffMs`；最终失败写死信。
- **handled 策略**：MQ 消费主路由 `handled=true`（死信后消费侧不重投队列）；重放/轮询路由 `handled=false`（异常抛回调用方，手动执行会记 FAILED）。
- **死信记录**：`DeadLetter(id, jobId, actionIndex, inputJson, error, status)`；`inputJson` 存**原始输入**（`edge.originalInput`，在消费/重放入口设置），StreamCache/InputStream 读成 JSON 字符串存储；`status` ∈ FAILED / REDELIVERED。
- **列表分页**：`GET /api/dead-letters?jobId=&status=&page=&size=`（默认 1/20，size≤100）返回 `{total, records}`，按 id 倒序；MyBatis-Plus 分页需 `PaginationInnerInterceptor`（mybatis-plus-jsqlparser 依赖）。
- **重投**：`POST /api/dead-letters/{id}/redeliver` → 解析 inputJson → `JobExecutionService.execute` 走 `direct:job-<id>` 重放路由 → **跳过首动作**，从第 2 个动作按原始输入重跑；成功标 REDELIVERED，失败保持 FAILED 并报“重投失败”。
- **代码位置**：`service/DeadLetterService.java`、`web/DeadLetterController.java`、`config/MybatisPlusConfig.java`、`service/RouteGenerator.java`（recordDeadLetter）。

## 决策时间线

| 日期 | 事件 | 为什么 | 被谁修正 | 锚点 |
|---|---|---|---|---|
| 08-18 | 失败即停 + 重试退避 + 死信记录（不承诺分布式补偿） | 跨 HTTP/JDBC/MQ 无法可靠补偿；跳过失败不可对账 | — | ADR-0003 |
| 08-26 | 死信列表分页 + 默认过滤 FAILED | 数据量大全量返回卡顿 | — | 本轮（DeadLetterService/View） |
| 08-26 | 死信输入存真实 JSON（修复 StreamCache 存成 Java toString） | 显示与重投都依赖 input_json 是合法 JSON | — | 本轮 |
| 08-26 | 重投按**原始输入**重跑（edge.originalInput） | 之前存失败时刻 body，HTTP 失败会二次映射 | — | 本轮（RouteGenerator） |

## 待办 / 已知限制

- 旧死信（input_json 为 Java toString）无法重投，建议清理（`DELETE FROM dead_letter WHERE input_json LIKE 'org.apache.camel%'`）；
- 任务列表的 lastRunStatus 只在手动执行/定时刷新，MQ 消费链路完成不更新（观察项）。

## 决策时间线

| 日期 | 事件 | 为什么 | 被谁修正 | 锚点 |
|---|---|---|---|---|
| 08-18 | 失败即停 + 重试退避 + 死信记录（不承诺分布式补偿） | 跨 HTTP/JDBC/MQ 无法可靠补偿；跳过失败不可对账 | — | ADR-0003 |
| 08-26 | 死信列表分页 + 默认过滤 FAILED | 数据量大全量返回卡顿 | — | 本轮（DeadLetterService/View） |
| 08-26 | 死信输入存真实 JSON（修复 StreamCache 存成 Java toString） | 显示与重投都依赖 input_json 是合法 JSON | — | 本轮 |
| 08-26 | 重投按**原始输入**重跑（edge.originalInput） | 之前存失败时刻 body，HTTP 失败会二次映射 | — | 本轮（RouteGenerator） |

## 当前生效

- 失败即停 + 重试退避 + 死信（ADR-0003）
- 死信列表分页（page/size，默认 FAILED）
- 死信输入 = 原始输入，重投从第 2 个动作重跑
- 旧死信（input_json 为 Java toString）无法重投，建议清理
