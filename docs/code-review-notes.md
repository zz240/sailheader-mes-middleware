# 代码审查笔记（进行中）

> 起始：2026-08-18，随接口走查持续补充。范围：spike 工程。
> 格式：建议 → 现状/评估 → 决策 → 影响面 → 状态。

## DS-1 统一响应格式：业务码信封

- **提出**：`ResponseEntity.status(HttpStatus.CREATED)` 这种返回格式是否通用？真实系统是否需要自定义 code/格式？有提示需要前端处理时直接用 Spring 默认响应是否不便？
- **现状**：成功时 create 用 201+裸 body，其余 200+裸 body；错误 400/404/409 纯文本；未捕获异常 500 走 Spring 默认 JSON——三种格式混用。
- **评估**：表单式内部工具场景下 HTTP status 够用；但项目预计对接 10+ 接口，多客户端/外部集成时需要业务码，当前成本低，值得标准化。
- **决策**：全部业务接口统一返回业务码信封，成功 `code="OK"`，失败为字符串业务错误码；HTTP status 保留语义（200/201/400/404/409/500），业务码供前端分支处理：

  ```json
  { "code": "OK", "message": "ok", "data": { } }
  ```

  错误码表（定稿草案，字符串码）：

  | code | 含义 | HTTP |
  |---|---|---|
  | `OK` | 成功 | 200 / 201 |
  | `PARAM_INVALID` | 参数错误 | 400 |
  | `NOT_FOUND` | 不存在 | 404 |
  | `CONFLICT` | 冲突 / 重名 | 409 |
  | `INTERNAL_ERROR` | 内部错误 | 500 |

  选型理由：字符串码自解释、前端 `switch (code)` 分支友好、Java 侧可用 `BusinessCode` 枚举保证拼写一致、对齐 RFC 7807 / Spring `ProblemDetail`；内部系统无需数字码的紧凑收益。约定：`code` 稳定不变，`message` 可随文案调整；Java 端集中定义枚举。

  mock 端点（`/mock/mes/*`）不属于业务面，保持裸响应。

- **影响面**：7 个控制器、ApiExceptionHandler、前端 `api()` 助手及调用处、相关测试。
- **状态**：待实施。

## DS-2 数据源 addition 类型化：Jackson 多态反序列化

- **提出**：`DataSourceRequest.addition` 为什么不用泛型？每种数据源应有自己独特的处理方式。
- **现状**：DTO/实体用 `Map`（MySQL JSON 列 + 敏感字段加密）；服务边界经 sealed `DataSourceConfig` 转强类型（HTTP/MySQL/RabbitMQ 各自 record + `validate()`）。类型化处理已存在；泛型并非正解（类型来自运行时 `type` 字段，Java 泛型是编译期的）。
- **缺口**：未知键不拦截（拼错字段静默通过）；校验只查必填不查取值（如 `port: "abc"` 静默回退默认值）。
- **决策**：`addition` 用 Jackson 多态反序列化直接得到类型化 record：
  - 机制：`@JsonTypeInfo(use = Id.NAME, include = As.EXTERNAL_PROPERTY, property = "type")` + `@JsonSubTypes`（name 与 `DataSourceType` 枚举名一致）。
  - 已验证：当前 Jackson（Spring Boot 3.4.5）下 EXTERNAL_PROPERTY 对 record + sealed interface 可用；DEDUCTION 推断不可靠（0 candidates），弃用。
  - JSON 契约不变：`{ "type": "MYSQL", "addition": { "url": ..., "username": ..., "password": ... } }`。
  - 复用现有 `DataSourceConfig` sealed interface，不新增平行类型体系；未知键策略建议严格（400），避免拼错字段静默入库。
  - **确认原则**：校验必须按类型各自处理（每种类型自己的必填/取值/未知键校验）；现状 sealed config 已按类型 `validate()`，DS-2 实施后由类型化 addition 承载并补齐未知键与值级校验。
- **影响面**：`DataSourceRequest`、`DataSourceConfig` 注解、`DataSourceService`（typed → Map 落库）、相关测试。
- **状态**：待实施。

## DS-3 数据源响应脱敏

- **发现**：`DataSourceView.from` 直接返回完整 `addition`（含解密后的明文密码），create/update/get/list 响应都会把凭证带回前端。
- **评估**：内部表单工具为编辑回显可以接受（加密只保护静态存储），但这是"明文凭证出网"面，外部集成/日志/抓包场景有风险。
- **建议**：视图层对敏感键脱敏（如 `password: "***"` 或只回显 mask），编辑走单独的凭证更新端点；至少要在文档里声明这个取舍。
- **状态**：暂缓（对接系统内部使用，加密必要性低；先排除加密相关更改）。

## DS-4 密钥丢失后数据源无法通过 API 恢复

- **发现**：`DataSourceService.update/delete` 都先 `get(id)`（读行即解密）；master key 不匹配时密文解不开，update/delete 全部 500，REST 层无自救路径（走查实操踩中，只能 SQL 改明文后再 PUT）。
- **建议**：提供管理端点允许以明文覆盖 addition（如 `POST /api/admin/data-sources/{id}/rewrite`），或让 update 在解密失败时走"重建"分支。
- **状态**：暂缓（同上，加密相关更改排除在本次范围外；现有 T11 加解密保持不动）。

## DS-5 前端表单特化与前后端形态

- **提出**：addition 用 JSON textarea 手填，报错只给"HTTP 数据源需要 method"，用户不知道填什么；前端需要按类型特化；单静态页是否够用、是否需要前后端分离。
- **评估**：
  - 报错缺字段定位 → 属 DS-1 范围，错误体应带 `field`，让前端能高亮具体输入框。
  - 更主动的方案：后端提供字段元数据端点（如 `GET /api/data-sources/fields?type=HTTP`），返回该类型的字段定义（名称/必填/类型/默认值/说明），前端据此动态渲染表单——新增数据源类型时后端加一个 record + 元数据，前端零改动。与 DS-2 的类型化 record 天然对应。
  - 表单特化与 DS-2 同源：后端类型化契约 + 前端类型化表单。
  - 前后端形态：当前 M0 演示静态页够用（链路已验证）；产品化（模板库、映射编辑器、日志/死信、10+ 接口）建议引入前端框架；但边缘部署建议"工程分离、部署合一"——前端产物打进同一 Spring Boot 静态目录或同容器 nginx，保持 docker-compose 单服务交付（与 ADR-0004 的运维单一化逻辑一致）。
- **状态**：待决策。
