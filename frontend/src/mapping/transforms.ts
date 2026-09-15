/**
 * 七种转换的参数与人话（#100）：在行里就地配置转换，不开弹窗。
 *
 * 一个目标字段的值 = 一棵只有 7 种节点的表达式树（#98「一行的值怎么取」），正好对应后端
 * `TransformType` 的 7 个值。这里只放**纯逻辑**，不碰 DOM 也不发请求，所以可以用
 * `node --experimental-strip-types` 直接检查（见 `checks.ts`）。
 *
 * 界面第一语言是中文：这里出的每一句话里都不出现 `$`，`$` 只出现在编译产物里（#98 对照表）。
 * 「对不对」仍然只在后端那个只读接口里判断，这里只管「参数编辑得对不对路」（#98 D1/D4）。
 */
import { INDEX_SOURCE, TRANSFORM_KINDS, transformLabel, type TransformKind } from './labels.ts'
import { markHumanEdited, realEnumMap, type ConcatPart, type MapRow } from './rows.ts'
// 片段转换那几条判断（#112）在 parts.ts：这是**函数级**的相互引用（两个模块都只在函数体里用对方），
// 用来保证「片段/行上值那一格怎么读」只有一处定义。
import { partFilled, partLabelSpec } from './parts.ts'
import type { SourceField } from './editor-model.ts'

/** 日期格式化的预设（#100：从几个预设里选，不用记格式串）。 */
export const DATE_PRESETS = [
  'yyyy-MM-dd',
  'yyyy-MM-dd HH:mm:ss',
  'yyyyMMdd',
  'yyyy/MM/dd',
] as const

/** 没选格式串时按后端编译器的默认值走（`JsonataRuleCompiler.DEFAULT_DATE_PATTERN`）。 */
export const DEFAULT_DATE_PATTERN = 'yyyy-MM-dd'

/** 界面上的「来源字段」占位说法；由组件注入（它才知道动作名、数组内外、外层引用）。 */
export type OperandLabel = (address: string) => string

/** 转换下拉里的一个选项。禁用时带上原因，界面上直接显示。 */
export interface TransformOption {
  value: TransformKind
  label: string
  disabled: boolean
  /** 禁用原因；可用时是空串。 */
  reason: string
}

/** 一次转换编辑要用到的参数，全都有默认值。 */
export interface TransformParams {
  datePattern?: string
  enumMap?: Record<string, any>
  constant?: string
  parts?: ConcatPart[]
  startAt?: number | null
}

/** 转换下拉的选项：七种全都在，不可用的置灰并带上原因（#100：不是藏起来）。 */
export function transformOptions(row: MapRow, depth = 0): TransformOption[] {
  // 「填了的片段」也算上挂了转换的那些（行号片段的值那一格是空的，但它有内容，#112）
  const pairs = row.parts.filter(partFilled)
  return TRANSFORM_KINDS.map(kind => {
    const disabledByDepth = kind === 'INDEX' && depth === 0
    return {
      value: kind,
      label: transformName(kind),
      disabled: disabledByDepth,
      // 禁用时显示为什么不能选；可用时显示这一项要填什么（票里「参数就地编辑」，得让人知道有参数）。
      reason: disabledByDepth ? '行号只能在数组映射的子行里用（与后端校验一致）' : paramHint(kind, row, pairs),
    }
  })
}

/** 禁用一个转换的原因：后端校验怎么说，界面就怎么说。 */
export function transformDisabledReason(row: MapRow, kind: string, depth = 0): string {
  return transformOptions(row, depth).find(o => o.value === kind)?.disabled
    ? '行号只能在数组映射的子行里用（与后端校验一致）'
    : ''
}

/** 这个转换在界面上能不能选。 */
export function canUseTransform(row: MapRow, kind: string, depth = 0): boolean {
  return transformDisabledReason(row, kind, depth) === ''
}

/** 转换类型的人话名（与 `labels.ts` 那张对照表同一份，只有一处定义）。 */
export function transformName(kind: string): string {
  return transformLabel(kind)
}

/** 选中这个转换要在界面上填什么（列出还缺的参数，不缺就说清它做什么）。 */
function paramHint(kind: string, row: MapRow, filledParts: ConcatPart[]): string {
  switch (kind) {
    case 'DATE':
      return `按格式输出日期 · 当前 ${datePatternFor(row)}`
    case 'ENUM':
      return Object.keys(realEnumMap(row.enumMap)).length
        ? `键值表 · 当前 ${Object.keys(realEnumMap(row.enumMap)).length} 条`
        : '键值表 · 还没填，选中后按样例补全'
    case 'CONSTANT':
      return row.extra.trim() ? '写死一个值 · 已填' : '写死一个值 · 选中后当场输入'
    case 'CONCAT':
      return filledParts.length ? `按顺序拼接 · 当前 ${filledParts.length} 个片段` : '按顺序拼接 · 至少要有 1 个片段'
    default:
      return TRANSFORM_HINTS[kind] ?? ''
  }
}

/**
 * 转换下拉里每一项的一句说明（没有参数要填的那几个，与面板上「换转换」那一排说的是同一份）。
 *
 * 只有一处定义：`paramHint` 的默认分支与行列表那份 `transformHint` 读的都是它——两处各写一遍的话，
 * 改了一处的措辞、界面上另一处还是老的。
 */
export const TRANSFORM_HINTS: Record<string, string> = {
  STRING: '原值直接搬过去',
  INT: '转成数字，非数字会失败',
  DATE: '按模式输出日期字符串',
  ENUM: '来源值 → 目标值的查表替换',
  CONSTANT: '不取来源，写死一个值',
  CONCAT: '常量与来源字段按顺序拼接',
  INDEX: '数组映射内每个元素的行号',
}

/**
 * 换转换：参数跟着换，切回同一种转换时把配过的参数留着。
 *
 * 这里只管行模型，产出什么规则由 `ruleFromRow` 负责——所以残留的参数不会漏进规则里
 * （`ruleFromRow` 只按当前的 `transform` 取它自己那份参数）。留着是有用的：日期格式串
 * 在 `extra` 里、枚举表在 `enumMap` 里，切走再切回来不该让人重配一遍。
 *
 * 两处刻意改来源字段：
 * - 点「行号」→ 来源固定成 `$index`（后端要求恰好等于它），起始值默认从 1 起；
 * - 从「行号」换回普通转换 → 把 `$index` 清掉，它不是来源字段，留着会编译成「字段 $index」。
 *   常量也不取来源，顺手清掉，免得上一行点的字段留在这行让人以为配好了。
 */
export function applyTransform(row: MapRow, kind: string, params: TransformParams = {}): MapRow {
  // 换来源之外还要看上一个转换是什么：只有「还是同一种转换」时才沿用旧参数。
  const previous = row.transform
  // extra 是按当前转换解释的：DATE 时它是格式串，CONSTANT 时它是那个写死的值。换之前先认一下。
  const keptExtra = row.extra
  row.transform = kind
  row.startAt = null
  // 用户挑过转换了：这一行不再是「自动匹配填上的那一行」（#105 的提示只提醒没碰过的那种）。
  markHumanEdited(row)

  switch (kind) {
    case 'DATE':
      // 显式选的预设或手输的格式串优先，其次沿用这行配过的；都没有（或刚被清空）就用默认格式。
      // 空串在这里就归一到默认值，不留到编译器那边当「写了一个空格式串」（#111 验收五）。
      row.extra = normalizeDatePattern(
        params.datePattern ?? (previous === 'DATE' ? keptExtra : DEFAULT_DATE_PATTERN))
      break
    case 'ENUM':
      row.enumMap = params.enumMap ?? (previous === 'ENUM' ? row.enumMap ?? {} : {})
      break
    case 'CONSTANT':
      row.extra = params.constant ?? (previous === 'CONSTANT' ? keptExtra : '')
      row.enumMap = null
      row.source = ''
      break
    case 'CONCAT':
      row.parts = params.parts ?? (previous === 'CONCAT' ? row.parts : [])
      row.enumMap = null
      break
    case 'INDEX':
      // 行号行的来源固定是 $index（后端要求恰好等于它）；起始值默认从 1 起。
      row.source = INDEX_SOURCE
      row.enumMap = null
      row.startAt = params.startAt ?? (previous === 'INDEX' ? row.startAt : null) ?? 1
      break
    default:
      // 直接映射与数字转换都没有参数。来源字段留着给用户重新点一次；
      // 但从「行号」走回头路时要把 $index 清掉——它不是来源字段，留着会编译成「字段 $index」。
      if (row.source.trim() === INDEX_SOURCE) row.source = ''
      row.enumMap = null
      break
  }
  return row
}

/**
 * 手输 / 选中的格式串归一：清空（或只有空白）时回落到默认格式（#111 验收五）。
 *
 * 回落而不是送一个空串，是因为空串会一路进规则（`datePattern: ''`）——后端读到的就成了「写了一个空
 * 格式串」而不是「没写」。这个值与后端 `DatePatterns.DEFAULT`（编译器与运行时都引用它）是同一个。
 */
export function normalizeDatePattern(value: string): string {
  return value.trim() || DEFAULT_DATE_PATTERN
}

/**
 * 日期转换当前生效的格式串：没写就按编译器的默认值显示
 * （`JsonataRuleCompiler` 在 `datePattern` 为空时用 `yyyy-MM-dd`）。
 *
 * 显示与存进规则用的是同一个值——界面上写着什么格式，目标系统收到的就是什么格式。
 */
export function datePatternFor(row: MapRow): string {
  return normalizeDatePattern(row.extra)
}

// ---------------------------------------------------------------- 枚举映射的样例补全

/**
 * 一个来源字段的样例值（去重、按出现顺序）。
 * 结构探测给的是 `SourceField.samples`，形状不定：标量、`{ value: ... }`、`{ key: ..., value: ... }`。
 */
export function distinctSampleValues(field?: SourceField | null): string[] {
  const out: string[] = []
  const seen = new Set<string>()
  for (const sample of field?.samples ?? []) {
    const text = sampleText(sample)
    if (text === '' || seen.has(text)) continue
    seen.add(text)
    out.push(text)
  }
  return out
}

/** 样例值 → 界面上显示的文本（也是枚举表的键）。 */
export function sampleText(sample: unknown): string {
  if (sample == null) return ''
  if (typeof sample === 'object') {
    const value = (sample as Record<string, unknown>).value
    if (value != null) return String(value)
    return JSON.stringify(sample)
  }
  return String(sample)
}

/**
 * 枚举表里还缺哪些键：样例里出现过、表里没有的值。
 * 后端查不到键时该字段会静默从目标报文里整个消失（不报错），所以这些值最好都在表里（#98 问题 2）。
 */
export function missingEnumKeys(map: Record<string, any> | null | undefined, field?: SourceField | null): string[] {
  const known = map ?? {}
  return distinctSampleValues(field).filter(v => !(v in known))
}

/**
 * 改枚举表某一行的键：把这一项的键改成 `nextKey`，返回新的一览。
 *
 * 枚举表就是一张 map，键唯一；用户把某一行的键改成表里已有的键时，两行会指向同一个键。
 * 这里按「后改的赢」合并——被撞上的那一行直接消失，让「改键 → 表里少一行」当场看得见，
 * 而不是等到写进规则才发现有一格的值被覆盖了。
 *
 * 键统一去掉首尾空格：存进规则时也要 trim，两边不一致会让「改了键却像没改」。
 */
export function setEnumKey(pairs: EnumPair[], index: number, nextKey: string): EnumPair[] {
  if (!pairs[index]) return pairs
  const key = nextKey.trim()
  const collision = key === '' ? -1 : pairs.findIndex((p, i) => i !== index && p.key.trim() === key)
  return pairs
    .map((pair, i) => (i === index ? { ...pair, key } : pair))
    .filter((_, i) => i !== collision)
}

/** 枚举表里的一行（表格编辑用；键为空表示还没填）。 */
export interface EnumPair {
  id: number
  key: string
  value: string
}
export function appendEnumSamples(
  map: Record<string, any> | null | undefined,
  missing: string[],
): Record<string, any> {
  const next: Record<string, any> = { ...(map ?? {}) }
  for (const key of missing) {
    if (!(key in next)) next[key] = key
  }
  return next
}

// ---------------------------------------------------------------- 人话

/**
 * 一行（或一个片段）的转换在界面上读起来要用的那几格。
 *
 * 单独抽出来是因为片段也要用**同一份**说法（#112）：片段不是行，但「值怎么取」这件事的形状是一样的
 * （转换 + 来源/值 + 参数）。`MapRow` 天然满足这个形状，`parts.ts` 的 `partLabelSpec` 把片段也折成它
 * ——两边共用下面那一个 switch，不各写一套人话。
 */
export interface TransformLabelSpec {
  transform: string
  source: string
  /** 按转换解释的那一格：日期是格式串、常量是那个写死的值。 */
  extra: string
  enumMap?: Record<string, any> | null
  startAt?: number | null
  parts?: ConcatPart[]
}

/**
 * 一行当前转换在界面上读起来的那句话。
 *
 * - 直接映射 → 来源字段本身
 * - 数字转换 / 日期格式化 / 枚举映射 → 转换名（带参数）套在来源字段外面
 * - 常量 → 常量「值」
 * - 拼接 → 拼接（片段 + 片段）
 * - 行号 → 行号 · 从 N 起
 *
 * `operand` 负责把行里的来源（含拼接片段的地址）翻成人话；这里不碰 `$` 和路径。
 */
export function transformLabelFor(spec: TransformLabelSpec, operand: OperandLabel): string {
  const row = spec
  switch (row.transform) {
    case 'CONSTANT': {
      const value = row.extra.trim()
      return `常量「${value === '' ? '还没填' : value}」`
    }
    case 'INDEX':
      return `行号 · 从 ${row.startAt == null ? 1 : row.startAt} 起`
    case 'CONCAT': {
      const parts = (row.parts ?? []).map((part, i) => {
        // 常量片段的值本身就是它显示出来的样子（不用走来源那一套）。
        if (part.type === 'const') return part.value.trim() === '' ? '（空片段）' : part.value
        // 挂了片段转换的那一段（#112）按它自己的转换念，与行级转换共用这一份人话
        if ((part.transform ?? 'STRING') !== 'STRING') {
          return transformLabelFor(partLabelSpec(part), operand)
        }
        return operand(String(i))
      })
      return `拼接（${parts.join(' + ')}）`
    }
    case 'ENUM': {
      const count = Object.keys(row.enumMap ?? {}).length
      return `枚举映射 · ${count ? `${count} 条` : '空表'}（${operand('source')}）`
    }
    case 'DATE':
      return `日期格式化 · ${normalizeDatePattern(row.extra)}（${operand('source')}）`
    case 'INT':
      return `数字转换（${operand('source')}）`
    case 'STRING':
      return operand('source')
    default:
      // 认不出来的转换值原样显示，不假装认识。
      return `${row.transform || '（没选转换）'}（${operand('source')}）`
  }
}

/** 行的人话：`MapRow` 天然就是 `TransformLabelSpec`（那几格名字一样）。 */
export function transformLabelOf(row: MapRow, operand: OperandLabel): string {
  return transformLabelFor(row, operand)
}
