# API 契约

全部业务接口返回统一信封，错误为稳定字符串业务码。

## 当前设计（现状）

- **信封（DS-1）**：成功 `{code:"OK", message:"ok", data:...}`；失败 `{code:"<业务码>", message:"...", data:null}`（错误体可带 `field` 定位输入框）；HTTP status 保留语义（200/201/400/404/409/500）。
- **错误码注册表**：`BusinessCode` 枚举集中定义字符串码（OK / PARAM_INVALID / NOT_FOUND / CONFLICT / INTERNAL_ERROR / DUPLICATE_NAME / JOB_NOT_ENABLED / UNAUTHORIZED 等），`code` 稳定不变、`message` 可随文案调整；粒度到“前端需要分支的场景”。
- **异常体系**：`BusinessException(code, message, field)`；`ApiExceptionHandler` 只处理 BusinessException + `Exception` 兜底 → INTERNAL_ERROR，消灭 Spring 默认格式漏网。
- **成功包装**：`ApiEnvelopeAdvice`（ResponseBodyAdvice）统一包装成功响应；`/mock/**` 端点与 204 不包装。
- **分页约定**：列表分页参数 `page/size`，返回 `{total, records}`（目前死信已用，其余列表待统一）。
- **代码位置**：`web/ApiEnvelopeAdvice.java`、`web/ApiExceptionHandler.java`、`web/BusinessCode.java`（或 model 下）、`frontend/src/api/client.ts`。

## 决策时间线

| 日期 | 事件 | 为什么 | 被谁修正 | 锚点 |
|---|---|---|---|---|
| 08-19 | 统一业务码信封 | 原格式三种混用；对接 10+ 接口需要稳定业务码 | — | DS-1 / b6a007c |
| 08-19 | 统一异常体系（BusinessException + 错误码注册表） | 54 处 throw、5 种异常语义混用 | — | DS-1 实施机制 |

## 待办 / 已知限制

- 分页约定尚未推广到 jobs/data-sources 等列表接口（仅死信）；
- 错误体 `field` 字段已设计，前端高亮输入框尚未全面落地。

## 决策时间线

| 日期 | 事件 | 为什么 | 被谁修正 | 锚点 |
|---|---|---|---|---|
| 08-19 | 统一业务码信封 | 原格式三种混用；对接 10+ 接口需要稳定业务码 | — | DS-1 / b6a007c |
| 08-19 | 统一异常体系（BusinessException + 错误码注册表） | 54 处 throw、5 种异常语义混用 | — | DS-1 实施机制 |

## 当前生效

- 信封 + BusinessCode（OK/PARAM_INVALID/NOT_FOUND/CONFLICT/INTERNAL_ERROR 等）
- 错误体带 field 可定位输入框
