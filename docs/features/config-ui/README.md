# 配置界面与部署形态

Vue 3 + TS + Vite + Element Plus 独立前端工程；工程分离、部署合一。

## 当前设计（现状）

- **技术栈（ADR-0006）**：`frontend/` 独立工程（Vue 3 + TypeScript + Vite + Element Plus + vue-router）；页面：数据源 / 任务（含映射编辑器）/ 死信 / 模板 / 站点，路由在 `frontend/src/router/index.ts`。
- **API 客户端**：`frontend/src/api/client.ts` 统一走 `/api` 前缀、解包 `{code,message,data}` 信封，`code!=OK` 抛 `ApiError(code,message)`；`/mock` 代理到后端 mock 端点。
- **表单特化**：数据源/动作按类型渲染字段（六种 ActionKind 各自表单）；映射编辑器三栏 + 多级表格 + 推荐面板（`MappingEditor.vue`）。
- **开发期**：Vite dev server 5173 代理 `/api`、`/mock` → 8080，热更新；**部署期**：Docker 多阶段构建（node 构建 dist → 拷入 Spring Boot 镜像），单镜像、同源、无 CORS。
- **代码位置**：`frontend/src/views/*.vue`、`frontend/src/components/MappingEditor.vue`、`frontend/vite.config.ts`、`spike/Dockerfile`。

## 决策时间线

| 日期 | 事件 | 为什么 | 被谁修正 | 锚点 |
|---|---|---|---|---|
| 08-18 | 前后端代码分离 + 单镜像交付 | 单文件原生 JS 不利于维护与迭代 | — | ADR-0006 |
| 08-19 | 前端表单特化（按类型渲染） | addition 手填 JSON、报错无字段定位 | 已实施（与 DS-2 同源） | DS-5 |
| 08-30 | 数据源 addition 改按类型特化表单（以 address 为准 + 高级 JSON 兜底） | 数据源字段错位、手动 JSON 易错 | — | DS-5 延伸 |

## 待办 / 已知限制

- 死信/模板/站点页面为表格 + 弹窗，交互相对简单。

## 决策时间线

| 日期 | 事件 | 为什么 | 被谁修正 | 锚点 |
|---|---|---|---|---|
| 08-18 | 前后端代码分离 + 单镜像交付 | 单文件原生 JS 不利于维护与迭代 | — | ADR-0006 |
| 08-19 | 前端表单特化（按类型渲染） | addition 手填 JSON、报错无字段定位 | 已实施（与 DS-2 同源） | DS-5 |
| 08-30 | 数据源 addition 改按类型特化表单（以 address 为准 + 高级 JSON 兜底） | 数据源字段错位、手动 JSON 易错 | — | DS-5 延伸 |

## 当前生效

- 工程分离、部署合一（ADR-0006）
- 表单按动作/数据源类型特化
- 数据源 addition 按类型特化（以 address 为准 + 高级 JSON 兜底）
