/** 映射规则行模型：叶子行（sourceStep+source→target+transform）与数组组行（iterate=FOR_EACH + children 相对路径）。 */
export interface ConcatPart {
  type: 'const' | 'path'
  value: string
}

/**
 * 枚举表里「还没填键」的格子占位用的键前缀（NUL 开头，真来源值不可能长这样）。
 * 表格编辑期间要留住空行，而 enumMap 的键是唯一的——不占位的话空行会被挤掉，
 * 用户看到的就是「点了加一行又消失」。写规则前用 `realEnumMap` 把占位键滤掉。
 */
export const ENUM_PLACEHOLDER = '\u0000'

/** 去掉还没填键的占位格，只留真正配对上的键值对。 */
export function realEnumMap(map: Record<string, any> | null | undefined): Record<string, any> {
  const out: Record<string, any> = {}
  for (const [key, value] of Object.entries(map ?? {})) {
    if (!key.startsWith(ENUM_PLACEHOLDER)) out[key] = value
  }
  return out
}

export interface MapRow {
  id: number
  sourceStep: number | null
  source: string
  target: string
  transform: string
  extra: string
  startAt: number | null
  enumMap: Record<string, any> | null
  parts: ConcatPart[]
  iterate: string | null
  children: MapRow[]
}

let uid = 1000

export function nextId() {
  return ++uid
}

export function createRow(
  sourceStep: number | null,
  source = '',
  target = '',
  transform = 'STRING',
  extra = '',
  startAt: number | null = null,
): MapRow {
  return {
    id: nextId(),
    sourceStep,
    source,
    target,
    transform,
    extra,
    startAt,
    enumMap: null,
    parts: [],
    iterate: null,
    children: [],
  }
}

export function createGroupRow(sourceStep: number | null, source = '', target = ''): MapRow {
  const row = createRow(sourceStep, source, target, 'STRING')
  row.iterate = 'FOR_EACH'
  return row
}

/** 后端规则 → 前端行（递归；旧配置无 children 天然兼容）。 */
export function rowFromRule(rule: Record<string, any>): MapRow {
  const children = Array.isArray(rule.children) ? rule.children.map(rowFromRule) : []
  const startAt = rule.startAt != null ? Number(rule.startAt) : null
  const transform = rule.transform ?? 'STRING'
  const extra =
    rule.constant != null ? String(rule.constant)
    : rule.datePattern != null ? String(rule.datePattern)
    : startAt != null ? String(startAt)
    : ''
  return {
    id: nextId(),
    sourceStep: rule.sourceStep != null ? Number(rule.sourceStep) : null,
    // 后端校验要求 INDEX 的 source 恒为 $index，读进来就归一化，保证往返稳定。
    source: transform === 'INDEX' ? '$index' : rule.source ?? '',
    target: rule.target ?? '',
    transform,
    extra,
    startAt,
    enumMap: rule.enumMap ?? null,
    parts: Array.isArray(rule.parts) ? rule.parts.map((p: any) =>
      typeof p === 'string' ? { type: 'const' as const, value: p }
        : { type: 'path' as const, value: String(p.path ?? '') }) : [],
    iterate: rule.iterate ?? (children.length ? 'FOR_EACH' : null),
    children,
  }
}

/**
 * 前端行 → 后端规则（递归；组内子规则不带 sourceStep）。
 *
 * 调用前先过 `isRowConfigured`：没配好的行写成规则会被后端整份拒掉（`configFromRows` 就是那么做的）。
 * 这里只负责「照现在这样写」——不该有的参数不带（枚举表留空、拼接片段全空、常量没值），
 * 这些形状后端会拒，而那种「整份拒」是用户配对的行也存不下去的原因，所以由调用方挡在前面。
 */
export function ruleFromRow(row: MapRow, index: number, inGroup = false): Record<string, any> | null {
  if (!row.target.trim()) return null
  const rule: Record<string, any> = {
    // 顶层规则必须显式写 sourceStep：写 undefined 会被 JSON.stringify 丢掉，
    // 后端把「没有这个键」当合法（= 任务输入），于是漏写永远查不出来（#99 D8）。
    // 组内子规则反过来——它们继承组节点的来源步骤，带上 sourceStep 后端会拒。
    source: row.source.trim(),
    target: row.target.trim(),
    transform: row.transform,
  }
  if (!inGroup) rule.sourceStep = row.sourceStep ?? 0
  if (row.transform === 'CONSTANT') rule.constant = row.extra.trim()
  if (row.transform === 'DATE' && row.extra.trim()) rule.datePattern = row.extra.trim()
  if (row.transform === 'ENUM') {
    // 占位键（还没填键的格子）不进规则：后端要的是一张真表。
    const map = realEnumMap(row.enumMap)
    if (Object.keys(map).length) rule.enumMap = map
  }
  if (row.transform === 'CONCAT' && row.parts.length) {
    // 空片段不进规则：后端 FieldMappingConfig.validate 会拒掉没写 path 的路径片段，
    // 而界面上「空片段」是个正常状态（用户加了片段还没填）。全空时不写 parts，
    // 「拼接需要片段」这条错留给后端那个只读接口去报（#98 D1：前端不复制校验规则）。
    rule.parts = row.parts
      .filter(p => p.value.trim() !== '')
      .map(p => (p.type === 'const' ? p.value : { path: p.value }))
    if (!rule.parts.length) delete rule.parts
  }
  if (row.transform === 'INDEX') {
    // 后端校验要求 INDEX 的 source 恰好是 $index，起始值另走 startAt。
    rule.source = '$index'
    rule.startAt = row.startAt == null ? 1 : row.startAt
  }
  if (row.iterate === 'FOR_EACH') {
    rule.iterate = 'FOR_EACH'
    // 只收配好的子行：没选来源的子行写成规则会被后端整份拒掉。
    // 一个子行都没配好时 children 是空的——那种组节点会被后端当成普通叶子（一整个数组写成一个值），
    // 所以这种行根本不该走到这里（`isRowConfigured` 挡在前面）。
    rule.children = row.children
      .filter(isRowConfigured)
      .map((c, i) => ruleFromRow(c, i, true))
      .filter(Boolean)
  }
  return rule
}

/**
 * 这一行算不算「配好了」——只有配好的行才产出规则。
 *
 * 判据是「按现在这样写成规则，后端会不会收」（`FieldMappingConfig.validate`）。空行留在编辑器里
 * 给用户继续填，但**不能变成规则**：后端是整份拒的，一行没配好会让用户已经配对的行也存不下去
 * （真机实测踩到过：没选来源的空行 → 400「映射规则缺少来源字段 source」）。
 *
 * 数组映射行更严一层：子行里一条都没配好时整行都不发。空 children 的组节点在后端会被当成普通叶子，
 * 结果是把一整个数组写成一个值——这是静默错误，比报错更难发现。
 *
 * 这里不复制后端的任何一条校验规则：它只回答「这一行有没有东西可存」，对错仍然由后端那个只读接口判（#98 D1）。
 */
export function isRowConfigured(row: MapRow): boolean {
  if (!row.target.trim()) return false
  if (row.iterate === 'FOR_EACH') {
    if (!row.source.trim()) return false
    return row.children.some(isRowConfigured)
  }
  switch (row.transform) {
    case 'CONSTANT':
      // 后端：「常量转换需要 constant」。空值不算配好（票里也是「当场输入一个值」）。
      return row.extra.trim() !== ''
    case 'INDEX':
      return true
    case 'ENUM':
      return Object.keys(realEnumMap(row.enumMap)).length > 0
    case 'CONCAT':
      // 后端：「CONCAT 转换需要 parts 片段」——一个片段都没填就等于没有 parts。
      return row.parts.some(part => part.value.trim() !== '')
    default:
      return row.source.trim() !== ''
  }
}

/** 一行上「值怎么取」的那一份（#102）：来源 + 转换 + 它的参数。目标字段与子行不属于它。 */
export type RowMapping = Pick<MapRow, 'source' | 'transform' | 'extra' | 'startAt' | 'enumMap' | 'parts'>

/** 这一行配过「值怎么取」没有——没配过就不该给「清除」入口（清完也没有东西可以撤销）。 */
export function hasMapping(row: MapRow): boolean {
  if (row.source.trim() !== '') return true
  if (row.transform !== 'STRING') return true
  if (row.extra.trim() !== '') return true
  if (Object.keys(row.enumMap ?? {}).length) return true
  return row.parts.some(part => part.value.trim() !== '')
}

/**
 * 清除这一行的映射（#102 票里的「每一行能清除」），返回撤销用的快照；本来就没配过时返回 `null`。
 *
 * 清的是**取值方式**，不是这一行本身：目标字段留着（它是这一行的身份，来自目标清单或手输的路径），
 * 数组行的子行也留着（子行是这一行的内容）。所以数组行清除后剩下「一行没有来源数组的数组映射」，
 * 而不是把整棵子树删掉——删行是另一件事。
 */
export function clearRowMapping(row: MapRow): RowMapping | null {
  if (!hasMapping(row)) return null
  const snapshot = mappingOf(row)
  row.source = ''
  row.transform = 'STRING'
  row.extra = ''
  row.startAt = null
  row.enumMap = null
  row.parts = []
  return snapshot
}

/** 撤销一次清除：把快照放回去（目标字段与子行本来就没动过，所以只还这一份）。 */
export function restoreRowMapping(row: MapRow, snapshot: RowMapping): void {
  Object.assign(row, snapshot)
}

/** 把一行上「值怎么取」的那几个字段取出来（快照与清除共用同一份清单，两边不会走散）。 */
function mappingOf(row: MapRow): RowMapping {
  return {
    source: row.source,
    transform: row.transform,
    extra: row.extra,
    startAt: row.startAt,
    enumMap: row.enumMap,
    parts: row.parts,
  }
}
