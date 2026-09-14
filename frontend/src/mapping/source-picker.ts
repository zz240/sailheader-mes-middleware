/**
 * 来源清单与「点一下填进这一行」（#101 数组映射）：数组行选来源数组、子行按相对路径取值、
 * 子行引用数组外层表头（界面上显示成「外层 · 字段名」）。
 *
 * 三处来源写法在这里被分开（#98 行模型 / #101 验收）：
 * - 数组映射行的来源是**来源数组**的路径（`product.bomItems`）；
 * - 数组元素子行的来源是**相对路径**（`materialCode`，相对当前元素）；
 * - 子行引用数组外层的表头字段是**外层引用**（存 `$root.product.productName`，编译器据此
 *   取这份来源数据的顶层对象）。
 *
 * 这里挡的不是「对不对」，而是**形状接不上**：点错了写进去就是一份自相矛盾的规则。真正的
 * 合法性判断（枚举表、片段、必填、类型）仍然只在后端那个只读接口里（#98 D1/D4）。
 *
 * 纯逻辑，不碰 DOM 也不发请求，所以可以用 `node --experimental-strip-types` 直接检查（见 `checks.ts`）。
 */
import { flattenRows, transformForType, type SourceField } from './editor-model.ts'
import { INDEX_SOURCE, ROOT_PREFIX } from './labels.ts'
import { createRow, type MapRow } from './rows.ts'

/** 左边清单上的一项（`label` 就是它显示成的那行字）。 */
export interface PickableItem {
  /**
   * 这一项是什么形状——判断接不接得上只看它，不看路径字符串长什么样：
   * 数组（整个来源数组）、元素字段（相对当前元素）、外层（数组外层的表头字段）、普通字段。
   */
  kind: 'array' | 'element' | 'outer' | 'field'
  /** 引用取自哪个动作；数组元素内的相对路径不存步骤（继承组节点）。 */
  step: number | null
  /** 填进行里的路径：数组路径、相对路径，或 `$root.` 开头的外层引用。 */
  path: string
  /** 取这份来源数据的顶层对象（外层引用）。 */
  root: boolean
  label: string
  type?: string
  samples?: any[]
}

/** 清单上的一个分组。`tip` 是没东西可给时的一句说明。 */
export interface PickableGroup {
  title: string
  items: PickableItem[]
  tip?: string
}

/** 填行的结果；没填进去时 `reason` 说清是哪一种形状接不上。 */
export interface PickResult {
  row: MapRow | null
  reason?: 'no-target' | 'not-array' | 'array-expects-array' | 'child-wants-relative'
}

/** 分组的固定标题（票里要求分组标题写明「数组元素内」）。 */
export const ELEMENT_GROUP_TITLE = '数组元素内'
export const ARRAY_GROUP_TITLE = '数组'
/** 外层引用在界面上的说法（`$root.x` 不上界面，#98 对照表）。 */
export const OUTER_LABEL_PREFIX = '外层 · '

/** 还没选来源数组时，「数组元素内」这一组为什么是空的。 */
const EMPTY_ELEMENT_TIP = '先在数组映射行上选一个来源数组，再从这里选元素内的字段'

// ---------------------------------------------------------------- 路径形状

/** 结构探测给数组元素的字段路径加了 `[0]` 段，据此认出来源是一整个数组。 */
export function isElementPath(path: string): boolean {
  return path.includes('[0]')
}

/** 元素字段属于哪个数组（`product.bomItems[0].qty` → `product.bomItems`）。 */
export function owningArrayOf(path: string): string {
  const at = path.indexOf('[0]')
  return at < 0 ? path : path.slice(0, at)
}

/** 元素字段在数组元素里的相对路径（`product.bomItems[0].qty` → `qty`）。 */
export function relativePathOf(path: string, array: string): string {
  return path.slice(array.length).replace(/^\[0\]\.?/, '')
}

/** 数组元素的字段路径只展开到第一层：嵌套数组这一轮不做（#98 不做深层数组重排）。 */
function isTopLevelElementField(path: string): boolean {
  const relative = path.slice(path.indexOf('[0]') + 3)
  return !relative.startsWith('.') || !relative.slice(1).includes('.')
}

/** 一个来源字段算不算「数组」：探到的是元素字段（带 `[0]`）时，取它所属的那个数组。 */
function arrayPathOf(field: SourceField): string | null {
  return isElementPath(field.name) ? owningArrayOf(field.name) : null
}

// ---------------------------------------------------------------- 左侧来源清单

/**
 * 左边清单取哪些字段，取决于**当前选中的这一行**是哪一行（#101 验收）：
 *
 * - 数组映射行：只给数组——这一行的来源只能是来源数组，给出别的只会点错；
 * - 数组元素子行：「数组元素内」按相对路径给，再加一组「外层」的表头字段；
 * - 其余行：普通字段一组，数组单独一组（点进数组行用）。
 *
 * `group` 是这一行所属的数组映射行（子行才需要）；`step` 是这一批字段来自哪个动作。
 */
export function sourcePickerFor(
  row: MapRow | null,
  fields: SourceField[],
  group: MapRow | null = null,
  step: number | null = null,
): PickableGroup[] {
  // 有 group 就是数组元素子行（调用方从行树里带进来）；它优先于「这一行自己是不是数组行」，
  // 也优先于「还没选行」——子行的清单由组节点决定，和选中哪一行无关。
  if (group) return childRowGroups(group, fields, step)
  if (!row) return [{ title: '可做来源的字段', items: plainFieldItems(fields, step) }]
  if (row.iterate === 'FOR_EACH') return [{ title: ARRAY_GROUP_TITLE, items: arrayItems(fields, step) }]
  return [
    { title: '可做来源的字段', items: plainFieldItems(fields, step) },
    { title: ARRAY_GROUP_TITLE, items: arrayItems(fields, step) },
  ]
}

/**
 * 数组元素子行：先给数组元素内的字段（相对路径，这就是子行的默认来源写法），
 * 再给数组外层的表头字段（外层引用）。
 */
function childRowGroups(group: MapRow, fields: SourceField[], step: number | null): PickableGroup[] {
  const own = group.source.trim()
  const elements = elementItems(fields, own)
  return [
    {
      title: ELEMENT_GROUP_TITLE,
      items: elements,
      tip: elements.length ? undefined : EMPTY_ELEMENT_TIP,
    },
    // 坑：「外层」不是「上一层」——编译器里它始终指这份来源数据的顶层对象（#98 B8）。
    { title: '外层', items: outerItems(fields, own, step) },
  ]
}

/**
 * 数组元素内的字段：按相对路径给。
 *
 * 不传步骤：相对路径继承组节点的来源步骤，子规则自带上 `sourceStep` 后端会拒（#101 验收）。
 */
function elementItems(fields: SourceField[], array: string): PickableItem[] {
  if (!array) return []
  const prefix = `${array}[0].`
  return fields
    .filter(field => field.name.startsWith(prefix))
    .filter(field => isTopLevelElementField(field.name))
    .map(field => ({
      kind: 'element' as const,
      step: null,
      path: relativePathOf(field.name, array),
      root: false,
      label: relativePathOf(field.name, array),
      type: field.type,
      samples: field.samples,
    }))
}

/**
 * 外层引用：取这份来源数据的顶层对象，存成 `$root.` 前缀，界面上显示成「外层 · 字段名」。
 * 排除这一行所在的那个数组自己——在它里面再取它自己没有意义。
 */
function outerItems(fields: SourceField[], ownArray: string, step: number | null): PickableItem[] {
  return fields
    .filter(field => !isElementPath(field.name) && field.name !== ownArray)
    .map(field => ({
      kind: 'outer' as const,
      // 外层引用取这份来源数据的顶层对象，所以**不在这里存步骤**：子规则带上 sourceStep，
      // 后端 FieldMappingConfig.validate 会拒（#101 验收：子规则不带 sourceStep）。
      step: null,
      path: field.name,
      root: true,
      label: `${OUTER_LABEL_PREFIX}${field.name}`,
      type: field.type,
      samples: field.samples,
    }))
}

/** 数组：源结构里探到元素字段（`items[0].x`）就按它推出数组本身，探到数组字段就用它。 */
function arrayItems(fields: SourceField[], step: number | null): PickableItem[] {
  const out: PickableItem[] = []
  const seen = new Set<string>()
  for (const field of fields) {
    const path = arrayPathOf(field)
    if (!path || seen.has(path)) continue
    seen.add(path)
    const own = fields.find(f => f.name === path)
    out.push({
      kind: 'array',
      step,
      path,
      root: false,
      label: path,
      type: own?.type,
      samples: own?.samples,
    })
  }
  return out
}

/** 普通字段：点它就是「这个动作 · 这个路径」。元素字段（`items[0].x`）单独标一种形状。 */
function plainFieldItems(fields: SourceField[], step: number | null): PickableItem[] {
  return fields.map(field => {
    const element = isElementPath(field.name)
    return {
      // 元素字段在这里只是「列出来」：它的相对路径写法归数组元素子行，顶层行点了会被挡下。
      kind: element ? ('element' as const) : ('field' as const),
      step,
      path: field.name,
      root: false,
      label: field.name,
      type: field.type,
      samples: field.samples,
    }
  })
}

// ---------------------------------------------------------------- 点一下填行

/**
 * 点左边清单里的一项：填进这一行。
 *
 * `isChildRow` 是**这一行在数组里的位置**（数组映射行的子行），由调用方从行树里带进来——
 * 行自己不带这个信息：子行不存 `sourceStep`，而顶层行也可能是「任务输入」。
 *
 * 挡的三种接不上（#101 验收）：数组行要的是数组、数组要填进数组行、相对路径只能填进子行。
 */
export function fillRowFromPick(
  row: MapRow | null,
  item: PickableItem,
  isChildRow = false,
): PickResult {
  if (!row) return { row: null, reason: 'no-target' }
  const isArrayRow = row.iterate === 'FOR_EACH'
  if (isArrayRow && item.kind !== 'array') {
    return { row: null, reason: 'array-expects-array' }
  }
  if (!isArrayRow) {
    if (item.kind === 'array') return { row: null, reason: 'not-array' }
    // 元素字段（相对路径）只在数组元素子行里有意义：
    // 顶层行收了它，编译出来是「动作 1 · items[0].qty」——只取第一个元素，读起来却像能用。
    if (item.kind === 'element' && !isChildRow) return { row: null, reason: 'child-wants-relative' }
    // 外层引用是数组元素才有的说法：普通行没有「外面那一层」（#98 B8 的坑）。
    if (item.root && !isChildRow) return { row: null, reason: 'child-wants-relative' }
  }
  row.source = item.root ? ROOT_PREFIX + item.path : item.path
  // 子行不存 sourceStep：组内子规则带上它后端会拒（#101 验收）。外层引用也不存——
  // 它取的始终是组节点那个动作的根对象，步骤由组节点带着。
  if (item.step !== null && !item.root && !isChildRow) row.sourceStep = item.step
  // 只在行还没被加工过时按来源类型猜转换；用户挑过的转换（非直接映射）不动它。
  if (!isArrayRow && (row.transform === 'STRING' || row.transform === '')) {
    row.transform = transformForType(item.type)
  }
  return { row }
}

/** 没填进去时界面上要说的话（与后端无关，只是「你点的东西和这一行接不上」）。 */
export function emptyPickResult(result: PickResult): { row: null; reason: string; message: string } {
  const reason = result.reason ?? 'no-target'
  const messages: Record<string, string> = {
    'no-target': '先选一行要填的目标字段',
    'array-expects-array': '这一行是数组映射，来源要选一个数组',
    'not-array': '这是一个数组，要填进数组映射行',
    'child-wants-relative': '数组子行的来源写的是相对路径——用数组元素内的字段，或「外层 · 」里的表头字段',
  }
  return { row: null, reason, message: messages[reason] ?? messages['no-target'] }
}

// ---------------------------------------------------------------- 展开 / 收起

/** 收起 / 展开一个数组行：返回值是新的收起集合。没给 id 就是一键收起所有数组行。 */
export function toggledRowIds(rows: MapRow[], collapsed: Iterable<number>, id?: number): Set<number> {
  const next = new Set(collapsed)
  const targets = id == null
    ? flattenRows(rows).filter(entry => entry.row.children.length).map(entry => entry.row.id)
    : [id]
  for (const target of targets) {
    if (next.has(target)) next.delete(target)
    else next.add(target)
  }
  return next
}

/** 界面上真正铺出来的行：收起的数组行下面的子行不出现。 */
export function visibleRows(rows: MapRow[], collapsed: Iterable<number>): ReturnType<typeof flattenRows> {
  const hidden = new Set(collapsed)
  const out: ReturnType<typeof flattenRows> = []
  const walk = (list: MapRow[], depth: number, parent: MapRow | null) => {
    for (const row of list) {
      out.push({ row, depth, parent })
      if (row.children.length && !hidden.has(row.id)) walk(row.children, depth + 1, row)
    }
  }
  walk(rows, 0, null)
  return out
}

/** 往数组行下面加一个子行：子行的目标字段写相对路径，所以从空开始。 */
export function appendChildRow(row: MapRow): MapRow {
  if (row.iterate !== 'FOR_EACH') return row
  // 子行不存 sourceStep：组内子规则带上它后端会拒（继承组节点）。
  row.children = [...row.children, createRow(null, '', '', 'STRING')]
  return row
}

/**
 * 往数组行下面加一个「行号」子行（#102）：来源固定 `$index`、起始值从 1 起，目标字段从空开始。
 *
 * 行号只在数组元素子行里合法（顶层用会被 `FieldMappingConfig.validate` 拒），所以这个入口只长在数组行上。
 * 起始值就在行里改（#100），不用再选一次转换。
 */
export function appendIndexChildRow(row: MapRow): MapRow {
  if (row.iterate !== 'FOR_EACH') return row
  // 同 `appendChildRow`：子行不存 sourceStep。
  row.children = [...row.children, createRow(null, INDEX_SOURCE, '', 'INDEX', '', 1)]
  return row
}
