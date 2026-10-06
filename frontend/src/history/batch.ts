/**
 * 批次口径（#139）：右详情按批分组、组头文案、某一步能不能重发、单条那一档。
 *
 * **纯模块**：不碰 DOM、不发请求、**也不带自测**——自测在 `history/batch-checks.ts`
 * （本仓的规矩：纯模块与它的检查分开，见 `mapping/draft.ts` + `mapping/checks.ts`）。
 *
 * 口径出自 ADR-0017（批次身份）与 #139 票面，压成四条：
 *
 * 1. **批次是「某一步上的分组」，不是消息的属性**（决策 1）：同一条消息在动作 1 与动作 3 上可以属
 *    不同批次，所以分组键是「步骤号 + 批号」，不是批号本身。
 * 2. **批号每执行一次现生成，随消息在链上继承到下一个基数变化动作为止**（决策 2）：界面上只在
 *    **这一步换了号**的地方画批徽标，中间那些步骤不画。
 * 3. **一次请求的报文只存一份**（决策 6）：合并动作多记一条**摘要行**（`message_id` 是
 *    `batch:<批号>`）。`读取批` 与 `发送批` 就是按这条摘要行分的——有摘要行的那一组是**发送**那一档。
 * 4. **某一步上这条消息自己一组**就是「单条 · 不是一批」（决策 10）：读库直连、MQ 消费、任务输入
 *    这些链永远不会出现批次，它们就该长这样，不给用户「这一批只有一条」的错觉。
 *
 * **不在这里判的**：单条消息步能不能断点重投（那是 `history/redeliver.ts` 的 `canRedeliver`，
 * #135 的口径）；这一票也不铺「重发这一批」那个按钮——它只长在**合并**那一档的组头上、且只在
 * 该批失败时铺（ADR-0017 决策 7），合并动作是后面那一票的事。判据在这儿、按钮到那一票再接。
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
 * **判据按数据判**（见 {@link batchRedeliver}），但这一票的后端还产不出这样的组——组里要有**摘要行**
 * 才算发送批，而摘要行是合并动作记的（#142）。所以这一票的界面上这一档铺不出来，按钮由 #142 接。
 */
export interface BatchRedeliver {
  /** 这一批几条。 */
  count: number
  label: string
}

/** 同一步上、同一批的那组消息。 */
export interface BatchGroup {
  /** 步骤号（批次是「某一步上的分组」）。 */
  step: number
  /** 批号；`null` = 这一步上没有批次（MQ 消费 / 任务输入那些链）。 */
  batchId: string | null
  kind: BatchKind
  /** 同一步上同批的**每一行**，按落库先后；界面上一条 chip 一行（**摘要行也在里面**）。 */
  members: MessageStep[]
  /** 这一组几行（= `members.length`，含摘要行）。 */
  total: number
  /** 这一批的**摘要行**（合并动作上承载整份请求与整份响应的那一条）；读取批没有，为 `null`。 */
  summary: MessageStep | null
  /**
   * 这一批**几条消息**（= `total` 去掉摘要行）。
   *
   * 「一次请求 N 条」与「重发这一批（N 条）」数的是**消息**，不是行：摘要行是这一批的摘要，
   * 它自己也有一颗 chip（ADR-0017 决策 10），但它不是一条发出去的消息——把它算进去，
   * 组头会说「一次请求 3 条」而界面上只有 2 颗消息 chip。
   */
  messages: number
  /** 这一批里**几条消息**失败（摘要行的状态不算消息的失败）。 */
  failed: number
  /** 组头那一句（`读取批 · 一次请求 N 条 · 失败 M 条` / 单条那档）。 */
  head: string
}

/**
 * 链上这一格与它所属的那一批。
 *
 * - `batch`：**这一步换了号**且这一批不止一条 → 链节点带小批徽标，点开是一组 chip；
 * - `single`：这一格上这条消息**自己一组** → 画虚线框，写「单条 · 不是一批」；
 * - `plain`：中间那些步骤（批号没换）→ 什么都不画；`group` 为 `null` 时也是它（**没有数据就不下结论**：
 *   一条运行的消息步还没取回来时，不能凭空说「这一步不是一批」）。
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

/** 这一行是不是**摘要行**（合并动作上承载整份请求与整份响应的那条，ADR-0017 决策 6）。 */
export function isSummaryRow(step: Pick<MessageStep, 'messageId'>): boolean {
  return step.messageId?.startsWith(SUMMARY_MESSAGE_PREFIX) ?? false
}

/**
 * 组头那一句：`读取批 · 一次请求 N 条 · 失败 M 条`。
 *
 * `读取批` 与 `发送批` 的区别只有一处说法（摘要行在不在这一组里）；单条那档不写「一次请求」，
 * 因为**它不是一批**——那正是这一档要挡住的那种错觉（ADR-0017 决策 10）。
 * `messages` / `failed` 数的都是**消息**（不含摘要行，见 {@link BatchGroup#messages}）。
 */
export function batchHead(kind: BatchKind, messages: number, failed: number): string {
  if (kind === 'single') return SINGLE_NOTE
  return `${kind === 'send' ? '发送批' : '读取批'} · 一次请求 ${messages} 条 · 失败 ${failed} 条`
}

/**
 * 一条运行的消息步**按批分组**（同一步 + 同批号一组）。
 *
 * 顺序照步骤号升序；同一步里按这一组第一行的落库先后（`id`）排——界面上的组头因此与步骤链同向。
 * 同一个批号在多个步骤上出现时是**多个组**（批号是继承来的，分组只在某一步上成立，决策 1）。
 */
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
  for (const group of groups.values()) {
    group.total = group.members.length
    const messages = group.members.filter((member) => !isSummaryRow(member))
    group.summary = group.members.find(isSummaryRow) ?? null
    group.messages = messages.length
    group.failed = messages.filter((member) => member.status === 'FAILED').length
    // **一条一组 = 不是一批**（ADR-0017 决策 10 要挡的正是「这一批只有一条」那种错觉）：批大小 1 的
    // 读库链、MQ 消费、任务输入都落这一档——界面上画虚线框，而不是一个只有一颗 chip 的组头
    //（那样的组头点开与不点开一模一样，还凭空多出一个「批」字）。
    // 一组以上再看有没有摘要行：有 = 这一批在合并动作上发过（决策 6），没有 = 读取批。
    group.kind = group.total <= 1 ? 'single' : (group.summary ? 'send' : 'read')
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
 * `previous` 是这条消息**链上的上一步**（入口那格不是某一步，所以它是 `null`）：批号与它不同才算
 * 「这一步换了号」——中间那些步骤不画批徽标（决策 2）。
 *
 * **一条一组按「不是一批」画**（决策 10）：批大小 1 的读库批在库里确实带着批号，但它没有「一组 chip」
 * 可看——组头点开与不点开一模一样。所以这一档画虚线框写「单条 · 不是一批」，而不是一个只有一颗
 * chip 的批。要「哪几条一起读进来的」这件事，把读库那一步的批大小调大就有了。
 */
export function nodeBatch(
  previous: Pick<MessageStep, 'batchId'> | null,
  current: Pick<MessageStep, 'step' | 'batchId'>,
  groups: readonly BatchGroup[],
): NodeBatch {
  const group = batchGroupAt(groups, current)
  if (!group) return { tier: 'plain', group: null }
  if (group.kind === 'single') return { tier: 'single', group }
  const changed = normalizeBatchId(previous?.batchId) !== normalizeBatchId(current.batchId)
  return { tier: changed ? 'batch' : 'plain', group }
}

/**
 * 「重发这一批（N 条）」那一档：**只有发送批（合并）且这一批里有失败**才铺
 * （ADR-0017 决策 7 —— 合并之前的步骤仍是逐条重投，那一档归 `redeliver.ts`）。
 *
 * <p>判据是**数据**判的，不是「现在有没有合并动作」判的——库里有摘要行的那一组（发送批）本来就该长
 * 这个按钮。#142 之前不会有这样的数据，所以界面上暂时铺不出来。</p>
 */
export function batchRedeliver(group: BatchGroup | null): BatchRedeliver | null {
  if (!group || group.kind !== 'send' || group.failed <= 0) return null
  // N 数的是**消息**：摘要行不是一条发出去的消息（它只是这一批的摘要），别把它算进重发条数
  return { count: group.messages, label: `重发这一批（${group.messages} 条）` }
}
