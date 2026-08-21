/** 映射规则行模型：叶子行（sourceStep+source→target+transform）与数组组行（iterate=FOR_EACH + children 相对路径）。 */
export interface ConcatPart {
  type: 'const' | 'path'
  value: string
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
  const extra =
    rule.constant != null ? String(rule.constant)
    : rule.datePattern != null ? String(rule.datePattern)
    : startAt != null ? String(startAt)
    : ''
  return {
    id: nextId(),
    sourceStep: rule.sourceStep != null ? Number(rule.sourceStep) : null,
    source: rule.source ?? '',
    target: rule.target ?? '',
    transform: rule.transform ?? 'STRING',
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

/** 前端行 → 后端规则（递归；组内子规则不带 sourceStep）。 */
export function ruleFromRow(row: MapRow, index: number): Record<string, any> | null {
  if (!row.target.trim()) return null
  const rule: Record<string, any> = {
    sourceStep: row.sourceStep ?? undefined,
    source: row.source.trim(),
    target: row.target.trim(),
    transform: row.transform,
  }
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
    rule.source = '$index'
    if (row.startAt != null && row.startAt !== 1) rule.startAt = row.startAt
  }
  if (row.iterate === 'FOR_EACH') {
    rule.iterate = 'FOR_EACH'
    rule.children = row.children.map((c, i) => ruleFromRow(c, i)).filter(Boolean)
  }
  return rule
}
