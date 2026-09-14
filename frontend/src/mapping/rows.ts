/** 映射规则行模型：叶子行（sourceStep+source→target+transform）与数组组行（iterate=FOR_EACH + children 相对路径）。 */
export interface ConcatPart {
  type: 'const' | 'path'
  value: string
}

/** 行的来源：manual = 手工新增，未标记 = 由目标清单或已存配置生成。 */
export type RowOrigin = '' | 'manual' | 'auto'

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
  origin: RowOrigin
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
    origin: '',
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
    origin: '',
  }
}

/** 前端行 → 后端规则（递归；组内子规则不带 sourceStep）。 */
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
    let map = row.enumMap && Object.keys(row.enumMap).length ? row.enumMap : null
    if (!map && row.extra.trim()) {
      try {
        map = JSON.parse(row.extra.trim())
      } catch {
        throw new Error(`第 ${index + 1} 行映射：枚举映射必须是合法 JSON，如 {"1":"已处理"}`)
      }
    }
    if (map) rule.enumMap = map
  }
  if (row.transform === 'CONCAT' && row.parts.length) {
    rule.parts = row.parts
      .filter(p => p.value.trim() !== '')
      .map(p => (p.type === 'const' ? p.value : { path: p.value }))
  }
  if (row.transform === 'INDEX') {
    // 后端校验要求 INDEX 的 source 恰好是 $index，起始值另走 startAt。
    rule.source = '$index'
    rule.startAt = row.startAt == null ? 1 : row.startAt
  }
  if (row.iterate === 'FOR_EACH') {
    rule.iterate = 'FOR_EACH'
    rule.children = row.children.map((c, i) => ruleFromRow(c, i, true)).filter(Boolean)
  }
  return rule
}
