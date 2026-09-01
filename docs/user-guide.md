# 边缘网关操作手册

> 面向实施工程师：按下面的顺序配置一个真实同步任务。
> 通用流程：**先建数据源 → 再建任务和动作 → 启用并执行 → 出问题看消息历史**。
> 术语定义见仓库根 `CONTEXT.md`；本文档随功能变更同步更新。

## 0. 本地环境

依赖：Docker（MySQL + RabbitMQ）、JDK 21、Node 22。启动方式见 README「快速运行」。

- 队列发消息（联调）：`tools/mq-publish.ps1 -Queue <队列名> -PayloadFile <json文件>`
- 本地 HTTP mock（9099，模拟客户 ERP）：`node tools/mock-9099.cjs`，请求日志在 `%TEMP%\edge-mock\bom-mock.log`

## 1. 数据源

数据源 = **连接实例**（地址 + 鉴权），不绑定具体表/队列/接口路径（那些在动作里配）。

操作步骤：

1. 打开「数据源」页，点「新建数据源」；
2. 填：名称、站点 ID、类型（HTTP / MySQL / RabbitMQ）、地址；
3. 按类型填 `addition`（JSON）：
   - **HTTP**：地址填根地址（如 `http://localhost:9099`）；addition 填 `{"token":"..."}`（可选，Bearer 鉴权）
   - **MySQL**：地址填 `jdbc:mysql://host:3306/库名`；addition 必填 `{"url":"jdbc:...","username":"...","password":"..."}`
   - **RabbitMQ**：地址填 `amqp://host:5672`（或 `host:5672`）；addition 可填 `{"host":"...","port":5672,"username":"guest","password":"guest"}`（有默认值）
4. 保存后可用「测试连通性」验证；「结构探测」输入表名或样例 JSON 查看字段列表（供后续映射用）。

## 2. 任务

操作步骤：

1. 打开「任务」页，点「新建任务」；
2. 填：名称、站点（下拉）、**方向**（北向=客户→MES，南向=MES→客户；只是业务标签）、cron（可选，留空=不自动）、重试次数、退避毫秒；
3. 保存后任务默认停用，点「启用」才注册路由。

常用操作：启停（「停用/启用」）、手动执行（「执行」，会立刻跑一次并更新最近执行状态）、删除。

> 注意：动作变更后路由会自动重建；若任务处于“启用但无消费者”的异常态，先停用再启用一次。

## 3. 动作（动作链）

点任务行的「动作」进入动作链，点「添加动作」。填：名称、类型、序号（执行顺序）。按类型填各自字段：

| 类型 | 必填字段 | 说明 |
|---|---|---|
| READ_SQL | 数据源（MySQL）、SQL、水位策略、水位列 | 增量读取；SQL 里可用 `{watermark}` 占位 |
| WRITE_UPSERT | 数据源（MySQL）、表名、幂等键 | 按业务主键 upsert |
| HTTP_CALL | 数据源（HTTP）、路径、方法 | 地址取数据源根地址 + 路径 |
| MQ_CONSUME | 数据源（RabbitMQ）、队列 | **只能作为链首（序号 1）**；exchange/routingKey 可选 |
| MQ_PUBLISH | 数据源（RabbitMQ）、exchange、routingKey/queue、body 步骤 | 发消息通知 |
| FIELD_MAP | 见下一节 | 字段映射 |

约束：一条任务最多一个 MQ_CONSUME、最多一个带水位的 READ_SQL。保存后「校验任务」可看配置问题。

## 4. 字段映射

映射编辑器为三栏：左来源树 / 中映射表 / 右目标树。推荐流程：

1. **来源**：贴一份来源样例 JSON（或从已配读动作探测），生成左树；
2. **目标**：贴目标字段样例 JSON，点「导入目标字段」生成右树；
3. **获取推荐**：系统按字段名/类型/历史给候选；点「采用」落行，或「一键自动填充」；
4. **数组组**：目标里的数组节点（如 `bomLines[]`）点选后展开子表——先选来源数组（如 `product.bomItems`），子表里字段用相对路径；行号用 `$index`（转换选 INDEX，可从 1 开始）；
5. **转换**：行内下拉 STRING/INT/DATE/ENUM/CONSTANT/INDEX/CONCAT；ENUM 和 CONCAT 点开弹窗编辑（不用手写 JSON）；
6. 点「校验配置」确认无误后保存。

高级：FIELD_MAP 动作支持 `mode:EXPRESSION`，直接写 JSONata 表达式（逃生通道，常规场景不需要）。

## 5. 消息历史与断点重投

任务执行会记录为**运行（job_run）+ 消息步（message_step）**，成功与失败全程可见：

1. 打开「消息历史」页（默认查 FAILED，可按任务过滤、按业务键检索、分页）；
2. 「消息步」看每条消息穿过每个动作的结果（动作名/状态/数据/错误），可按运行/消息/业务键分组；
3. 修复原因（如目标服务恢复）后，对一条 `FAILED` 消息步点「重投」——系统用**该步数据**从该动作**精确断点续跑**，成功后该消息步状态变 `REDELIVERED`。

> 旧 `dead_letter` 已弃用（无迁移）；新数据从 `job_run`/`message_step` 开始累积。

## 6. 模板

「模板」页：新建模板填名称 + `configJson`（`{"job":{...},"actions":[...]}`），支持导出/导入/版本管理，用于把固定部分沉淀复用。

## 7. 站点与外部触发

「站点」页：站点名称、时区、建议器覆盖；可生成 **API Key（仅显示一次，妥善保存）**。

外部系统触发任务：

```powershell
curl -X POST http://<网关地址>:8080/api/external/jobs/<任务ID>/trigger `
  -H "X-API-Key: <站点APIKey>" -H "Content-Type: application/json" `
  -d '{"字段":"值"}'
```

## 8. 常见问题

- 消息“消失”但任务显示成功：检查 HTTP_CALL 动作的数据源是不是指向了网关自己（SPA 兜底会返回 200），目标应是真实接口或 mock；
- 队列收不到：确认 MQ_CONSUME 的队列名与发布方一致，任务已启用；
- 消息步重投失败：任务未启用，或该步初始输入不是 JSON。
