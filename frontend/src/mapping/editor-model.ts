/**
 * 新版映射编辑器的行模型（#99）：目标字段清单 → 一行一个字段的行列表。
 *
 * 只做纯逻辑，不碰 DOM 也不发请求，所以可以用 `node --experimental-strip-types` 直接检查。
 * 行与规则的互转仍然走 `rows.ts` 那一层，这里不重写它。
 */
import { createGroupRow, createRow, isRowConfigured, rowFromRule, ruleFromRow, type MapRow } from './rows.ts'
import { fieldReferenceFullLabel, fieldReferenceLabel, INDEX_SOURCE } from './labels.ts'

/**
 * 目标字段清单里的一个字段；`element` 是数组元素里的字段（相对路径）。
 *
 * `element` 允许写得很明确是 null（而不是只省略这个键）：这份清单会**原样发给后端**（试算的
 * `targetFields`），而对方读的是「这个字段是不是数组」——写上 `element: null` 比让键消失更不容易
 * 被读错（后端返回的探清单里两边的写法都出现过）。
 */
export interface TargetField {
  path: string
  type?: string
  required?: boolean
  element?: TargetField[] | null
}

/** 来源字段清单里的一个字段（由结构探测给出：路径 + 类型 + 样例值）。 */
export interface SourceField {
  name: string
  type: string
  nullable?: boolean
  samples?: any[]
}

/** 一个来源步骤的字段清单。 */
export interface SourceStepFields {
  step: number | null
  sourceKey?: string
  fields: SourceField[]
}

/** 进度条口径：只统计必填字段（#98 B11）。 */
export interface RequiredProgress {
  /** 已配置的必填字段数。 */
  configured: number
  /** 必填字段总数。 */
  total: number
  /** 顶层：已配置 / 总数。 */
  top: { configured: number; total: number }
  /** 数组内：已配置 / 总数。 */
  array: { configured: number; total: number }
  /** 可选字段：已配置 / 总数（只用于悬停提示，不进进度条）。 */
  optional: { configured: number; total: number }
}

/**
 * 目标字段清单 → 行：每个目标字段一行，数组字段占一行并带子行。
 * `sourceStep` 是所有新行的默认来源步骤（编辑器顶部那个下拉）。
 */
export function rowsFromTargetFields(targets: TargetField[], sourceStep: number | null): MapRow[] {
  return targets.map(t => {
    if (t.element?.length) {
      const row = createGroupRow(sourceStep, '', t.path)
      row.children = t.element.map(e => createRow(null, '', e.path, transformForType(e.type)))
      return row
    }
    return createRow(sourceStep, '', t.path, transformForType(t.type))
  })
}

/** 类型 → 默认转换：数字和日期类型预先套上对应转换，其余直接映射。 */
export function transformForType(type?: string): string {
  const t = (type ?? '').toUpperCase()
  if (t.includes('INT') || t.includes('DECIMAL') || t.includes('NUMBER') || t.includes('DOUBLE') || t.includes('FLOAT')) {
    return 'INT'
  }
  if (t.includes('DATE') || t.includes('TIME')) return 'DATE'
  return 'STRING'
}

// ---------------------------------------------------------------- 手工新增行（#102）

/**
 * 「+ 字段映射」：手工加一行空的字段映射（#102）。目标字段从空开始——从补全清单里选一个，
 * 或者直接手输一个清单里没有的路径；目标字段清单不全时，这就是唯一的出路（#98 H3）。
 */
export function appendLeafRow(rows: MapRow[], sourceStep: number | null): MapRow {
  const row = createRow(sourceStep, '', '', 'STRING')
  rows.push(row)
  return row
}

/** 「+ 数组映射」：手工加一行空的数组映射（#102）。目标写数组路径，来源要选一个数组。 */
export function appendArrayRow(rows: MapRow[], sourceStep: number | null): MapRow {
  const row = createGroupRow(sourceStep, '', '')
  rows.push(row)
  return row
}

/**
 * 这一行的目标字段可以填哪些（#102：「可以从清单里选，也可以直接手输」里的那份清单）。
 *
 * 给的和这一行的形状对得上（与 `sourcePickerFor` 同一个口径）：
 * - 数组映射行 → 清单里的数组字段；
 * - 数组元素子行 → 这个数组元素里的相对路径；
 * - 其余行 → 清单里的标量字段。
 *
 * 手工加的行（清单里没有它那个数组）拿不到补全，那正是「手输一个路径」那条路；
 * 手输的路径也不进这份清单——它是用户写下的，不是清单给的。
 */
export function targetCandidatesFor(targets: TargetField[], entry: FlatRow): string[] {
  if (entry.depth > 0 && entry.parent) return elementPathsOf(targets, entry.parent.target.trim())
  const arrays = targets.filter(isArrayField).map(f => f.path)
  if (entry.row.iterate === 'FOR_EACH') return arrays
  const arraySet = new Set(arrays)
  return targets.filter(f => !arraySet.has(f.path)).map(f => f.path)
}

/** 目标清单里的数组字段：探测出来的数组（`element` 非空）或类型写着 ARRAY 的。 */
function isArrayField(field: TargetField): boolean {
  return !!field.element?.length || (field.type ?? '').toUpperCase() === 'ARRAY'
}

/** 目标清单里某个数组元素内的字段路径（相对路径；子行的目标字段就是这么写的）。 */
function elementPathsOf(targets: TargetField[], arrayPath: string): string[] {
  if (!arrayPath) return []
  const field = targets.find(f => f.path === arrayPath)
  return field ? elementPaths(field) : []
}

/** 元素字段的相对路径；元素里再套数组时往下展开（深层数组只用来看清单，重排仍走手写 JSONata）。 */
function elementPaths(field: TargetField, prefix = ''): string[] {
  const out: string[] = []
  for (const child of field.element ?? []) {
    const path = prefix ? `${prefix}.${child.path}` : child.path
    if (child.element?.length) out.push(...elementPaths(child, path))
    else out.push(path)
  }
  return out
}

/**
 * 把探测结果里的扁平路径（`items[0].qty`）折成嵌套的目标字段清单。
 *
 * `required`（是否必填）与 `types`（类型）都按**探测结果里的原始路径**给（带 `[0]` 的那种），
 * 这里折成编辑器的路径口径（`items.qty`）再去查——不折的话数组元素里的字段永远查不到，
 * 而它们恰恰是最需要看必填和类型的那一批。
 */
export function targetFieldsFromPaths(
  paths: string[],
  required: Map<string, boolean> = new Map(),
  types: Map<string, string> = new Map(),
): TargetField[] {
  const requiredByPath = foldArraySegments(required)
  const typesByPath = foldArraySegments(types)
  const roots: TargetField[] = []
  for (const raw of paths) {
    const segments = raw.split('.')
    let level = roots
    let prefix = ''
    for (let i = 0; i < segments.length; i++) {
      const isArrayElement = /\[\d+\]$/.test(segments[i])
      const key = isArrayElement ? segments[i].replace(/\[\d+\]$/, '') : segments[i]
      const last = i === segments.length - 1
      prefix = prefix ? `${prefix}.${key}` : key
      let node = level.find(n => n.path === key)
      if (!node) {
        node = { path: key, required: requiredByPath.get(prefix) ?? false }
        level.push(node)
      }
      if (!last) {
        node.type = 'ARRAY'
        // `element` 可能是 null（清单从后端来时键写的是 null），也可能还是刚建出来的那个空数组
        if (!node.element) node.element = []
        level = node.element
      } else {
        // 中间段路过它的时候已经标成数组了，那就不改回去
        node.type = node.type === 'ARRAY' ? 'ARRAY' : typesByPath.get(prefix)
      }
    }
  }
  return roots
}

/** 把路径里的数组下标去掉（`items[0].qty` → `items.qty`），即编辑器里的目标字段路径口径。 */
function foldArraySegments<V>(byProbePath: Map<string, V>): Map<string, V> {
  return new Map([...byProbePath].map(([path, value]) => [path.replace(/\[\d+\]/g, ''), value]))
}

/** 行在界面上按层级铺开：数组行后面紧跟它的子行。`parent` 是数组行（取它继承的来源步骤用）。 */
export interface FlatRow {
  row: MapRow
  depth: number
  parent: MapRow | null
}

/** 把行树摊平成列表（编辑器所有遍历都走这里）。 */
export function flattenRows(rows: MapRow[]): FlatRow[] {
  const out: FlatRow[] = []
  const walk = (list: MapRow[], depth: number, parent: MapRow | null) => {
    for (const row of list) {
      out.push({ row, depth, parent })
      if (row.children.length) walk(row.children, depth + 1, row)
    }
  }
  walk(rows, 0, null)
  return out
}

/** 已配置的行的集合：目标字段路径 → 这条路径上有没有行、填没填来源。 */
function rowIndex(rows: MapRow[]): Map<string, MapRow> {
  return new Map(flattenRows(rows).filter(e => e.row.target.trim()).map(e => [pathOf(e), e.row]))
}

/** 一行在目标字段清单里的路径（子行要带上数组行的前缀）。 */
function pathOf(entry: FlatRow): string {
  const target = entry.row.target.trim()
  return entry.parent ? `${entry.parent.target.trim()}.${target}` : target
}

/**
 * 进度条口径：必填的目标字段里有几条已经选了来源，顶层和数组内分开数。
 * 「填了目标字段」但没选来源不算配置完成。
 */
export function progressOf(rows: MapRow[], targets: TargetField[]): RequiredProgress {
  const index = rowIndex(rows)
  const counters = {
    top: { configured: 0, total: 0 },
    array: { configured: 0, total: 0 },
    optional: { configured: 0, total: 0 },
  }

  const walk = (list: TargetField[], prefix: string, inArray: boolean) => {
    for (const field of list) {
      const path = prefix ? `${prefix}.${field.path}` : field.path
      const bucket = field.required ? (inArray ? counters.array : counters.top) : counters.optional
      bucket.total++
      const row = index.get(path)
      if (row && rowHasValue(row)) bucket.configured++
      if (field.element?.length) walk(field.element, path, true)
    }
  }
  walk(targets, '', false)

  return {
    configured: counters.top.configured + counters.array.configured,
    total: counters.top.total + counters.array.total,
    top: counters.top,
    array: counters.array,
    optional: counters.optional,
  }
}

/** 这一行的来源选没选（行号行不算，它本来就没有来源字段）。 */
export function rowHasValue(row: MapRow): boolean {
  if (row.transform === 'INDEX') return true
  return row.source.trim() !== ''
}

/**
 * 挑出「下一个该填的行」：当前行之后的第一个空行，没找到就用当前行。
 * 点一下来源就往下走一行，是配一张长清单最省事的走法。
 * 数组映射行不参与——它要的是来源数组，得自己选中（选中之后由 `pick` 落到它的子行上）。
 */
export function nextEmptyRow(rows: MapRow[], from: MapRow | null): MapRow | null {
  const flat = flattenRows(rows).map(e => e.row)
  const blank = flat.filter(r => r.target.trim() && r.iterate !== 'FOR_EACH' && !r.source.trim())
  if (!blank.length) return from
  const start = from ? flat.indexOf(from) + 1 : 0
  return blank.find(r => flat.indexOf(r) >= start) ?? blank[0]
}

/** 行里存的来源字段 → 界面上的引用（拆 `$root.` 前缀、认行号）。 */
export function referenceOf(row: MapRow): { step: number | null; path: string; root: boolean; startAt: number | null } {
  const source = row.source.trim()
  if (row.transform === 'INDEX' || source === INDEX_SOURCE) {
    return { step: row.sourceStep, path: INDEX_SOURCE, root: false, startAt: row.startAt }
  }
  if (source.startsWith('$root.')) {
    return { step: row.sourceStep, path: source.slice('$root.'.length), root: true, startAt: null }
  }
  return { step: row.sourceStep, path: source, root: false, startAt: null }
}

/**
 * 一行当前来源在界面上显示成什么（子行按相对路径显示）。
 *
 * `isChild` 是组件按这一行在树里的层级给出来的（行列表传 `depth > 0`）——它随上下文而变，
 * 所以不进 `MapRow`：试算的证据表（`trial.ts`）也读这一份，同一行两处不会读成两种说法。
 */
export function rowReferenceLabel(
  row: MapRow,
  actionNames: Record<number, string> = {},
  isChild = false,
): string {
  const ref = referenceOf(row)
  return fieldReferenceLabel({ ...ref, relative: isChild && !ref.root && ref.path !== INDEX_SOURCE }, actionNames)
}

/**
 * 一行当前来源的完整说法（悬停提示）。子行自己不存 sourceStep（继承组节点），
 * 所以要取哪份样例得由调用方把组节点的来源步骤传进来。
 */
export function rowReferenceFullLabel(
  row: MapRow,
  actionNames: Record<number, string> = {},
  inheritedStep: number | null = null,
): string {
  const ref = referenceOf(row)
  if (ref.step == null && inheritedStep != null) ref.step = inheritedStep
  return fieldReferenceFullLabel(ref, actionNames)
}

/** 把已存配置的行与目标字段清单对齐：清单里的字段沿用已配的行，其余行追加在后面。 */
export function mergeRowsWithTargets(saved: MapRow[], targets: TargetField[], sourceStep: number | null): MapRow[] {
  const derived = rowsFromTargetFields(targets, sourceStep)
  if (!saved.length) return derived
  const savedIndex = rowIndex(saved)
  const merged = derived.map(row => {
    const hit = savedIndex.get(row.target.trim())
    if (!hit) return row
    // 已存配置的子行优先（ENUM 表、拼接片段这些参数只在已存的行里）。
    const children = row.children.length ? mergeChildren(hit.children, row.children) : hit.children
    return { ...hit, children }
  })
  // 清单里没有的行（手写的、清单不全时加的）原样保留在后面。
  const derivedPaths = new Set(derived.map(r => r.target.trim()))
  const extras = saved.filter(r => !derivedPaths.has(r.target.trim()))
  return [...merged, ...extras]
}

function mergeChildren(saved: MapRow[], derived: MapRow[]): MapRow[] {
  const savedByTarget = new Map(saved.map(r => [r.target.trim(), r]))
  // 顺序跟目标清单走；已存的子行沿用（ENUM 表、拼接片段这些参数只在已存的行里）。
  const ordered = derived.map(d => savedByTarget.get(d.target.trim()) ?? d)
  const derivedNames = new Set(derived.map(d => d.target.trim()))
  return [...ordered, ...saved.filter(s => !derivedNames.has(s.target.trim()))]
}

/** 配置 → 行（编辑器打开时的回填）。 */
export function rowsFromConfig(config: Record<string, any> | null | undefined): MapRow[] {
  const rules = Array.isArray(config?.rules) ? config!.rules : []
  return rules.map((rule: Record<string, any>) => rowFromRule(rule))
}

/**
 * 行 → 配置（编辑器保存时用）：一行一条规则，**没配好的行不进规则**。
 *
 * 这条不变量必须由前端保住：没选来源的空行写成规则，后端会用「映射规则缺少来源字段 source」
 * 把整份配置拒掉——用户明明只配了两行，却连保存都存不下去（真机实测踩到过）。
 * 空行留在编辑器里继续填，只是先不参与保存。
 */
export function configFromRows(rows: MapRow[]): { rules: Record<string, any>[] } {
  const rules = rows
    .filter(isRowConfigured)
    .map((row, i) => ruleFromRow(row, i))
    .filter((rule): rule is Record<string, any> => rule !== null)
  return { rules }
}
