/**
 * 批次口径（#139 起，#147 换了「读取批 / 发送批」的判据）：右详情按批分组、组头文案、
 * 打包消息怎么认、某一步能不能重跑任务、单条那一档。
 *
 * **纯模块**：不碰 DOM、不发请求、**也不带自测**——自测在 `history/batch-checks.ts`
 * （本仓的规矩：纯模块与它的检查分开，见 `mapping/draft.ts` + `mapping/checks.ts`）。
 *
 * 口径出自 ADR-0017、#142 与 #147，压成五条：
 *
 * 1. **批次是「某一步上的分组」，不是消息的属性**（决策 1）：同一条消息在动作 1 与动作 3 上可以属
 *    不同批次，所以分组键是「步骤号 + 批号」。
 * 2. **批号每执行一次现生成，随消息在链上继承到下一个基数变化动作为止**（决策 2/3）：界面上只在
 *    读库那种「这一步换了号」的地方画批徽标，中间那些步骤不画。
 * 3. **打包消息**（#147 新立的概念，取代已删除的摘要行）：合并 N→1 打出来的那条**信封消息**。
 *    合并那一步**批号转手**——`AggregateFragment.takeBatch` 现生成一个新批号、并给打包出来的那一条
 *    一个自己的消息标识——所以信封消息是它自己那个批号的**第一步**，而且**独占了那一步**
 *    （成员要等还原那一步才重新出现）。判据因此可以纯从数据判，见 {@link batchGroups}：
 *    **某批号「步骤号最小的那一步」上独占该步、而该批号下还有别的消息标识**的那条消息。
 *    *为什么非要「最小步骤」这一步*：一条读取批 / 拆分批在链中途失败时，那一步可能只留下**一条**
 *    消息的行（失败即停，后面的没跑到）——只按「独占某一步」判就会把这条普通消息误认成打包消息，
 *    档位错成发送批、条数还少算一条；而读取批的最小步骤（读库那一步）与拆分批的最小步骤
 *    （拆分之后第一步）上，那一批的消息都在，谁都不独占。
 * 4. **某一步上这条消息自己一组**就是「单条 · 不是一批」（决策 10）：读库直连、MQ 消费、任务输入
 *    这些链永远不会出现批次，它们就该长这样。
 * 5. **失败恢复只有「重跑任务」一条路**（#147）：一次点击 = **重跑整个任务**。水位只在一批全部成功
 *    之后才推进，所以重跑会从未完成的那一批整批重走（重读 → 重打包 → 重出站 → 还原 → 写回），
 *    代价是目标端会再收到一次该批请求。旧的「整批重发」（只把请求重发一遍、不跑下游）与承载它的
 *    摘要行（`message_id = batch:<批号>`）都已删除。
 *
 *    按钮的**落点**：一个批号只铺**一颗**，落在「该批号里**最深的、还带着打包消息的那一步**」那一组
 *    （{@link BatchGroup#rerunStep}）。改前的界面也是一颗批号一颗——`markBatchCarrier` 只在**出站**
 *    那一步把消息标识换成 `batch:<批号>`，摘要行因此只落在出站那一步，按钮也就只长在出站那一组
 *    （合并那一组与还原那一组都没有）。摘要行删掉之后判据只能按数据判，而打包消息在**打包**与
 *    **出站**两步各有一行，按「这一组里有没有打包消息」判就会一次铺出两颗（还原再失败就是三颗）——
 *    这既不是改前的行为，也让同一批出现两个作用完全相同的入口。取「最深的那一步」既唯一，又在两个
 *    主用例上落在**出站那一步**（与改前一致）：① 出站失败；② 出站成功、下游写回失败（信封行仍只在
 *    打包与出站两步上）。只有「还原失败」那一档会落到**还原那一步**——那时按钮跟着失败发生的地方走，
 *    是可接受的偏差。多出口（链上两个出站动作）因此也从「每个出口各一颗」收敛成 1 颗。
 *
 * **不在这里判的**：单条消息步能不能断点重投（那是 `history/redeliver.ts` 的 `canRedeliver`，#135 的口径）。
 */
import type { MessageStep } from '../types'

/** 这一档批次的说法：读取批 / 发送批 / 单条（不是一批）。 */
export type BatchKind = 'read' | 'send' | 'single'

/** 链节点上那个小批徽标的字（**只标「这一步是一批」，不摊开成员**）。 */
export const BATCH_BADGE = '一批'

/** 「某一步上这条消息自己一组」时那句话（画虚线框）。 */
export const SINGLE_NOTE = '单条 · 不是一批'

/** 「重跑任务」那颗按钮上的字（#147：失败恢复只有这一条路）。 */
export const RERUN_LABEL = '重跑任务'

/** 二次确认框的标题（`ElMessageBox.confirm` 第二格）。 */
export const RERUN_CONFIRM_TITLE = '重跑任务'

/** 确认框上那颗按钮的字。 */
export const RERUN_CONFIRM_BUTTON = '重跑任务'

/** 确认框上那颗取消按钮的字。 */
export const RERUN_CONFIRM_CANCEL = '取消'

/** 同一步上、同一批的那组消息。 */
export interface BatchGroup {
  /** 步骤号（分组键的一半）。 */
  step: number
  /** 批号；`null` = 这一步上没有批次（MQ 消费 / 任务输入那些链）。 */
  batchId: string | null
  kind: BatchKind
  /** 同一步上同批的**每一行**，按落库先后；界面上一条 chip 一行。 */
  members: MessageStep[]
  /** 这一组几行（= `members.length`）。 */
  total: number
  /**
   * 这一批的**打包消息**（合并 N→1 打出来的那条信封消息）**落在这一组里的那一行**；
   * 这一组里没有它就是 `null`（同一批在别的步骤上仍可能有——它只在自己那一步、以及出站那一步各有一行）。
   */
  packed: MessageStep | null
  /**
   * 这一批的「重跑任务」那颗按钮**落在哪一步**（#147 的落点口径）：该批号里**还带着打包消息的最深那一步**。
   *
   * 同一批的每一组都带这个值，只有 `step === rerunStep` 的那一组铺按钮——**一个批号只铺一颗**
   * （理由见文件头第 5 条）。没有打包消息（读取批 / 单条那档）时是 `null`。
   */
  rerunStep: number | null
  /**
   * 这一批**几条消息**（= 该批号下的**不同消息标识**数，跨步骤去重，**不含打包消息**）。
   *
   * 「一次请求 N 条」数的是**消息**，不是行：同一条消息在打包 / 出站 / 还原这几步各有一行。
   * 打包消息本身也不是"发出去的一条"（它是那一批的信封），#147 起一并排除——这样「一次请求 N 条」
   * 不再多算那一条（#143 记的偏差：20 条码写成 21 条）。
   */
  messages: number
  /** 这一批里**几条消息**失败（该批号下任一失败行所属的不同消息标识数，同样不含打包消息）。 */
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

/**
 * 组头那一句：`读取批 · 一次请求 N 条 · 失败 M 条`。
 *
 * `读取批` 与 `发送批` 的区别是**这一批有没有打包消息**（#147 的新判据：旧判据看的是摘要行）；
 * 单条那档不写「一次请求」，因为**它不是一批**——那正是这一档要挡住的那种错觉（ADR-0017 决策 10）。
 * `messages` / `failed` 数的都是**消息**（不含打包消息，见 {@link BatchGroup#messages}）。
 */
export function batchHead(kind: BatchKind, messages: number, failed: number): string {
  if (kind === 'single') return SINGLE_NOTE
  return `${kind === 'send' ? '发送批' : '读取批'} · 一次请求 ${messages} 条 · 失败 ${failed} 条`
}

/** 一条运行的消息步**按批分组**（同一步 + 同批号一组），组头文案与档位按**批号**统一判。 */
export function batchGroups(steps: readonly MessageStep[]): BatchGroup[] {
  const ordered = [...steps].sort((a, b) => a.id - b.id)
  const groups = new Map<string, BatchGroup>()
  for (const step of ordered) {
    const key = batchKey(step)
    let group = groups.get(key)
    if (!group) {
      group = {
        step: step.step, batchId: normalizeBatchId(step.batchId), kind: 'single', members: [],
        total: 0, packed: null, rerunStep: null, messages: 0, failed: 0, head: '',
      }
      groups.set(key, group)
    }
    group.members.push(step)
  }

  // 批号维度的那几件事：这一批**最小步骤**上是哪条（哪几条）消息、这一批有哪些消息、谁失败过
  const idsAtFirstStep = new Map<string, Set<string>>()
  const firstStep = new Map<string, number>()
  const messageIds = new Map<string, Set<string>>()
  const failedIds = new Map<string, Set<string>>()
  for (const step of ordered) {
    const batchId = normalizeBatchId(step.batchId)
    if (batchId === null) continue
    const at = firstStep.get(batchId)
    if (at === undefined || step.step < at) {
      // 见到更小的步骤号：最小步骤换人，重开一个集合（最小的那一步可能有好几行）
      firstStep.set(batchId, step.step)
      idsAtFirstStep.set(batchId, new Set([step.messageId]))
    } else if (step.step === at) {
      idsAtFirstStep.get(batchId)!.add(step.messageId)
    }
    if (!messageIds.has(batchId)) messageIds.set(batchId, new Set())
    messageIds.get(batchId)!.add(step.messageId)
    if (step.status === 'FAILED') {
      if (!failedIds.has(batchId)) failedIds.set(batchId, new Set())
      failedIds.get(batchId)!.add(step.messageId)
    }
  }

  // 打包消息：**最小步骤**上独占该步、而这一批下还有别的消息标识的那一条（见文件头第 3 条）。
  // 一个批号最多一条：最小步骤只有一个，独占它的消息也只能有一条。
  const packedIds = new Map<string, string>()
  for (const [batchId, ids] of messageIds) {
    const atFirstStep = idsAtFirstStep.get(batchId)
    if (ids.size <= 1 || !atFirstStep || atFirstStep.size !== 1) continue
    packedIds.set(batchId, [...atFirstStep][0])
  }

  // 「重跑任务」的落点：该批号里**还带着打包消息的最深那一步**（打包消息在打包与出站两步各有一行，
  // 还原那一步它也可能有一行——取最深那一步，一个批号因此只铺一颗按钮，见文件头第 5 条）。
  const rerunSteps = new Map<string, number>()
  for (const step of ordered) {
    const batchId = normalizeBatchId(step.batchId)
    if (batchId === null || packedIds.get(batchId) !== step.messageId) continue
    const at = rerunSteps.get(batchId)
    if (at === undefined || step.step > at) rerunSteps.set(batchId, step.step)
  }

  for (const group of groups.values()) {
    group.total = group.members.length
    const batchId = group.batchId
    if (batchId === null) {
      // 没有批号的那几档（MQ 消费 / 任务输入 / 读库直连）：按本组自己的行进算，没有「批」可言
      group.messages = group.total
      group.failed = group.members.filter((member) => member.status === 'FAILED').length
      group.kind = group.total <= 1 ? 'single' : 'read'
      group.head = batchHead(group.kind, group.messages, group.failed)
      continue
    }
    const packedId = packedIds.get(batchId) ?? null
    group.packed = packedId === null ? null : group.members.find((member) => member.messageId === packedId) ?? null
    group.rerunStep = rerunSteps.get(batchId) ?? null
    const ids = messageIds.get(batchId) ?? new Set<string>()
    const bad = failedIds.get(batchId) ?? new Set<string>()
    // **N 条 / 失败 M 条都按批号算、都不含打包消息**（#143 的多算一条就此闭合）
    group.messages = [...ids].filter((id) => id !== packedId).length
    group.failed = [...bad].filter((id) => id !== packedId).length
    // **这一批有打包消息 = 发送批**：即便这一组只有一条（合并那一步只有信封一行），它也是发送批，
    // 不然那颗「重跑任务」按钮会挂在一个写着「单条 · 不是一批」的组上
    group.kind = packedId !== null ? 'send' : (group.total <= 1 ? 'single' : 'read')
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
 *   打包消息那一行只在其中两格上（合并与出站），但「这一批」是整段（否则用户得猜哪一格能点开）。
 * - **读取批**：只在**换了号**的那一步标（`previous` 是链上的上一步；批号与它不同才算换了号），
 *   中间那些步骤不标（决策 2）。
 * - **一条一组**（没有打包消息、也没有别的成员）按「不是一批」画（决策 10）：批大小 1 的读库批在库里
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
 * 「重跑任务」那一档（#147）：**发送批、这一批有失败、拿得到任务标识、而且这一组就是落点**时返回它。
 *
 * <p>四件事都是判据的一部分，缺一不铺：**档位**（只有发送批的组头上有这颗按钮，合并之前的步骤仍是
 * 逐条断点重投）、**有失败**（全成了就没什么可重跑的）、**任务标识**（重跑是任务级的动作，
 * 组本身不带任务号——界面把当前运行的任务标识喂进来，拿不到就不铺，免得按下去不知道跑谁）、
 * **落点**（一个批号只铺一颗，见 {@link BatchGroup#rerunStep} 与文件头第 5 条）。</p>
 *
 * <p>与旧按钮的区别是**语义**而不是样式：旧的只把请求重发一遍、不跑下游，还要求使用者自己判断
 * 「失败发生在出站还是下游」；这一颗重跑**整个任务**——水位未推进 ⇒ 从未完成的那一批整批重走。</p>
 */
export function batchRerun(group: BatchGroup | null, jobId?: number | null): BatchRerun | null {
  if (!group || group.kind !== 'send' || group.failed <= 0) return null
  if (!group.packed || group.step !== group.rerunStep || jobId == null) return null
  return { jobId, label: RERUN_LABEL, confirm: rerunConfirmText(group, jobId) }
}

/**
 * 「重跑任务」那一档里那三样：要跑哪个任务、按钮写什么、确认框里问什么。
 *
 * 文案与判据一处写（与 `history/redeliver.ts` 同一套做法）：界面与真机脚本判的是**同一句话**，
 * 不会界面上说一套、脚本判另一套。
 */
export interface BatchRerun {
  /** 要重跑的那个任务（组本身不带任务号，由界面把当前运行的任务标识喂进来）。 */
  jobId: number
  /** 按钮上的字（{@link RERUN_LABEL}）。 */
  label: string
  /** 二次确认框里那句话（见 {@link rerunConfirmText}）。 */
  confirm: string
}

/**
 * 二次确认框里那句话：**将从「未完成的那一批」继续**，并且明说**目标端会再收到一次该批请求**。
 *
 * 水位只在一批全部成功之后才推进，所以重跑 = 从未完成的那一批整批重走（含原先没跑到的还原与写回）；
 * 代价是目标端会再收到一次该批请求——这与产品既有契约一致（至少一次交付 + 目标端按业务键去重），
 * 但**必须让按按钮的人知道**，所以写成一次确认，而不是点一下就发。
 */
export function rerunConfirmText(group: BatchGroup, jobId: number): string {
  return `重跑任务 #${jobId}：任务将从「未完成的那一批」继续——重新读这一批、重新打包，`
    + `再把这一批请求发给目标端一次，然后跑完它后面的还原与写回。`
    + `这一批 ${group.messages} 条消息里有 ${group.failed} 条失败。`
    + `目标端会再收到一次该批请求：请确认它按业务键去重（至少一次交付）。`
}
