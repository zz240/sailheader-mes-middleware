# 字段映射界面 · 高保真原型

这四支 HTML 是**自绘的单文件原型**（无构建、无依赖，双击即可打开），是这个项目**唯一的视觉参照物**。
生产实现（Vue 3 + Element Plus）的视觉口径以它们为准——见 `docs/adr/0013-console-visual-language.md`。

## 文件

| 文件 | 设计说明里的位置 | 这一版换掉了什么 |
|---|---|---|
| `01-canvas.html` | `docs/design/field-mapping-canvas-brief.md` §3–§9 | 第一版画布：来源→目标连线 + 转换检查器 + 常驻诊断条 |
| `02-workbench.html` | 同上 §10 | **关系不再用线表达**，改成目标行内的一段表达式；来源行写「已用于 N 处」 |
| `03-studio.html` | 同上 §11–§12 | 关系写成一个句子、句子当场求值；试算结果按「一次执行的判决」展示（结论条 / 计量条 / 证据表） |
| `field-mapping-ledger.html` | 补充件（见下） | 「逐行配置（原型 A）」：这是 `01/02/03` 之外的**并列方案**，不是它们的后继 |

**看视觉体系只认 `03-studio.html`**：它的 `:root` 是这套令牌的最新版（比 `01`/`02` 多 `--warn-ink` / `--danger-ink`
两个「小字号告警文字色」令牌）。`field-mapping-ledger.html` 用的是同一套令牌（0 处 hex、25 处 `color-mix(in oklch, …)`），
可以一起读，但它**不在设计说明的 source map 里**——它是什么时候、为了什么做的，说明文档没有记，`docs/design/` 里也没有对应章节。
存档时按「并列方案」保留，不做后继关系推断。

## 这套视觉体系是什么

`:root` 里十几个令牌，其余颜色全部 `color-mix(in oklch, …)` 从它们派生（四支原型合计 **0 处 hex**）：

```
--bg / --surface / --fg / --muted / --border / --accent   六色
--radius 8px / --radius-lg 12px
--fs-body 13.5px / --fs-meta 11.5px / --fs-lead 15px
--font-mono（等宽承载字段路径、类型、样例值、编译表达式）
```

设计方向（`field-mapping-canvas-brief.md` §2 原话）：**技术工具向（dense utility）**——深浅两级中性色 +
**单一信号绿**（`oklch(48% 0.15 150)`）；等宽字体承载字段路径、类型、样例值、编译表达式；
状态色（警告/错误）只做信号。无英雄图、无渐变、无卡片阴影堆叠。

**因此「像不像原型」不取决于配色，取决于有没有这十几个有名字的令牌**：原型一支文件里
`color-mix(in oklch, …)` 用了 22–32 次，全靠派生；生产侧（改造前）是 158 处孤立 hex、43 个色值。
两边是**结构差异**，不是审美差异。工作量评估见 `docs/research/ui-visual-refresh-scope.md`。

## 怎么打开

```
start frontend/prototype/03-studio.html        # Windows
open  frontend/prototype/03-studio.html        # macOS
```

原型里的数据是**真实取值的演示数据**（来源样例取 `spike/src/test/resources/json/erp测试数据.json` 的
ERP BOM 结构，目标与规则对齐 `config/FieldMappingRule.java`），不是编造指标。原型里的交互大多是能点的
（点标签开检查器、切页签、跑试算），但**它连的是内置的假数据，不连后端**。

## 注意

- 这几支文件**不是生产代码**，不要 import、不要改造成组件；它们的用途是「对着看」与「对照验收」。
- `frontend/prototype/` 在 `frontend/` 工程里，但 Vite 只会构建 `index.html` 引到的模块，
  这几支不会被卷进构建产物。
- 改原型之前先读 `docs/adr/0013-console-visual-language.md`：原型是**视觉契约**，改它等于改契约。
