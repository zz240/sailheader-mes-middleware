# 功能演化页（Features）

按**功能**聚合的决策时间线，是开发者理解“某个功能为什么长这样”的导航入口。

- 现状（用户视角）：[docs/user-guide.md](../user-guide.md)
- 术语：仓库根 `CONTEXT.md`
- 决策详情：`docs/adr/`（正式记录）与 `docs/code-review-notes.md`（历史流水账，已冻结）；本目录**只引用不复制**

## 维护规则（每次变更 5 分钟）

1. **归类**：改动影响哪个功能（可多个）；
2. **用户侧**：更新 `docs/user-guide.md` 对应功能行（功能 → 入口 → 验收）；
3. **演进侧**：受影响功能页时间线**加一行**（日期/事件/为什么/锚点）；
4. **方向性变化**：写新 ADR，旧 ADR 标 Superseded/Amends 并互链；
5. **提交带锚点**：commit 消息写 `T#` / `DS#`，让时间线可反查。

> ADR 约定（2026-08-27 确认）：**轻量可改**——允许在旧 ADR 追加状态与 `Amended by` / `Superseded by` 链接，不重写决策正文。

> 检查：每个里程碑核对 user-guide 功能行、功能页“现状”段、ADR 状态三者一致。

## 页面格式要求

每个功能页必须包含 **「当前设计（现状）」** 一节，让新开发者**读这一页就能直接干活**，不需要先翻全部 ADR：

- 数据模型与字段（Job/Action/DeadLetter 等实体）；
- 核心规则与约束（必填项、链约束、handled 策略等）；
- 关键流程（路由怎么生成、重投怎么跑、映射怎么执行）；
- 代码位置（哪些类/文件）；
- 待办与已知限制。

ADR 只提供“为什么”，现状细节写在本页；发现页面与代码不一致时，更新页面（页面以代码为准）。

## 功能索引

| 功能 | 演化页 | 相关决策 |
|---|---|---|
| 数据源 | [data-source](data-source/) | DS-2/3/4/6，ADR-0007 |
| 任务与动作链 | [job-action-chain](job-action-chain/) | DS-7/8/9 |
| 字段映射 | [field-mapping](field-mapping/) | DS-2/5/10，ADR-0005 |
| 可靠性（失败/死信/重投） | [reliability](reliability/) | ADR-0003 |
| 配置界面与部署形态 | [config-ui](config-ui/) | DS-5，ADR-0006 |
| API 契约 | [api-contract](api-contract/) | DS-1 |
| 通信模型（南北向） | [transport](transport/) | ADR-0002，DS-9 |
