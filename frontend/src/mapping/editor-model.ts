/**
 * 新版映射编辑器的行模型（#99）：目标字段清单 → 一行一个字段的行列表。
 *
 * 只做纯逻辑，不碰 DOM 也不发请求，所以可以用 `node --experimental-strip-types` 直接检查。
 * 行与规则的互转仍然走 `rows.ts` 那一层，这里不重写它。
 */
import { createGroupRow, createRow, rowFromRule, type MapRow } from './rows.ts'
import { fieldReferenceFullLabel, fieldReferenceLabel, INDEX_SOURCE, ROOT_PREFIX } from './labels.ts'

/** 目标字段清单里的一个字段；`element` 是数组元素里的字段（相对路径）。 */
export interface TargetField {
  path: string
  type?: string
  required?: boolean
  element?: TargetField[]
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

/** 把探测结果里的扁平路径（`items[0].qty`）折成嵌套的目标字段清单。 */
export function targetFieldsFromPaths(paths: string[], required: Map<string, boolean> = new Map()): TargetField[] {
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
        node = { path: key, required: required.get(prefix) ?? false }
        level.push(node)
      }
      if (!last) {
        node.type = 'ARRAY'
        node.element = node.element ?? []
        level = node.element
      } else {
        node.type = node.type === 'ARRAY' ? 'ARRAY' : undefined
      }
    }
  }
  return roots
}

/** 已配置的行的集合：目标字段路径 → 这条路径上有没有行、填没填来源。 */
function rowIndex(rows: MapRow[]): Map<string, MapRow> {
  const index = new Map<string, MapRow>()
  const walk = (list: MapRow[], prefix: string) => {
    for (const row of list) {
      const path = prefix ? `${prefix}.${row.target.trim()}` : row.target.trim()
      if (row.target.trim()) index.set(path, row)
      if (row.children.length) walk(row.children, path)
    }
  }
  walk(rows, '')
  return index
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

  const requiredTop = counters.top
  const requiredArray = counters.array
  return {
    configured: requiredTop.configured + requiredArray.configured,
    total: requiredTop.total + requiredArray.total,
    top: requiredTop,
    array: requiredArray,
    optional: counters.optional,
  }
}

/** 这一行的来源选没选（行号行不算，它本来就没有来源字段）。 */
export function rowHasValue(row: MapRow): boolean {
  if (row.transform === 'INDEX') return true
  return row.source.trim() !== ''
}

/** 这一行有没有被配置过（用于「只看未配置」这类过滤，以及判断空行）。 */
export function rowConfigured(row: MapRow): boolean {
  if (!row.target.trim()) return false
  return rowHasValue(row) || row.iterate === 'FOR_EACH'
}

export interface PickResult {
  /** 填了哪一行；没填返回 null。 */
  row: MapRow | null
  /** 为什么没填。 */
  reason?: 'no-target' | 'not-array' | 'array-expects-array'
}

/**
 * 把一份来源字段填进当前行（#99 验收：点来源列表里的字段就填进当前行）。
 *
 * - 当前行是数组映射行时，只接受来源数组（路径带 `[0]`）；
 * - 当前行是普通行时，不接受来源数组。
 */
export function fillRowFromSource(row: MapRow | null, field: SourceField, step: number | null): PickResult {
  if (!row) return { row: null, reason: 'no-target' }
  const isArray = isArrayPath(field.name)
  if (row.iterate === 'FOR_EACH' && !isArray) return { row: null, reason: 'array-expects-array' }
  if (row.iterate !== 'FOR_EACH' && isArray) return { row: null, reason: 'not-array' }
  row.source = field.name
  row.sourceStep = step
  if (row.iterate !== 'FOR_EACH') row.transform = transformForType(field.type)
  return { row }
}

/** 结构探测给数组元素的字段路径加了 `[0]` 段，据此认出来源是一整个数组。 */
export function isArrayPath(path: string): boolean {
  return path.includes('[0]')
}

/**
 * 挑出「下一个该填的行」：当前行之后的第一个空行，没找到就用当前行。
 * 点一下来源就往下走一行，是配一张长清单最省事的走法。
 * 数组映射行不参与——它要的是来源数组，得自己选中（#99 只做骨架，数组在下一张票里配）。
 */
export function nextEmptyRow(rows: MapRow[], from: MapRow | null): MapRow | null {
  const flat: MapRow[] = []
  const walk = (list: MapRow[]) => list.forEach(r => { flat.push(r); if (r.children.length) walk(r.children) })
  walk(rows)
  if (!flat.length) return null
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

/** 界面选中的来源字段 → 行里存的来源路径（外层引用加 `$root.` 前缀）。 */
export function sourceValueFor(field: SourceField, outer: boolean): string {
  return outer ? `${ROOT_PREFIX}${field.name}` : field.name
}

/** 一行当前来源在界面上显示成什么（子行按相对路径显示）。 */
export function rowReferenceLabel(row: MapRow, actionNames: Record<number, string> = {}, isChild = false): string {
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
