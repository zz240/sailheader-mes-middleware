/**
 * 拼接片段的片段转换（#112）：一个片段挂一个转换，深度锁死一层。
 *
 * 存储形状四种，`ConcatPart` 一一对应（老形状是加工片段的退化情形，读取兼容、不做数据迁移）：
 *
 * | 存储 | 行模型 | 编译成 |
 * |---|---|---|
 * | `"BOM-"` | `{type:'const', value:'BOM-'}` | 字符串字面量 |
 * | `{"path": "a.b"}` | `{type:'path', value:'a.b'}` | 来源表达式 |
 * | `{"path": "a.b", "transform": "DATE", "datePattern": "yyyyMMdd"}` | `+ transform/datePattern` | `$dateFormat(...)` |
 * | `{"transform": "INDEX", "startAt": 1}` | `{type:'path', value:'', transform:'INDEX', startAt:1}` | `($i0 + 1)`（编译时自动加括号） |
 *
 * 允许的**片段转换**五种（直接取值 / 数字转换 / 日期格式化 / 枚举映射 / 行号）加「常量」，界面上就是
 * 六选一。不给**拼接**（拼接是结合的，摊平即可）与**数组迭代**（会改变片段数量）——一个片段只能挂一个
 * 转换，所以这里没有嵌套可言。
 *
 * 这里只放**纯逻辑**，不碰 DOM 也不发请求，所以可以用 `node --experimental-strip-types` 直接检查
 * （见 `checks.ts`）。`$` 一个都不出现：括号那段只写在编译产物里（#98 对照表）。
 */
import { transformLabel, type TransformKind } from './labels.ts'
import { realEnumMap, type ConcatPart, type MapRow } from './rows.ts'
import { DEFAULT_DATE_PATTERN, normalizeDatePattern, type TransformParams } from './transforms.ts'

/** 片段能挂的转换，界面上按这个顺序排：「常量」在前，其余与行级那七种同序。 */
export const PART_KINDS = ['CONSTANT', 'STRING', 'INT', 'DATE', 'ENUM', 'INDEX'] as const
export type PartKind = (typeof PART_KINDS)[number]

/** 片段转换的人话：与行级那张对照表同一份，只有「直接取值」不一样（行级叫「直接映射」）。 */
export function partKindLabel(kind: string): string {
  return kind === 'STRING' ? '直接取值' : transformLabel(kind)
}

/** 这个片段现在是六种里的哪一种（没写 `transform` 的取值片段 = 直接取值，老形状的退化情形）。 */
export function partKindOf(part: ConcatPart): PartKind {
  if (part.type === 'const') return 'CONSTANT'
  const kind = (part.transform ?? 'STRING') as PartKind
  return PART_KINDS.includes(kind) ? kind : 'STRING'
}

/**
 * 这个片段要怎么存（#112 的存储形状）。
 *
 * 它不管「这个片段填够了没有」——那是 `partFilled` 的事，`ruleFromRow` 先筛再调这里
 * （与行级 `ruleFromRow` 和 `isRowConfigured` 的分工一样）。
 */
export function partRuleOf(part: ConcatPart): string | Record<string, any> {
  const kind = partKindOf(part)
  if (kind === 'CONSTANT') return part.value
  if (kind === 'INDEX') {
    // 行号片段的源就是行号本身，**不带 path**（后端按这个判它是不是行号片段）
    return { transform: 'INDEX', startAt: part.startAt == null ? 1 : part.startAt }
  }
  const rule: Record<string, any> = { path: part.value }
  if (kind === 'DATE') rule.datePattern = normalizeDatePattern(part.datePattern ?? '')
  if (kind === 'ENUM') {
    // 还没填键的占位格不进规则（与行级同一条）：整张表都没填时干脆不写这个键，
    // 「枚举转换需要 enumMap」留给后端那条校验去报（前端不复制校验规则，#98 D1）
    const map = realEnumMap(part.enumMap)
    if (Object.keys(map).length) rule.enumMap = map
  }
  if (kind !== 'STRING') rule.transform = kind
  return rule
}

/** 存储里的一个片段 → 行模型（递归用不上：片段里不能再放片段）。 */
export function partFromRule(raw: unknown): ConcatPart {
  if (typeof raw === 'string') return { type: 'const', value: raw }
  const map = (raw ?? {}) as Record<string, any>
  const transform = map.transform == null ? undefined : String(map.transform)
  const part: ConcatPart = {
    type: 'path',
    // 行号片段没有 path：值那一格留空（它取的是行号本身）
    value: transform === 'INDEX' ? '' : String(map.path ?? ''),
  }
  if (transform) part.transform = transform
  if (map.datePattern != null) part.datePattern = String(map.datePattern)
  if (map.enumMap != null) part.enumMap = map.enumMap
  if (map.startAt != null) part.startAt = Number(map.startAt)
  return part
}

/**
 * 换片段转换：参数跟着换，不该留的旧参数清掉（否则存进去的是自相矛盾的片段）。
 *
 * 与行级的 `applyTransform` 同一条口径——切回同一种转换时把配过的参数留着是行级那边的事，
 * 这里是「一个片段只有一个转换」，所以换过去就是换过去了。
 */
export function applyPartKind(part: ConcatPart, kind: PartKind, params: TransformParams = {}): ConcatPart {
  const keptPath = part.type === 'path' ? part.value : ''
  const keptDate = part.datePattern
  const keptEnum = part.enumMap
  const keptStartAt = part.startAt
  part.transform = undefined
  part.datePattern = undefined
  part.enumMap = undefined
  part.startAt = undefined
  switch (kind) {
    case 'CONSTANT':
      part.type = 'const'
      part.value = ''
      break
    case 'INDEX':
      // 行号片段的源就是行号本身：不留路径；起始值默认从 1 起
      part.type = 'path'
      part.value = ''
      part.transform = 'INDEX'
      part.startAt = params.startAt ?? (keptStartAt ?? 1)
      break
    case 'DATE':
      part.type = 'path'
      part.value = keptPath
      part.transform = 'DATE'
      part.datePattern = normalizeDatePattern(params.datePattern ?? keptDate ?? DEFAULT_DATE_PATTERN)
      break
    case 'ENUM':
      part.type = 'path'
      part.value = keptPath
      part.transform = 'ENUM'
      part.enumMap = params.enumMap ?? keptEnum ?? {}
      break
    default:
      part.type = 'path'
      part.value = keptPath
      if (kind !== 'STRING') part.transform = kind
      break
  }
  return part
}

/** 这个片段转换在这个位置上能不能选。 */
export function canUsePartKind(part: ConcatPart, kind: string, depth = 0): boolean {
  return partDisabledReason(part, kind, depth) === ''
}

/** 禁用一个片段转换的原因；能选时是空串。与后端校验同一条口径。 */
export function partDisabledReason(part: ConcatPart, kind: string, depth = 0): string {
  if (kind === 'INDEX' && depth === 0) {
    return '行号片段只能在数组映射的子行里用（与后端校验一致）'
  }
  return ''
}

/**
 * 这个片段填够了没有——够了的才写成规则（`ruleFromRow` 只收填够的）。
 *
 * 判据是「按现在这样写成规则，后端会不会收」：常量要有值、行号不需要来源、其余要有路径。
 * 与 `isRowConfigured` 一样，这里不复制后端的校验规则（#98 D1）。
 */
export function partFilled(part: ConcatPart): boolean {
  const kind = partKindOf(part)
  if (kind === 'INDEX') return true
  return part.value.trim() !== ''
}

/** 这个片段是不是「空的」（界面上报「空片段」那条警告用；行号片段有内容，只是没有路径）。 */
export function partEmpty(part: ConcatPart): boolean {
  return partKindOf(part) !== 'INDEX' && part.value.trim() === ''
}

/**
 * 这个片段还在等一个来源路径（「自动匹配同名」往这种片段里填、算「还没填的片段」）。
 *
 * 行号片段不等路径（它的源就是行号本身），常量片段也不等（它的值是写死的）——#112 之前这两件事
 * 都不存在，所以那时候「`type === 'path'` 且值是空串」就等于这一条。
 */
export function partNeedsPath(part: ConcatPart): boolean {
  const kind = partKindOf(part)
  return kind !== 'INDEX' && kind !== 'CONSTANT' && part.value.trim() === ''
}

/**
 * 片段拼成人话要用的那个「像行一样」的对象：`transformLabelOf` 认的是行上那几个字段。
 *
 * 行上「值那一格」是按转换解释的（常量与格式串都在 `extra` 里、取值在 `source` 里），片段也一样——
 * 这样片段与行级转换共用同一份人话（「日期格式化 · yyyyMMdd（动作 1 · 读 BOM · createdTime）」），
 * 不用为片段再写一套说法。行号片段的来源由 `transformLabelOf` 自己说成「行号 · 从 N 起」。
 */
export function partAsRow(part: ConcatPart, row: MapRow): MapRow {
  const kind = partKindOf(part)
  return {
    ...row,
    transform: kind,
    source: kind === 'CONSTANT' ? '' : part.value,
    extra: kind === 'CONSTANT' ? part.value : (part.datePattern ?? ''),
    enumMap: part.enumMap ?? null,
    startAt: part.startAt ?? null,
  }
}

/** 片段转换的类型（行级那个 `TransformKind` 去掉拼接，就是这六个）。 */
export type PartTransformKind = Extract<TransformKind, PartKind>
