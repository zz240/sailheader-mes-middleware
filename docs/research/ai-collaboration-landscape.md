# AI 协作落地：市面上的实践方案盘点

调研时间 2026-11-14。目的：回答「文档不内聚、规划失控、能力边界不清」这三类问题，业界有哪些现成做法，各自成本和证据。

分五类：解释（文档）、规划（规格）、协作模式、能力边界、反面证据。**便宜的在第一类，贵的在第二类。**

---

## 一、解释：让文档符合人的阅读习惯

这一类不增加流程，只改写法，成本最低。

### 1. Diátaxis（文档四分类）

- 主张：文档只有四种，对应四种读者需求——教程（tutorials，学习时）、操作指南（how-to guides，干活时）、参考（reference，查的时候）、解释（explanation，理解时）。每种写法不同，文档结构应该按这四种需求组织。
- 治什么：你现在的文档是「按提问顺序排的解释」，只有第四种，而且混着前三种的功能。读完仍然拼不出整体。
- 成本：一次性调整目录结构，之后每篇归位。无工具、无新流程。
- 出处：<https://diataxis.fr/start-here/>

### 2. C4 模型（四层缩放）

- 主张：架构图分四层——上下文（系统与外部）、容器（可部署单元）、组件、代码。每层服务不同读者，不画「一张什么都有的大图」。
- 治什么：文档散、每次讲一个点，是因为没有一个固定的缩放层级。C4 给了你「先讲第几层」的默认顺序。
- 成本：一张图一件事，写的时候按层落。
- 出处：<https://c4model.com/>；配套模板 arc42（12 节）：<https://arc42.org/>

### 3. ADR（架构决策记录）

- 主张：每个有架构意义的决定，一个编号短文件，只写「背景 / 决定 / 后果」，永不删除，随代码进仓库。Nygard 2011 年提出。
- 治什么：AI 每次给「在冗余上加意见」，是因为没有地方沉淀「当初为什么这样」。ADR 把决策从对话里搬到仓库里。
- 成本：一页纸，一个决定一次。
- 证据：一项对 GitHub 的 MSR 研究发现 1267 个仓库里 921 个有 ADR、合计 5000 多条；但多数仓库试过就停了，长期坚持的是少数。说明它便宜，但需要纪律。
- 出处：<https://www.jfokus.se/jfokus25-preso/Architecture-Decision-Records.pdf>

### 4. 「用自己的话解释这个改动」作为门禁

- 做法：Python 官方开发指南要求，用 AI 提 PR 的人必须能用自己的话解释改动，否则不接受。责任永远在人。
- 治什么：AI 写的代码你只能验收、不能解释，就是这里缺一条门禁。
- 出处：<https://devguide.python.org/getting-started/ai-tools/>

---

## 二、规划：让规格先于代码

这一类增加流程，成本高，且业界自己承认还不成熟。

### 5. SDD（规格驱动开发）的三个档次

Böckeler（Thoughtworks）2025-10 把市面工具分成三档：

| 档次 | 含义 | 代表 |
|---|---|---|
| spec-first | 先写规格，写完可以丢 | GitHub Spec Kit |
| spec-anchored | 规格长期保留，演化时回查 | Kiro（AWS） |
| spec-as-source | 规格是唯一维护对象，人不碰生成代码 | Tessl |

- Spec Kit：三步（规格 → 计划 → 任务），加一份「宪章」（constitution）定义不可变原则，提示词可配置。
- Kiro：需求 → 设计 → 任务，三个阶段的工作流。
- 治什么：你第 3 点「扩展不按能说清楚的思路走」。
- 成本：高。产出物多、评审重。
- 出处：<https://martinfowler.com/articles/exploring-gen-ai/sdd-3-tools.html>

### 6. Thoughtworks 对 SDD 的评价（重要，属反面证据）

2025-11 技术雷达把它放进「评估」：这类流程「依然较为繁琐且带有很强的主观性」，工具会产出「冗长难以审阅的规范文件」，做 PRD 时「不清楚目标受众是谁」。并警告：**为 AI 手工编写详细规则，可能重蹈无法规模化的覆辙。**

- 出处：<https://www.thoughtworks.com/zh-cn/radar/techniques/spec-driven-development>

### 7. 其他 SDD 框架

OpenSpec、BMAD-METHOD、Agent-OS、AB Method。都是「规格 → 任务 → 实现」的变体，差异在是否保留规格、是否分角色 agent。
- 出处（横向对比）：<https://github.com/qte77/ai-agents-research/blob/main/docs/non-cc/frameworks/spec-driven-frameworks-landscape.md>

### 8. AI-DLC（AWS 的 AI 驱动开发生命周期）

- 主张：三个阶段——启始（inception，AI 提方案人批）、构建（construction，AI 执行人验证）、运维（operations）。核心是「AI 当主要执行者，人在关键决策点把关」。
- 治什么：给「什么该人批、什么该 AI 自己做」一条明确的分界线。
- 成本：组织级流程，个人项目偏重。
- 出处：<https://builder.aws.com/content/3KjWpM4sqd5f0zjUtE67OfUa7rk/ai-driven-development-life-cycle-ai-dlc-on-aws-a-new-approach-to-software-engineering>

### 9. Plan mode（工具内置的先规划后动手）

- Claude Code 的官方最佳实践：探索 → 计划 → 编码 → 提交。计划模式只用只读工具，计划可以在编辑器里改。官方说法：**改动涉及 3 个以上文件、或代码不熟时，先计划**。
- 成本：几乎为零，已经内置。这是「规划」里性价比最高的一项。
- 出处：<https://code.claude.com/docs/en/best-practices>

---

## 三、协作模式：把「怎么配合」写成约定

### 10. AGENTS.md / CLAUDE.md（仓库级约定文件）

- 做法：在仓库根放一个文件，写明构建命令、测试命令、风格、禁区。工具无关的是 AGENTS.md，各家还有 CLAUDE.md、copilot instructions、GEMINI.md。
- 现状：2026 年一项开源仓库扫描里，只有 5% 的仓库采用了其中至少一种。也就是说这条路「有标准、没普及」。
- 治什么：每次新会话重新解释一遍项目，以及「它不知道这里的规矩」。
- 出处：<https://ar5iv.labs.arxiv.org/html/2510.21413>

### 11. 增强编码（Augmented Coding，Kent Beck）

- 主张：和「氛围编程」（vibe coding，不看代码只看能不能跑）相对。增强编码对 AI 代码用同一套质量标准：严格 TDD、拒绝被禁用的测试、拒绝没要求的功能、早发现设计漂移。
- 配套是他提的 3X：探索（Explore）→ 扩展（Expand）→ 提炼（Extract）。**AI 在探索阶段有用，扩展和提炼阶段不行**——因为那两段要的是可预测性。
- 治什么：你第 2 点「没有掌控感」。这套的价值在于给出「哪一段可以放手」的判断，而不是笼统地说「AI 写的要审」。
- 出处：<https://kentbeck.com/summaries/augmented-coding-beyond-the-vibes/>；<https://kentbeck.com/summaries/augmented-coding-is-good-for-exploration/>

### 12. Harness engineering（约束层工程）

- 主张：把系统提示、工具、检查、钩子、CI 合起来当作「给 agent 的约束层」来设计，而不是散落的提示词。
- 状态：术语还在形成，已有整本书在整理这套实践。
- 出处（示例）：<https://gitee.com/yawpei/harness-engineering-orange-book>

### 13. 结对角色与上下文隔离

- 驾驶/领航（driver/navigator）：人定方向和验收，agent 打字。
- 半人马 / 赛博格（centaur/cyborg，Mollick）：两种人机分工模型——按任务切给谁，或深度融合。
- 子代理隔离：把搜索、评审放到独立上下文，主上下文只留结论。
- 成本：低到中。

---

## 四、能力边界：怎么知道它能干到哪

### 14. 锯齿状前沿（Jagged Frontier）

- 主张：AI 的能力边界不是一条线，是锯齿。同一个任务集里，有的落在界内（大幅提升），有的落在界外（比不用还差），而且**人判断不出来自己在哪一侧**。
- 证据：Mollick 等在波士顿咨询做的现场实验，界外任务上用了 AI 的组正确率反而更低。
- 治什么：你第 4 点「建议有提示作用但不拓展边界」。边界要靠任务探测，不靠感觉。
- 出处：<https://pubsonline.informs.org/doi/10.1287/orsc.2025.21838>

### 15. METR 的实测（自我感觉不可信）

- 16 名资深开源维护者，246 个真实任务，都是自己维护了约 5 年的仓库。结果：用 AI 慢 19%。事前他们预计快 24%，事后自认为快 20%。
- 结论：**自报的提速不可信，这是这个领域最常被引用、最弱的证据**。
- 出处：<https://arstechnica.com/ai/2025/07/study-finds-ai-tools-made-open-source-software-developers-19-percent-slower/>

### 16. Anthropic 的随机对照实验（对你第 4 点最直接）

- 52 名以初级为主的工程师，学一个陌生的异步库 Trio。
- 结果：用 AI 组测验 50%，手写组 67%，差 17 分（d=0.738，p=0.01）；速度提升约 2 分钟，**不显著**；差距最大的是调试题。
- 关键分组：**用 AI 问概念的人拿到 65% 以上；把写代码整个交给 AI 的人低于 40%。**
- 作者结论：AI 在已掌握的技能上加速，在新技能的获取上拖后腿。
- 出处：<https://www.infoq.com/news/2026/02/ai-coding-skill-formation/>

### 17. 认知债 / 理解债

- 理解债（comprehension debt，Osmani/Storey）：代码量与人真正理解的部分之间不断扩大的差距。它和一般技术债不同——技术债会疼，理解债会让你误以为一切正常。
- 认知债（MIT Media Lab，Kosmyna 等，2025）：54 人写议论文，脑电显示用 LLM 的组神经连接最弱，且停用后仍偏低。
- 微软研究院 + CMU（Lee 等，CHI 2025）：319 名知识工作者、936 个任务。对 AI 越有信心的人，投入的批判性思考越少；AI 把精力从「生成」挪到「验证」。
- 治什么：把「我没掌控感」变成一个可观察的量，而不是情绪。
- 出处：<https://www.oreilly.com/radar/comprehension-debt-the-hidden-cost-of-ai-generated-code/>；<https://www.media.mit.edu/publications/your-brain-on-chatgpt/>；<https://dl.acm.org/doi/full/10.1145/3706598.3713778>

### 18. DORA 的结论（放大器，不是解药）

- 2025 报告，近 5000 人。核心一句：**AI 不会修好一个团队，它放大已有的东西。** 回报来自内部平台质量、流程清晰度、信任，而不是工具本身。
- 2024 报告的数据更硬：AI 用得越多，交付稳定性和吞吐越差。
- 出处：<https://cloud.google.com/blog/products/ai-machine-learning/announcing-the-2025-dora-report>

---

## 五、按成本排的选型（我的判断，非引用）

| 方案 | 成本 | 治你哪个症状 |
|---|---|---|
| Diátaxis 四分类 | 极低，只改目录 | 文档散、读不出整体 |
| C4 四层 + 一张图一件事 | 低 | 每次只讲一个点 |
| ADR | 低，一页一次 | 决策丢失、在冗余上加意见 |
| Plan mode | 零，已内置 | 不问就动手 |
| AGENTS.md | 低 | 每次重讲项目规矩 |
| 增强编码 + 3X | 中，要自律 | 没有掌控感 |
| SDD / Spec Kit / Kiro | 高 | 扩展无思路 |
| AI-DLC | 很高，组织级 | 人机分界 |
| Tessl（spec-as-source） | 很高，激进的范式 | 规格即源码 |

## 六、对这套盘点的诚实评价

1. **治「解释」的方案便宜，是因为它们只规定结构，不增加流程。** 你不需要新的方法论，只需要给现有文档一个固定的四分类和四层缩放。
2. **治「规划」的方案贵，而且业界自己没跑通。** Thoughtworks 的原话是「繁琐且主观」。你嫌我上一版成本高，这个批评对 SDD 同样成立，甚至更成立。
3. **最能解释「能力停滞」的是第 16 条。** 同样的工具，问概念的人学会了，要代码的人没学会。差别不在工具，在用法。
4. **没有一条方案能替代「你自己做决定」。** 所有方案都在安排谁来审、审什么，没有一条能把判断力外包出去。
