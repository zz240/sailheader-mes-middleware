# 功能页（Features）

一句话：每个功能一页，回答「**怎么用 + 现在是什么**」。为什么长这样，看 `docs/adr/`。

## 从哪里开始

| 我想 | 去哪 |
|---|---|
| 先看系统整体长什么样 | 根 `README.md` 的「它为什么长这样」 |
| 照着配一条链 | `docs/user-guide.md` |
| 查一个词是什么意思 | 根 `GLOSSARY.md` |
| 知道当初为什么这么选 | `docs/adr/` |

## 功能索引

| 功能 | 页面 | 相关决策 |
|---|---|---|
| 数据源 | [data-source](data-source/README.md) | ADR-0007 / ADR-0014 |
| 任务与动作链 | [job-action-chain](job-action-chain/README.md) | ADR-0017、ADR-0015 |
| 字段映射 | [field-mapping](field-mapping/README.md) | ADR-0005 / ADR-0011 / ADR-0012 |
| 可靠性（失败/重试/历史/重投） | [reliability](reliability/README.md) | ADR-0003 / ADR-0009 / ADR-0017 |
| 配置界面与部署形态 | [config-ui](config-ui/README.md) | ADR-0006 / ADR-0013 |
| API 契约 | [api-contract](api-contract/README.md) | — |
| 通信模型（南北向） | [transport](transport/README.md) | ADR-0002 / ADR-0010 |

## 页面怎么写

结构见 `AGENTS.md` 的「文档规范 · 功能页模板」。三条最容易忘的：

- **正文不写类名**，类名只在末尾「代码位置」表里。
- **票号只在决策时间线**，正文不写 `#123`。
- **限制与绕法**用两张表：做不到的、要注意的。

## 维护规则

每次改动，五分钟做完：

1. **归类**：这次改了哪个功能，可能不止一个。
2. **用户侧**：`docs/user-guide.md` 对应那一行要不要改。
3. **功能页**：受影响页的时间线加一行（日期 / 事件 / 为什么 / 锚点）。
4. **方向性变化**：写新 ADR；旧 ADR 只追加状态行，不改正文。
5. **删功能**：删页面、更新本索引，**再查所有页的「限制与绕法」**——那里最容易留下描述死功能的条目。

> ADR 的约定：轻量可改。允许在旧 ADR 追加状态与 `Amended by` / `Superseded by` 链接，不重写决策正文。

> 里程碑时核对一次：user-guide 的功能行、功能页的现状、ADR 的状态，三者说的是不是同一件事。
