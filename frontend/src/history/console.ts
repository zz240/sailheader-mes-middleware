/**
 * 消息历史页的口径（#129，第 1 张票）：状态芯片、时间戳、列表文案、查询串。
 *
 * **纯模块**：不碰 DOM、不发请求、**也不带自测**——自测在 `history/checks.ts`（本仓的规矩：
 * 纯模块与它的检查分开，见 `mapping/draft.ts` + `mapping/checks.ts`）。
 * 这一条不是洁癖：本文件被 `views/HistoryView.vue` 与 `components/history/*` import，
 * 自测要是留在这里，每次开页面都会在浏览器里跑一遍断言、还会碰到 `process` 这个浏览器没有的全局。
 */

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

/** 下拉里的顺序（原型同序）。 */
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

export interface RunFilters {
  jobId: number | null
  status: string
  businessKey: string
}

export function emptyFilters(): RunFilters {
  return { jobId: null, status: '', businessKey: '' }
}

export function hasAnyFilter(filters: RunFilters): boolean {
  return filters.jobId !== null || !!filters.status || !!filters.businessKey.trim()
}

/** 筛选条上那句话：`任务 #7 · 状态 FAILED · 业务键 SO-2026`。 */
export function describeFilters(filters: RunFilters): string {
  const parts: string[] = []
  if (filters.jobId !== null) parts.push(`任务 #${filters.jobId}`)
  if (filters.status) parts.push(`状态 ${filters.status}`)
  if (filters.businessKey.trim()) parts.push(`业务键 ${filters.businessKey.trim()}`)
  return parts.join(' · ')
}

/**
 * 运行视图的查询串。
 *
 * **默认不过滤**：状态只有选了才带（消息步那一侧默认只看 FAILED，两边的口径本来就不对称，#74）。
 * 业务键走 `businessKey`，语义是「该运行下任一条消息的业务键命中就保留这条运行」。
 */
export function runQuery(filters: RunFilters, page: number, size: number): string {
  const params = new URLSearchParams()
  if (filters.jobId !== null) params.set('jobId', String(filters.jobId))
  if (filters.status) params.set('status', filters.status)
  if (filters.businessKey.trim()) params.set('businessKey', filters.businessKey.trim())
  params.set('page', String(page))
  params.set('size', String(size))
  return params.toString()
}

/** 任务下拉的写法（原型：`#7 MDM 编码回写（B6 链）`）。 */
export function jobOptionLabel(job: Pick<{ id: number; name: string }, 'id' | 'name'>): string {
  return `#${job.id} ${job.name}`
}

/** 拿到 id 说任务名；找不到就只报 id（列表行与详情头共用一处）。 */
export function jobTitleOf(jobs: readonly { id: number; name: string }[], jobId: number): string {
  const job = jobs.find((item) => item.id === jobId)
  return job ? jobOptionLabel(job) : `#${jobId}`
}

/** 面板头上的计数写法（分页在服务端，给不了原型的 `命中 / 全部`）。 */
export function listCountText(total: number): string {
  return `共 ${total} 条`
}

export interface ListEmptyState {
  error?: string
  filters: RunFilters
}

export interface ListEmptyCopy {
  title: string
  hint: string
}

/** 空态三套说法：接口错了 / 筛没了 / 真的还没有。 */
export function listEmptyCopy(state: ListEmptyState): ListEmptyCopy {
  if (state.error) {
    return { title: '运行列表没取到。', hint: '接口返回了错误，可以重试。' }
  }
  if (hasAnyFilter(state.filters)) {
    return { title: '没有符合条件的运行。', hint: `当前筛选：${describeFilters(state.filters)}` }
  }
  return { title: '还没有任何运行记录。', hint: '任务跑过之后，这里会出现它的运行。' }
}
