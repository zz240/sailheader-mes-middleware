/**
 * 配得快一点（#105）：来源清单的搜索、已用高亮与计数、三种过滤，目标字段搜索并跳行，
 * 以及「自动匹配同名」。
 *
 * 这一层是**纯逻辑**：不碰 DOM，也不发请求——`autoMatchSameName` 的整条通路都在这里，
 * 组件只负责把返回的结果画出来（票里那条「自动匹配不发起任何网络请求」就是这么保住的，
 * 检查里也有一条断言看着它）。
 *
 * 两条口径（#98 C5 / D1）：
 * - 自动匹配**只在浏览器里按同名精确匹配**，只填空行、不覆盖已经配好的行。这样不引入第二套匹配
 *   算法和第二个阈值——后端的建议器这一轮不接到界面上，它的能力原样保留。
 * - 过滤与搜索只回答「这一项要不要显示」，**不判对错**：一行的来源对不对仍然只在后端那个只读接口里判。
 */
import {
  flattenRows, rowHasValue, type FlatRow, type SourceField, type TargetField,
} from './editor-model.ts'
import { ROOT_PREFIX, INDEX_SOURCE } from './labels.ts'
import { createGroupRow, isRowConfigured, type MapRow } from './rows.ts'
import { partNeedsPath } from './parts.ts'
import { fillRowFromPick, sourcePickerFor, type PickableGroup, type PickableItem } from './source-picker.ts'

// ---------------------------------------------------------------- 来源字段的搜索

/**
 * 「全部 / 只看未配置 / 只看必填」这三档。两份列表（左边来源、右边行）用的是同一套档位，
 * 所以值、顺序、说法只有这一处定义——两份各写一遍，改一处忘一处就会对不上。
 */
export type FilterKind = 'all' | 'unconfigured' | 'required'

export const FILTER_KINDS: FilterKind[] = ['all', 'unconfigured', 'required']

/** 三档在界面上的说法（顺序就是按钮的顺序）。 */
export const FILTER_LABELS: Record<FilterKind, string> = {
  all: '全部',
  unconfigured: '只看未配置',
  required: '只看必填',
}

/**
 * 来源清单的过滤档（票里那三档）。「必填」不是来源字段自己的属性——来源结构探测只给
 * 路径、类型、样例值。它指的是：**还没配好、且目标字段清单里标了 required 的那些目标字段**
 * 要用的来源（进度条统计的就是它们），所以这一档要带上 `RequiredFieldKeys`。
 */
export type SourceFilter = FilterKind

export const SOURCE_FILTERS: SourceFilter[] = FILTER_KINDS

export const SOURCE_FILTER_LABELS: Record<SourceFilter, string> = FILTER_LABELS

/**
 * 来源清单里的一项跟查询词对得上吗：**搜路径，也搜样例值**（票里那两条）。
 *
 * 数组元素字段额外连它所属的数组路径一起比——探到的路径是 `items[0].qty`，而这一项存的是
 * 相对路径 `qty`（#101：子规则里不带数组前缀），用户想搜的却往往是 `items` 那个名字。
 */
export function sourceItemMatches(item: PickableItem, query: string): boolean {
  const q = query.trim().toLowerCase()
  if (!q) return true
  return searchTexts(item).some(text => text.toLowerCase().includes(q))
}

/** 一项能被搜到的那些文本（路径、数组前缀、样例值）。 */
function searchTexts(item: PickableItem): string[] {
  const texts = [item.path, item.label]
  if (item.group) texts.push(`${item.group}.${item.path}`)
  for (const sample of item.samples ?? []) texts.push(sampleText(sample))
  return texts
}

/** 样例值 → 拿来搜的文本（与枚举表补全用的是同一份口径：对象取 value，没有 value 就整条序列化）。 */
function sampleText(sample: unknown): string {
  if (sample == null) return ''
  if (typeof sample === 'object') {
    const value = (sample as Record<string, unknown>).value
    if (value != null) return String(value)
    return JSON.stringify(sample)
  }
  return String(sample)
}

// ---------------------------------------------------------------- 已用高亮与次数

/** 一个来源引用在草稿里的键：**存进行里的那个形状**，不是清单上显示的那个。 */
export interface SourceRefKey {
  /** 引用取自哪个动作；相对路径（数组元素内）与外层引用不存步骤，这里是 `null`。 */
  step: number | null
  /** 行里存的路径：普通路径、相对路径，或 `$root.` 开头的外层引用。 */
  path: string
  /** 相对路径取自哪个动作（子行继承组节点；同一句 `qty` 在别的动作下是另一个字段）。 */
  groupStep?: number | null
  /** 相对路径属于哪个数组（`product.bomItems`）；同一句 `qty` 在不同数组里也不是一个字段。 */
  group?: string | null
}

/** 键里「不认步骤」的那一档：数组元素内的相对路径与外层引用都不存步骤。 */
const NO_STEP = -1

/** 使用次数：`key` → 用了几处、哪几行在用（点一下能跳过去），以及这一项对应的引用键。 */
export type SourceUsage = Map<string, { count: number; rowIds: number[]; key: SourceRefKey }>

/**
 * 清单上的一项**填进行里之后**长什么样（`fillRowFromPick` 的存法），转成高亮与计数用的键。
 * 清单上这一项在哪一组里点（`inheritedStep` + 这一组的元素属于哪个数组）由调用方带进来。
 *
 * 相对路径与外层引用不存步骤：前者继承组节点的来源步骤，后者取的始终是组节点那个动作的根对象
 * （子规则带上 `sourceStep` 后端会拒，#101）。所以它们归到一个固定的键上，不按步骤分。
 *
 * 相对路径这一项还要落到**哪个数组**上：子行里存的 `qty` 离开组行读不出来（是哪个数组的 `qty`？），
 * 而 `sourceUsageOf` 那一侧是按行的组算的（`row.group`），所以这里用同一条规则——从探测给的
 * 字段名里推出这一项属于哪个数组。不这么接的话，两边的键永远对不上，子行里的来源一个都不亮。
 */
export function sourceRefKeyOf(
  item: PickableItem,
  inheritedStep: number | null = null,
  sourceFields: SourceField[] = [],
): SourceRefKey {
  if (item.kind === 'element') {
    // 子行那一组自带数组（`item.group`）；顶层清单里列出来的元素字段只能从探测结果里认。
    return {
      step: null,
      path: item.path,
      groupStep: inheritedStep,
      group: item.group ?? owningArrayOf(item.path, sourceFields),
    }
  }
  // 「外层」取的是组节点那个动作的顶层对象：子行不存步骤，所以按当前清单是哪一步来算
  // （同一句 `外层 · productName` 在别的动作下是另一个字段）。
  if (item.root) return { step: null, path: ROOT_PREFIX + item.path, groupStep: inheritedStep }
  return { step: item.step, path: item.path }
}

/** 结构探测里哪个数组有这个名字的字段（`materialCode` → `product.bomItems`）；认不出来就是 null。 */
function owningArrayOf(path: string, fields: SourceField[]): string | null {
  const wanted = nameSegment(path)
  const owner = fields.find(f => f.name.includes('[0]') && nameSegment(f.name) === wanted)
  return owner ? owner.name.split('[0]')[0] : null
}

/**
 * 一行当前来源的引用键（相对路径还要落到「哪个数组」上）；没选来源的行没有键（返回 null）。
 *
 * `group` 是这一行的组（它属于哪个数组行），由调用方从行树里带进来——子行自己不记这个，
 * 规则里也没有它（子规则只存相对路径）。
 */
export function rowRefKeyOf(
  row: MapRow,
  inheritedStep: number | null = null,
  group: string | null = null,
): SourceRefKey | null {
  const source = row.source.trim()
  if (!source) return null
  // 行号行取的是行号，不是来源字段：把它当「$index 这个来源被用了」没有意义。
  if (row.transform === 'INDEX' || source === INDEX_SOURCE) return null
  // 相对路径与外层引用都不存步骤（子规则带上 `sourceStep` 后端会拒，#101），所以键里按
  // 「这一步 + 这一组」算：`refKeyId` 再把「哪一组」落到所属数组上。不带继承步骤的话，
  // 别的动作的同名相对路径、甚至「任务输入」里的同名字段会跟它合成一处，计数就虚了。
  if (source.startsWith(ROOT_PREFIX) || row.sourceStep == null) {
    return { step: null, path: source, groupStep: row.sourceStep ?? inheritedStep, group }
  }
  return { step: row.sourceStep, path: source }
}

/**
 * 草稿里每个来源字段被引用了多少处、被哪几行引用。
 *
 * 子行不存 `sourceStep`（继承组节点），也不记自己属于哪个数组，所以遍历时把这两样带下去——
 * 不带的话，子行里那些「相对于当前元素」的引用跟别的动作、别的数组的同名字段就分不开了。
 */
export function sourceUsageOf(rows: MapRow[]): SourceUsage {
  const usage: SourceUsage = new Map()
  const walk = (list: MapRow[], inheritedStep: number | null, group: string | null) => {
    for (const row of list) {
      const key = rowRefKeyOf(row, inheritedStep, group)
      if (key) {
        const id = refKeyId(key)
        const hit = usage.get(id)
        usage.set(id, { count: (hit?.count ?? 0) + 1, rowIds: [...(hit?.rowIds ?? []), row.id], key })
      }
      if (row.children.length) {
        walk(row.children, row.sourceStep ?? inheritedStep, row.source.trim() || null)
      }
    }
  }
  walk(rows, null, null)
  return usage
}

/**
 * 清单上这一项被用了几处（0 = 还没被用过）。
 *
 * `fields` 是左边这份来源字段清单：相对路径（数组元素内的那一组）要拿它认出「属于哪个数组」，
 * 才跟 `sourceUsageOf` 那一侧的键对得上。`selfStep` 是当前清单这一步（子行里就是组节点的步骤）——
 * 同一句 `qty` 在别的动作下是另一个字段，不能算到它头上。
 */
export function usedCountOf(
  usage: SourceUsage,
  item: PickableItem,
  fields: SourceField[] = [],
  selfStep: number | null = null,
): number {
  return usageEntriesOf(usage, item, fields, selfStep).reduce((sum, hit) => sum + hit.count, 0)
}

/** 用了这一项的行的 id（点一下能跳到第一处）。 */
export function usedRowIdsOf(
  usage: SourceUsage,
  item: PickableItem,
  fields: SourceField[] = [],
  selfStep: number | null = null,
): number[] {
  return usageEntriesOf(usage, item, fields, selfStep).flatMap(hit => hit.rowIds)
}

/** 这一项在计数表里对应的那些条目。 */
function usageEntriesOf(
  usage: SourceUsage,
  item: PickableItem,
  fields: SourceField[],
  selfStep: number | null,
): { count: number; rowIds: number[] }[] {
  const key = sourceRefKeyOf(item, selfStep, fields)
  return [...usage.values()].filter(hit => sameRef(hit.key, key))
}

/** 引用键 → 字符串（`SourceUsage` 的键）。导出是给检查用的：断言不该照抄键的内部拼法。 */
export function sourceRefKeyId(key: SourceRefKey): string {
  return `${key.step ?? NO_STEP}\u0000${key.path}\u0000${key.group ?? ''}`
}

const refKeyId = sourceRefKeyId

/** 两个引用键是不是同一个字段：相对路径（不存步骤）还要连「这一步 + 这一组」一起比。 */
function sameRef(a: SourceRefKey, b: SourceRefKey): boolean {
  if (a.path !== b.path) return false
  if (a.step != null || b.step != null) return a.step === b.step
  return (a.groupStep ?? null) === (b.groupStep ?? null) && (a.group ?? null) === (b.group ?? null)
}

// ---------------------------------------------------------------- 「只看必填」要哪些来源

/**
 * 「还没配好、且目标字段清单里标了 required 的那些目标字段」要用的来源字段，按**来源步骤**分好组。
 * 进度条统计的是同一批字段（`progressOf`）——「还没配好」那一半与它同一个口径。
 *
 * 每一档里放的是 `requiredSourceKey(目标字段路径, 在数组元素内吗)`。比的是**最后一段的名字**
 * （与「自动匹配同名」同一个口径）：目标写 `bom.bomLines.qty`、来源写 `product.bomItems[0].qty`，
 * 这两个才是一对。带「哪一层」是因为同一层里才谈得上对应（顶层的 `qty` 与元素里的 `qty` 不是一回事）。
 *
 * `available` 是这一来源真的给得出来的字段（来源样例里有哪些字段）。**名字对不上任何一个来源字段的
 * 目标字段不算进来**：这一档是拿来点来源的，放一个点不出来的名字进去，只会让「筛完一项都不剩」
 * 的说明说成「都配好了」——而进度条那边还在数它没配（#105 走查时发现的）。
 */
export type RequiredFieldKeys = Map<number | null, Set<string>>

/** 必填那一档里的一项怎么拼键：`数组内/顶层` + 名字（最后一段）。 */
export function requiredSourceKey(path: string, inArray: boolean): string {
  return `${inArray ? 'e' : 't'}\u0000${nameSegment(path)}`
}

/** 目标字段清单 + 当前的行树 → 必填那一档要显示哪些来源字段。 */
export function requiredSourceKeys(
  rows: MapRow[],
  targets: TargetField[],
  defaultStep: number | null = null,
  available: SourceField[] = [],
): RequiredFieldKeys {
  const out: RequiredFieldKeys = new Map()
  const add = (step: number | null, path: string, inArray: boolean) => {
    if (!out.has(step)) out.set(step, new Set())
    out.get(step)!.add(requiredSourceKey(path, inArray))
  }
  /** 这一来源里有没有这个名字的字段（顶层字段、或者数组元素里的都算）。 */
  const hasName = (path: string) => !available.length || available.some(f => nameSegment(f.name) === nameSegment(path))

  /** 一行填上了没有（数组行「填上」= 选了来源数组；其余行按 `rowHasValue` 算）。 */
  const configured = (row: MapRow | undefined, inGroup: boolean) => {
    if (!row) return false
    if (row.iterate === 'FOR_EACH') return row.source.trim() !== ''
    return inGroup ? row.source.trim() !== '' || row.transform === 'INDEX' : rowHasValue(row)
  }

  const index = indexByTargetPath(rows)
  const walk = (fields: TargetField[], prefix: string, inArray: boolean, step: number | null) => {
    for (const field of fields) {
      const path = prefix ? `${prefix}.${field.path}` : field.path
      const row = index.get(path)
      const filled = configured(row, inArray)
      // 顶级那一层的字段要写进行里，所以按**这一行的来源步骤**算（没选来源的行跟默认来源走）；
      // 数组元素子行不存步骤，取这份来源数据的还是数组行上那个步骤，所以带下去的是组节点的。
      const stepForLeaf = inArray ? row?.sourceStep ?? step : defaultStep
      const stepForChildren = row?.sourceStep ?? step
      // 名字对得上、又还没填好的必填字段，才是「还差的那几个」。
      if (field.required && !filled && hasName(field.path)) add(stepForLeaf, field.path, inArray)
      if (field.element?.length) {
        walk(field.element, path, true, stepForChildren)
      }
    }
  }
  walk(targets, '', false, defaultStep)
  return out
}

/** 目标字段路径 → 那一行（子行按「所属数组行的目标字段 + 相对路径」算全路径）。 */
function indexByTargetPath(rows: MapRow[]): Map<string, MapRow> {
  const out = new Map<string, MapRow>()
  for (const entry of flattenRows(rows)) {
    const target = entry.row.target.trim()
    if (!target) continue
    out.set(entry.parent ? `${entry.parent.target.trim()}.${target}` : target, entry.row)
  }
  return out
}

/**
 * 这一档过滤下，清单上这一项该不该显示。
 *
 * 「只看必填」按**这一项会被填到哪一步、哪一层**去比对，而清单上那一项记的步骤与它填进行里之后的
 * 步骤不一定一样：
 * - 数组元素内的字段存的是相对路径（#101），它取哪份来源数据由**组节点**决定，所以拿元素字段
 *   自己带的步骤（null）去比是比不上的——那一组还在，就说明组节点就是这一步的；
 * - 「外层」引用也一样：它取的是组节点那个动作的顶层对象。
 *
 * `step` 是这份字段清单取自哪个动作；`inArray` 是清单当前给的是不是数组元素子行那一组。
 */
export function passesSourceFilter(
  item: PickableItem,
  filter: SourceFilter,
  usage: SourceUsage,
  required: RequiredFieldKeys,
  step: number | null = null,
  inArray = false,
): boolean {
  if (filter === 'all') return true
  if (filter === 'unconfigured') return usedCountOf(usage, item) === 0
  const key = sourceRefKeyOf(item)
  const groupStep = key.step ?? step
  return required.get(groupStep)?.has(requiredSourceKey(item.path, inArray)) === true
}

/**
 * 把清单按查询词与过滤档筛一遍（分组结构、组标题与提示都留着，只是每一项少了）。
 *
 * 搜索与过滤合在一处：清单是常驻的那份列表，用户看到的就是「这一档 + 这个词」的结果。
 * `step` 是这份字段清单取自哪个动作，`inArray` 是这一组是不是数组元素子行那一组
 * （数组元素字段与外层引用按它去比对必填那一档）。
 */
export function filterSourceGroups(
  groups: PickableGroup[],
  query: string,
  filter: SourceFilter,
  usage: SourceUsage,
  required: RequiredFieldKeys,
  step: number | null = null,
  inArray = false,
): PickableGroup[] {
  return groups
    .map(group => ({
      ...group,
      items: group.items.filter(item =>
        sourceItemMatches(item, query) && passesSourceFilter(item, filter, usage, required, step, inArray)),
    }))
    .filter(group => group.items.length > 0)
}

// ---------------------------------------------------------------- 目标字段搜索

/** 目标字段列表当前的过滤档（票里：全部 / 只看未配置 / 只看必填）。 */
export type TargetFilter = FilterKind

export const TARGET_FILTERS: TargetFilter[] = FILTER_KINDS

export const TARGET_FILTER_LABELS: Record<TargetFilter, string> = FILTER_LABELS

/** 一次搜索 / 过滤之后，行列表该铺出哪些行。 */
export interface TargetRowsView {
  /** 命中的行 id（搜索时按列表顺序；没搜索词时是当前这一档会显示的那些行）。 */
  rowIds: number[]
  /** 命中几行。 */
  count: number
  /** 上面那些行里，数组元素子行有几行（标题上给个说法）。 */
  inArrayCount: number
  /** 这一档一共有多少行可显示（搜索前的总数；「只看未配置」这类空结果要靠它说清为什么）。 */
  total: number
  /** 有搜索词时：有没有命中。 */
  matched: boolean
}

/**
 * 目标字段列表要显示哪些行：先按这一档过滤，再按搜索词筛（搜的是**目标字段的完整路径**，
 * 数组元素子行要带上它所属的数组行，否则两句 `qty` 分不出来）。
 *
 * **还没填目标字段的空行永远铺出来**（「+ 字段映射」「+ 数组映射」刚加的那一行就是这种）。
 * 搜索和过滤都是围着「目标字段」转的，对一行还没有目标字段的行无从下手；把它们一起筛掉，
 * 用户点完加行就什么都看不见了——那一行明明加进去了（真机实测踩到过）。它们**不算命中**，
 * 搜索框旁边那个数字里也不含它们。
 *
 * `entries` 是行树铺平的结果（含收起的子行——搜索要能搜到收起的那些）。
 */
export function targetRowsView(
  entries: FlatRow[],
  query: string,
  filter: TargetFilter,
  targets: TargetField[],
): TargetRowsView {
  const required = requiredTargetPaths(targets)
  const scoped = entries.filter(entry => {
    // 空行（还没填目标字段）：这一档与搜索词都管不着它，永远显示。
    if (!entry.row.target.trim()) return true
    if (filter === 'unconfigured') return !rowHasValue(entry.row)
    if (filter === 'required') return required.has(fullTargetPath(entry))
    return true
  })
  const q = query.trim().toLowerCase()
  const matched = scoped.filter(entry => {
    if (!entry.row.target.trim()) return true
    return q === '' || fullTargetPath(entry).toLowerCase().includes(q)
  })
  // 命中数只算真被搜到的那些行（空行不算命中——搜索框旁边那个数字说的是「搜到几行」）。
  const hits = matched.filter(entry => entry.row.target.trim())
  return {
    rowIds: matched.map(entry => entry.row.id),
    count: hits.length,
    inArrayCount: hits.filter(entry => entry.depth > 0).length,
    total: scoped.filter(entry => entry.row.target.trim()).length,
    matched: hits.length > 0,
  }
}

/** 这一行的目标字段在清单里的完整路径（子行带上所属数组行——「必填」只认清单里那些路径）。 */
export function fullTargetPath(entry: FlatRow): string {
  const target = entry.row.target.trim()
  return entry.parent ? `${entry.parent.target.trim()}.${target}` : target
}

/** 目标清单里标了 required 的路径（含数组元素里的相对路径折成的全路径）。 */
function requiredTargetPaths(targets: TargetField[]): Set<string> {
  const out = new Set<string>()
  const walk = (fields: TargetField[], prefix: string) => {
    for (const field of fields) {
      const path = prefix ? `${prefix}.${field.path}` : field.path
      if (field.required) out.add(path)
      if (field.element?.length) walk(field.element, path)
    }
  }
  walk(targets, '')
  return out
}

/**
 * 搜索框里按回车：跳到下一个命中的行（一路回车就是在命中项之间走一圈）。
 * `from` 是当前选中的行；找不到命中项时返回 `null`（界面据此说「没搜到」）。
 */
export function nextMatchRowId(view: TargetRowsView, from: number | null): number | null {
  if (!view.rowIds.length) return null
  const at = from == null ? -1 : view.rowIds.indexOf(from)
  return view.rowIds[(at + 1) % view.rowIds.length]
}

// ---------------------------------------------------------------- 自动匹配同名（只在浏览器里）

/** 「自动匹配同名」改动了几行；`rowIds` 是它填上的那些行。 */
export interface AutoMatchResult {
  rowIds: number[]
}

/**
 * 一键「自动匹配同名」：把**空白的行**按同名字段填上，只填空行、不覆盖已经配好的行。
 *
 * 口径（#98 C5）：**只在浏览器里按同名精确匹配**，不调用后端——所以这里一个请求都不发，
 * 候选清单就是编辑器左边那份来源字段清单。同名指**最后一段完全相等**（大小写不敏感）：
 * 目标 `bomLines.qty` 对上来源 `product.bomItems[0].qty`。
 *
 * 谁不参与：
 * - 已经选了来源的行（不覆盖）、常量行（它本来就不取来源）；
 * - 整数组映射行——它要的是**来源数组**，而同名数组不代表两个数组的元素结构对得上。
 *   数组行下面的子行照样参与（那才是省事的地方）。
 *
 * `fields` 是左边清单那一批字段（编辑器顶部那个默认来源的探测结果），所以自动匹配填上的行，
 * 来源步骤与用户手点的完全一样（`fillRowFromPick` 自己会写）。
 */
export function autoMatchSameName(rows: MapRow[], fields: SourceField[], step: number | null): AutoMatchResult {
  const rowIds: number[] = []
  const walk = (list: MapRow[], group: MapRow | null, groupStep: number | null) => {
    for (const row of list) {
      // 子行按组节点的步骤取这份来源数据（数组行还没选来源数组时，跟编辑器顶部的默认来源走）。
      const ownStep = row.sourceStep ?? groupStep
      if (isBlankRow(row) && fillBlankRow(row, fields, ownStep, group, groupStep)) rowIds.push(row.id)
      if (row.children.length) walk(row.children, row, ownStep)
    }
  }
  walk(rows, null, step)
  return { rowIds }
}

/**
 * 把这一行的空白来源填上，填上了返回 true。
 *
 * 走的是**用户手点的那条通路**（`fillRowFromPick`）：形状挡不挡得住、转换要不要跟着来源类型猜、
 * 子行存不存步骤，两边完全一致——自动匹配不引入第二套算法（#98 C5）。只有拼接行例外：
 * 那一行的「来源」是它的片段，`fillRowFromPick` 写的 `row.source` 对拼接行没有用。
 */
function fillBlankRow(
  row: MapRow,
  fields: SourceField[],
  step: number | null,
  group: MapRow | null,
  groupStep: number | null,
): boolean {
  if (row.transform === 'CONCAT') return fillBlankConcatPart(row, fields, step, group, groupStep)
  const item = sameNameSourceItem(row.target, fields, step, group, groupStep)
  return item != null && fillRowFromPick(row, item, group != null).row != null
}

/**
 * 这一行还空白吗——票里「只填空行、不覆盖已经配好的行」就是这一句。
 *
 * 它自己有没有东西，按这条口径依次看：
 * - **选了来源的行一个字都不动**（不管它的转换还差什么参数）——用户挑过这一格；
 * - 行号行：起始值就是它的全部内容，也不动；
 * - 拼接行：看**片段还空着没有**（这一行的「来源」就是它的片段，常量片段填过也不算空）；
 * - 其余行看 `isRowConfigured`（「按现在这样写成规则，后端会不会收」）。
 *
 * 数组映射行整个不参与：它要的是**来源数组**，同名数组不代表两个数组的元素结构对得上
 * （子行照样参与，那才是省事的地方）。
 */
function isBlankRow(row: MapRow): boolean {
  if (row.iterate === 'FOR_EACH' || !row.target.trim()) return false
  if (row.source.trim() || row.transform === 'INDEX') return false
  if (row.transform === 'CONCAT') return row.parts.some(partNeedsPath)
  return !isRowConfigured(row)
}

/**
 * 同一个形状下拿得到、又跟目标字段同名的那个来源字段（没有就返回 null）。
 *
 * 候选**完全由 `sourcePickerFor` 决定**（与用户手点时看到的是同一份清单），只是把选中那一行时
 * 才知道的两件事补上——「数组元素内只给这个数组的元素字段」「外层引用只在子行里有意义」：
 *
 * - 顶层行：普通字段那两组，数组与元素字段都不给（`fillRowFromPick` 也会把它们挡下）；
 * - 数组元素子行：先看元素字段（相对路径），再看「外层」那一组，取第一个同名的；
 * - 拼接行：候选一样，只是填的是空片段，不是 `row.source`。
 */
export function sameNameSourceItem(
  target: string,
  fields: SourceField[],
  step: number | null,
  group: MapRow | null = null,
  groupStep: number | null = null,
): PickableItem | null {
  const wanted = nameSegment(target)
  if (!wanted) return null
  return candidatesFor(fields, step, group, groupStep).find(i => nameSegment(i.path) === wanted) ?? null
}

/**
 * 这一行真点得到的来源字段：与 `sourcePickerFor` 同一个形状口径。
 *
 * 「点得到」这件事只有界面知道——选中一行时才拿得到组行（子行属于哪个数组）。组行的来源数组
 * 可能还没选（数组映射行是手工加的、用户还没点来源数组），而自动匹配发生在那一批行上，
 * 所以这里还要按这份字段清单把组行补出来：组行自己选了来源数组就用它，没选就按名字找一个同名的，
 * 都找不到就退回「外层」那一组（与手点时的行为一致）。
 *
 * 「外层」那一组按**组节点的步骤**给：它取的是组节点那个动作的顶层对象，可能不是编辑器的默认来源。
 */
function candidatesFor(
  fields: SourceField[],
  step: number | null,
  group: MapRow | null,
  groupStep: number | null,
): PickableItem[] {
  if (!group) return sourcePickerFor(null, fields, null, step).flatMap(g => g.items).filter(isAutoMatchCandidate)
  const array = group.source.trim() || arrayForTarget(group.target, fields)?.source || ''
  const asGroup = createGroupRow(null, array, group.target)
  return sourcePickerFor(null, fields, asGroup, groupStep ?? step)
    .flatMap(g => g.items)
    .filter(isAutoMatchCandidate)
}

/** 自动匹配能填的那些候选：数组要填进数组行（数组行不参与），元素字段只在它自己那个数组的子行里给。 */
function isAutoMatchCandidate(item: PickableItem): boolean {
  if (item.kind === 'array') return false
  if (item.kind !== 'element') return true
  return item.group != null
}

/** 目标数组字段（`bom.bomLines`）按名字在来源里找一个数组（`product.bomItems`）：只比最后一段。 */
function arrayForTarget(target: string, fields: SourceField[]): MapRow | null {
  const wanted = nameSegment(target)
  const arrays = [...new Set(fields.filter(f => f.name.includes('[0]')).map(f => f.name.split('[0]')[0]))]
  const hit = arrays.find(array => nameSegment(array) === wanted)
  return hit ? createGroupRow(null, hit, target) : null
}

/**
 * 路径的最后一段、小写、去掉数组下标——「同名」比的就是它。
 * `bom.bomLines.QTY` → `qty`；`product.bomItems[0].qty` → `qty`。
 */
export function nameSegment(path: string): string {
  const segments = path.trim().replace(/\[\d+\]/g, '').split('.').filter(s => s !== '')
  return (segments[segments.length - 1] ?? '').toLowerCase()
}

/**
 * 拼接行的空片段按同名补上来源字段（票里「把空白的行填上」：这一行本身没选来源，
 * 而拼接行上「来源」就是它的片段）。
 *
 * 一条拼接里有好几个片段，只补名字对得上的那一个（对不上就留着让用户自己点）；常量片段与行号片段不动。
 * 补的是**路径那一格**：片段上已经配好的转换与参数留着（#112 起片段能挂转换了）。
 */
function fillBlankConcatPart(
  row: MapRow,
  fields: SourceField[],
  step: number | null,
  group: MapRow | null,
  groupStep: number | null,
): boolean {
  // 同名的判断与普通行共用一套（`sameNameSourceItem`）：拼接行只是把结果写进空片段，不是写 row.source。
  const item = sameNameSourceItem(row.target, fields, step, group, groupStep)
  if (!item) return false
  const next = [...row.parts]
  const at = next.findIndex(partNeedsPath)
  // 没有空片段时什么都不填：不该悄悄往这一行里塞一个片段。
  if (at < 0) return false
  next[at] = { ...next[at], value: item.path }
  row.parts = next
  return true
}

// ---------------------------------------------------------------- 自动填上的行：校验时提醒确认

/** 一条前端提示要挂在哪一行（与后端问题同一套定位口径：目标字段 + 它所属的数组行）。 */
export interface AutoSourceNotice {
  target: string
  parent: string | null
  message: string
}

/**
 * 「自动匹配填上的行」在校验结果里给的那条提示（票里：提醒确认后才算人工结果）。
 *
 * 为什么由前端出：后端那份草稿里**没有**「这一行是自动填的」这个信息，它也不该有——草稿就是
 * 「保存会写出去的规则」，自动与否是编辑器里的本地事实（与 `sourceStep` 那条不变量同一个道理）。
 * 所以提示挂进校验结果列表，但**不改后端返回的内容**。
 */
export function autoSourceNotices(rows: MapRow[]): AutoSourceNotice[] {
  const out: AutoSourceNotice[] = []
  for (const entry of flattenRows(rows)) {
    if (!entry.row.auto) continue
    const target = entry.row.target.trim()
    if (!target) continue
    out.push({
      target,
      parent: entry.parent?.target.trim() ?? null,
      message: `来源是自动匹配同名填上的（${entry.row.source.trim()}）——确认一下再当它是人工结果`,
    })
  }
  return out
}
