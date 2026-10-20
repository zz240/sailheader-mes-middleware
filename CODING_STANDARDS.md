# 代码规范

> 评审按这份判。**能自动判的已经落到检查里（下面标了「自动」），剩下的才是判断题。**
> 这份文件没写的，以邻近代码为准——先读三个同类文件，再动手。

## 提交信息（自动：`.githooks/commit-msg`）

- 首行 `type(scope): #票号 主题`，type 取 `feat fix docs refactor perf test build ci chore`。
- 中文；正文分段写「为什么改」和「哪些行为变了」，不写"更新了若干文件"这种无信息量的话。
- 提交信息里有 `--noEmit`、`--force` 这类以 `--` 开头的片段时，**只能用 `git commit -F 文件`**，
  `-m` 会被 git 当成选项解析并报 unknown option。
- 钩子会挡住格式不对的首行。`--no-verify` 只在合并冲突、rebase 这类场景用。

## 语言

- 注释、javadoc、异常信息、日志、文档：**中文**。
- 标识符、包名、测试方法名：**英文**。
- 注释说「为什么」和「牺牲了什么」，不复述代码；能挂票号的挂上 `#N`。

## 后端（`spike/`，Java 21 · Spring Boot 3.4 · Camel 4.10）

- 动作配置类统一「四件套」，参照 [`spike/src/main/java/com/example/edgegateway/config/action/MqPublishConfig.java`](../spike/src/main/java/com/example/edgegateway/config/action/MqPublishConfig.java)：
  - `record XxxConfig(...) implements ActionConfig`
  - `static from(Map<String,Object>)`：先 `rejectUnknownKeys`，未知键抛 `IllegalArgumentException("未知字段: " + k)`
  - `validate()`：缺必填、越界时抛**中文** `IllegalArgumentException`
  - `toMap()`：只写非 null 字段，键顺序稳定
- 分层：`web`（Controller + `web/dto`）/ `service` / `mapper` / `model`。Controller 只做参数与响应封装，业务判断不进 Controller。
- 数据库：Flyway 迁移 `V<n>__<snake_case>.sql`，**只增不改**，必须幂等（可重复执行不报错）。
- 测试：JUnit 5 + AssertJ；方法名英文、javadoc 中文。测试用 H2，不依赖本地 MySQL。

## 前端（`frontend/`，Vue 3 + TS + Vite + Element Plus）

- `<script setup lang="ts">`；页面在 `views/`，可复用件在 `components/`。
- 颜色、字号、圆角一律引用 [`frontend/src/styles/tokens.css`](../frontend/src/styles/tokens.css) 里的令牌，
  **不写死 hex**；不改 Element Plus 组件内部样式去硬压——生产样式里 `!important` 保持 0 处（规则写在 tokens.css 文件头）。
- 与后端交互的类型集中在 `src/types.ts`。

## 文档

- 活文档：`docs/features/**`、`docs/flows/**`、`README.md`、`docs/user-guide.md`——里面的 `file:line` 要跟着代码改。
- 冻结的历史：`docs/adr/**`、`docs/archive/**`——只允许追加状态与「Amended by」链接，**不改正文**。
- 随代码走的约定放 `docs/agents/`；`AGENTS.md` 只放指针，不放细节。

## 判断题（没有工具替你判）

- 改动是否和邻近代码同构：同样的错误措辞、同样的日志字段、同样的 DTO 形状。
- 跨文件一致性：同一个概念在 UI、DTO、SQL、文档里是不是同一个词，以 `GLOSSARY.md` 为准。
- 新增字段是否同时改了四层：DTO → 校验 → 落库 → 前端展示。漏一层就是线上 bug。
