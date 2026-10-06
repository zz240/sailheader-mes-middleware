/**
 * 消息历史页的快照口径（#132）：两份快照（输入 / 数据）怎么读、怎么降级、怎么搜。
 *
 * **纯模块**：不碰 DOM、不发请求、不带自测（自测在 `history/checks.ts`，与 `console.ts` 同一套规矩）。
 * ADR-0016 决策 8 要的「只做显示侧降级」就落在这里：什么算非 JSON 文本、什么算空、多长算超长，
 * **只有这一处判**——组件按 `kind` 分派，真机脚本按同一套口径断言，界面里不再各 `JSON.parse` 一遍
 * 再各写一句「解析失败」（那正是三条降级说法会走样的地方）。
 */
import type { MessageStep } from '../types'

/* ── 三种降级的界线 ───────────────────────────────────────────────── */

/**
 * 超长的界线：`message_step` 那条 MEDIUMTEXT 列的上限（16777215 字节 = 16 MB，#141）。
 *
 * 与列上限**同一个数**是有意的：正常链路写进来的快照永远进不了超长那一档，它是留给
 * 「别的东西写进去的 / 列被改宽之后」的护栏——也就是「页面不卡死」这句话的兜底。
 *
 * 这里数的是**字符**（`String.length`）而列上限数的是**字节**：快照能落库就说明它没超列的字节上限，
 * 于是这一档只剩「页面渲染得动吗」这一个问题——按字符数拦更省事也够用（中文最多差 3 倍，
 * 16 MB 的护栏留得起这点余量）。列型再变时这个数跟着变（#141 起它是 MEDIUMTEXT 的上限）。
 */
export const SNAPSHOT_TEXT_CAP = 16_777_215

/** 上面那个数写在人话里的样子（超长那一档的说明要用它，只在这一处写）。 */
export const SNAPSHOT_CAP_LABEL = '16 MB'

/**
 * 超长那一档实际画多少字符（8 KB）。
 *
 * 一屏文本也就两三万字符，8 KB 足够看清「这是什么东西」；超过部分交给复制按钮（复制的是**全文**，
 * 不是这半段）。
 */
export const SNAPSHOT_PREVIEW_CHARS = 8192

/** 一行里的长字符串先截到多少字（原型 `scalarHTML` 里那个 72）。 */
export const LONG_VALUE_CHARS = 72

/* ── 展开层数（原型 `state.depth`） ───────────────────────────────── */

/**
 * 工具栏上那三档展开层数（原型 `['1:1 层', '2:2 层', '9:全部']`）。
 *
 * 用户看到的「层」是**容器的层数**：选 1 层 = 根下面的容器自动展开，更深的折叠。
 * 交给库的 `deep` 要 +1，见 {@link deepOf}。
 */
export const SNAPSHOT_DEPTHS: readonly { value: string; label: string }[] = [
  { value: '1', label: '1 层' },
  { value: '2', label: '2 层' },
  { value: 'all', label: '全部' },
]

/** 默认两层：够看清结构，又不至于一屏几千行（原型 `state.depth = 2`）。 */
export const DEFAULT_SNAPSHOT_DEPTH = '2'

/**
 * 「全部」档交给库的那个数。
 *
 * 比任何真实快照都深就够（库判的是 `node.level >= deep`，99 层不可能有人写到）；
 * 原型那三档的取值是 `1 / 2 / 9`，但那是**它自己那棵树**的层数，不是这个库的 `deep`，
 * 而且 9 层在病态嵌套上会露馅——所以这里不跟着写 9。
 */
export const SNAPSHOT_DEEP_ALL = 99

/**
 * 界面上的「层」换算成 `vue-json-pretty` 的 `deep`。
 *
 * 库判的是 `node.level >= deep` 就折叠，而根那一行自己算 0 层、根的孩子算 1 层——所以
 * 「自动展开 1 层容器」在库那边是 `deep = 2`（`deep = 1` 只画根那一行）。
 */
export function deepOf(depth: string): number {
  if (depth === '1') return 2
  if (depth === '2') return 3
  return SNAPSHOT_DEEP_ALL
}

/**
 * 树实际用的 `deep`：**一搜就整个展开**（ADR-0016 决策 10：放弃行内 `<mark>`，
 * 换成「按路径过滤 + 自动展开到命中路径」——库做不了定位展开，那把整个命中分支摊开是一样的效果）。
 */
export function treeDeep(depth: string, query: string): number {
  return query.trim() ? SNAPSHOT_DEEP_ALL : deepOf(depth)
}

/* ── 读一份快照 ───────────────────────────────────────────────────── */

export type SnapshotKind = 'missing' | 'text' | 'empty' | 'oversized' | 'json'

/**
 * 一个 JSON 值。与 `vue-json-pretty` 的 `JSONDataType` 同形（`string | number | boolean | unknown[] |
 * Record<string, unknown> | null`）——树那边直接吃它，不用在组件里 `as any`。
 */
export type SnapshotValue = string | number | boolean | null | SnapshotValue[] | { [key: string]: SnapshotValue }

export interface SnapshotRead {
  /** 落在哪一档：没落 / 非 JSON 文本 / 空 / 超长 / 正常 JSON。 */
  kind: SnapshotKind
  /** 原文全文（复制那一头要的；超长时也是全文，不是截断那半段）。 */
  raw: string
  /** 界面上要显示的原文（只有非 JSON 与超长两档有；超长时是截断后的前缀）。 */
  text: string
  /** 解析出来的值（`json` 与 `empty` 两档有，其余 `undefined`）。 */
  value: SnapshotValue | undefined
  /** 空那一档的标签：`空对象 {}` / `空数组 []`；其余档空串。 */
  emptyLabel: string
  /** 一句口径说明（正常的 JSON 与「没落」都不写）。 */
  note: string
}

const EMPTY_OBJECT_LABEL = '空对象 {}'
const EMPTY_ARRAY_LABEL = '空数组 []'

const TEXT_NOTE = '非 JSON 文本：解析不出 JSON 值，断点续投回填时它进不了上下文，'
  + '下游引用该步只会取到空（#90 / #123 口径）。'

type SnapshotContainer = SnapshotValue[] | { [key: string]: SnapshotValue }

function isContainer(value: unknown): value is SnapshotContainer {
  return typeof value === 'object' && value !== null
}

/** 空那一档的标签；不是空容器给空串。 */
function emptyLabelOf(value: unknown): string {
  if (Array.isArray(value)) return value.length ? '' : EMPTY_ARRAY_LABEL
  if (isContainer(value)) return Object.keys(value).length ? '' : EMPTY_OBJECT_LABEL
  return ''
}

/**
 * 空容器那一句：**合法但是空的**——与「没落」和「非 JSON」都分得开，也点明后果。
 *
 * 生产里没有「这一步为什么是空的」这一列（原型那句 `emptyWarn` 是 fixture 现写的现场解释），
 * 所以这里只写这一档本身说得清的部分：它是合法 JSON、但没有内容、下游取它下面的值会落空。
 */
function emptyNote(label: string): string {
  const what = label === EMPTY_ARRAY_LABEL
    ? '一项都没有——下游按下标取它里面的值'
    : '一个字段都没有——下游按路径取它下面的值'
  return `${label}：这一份快照是合法 JSON，但${what}只会拿到空（#90 口径）。`
}

/**
 * 读一份快照原文，判它落在哪一档。
 *
 * 四步，顺序不能换：**空白**（没落）→ **超长**（不解析，直接截断）→ **JSON.parse**（失败即非 JSON 文本）
 * → **空容器**。超长排在最前是有意的：解析一份 16 MB 级的文本本身就是这一档要防的事。
 */
export function readSnapshot(raw?: string | null): SnapshotRead {
  const text = raw ?? ''
  if (!text.trim()) {
    return { kind: 'missing', raw: text, text: '', value: undefined, emptyLabel: '', note: '' }
  }
  if (text.length > SNAPSHOT_TEXT_CAP) {
    return {
      kind: 'oversized',
      raw: text,
      text: text.slice(0, SNAPSHOT_PREVIEW_CHARS),
      value: undefined,
      emptyLabel: '',
      note: `超长快照：共 ${text.length} 字符，超过 ${SNAPSHOT_CAP_LABEL} 的列上限（#141）`
        + `——这里只画前 ${SNAPSHOT_PREVIEW_CHARS} 字符。`,
    }
  }
  let value: SnapshotValue
  try {
    value = JSON.parse(text)
  } catch {
    return { kind: 'text', raw: text, text, value: undefined, emptyLabel: '', note: TEXT_NOTE }
  }
  const emptyLabel = emptyLabelOf(value)
  if (emptyLabel) {
    return { kind: 'empty', raw: text, text: '', value, emptyLabel, note: emptyNote(emptyLabel) }
  }
  return { kind: 'json', raw: text, text: '', value, emptyLabel: '', note: '' }
}

/* ── 两块快照 ─────────────────────────────────────────────────────── */

export type SnapshotWhich = 'input' | 'data'

/** 复制按钮的档位：`json`（整份格式化 JSON）/ `text`（原文）/ `''`（没落，不铺按钮）。 */
export type SnapshotCopyKind = '' | 'json' | 'text'

/** 快照面板要的那一份（一块 = 一侧快照的标题 / 去向 / 读出来的档 / 树 / 复制）。 */
export interface SnapshotSide {
  which: SnapshotWhich
  /** `输入快照` / `数据快照`（原型 `snapHTML` 那两个标题）。 */
  title: string
  /** 去向那一行（原型 `where`）：输入是「进入本步的报文」，数据是「本步输出 → context[N]」。 */
  where: string
  read: SnapshotRead
  /** 喂给树的那份数据：没搜索就是原值，搜索时是过滤后的（见 {@link filterSnapshot}）。 */
  tree: SnapshotValue
  /** 「原始文本」那一档给的东西：**整份**格式化后的 JSON（搜索只改树，不改这一份）。 */
  rawText: string
  /** 搜索那一句（没搜是空串）；两块各写各的命中数，非 JSON 那两档照实说没得搜。 */
  searchNote: string
  /** 复制按钮拿到的东西：JSON 档给格式化后的 JSON，文本档给原文全文。 */
  copyText: string
  /** 复制按钮的标签（`复制 JSON` / `复制原文`）；没落那一档空串 = 不铺按钮。 */
  copyLabel: string
  /** 复制按钮是哪一档（组件按它分派，不拿展示文案当判据）。 */
  copyKind: SnapshotCopyKind
}

export interface SnapshotFilter {
  /** 过滤后的树（命中分支 + 它的祖先）。 */
  value: SnapshotValue
  /** 路径含搜索片段的节点数（原型 `walk` 里那个 `onMatch`）。 */
  matches: number
  /** 数组被折成了原下标做键的对象（说明里要交代一句，见 {@link searchCopy}）。 */
  arraysTrimmed: boolean
}

interface FilterState {
  matches: number
  arraysTrimmed: boolean
}

/**
 * 按**键路径**过滤一份 JSON（原型 `walk`）：路径含搜索片段的留下，其余连同祖先一起丢掉；
 * 容器自己命中时整格保留（哪怕孩子都不命中——原型 `selfMatch` 那一支）。
 *
 * 两处有意与原型不同：
 * ① 大小写不敏感（原型 `toLowerCase`）——照抄；
 * ② **数组被裁过就折成「原下标做键」的对象**。不然过滤后的数组下标会重排，界面上的 `[0]`
 *    与快照里的 `[7]` 就不是一回事了（那是最坏的那种「看着对」。折成对象时下标是真的，
 *    只是显示成 `{"7": …}`，这一点由 {@link searchCopy} 那句口径说明交代）。
 */
function filterValue(value: SnapshotValue, path: string, q: string, state: FilterState):
{ value: SnapshotValue | undefined; kept: boolean } {
  const selfMatch = path !== '' && path.toLowerCase().includes(q)
  if (selfMatch) state.matches += 1

  if (!isContainer(value)) return selfMatch ? { value, kept: true } : { value: undefined, kept: false }

  if (Array.isArray(value)) {
    const kept: { index: number; value: SnapshotValue }[] = []
    value.forEach((item, index) => {
      const child = filterValue(item, `${path}[${index}]`, q, state)
      if (child.kept && child.value !== undefined) kept.push({ index, value: child.value })
    })
    if (!selfMatch && !kept.length) return { value: undefined, kept: false }
    if (kept.length === value.length) return { value: kept.map((entry) => entry.value), kept: true }
    const folded: { [key: string]: SnapshotValue } = {}
    for (const entry of kept) folded[String(entry.index)] = entry.value
    state.arraysTrimmed = true
    return { value: folded, kept: true }
  }

  const out: { [key: string]: SnapshotValue } = {}
  let anyChild = false
  for (const [key, item] of Object.entries(value)) {
    const child = filterValue(item, path ? `${path}.${key}` : key, q, state)
    if (child.kept && child.value !== undefined) {
      out[key] = child.value
      anyChild = true
    }
  }
  if (!selfMatch && !anyChild) return { value: undefined, kept: false }
  return { value: out, kept: true }
}

/**
 * 键路径搜索（票面：按键路径过滤并自动展开到命中路径）。
 *
 * 标量根（整份快照就是一个数 / 一段串）没有键路径可搜：原样留着、命中数为 0——
 * 「搜不了」不是「被搜没了」。
 */
export function filterSnapshot(value: SnapshotValue, query: string): SnapshotFilter {
  const q = query.trim().toLowerCase()
  if (!q || !isContainer(value)) return { value, matches: 0, arraysTrimmed: false }
  const state: FilterState = { matches: 0, arraysTrimmed: false }
  const { value: filtered, kept } = filterValue(value, '', q, state)
  // 一条都不命中时给一个**空的同型容器**（`{}` / `[]`），不是 `undefined`：
  // 树那边拿到 `undefined` 就什么都不画，与「这一步没有快照」长得一样——那是两个不同的档。
  const empty: SnapshotValue = Array.isArray(value) ? [] : {}
  return {
    value: kept && filtered !== undefined ? filtered : empty,
    matches: state.matches,
    arraysTrimmed: state.arraysTrimmed,
  }
}

/**
 * 搜索那一句（原型那两套说法 + 两处补的口径）。
 *
 * **三种档都要给反馈**：搜索框是两块共用的一个输入框，非 JSON / 超长那两档没有键路径可搜，
 * 也得说一句「没得搜」——不然敲了字界面一动不动，看着像搜索坏了（原型那句 footer 本来也是无条件的）。
 */
function searchCopy(query: string, read: SnapshotRead, filter: SnapshotFilter): string {
  const q = query.trim()
  if (read.kind === 'missing') return ''
  if (read.kind === 'text' || read.kind === 'oversized') {
    return `“${q}”：这一段不是 JSON 文本，没有键路径可搜。`
  }
  const head = filter.matches
    ? `“${q}” 命中 ${filter.matches} 处键路径，命中分支已自动展开`
    : `“${q}” 没有匹配的键路径，试着换成字段名片段`
  return filter.arraysTrimmed
    ? `${head} · 数组按原下标保留为键（界面上写成对象）`
    : head
}

/** 复制按钮拿到什么、叫什么、是哪一档（组件按 `kind` 分派，不拿展示文案当判据）。 */
function copyOf(read: SnapshotRead): { text: string; label: string; kind: SnapshotCopyKind } {
  if (read.kind === 'missing') return { text: '', label: '', kind: '' }
  if (read.kind === 'text' || read.kind === 'oversized') {
    return { text: read.raw, label: '复制原文', kind: 'text' }
  }
  return { text: prettyJson(read.value), label: '复制 JSON', kind: 'json' }
}

/** 一份 JSON 值格式化出来（`undefined` 只在「没落」那几档出现，给空串）。 */
function prettyJson(value: SnapshotValue | undefined): string {
  return value === undefined ? '' : (JSON.stringify(value, null, 2) ?? '')
}

const SIDE_TITLES: Record<SnapshotWhich, string> = { input: '输入快照', data: '数据快照' }

/**
 * 单步卡里那两块快照（原型 `.snaps` 的并排两份）：输入在前、数据在后。
 *
 * `row` 是**按 id 取回来的整行**（`/history/steps/{id}`，见 #131 补记指的那条路）——
 * 索引那条路上的行没有快照原文，手里没有整行时两块都是「没落」，界面因此不会把
 * 「还没取到」画成「没有快照」：那由 `loading` 那一路管。
 */
export function snapshotSides(
  row: Pick<MessageStep, 'step' | 'inputSnapshot' | 'dataSnapshot'> | null,
  query: string,
): SnapshotSide[] {
  if (!row) return []
  const raw: Record<SnapshotWhich, string | null | undefined> = {
    input: row.inputSnapshot,
    data: row.dataSnapshot,
  }
  return (['input', 'data'] as const).map((which) => {
    const read = readSnapshot(raw[which])
    const filter = filterSnapshot(read.value ?? null, query)
    const copy = copyOf(read)
    return {
      which,
      title: SIDE_TITLES[which],
      where: which === 'input' ? '进入本步的报文' : `本步输出 → context[${row.step}]`,
      read,
      tree: filter.value,
      rawText: prettyJson(read.value),
      searchNote: query.trim() ? searchCopy(query, read, filter) : '',
      copyText: copy.text,
      copyLabel: copy.label,
      copyKind: copy.kind,
    }
  })
}

/* ── 长值 ─────────────────────────────────────────────────────────── */

/** 一行里的字符串长过 {@link LONG_VALUE_CHARS} 就该截断 + 给一个「展开」的出口。 */
export function isLongValue(value: unknown): boolean {
  return typeof value === 'string' && value.length > LONG_VALUE_CHARS
}

/** 长值截断后的写法（带引号，与库里那份 `"…"` 的写法对齐）。 */
export function longPreview(value: string): string {
  return value.length > LONG_VALUE_CHARS ? `"${value.slice(0, LONG_VALUE_CHARS)}…"` : `"${value}"`
}
