# 代码审查笔记（进行中）

> **状态：已冻结为历史档案（2026-08-26）**。本文件是 2026-08 开发期的决策流水账，不再更新；决策演进与当前现状见 `docs/features/`，决策基线见 `docs/adr/`，用户功能见 `docs/user-guide.md`。

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
- **状态**：已实施（commit b6a007c，2026-08-19；spec #44 / T24 / T25）。

### DS-1 实施机制：统一异常体系与错误码注册表

- **现状盘点（2026-08-19）**：54 处 `throw new`，5 种异常。`IllegalArgumentException`×31（语义过载：参数/状态/配置全混）、`NoSuchElementException`×10（404）、`IllegalStateException`×7（未映射，500 走 Spring 默认格式）、`ConflictException`×5、`UnauthorizedException`×1。错误码不存在，同语义文案散落多处（数据源/任务/模板重名各自拼接 message）。
- **方案**：
  1. `BusinessCode` 枚举作为唯一错误码注册表：`code`（字符串，稳定不变）+ 默认 message + `httpStatus`；粒度到"前端需要分支的场景"（如 `DUPLICATE_NAME`、`JOB_NOT_ENABLED`），不到 message 粒度。
  2. `BusinessException extends RuntimeException`：携带 code + message + 可选 field。
  3. `ApiExceptionHandler` 只处理 `BusinessException`，并加 `Exception` 兜底 → `INTERNAL_ERROR` 信封（消灭漏网与 Spring 默认格式）。
  4. 现有 5 个异常类收敛为 `BusinessException`（或子类继承它，默认 code）。
- **状态**：已实施（与 DS-1 一并落地：BusinessCode 枚举、BusinessException、handler 统一信封、ResponseBodyAdvice 成功包装；Conflict/Unauthorized 异常收敛，任务未启用→JOB_NOT_ENABLED）。

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
- **状态**：已实施（commit da5afc9 / 213e0db，2026-08-19；spec #47 / T26）。
  - 修正：EXTERNAL_PROPERTY 多态对 record（构造参数）不可用（Jackson 限制），改由 DataSourceRequest @JsonCreator 按 type 手工分派 + 各 from() 拒绝未知键/值级校验。

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

## DS-6 数据源粒度统一（方案 A：连接级）

- **提出**：数据源粒度不一致——MySQL 是连接级（表在动作 SQL），MQ/HTTP 是资源级（队列/接口路径绑死在数据源）。
- **现状**：MySQL address=服务器+库、表在动作；MQ address=broker、队列在 addition；HTTP address=完整接口路径。CONTEXT.md 原定义（"某个 HTTP 地址、某个数据库连接、某个 MQ 队列"）本身混了两种粒度。
- **决策：方案 A（统一连接级）**：数据源 = 连接实例（地址+鉴权），具体资源归动作：
  - HTTP：address 为根地址（scheme://host:port），`path` + `method` 移入动作 addition；RouteGenerator 组装 base + path。
  - MQ：address 为 broker，`queue`（/exchange/routingKey）移入动作 addition。
  - MySQL：不变（表已在动作 SQL）。
- **动机**：凭证只存一份（换域名/broker 只改一处）、模板复用统一、避免同系统多接口/多队列的凭证重复（10+ 接口场景放大）。
- **连带**：动作契约变更（与 DS-2 一起实施，成本最低点）；RouteGenerator 组装逻辑；映射历史 sourceKey 从动作推导；CONTEXT.md 术语已更新为连接级定义；实施时起草 ADR-0007。
- **状态**：已实施（commit 213e0db，2026-08-19；ADR-0007；spec #47 / T27）。HTTP 数据源=根地址+token、MQ queue 移动作、路由组装 base+path、南向 sourceKey 从动作 queue 推导；本地存量数据已迁移并验证北向/南向链路。

## DS-7 任务与动作创建流程重设计

- **提出（2026-08-19，前端实测 7 条）**：
  1. 创建任务/动作与数据源没有关系——数据源引用只存在于 addition JSON 里，界面无关联；
  2. 站点选择是数值输入框（`el-input-number`），不知道对应哪个站点；
  3. 水位策略放在任务上不搭——建任务时还不知道源表和字段；水位应属于读动作（与 DS-6"资源归动作"一致）；
  4. 动作 addition 与数据源同样不特化（Map + JSON textarea，同 DS-2 问题）；
  5. 远程访问动作没有"选择数据源"的入口，`dataSourceId` 要手写进 JSON；
  6. 字段映射虽已有表格（MappingEditor），但来源样例/目标样例仍是 JSON textarea；来源字段路径无点选/补全入口（`loadSampleFields` 未接按钮）；ENUM 的 enumMap 要手写 JSON；
  7. 结论：动作创建需要重新设计，当前版本不可用。
- **目标形态**：
  - 任务表单：站点改为下拉（显示名称+时区，后端 `/api/sites` 已就绪）；水位策略/水位列移出任务，改放到"读动作"上（水位值仍按任务存）；任务保留 cron、重试、退避（任务级策略）。
  - 动作表单按类型特化（DS-2 延伸到 ActionRequest）：
    - 远程访问：数据源下拉（按站点过滤，显示名称+类型+地址）；类型相关字段——MySQL 读（SQL + 表名推断 + 水位设置）、MySQL 写（表名 + 幂等键）、HTTP（路径 + 方法）、MQ 消费（队列）、MQ 生产（exchange/routingKey）。
    - 字段映射：来源样例改从"已配置的读动作"探测，而非手贴 JSON；来源字段路径点选/自动补全；ENUM 用可视化键值编辑，不写 JSON。
  - 动作链：序号支持上移/下移；动作列表显示所用数据源与方向摘要。
- **状态**：已实施（commit 66ae7d0 / 9737fe7，2026-08-20；spec #50 / T28~T32）：任务表单站点下拉+水位移除、六种 ActionKind 动作表单特化、数据源下拉、映射编辑器 sourceStep 选择器。

## DS-8 路由生成器：固定模板 vs 通用动作组合

- **现状**：`RouteGenerator.build` 只支持两个固定形状——北向（MySQL 读+水位 → 字段映射 → HTTP 写）和南向（MQ 消费 → 字段映射 → MySQL upsert）。动作链不是"组合单元"：生成器只取"第一个/最后一个 REMOTE_ACCESS + 第一个 FIELD_MAPPING"，其余动作被忽略；最后一个 remote 动作若指向非 HTTP 数据源，北向会 cast 失败导致路由注册 500。
- **结论**：在动作链里插入"MQ 发送"等其他动作**当前不支持**——插中间被静默忽略，插末尾（非 HTTP 数据源）导致注册失败。更广义的多读/条件/并行/数组映射均不支持。
- **根因**：动作只有 REMOTE_ACCESS/FIELD_MAPPING 两种类型，读写意图靠位置和 addition 内容推断，没有显式动作语义（READ_SQL/WRITE_UPSERT/MQ_PUBLISH...）；设计文档 §5 的"上下文列表/来源步骤"模型未实现（`FieldMapper(List.of())` 传空历史）。
- **改造方向**：动作升级为有明确语义的种类（或在 RemoteAccessConfig 加 operation 枚举）；RouteGenerator 改为"每类动作生成一个 Camel 片段、按 index 顺序串联"；上下文列表（动作输出按序保存，字段来源=步骤N.路径）落地。
- **关联**：与 DS-7（动作重设计）同一批；这也是"对接任务同时发消息"这类真实场景的前置。
- **细化设计**：见 [docs/action-chain-design.md](../action-chain-design.md)（动作种类目录、上下文列表、片段组合、迁移路径、影响面、实施顺序、验收场景）。
- **状态**：已实施（commits 66ae7d0 / 6b079ad / 4f1f327 / d7790b1 / 9737fe7，2026-08-20；spec #50）：ActionKind 六种语义 + typed config、上下文列表/sourceStep、片段组合生成器（MQ_PUBLISH 验收场景"同步+通知"端到端跑通）、水位迁移到 READ_SQL。

### DS-8 补充：入口命名遗留

- [RouteGenerator](D:/workspace/programs/edge-gateway/spike/src/main/java/com/example/edgegateway/service/RouteGenerator.java:82) 的 `buildNorthbound`/`buildSouthbound` 已不是模板，只是入口形态不同（轮询入口 direct/timer vs 消费入口 MQ），内部共用 `appendChain`/`appendFragment`。
- 建议改名 `buildPollingEntry` / `buildConsumerEntry`，消除"还有南北模板"的误导。
- **状态**：已实施（commit 67ee48c，2026-08-21）：RouteGenerator 入口改名 buildPollingEntry / buildConsumerEntry。

## DS-9 任务方向显式化（用户声明北向/南向）

- **提出**：方向不应从链入口推断——"南向不一定走 MQ 发消息"。反例：客户系统发 MQ → 边缘消费 → 推 MES HTTP，入口是 `MQ_CONSUME` 但数据方向是**北向**；当前 `inferDirection`（首动作 MQ_CONSUME → SOUTH）会误判为南向，映射历史隔离和字段校验策略都会错。
- **方案**：
  1. `Job` 增加 `direction` 字段，用户在创建任务时显式声明（北向/南向），不再从链推断；
  2. 映射历史与校验策略改用 `job.direction`；`inferSourceKey` 改为从**实际源动作**推导（`MQ_CONSUME` → `mq:queue`、`READ_SQL` → `db:table`），与 direction 解耦；
  3. **不做方向↔入口一致性校验**（已确认 2026-08-20）：数据的读写可以任意组合 MQ/HTTP/SQL，本质是系统对接；方向只是业务标签，路由入口仍由链首动作种类决定。
  4. 连带修正：`JobValidationService` 的"北向才做源字段探测对照"改为**按源动作判断**——链中只要有 `READ_SQL` + `FIELD_MAP` 就做探测对照（可探测性由源动作决定，不由方向标签决定）。
- **影响面**：Job/JobRequest/JobView、MappingHistoryService（record/list/sourceKey）、MappingSuggestionService、JobValidationService、存量数据迁移（按原推断回填）、前端任务表单（方向选择）。
- **状态**：已实施（commit 67ee48c / a663aa1，2026-08-21；spec #56 / T33~T34）：Job.direction 显式声明、inferSourceKey 按源动作推导（与方向解耦）、校验探测按源动作判断、RouteGenerator 入口改名、存量回填、前端方向选择。反例场景（MQ_CONSUME 入口 + NORTH 方向）端到端验证通过。

## DS-10 映射编辑器体验（2026-08-21 实测反馈）

### 10.1 推荐结果展示

- **问题**：获取推荐数量多时需下滑很远才能看到/采用；当前"候选 chip"横向排列样式不佳；已采用的推荐无标记。
- **建议**：推荐区改为**表格 + 分页**（按目标字段分组，每行一条候选：来源路径/得分/来源标签/转换/操作），采用后该行标注"已采用"并禁用；已填充的目标字段高亮。
- **状态**：已实施（2026-08-21，前端 MappingEditor 重做）：建议面板改为 el-table + 分页（每页 8 条、max-height 320），列含 目标字段/来源路径/匹配方式/转换/置信度/操作；采用后该候选自动变"已采用"标签并禁用，已填充目标字段高亮；重新获取推荐时回到第 1 页。

### 10.2 复杂层级映射（制品→BOM）

- **问题**：当前 `FieldMapper` 只支持扁平点号路径 + 数组下标（`data.items[0].name`），对"两个层级一起"的嵌套映射（如 源 header/items → 目标 header/bomLines）支持不足。
- **调研**：见 [docs/research/mapping-framework.md](../research/mapping-framework.md)（开源框架与可视化方案调研，含推荐路线）。
- **结论（2026-08-21 更新）**：采用**路线 A（多级表格映射）**为主通道，**执行引擎定为 JSONata**——前端传结构化规则，后端编译为 JSONata 表达式并经 `camel-jsonata`（IBM JSONata4Java）执行，自研 FieldMapper 不再扩展；行号/拼接/枚举原生支持，DATE 格式化用自定义函数（S0 spike 验证）；**路线 B 与 A 合并**（手写表达式即高级通道）；JOLT 降为备选；AtlasMap 出局（项目已停维护，camel-atlasmap 在 Camel 4 被移除，见 CAMEL-19686；本项目 Camel 4.10.2 无此组件）。
- **状态**：已决策（2026-08-21），细化设计见 [mapping-framework.md](../research/mapping-framework.md) §4；spec #59 拆 T35~T39 推进中：
  - **S0 已完成**（T35 #60，commit 7c3444d）：dashjoin jsonata 0.9.10 直调执行，BOM 全场景验证通过；
  - **S1 已完成**（T36 #61，commit e5d4bed）：FieldMappingRule 递归树 + JsonataRuleCompiler + JsonataFieldMapper，FIELD_MAP 路由改走 JSONata，旧扁平规则行为兼容；
  - **S2 已完成**（T37 #62，commit ce50dad/6171891）：递归规则校验（配置期 400）、历史/模板兼容、mode:EXPRESSION 手写表达式逃生通道、JSONata 输入规范化（LocalDateTime 等 JDBC 类型，本地实测发现）；
  - **S3 已完成**（T38 #63，commit c1e24fd）：前端多级表格（组行 expand 内嵌子表）+ 来源/目标双树点选 + ENUM/CONCAT 弹窗 + mode:EXPRESSION 切换，Playwright 实测通过；
  - **S4 已完成**（T39 #64，commit 6541d89）：建议器输出组建议（源 [0] 数组路径 → 目标数组，children 相对路径 + 转换推断，MatchSource.ARRAY），单值样例 CONSTANT 修复随票回归，评测基线无变化；
  - **DS-10 全链路闭环**（2026-08-21）：spec #59 已关闭。

### 10.3 保存字段映射动作失败（静默无提示）

- **现象（2026-08-21 实测复现）**：添加 FIELD_MAP 动作时，若某行转换选 **ENUM** 且"常量/枚举/日期"列输入了非合法 JSON（如 `1:待处理`），点击保存**无任何提示**，弹窗不关、无请求发出。
- **根因**：`MappingEditor.getRules()` 对 ENUM 直接 `JSON.parse(extra)`；该函数在 `JobsView.saveAction()` 的 try/catch **之外**的 `buildActionAddition()` 里同步调用，异常成为未处理的 Promise rejection，前端静默失败（`SyntaxError: Unexpected non-whitespace character after JSON at position 1`）。
- **修复**：
  1. `getRules()` 对 ENUM 做 try/catch 校验，抛友好错误（`第 N 行映射：枚举映射必须是合法 JSON，如 {"1":"已处理"}`）；
  2. `saveAction()` 把 `buildActionAddition()` 移入 try/catch，配置构建类错误统一走 `ElMessage.error` 提示；
  3. 顺带修复 `setRules()` 的 `??` 链（`constant ?? enumMap ?? datePattern` 优先级问题会导致编辑 DATE 规则时 `datePattern` 丢失），改为按优先级取一。
- **验证**：Playwright 真实浏览器回归——ENUM 坏 JSON 现在弹 toast 且不崩溃；基础 STRING 映射保存成功（后端 201）；建议面板表格/分页/已采用标记正常（30 条建议 → 每页 8 行 + Total 30）。
- **状态**：已修复（2026-08-21，前端 MappingEditor.vue + JobsView.vue）。

### 10.4 建议器把单值样例推荐成常量（待办）

- **现象（2026-08-21 实测）**：来源样例 `orderNo` 只有一个值 `PO-001` 时，`/mapping-suggestions` 返回 `transform=CONSTANT, constant="PO-001"`；用户"采用"后映射变成写死常量，语义错误。
- **建议**：常量推荐仅在"同一字段出现多次且值全部相同"时给出；单值样例按 STRING 直接映射处理。
- **状态**：已实施（2026-08-21，commit 587172a）：`TransformInferrer` 改为仅当字段出现多次且去重后值全部相同才推荐 CONSTANT；单值样例按 STRING 直接映射。新增 `TransformInferrerTest`（单值 STRING / 重复全同 CONSTANT 两个用例），本地实测通过。
