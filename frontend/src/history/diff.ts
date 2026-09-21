/**
 * 「与上一步对照」的口径（#133）：把一条消息里**相邻两步的数据快照**按叶子路径对齐，判出 同 / 改 / 增 / 减。
 *
 * **纯模块**：不碰 DOM、不发请求、不带自测（自测在 `history/checks.ts`，与 `console.ts` / `snapshot.ts`
 * 同一套规矩）。ADR-0016 决策 9 要的那一段口径只在这里判一次——组件按 `state` 分派，
 * 真机脚本按同一套口径自己算一遍当裁判。
 *
 * 口径本身（原型 `04-message-history.html` 的 `diffHTML` + 「对照口径」那行注）：
 *   ① 按**叶子路径**对齐：对象走 `.键`、数组走 `[下标]`，对齐的是路径字符串；
 *   ② `改` = 同名路径取值变了；`增` / `减` = 只出现在一侧；
 *   ③ 判等用 `JSON.stringify`；
 *   ④ 数组**按索引**对齐；**空容器记一格**（`{}` / `[]` 自己是一条叶子，不是「没有」）；
 *   ⑤ 计数是**行数**：四个数加起来 = 行数。
 *
 * **连弱处一起接受**（票面明说）：数组中间插入或删除会让后续下标全判成「改」——本期不做 LCS /
 * 键对齐的语义 diff。这是**叶子路径级**对照，不是语义级，界面上的口径说明照这句写。
 */
import { readSnapshot, type SnapshotValue } from './snapshot.ts'
import type { MessageStep } from '../types.ts'

/* ── 一个判决 ─────────────────────────────────────────────────────── */

export type DiffStatus = 'same' | 'chg' | 'add' | 'del'

/** 一行上那个字：同 / 改 / 增 / 减。 */
export const DIFF_LABELS: Record<DiffStatus, string> = { same: '同', chg: '改', add: '增', del: '减' }

/** 四个判决的顺序：摘要行与「口径」那一句都按它写（原型 `dc same/chg/add/del`）。 */
export const DIFF_STATUSES: readonly DiffStatus[] = ['same', 'chg', 'add', 'del']

export interface PathCounts {
  same: number
  chg: number
  add: number
  del: number
}

export interface DiffRow {
  /** 叶子路径：对象是 `order.order_no`，数组是 `lines[0].qty`；整份是标量时是 `（标量）`。 */
  path: string
  status: DiffStatus
  /** 上一步那一侧的值（`减` / `增` 时有一侧是 `undefined` = 界面上那个破折号）。 */
  oldValue: unknown
  newValue: unknown
}

export interface SnapshotDiff {
  rows: DiffRow[]
  counts: PathCounts
}

/* ── 摊平：叶子路径 → 值 ──────────────────────────────────────────── */

/** 整份快照就是一个标量时，原型给的那个路径名。 */
const SCALAR_PATH = '（标量）'

function isContainer(value: unknown): value is SnapshotValue[] | { [key: string]: SnapshotValue } {
  return typeof value === 'object' && value !== null
}

/**
 * 把一份 JSON 值摊成 `叶子路径 → 值`。两个决定与原型逐字一致：
 * ① **空容器记一格**（`{}` / `[]` 自己是一条路径，不是「底下什么都没有」）；
 * ② 容器不自己占一行，只有叶子进表。
 */
export function flattenLeaves(value: SnapshotValue, path = '', into: Map<string, unknown> = new Map()): Map<string, unknown> {
  if (isContainer(value)) {
    if (Array.isArray(value)) {
      if (!value.length) into.set(path || SCALAR_PATH, value)
      value.forEach((item, index) => flattenLeaves(item, `${path}[${index}]`, into))
    } else {
      const keys = Object.keys(value)
      if (!keys.length) into.set(path || SCALAR_PATH, value)
      for (const key of keys) flattenLeaves(value[key], path ? `${path}.${key}` : key, into)
    }
    return into
  }
  into.set(path || SCALAR_PATH, value)
  return into
}

function countsOf(rows: readonly DiffRow[]): PathCounts {
  const counts: PathCounts = { same: 0, chg: 0, add: 0, del: 0 }
  for (const row of rows) counts[row.status] += 1
  return counts
}

/**
 * 对齐两侧快照（原型 `diffHTML` 那一段）：上一步的路径按原顺序先出，本步多出来的路径接在后面。
 * 顺序只在**一侧的键顺序**里定——两侧都有的路径按上一步的位置走。
 */
export function snapshotDiff(prev: SnapshotValue, next: SnapshotValue): SnapshotDiff {
  const before = flattenLeaves(prev)
  const after = flattenLeaves(next)
  const paths = [...before.keys(), ...[...after.keys()].filter((path) => !before.has(path))]
  const rows = paths.map((path): DiffRow => {
    const hasOld = before.has(path)
    const hasNew = after.has(path)
    const oldValue = before.get(path)
    const newValue = after.get(path)
    const status: DiffStatus = hasOld && hasNew
      ? (JSON.stringify(oldValue) === JSON.stringify(newValue) ? 'same' : 'chg')
      : (hasNew ? 'add' : 'del')
    return { path, status, oldValue, newValue }
  })
  return { rows, counts: countsOf(rows) }
}

/* ── 值那一格怎么写 ───────────────────────────────────────────────── */

/** 值那一格最多显示多少字（原型 `showVal` 里那个 46）。 */
export const DIFF_VALUE_CHARS = 46

/** 破折号：这一侧没有这个路径（`增` 的左边 / `减` 的右边）。 */
export const DIFF_ABSENT = '—'

const ISO = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:?\d{2})$/

/**
 * 一格里显示什么：
 * ① `null` / 空串各有自己的写法（`null` / `""`，与 JSON 里的写法对上，不是空白）；
 * ② **容器原样印 JSON**（`{}` / `[]`）——进这一格的容器只有空的那两个（见 {@link flattenLeaves}），
 *    印 `[object Object]` 等于把「空对象」和「空数组」写成同一个东西；
 * ③ ISO 时刻写成 `2026-08-26 10:00:00 UTC`（原型 `fmtIso`：一眼看出这是时间，还带时区）；
 * ④ 其余长过 {@link DIFF_VALUE_CHARS} 的串截断加省略号——**展示层**的截断，值本身不动。
 */
export function diffCellText(value: unknown): string {
  if (value === undefined) return DIFF_ABSENT
  if (value === null) return 'null'
  if (value === '') return '""'
  if (typeof value === 'object') return JSON.stringify(value) ?? DIFF_ABSENT
  if (typeof value === 'string') {
    const iso = ISO.exec(value)
    if (iso) {
      const tz = iso[2] === 'Z' ? 'UTC' : iso[2]
      return `${value.slice(0, 19).replace('T', ' ')} ${tz.slice(0, 6)}`
    }
    return value.length > DIFF_VALUE_CHARS ? `${value.slice(0, DIFF_VALUE_CHARS)}…` : value
  }
  return String(value)
}

/* ── 上下文路径与「上一步」是谁 ───────────────────────────────────── */

/**
 * 摊平路径 → 上下文路径（原型 `toCtxPath`）：`3.order_no` → `context[3].order_no`。
 *
 * 只在**首个数字段**上加方括号：`context[0]` 是任务输入、`context[3]` 是步骤 3 的输出
 * （映射里 `sourceStep` 引的就是它）。数组下标本来就是方括号，照原样留着。
 */
export function contextPath(path: string): string {
  if (!path) return 'context'
  return /^\d/.test(path) ? `context[${path.replace(/^(\d+)/, '$1]')}` : path
}

/**
 * 这一条消息里，选中那一步的**上一步**是谁（按步骤号升序里紧邻的前一个）；本来就是第一步给 `null`。
 *
 * 与 `console.ts` 的 {@link import('./console.ts').chainNodes} 同一份口径：`message.steps` 已经是
 * 按步骤号升序排好的。找当前那一步时**先认 id**（列表行与整行的 id 是同一个），id 认不出来（没带、
 * 或者手里那份不是这条消息里的行）再按步骤号认——步骤号理论上可能重复（测试造数），id 不会。
 */
export function previousStepOf(
  message: { steps: readonly MessageStep[] } | null,
  current: MessageStep | null,
): MessageStep | null {
  if (!message || !current) return null
  const steps = message.steps
  let at = current.id === undefined || current.id === null
    ? -1
    : steps.findIndex((s) => s.id === current.id)
  if (at < 0) at = steps.findIndex((s) => s.step === current.step)
  if (at <= 0) return null
  return steps[at - 1]
}

/* ── 这一票能不能对照（挡路的那几种） ─────────────────────────────── */

type Row = Pick<MessageStep, 'step' | 'dataSnapshot'>

/**
 * 一份快照读出来的档能不能拿去对照：只有 `json` 与 `empty` 两档手里有 JSON 值
 * （`empty` 是合法 JSON 的空容器，摊平后是一格）。
 *
 * 「没落」与「非 JSON 文本 / 超长」分开说——票面要的是「说清原因」，不是一句「没数据」。
 */
function usable(read: ReturnType<typeof readSnapshot>): boolean {
  return read.kind === 'json' || read.kind === 'empty'
}

/** 不能对照时那一侧怎么说：前半句指人，后半句说清为什么。 */
function reasonOf(read: ReturnType<typeof readSnapshot>): string {
  if (read.kind === 'missing') return '没有数据快照：失败步、被跳步与运行中的步骤都不写快照，MQ 入口动作也不记成功步快照'
  if (read.kind === 'text') return '的数据快照不是 JSON 文本，没有键路径可对照'
  if (read.kind === 'oversized') return `的数据快照超过 64 KB 的列上限（#127），这里只画了前一段，对照不了`
  return ''
}

export interface DiffStarter {
  prev: Row
  next: Row
  /** 两侧的标题：`步骤 3 push-qc`（原型那句 `步骤 N 动作`）。 */
  prevHead: string
  nextHead: string
}

export interface DiffBlocker {
  /** `none` = 这是这条消息的第一步；`no-snap` = 某一侧没有可用的快照（原文在 `text` 里）。 */
  state: 'none' | 'no-snap'
  text: string
}

/**
 * 这一票到底能不能对照——组件据此分派，判据不在组件里。
 *
 * 三种结果：`{ start }` 两侧都有可对照的快照（去调 {@link snapshotDiff}）；
 * `{ blocker }` 说清挡在哪儿（第一步 / 哪一侧缺哪种快照）。**两者互斥**。
 */
export function stepDiffStart(
  prev: Row | null,
  current: Row | null,
  meta: { prevAction?: string; nextAction?: string } = {},
): { start: DiffStarter | null; blocker: DiffBlocker | null } {
  const at = (row: Row | null, action?: string): string => `步骤 ${row?.step ?? '?'}${action ? ` ${action}` : ''}`
  if (!prev || !current) {
    return {
      start: null,
      blocker: {
        state: 'none',
        text: `步骤 ${current?.step ?? '?'} 是这条消息的第一步，没有上一步可对照。`,
      },
    }
  }
  const beforeRead = readSnapshot(prev.dataSnapshot)
  const afterRead = readSnapshot(current.dataSnapshot)
  const beforeOk = usable(beforeRead)
  const afterOk = usable(afterRead)
  if (!beforeOk || !afterOk) {
    // 缺的那一侧写全（带它自己的动作名）：两处分开写，「哪一侧没有」就不会含糊
    const who = !beforeOk
      ? `上一步（${at(prev, meta.prevAction)}）`
      : `本步（${at(current, meta.nextAction)}）`
    const why = reasonOf(!beforeOk ? beforeRead : afterRead)
    return {
      start: null,
      blocker: {
        state: 'no-snap',
        // `text` 那两档的 `reasonOf` 自带「的数据快照…」，拼起来是一句完整的话
        text: why.startsWith('没有数据快照')
          ? `${who} ${why}，没法对照。`
          : `${who}${why}，没法对照。`,
      },
    }
  }
  return {
    start: {
      prev,
      next: current,
      prevHead: at(prev, meta.prevAction),
      nextHead: at(current, meta.nextAction),
    },
    blocker: null,
  }
}

/**
 * 一侧快照的标题（`上一步 步骤 2 READ_SQL` / `本步 步骤 3 push-qc`）——
 * 摘要行与标题都从它写，样式不同但内容同源。
 */
export function diffHeads(prevHead: string, nextHead: string): { prev: string; next: string } {
  return { prev: `上一步 ${prevHead}`, next: `本步 ${nextHead}` }
}

/** 摘要行那四个计数（原型 `dc` 那四格）：`同 N / 改 N / 增 N / 减 N`。 */
export function diffCountsText(counts: PathCounts): { status: DiffStatus; label: string; text: string }[] {
  return DIFF_STATUSES.map((status) => ({
    status,
    label: DIFF_LABELS[status],
    text: `${DIFF_LABELS[status]} ${counts[status]}`,
  }))
}

/**
 * 「口径」那一句（原型自带那条注）。票面要求**界面上带一句口径说明**：写明这是叶子路径级、
 * 数组按索引对齐，以及那个连弱处一起接受的后果。
 */
export const DIFF_NOTE = '按叶子路径对齐两侧快照：改 = 同名路径取值变了，增 / 减 = 只出现在一侧；'
  + '数组按索引对齐——中间插入或删除会让后续下标全判成改（这是叶子路径级对照，不是语义级）。'
