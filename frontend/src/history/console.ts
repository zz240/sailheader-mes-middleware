/**
 * 消息历史页的口径（#129 双栏骨架 / #136 消息步检索模式）：状态芯片、时间戳、列表行、查询串、选中态。
 *
 * **纯模块**：不碰 DOM、不发请求、**也不带自测**——自测在 `history/checks.ts`（本仓的规矩：
 * 纯模块与它的检查分开，见 `mapping/draft.ts` + `mapping/checks.ts`）。
 * 这一条不是洁癖：本文件被 `views/HistoryView.vue` 与 `components/history/*` import，
 * 自测要是留在这里，每次开页面都会在浏览器里跑一遍断言、还会碰到 `process` 这个浏览器没有的全局。
 */
import type { HistoryCounts, Job, JobRun, MessageStep, RunSummary } from '../types'

export type StatusChipKind = 'ok' | 'bad' | 'warn' | 'run'

export interface StatusChip {
  kind: StatusChipKind
  label: string
}

/** 状态词表就这一份：芯片的判档与筛选下拉的选项都从它取（别再各写一遍字符串）。 */
export const STATUS_OK = 'SUCCESS'
export const STATUS_BAD = 'FAILED'
export const STATUS_WARN = 'REDELIVERED'
export const STATUS_RUNNING = 'RUNNING'

/**
 * 状态下拉里「全部状态」那一档的取值（#136）。
 *
 * 它与「没选」（空串）**必须分开**：消息步检索这一侧「没选」的口径是默认只看 FAILED（#74），
 * 而「全部状态」是用户明确说「不看状态」。旧实现把这两件事混成一个空值，于是那一侧的
 * 「全部状态」根本看不到全部——这一格就是那个 bug 的修法。
 */
export const STATUS_ANY = 'ALL'

/** 下拉里的顺序（原型同序；「全部状态」不在这一列里，它是单独的一档，见 HistoryFilters.vue）。 */
export const RUN_STATUSES = [STATUS_BAD, STATUS_OK, STATUS_WARN, STATUS_RUNNING] as const

/** 状态芯片：原型 `statusCls` 的四档 + 原样标签（空值给破折号）。 */
export function statusChip(status?: string | null): StatusChip {
  const clean = status?.trim() ?? ''
  const kind: StatusChipKind = clean === STATUS_OK ? 'ok'
    : clean === STATUS_BAD ? 'bad'
      : clean === STATUS_WARN ? 'warn'
        : 'run'
  return { kind, label: clean || '—' }
}

/** 后端给的是 ISO（`2026-09-17T09:12:31`）：界面统一 `2026-09-17 09:12:31`。 */
export function stampOf(value?: string | null): string {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 19)
}

/** 错误在列表里只占一行。 */
export function firstLine(text?: string | null): string {
  if (!text) return ''
  const cut = text.indexOf('\n')
  return cut < 0 ? text : text.slice(0, cut)
}

/** 长 id（消息 UUID）在界面上只留前 8 位（原型 `shortId`）。 */
export function shortId(id?: string | null): string {
  const clean = id?.trim() ?? ''
  if (!clean) return '—'
  return clean.length > 10 ? `${clean.slice(0, 8)}…` : clean
}

/* ── 两个模式 ─────────────────────────────────────────────────────── */

/** 左列表的两个模式（原型 `view-toggle`）。 */
export type HistoryMode = 'runs' | 'steps'

/** 模式开关的选项与顺序（界面从它渲染，别在组件里再写一遍标签）。 */
export const HISTORY_MODES: readonly { value: HistoryMode; label: string }[] = [
  { value: 'runs', label: '按运行展开' },
  { value: 'steps', label: '消息步检索' },
]

/** 初始模式：运行那一侧（原型 `state.view = 'runs'`，重置也回到它）。 */
export const DEFAULT_MODE: HistoryMode = 'runs'

/* ── 筛选 ─────────────────────────────────────────────────────────── */

export interface RunFilters {
  jobId: number | null
  /** `''` 没选（用模式的默认口径）/ `STATUS_ANY` 全部状态 / 其余是具体状态。 */
  status: string
  businessKey: string
}

export function emptyFilters(): RunFilters {
  return { jobId: null, status: '', businessKey: '' }
}

/** 「用户在这条筛选条上做过选择」——`STATUS_ANY` 也算（它关掉了消息步那一侧的默认 FAILED）。 */
export function hasAnyFilter(filters: RunFilters): boolean {
  return filters.jobId !== null || filters.status !== '' || !!filters.businessKey.trim()
}

/**
 * 状态那一格的三档判决（**只有这一处**判档，查询串与界面文案都从它取）：
 * 没选 / 明确「全部状态」/ 一个具体状态。`RunFilters.status` 是个字符串（控件要它），
 * 但界面与查询串要的是这三档，别再各自 `=== STATUS_ANY`、`=== ''` 地判一遍。
 */
type StatusChoice = { kind: 'unset' } | { kind: 'all' } | { kind: 'one'; status: string }

function statusChoice(filters: RunFilters): StatusChoice {
  if (filters.status === STATUS_ANY) return { kind: 'all' }
  const clean = filters.status.trim()
  return clean ? { kind: 'one', status: clean } : { kind: 'unset' }
}

/** 筛选条上那句话：`任务 #7 · 状态 FAILED · 业务键 SO-2026`。 */
export function describeFilters(filters: RunFilters): string {
  const parts: string[] = []
  if (filters.jobId !== null) parts.push(`任务 #${filters.jobId}`)
  const choice = statusChoice(filters)
  if (choice.kind === 'all') parts.push('状态 全部状态')
  if (choice.kind === 'one') parts.push(`状态 ${choice.status}`)
  if (filters.businessKey.trim()) parts.push(`业务键 ${filters.businessKey.trim()}`)
  return parts.join(' · ')
}

/**
 * 状态那一格实际该带的参数（`''` = 这一侧不带状态）。
 *
 * **两边不对称，别顺手对齐**：运行那一侧默认不过滤；消息步那一侧默认只看 `FAILED`（#74），
 * 只有用户明确选了「全部状态」才放开。
 */
export function statusParam(mode: HistoryMode, filters: RunFilters): string {
  const choice = statusChoice(filters)
  if (choice.kind === 'all') return ''
  if (choice.kind === 'one') return choice.status
  return mode === 'steps' ? STATUS_BAD : ''
}

/**
 * 列表查询串（两个模式共用一个：参数名与顺序都一样，只有路径不同）。
 *
 * 业务键两边同一个语义：消息步那一侧按**该步所属消息**的业务键过滤（后端 `LIKE`）。
 */
export function listQuery(mode: HistoryMode, filters: RunFilters, page: number, size: number): string {
  const params = new URLSearchParams()
  if (filters.jobId !== null) params.set('jobId', String(filters.jobId))
  const status = statusParam(mode, filters)
  if (status) params.set('status', status)
  if (filters.businessKey.trim()) params.set('businessKey', filters.businessKey.trim())
  params.set('page', String(page))
  params.set('size', String(size))
  return params.toString()
}

/**
 * 计数与运行摘要那条只读聚合接口的查询串（#130）：**同一组筛选参数**——
 * 「这次筛选命中了多大规模」与「这条运行值不值得点开」是两个问题，分页只回答第三个。
 *
 * 参数名与顺序跟 {@link listQuery} 一致，`status` 由调用方按当前模式算好
 * （运行那一侧默认不过滤，消息步那一侧默认 FAILED）——两条请求带同一组条件，数字才对得上。
 *
 * `page` / `size` 照传，但它们**只决定摘要给哪一页的运行**：四个计数是**整个筛选集**的，
 * 与分页无关（票面「翻页不影响计数」）。后端把「摘要有页、计数无页」这条写在它自己的注释里。
 */
export function summaryQuery(filters: RunFilters, status: string, page: number, size: number): string {
  const params = new URLSearchParams()
  if (filters.jobId !== null) params.set('jobId', String(filters.jobId))
  if (status) params.set('status', status)
  if (filters.businessKey.trim()) params.set('businessKey', filters.businessKey.trim())
  params.set('page', String(page))
  params.set('size', String(size))
  return params.toString()
}

/**
 * 状态下拉的占位文案。
 *
 * 消息步那一侧「什么都没选」时实际看的是 FAILED，占位符就说这件事——旧实现把它写成「全部状态」、
 * 把真实口径藏在别处，这正是 #136 要修的（ADR-0016 决策 5）。
 */
export function statusPlaceholder(mode: HistoryMode): string {
  return mode === 'steps' ? '默认 FAILED' : '全部状态'
}

/**
 * 筛选条右端那句口径（原型 `filterNote`）。
 *
 * 消息步那一侧**把当前状态口径写出来**：默认只看 FAILED（#74）/ 只看某个状态 / 全部状态。
 */
export function filterNote(mode: HistoryMode, filters: RunFilters): string {
  if (mode === 'runs') return '选任务 / 状态即时生效；业务键敲回车或点查询'
  const choice = statusChoice(filters)
  const status = choice.kind === 'all' ? '全部状态'
    : choice.kind === 'one' ? `只看 ${choice.status}`
      : '默认只看 FAILED（#74）'
  return `消息步检索：${status} · 业务键按消息步过滤`
}

/** 面板头的标题（原型：运行 / 消息步）。 */
export function listTitle(mode: HistoryMode): string {
  return mode === 'runs' ? '运行' : '消息步'
}

/** 面板头右端那句提示（原型 `listHint`）。 */
export function listHint(mode: HistoryMode): string {
  return mode === 'runs' ? '选一条运行看它的消息与消息步' : '默认只看 FAILED（#74）'
}

/** 任务下拉的写法（原型：`#7 MDM 编码回写（B6 链）`）。 */
export function jobOptionLabel(job: Pick<Job, 'id' | 'name'>): string {
  return `#${job.id} ${job.name}`
}

/** 拿到 id 说任务名；找不到就只报 id（列表行与详情头共用一处）。 */
export function jobTitleOf(jobs: readonly Pick<Job, 'id' | 'name'>[], jobId: number): string {
  const job = jobs.find((item) => item.id === jobId)
  return job ? jobOptionLabel(job) : `#${jobId}`
}

/** 面板头上的计数写法（分页在服务端，给不了原型的 `命中 / 全部`）。 */
export function listCountText(total: number): string {
  return `共 ${total} 条`
}

export interface ListEmptyState {
  mode: HistoryMode
  error?: string
  filters: RunFilters
}

export interface ListEmptyCopy {
  title: string
  hint: string
}

/** 空态说法按模式分：运行那一侧三套（接口错了 / 筛没了 / 真的还没有），消息步那一侧要带上状态口径。 */
export function listEmptyCopy(state: ListEmptyState): ListEmptyCopy {
  const { mode, filters } = state
  if (state.error) {
    return mode === 'runs'
      ? { title: '运行列表没取到。', hint: '接口返回了错误，可以重试。' }
      : { title: '消息步列表没取到。', hint: '接口返回了错误，可以重试。' }
  }
  if (mode === 'steps') {
    if (hasAnyFilter(filters)) {
      const filtered = `当前筛选：${describeFilters(filters)}`
      // 状态没选时那半句默认口径要补上，否则「筛没了」看起来像是因为筛得太严
      return {
        title: '没有符合条件的消息步。',
        hint: statusChoice(filters).kind === 'unset' ? `${filtered} · 默认只看 FAILED（#74）` : filtered,
      }
    }
    return { title: '没有符合条件的消息步。', hint: '默认只看 FAILED（#74），可把状态改成「全部状态」。' }
  }
  // 运行那一侧：只有**真把结果收窄**的筛选才配叫「没有符合条件」——「全部状态」是放开的，不算。
  // （它在这里出现，只可能是从消息步那一侧切过来时带过来的。）
  const narrowed = filters.jobId !== null || !!filters.businessKey.trim()
    || statusChoice(filters).kind === 'one'
  if (narrowed) {
    return { title: '没有符合条件的运行。', hint: `当前筛选：${describeFilters(filters)}` }
  }
  return { title: '还没有任何运行记录。', hint: '任务跑过之后，这里会出现它的运行。' }
}

/* ── 列表行 ───────────────────────────────────────────────────────── */

/* ── 计数条与运行摘要（#130） ─────────────────────────────────────── */

/** 计数条上的一项：标签与数字分开给，模板里不再拼字符串。 */
export interface CountItem {
  key: 'runs' | 'messages' | 'steps' | 'failed'
  label: string
  value: number
}

/**
 * 计数条四项，顺序照原型（`运行 / 消息 / 消息步 / 失败步`）。
 *
 * `counts` 为 `null` = 这一轮还没取到（或没取）——此时**一个数都不编**，返回空数组让界面留白。
 */
export function countItems(counts: HistoryCounts | null): CountItem[] {
  if (!counts) return []
  return [
    { key: 'runs', label: '运行', value: counts.runs },
    { key: 'messages', label: '消息', value: counts.messages },
    { key: 'steps', label: '消息步', value: counts.steps },
    { key: 'failed', label: '失败步', value: counts.failedSteps },
  ]
}

/**
 * 运行行的规模摘要（原型 `N 消息 · M 步` + 失败时才有的一段，票面：`N 消息 · M 步 · 失败 K`）。
 *
 * **`失败 0` 不许出现**（票面明说）：没有失败步时那一段整个不写，别拿一个恒在的 `失败 0` 占位——
 * 一屏运行里绝大多数是 0，写出来只是噪声。
 *
 * 返回**分段**而不是一整句：模板要把 `失败 K` 那一段单独上色（原型 `style="color:var(--danger-ink)"`），
 * 而「哪一段算失败」这件事只能有一处说法——{@link countsText} 是从它拼出来的。
 */
export function countsParts(
  summary: Pick<RunSummary, 'messageCount' | 'stepCount' | 'failedSteps'>,
): { text: string; bad: boolean }[] {
  const parts = [
    { text: `${summary.messageCount} 消息`, bad: false },
    { text: `${summary.stepCount} 步`, bad: false },
  ]
  if (summary.failedSteps > 0) parts.push({ text: `失败 ${summary.failedSteps}`, bad: true })
  return parts
}

/** 摘要那一整句（`N 消息 · M 步[ · 失败 K]`）：由 {@link countsParts} 拼出来，别再各写一遍。 */
export function countsText(
  summary: Pick<RunSummary, 'messageCount' | 'stepCount' | 'failedSteps'>,
): string {
  return countsParts(summary).map((part) => part.text).join(' · ')
}

/** 业务键那一格没得写时的说法（消息步行与运行行两处共用一句）。 */
const NO_BUSINESS_KEY = '无业务键'

/** 命中的业务键小标签（原型 `.r-biz`）：一条都没有时给空数组，模板据此整块不铺。 */
export function matchedKeysView(keys: readonly string[] | undefined): string[] {
  return (keys ?? []).map((key) => key.trim() || NO_BUSINESS_KEY)
}

/**
 * 运行行的说法：一次算好，模板里不再重复调 statusChip / 反复查任务名。
 *
 * `summaries` 是聚合接口按运行 id 回的那一份（#130）：缺一条就给空段（界面写「—」）而不是
 * `0 消息 · 0 步`——「还没取到摘要」与「这条运行真的一条消息都没有」在界面上必须分得开。
 */
export interface RunRow {
  run: JobRun
  chip: StatusChip
  jobTitle: string
  started: string
  finished: string
  errLine: string
  /** `N 消息 · M 步[ · 失败 K]` 的分段（拿不到摘要时是 `[{ text: '—', bad: false }]`），模板直接上色。 */
  counts: { text: string; bad: boolean }[]
  /** 命中的业务键（运行侧按业务键筛过时才有内容）。 */
  keys: string[]
}

export function runRows(
  runs: readonly JobRun[],
  jobs: readonly Job[],
  summaries: readonly RunSummary[] = [],
): RunRow[] {
  const byRun = new Map(summaries.map((summary) => [summary.runId, summary]))
  return runs.map((run) => {
    const summary = byRun.get(run.id)
    return {
      run,
      chip: statusChip(run.status),
      jobTitle: jobTitleOf(jobs, run.jobId),
      started: stampOf(run.startedAt),
      finished: stampOf(run.finishedAt),
      errLine: firstLine(run.error),
      counts: summary ? countsParts(summary) : [{ text: '—', bad: false }],
      keys: matchedKeysView(summary?.matchedBusinessKeys),
    }
  })
}

/**
 * 消息步行的说法（#136）：`#运行 id · 步骤 n`、状态、动作、业务键（无则「无业务键」）、
 * 时刻、尝试次数、`任务 #任务 id`、错误首行。
 *
 * `jobId` 是消息步列表接口按页补上的**投影**（`message_step` 表没有这一列）——没有它，
 * 界面每行都得再补拉一次运行，就是 ADR-0016 决策 6 拒绝的 N+1。取不到时写「任务 —」。
 */
export interface StepRow {
  step: MessageStep
  head: string
  chip: StatusChip
  action: string
  businessKey: string
  at: string
  attempt: number
  job: string
  errLine: string
}

export function stepRows(steps: readonly MessageStep[]): StepRow[] {
  return steps.map((step) => ({
    step,
    head: `#${step.runId} · 步骤 ${step.step}`,
    chip: statusChip(step.status),
    action: step.actionName?.trim() || '—',
    businessKey: step.businessKey?.trim() || NO_BUSINESS_KEY,
    at: stampOf(step.createdAt),
    attempt: step.attempt ?? 1,
    job: step.jobId === null || step.jobId === undefined ? '任务 —' : `任务 #${step.jobId}`,
    errLine: firstLine(step.error),
  }))
}

/* ── 选中态 ───────────────────────────────────────────────────────── */

/**
 * 左列表选中了什么。运行模式只定 `runId`；消息步模式连消息与步骤一起定（原型 `state.runId/idx/stepN`）。
 * 右详情的头与列表的选中态都从它推出来——只有一个真相，切换模式时不会两边各说各话。
 */
export interface HistorySelection {
  runId: number | null
  messageId: string | null
  step: number | null
}

export function noSelection(): HistorySelection {
  return { runId: null, messageId: null, step: null }
}

export function selectRun(runId: number): HistorySelection {
  return { runId, messageId: null, step: null }
}

export function selectStep(step: Pick<MessageStep, 'runId' | 'messageId' | 'step'>): HistorySelection {
  return { runId: step.runId, messageId: step.messageId, step: step.step }
}

export function isRunSelected(selection: HistorySelection, runId: number): boolean {
  return selection.runId === runId
}

/** 三样都对上才算「选中的那一条」——只比 runId 会把同一运行下的所有消息步都点亮。 */
export function isStepSelected(
  selection: HistorySelection,
  step: Pick<MessageStep, 'runId' | 'messageId' | 'step'>,
): boolean {
  return selection.runId === step.runId
    && selection.messageId === step.messageId
    && selection.step === step.step
}

/** 右详情头上的路径（原型 `detailPath`）：`#12 · 消息 3f9a1c02… · 步骤 4`。 */
export function detailPath(selection: HistorySelection): string {
  if (selection.runId === null) return '—'
  if (selection.messageId === null || selection.step === null) return `#${selection.runId}`
  return `#${selection.runId} · 消息 ${shortId(selection.messageId)} · 步骤 ${selection.step}`
}
