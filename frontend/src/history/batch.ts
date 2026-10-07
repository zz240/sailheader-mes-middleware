/**
 * 批次口径（#139 起）：右详情按批分组、组头文案、某一步能不能重发、单条那一档。
 *
 * **纯模块**：不碰 DOM、不发请求、**也不带自测**——自测在 `history/batch-checks.ts`
 * （本仓的规矩：纯模块与它的检查分开，见 `mapping/draft.ts` + `mapping/checks.ts`）。
 *
 * 口径出自 ADR-0017 与 #142，压成五条：
 *
 * 1. **批次是「某一步上的分组」，不是消息的属性**（决策 1）：同一条消息在动作 1 与动作 3 上可以属
 *    不同批次，所以分组键是「步骤号 + 批号」。
 * 2. **批号每执行一次现生成，随消息在链上继承到下一个基数变化动作为止**（决策 2/3）：界面上只在
 *    读库那种「这一步换了号」的地方画批徽标，中间那些步骤不画。
 * 3. **一次请求的报文只存一份**（决策 6 的修订版）：那一批在**出站**动作上有一条**摘要行**
 *    （`message_id` 是 `batch:<批号>`）。摘要行落在哪一步、成员落在哪一步**不必是同一步**：
 *    合并只打包（N→1，一个号在这一步转手）、出站发一次（摘要行在这儿）、还原摊回原来那 N 条
 *    （1→N，**不换号**，所以它仍属同一个批号）。于是「这一批」是**按批号**成立的，不是「某一步上的那一组」。
 * 4. **某一步上这条消息自己一组**就是「单条 · 不是一批」（决策 10）：读库直连、MQ 消费、任务输入
 *    这些链永远不会出现批次，它们就该长这样。
 * 5. **「重发这一批」长在摘要行那一组上**（决策 7 的修订版）：一次点击 = 一次请求；多目标时
 *    每个出站动作各有一条摘要行 → 各一个按钮，点谁重发谁。
 *
 * **不在这里判的**：单条消息步能不能断点重投（那是 `history/redeliver.ts` 的 `canRedeliver`，#135 的口径）。
 */
import type { MessageStep } from '../types'

/** 摘要行的 `message_id` 前缀（ADR-0017 决策 6；该列非空，且「运行 + 消息 + 步骤」要唯一）。 */
export const SUMMARY_MESSAGE_PREFIX = 'batch:'

/** 这一档批次的说法：读取批 / 发送批 / 单条（不是一批）。 */
export type BatchKind = 'read' | 'send' | 'single'

/** 链节点上那个小批徽标的字（**只标「这一步是一批」，不摊开成员**）。 */
export const BATCH_BADGE = '一批'

/** 「某一步上这条消息自己一组」时那句话（画虚线框）。 */
export const SINGLE_NOTE = '单条 · 不是一批'

/**
 * 「重发这一批」那一档（ADR-0017 决策 7）：一次点击 = 一次请求，绝不摆 N 个「重投这一条」。
 *
 * **判据按数据判**（见 {@link batchRedeliver}）：这一批得有**摘要行**（合并 + 出站之后才有），
 * 而且这一批里**有失败**（出站那一步失败、或下游还原失败）——后者也算：重发要重新打一次请求。
 */
export interface BatchRedeliver {
  /** 这一批几条消息。 */
  count: number
  label: string
}

/** 同一步上、同一批的那组消息。 */
export interface BatchGroup {
  /** 步骤号（分组键的一半）。 */
  step: number
  /** 批号；`null` = 这一步上没有批次（MQ 消费 / 任务输入那些链）。 */
  batchId: string | null
  kind: BatchKind
  /** 同一步上同批的**每一行**，按落库先后；界面上一条 chip 一行（**摘要行也在里面**）。 */
  members: MessageStep[]
  /** 这一组几行（= `members.length`，含摘要行）。 */
  total: number
  /** 这一批的**摘要行**（整份请求与整份响应的那一条）；不在这一组里时是 `null`。 */
  summary: MessageStep | null
  /**
   * 这一批**几条消息**（= 该批号下非摘要行的**不同消息标识**数，跨步骤去重）。
   *
   * 「一次请求 N 条」与「重发这一批（N 条）」数的是**消息**，不是行：同一条消息在打包 / 出站 / 还原
   * 这几步各有一行，摘要行也不是一条发出去的消息。早期版本按「这一组几行」数，会多数。
   */
  messages: number
  /** 这一批里**几条消息**失败（该批号下任一失败行所属的不同消息标识数）。 */
  failed: number
  /** 组头那一句（`读取批/发送批 · 一次请求 N 条 · 失败 M 条` / 单条那档）。 */
  head: string
}

/**
 * 链上这一格与它所属的那一批。
 *
 * - `batch`：这一步属于**发送批**（打包 / 出站 / 还原这几步都算，因为它们是同一个批号），
 *   或者读库那种**换了号**的那一步 → 链节点带小批徽标，点开是一组 chip；
 * - `single`：这一格上这条消息**自己一组** → 画虚线框，写「单条 · 不是一批」；
 * - `plain`：中间那些步骤（读库批号没换）→ 什么都不画；`group` 为 `null` 时也是它
 *   （**没有数据就不下结论**：一条运行的消息步还没取回来时，不能凭空说「这一步不是一批」）。
 */
export interface NodeBatch {
  tier: 'batch' | 'single' | 'plain'
  group: BatchGroup | null
}

/** 批号归一：空白与缺省都算「没有批号」（同一个判据，分组与比较都从它走）。 */
export function normalizeBatchId(batchId?: string | null): string | null {
  return batchId?.trim() || null
}

/** 一条消息步 / 一个组的**键**：`步骤号 + 批号`（批次是「某一步上的分组」，不是消息的属性，决策 1）。
 *
 * 分组、查组、界面上「哪一组开着」都用它——这个拼法只有这一处（`\u0000` 是分隔符：步骤号是数字、
 * 批号是 UUID，拿它分开就不会出现 `1` + `23` 与 `12` + `3` 撞成一个键）。
 */
export function batchKey(of: Pick<MessageStep, 'step' | 'batchId'>): string {
  return `${of.step}\u0000${normalizeBatchId(of.batchId) ?? ''}`
}

/** 这一行是不是**摘要行**（出站动作上承载整份请求与整份响应的那条，ADR-0017 决策 6）。 */
export function isSummaryRow(step: Pick<MessageStep, 'messageId'>): boolean {
  return step.messageId?.startsWith(SUMMARY_MESSAGE_PREFIX) ?? false
}

/**
 * 组头那一句：`读取批 · 一次请求 N 条 · 失败 M 条`。
 *
 * `读取批` 与 `发送批` 的区别是**这一批有没有出站摘要行**；单条那档不写「一次请求」，
 * 因为**它不是一批**——那正是这一档要挡住的那种错觉（ADR-0017 决策 10）。
 * `messages` / `failed` 数的都是**消息**（不含摘要行，见 {@link BatchGroup#messages}）。
 */
export function batchHead(kind: BatchKind, messages: number, failed: number): string {
  if (kind === 'single') return SINGLE_NOTE
  return `${kind === 'send' ? '发送批' : '读取批'} · 一次请求 ${messages} 条 · 失败 ${failed} 条`
}

/** 一条运行的消息步**按批分组**（同一步 + 同批号一组），组头文案与档位按**批号**统一判。 */
export function batchGroups(steps: readonly MessageStep[]): BatchGroup[] {
  const groups = new Map<string, BatchGroup>()
  for (const step of [...steps].sort((a, b) => a.id - b.id)) {
    const key = batchKey(step)
    let group = groups.get(key)
    if (!group) {
      group = {
        step: step.step, batchId: normalizeBatchId(step.batchId), kind: 'single', members: [],
        total: 0, summary: null, messages: 0, failed: 0, head: '',
      }
      groups.set(key, group)
    }
    group.members.push(step)
  }

  // 批号维度的两件事：这一批有没有摘要行（可能在**别的**步骤上）、这一批有哪些消息（跨步骤去重）
  const summaryBatches = new Set<string>()
  const messageIds = new Map<string, Set<string>>()
  const failedIds = new Map<string, Set<string>>()
  for (const step of steps) {
    const batchId = normalizeBatchId(step.batchId)
    if (batchId === null) continue
    if (isSummaryRow(step)) {
      summaryBatches.add(batchId)
      continue
    }
    if (!messageIds.has(batchId)) messageIds.set(batchId, new Set())
    messageIds.get(batchId)!.add(step.messageId)
    if (step.status === 'FAILED') {
      if (!failedIds.has(batchId)) failedIds.set(batchId, new Set())
      failedIds.get(batchId)!.add(step.messageId)
    }
  }

  for (const group of groups.values()) {
    group.total = group.members.length
    group.summary = group.members.find(isSummaryRow) ?? null
    const batchId = group.batchId
    const hasSummary = batchId !== null && summaryBatches.has(batchId)
    // 没有批号的那几档（MQ 消费 / 任务输入 / 读库直连的空批）就按本组自己的行进算
    group.messages = batchId === null ? group.total : (messageIds.get(batchId)?.size ?? 0)
    group.failed = batchId === null
      ? group.members.filter((member) => member.status === 'FAILED').length
      : (failedIds.get(batchId)?.size ?? 0)
    // **这一批有出站摘要行 = 发送批**（决策 6）：即便这一组只有一条（出站那一行摘要），它也是发送批，
    // 不然「重发这一批」那颗按钮挂在一个写着「单条 · 不是一批」的组上
    group.kind = hasSummary ? 'send' : (group.total <= 1 ? 'single' : 'read')
    group.head = batchHead(group.kind, group.messages, group.failed)
  }
  return [...groups.values()].sort((a, b) => a.step - b.step
    || (a.members[0]?.id ?? 0) - (b.members[0]?.id ?? 0))
}

/**
 * 链上某一格属于哪一批（从**整条运行**的分组里找，不是从这一条消息自己的步骤里找）。
 *
 * 找不到就 `null`：链上那几格是从这条消息的消息步来的，而分组要整条运行的消息步
 * （同批的另外几条消息不在这一条里）——数据没到齐时不许下结论。
 */
export function batchGroupAt(
  groups: readonly BatchGroup[],
  step: Pick<MessageStep, 'step' | 'batchId'>,
): BatchGroup | null {
  const key = batchKey(step)
  return groups.find((group) => batchKey(group) === key) ?? null
}

/**
 * 链上这一格画成什么（批徽标 / 虚线框 / 什么都不画）。
 *
 * - **发送批**：打包 / 出站 / 还原这几步是同一个批号（还原**不换号**），所以这几格都标——
 *   摘要行只在其中一格上，但「这一批」是整段（否则用户得猜哪一格能点开）。
 * - **读取批**：只在**换了号**的那一步标（`previous` 是链上的上一步；批号与它不同才算换了号），
 *   中间那些步骤不标（决策 2）。
 * - **一条一组**（没有摘要行、也没有别的成员）按「不是一批」画（决策 10）：批大小 1 的读库批在库里
 *   确实带着批号，但它没有「一组 chip」可看——要「哪几条一起读进来的」，把读库那一步的批大小调大。
 */
export function nodeBatch(
  previous: Pick<MessageStep, 'batchId'> | null,
  current: Pick<MessageStep, 'step' | 'batchId'>,
  groups: readonly BatchGroup[],
): NodeBatch {
  const group = batchGroupAt(groups, current)
  if (!group) return { tier: 'plain', group: null }
  if (group.kind === 'send') return { tier: 'batch', group }
  if (group.kind === 'single') return { tier: 'single', group }
  const changed = normalizeBatchId(previous?.batchId) !== normalizeBatchId(current.batchId)
  return { tier: changed ? 'batch' : 'plain', group }
}

/**
 * 「重发这一批（N 条）」那一档：**这一批有摘要行、而且这一批里有失败**才铺
 * （ADR-0017 决策 7 的修订版 —— 按钮只长在**摘要行那一组**上，也就是出站那一步；
 * 合并之前的步骤仍是逐条重投，那一档归 `redeliver.ts`）。
 *
 * <p>失败可能发生在**下游**（出站成功、还原那一步配不上）：那时摘要行自己是 SUCCESS，但这一批整体
 * 没成——重发的动作仍是「再打一次请求」（不是拿旧响应重新配一遍：同一份响应配第二次还是同一份结果）。</p>
 */
export function batchRedeliver(group: BatchGroup | null): BatchRedeliver | null {
  if (!group || group.kind !== 'send' || !group.summary || group.failed <= 0) return null
  // N 数的是**消息**：摘要行不是一条发出去的消息（它只是这一批的摘要），别把它算进重发条数
  return { count: group.messages, label: `重发这一批（${group.messages} 条）` }
}

/**
 * 按批重发的接口路径（#142）：`{id}` 是那一批的**摘要行**的消息步 id（出站那一步的那一行）。
 *
 * 一次调用 = 一次请求（重发整批）；合并之前的步骤仍是逐条重投（那是 `redeliver.ts` 那条路）。
 */
export function redeliverBatchPath(summaryStepId: number): string {
  return `/history/batches/${summaryStepId}/redeliver`
}

/** 按批重发成功后那一句（说的是这一批几条 + 新运行，界面不写死状态文案）。 */
export function batchRedeliverDone(count: number, runId: number | null): string {
  return `已重发这一批（${count} 条）${runId == null ? '' : `，新运行 #${runId}`}`
}
