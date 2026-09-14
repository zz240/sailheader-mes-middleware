/**
 * 人话标签（#98 对照表）：界面第一语言是中文，`$` 只出现在编译产物里。
 *
 * | 内部符号 | 界面显示 |
 * |---|---|
 * | `$s_N`（N ≥ 1） | 动作 N · 动作名 |
 * | `$s_0` / 无来源步骤 | 任务输入 |
 * | `$root.x` | 外层 · 字段名 |
 * | 数组内相对路径 | 字段名（分组标题写「数组元素内」） |
 * | `$index` 加起始值 | 行号 · 从 N 起 |
 *
 * 坑：「外层」不能叫「上一层」或「父级」——编译器里它始终指这份来源数据的顶层对象，
 * 直接引用父级数组元素在现在的编译规则下做不到（`docs/flows/transform-flow.md` P9）。
 */

/** 引用一条来源字段（或行号）在界面上显示成什么。 */
export interface FieldReference {
  /** 动作序号；null / 0 都表示任务输入。 */
  step: number | null
  /** 字段路径；`$index` 表示行号。 */
  path: string
  /** 相对当前数组元素解析（数组映射的子行）。 */
  relative?: boolean
  /** 取这份来源数据的顶层对象，而不是当前数组元素（`$root.x`）。 */
  root?: boolean
  /** 行号起始值。 */
  startAt?: number | null
}

/** 步骤标题：`$s_0` → 任务输入，`$s_N` → 动作 N · 动作名。 */
export function stepLabel(step: number | null | undefined, actionName?: string | null): string {
  const n = step == null ? 0 : Number(step)
  if (n <= 0) return '任务输入'
  return actionName ? `动作 ${n} · ${actionName}` : `动作 ${n}`
}

/** 一条来源引用的人话标签。`actionNames` 是 动作序号 → 动作名 的映射。 */
export function fieldReferenceLabel(ref: FieldReference, actionNames: Record<number, string> = {}): string {
  if (ref.path === '$index') {
    const startAt = ref.startAt == null ? 1 : Number(ref.startAt)
    return `行号 · 从 ${startAt} 起`
  }
  if (ref.relative) return ref.path
  const step = ref.step == null ? 0 : Number(ref.step)
  if (ref.root) return `外层 · ${ref.path}`
  return `${stepLabel(step, actionNames[step])} · ${ref.path}`
}

/**
 * 带上「这条外层引用取自哪个动作」的完整说法，用于悬停提示。
 * 顶层不用带——它和这个编辑器当前绑定的默认来源是同一个动作。
 */
export function fieldReferenceFullLabel(ref: FieldReference, actionNames: Record<number, string> = {}): string {
  const base = fieldReferenceLabel(ref, actionNames)
  if (!ref.root || ref.path === '$index') return base
  const step = ref.step == null ? 0 : Number(ref.step)
  return `${stepLabel(step, actionNames[step])} · ${base}`
}

/** 还没选来源的行显示什么。 */
export function emptyRowLabel(): string {
  return '还没选来源'
}

/** 转换类型的人话标签（参数另有一列显示）。 */
export function transformLabel(transform: string): string {
  const table: Record<string, string> = {
    STRING: '直接映射',
    INT: '数字转换',
    DATE: '日期格式化',
    ENUM: '枚举映射',
    CONSTANT: '常量',
    CONCAT: '拼接',
    INDEX: '行号',
  }
  return table[transform] ?? transform
}

/** 数组映射那一行的分组标题。 */
export function arrayGroupLabel(): string {
  return '数组元素内'
}

/** 用来判断一条规则来源是不是行号（内部符号只在这里出现）。 */
export const INDEX_SOURCE = '$index'
/** 外层引用的内部前缀（内部符号只在这里出现）。 */
export const ROOT_PREFIX = '$root.'
