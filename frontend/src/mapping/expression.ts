/**
 * 手写 JSONata 这条逃生通道（#106）：进哪一种模式、保存成什么、界面上要讲清哪两件事。
 *
 * 为什么要有这条路：层级重排（深层数组任意重排）表单表达不了，而**每行只放一个转换**是这次改版的
 * 形态（#98 B1/F1）。界面不给入口，等于悄悄砍掉一条已经承诺的出路（票里那句话）。
 *
 * 与后端的约定一个字都不改（#98 C1）：存进数据库的还是 `{mode? / expression? / rules[]}`，
 * 手写这条路写 `mode: 'EXPRESSION'` + 非空 `expression`，规则树不参与——`FieldMappingConfig.problems()`
 * 在 EXPRESSION 下直接返回（只看表达式非不非空），所以这条路**跳过规则树那一整套校验**。
 * 这句话必须写在界面上（票里验收），所以它在这里只有一份来源。
 *
 * 纯逻辑，不碰 DOM 也不发请求，所以可以用 `node --experimental-strip-types` 直接检查（见 `checks.ts`）。
 */

/** 编辑器当前在哪一边：逐行配置，还是手写 JSONata。 */
export type EditorMode = 'rows' | 'expression'

/**
 * 已存配置 → 打开编辑器时该进哪一边。
 *
 * 口径与后端一致：**不是 EXPRESSION 就当 RULES**（`FieldMappingConfig`；文档里那个 `STRUCTURED`
 * 是旧说法，`docs/features/field-mapping/README.md` 已注明以代码为准）。表达式空着的 EXPRESSION
 * 也进手写这一边——那正是要人去补的情况，进逐行配置会把这条已经存在的配置悄悄丢掉。
 */
export function editorModeOf(config: Record<string, any> | null | undefined): EditorMode {
  return config?.mode === 'EXPRESSION' ? 'expression' : 'rows'
}

/** 已存配置里的手写表达式（回填文本框用；没有就是空串）。 */
export function expressionOf(config: Record<string, any> | null | undefined): string {
  const expression = config?.expression
  return typeof expression === 'string' ? expression : ''
}

/**
 * 手写表达式要保存 / 送校验的那份配置。
 *
 * `rules: []` 不能省：`FieldMappingConfig.toMap()` 恒定带这个键，旧编辑器存的也是这份形状，
 * 回填时（`rulesFromConfig` 读到空数组）不会多出什么。表达式**原样**保存——它是要执行的文本，
 * 动它的空白就是在动它的内容。
 */
export function expressionConfig(expression: string): { mode: 'EXPRESSION'; expression: string; rules: never[] } {
  return { mode: 'EXPRESSION', expression, rules: [] }
}

/**
 * 「这一段写了没有」——编辑器里的本地事实，不是替后端判对错（#98 D4）。
 *
 * 与逐行那边的「这一行填了目标字段没有」同一类：空表达式根本存不出去（后端要的是非空 expression），
 * 所以保存前先把话说清楚，而不是让后端回一条没人看得懂的错。对错仍然全在后端那个只读接口里。
 */
export function hasExpression(text: string): boolean {
  return text.trim() !== ''
}

/** 切到手写这条路时要说清的事（票里验收：界面上说明了这条路会跳过规则树校验）。 */
export function expressionSkipNotice(): string {
  return '这条路跳过规则树校验：判断只看这段表达式能不能跑（与后端 mode=EXPRESSION 一致），'
    + '逐行配置那些检查（必填、样例值、枚举覆盖）都不跑。深层的数组重排走这里。'
}

/** 从手写切回逐行配置时要说清的事（票里验收：说清了规则树会被替换）。 */
export function expressionReplacedNotice(): string {
  return '保存时会用逐行规则树替换掉这份手写表达式（表达式不再保存）。'
    + '切回去看到的是逐行编辑器里现在这些行——原来没有规则树时，那些行按目标字段清单生成。'
}
