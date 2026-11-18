# API 契约

一句话：所有业务接口返回同一个信封，错误用稳定的字符串业务码，前端只认 `code`。

## 信封长什么样

| 情况 | 返回 | HTTP 状态 |
|---|---|---|
| 成功 | `{code:"OK", message:"ok", data:...}` | 200 / 201 |
| 失败 | `{code:"<业务码>", message:"...", data:null}` | 400 / 404 / 409 / 500 |

HTTP 状态保留语义，不一律 200。错误体还可以带一个 `field`，指到具体输入框。

## 业务码

集中在一个枚举里定义。**`code` 稳定不变，`message` 可以改文案。**

| 码 | 什么时候 |
|---|---|
| `OK` | 成功 |
| `PARAM_INVALID` | 入参不合法，通常带 `field` |
| `NOT_FOUND` | 目标不存在 |
| `CONFLICT` | 状态冲突 |
| `DUPLICATE_NAME` | 重名 |
| `JOB_NOT_ENABLED` | 任务没启用就执行 |
| `UNAUTHORIZED` | API Key 不对 |
| `INTERNAL_ERROR` | 兜底 |

粒度标准：**前端需要分支的场景**才单独给码，不需要分支的用大类。

## 实现要点

- 业务异常统一走 `BusinessException(code, message, field)`；处理器只接它和 `Exception` 兜底，把 Spring 的默认错误格式挡住。
- 成功响应由 `ResponseBodyAdvice` 统一包信封；`/mock/**` 和 204 不包。
- 分页参数是 `page/size`，返回 `{total, records}`——目前只有消息历史在用。

## 限制与绕法

| 注意 | 会看到什么 | 怎么办 |
|---|---|---|
| 分页约定没推广 | 任务、数据源列表还是全量返回 | 数据量小，先接受 |
| `field` 只设计、没全用 | 前端高亮输入框只落了部分 | 新增接口时顺手带上 |

## 代码位置

| 区域 | 类 | 职责 |
|---|---|---|
| 成功包装 | `ApiEnvelopeAdvice` | 统一信封；排除 mock 与 204 |
| 异常兜底 | `ApiExceptionHandler` | BusinessException → 对应状态，其余 → INTERNAL_ERROR |
| 错误码 | `BusinessCode` | 字符串码的唯一定义处 |
| 前端解包 | `api/client.ts` | 解 `{code,message,data}`；`code!=OK` 抛 `ApiError` |

## 决策时间线

| 日期 | 事件 | 为什么 | 锚点 |
|---|---|---|---|
| 08-19 | 统一业务码信封 | 原格式三种混用；要对接 10+ 接口，需要稳定业务码 | DS-1 |
| 08-19 | 统一异常体系（BusinessException + 错误码注册表） | 54 处 throw、5 种异常语义混用 | DS-1 |
