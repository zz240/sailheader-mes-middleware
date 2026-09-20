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

/* ── 一条运行的消息步取多少（#131） ───────────────────────────────── */

/** 取一条运行的消息步时一页多少条——与后端 `safeSize` 的上限（100）对齐，一页顶格取。 */
export const RUN_STEPS_PAGE = 100

/**
 * 一条运行最多取回多少步（#131 把票面那个「只取前 100 个」的隐藏上限**显式化**）。
 *
 * 票面给了两条路：给出提示，或去掉上限。这里**两条都走**——常见运行一次取全（上限远在它之上，
 * 实测库里最大的一条运行 720 步：`/history/runs/summary` 逐页扫 2682 条运行得到），
 * 而真遇到病态大运行时不至于把页面拖死；真撞上上限时 {@link runStepsNote} 会把话说出来，
 * 「静默」这一条就此消失。
 */
export const RUN_STEPS_MAX = 1000

/** 取某一条运行某一页消息步的查询串（按 id 倒序，与列表接口同一套分页口径）。 */
export function runStepsQuery(runId: number, page: number): string {
  const params = new URLSearchParams()
  params.set('runId', String(runId))
  params.set('page', String(page))
  params.set('size', String(RUN_STEPS_PAGE))
  return params.toString()
}

/**
 * 接口回了 `total` 之后要追几页。
 *
 * 只回页数、不回「截没截断」——「截断了没有」是**手里有多少 vs 接口说有多少**比出来的，
 * 界面拿 {@link runStepsNote} 从两个数直接算；多回一个布尔字段就是同一个判决的第二份定义
 * （而且那一份没人读，只有测试读）。
 */
export function runStepsPages(total: number): number {
  return Math.min(Math.max(1, Math.ceil(total / RUN_STEPS_PAGE)), Math.ceil(RUN_STEPS_MAX / RUN_STEPS_PAGE))
}

/**
 * 截断那句话（**没截断给空串**，界面据此整块不铺）：界面只在「拿到的比接口说的少」时才开口。
 *
 * 说的不只是「截断了」，还包括「本来有多少」与「为什么」——只写一个上限数字，等于把
 * 「你看的是残缺的」这件事又说了一半。
 */
export function runStepsNote(loaded: number, total: number): string {
  if (total <= loaded) return ''
  return `这条运行共 ${total} 个消息步，界面只带了前 ${loaded} 个（上限 ${RUN_STEPS_MAX}）`
    + '——不再静默截断（#131）。剩下的在消息步检索模式里按条件查。'
}

/* ── 消息切换与步骤链（#131） ─────────────────────────────────────── */

/** 消息 chip 上的那颗状态：`全通` / `失败 N 步`（原型 `mchip` 里那一句三元）。 */
export interface MessageChip {
  kind: 'ok' | 'bad'
  label: string
}

/** 一条消息的判决——芯片与断言都从这里取，别在模板里再数一遍 FAILED。 */
export function messageChip(failed: number): MessageChip {
  return failed > 0 ? { kind: 'bad', label: `失败 ${failed} 步` } : { kind: 'ok', label: '全通' }
}

/**
 * 一条运行里的一条消息：它自己的消息步（**按步骤号升序**）、失败步数与业务键。
 *
 * 业务键取该消息任一步上的第一个非空值：`message_step` 按步存业务键，同一条消息的每一步
 * 都是同一个（链路生成时一次算出来），所以取第一个就够，不为它单独跑一趟接口。
 */
export interface RunMessage {
  messageId: string
  /** 没有（或全空白）时写「无业务键」——与消息步行、命中业务键标签同一个口径。 */
  businessKey: string
  steps: MessageStep[]
  failed: number
  chip: MessageChip
}

/**
 * 把一条运行的消息步按消息分组（原型 `run.messages`）。
 *
 * 顺序两处都要归一：**消息之间按落库先后**（步 `id` 升序——接口给的是 id 倒序，不翻过来的话
 * 消息 chip 会倒着走），**消息之内按步骤号升序**（票面：步骤链按步骤号升序）。
 */
export function runMessages(steps: readonly MessageStep[]): RunMessage[] {
  const byMessage = new Map<string, RunMessage>()
  for (const step of [...steps].sort((a, b) => a.id - b.id)) {
    let message = byMessage.get(step.messageId)
    if (!message) {
      message = {
        messageId: step.messageId, businessKey: '', steps: [], failed: 0,
        chip: messageChip(0),
      }
      byMessage.set(step.messageId, message)
    }
    message.steps.push(step)
    if (step.status === STATUS_BAD) message.failed += 1
  }
  for (const message of byMessage.values()) {
    message.steps.sort((a, b) => (a.step ?? 0) - (b.step ?? 0))
    message.businessKey = message.steps.map((s) => s.businessKey?.trim()).find((key) => !!key)
      || NO_BUSINESS_KEY
    message.chip = messageChip(message.failed)
  }
  return [...byMessage.values()]
}

/**
 * 右详情手里那一份「选中了什么」（原型 `cur()`）：选中的消息与选中的步骤。
 *
 * **归一在同一处**：选中的消息不在（或还没选）就是第 1 条；选中的步骤不在这条消息里
 * （换消息之后、或列表那一页刷新过）就是这条消息的**第 1 步**——原型 `cur()` 里那个
 * 「找不到就取下标 0」的循环。界面因此永远有一个当前消息与当前步骤，不会出现半空的右栏。
 */
export interface RunDetail {
  messages: RunMessage[]
  /** 当前消息在 `messages` 里的下标（一条消息都没有时是 0）。 */
  index: number
  message: RunMessage | null
  step: MessageStep | null
}

export function runDetail(steps: readonly MessageStep[], selection: HistorySelection): RunDetail {
  const messages = runMessages(steps)
  if (!messages.length) return { messages, index: 0, message: null, step: null }
  const found = selection.messageId === null
    ? -1
    : messages.findIndex((m) => m.messageId === selection.messageId)
  const index = found >= 0 ? found : 0
  const message = messages[index]
  const step = message.steps.find((s) => s.step === selection.step) ?? message.steps[0] ?? null
  return { messages, index, message, step }
}

/** 快照点：这一格的两个小点里对应的那个亮不亮（空串与纯空白都算「没落」）。 */
function hasSnapshot(text?: string | null): boolean {
  return !!text && !!text.trim()
}

/** 步骤链上的一格（原型 `chainHTML` 里那个循环的一轮）。 */
export interface ChainNode {
  /** `0` = 原型那格 `context[0] 任务输入`；其余是步骤号。 */
  n: number
  /** 左上那行：`0 · 任务输入` / `步骤 3`。 */
  head: string
  /** 动作那行：`context[0]` / 动作名（没有动作名写破折号）。 */
  action: string
  /** 最下一行的灰字：只有 `context[0]` 那格有（`本次运行的触发报文`）。 */
  note: string
  chip: StatusChip
  /** 两个快照点（输入 / 数据）亮不亮。 */
  hasInput: boolean
  hasData: boolean
  selected: boolean
  /** 可访问名称：步骤号、动作、状态，外加两个快照点有没有落（票面要求写清这几样）。 */
  label: string
  /** 点这一格要选中的那一步；`context[0]` 那格是静态的（原型里它也不是按钮）。 */
  step: MessageStep | null
}

/**
 * 步骤链的节点：从 `context[0] 任务输入` 起，其后每条消息步一格（原型 `chainHTML`）。
 *
 * 消息为 `null`（这条运行还没有消息步 / 还没取回来）时只回 `context[0]` 那一格——
 * 「入口那一格永远在」是原型的口径，界面据此不会在取数途中把链整块抽掉。
 *
 * `current` 是**归一之后**的那一步（{@link runDetail} 的 `step`），不是原始 `selection`：
 * 刚点开一条运行时选中态里还没有消息与步骤，而右栏已经在看第 1 条消息的第 1 步了——
 * 拿原始选中态判的话，链上一格都不会亮（原型是在 `open-run` 里先把 `stepN` 归一再渲染的）。
 *
 * 票面要的可访问名称写「步骤号、动作、名称、状态」四样。**这个模型里只有一样**：
 * `message_step` 只有 `action_name` 一列（没有独立的「动作类型」与「动作名称」两格），
 * 所以那三样合成一句 `步骤 3 · 动作 push-qc · 状态 FAILED`，再带上两个快照点——
 * 硬凑第四格只会把同一个值写两遍（ADR-0016 决策 5 的 #131 补记里记了这条偏差）。
 */
export function chainNodes(message: RunMessage | null, current: MessageStep | null): ChainNode[] {
  const nodes: ChainNode[] = [{
    n: 0,
    head: '0 · 任务输入',
    action: 'context[0]',
    note: '本次运行的触发报文',
    chip: statusChip(null),
    hasInput: false,
    hasData: false,
    selected: false,
    label: '步骤 0 · 任务输入 · context[0] · 本次运行的触发报文',
    step: null,
  }]
  if (!message) return nodes
  for (const step of message.steps) {
    const chip = statusChip(step.status)
    const action = step.actionName?.trim() || '—'
    const hasInput = hasSnapshot(step.inputSnapshot)
    const hasData = hasSnapshot(step.dataSnapshot)
    nodes.push({
      n: step.step,
      head: `步骤 ${step.step}`,
      action,
      note: '',
      chip,
      hasInput,
      hasData,
      selected: !!current && current.messageId === step.messageId && current.step === step.step,
      label: `步骤 ${step.step} · 动作 ${action} · 状态 ${chip.label}`
        + ` · 输入快照 ${hasInput ? '有' : '无'} · 数据快照 ${hasData ? '有' : '无'}`,
      step,
    })
  }
  return nodes
}

/** 链头右端那句（原型 `${steps.length} 步 · 入口 ${steps[0].action}`）。 */
export function chainSummary(message: RunMessage | null): string {
  if (!message || !message.steps.length) return '还没有消息步'
  return `${message.steps.length} 步 · 入口 ${message.steps[0].actionName?.trim() || '—'}`
}

/** 单步卡上的一格事实（原型 `.fact`）：标签与值分开给，模板里不再拼字符串。 */
export interface StepFact {
  label: string
  value: string
}

/**
 * 单步卡（#131 票面：状态、动作、尝试次数、时刻、错误块）。
 *
 * `facts` 三格照原型那张 `.fact` 行（尝试 / 落库 / 输出 `context[n]（未写）`）。
 * **耗时不在这一票里**：动作耗时是 #134 的后端改动（`message_step.duration_ms`），
 * 这一票只把卡片长出来，耗时那一格由它补——现在写 `—` 会把「没测」与「测得是 0」混起来，
 * 正是 ADR-0016 决策 7 拒绝的那种含糊。
 *
 * `error` **原样保留换行**（票面：便于直接粘贴），只把第一行单独切出来给模板加粗
 * （原型 `errHTML` 里那句 `<b>`）；`error` 为空串时整块不铺——成功步不显示空错误块。
 */
export interface StepCardView {
  n: number
  action: string
  chip: StatusChip
  /** 尝试次数（缺了给 1：与消息步行同一个口径）。 */
  attempt: number
  /** 落库时刻。 */
  at: string
  facts: StepFact[]
  /** 错误原文（保留换行）；空串 = 没有错误块。 */
  error: string
  /** 错误第一行（加粗那一行）。 */
  errorFirst: string
  /** 其余各行（连同前面那个换行原样带回；单行错误时是空串）。 */
  errorRest: string
}

/** 本步的输出有没有写回上下文：成功与重投过都写了，失败步没写（原型那半句「（未写）」）。 */
function wroteOutput(status: string | null | undefined): boolean {
  const clean = status?.trim() ?? ''
  return clean === STATUS_OK || clean === STATUS_WARN
}

export function stepCardView(step: MessageStep | null): StepCardView | null {
  if (!step) return null
  const chip = statusChip(step.status)
  const error = step.error && step.error.trim() ? step.error : ''
  const lines = error.split('\n')
  return {
    n: step.step,
    action: step.actionName?.trim() || '—',
    chip,
    attempt: step.attempt ?? 1,
    at: stampOf(step.createdAt),
    facts: [
      { label: '尝试', value: String(step.attempt ?? 1) },
      { label: '落库', value: stampOf(step.createdAt) },
      { label: '输出', value: `context[${step.step}]${wroteOutput(step.status) ? '' : '（未写）'}` },
    ],
    error,
    errorFirst: lines[0] ?? '',
    errorRest: lines.length > 1 ? `\n${lines.slice(1).join('\n')}` : '',
  }
}
