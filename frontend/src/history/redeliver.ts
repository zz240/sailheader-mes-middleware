/**
 * 断点重投的口径（#135）：入口能不能点、弹窗写什么、进行中与失败各说什么。
 *
 * **纯模块**：不碰 DOM、不发请求、**也不带自测**——自测在 `history/redeliver-checks.ts`
 * （本仓的规矩：纯模块与它的检查分开，见 `mapping/draft.ts` + `mapping/checks.ts`）。
 *
 * **这个页面上的每一句话都在这里**：组件模板里不再硬写「成功后原步置 REDELIVERED」这类句子
 * （两轴复核把这一条挑出来了——同一句话散在组件与纯模块两处，界面上说的与真机脚本判的就会
 * 慢慢分家）。缺省写法（「无业务键」与破折号）也复用列表那一套，不另立第二套。
 *
 * 语义一个字都没改（ADR-0009 / #108 / #90）：
 *   - **只有 `FAILED` 的消息步能重投**——`REDELIVERED` 的含义是「该步已成功续跑」，
 *     它不是「再投一次」的入口；
 *   - 成功后原步置 `REDELIVERED`（**原运行仍是那次失败**），失败则**保持 `FAILED`**；
 *   - 重投带上更早步骤的输出（`edge.resumeContext` 回填 `context[step]`），
 *     下游 `sourceStep` 引用上游时映射结果与首次执行一致。
 */
import { NO_BUSINESS_KEY, STATUS_BAD, STATUS_WARN, textOrDash } from './console.ts'
import type { MessageStep } from '../types.ts'

/** 弹窗标题（原型 `#redeliverTitle`）。 */
export const REDELIVER_TITLE = '断点重投'
/** 取消按钮上的字。 */
export const REDELIVER_CANCEL_LABEL = '取消'
/** 确认按钮上的字。 */
const CONFIRM_LABEL = '确认重投'
/** 进行中确认按钮上的字：不是还写「确认重投」让人以为没点上。 */
const CONFIRM_PENDING_LABEL = '重投中…'

/**
 * 这一投最多等多久（毫秒）。
 *
 * 「界面不停留在 pending」不能只对**报错**成立：一次挂住不回的请求会让 pending 永远挂着，
 * 而进行中弹窗是**不许关**的（`redeliverClosable`），用户就只剩刷新页面这一条路。给一个上限，
 * 到点了当成一次失败处理（错误文案见 {@link redeliverTimeoutText}）。
 *
 * 2 分钟是照着这条链路最慢的合法情形定的：目标端不可达时 HikariCP `connectionTimeout` 默认 30 s、
 * 每条消息还要按任务的 `retryCount` 退避重试（`docs/flows/reliability-flow.md` 实测 `retryCount=1`
 * 一条消息就是 60 s）。
 */
export const REDELIVER_TIMEOUT_MS = 120_000

/** 重投那一步的地址：只有这一处拼（组件与真机脚本判的是同一条路）。 */
export function redeliverPath(stepId: number): string {
  return `/history/steps/${stepId}/redeliver`
}

/**
 * 这一步能不能重投：**只有 `FAILED`**。
 *
 * 与后端 `MessageHistoryService.redeliver` 的第一道门同一个判据（`仅失败消息步可重投`）。
 * **这里只判状态**——后端另有两道门（任务未启用、源动作失败无断点数据），要在这里判就得先取
 * 那个任务的动作表（一次点击多一次请求，正是 ADR-0016 决策 6 拒绝的 N+1），所以那两档由接口
 * 那句错误说清（`MessageHistoryService.java:515/:518`），文案不假装它们不会发生
 * （见 {@link redeliverFailure} 的那半句）。
 */
export function canRedeliver(step: Pick<MessageStep, 'status'> | null | undefined): boolean {
  return (step?.status?.trim() ?? '') === STATUS_BAD
}

/** 入口上的字：`从步骤 3 断点重投`（原型 `sc-foot` 那个按钮）。 */
export function redeliverButtonLabel(step: Pick<MessageStep, 'step'>): string {
  return `从步骤 ${step.step} 断点重投`
}

/** 可重投那一档的后果交代（原型 `sc-foot` 按钮右边那半句）。 */
export function redeliverOutcomeNote(): string {
  return '重投成功后原步置 REDELIVERED，重投失败则原步保持 FAILED（#108）。'
}

/** 弹窗底下那句（原型 `.mf` 的 hint，逐字）：与单步卡那一格说的是同一件事，只是更短。 */
export function redeliverDialogNote(): string {
  return '重投只投这一条消息，成功后原步置 REDELIVERED。'
}

/**
 * 不可重投那一档的说明（原型 `sc-foot` 的 else 分支）。
 *
 * `REDELIVERED` 额外补一句「这一步的首跑报错已由重投修复」——不然用户看着一个不能点的
 * 「成功」会以为重投坏了（原型同句）。
 */
export function redeliverBlockedNote(step: Pick<MessageStep, 'status'> | null | undefined): string {
  const status = step?.status?.trim() || '—'
  const head = `本步状态 ${status}，不可重投：只有 ${STATUS_BAD} 消息步能断点续跑（#108）。`
  return status === STATUS_WARN ? `${head}这一步的首跑报错已由重投修复。` : head
}

/**
 * 弹窗里那几样事实（票面：**哪条消息的哪一步**——消息 id、业务键、步骤号、动作、状态）。
 *
 * `businessKey` 由调用方给右详情**分组之后**的那个值（`RunMessage.businessKey`，同样一句
 * 「无业务键」）；不传就退回该步自己的那一格。动作与状态缺省写破折号——与列表行同一个口径
 * （`textOrDash`）。
 */
export interface RedeliverFacts {
  runId: number
  /** 完整的消息 id（弹窗要写清「哪条消息」——列表那一行给的是运行与步骤号，没有消息 id）。 */
  messageId: string
  businessKey: string
  step: number
  action: string
  status: string
}

export function redeliverFacts(
  step: Pick<MessageStep, 'runId' | 'messageId' | 'step' | 'actionName' | 'status'>
    & Partial<Pick<MessageStep, 'businessKey'>>,
  businessKey?: string | null,
): RedeliverFacts {
  const key = (businessKey ?? step.businessKey ?? '').trim()
  return {
    runId: step.runId,
    messageId: textOrDash(step.messageId),
    businessKey: key || NO_BUSINESS_KEY,
    step: step.step,
    action: textOrDash(step.actionName),
    status: textOrDash(step.status),
  }
}

/** 弹窗第一句：`从失败消息步 #12 / 步骤 3 upsert（业务键 SO-…）断点续跑。`（原型同句）。 */
export function redeliverHeadline(facts: RedeliverFacts): string {
  return `从失败消息步 #${facts.runId} / 步骤 ${facts.step} ${facts.action}`
    + `（业务键 ${facts.businessKey}）断点续跑。`
}

/**
 * 弹窗里那段后果交代。
 *
 * 两件事都要说：只投这一条消息、上游快照会被带上（#90）——不写这句，「重投看起来成功、
 * 下游却取到空」的疑虑就只能靠猜；以及后果本身（{@link redeliverOutcomeNote}）。
 */
export function redeliverExplain(): string {
  return '重投只投这一条消息：用该步的数据从这一步重放，更早步骤的输出会一并带上'
    + '（下游 sourceStep 引用上游时，映射结果与首次执行一致）。'
    + redeliverOutcomeNote()
}

/** 确认按钮上的字（进行中换成 `重投中…`，配合 `loading` 一起用）。 */
export function redeliverConfirmLabel(pending: boolean): string {
  return pending ? CONFIRM_PENDING_LABEL : CONFIRM_LABEL
}

/**
 * 进行中能不能关弹窗：**不能**。
 *
 * 请求已经发出去了，关掉弹窗就没地方说这一投到底成了没成（用户会以为「取消了」）——
 * 那正是 ADR-0016 决策 11 要挡住的那类含糊。挂死的请求由 {@link REDELIVER_TIMEOUT_MS} 封顶，
 * 所以「关不掉」不会是个无期状态。
 */
export function redeliverClosable(pending: boolean): boolean {
  return !pending
}

/**
 * 成功后那句（`ElMessage.success`）：写清原步置成了**什么**，以及「列表与详情都刷新了」。
 *
 * `status` 取接口回来的那一格（`HistoryController.redeliver` 回 `{status, stepId}`），
 * 不在界面这一侧写死 `REDELIVERED`——后端说是它才是它。
 */
export function redeliverDoneMessage(facts: RedeliverFacts, status: string): string {
  return `重投完成：运行 #${facts.runId} 的步骤 ${facts.step} 已置 ${textOrDash(status)}，`
    + '列表与详情都刷新了。'
}

/** 失败后弹窗里那三句：标题 / 接口给的原因 / 原步还在那儿。 */
export interface RedeliverFailure {
  title: string
  detail: string
  hint: string
}

/**
 * 失败那一档的文案。
 *
 * 最后那半句**不承诺「再投一次就行」**：源动作（READ_SQL / MQ_CONSUME）失败本来就没有断点数据
 * （后端第二道门），那种要重新执行任务——两轴复核指出原来那句会把人带偏。
 */
export function redeliverFailure(raw: string): RedeliverFailure {
  return {
    title: '重投没成功。',
    detail: raw.trim() || '接口没有给出原因。',
    hint: '原步仍是 FAILED，没有标成 REDELIVERED（#108）。原因见上面那句：'
      + '修好依赖可以再投一次；如果上面说的是源动作（READ_SQL / MQ_CONSUME）失败或任务未启用，'
      + '那要按它的说法处理（前者没有断点数据，得重新执行任务）。',
  }
}

/** 到点还没回来那一句（超时被当成一次失败）。 */
export function redeliverTimeoutText(): string {
  const minutes = Math.round(REDELIVER_TIMEOUT_MS / 60_000)
  return `重投超过 ${minutes} 分钟还没回来：这一次的结果不知道。`
    + '刷新一下这条运行的消息步再决定要不要重投（#108：没跑通就不会标 REDELIVERED）。'
}

/**
 * 接口那一侧抛出来的东西 → 界面上那句错误。
 *
 * 超时（`AbortSignal.timeout`）走 {@link redeliverTimeoutText}，其余照原样带出 `Error.message`
 * （`ApiError` 的 message 就是后端信封里那句「重投失败: &lt;根因&gt;」），一个都不吞。
 */
export function redeliverErrorText(error: unknown): string {
  const name = (error as { name?: string } | null | undefined)?.name ?? ''
  if (name === 'TimeoutError' || name === 'AbortError') return redeliverTimeoutText()
  if (error instanceof Error) return error.message || '接口没有给出原因。'
  return String(error)
}
