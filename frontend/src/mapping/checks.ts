/**
 * 新版映射编辑器的前端检查（#99 验收项）：不测组件，只测纯逻辑的外部行为。
 *
 * 跑法（零新依赖）：
 *   node --experimental-strip-types frontend/src/mapping/checks.ts
 *
 * 断言三件事：
 *   1. 行与规则互转往返不丢字段；
 *   2. 每条顶层规则显式写了 `sourceStep`（后端查不出这个错，只能前端保住）；
 *   3. 人话标签符合对照表（界面上不出现 `$`）。
 */
import { createGroupRow, createRow, ENUM_PLACEHOLDER, isRowConfigured, rowFromRule, ruleFromRow, type MapRow } from './rows.ts'
import {
  configFromRows, fillRowFromSource, mergeRowsWithTargets, rowReferenceFullLabel, rowReferenceLabel,
  rowsFromTargetFields, targetFieldsFromPaths, type SourceField, type TargetField,
} from './editor-model.ts'
import {
  fieldReferenceLabel, fieldReferenceFullLabel, stepLabel, emptyRowLabel, transformLabel, TRANSFORM_KINDS,
  type TransformKind,
} from './labels.ts'
import {
  applyTransform, appendEnumSamples, canUseTransform, datePatternFor, DEFAULT_DATE_PATTERN, distinctSampleValues,
  missingEnumKeys, setEnumKey, transformDisabledReason, transformLabelOf, transformName, transformOptions,
} from './transforms.ts'

// 这个文件由 node 直接执行（不在浏览器里），只需要 process.exitCode 一处。
declare const process: { exitCode?: number }

let failed = 0
let passed = 0

function ok(name: string, cond: boolean, detail = '') {
  if (cond) {
    passed++
    console.log(`ok - ${name}`)
  } else {
    failed++
    console.log(`not ok - ${name}${detail ? ` :: ${detail}` : ''}`)
  }
}

function eq(name: string, actual: unknown, expected: unknown) {
  const a = stableJson(actual)
  const e = stableJson(expected)
  ok(name, a === e, `expected ${e} got ${a}`)
}

/** 键序无关的 JSON 比较：规则的字段顺序不是契约，值才是。 */
function stableJson(value: unknown): string {
  return JSON.stringify(sortKeys(value))
}

function sortKeys(value: any): any {
  if (Array.isArray(value)) return value.map(sortKeys)
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().map(k => [k, sortKeys(value[k])]))
  }
  return value
}

// ---------------------------------------------------------------- 1. 往返不丢字段

/** 转出来的规则一定存在（用例里的行都有目标字段），这里替断言收掉 null。 */
function ruleOf(row: MapRow, inGroup = false): Record<string, any> {
  const rule = ruleFromRow(row, 0, inGroup)
  if (!rule) throw new Error(`这一行没转出规则：${JSON.stringify(row)}`)
  return rule
}

const TOP_RULES: Record<string, any>[] = [
  { sourceStep: 1, source: 'bomNo', target: 'bom.bomNo', transform: 'STRING' },
  { sourceStep: 0, source: 'productCode', target: 'bom.productCode', transform: 'STRING' },
  {
    sourceStep: 1,
    source: 'items',
    target: 'bom.bomLines',
    transform: 'STRING',
    iterate: 'FOR_EACH',
    children: [
      { source: '$index', target: 'lineNo', transform: 'INDEX', startAt: 1 },
      { source: 'materialCode', target: 'materialCode', transform: 'STRING' },
      { source: 'quantity', target: 'qty', transform: 'INT' },
      { source: 'unit', target: 'unit', transform: 'ENUM', enumMap: { PCS: '件', SET: '套' } },
      { source: 'materialName', target: 'materialName', transform: 'CONCAT', parts: ['BOM-', { path: 'materialCode' }] },
    ],
  },
  { sourceStep: 1, source: 'vendor', target: 'bom.vendorName', transform: 'CONSTANT', constant: '固定值' },
  { sourceStep: 1, source: 'createdAt', target: 'bom.createdDate', transform: 'DATE', datePattern: 'yyyy-MM-dd' },
]

eq('规则 → 行 → 规则 顶层往返一致', TOP_RULES.map(r => ruleOf(rowFromRule(r))), TOP_RULES)

const group = rowFromRule(TOP_RULES[2])
eq('组行识别为数组映射', [group.iterate, group.children.length], ['FOR_EACH', 5])
eq('子行不带 sourceStep（后端校验要求）', ruleOf(group.children[0], true).sourceStep, undefined)
eq('行号行固定用 $index 取值', ruleOf(group.children[0], true).source, '$index')

// 空行不进规则（没有目标字段的行是"还没配"，不是一条规则）
const blank = rowFromRule({ sourceStep: 1, source: 'x', target: 'keep', transform: 'STRING' })
blank.children.push({ ...blank, id: 9999, target: '   ', children: [] })
eq('目标字段为空的行不进规则', ruleOf(blank).target, 'keep')

// 没选来源的空行也不能进规则：后端「映射规则缺少来源字段 source」会整份拒掉
const halfConfigured = rowFromRule({ sourceStep: 1, source: 'order_no', target: 'order_no', transform: 'STRING' })
const untouched = createRow(1, '', 'qty', 'STRING')
eq('还没选来源的行不算配好', isRowConfigured(untouched), false)
eq('选了来源的行算配好', isRowConfigured(halfConfigured), true)
eq('没有目标字段的行永远不算配好', isRowConfigured(createRow(1, 'x', '  ', 'STRING')), false)

// 「配好」的口径是「按现在这样写成规则，后端会不会收」——下面这几种后端都会拒，
// 所以不算配好（不算配好的行不进规则，用户配对的那些行才存得下去）
const blankConstant = createRow(1, '', 'kind', 'CONSTANT')
eq('常量没填值不算配好（后端：常量转换需要 constant）', isRowConfigured(blankConstant), false)
eq('常量填了值才算配好',
  isRowConfigured({ ...blankConstant, extra: '固定值' }), true)
eq('行号行不需要来源就算配好', isRowConfigured(createRow(null, '$index', 'lineNo', 'INDEX')), true)
eq('枚举表是空的（只有还没填键的格子）不算配好',
  isRowConfigured(createRow(1, 'unit', 'unit', 'ENUM')), false)
eq('枚举表里真有一条才算配好',
  isRowConfigured({ ...createRow(1, 'unit', 'unit', 'ENUM'), enumMap: { PCS: '件' } }), true)
eq('枚举表里只有占位键不算配好',
  isRowConfigured({ ...createRow(1, 'unit', 'unit', 'ENUM'), enumMap: { [ENUM_PLACEHOLDER + '0']: '' } }), false)
eq('拼接一个片段都没填不算配好（后端：CONCAT 转换需要 parts 片段）',
  isRowConfigured({ ...createRow(1, 'materialCode', 'bomNo', 'CONCAT'), parts: [{ type: 'path', value: '' }] }), false)
eq('拼接有片段就算配好',
  isRowConfigured({
    ...createRow(1, 'materialCode', 'bomNo', 'CONCAT'), parts: [{ type: 'const', value: 'BOM-' }],
  }), true)

// 数组映射行：子行一条都没配好时整行都不发——空 children 的组节点在后端会被当成普通叶子，
// 结果是把一整个数组写成一个值（静默错误，比报错更难发现）
const blankGroup = createGroupRow(1, 'items[0]', 'bom.bomLines')
blankGroup.children = [createRow(null, '', 'lineNo', 'STRING')]
eq('数组行没选来源数组不算配好', isRowConfigured({ ...blankGroup, source: '' }), false)
eq('数组行选了来源数组但子行一条都没配好，也不算配好', isRowConfigured(blankGroup), false)
blankGroup.children[0].source = 'materialCode'
eq('子行配好了整行才算配好', isRowConfigured(blankGroup), true)
eq('没配好的子行不进规则',
  (ruleOf(blankGroup).children ?? []).length, 1)
blankGroup.children.push(createRow(null, '', 'qty', 'INT'))
eq('一条数组行只把配好的子行写进规则',
  (ruleOf(blankGroup).children ?? []).map((c: Record<string, any>) => c.target), ['lineNo'])

eq('一条规则都产不出来时配置里就没有规则（保存会被拦住，不是被后端拒）',
  configFromRows([untouched]), { rules: [] })
eq('只把配好的行写进规则，空行留在编辑器里',
  configFromRows([halfConfigured, untouched, { ...blankConstant, extra: '固定值' }]).rules.map(r => r.target),
  ['order_no', 'kind'])

// 手写目标字段（清单里没有的）也要往返
const manual = rowFromRule({ sourceStep: 0, source: 'manualSrc', target: 'custom.deep.path', transform: 'STRING' })
eq('手写目标字段路径往返一致', ruleOf(manual).target, 'custom.deep.path')

// ---------------------------------------------------------------- 2. sourceStep 显式

function topLevelSourceSteps(): unknown[] {
  return TOP_RULES.map(r => ruleOf(rowFromRule(r)).sourceStep)
}

ok('顶层规则的 sourceStep 都显式存在', topLevelSourceSteps().every(v => v !== undefined && v !== null),
  JSON.stringify(topLevelSourceSteps()))

// 任务输入（null）也必须显式写成 0，不能落成 undefined 被 JSON 丢掉
const taskInputRow = rowFromRule({ source: 'a', target: 'x', transform: 'STRING' })
eq('sourceStep 缺失的行按任务输入显式写 0', ruleOf(taskInputRow).sourceStep, 0)

const withNull = rowFromRule({ sourceStep: null, source: 'a', target: 'x', transform: 'STRING' })
eq('sourceStep=null 的行也显式写 0', ruleOf(withNull).sourceStep, 0)

// 子规则不能带 sourceStep（带了后端会拒）
const childRules = (ruleOf(group).children ?? []) as Record<string, any>[]
ok('组内子规则都不带 sourceStep', childRules.every(c => c.sourceStep === undefined),
  JSON.stringify(childRules.map(c => c.sourceStep)))
eq('组节点的 sourceStep 照常写上', ruleOf(group).sourceStep, 1)

// ---------------------------------------------------------------- 3. 人话标签对照表

eq('跨步骤引用显示动作序号与字段名', fieldReferenceLabel({ step: 2, path: 'productCode' }, { 2: '读订单' }),
  '动作 2 · 读订单 · productCode')
eq('拿不到动作名时只显示动作序号', fieldReferenceLabel({ step: 2, path: 'productCode' }, {}),
  '动作 2 · productCode')
eq('任务输入', fieldReferenceLabel({ step: 0, path: 'batchNo' }, { 2: '读订单' }), '任务输入 · batchNo')
eq('step 为空按任务输入', fieldReferenceLabel({ step: null, path: 'batchNo' }, { 2: '读订单' }), '任务输入 · batchNo')
eq('外层引用', fieldReferenceLabel({ step: 1, path: 'headerNo', root: true }, { 1: '读 BOM' }),
  '外层 · headerNo')
eq('外层引用的悬停提示带上取自哪个动作',
  fieldReferenceFullLabel({ step: 1, path: 'headerNo', root: true }, { 1: '读 BOM' }),
  '动作 1 · 读 BOM · 外层 · headerNo')
eq('数组内相对路径不带前缀', rowReferenceLabel(group.children[1], { 1: '读 BOM' }, true), 'materialCode')
eq('同一行的完整说法（悬停）仍能看出取自哪个动作',
  rowReferenceFullLabel(group.children[1], { 1: '读 BOM' }, group.sourceStep), '动作 1 · 读 BOM · materialCode')
eq('顶层行显示动作名', rowReferenceLabel(TOP_RULES[0] && rowFromRule(TOP_RULES[0]), { 1: '读 BOM' }),
  '动作 1 · 读 BOM · bomNo')
eq('数组子行的行号显示成人话', rowReferenceLabel(group.children[0], { 1: '读 BOM' }, true), '行号 · 从 1 起')
eq('行号', fieldReferenceLabel({ step: 1, path: '$index' }, { 1: '读 BOM' }), '行号 · 从 1 起')
eq('行号带起始值', fieldReferenceLabel({ step: 1, path: '$index', startAt: 10 }, { 1: '读 BOM' }), '行号 · 从 10 起')
eq('步骤标题', stepLabel(0, '读订单'), '任务输入')
eq('步骤标题-其他动作', stepLabel(1, '读订单'), '动作 1 · 读订单')
eq('空行提示', emptyRowLabel(), '还没选来源')

// 七种转换各自都有人话标签（对照表覆盖齐全，加一种这里就会红）
eq('七种转换的标签', TRANSFORM_KINDS.map(transformLabel),
  ['直接映射', '数字转换', '日期格式化', '枚举映射', '常量', '拼接', '行号'])

function assertNoDollar(text: string) {
  ok(`界面文案不出现 $：${text}`, !text.includes('$'), text)
}
;[fieldReferenceLabel({ step: 0, path: 'a' }, {}),
  fieldReferenceLabel({ step: 3, path: 'b', root: true }, { 3: 'x' }),
  fieldReferenceLabel({ step: 1, path: '$index' }, { 1: 'x' }),
  stepLabel(0, ''), emptyRowLabel()].forEach(assertNoDollar)

// ---------------------------------------------------------------- 目标清单 → 行

const TARGETS: TargetField[] = [
  { path: 'bom.bomNo', type: 'VARCHAR', required: true },
  { path: 'bom.productCode', type: 'VARCHAR', required: true },
  { path: 'bom.remark', type: 'VARCHAR', required: false },
  {
    path: 'bom.bomLines', type: 'ARRAY', required: true, element: [
      { path: 'lineNo', type: 'INT', required: true },
      { path: 'materialCode', type: 'VARCHAR', required: true },
    ],
  },
]

const targetRows: MapRow[] = rowsFromTargetFields(TARGETS, 1)
eq('每个目标字段一行（含数组内子行）', targetRows.length, 4)
eq('数组目标字段行是组行', [targetRows[3].target, targetRows[3].iterate], ['bom.bomLines', 'FOR_EACH'])
eq('数组子行目标写相对路径', targetRows[3].children.map(c => c.target), ['lineNo', 'materialCode'])
eq('新行默认来源步骤', targetRows.map(r => r.sourceStep), [1, 1, 1, 1])
eq('新行默认不带来源字段', targetRows.every(r => r.source === ''), true)

// 结构探测给的是扁平路径，编辑器要把它折成"数组字段一行 + 子行"
const folded = targetFieldsFromPaths(
  ['bomNo', 'productCode', 'items[0].materialCode', 'items[0].qty', 'items[1].materialCode'],
  new Map([['bomNo', true], ['productCode', true], ['items', false]]))
eq('扁平路径折成两个顶层字段加一个数组字段', folded.map(f => f.path), ['bomNo', 'productCode', 'items'])
eq('数组元素字段去重并落在 element 里', folded[2].element?.map(e => e.path), ['materialCode', 'qty'])
eq('必填口径按请求里的清单来', [folded[0].required, folded[2].required], [true, false])

// 已存配置与目标清单对齐：清单里的行沿用已配的行（参数不丢），清单外的行留着
const savedGroup = rowFromRule({
  sourceStep: 1, source: 'items', target: 'bom.bomLines', transform: 'STRING', iterate: 'FOR_EACH',
  children: [{ source: 'unit', target: 'unit', transform: 'ENUM', enumMap: { PCS: '件' } }],
})
const savedLeaf = rowFromRule({ sourceStep: 1, source: 'bomNo', target: 'bom.bomNo', transform: 'STRING' })
const manualExtra = rowFromRule({ sourceStep: 1, source: 'x', target: 'custom.field', transform: 'STRING' })
const merged = mergeRowsWithTargets([savedLeaf, savedGroup, manualExtra], TARGETS, 1)
eq('合并后清单字段在前、手写行在后', merged.map(r => r.target),
  ['bom.bomNo', 'bom.productCode', 'bom.remark', 'bom.bomLines', 'custom.field'])
eq('已配的行沿用（来源字段没被清掉）', merged[0].source, 'bomNo')
eq('已存子行沿用、顺序跟目标清单走、清单里多出来的补在后面', merged[3].children.map(c => c.target),
  ['lineNo', 'materialCode', 'unit'])
eq('已存子行的参数保留（ENUM 表没丢）', merged[3].children[2].transform, 'ENUM')
eq('已存子行的参数真的还在', merged[3].children[2].enumMap, { PCS: '件' })
eq('清单里没配过的行仍是空行', merged[1].source, '')

// ---------------------------------------------------------------- 点来源字段填行

const leafRow = createRow(1, '', 'bom.bomNo', 'STRING')
const arrayRow = createGroupRow(1, '', 'bom.bomLines')
const childRow = createRow(null, '', 'lineNo', 'STRING')
const arrayField = { name: 'items[0].materialCode', type: 'STRING' }
const plainField = { name: 'bomNo', type: 'STRING' }

eq('普通行接受普通字段', fillRowFromSource(leafRow, plainField, 1).row?.source, 'bomNo')
eq('普通行不接受数组字段', fillRowFromSource(leafRow, arrayField, 1).reason, 'not-array')
eq('数组行只接受数组', fillRowFromSource(arrayRow, plainField, 1).reason, 'array-expects-array')
eq('数组行接受数组字段', fillRowFromSource(arrayRow, arrayField, 1).row?.source, 'items[0].materialCode')
eq('数组子行的来源必须是数组元素内的字段', fillRowFromSource(childRow, plainField, 1, true).reason, 'child-wants-relative')
eq('子行填相对路径时不自己存 sourceStep（继承组节点）', childRow.sourceStep, null)
eq('没有选中行时给提示', fillRowFromSource(null, plainField, 1).reason, 'no-target')

// 选来源时按字段类型猜转换：没动过的行才猜，用户挑过的转换不能被冲掉
const intRow = createRow(1, '', 'qty', 'STRING')
eq('空行按来源类型猜数字转换', fillRowFromSource(intRow, { name: 'order_no', type: 'INT' }, 1).row?.transform, 'INT')
const dateRow = createRow(1, '', 'createdDate', 'DATE')
eq('已经挑了转换的行不被改回默认', fillRowFromSource(dateRow, { name: 'order_no', type: 'STRING' }, 1).row?.transform, 'DATE')
const enumRow = createRow(1, '', 'status', 'ENUM')
eq('用户挑的枚举映射也保住', fillRowFromSource(enumRow, { name: 'order_no', type: 'INT' }, 1).row?.transform, 'ENUM')

/** 取规则里几个字段，方便一眼看出形状（顺序按键名排，因为 eq 的比较是键序无关的）。 */
function pick(rule: Record<string, any>, ...keys: string[]): unknown[] {
  return keys.slice().sort().map(k => rule[k])
}

// ---------------------------------------------------------------- 七种转换（#100）

/** 一条叶子规则，参数按需要给。 */
function leaf(source: string, target: string, transform: string, extra: Record<string, any> = {}): MapRow {
  return rowFromRule({ sourceStep: 1, source, target, transform, ...extra })
}

/** 数组映射的子行（行号只有这种行能用）。 */
function childOf(source: string, transform: string, extra: Record<string, any> = {}): MapRow {
  return rowFromRule({ source, target: 'lineNo', transform, ...extra })
}

/** 数组映射的子行在目标树里的层级是 1（组行是 0）。 */
const CHILD_DEPTH = 1

// 七种转换各自产出的规则形状：transform 加它自己那份参数（后端 FieldMappingConfig.validate 认的就是这些）
// 这条放最前面：它依赖「还没配过日期转换」这个前提（配过之后会记着最近用过的那个预设）。
eq('日期转换没带格式串时按编译器的默认值显示（后端默认 yyyy-MM-dd）',
  datePatternFor(leaf('a', 'x', 'DATE')), DEFAULT_DATE_PATTERN)
eq('直接映射：只有 transform',
  ruleOf(leaf('bomNo', 'bom.bomNo', 'STRING')).transform, 'STRING')
eq('数字转换：套在来源字段外面，不带额外参数',
  pick(ruleOf(applyTransform(leaf('quantity', 'qty', 'STRING'), 'INT')), 'transform', 'source'), ['quantity', 'INT'])
eq('日期格式化：选中的预设原样带到规则里',
  pick(ruleOf(applyTransform(leaf('createdAt', 'createdDate', 'STRING'), 'DATE', { datePattern: 'yyyyMMdd' })),
    'transform', 'datePattern'), ['yyyyMMdd', 'DATE'])
eq('日期格式化：预设之外也认（旧配置里的格式串要能原样回填）',
  ruleOf(applyTransform(leaf('createdAt', 'createdDate', 'STRING'), 'DATE', { datePattern: 'yyyy/MM/dd' })).datePattern,
  'yyyy/MM/dd')
eq('枚举映射：键值表原样带到规则里',
  ruleOf(applyTransform(leaf('unit', 'unit', 'STRING'), 'ENUM', { enumMap: { PCS: '件' } })).enumMap, { PCS: '件' })
eq('常量：值当场写进 constant，且不留来源字段',
  pick(ruleOf(applyTransform(leaf('bomNo', 'bom.vendorName', 'STRING'), 'CONSTANT', { constant: '固定值' })),
    'constant', 'source'), ['固定值', ''])
ok('常量行不留来源字段（界面上也就不会拿着上一行的来源假装配好了）',
  applyTransform(leaf('bomNo', 'bom.vendorName', 'STRING'), 'CONSTANT', { constant: '固定值' }).source === '')
eq('拼接：常量片段是字符串、来源片段是 {path}',
  ruleOf(applyTransform(leaf('materialCode', 'bomNo', 'STRING'), 'CONCAT',
    { parts: [{ type: 'const', value: 'BOM-' }, { type: 'path', value: 'materialCode' }] })).parts,
  ['BOM-', { path: 'materialCode' }])
eq('拼接：空片段不进规则（后端会拒没写 path 的路径片段）',
  ruleOf(applyTransform(leaf('materialCode', 'bomNo', 'STRING'), 'CONCAT',
    { parts: [{ type: 'const', value: 'BOM-' }, { type: 'path', value: '' }, { type: 'const', value: '' }] })).parts,
  ['BOM-'])
eq('拼接：一个片段都没填时不写 parts，「拼接需要片段」留给后端报',
  ruleOf(applyTransform(leaf('materialCode', 'bomNo', 'STRING'), 'CONCAT', { parts: [{ type: 'path', value: '' }] })).parts,
  undefined)
eq('行号：source 固定 $index、起始值走 startAt',
  pick(ruleOf(applyTransform(childOf('', 'INDEX'), 'INDEX', { startAt: 10 })), 'transform', 'source', 'startAt'),
  ['$index', 10, 'INDEX'])

// 行号只在数组映射的子行里合法（后端 FieldMappingConfig.validate：顶层 INDEX 直接拒）
eq('顶层行不能选行号', canUseTransform(leaf('bomNo', 'bom.bomNo', 'STRING'), 'INDEX'), false)
eq('数组子行可以选行号', canUseTransform(childOf('', 'INDEX'), 'INDEX', CHILD_DEPTH), true)
eq('顶层禁用行号时说清原因（与后端校验一致）',
  transformDisabledReason(leaf('bomNo', 'bom.bomNo', 'STRING'), 'INDEX'), '行号只能在数组映射的子行里用（与后端校验一致）')
eq('合法组合没有禁用原因', transformDisabledReason(childOf('', 'INDEX'), 'INDEX', CHILD_DEPTH), '')
ok('顶层行禁用的行号选项带上了原因',
  transformOptions(leaf('bomNo', 'bom.bomNo', 'STRING')).some(o => o.value === 'INDEX' && o.disabled && o.reason !== ''))
// 可用时 reason 说的是这一项要填什么——票里「参数就地编辑」，选中之前就得知道有参数
ok('日期格式化选项上写着当前格式串',
  transformOptions(leaf('createdAt', 'x', 'DATE', { datePattern: 'yyyyMMdd' }))
    .find(o => o.value === 'DATE')?.reason.includes('yyyyMMdd') === true)
ok('枚举表空着的行，选项上直接说清还没填',
  transformOptions(leaf('unit', 'x', 'STRING')).find(o => o.value === 'ENUM')?.reason.includes('还没填') === true)
ok('拼接没片段时选项上写着至少要有一个片段',
  transformOptions(leaf('a', 'x', 'STRING')).find(o => o.value === 'CONCAT')?.reason.includes('至少要有 1 个片段') === true)
ok('数组子行的七种转换都可用', transformOptions(childOf('', 'INDEX'), CHILD_DEPTH).every(o => !o.disabled))
ok('转换选项顺序与后端枚举一致（界面上按这个顺序排）',
  transformOptions(childOf('', 'INDEX'), CHILD_DEPTH).every((o, i) => o.value === TRANSFORM_KINDS[i]))

// 换转换：参数跟着换，不该留的旧参数清掉（否则存进去的是自相矛盾的规则）
eq('数字转换 → 日期格式化：行上的格式串跟着换，来源字段留着',
  pick(applyTransform(leaf('quantity', 'qty', 'INT'), 'DATE', { datePattern: 'yyyyMMdd' }),
    'transform', 'extra', 'datePattern'), [null, 'yyyyMMdd', 'DATE'])
eq('日期格式化 → 数字转换 → 日期格式化：按这行配过的和编译器默认值来（不跨行记别人的预设）',
  datePatternFor(applyTransform(applyTransform(leaf('a', 'x', 'DATE', { datePattern: 'yyyyMMdd' }), 'INT'), 'DATE')),
  DEFAULT_DATE_PATTERN)
eq('日期格式化 → 直接映射：规则里不再有格式串（切回来时还认得出配过的那一个）',
  pick(ruleOf(applyTransform(leaf('createdAt', 'createdDate', 'DATE', { datePattern: 'yyyy-MM-dd' }), 'STRING')),
    'transform', 'datePattern'), [undefined, 'STRING'])
eq('枚举映射 → 直接映射：枚举表清掉',
  pick(applyTransform(leaf('unit', 'unit', 'ENUM', { enumMap: { PCS: '件' } }), 'STRING'), 'transform', 'enumMap'),
  [null, 'STRING'])
eq('常量 → 直接映射：规则里不再有写死的值',
  pick(ruleOf(applyTransform(leaf('', 'x', 'CONSTANT', { constant: '固定值' }), 'STRING')), 'transform', 'constant'),
  [undefined, 'STRING'])
eq('行号 → 直接映射：不再抓着 $index 不放（否则编译成「字段 $index」）',
  pick(applyTransform(childOf('$index', 'INDEX', { startAt: 5 }), 'STRING'), 'transform', 'source', 'startAt'),
  ['', null, 'STRING'])

// 枚举映射的键值表：按样例值把缺的键补上（后端查不到键时该字段静默变成 null）
// samples 的形状按后端结构探测契约来（SourceField.samples 就是原始值那列）。
const UNIT_FIELD: SourceField = { name: 'unit', type: 'STRING', nullable: false, samples: ['PCS', 'SET', 'PCS'] }
const ELEMENT_UNIT: SourceField = { name: 'items[0].unit', type: 'STRING', nullable: false, samples: ['PCS', 'SET'] }
eq('样例值去重、按出现顺序', distinctSampleValues(UNIT_FIELD), ['PCS', 'SET'])
eq('没配来源时拿不到样例值', distinctSampleValues(undefined), [])
eq('样例值是对象时取它的 value，没有 value 就整条序列化（探不到形状也别丢值）',
  distinctSampleValues({ name: 'a', type: 'STRING', samples: [{ value: 1 }, { key: 'PCS', value: '件' }, { other: 2 }] }),
  ['1', '件', '{"other":2}'])
eq('缺的键按样例值算出来（已有的键不动）',
  missingEnumKeys({ PCS: '件', OTHER: 'x' }, ELEMENT_UNIT), ['SET'])
eq('一条都没有时全是缺的', missingEnumKeys({}, ELEMENT_UNIT), ['PCS', 'SET'])
eq('补全后的表能直接存进规则',
  ruleOf(applyTransform(leaf('unit', 'bom.unit', 'STRING'), 'ENUM',
    { enumMap: appendEnumSamples({ PCS: '件' }, missingEnumKeys({ PCS: '件' }, ELEMENT_UNIT)) })).enumMap,
  { PCS: '件', SET: 'SET' })
// 表格里「加了行还没填键」的格子用占位键占位；写规则时必须滤掉，否则存的是一张带空键的表
eq('占位键不进规则',
  ruleOf({ ...leaf('unit', 'x', 'ENUM', { enumMap: { [ENUM_PLACEHOLDER + '0']: '', PCS: '件' } }) }).enumMap,
  { PCS: '件' })
eq('只有占位键时不写 enumMap（「枚举转换需要 enumMap」留给后端报）',
  ruleOf(leaf('unit', 'x', 'ENUM', { enumMap: { [ENUM_PLACEHOLDER + '0']: '' } })).enumMap, undefined)

// 枚举表就是一张 map，键唯一：改键撞上已有的键时按「后改的赢」合并，当场少一行
const enumPairs = [
  { id: 1, key: 'ACTIVE', value: '已生效' },
  { id: 2, key: '', value: '' },
]
eq('改键撞上已有的键：被撞的那行消失，改的那行留下新键',
  setEnumKey(enumPairs, 1, 'ACTIVE').map(p => [p.key, p.value]), [['ACTIVE', '']])
eq('改键没撞上：两行都在',
  setEnumKey(enumPairs, 1, 'INACTIVE').map(p => p.key), ['ACTIVE', 'INACTIVE'])
eq('把键清空不算撞（空键本来就用占位键各占一格）',
  setEnumKey(enumPairs, 1, '').map(p => p.key), ['ACTIVE', ''])
eq('键前后空格不影响判重',
  setEnumKey(enumPairs, 1, ' ACTIVE ').map(p => p.key), ['ACTIVE'])
eq('越界的行号原样返回', setEnumKey(enumPairs, 9, 'X').length, 2)

// 每种转换在界面上读起来是一句中文
eq('转换类型的人话与对照表一致', TRANSFORM_KINDS.map(transformName),
  ['直接映射', '数字转换', '日期格式化', '枚举映射', '常量', '拼接', '行号'])
eq('直接映射：来源字段就是这句话',
  transformLabelOf(leaf('bomNo', 'x', 'STRING'), () => '动作 1 · 读 BOM · bomNo'), '动作 1 · 读 BOM · bomNo')
eq('数字转换的人话（来源字段套在里面）',
  transformLabelOf(leaf('quantity', 'x', 'INT'), () => '动作 1 · 读 BOM · quantity'),
  '数字转换（动作 1 · 读 BOM · quantity）')
eq('日期格式化的人话里带着选中的预设',
  transformLabelOf(leaf('createdAt', 'x', 'DATE', { datePattern: 'yyyy-MM-dd' }), () => '动作 1 · 读 BOM · createdAt'),
  '日期格式化 · yyyy-MM-dd（动作 1 · 读 BOM · createdAt）')
eq('枚举映射的人话里带着条数',
  transformLabelOf(leaf('unit', 'x', 'ENUM', { enumMap: { PCS: '件', SET: '套' } }), () => 'unit'),
  '枚举映射 · 2 条（unit）')
eq('空枚举表也读得懂', transformLabelOf(leaf('unit', 'x', 'ENUM', { enumMap: {} }), () => 'unit'), '枚举映射 · 空表（unit）')
eq('常量的人话把值显示出来',
  transformLabelOf(leaf('', 'x', 'CONSTANT', { constant: '固定值' }), () => '还没选来源'), '常量「固定值」')
eq('常量没填值时说得出来',
  transformLabelOf(leaf('', 'x', 'CONSTANT'), () => '还没选来源'), '常量「还没填」')
eq('拼接的人话按顺序念出每个片段（来源片段用字段名）',
  transformLabelOf(leaf('materialCode', 'x', 'CONCAT', { parts: ['BOM-', { path: 'materialCode' }] }),
    (addr: string) => (addr === '0' ? 'BOM-' : 'materialCode')), '拼接（BOM- + materialCode）')
eq('拼接里空来源片段也说得出来',
  transformLabelOf(leaf('materialCode', 'x', 'CONCAT', { parts: [{ path: '' }] }), () => '（空片段）'),
  '拼接（（空片段））')
eq('行号的人话带着起始值',
  transformLabelOf(childOf('$index', 'INDEX', { startAt: 10 }), () => '行号'), '行号 · 从 10 起')
eq('还没选来源的行照旧显示「还没选来源」',
  transformLabelOf(leaf('', 'x', 'STRING'), () => '还没选来源'), '还没选来源')

// 选中的日期预设要能在界面上显示出来（用户选了什么，行里就显示什么）
eq('选中的日期预设就是行里显示的那个',
  datePatternFor(leaf('a', 'x', 'DATE', { datePattern: 'yyyyMMdd' })), 'yyyyMMdd')
eq('换过的预设也照原样显示（预设之外自己写的格式串同样认）',
  datePatternFor(leaf('a', 'x', 'DATE', { datePattern: 'yyyy/MM/dd' })), 'yyyy/MM/dd')

// 界面上不出现 $ 符号：七种转换在界面上的说法里一个 $ 都没有
for (const t of TRANSFORM_KINDS) {
  const text = transformLabelOf(leaf('bomNo', 'x', t, {
    datePattern: 'yyyy-MM-dd', enumMap: { PCS: '件' }, constant: '固定值',
    parts: ['BOM-', { path: 'materialCode' }], startAt: 1,
  }), (addr: string) => (addr === '0' ? 'BOM-' : 'materialCode'))
  assertNoDollar(text)
}

console.log(`\n# ${passed} passed, ${failed} failed`)
if (failed > 0) {
  process.exitCode = 1
}
