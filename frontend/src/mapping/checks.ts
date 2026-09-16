/**
 * 新版映射编辑器的前端检查（#99 / #100 / #101 / #102 / #105 验收项）：不测组件，只测纯逻辑的外部行为。
 *
 * 跑法（零新依赖）：
 *   node --experimental-strip-types frontend/src/mapping/checks.ts
 *
 * 断言这几件事：
 *   1. 行与规则互转往返不丢字段；
 *   2. 每条顶层规则显式写了 `sourceStep`（后端查不出这个错，只能前端保住）；
 *   3. 人话标签符合对照表（界面上不出现 `$`）；
 *   4. 目标清单 → 行（含数组行与子行）；
 *   5. 七种转换的参数与人话；
 *   6. 数组映射（#101）：数组行选来源数组、子行按相对路径、子行引用外层表头、展开收起、加子行，
 *      以及跑通制品 → BOM 的规则形状；
 *   7. 手工新增行（#102）：加空行、目标字段的补全清单按行给、手输路径照样进规则、加子字段与行号，
 *      以及清除一行与撤销这次清除；
 *   8. 依赖样例与目标清单的问题（#104）：后端新报的那几条（必填没配来源 / 来源不在样例里 / 枚举没覆盖
 *      样例值 / 文本写进数字字段 / 拼接有空片段）同样落在正确的行右侧、进得了问题列表、跳得过去；
 *   9. 配得快一点（#105）：来源清单按路径与样例值搜、「已用」的计数与高亮、三档过滤（全部 / 只看未配置 /
 *      只看必填）、目标字段搜索并跳行、以及「自动匹配同名」只填空行不覆盖、且这条通路上没有网络调用。
 *  10. 校验两档与清掉的行（#113）：必需那几条问题落到正确的行上、一行上的全部问题一条不少（错误在前）、
 *      只有警告时不算有错 / 没有可跳的行，以及清掉一个必填字段的行给出的那条提醒（与后端逐字一致、
 *      只认用户亲手清过的行）。
 *  11. 来源样例的两条路（#115）：探测那条路的说法一个字不变，粘的那条路写明按哪一步的输出来算、
 *      是数组就注明「按第 1 条推的」，换来源步骤只换这份样例归到哪一步（字段清单不动）；目标字段清单
 *      粘出来的那一行小字（#118）：必填推不出来、数组按第 1 条推。
 */
import {
  clearRowMapping, createGroupRow, createRow, ENUM_PLACEHOLDER, hasMapping, isRowConfigured,
  markHumanEdited, restoreRowMapping, rowFromRule, ruleFromRow, type MapRow,
} from './rows.ts'
import {
  appendArrayRow, appendLeafRow, configFromRows, flattenRows, mergeRowsWithTargets, nextEmptyRow,
  rowReferenceFullLabel, rowReferenceLabel, rowsFromTargetFields, targetCandidatesFor, targetFieldsFromPaths,
  type SourceField, type TargetField,
} from './editor-model.ts'
import {
  autoMatchSameName, autoSourceNotices, clearedRequiredNotices, filterSourceGroups, nameSegment,
  nextMatchRowId, requiredSourceKey, requiredSourceKeys, sourceItemMatches, sourceRefKeyId, sourceUsageOf,
  targetRowsView, usedCountOf, usedRowIdsOf,
} from './fast-config.ts'
import {
  fieldReferenceLabel, fieldReferenceFullLabel, stepLabel, emptyRowLabel, transformLabel, TRANSFORM_KINDS,
  type TransformKind,
} from './labels.ts'
import {
  applyTransform, appendEnumSamples, canUseTransform, DATE_PRESETS, datePatternFor, DEFAULT_DATE_PATTERN,
  distinctSampleValues, missingEnumKeys, normalizeDatePattern, setEnumKey, transformDisabledReason,
  transformLabelFor, transformLabelOf, transformName, transformOptions,
} from './transforms.ts'
import {
  appendChildRow, appendIndexChildRow, emptyPickResult, fillRowFromPick, sourcePickerFor, toggledRowIds,
  visibleRows, ancestorRowIds, type PickableItem,
} from './source-picker.ts'
import {
  draftRequest, firstErrorRowId, issueLocation, issuesByRow, locateIssues, rowIssueState,
  type DraftValidation,
} from './draft.ts'
import {
  editorModeOf, expressionConfig, expressionOf, expressionReplacedNotice, expressionSkipNotice, hasExpression,
} from './expression.ts'
import {
  PART_KINDS, applyPartKind, canUsePartKind, partEmpty, partFromRule, partKindDisabledReason,
  partKindLabel, partKindOf, partLabelSpec, partRuleOf,
} from './parts.ts'
import { isPanelChoice, keyIntent, rowStepIndex, stepIndex, type KeyContext } from './keyboard.ts'
import { attributeSample, sampleNoteOf, sourceTipOf, targetNoteOf, type SampleOrigin } from './sample.ts'
import type { EditorSourceSample } from '../types.ts'

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

// 目标字段清单：探测给什么就带什么。类型与必填都按探测路径（带 `[0]`）给，
// 折进编辑器路径口径之后数组元素里的字段才查得到——不然它们永远既没类型也不算必填。
const typedTargets = targetFieldsFromPaths(
  ['order_no', 'qty', 'items[0].materialCode', 'items[0].qty'],
  new Map([['order_no', true], ['items[0].qty', true]]),
  new Map([['order_no', 'VARCHAR'], ['items[0].qty', 'INT']]))
eq('目标清单带着探测到的类型（数组元素里的也带）',
  [typedTargets[0].type, typedTargets[2].type, typedTargets[2].element?.[1].type],
  ['VARCHAR', 'ARRAY', 'INT'])
eq('数组元素里的字段也查得到必填（探测路径带 [0]，编辑器里不带）',
  typedTargets[2].element?.map(e => e.required), [false, true])

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
/** 左边清单上的一项，形状就是 `sourcePickerFor` 产出的那个。 */
function item(path: string, extra: Partial<PickableItem> = {}): PickableItem {
  return { kind: 'field', step: 1, path, root: false, label: path, ...extra }
}
/** 数组项：真清单里给的是**数组本身**的路径（`arrayItems` 把元素字段的 `[0]` 去掉）。 */
const arrayField = item('items', { kind: 'array' })
const plainField = item('bomNo', { step: null })

eq('普通行接受普通字段', fillRowFromPick(leafRow, plainField).row?.source, 'bomNo')
eq('普通行不接受数组', fillRowFromPick(leafRow, arrayField).reason, 'not-array')
eq('数组行只接受数组', fillRowFromPick(arrayRow, plainField).reason, 'array-expects-array')
eq('数组行接受数组', fillRowFromPick(arrayRow, arrayField).row?.source, 'items')
eq('数组行填上数组时带上它自己的来源步骤',
  fillRowFromPick(createGroupRow(1, '', 'l'), arrayField).row?.sourceStep, 1)
eq('子行里普通路径照样能填（相对路径与外层引用才是子行专属的）',
  fillRowFromPick(childRow, item('bomNo', { kind: 'field', step: null }), true).row?.source, 'bomNo')
eq('顶层行不收元素字段（收了编译出来只取第一个元素）',
  fillRowFromPick(leafRow, item('items[0].qty', { kind: 'element', step: null })).reason, 'child-wants-relative')
eq('顶层行也不收外层引用（它没有外面那一层）',
  fillRowFromPick(leafRow, item('bomNo', { kind: 'outer', step: 1, root: true })).reason, 'child-wants-relative')
eq('相对路径能填进子行（子行就是收这个的）',
  fillRowFromPick(createRow(null, '', 'qty', 'STRING'), item('qty', { kind: 'element', step: null }), true)
    .row?.source,
  'qty')
eq('没有选中行时给提示', fillRowFromPick(null, plainField).reason, 'no-target')

// 选来源时按字段类型猜转换：没动过的行才猜，用户挑过的转换不能被冲掉
const intRow = createRow(1, '', 'qty', 'STRING')
eq('空行按来源类型猜数字转换', fillRowFromPick(intRow, item('order_no', { type: 'INT' })).row?.transform, 'INT')
const dateRow = createRow(1, '', 'createdDate', 'DATE')
eq('已经挑了转换的行不被改回默认',
  fillRowFromPick(dateRow, item('order_no', { type: 'STRING' })).row?.transform, 'DATE')
const enumRow = createRow(1, '', 'status', 'ENUM')
eq('用户挑的枚举映射也保住', fillRowFromPick(enumRow, item('order_no', { type: 'INT' })).row?.transform, 'ENUM')

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

// 枚举映射的键值表：按样例值把缺的键补上（后端查不到键时该字段静默从目标报文里整个消失）
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

// ---------------------------------------------------------------- 日期格式手输（#111）

// 预设之外能**手输任意格式串**：手输的那一个与四个预设走同一个 `datePattern`，摘要里显示的就是它
eq('手输的格式串原样进规则',
  ruleOf(applyTransform(leaf('createdAt', 'createdDate', 'STRING'), 'DATE',
    { datePattern: 'yyyyMMddHHmmss' })).datePattern, 'yyyyMMddHHmmss')
eq('手输的格式串显示在这一行的摘要里',
  transformLabelOf(leaf('createdAt', 'x', 'DATE', { datePattern: 'yyyyMMddHHmmss' }), () => 'createdAt'),
  '日期格式化 · yyyyMMddHHmmss（createdAt）')
ok('日期那一项的说明里写着当前格式串（手输的也认）',
  transformOptions(leaf('createdAt', 'x', 'DATE', { datePattern: 'yyyyMMddHHmmss' }))
    .find(o => o.value === 'DATE')?.reason.includes('yyyyMMddHHmmss') === true)
eq('四个预设原样进规则（手输这条路不改变预设那条）',
  DATE_PRESETS.map(p => ruleOf(applyTransform(leaf('createdAt', 'createdDate', 'STRING'), 'DATE',
    { datePattern: p })).datePattern), [...DATE_PRESETS])

// 输入框清空时回落到默认格式，而不是把空串送进编译器（#111 验收五）
eq('清空的格式串回落到默认格式', normalizeDatePattern(''), DEFAULT_DATE_PATTERN)
eq('只有空白也算清空', normalizeDatePattern('   '), DEFAULT_DATE_PATTERN)
eq('手输的格式串去掉首尾空格之后再用', normalizeDatePattern('  yyyyMMddHHmmss  '), 'yyyyMMddHHmmss')
eq('换转换时参数给的是空串也落回默认格式（不是空串）',
  pick(ruleOf(applyTransform(leaf('createdAt', 'createdDate', 'STRING'), 'DATE', { datePattern: '' })),
    'transform', 'datePattern'), [DEFAULT_DATE_PATTERN, 'DATE'])

// 老配置里 `datePattern` 是空串时，存出去的那一份**不带这个键**——后端按「没写」用默认值
const blankDateRow = rowFromRule({ sourceStep: 1, source: 'createdAt', target: 'createdDate',
  transform: 'DATE', datePattern: '' })
eq('空的格式串不写成空串（这个键干脆不写）', ruleOf(blankDateRow).datePattern, undefined)
eq('空的格式串在界面上显示的是默认格式', datePatternFor(blankDateRow), DEFAULT_DATE_PATTERN)

// 第一轮存下来的配置打开后行为完全一致（#111 验收六）：四个预设与老配置里写过的格式串都原样往返
for (const pattern of [...DATE_PRESETS, 'yyyy/MM/dd HH:mm:ss', 'yyyyMMddHHmmss']) {
  const saved = { sourceStep: 1, source: 'createdAt', target: 'createdDate', transform: 'DATE',
    datePattern: pattern }
  eq(`老配置打开 → 保存：${pattern} 一个字都不变`, ruleOf(rowFromRule(saved)), saved)
}

// 界面上不出现 $ 符号：七种转换在界面上的说法里一个 $ 都没有
for (const t of TRANSFORM_KINDS) {
  const text = transformLabelOf(leaf('bomNo', 'x', t, {
    datePattern: 'yyyy-MM-dd', enumMap: { PCS: '件' }, constant: '固定值',
    parts: ['BOM-', { path: 'materialCode' }], startAt: 1,
  }), (addr: string) => (addr === '0' ? 'BOM-' : 'materialCode'))
  assertNoDollar(text)
}

// ---------------------------------------------------------------- 片段转换（#112）

// 一个片段挂一个转换，六选一（常量 + 直接取值/数字转换/日期格式化/枚举映射/行号）
eq('片段转换的六项与人话', PART_KINDS.map(partKindLabel),
  ['常量', '直接取值', '数字转换', '日期格式化', '枚举映射', '行号'])
eq('老片段（字符串常量）读进来是常量', partKindOf({ type: 'const', value: 'BOM-' }), 'CONSTANT')
eq('老片段（{"path"}）读进来是直接取值', partKindOf({ type: 'path', value: 'materialCode' }), 'STRING')
eq('加工片段读进来是它自己那一种',
  partKindOf({ type: 'path', value: 'createdTime', transform: 'DATE', datePattern: 'yyyyMMdd' }), 'DATE')
eq('行号片段读进来是行号（值那一格是空的）',
  partKindOf({ type: 'path', value: '', transform: 'INDEX', startAt: 1 }), 'INDEX')

// 换转换：参数跟着换、旧的清掉——一个片段只有一个转换，界面上没有第二层的入口
const datePart = applyPartKind({ type: 'path', value: 'createdTime' }, 'DATE')
eq('选日期格式化：默认格式串落在片段自己身上',
  partRuleOf(datePart), { path: 'createdTime', transform: 'DATE', datePattern: DEFAULT_DATE_PATTERN })
eq('选日期格式化时给的那一个就用那一个',
  partRuleOf(applyPartKind({ type: 'path', value: 'createdTime' }, 'DATE', { datePattern: 'yyyyMMdd' })),
  { path: 'createdTime', transform: 'DATE', datePattern: 'yyyyMMdd' })
eq('再选数字转换：日期那一份参数跟着清掉',
  partRuleOf(applyPartKind(datePart, 'INT')), { path: 'createdTime', transform: 'INT' })
eq('再选直接取值：不带任何参数（老形状）',
  partRuleOf(applyPartKind(datePart, 'STRING')), { path: 'createdTime' })
eq('选枚举映射：还不写 enumMap（「枚举转换需要 enumMap」由后端说，与行级同一条）',
  partRuleOf(applyPartKind({ type: 'path', value: 'unit' }, 'ENUM')),
  { path: 'unit', transform: 'ENUM' })
eq('枚举片段里填了键值对才写进规则',
  partRuleOf(applyPartKind({ type: 'path', value: 'unit' }, 'ENUM', { enumMap: { PCS: '件' } })),
  { path: 'unit', transform: 'ENUM', enumMap: { PCS: '件' } })
eq('枚举片段里还没填键的占位格不进规则（与行级同一条）',
  partRuleOf({ type: 'path', value: 'unit', transform: 'ENUM',
    enumMap: { [ENUM_PLACEHOLDER + '0']: '', PCS: '件' } }),
  { path: 'unit', transform: 'ENUM', enumMap: { PCS: '件' } })
eq('选常量：变成常量片段、值清空、参数一并清掉',
  partRuleOf(applyPartKind(datePart, 'CONSTANT')), '')
eq('选行号：值那一格清掉、起始值默认从 1 起',
  partRuleOf(applyPartKind({ type: 'path', value: 'anything' }, 'INDEX')), { transform: 'INDEX', startAt: 1 })
eq('整数片段往返（数字转换）',
  partRuleOf(partFromRule({ path: 'qty', transform: 'INT' })), { path: 'qty', transform: 'INT' })

// 行号片段只在数组元素子行里给（顶层置灰，并且写明原因）
eq('顶层拼接行上不给行号片段', canUsePartKind('INDEX', 0), false)
eq('数组子行里给行号片段', canUsePartKind('INDEX', 1), true)
ok('置灰时写明原因', partKindDisabledReason('INDEX', 0).includes('数组映射的子行'))
ok('别的片段转换不受位置影响（只有行号看位置）',
  PART_KINDS.every(kind => kind === 'INDEX' || canUsePartKind(kind, 0)))

// 存储形状往返：老片段打开 → 保存逐字段不变（#112 验收八最重要的一条）
for (const raw of ['BOM-', { path: 'materialCode' }]) {
  eq(`老片段 ${JSON.stringify(raw)} 往返不变`, partRuleOf(partFromRule(raw)), raw)
}
const legacyConcatRule = { sourceStep: 1, source: '', target: 'bomNo', transform: 'CONCAT',
  parts: ['BOM-', { path: 'materialCode' }] }
eq('老配置的整条拼接规则往返不变', ruleOf(rowFromRule(legacyConcatRule)), legacyConcatRule)

// 新片段往返：加工片段与行号片段各一种
const NEW_PARTS = ['BOM-',
  { path: 'createdTime', transform: 'DATE', datePattern: 'yyyyMMdd' },
  { path: 'unit', transform: 'ENUM', enumMap: { PCS: '件' } },
  { path: 'qty', transform: 'INT' },
  { transform: 'INDEX', startAt: 1 }]
eq('加工片段与行号片段往返不变',
  ruleOf(rowFromRule({ sourceStep: 1, target: 'bomNo', transform: 'CONCAT', parts: NEW_PARTS })).parts, NEW_PARTS)

// 行号片段不算「空片段」：它没有路径，但有内容（别看值那一格空不空）
eq('行号片段不算空片段', partEmpty({ type: 'path', value: '', transform: 'INDEX', startAt: 1 }), false)
eq('没填值的取值片段算空片段', partEmpty({ type: 'path', value: '  ' }), true)
eq('只配了一个行号片段的拼接行算配好了',
  isRowConfigured({ ...createRow(1, '', 'bomNo', 'CONCAT'),
    parts: [{ type: 'path', value: '', transform: 'INDEX', startAt: 1 }] }), true)
eq('行号片段进得了规则',
  ruleOf({ ...createRow(1, '', 'bomNo', 'CONCAT'),
    parts: [{ type: 'path', value: '', transform: 'INDEX', startAt: 1 }] }).parts,
  [{ transform: 'INDEX', startAt: 1 }])

// 片段的人话与行级转换共用同一份（`transformLabelFor` 那一个 switch，摘要里看得见参数）
eq('日期片段的人话带着格式串',
  transformLabelFor(partLabelSpec({ type: 'path', value: 'createdTime', transform: 'DATE',
    datePattern: 'yyyyMMdd' }), () => '动作 1 · 读 BOM · createdTime'),
  '日期格式化 · yyyyMMdd（动作 1 · 读 BOM · createdTime）')
eq('行号片段的人话带着起始值',
  transformLabelFor(partLabelSpec({ type: 'path', value: '', transform: 'INDEX', startAt: 10 }), () => ''),
  '行号 · 从 10 起')
eq('常量片段的人话写着值',
  transformLabelFor(partLabelSpec({ type: 'const', value: 'BOM-' }), () => ''), '常量「BOM-」')

// 界面上不出现 $：片段的人话里一个 $ 都没有（#98 对照表）
for (const kind of PART_KINDS) {
  const part = applyPartKind({ type: 'path', value: 'createdTime' }, kind)
  if (kind !== 'CONSTANT') part.value = 'createdTime'
  assertNoDollar(transformLabelFor(partLabelSpec(part), () => 'createdTime'))
}

// ---------------------------------------------------------------- 数组映射（#101）
/** 数组行、子行、外层引用这三件事的入口都在 `source-picker.ts`；`label()` 就是左边清单上那行字。 */
const BOM_SOURCE: SourceField[] = [
  { name: 'product.productCode', type: 'STRING' },
  { name: 'product.productName', type: 'STRING' },
  { name: 'product.bomItems[0].materialCode', type: 'STRING' },
  { name: 'product.bomItems[0].qty', type: 'NUMBER' },
]

/** 左边清单取出来的一项（点它就是按钮上的 onclick）。`step` 是这批字段来自哪个动作。 */
function itemsOf(row: MapRow, group: MapRow | null = null, step: number | null = 1): PickableItem[] {
  return sourcePickerFor(row, BOM_SOURCE, group, step).flatMap(g => g.items)
}

/** 点一下左边清单里的某一项，返回填完的行。 */
function pickInto(entry: MapRow, item: PickableItem, isChildRow = false): MapRow {
  const result = fillRowFromPick(entry, item, isChildRow)
  if (!result.row) throw new Error(`没填进去：${item.label}｜${result.reason}`)
  return result.row
}

const bomArrayRow = createGroupRow(1, '', 'bom.bomLines')
const bomChildRow = createRow(null, '', 'materialCode', 'STRING')

// 数组行上能选一个来源数组——来源清单里正好有它，点一下就是这一行的来源
eq('数组行的来源清单只留数组', itemsOf(bomArrayRow).map(i => i.label), ['product.bomItems'])
eq('点一下数组字段就填进数组行', pickInto(bomArrayRow, itemsOf(bomArrayRow)[0]).source, 'product.bomItems')
eq('数组行不接受普通字段（核心里是两个东西：数组路径 vs 相对路径）',
  fillRowFromPick(bomArrayRow, item('product.productCode')).reason,
  'array-expects-array')

// 子行的来源默认按相对路径解析，清单上只给数组元素内的字段
eq('子行的来源清单开头是「数组元素内」分组',
  sourcePickerFor(bomChildRow, BOM_SOURCE, bomArrayRow)[0].title, '数组元素内')
eq('数组元素内的字段按相对路径给（不带数组前缀）',
  sourcePickerFor(bomChildRow, BOM_SOURCE, bomArrayRow)[0].items.map(i => i.label), ['materialCode', 'qty'])
eq('子行填相对路径时存的就是相对路径',
  pickInto(bomChildRow, itemsOf(bomChildRow, bomArrayRow)[0], true).source, 'materialCode')
// 子行还能引用数组外层的表头字段，界面上显示成「外层 · 字段名」
const outerRow = createRow(null, '', 'lineNo', 'STRING')
const outerItem = itemsOf(outerRow, bomArrayRow).find(i => i.label === '外层 · product.productName')
ok('子行的来源清单里有「外层 · 字段名」这一项', !!outerItem)
eq('外层引用存成 $root 前缀（编译器据此取这份来源数据的顶层）',
  pickInto(outerRow, outerItem!, true).source, '$root.product.productName')
eq('外层引用在界面上显示成「外层 · 字段名」',
  rowReferenceLabel(pickInto(outerRow, outerItem!, true), { 1: '读 BOM' }, true), '外层 · product.productName')
eq('外层引用的悬停提示带上取自哪个动作',
  rowReferenceFullLabel(pickInto(outerRow, outerItem!, true), { 1: '读 BOM' }, 1),
  '动作 1 · 读 BOM · 外层 · product.productName')
eq('元素字段之后才是外层字段（数组元素内排最前）',
  itemsOf(outerRow, bomArrayRow).map(i => i.label),
  ['materialCode', 'qty', '外层 · product.productCode', '外层 · product.productName'])
eq('外层引用自己不存来源步骤（子规则带 sourceStep 后端会拒）', [outerItem!.step, outerItem!.path],
  [null, 'product.productName'])
eq('填上外层引用之后行上也不留 sourceStep',
  pickInto(outerRow, outerItem!, true).sourceStep, null)
eq('数组行那一项也带着来源步骤（顶层行的取值依据）',
  itemsOf(bomArrayRow).map(i => [i.step, i.path]), [[1, 'product.bomItems']])
eq('界面文案不出现 $（外层引用的内部写法不上界面）',
  sourcePickerFor(outerRow, BOM_SOURCE, bomArrayRow).flatMap(g => g.items.map(i => i.label))
    .every(text => !text.includes('$')),
  true)

// 还没选来源数组的数组行：元素字段还没法按相对路径给出来，清单得说清为什么
const sourceLessArray = createGroupRow(1, '', 'bom.bomLines')
const arrayWithoutSource = sourcePickerFor(null, BOM_SOURCE, sourceLessArray)
eq('数组元素子行还没选来源数组时，「数组元素内」是空的',
  [arrayWithoutSource[0].title, arrayWithoutSource[0].items.length], ['数组元素内', 0])
ok('空分组带着一句说明（为什么这里是空的）', (arrayWithoutSource[0].tip ?? '').length > 0)
eq('「外层」照样给出来（表头字段不依赖数组选没选）', arrayWithoutSource[1].items.length > 0, true)
eq('元素字段按相对路径给出来了，数组行本身没选来源数组也不影响外层',
  arrayWithoutSource[1].items.map(i => i.path), ['product.productCode', 'product.productName'])

// 源结构里没有独立的数组字段时（探到的是 items[0].x 这种展开路径），数组行也要能选到它
eq('数组只有元素字段时，按元素字段推出数组本身',
  itemsOf(createGroupRow(1, '', 'bom.bomLines')).map(i => [i.label, i.path]), [['product.bomItems', 'product.bomItems']])

// 没填进去时要说清是哪一种接不上（界面上直接显示这句）
eq('数组行点了普通字段的提示',
  emptyPickResult({ row: null, reason: 'array-expects-array' }).message,
  '这一行是数组映射，来源要选一个数组')
eq('子行点了数组元素外的字段的提示',
  emptyPickResult({ row: null, reason: 'child-wants-relative' }).message,
  '数组子行的来源写的是相对路径——用数组元素内的字段，或「外层 · 」里的表头字段')
eq('没选行时的提示', emptyPickResult({ row: null, reason: 'no-target' }).message, '先选一行要填的目标字段')

// 点来源字段之后自动走到下一个空行（子行也在候选里：数组里几个字段是一路往下填的）
const bomArrayForFlow = createGroupRow(1, '', 'bom.bomLines')
bomArrayForFlow.children = [createRow(null, '$index', 'lineNo', 'INDEX'), createRow(null, '', 'materialCode', 'STRING')]
const afterPick = pickInto(bomArrayForFlow, itemsOf(bomArrayForFlow)[0])
eq('点完来源数组后落在第一个空行上（数组行自己不是候选）',
  nextEmptyRow([bomArrayForFlow], afterPick)?.target, 'materialCode')

// 展开 / 收起：数组行下面才有子行，收起时子行不铺出来
const collapseTarget: TargetField[] = [{
  path: 'bom.bomLines', type: 'ARRAY', required: true, element: [
    { path: 'lineNo', type: 'INT', required: true },
    { path: 'materialCode', type: 'VARCHAR', required: true },
  ],
}]
const collapseRows = rowsFromTargetFields(collapseTarget, 1)
const shown = new Set<number>()
eq('默认全部展开', visibleRows(collapseRows, shown).map(e => e.row.target),
  ['bom.bomLines', 'lineNo', 'materialCode'])
eq('收起后只剩数组行自己', visibleRows(collapseRows, toggledRowIds(collapseRows, shown)).map(e => e.row.target),
  ['bom.bomLines'])
eq('再展开一次又都回来了',
  visibleRows(collapseRows, toggledRowIds(collapseRows, toggledRowIds(collapseRows, shown)))
    .map(e => e.row.target),
  ['bom.bomLines', 'lineNo', 'materialCode'])

// 加子行：子行的目标字段写相对路径
const blankArray = createGroupRow(1, 'product.bomItems', 'bom.bomLines')
eq('空数组行加一个子行', appendChildRow(blankArray).children.map(c => c.target), [''])
eq('子行只有相对路径（数组前缀不写进来）',
  appendChildRow(appendChildRow(blankArray)).children
    .every(c => !c.target.includes('.') && !c.target.includes('[')),
  true)
eq('子行不存 sourceStep（后端校验要求子规则不带）', appendChildRow(blankArray).children[0].sourceStep, null)
eq('不是数组行就没有子行可加', appendChildRow(createRow(1, 'a', 'bom.bomNo', 'STRING')).children.length, 0)

ok('普通行不会给出「外层 · 」（它不是数组元素，没有外面那一层）',
  !itemsOf(createRow(1, '', 'bom.bomNo', 'STRING')).some(i => i.label.startsWith('外层 · ')))

// ---------------------------------------------------------------- 制品 → BOM（#101 验收场景）

const BOM_TARGETS: TargetField[] = [
  { path: 'sku', type: 'VARCHAR', required: true },
  { path: 'name', type: 'VARCHAR', required: true },
  { path: 'items', type: 'ARRAY', required: true, element: [
    { path: 'itemCode', type: 'VARCHAR', required: true },
    { path: 'itemName', type: 'VARCHAR', required: true },
    { path: 'qty', type: 'INT', required: true },
    { path: 'unit', type: 'VARCHAR', required: true },
  ] },
]
/** 来源是一张 BOM 表：表头字段 + 一个 items 数组（元素路径由结构探测展开成 `[0]`）。 */
const BOM_SAMPLE: SourceField[] = [
  { name: 'product.productCode', type: 'STRING' },
  { name: 'product.productName', type: 'STRING' },
  { name: 'product.bomItems[0].itemCode', type: 'STRING' },
  { name: 'product.bomItems[0].itemName', type: 'STRING' },
  { name: 'product.bomItems[0].qty', type: 'NUMBER' },
  { name: 'product.bomItems[0].unit', type: 'STRING' },
]

/** 目标清单里那个数组字段 → 一行数组映射（子行照清单铺开）。 */
function bomArrayRowOf(): MapRow {
  const rows = rowsFromTargetFields(BOM_TARGETS, 1)
  // 目标路径按约定的 BOM 结构摆放：顶层三个字段 + bom.bomLines 数组。
  const top = rows.slice(0, 2)
  for (const [i, path] of ['bom.sku', 'bom.name'].entries()) top[i].target = path
  const array = rows[2]
  array.target = 'bom.bomLines'
  return array
}

/** 在左边清单上按那一行字找一项，点进去。`step` 是编辑器当前绑定的默认来源动作。 */
function bomPick(row: MapRow, label: string, group: MapRow | null = null, step = 1): MapRow {
  const items = sourcePickerFor(row, BOM_SAMPLE, group, step).flatMap(g => g.items)
  const item = items.find(i => i.label === label)
  if (!item) {
    throw new Error(`清单里没有：${label}｜清单是 ${JSON.stringify(items.map(i => i.label))}`
      + `｜组来源 ${JSON.stringify(group?.source)}`)
  }
  return pickInto(row, item, group !== null)
}

/** 五列的数组行：先把来源步骤挪到「任务输入」，好看清是**选来源数组**这一步把它定下来的。 */
const bomArray = bomArrayRowOf()
bomArray.sourceStep = 0
eq('数组行选到来源数组', bomPick(bomArray, 'product.bomItems').source, 'product.bomItems')
eq('选来源数组时把这一行的来源步骤定成那个动作（不是留在原地的任务输入）', bomArray.sourceStep, 1)
eq('子行的目标字段写的都是相对路径', bomArray.children.map(c => c.target),
  ['itemCode', 'itemName', 'qty', 'unit'])
eq('子行按相对路径取数组元素内的字段',
  bomArray.children.map(c => bomPick(c, c.target, bomArray).source), ['itemCode', 'itemName', 'qty', 'unit'])

// 五列的目标字段：行号 + 三个元素字段 + 一个外层表头字段
const bomLineNo = bomArray.children[0]
bomLineNo.target = 'lineNo'
applyTransform(bomLineNo, 'INDEX', { startAt: 1 })
eq('行号行取行号（从 1 起）', [bomLineNo.transform, bomLineNo.source, bomLineNo.startAt], ['INDEX', '$index', 1])
// 数组元素里没有这个字段，取外层表头要显式走「外层 · 」
eq('子行里也能引用数组外层的表头字段',
  bomPick(bomArray.children[1], '外层 · product.productName', bomArray).source, '$root.product.productName')
// 子行一概不存 sourceStep：元素字段（相对路径）与外层引用都不存——后端校验要求子规则不带。
ok('子行一个都不带 sourceStep',
  bomArray.children.every(c => c.sourceStep === null),
  JSON.stringify(bomArray.children.map(c => c.sourceStep)))
eq('子行填了来源后都算配好', bomArray.children.slice(1).every(isRowConfigured), true)

// 保存后的规则形状：数组那一行带 sourceStep，子规则不带（后端校验要求子规则不带）
const bomRules = configFromRows([bomArray]).rules
eq('数组那一行带 sourceStep', bomRules[0].sourceStep, 1)
ok('子规则一个都不带 sourceStep', bomRules[0].children.every((c: Record<string, any>) => c.sourceStep === undefined))
eq('数组规则是 iterate=FOR_EACH 加 children', [bomRules[0].iterate, bomRules[0].children.length], ['FOR_EACH', 4])
eq('数组行自己的来源是来源数组路径（不是相对路径）', bomRules[0].source, 'product.bomItems')
eq('外层引用原样存成 $root 前缀（编译器据此还原成那个动作的根对象）',
  bomRules[0].children[1].source, '$root.product.productName')
ok('子行目标字段里没有数组下标（相对路径就是字段名）',
  bomRules[0].children.every((c: Record<string, any>) => !c.target.includes('[0]')))
// 编译产物里数组用 $map 生成、外层的 [ ] 由 JsonataRuleCompiler 加——那一条在
// JsonataRuleCompilerTest 里断言（前端不复制编译器，也不复制编译产物 #98 D1）

// ---------------------------------------------------------------- 手工新增行（#102）

// 清除一行：清的是这一行「值怎么取」，目标字段留着（它是这一行的身份），撤销能原样拿回来。
const clearDateRow = leaf('createdAt', 'bom.createdDate', 'DATE', { datePattern: 'yyyyMMdd' })
eq('配过的行有东西可清除', hasMapping(clearDateRow), true)
const clearDateSnapshot = clearRowMapping(clearDateRow)
eq('清除后目标字段留着（清除的是取值方式，不是这一行）', clearDateRow.target, 'bom.createdDate')
eq('清除后来源、转换、参数都没了',
  pick(clearDateRow, 'enumMap', 'parts', 'source', 'startAt', 'transform'),
  [null, [], '', null, 'STRING'])
eq('清除后这一行不再算配好（也就不会写成规则）', isRowConfigured(clearDateRow), false)
ok('清除时拿到撤销用的快照', clearDateSnapshot !== null)
restoreRowMapping(clearDateRow, clearDateSnapshot!)
eq('撤销后规则原样回来', ruleOf(clearDateRow),
  { sourceStep: 1, source: 'createdAt', target: 'bom.createdDate', transform: 'DATE', datePattern: 'yyyyMMdd' })

const enumToClear = leaf('unit', 'unit', 'ENUM', { enumMap: { PCS: '件' } })
clearRowMapping(enumToClear)
eq('清除也把枚举表清掉（否则下一行的枚举表是上一行留下的）', enumToClear.enumMap, null)
const indexToClear = childOf('$index', 'INDEX', { startAt: 10 })
clearRowMapping(indexToClear)
eq('清除行号行：转换回默认，起始值与 $index 一起清掉',
  pick(indexToClear, 'source', 'startAt', 'transform'), ['', null, 'STRING'])

eq('没配过的行没什么可清除（界面上也就不会给一个空的撤销）',
  clearRowMapping(createRow(1, '', 'x', 'STRING')), null)
eq('只是没选来源的空行也没什么可清除（它本来就没有映射）',
  hasMapping(createRow(1, '', 'x', 'STRING')), false)

// 数组行：清除的是「来源数组」，子行留着（子行是这一行的内容，不是它的取值方式）
const arrayToClear = rowFromRule(TOP_RULES[2])
const arraySnapshot = clearRowMapping(arrayToClear)
eq('数组行清除后子行一条不少', arrayToClear.children.length, 5)
eq('数组行清除后不再算配好（来源数组没了，子行配好也不算）', isRowConfigured(arrayToClear), false)
restoreRowMapping(arrayToClear, arraySnapshot!)
eq('撤销后数组行的来源数组回来', ruleOf(arrayToClear).source, 'items')
eq('撤销后子行也还在', [arrayToClear.iterate, arrayToClear.children.length], ['FOR_EACH', 5])
eq('撤销之后整条规则与原来一模一样', ruleOf(arrayToClear), TOP_RULES[2])

// 手工新增行：目标字段清单不全的时候也要能把配置配完（#102）
/** 目标清单里那个数组字段对应的行（`rowsFromTargetFields` 铺出来的那一行）。 */
const TARGET_ARRAY = targetRows[3]
const manualRows: MapRow[] = []
const manualLeaf = appendLeafRow(manualRows, 1)
eq('「+ 字段映射」把空行加在列表最后', manualRows.map(r => r.id), [manualLeaf.id])
eq('新加的是普通行（不是数组映射）', [manualLeaf.iterate, manualLeaf.target, manualLeaf.source], [null, '', ''])
eq('新行的默认来源步骤跟着编辑器顶部那个下拉', manualLeaf.sourceStep, 1)
eq('行上带上了它的补全清单（普通行给清单里的标量字段）',
  targetCandidatesFor(TARGETS, { row: manualLeaf, depth: 0, parent: null }),
  ['bom.bomNo', 'bom.productCode', 'bom.remark'])

const manualArray = appendArrayRow(manualRows, 1)
eq('「+ 数组映射」加的是数组行（目标写数组路径，来源要选一个数组）',
  [manualArray.iterate, manualArray.target, manualArray.source], ['FOR_EACH', '', ''])
eq('数组行的补全清单只给清单里的数组字段',
  targetCandidatesFor(TARGETS, { row: manualArray, depth: 0, parent: null }), ['bom.bomLines'])
eq('数组元素子行的补全清单是这个数组元素里的相对路径',
  targetCandidatesFor(TARGETS, { row: createRow(null, '', '', 'STRING'), depth: 1, parent: TARGET_ARRAY }),
  ['lineNo', 'materialCode'])
eq('手工加的数组行不在清单里时，子行没有可补全的（目标只能手输）',
  targetCandidatesFor(TARGETS, { row: createRow(null, '', '', 'STRING'), depth: 1, parent: manualArray }), [])

// 手输的路径（清单里没有的）照样写进规则——清单不全时这就是唯一的出路，也是后端校验要看的草稿
const typedRow = appendLeafRow([], 1)
typedRow.target = 'custom.deep.path'
typedRow.source = 'order_no'
eq('手输的目标字段路径照样进规则',
  configFromRows([typedRow]).rules.map(r => r.target), ['custom.deep.path'])
eq('手输路径的行在清单之外，界面上标成「手写」',
  targetCandidatesFor(TARGETS, { row: typedRow, depth: 0, parent: null }).includes('custom.deep.path'), false)

// 清单是编辑器真拿得到的那两种形状（结构探测给的是扁平路径），所以补全也要按真链路走一遍：
// 探测路径 → 折成目标清单 → 铺成行 → 这一行能补全出什么。
const probeFlat = targetFieldsFromPaths(['order_no', 'qty', 'unit'])
const flatRows = rowsFromTargetFields(probeFlat, 1)
eq('探测出来的扁平列名都当标量字段（普通行的补全就是它们）',
  targetCandidatesFor(probeFlat, { row: flatRows[0], depth: 0, parent: null }), ['order_no', 'qty', 'unit'])

const probeArray = targetFieldsFromPaths(['items[0].itemCode', 'items[0].qty', 'sku'])
const probedRows = rowsFromTargetFields(probeArray, 1)
eq('探测出来的数组字段折成一行数组映射', [probedRows[0].target, probedRows[0].iterate], ['items', 'FOR_EACH'])
eq('数组行能从清单里选到它自己',
  targetCandidatesFor(probeArray, { row: probedRows[0], depth: 0, parent: null }), ['items'])
eq('数组元素子行的补全是这个数组元素里的相对路径',
  targetCandidatesFor(probeArray, { row: probedRows[0].children[0], depth: 1, parent: probedRows[0] }),
  ['itemCode', 'qty'])

// 数组行下面的两个入口：+ 子字段（相对路径）与 + 行号（#102）
const childActions = createGroupRow(1, 'product.bomItems', 'bom.bomLines')
const indexChild = appendIndexChildRow(childActions).children[0]
eq('「+ 行号」加的是行号子行（来源 $index、从 1 起、目标从空开始）',
  [indexChild.target, indexChild.transform, indexChild.source, indexChild.startAt],
  ['', 'INDEX', '$index', 1])
eq('行号子行不存 sourceStep（子规则带上后端会拒）', indexChild.sourceStep, null)
eq('「+ 子字段」加的仍是普通子行（来源写相对路径）',
  appendChildRow(childActions).children.map(c => c.transform), ['INDEX', 'STRING'])
eq('不是数组行就没有「+ 行号」可加',
  appendIndexChildRow(createRow(1, 'a', 'b', 'STRING')).children.length, 0)
indexChild.target = 'lineNo'
eq('行号子行填上目标字段就能存（source 固定 $index、startAt 写出来）',
  ruleOf(indexChild, true), { source: '$index', target: 'lineNo', transform: 'INDEX', startAt: 1 })

// ---------------------------------------------------------------- 校验配置（#103）

// 发出去的是「按现在这样保存会写进 rules 的那份规则」——前端不自己判对错，只是把草稿交出去。
const DRAFT_CONFIGURED = leaf('bomNo', 'bom.bomNo', 'STRING')
const DRAFT_BLANK = createRow(1, '', 'bom.remark', 'STRING')
const draftBody = draftRequest({
  rows: [DRAFT_CONFIGURED, DRAFT_BLANK],
  targets: TARGETS,
  sourceFields: [UNIT_FIELD],
  sourceFieldsStep: 1,
  earlierActionIndexes: [1],
  defaultSourceStep: 1,
  actionIndex: 2,
})
eq('草稿就是「保存会写进去的那份规则」（没配好的行不进草稿）',
  (draftBody.draft.rules ?? []).map((r: Record<string, any>) => r.target), ['bom.bomNo'])
eq('草稿里的顶层规则显式带着 sourceStep',
  (draftBody.draft.rules ?? []).map((r: Record<string, any>) => r.sourceStep), [1])
eq('请求带上链上更早的动作序号（sourceStep 只能指向这些）', draftBody.earlierActionIndexes, [1])
eq('请求带上默认来源与这个动作自己的序号',
  [draftBody.defaultSourceStep, draftBody.actionIndex], [1, 2])
eq('请求带上目标清单与来源样例（基于样例的那几条检查要用）',
  [draftBody.targets.length, draftBody.sourceFields.length], [TARGETS.length, 1])
// 只有这样样例是哪一步的，后端才判得了「来源字段在不在样例里」：只带一份样例，
// 拿它去判别的步骤的规则必然误报（顶部一换默认来源、已配的行留在原步骤时就会整片误报）
eq('请求还带上这份样例是哪个动作的探测结果', draftBody.sourceFieldsStep, 1)

// 后端给的问题 → 界面上哪一行：靠「目标字段 + 它所属的数组行」，两者都一样才是同一行。
const MATERIAL_CHILD = createRow(null, '', 'materialCode', 'STRING')
const MATERIAL_TOP = leaf('materialCode', 'materialCode', 'STRING')
const ISSUE_ARRAY = createGroupRow(1, 'product.bomItems', 'bom.bomLines')
ISSUE_ARRAY.children = [createRow(null, '', 'lineNo', 'STRING'), MATERIAL_CHILD]
const ISSUE_ROWS: MapRow[] = [leaf('bomNo', 'bom.bomNo', 'STRING'), MATERIAL_TOP, ISSUE_ARRAY]

const DRAFT_RESULT: DraftValidation = {
  valid: false,
  errors: [
    { target: null, parent: null, message: '字段映射动作需要至少有一条规则' },
    { target: 'materialCode', parent: null, message: '映射规则缺少来源字段 source' },
    { target: 'materialCode', parent: 'bom.bomLines', message: '枚举转换需要 enumMap' },
  ],
  warnings: [{ target: 'lineNo', parent: 'bom.bomLines', message: '来源字段不在样例里' }],
  compiled: { jsonata: null, rulesJson: '{}' },
}

const located = locateIssues(flattenRows(ISSUE_ROWS), DRAFT_RESULT)
eq('错误在前、警告在后', located.map(i => i.severity), ['error', 'error', 'error', 'warning'])
eq('定位不到行的问题照样列出来（整份草稿级别）', located[0].rowId, null)
// 「整份草稿」级别的问题（target/parent 都是 null）不能被扣到一行**还没填目标字段**的空行上
eq('空行不会当上整份草稿级别问题的落点',
  locateIssues(flattenRows([createRow(1, '', '', 'STRING')]), DRAFT_RESULT)[0].rowId, null)
eq('同名的目标字段按「所属数组行」分开：顶层那一行',
  located[1].rowId, MATERIAL_TOP.id)
eq('同名的目标字段按「所属数组行」分开：数组元素内的那一行',
  located[2].rowId, MATERIAL_CHILD.id)
eq('数组子行的问题也定位得到', located[3].rowId, ISSUE_ARRAY.children[0].id)

const byRow = issuesByRow(located)
eq('配好的行是「没问题」', rowIssueState(byRow.get(ISSUE_ROWS[0].id)), 'ok')
eq('有错的行是「有错」', rowIssueState(byRow.get(MATERIAL_TOP.id)), 'error')
eq('有警告的行是「有警告」', rowIssueState(byRow.get(ISSUE_ARRAY.children[0].id)), 'warning')
eq('数组行自己没有问题时是「没问题」（它不替子行背）', rowIssueState(byRow.get(ISSUE_ARRAY.id)), 'ok')
eq('一行上的多条问题都留着', byRow.get(MATERIAL_CHILD.id)?.length, 1)

// 保存被拦下时跳到哪一行：第一条**定位得到**的错误（定位不到的那条跳不过去，跳过它接着找）
eq('跳到第一条错误所在的行', firstErrorRowId(located), MATERIAL_TOP.id)
eq('一条都定位不到时没有可跳的行',
  firstErrorRowId(locateIssues(flattenRows(ISSUE_ROWS), {
    ...DRAFT_RESULT, errors: [{ target: '不存在', parent: null, message: 'x' }], warnings: [],
  })), null)

// 用户加了一行重复的目标字段：后端报的是「后出现的那一条规则」，界面也得挂在后加的那一行上
const dupFirst = leaf('order_no', 'order_no', 'STRING')
const dupSecond = leaf('status', 'order_no', 'STRING')
const dupLocated = locateIssues(flattenRows([dupFirst, dupSecond]), {
  ...DRAFT_RESULT,
  errors: [{ target: 'order_no', parent: null, message: '目标字段重复映射: order_no' }],
  warnings: [],
})
eq('重复目标的错误挂在后加的那一行上（编译器也是在后一条上报的）', dupLocated[0].rowId, dupSecond.id)

// 问题列表里那一列「这条问题在哪」的说法
eq('顶层规则的问题就说目标字段', issueLocation({ target: 'bom.bomNo', parent: null, message: 'x' }), 'bom.bomNo')
eq('数组子行的问题带上它所属的数组行',
  issueLocation({ target: 'materialCode', parent: 'bom.bomLines', message: 'x' }), 'bom.bomLines · materialCode')
eq('定位不到行的说法', issueLocation({ target: null, parent: null, message: 'x' }), '整份配置')

// 跳到收起的数组子行：得先把它上面的数组行展开，否则那一行根本铺不出来
eq('一行的祖先数组行', ancestorRowIds(ISSUE_ROWS, MATERIAL_CHILD.id), [ISSUE_ARRAY.id])
eq('顶层行没有祖先', ancestorRowIds(ISSUE_ROWS, MATERIAL_TOP.id), [])

// ---------------------------------------------------------------- 依赖样例与目标清单的问题（#104）

// 后端新报的那几条（必填没配来源 / 来源不在样例里 / 枚举没覆盖样例值 / 文本写进数字字段 / 拼接有空片段）
// 与 #103 那几条走的是同一条定位通路：前端**一条规则都不复制**，只按「目标字段 + 所属数组行」摆到行上。
// 这里锁的是那条通路：新问题必须落在正确的行右侧、进得了问题列表、点得着跳得过去。
const SAMPLE_ISSUES: DraftValidation = {
  valid: true,
  errors: [],
  warnings: [
    // #113：必填没配、来源不在样例里这两条由错误降成警告——后端不再拦保存，行上也就只是提醒
    { target: 'bom.remark', parent: null, message: '必填的目标字段没有配置来源: bom.remark' },
    { target: 'materialCode', parent: 'bom.bomLines', message: '来源字段不在样例里: materialCodex' },
    { target: 'bom.bomNo', parent: null, message: '枚举表没有覆盖样例里出现过的值: SET' },
    { target: 'qty', parent: 'bom.bomLines', message: '目标字段 bom.bomLines.qty 是数字类型（INT）' },
    { target: 'materialName', parent: 'bom.bomLines', message: '拼接里有 1 个空片段' },
  ],
  compiled: { jsonata: null, rulesJson: '{}' },
}

// 一份「必填字段没配来源」的草稿长什么样：那一行还在编辑器里，只是没进草稿
const UNCONFIGURED_REQUIRED = createRow(1, '', 'bom.remark', 'STRING')
// 数组那一行照旧带上几个子行：数字类型与拼接空片段这两条要落在子行上
const SAMPLE_ARRAY = createGroupRow(1, 'product.bomItems', 'bom.bomLines')
SAMPLE_ARRAY.children = [
  createRow(null, 'materialCodex', 'materialCode', 'STRING'),
  createRow(null, 'quantity', 'qty', 'STRING'),
  createRow(null, 'materialCode', 'materialName', 'CONCAT'),
]
const SAMPLE_ROWS: MapRow[] = [leaf('bomNo', 'bom.bomNo', 'STRING'), UNCONFIGURED_REQUIRED, SAMPLE_ARRAY]
const sampleLocated = locateIssues(flattenRows(SAMPLE_ROWS), SAMPLE_ISSUES)

eq('「必填没配」的警告落在那一行上', sampleLocated[0].rowId, UNCONFIGURED_REQUIRED.id)
eq('「来源不在样例」的警告落在数组元素那一行上', sampleLocated[1].rowId, SAMPLE_ARRAY.children[0].id)
eq('样例检查的警告按行定位（枚举没覆盖）', sampleLocated[2].rowId, SAMPLE_ROWS[0].id)
eq('样例检查的警告按行定位（数字类型）', sampleLocated[3].rowId, SAMPLE_ARRAY.children[1].id)
eq('样例检查的警告按行定位（拼接空片段）', sampleLocated[4].rowId, SAMPLE_ARRAY.children[2].id)

// 行右侧那三个状态照样由问题的级别算：错误压过警告
const sampleByRow = issuesByRow(sampleLocated)
eq('必填没配的行右侧显示「有警告」（#113：这一档不再拦保存）',
  rowIssueState(sampleByRow.get(UNCONFIGURED_REQUIRED.id)), 'warning')
eq('有警告没错误的行右侧显示「有警告」', rowIssueState(sampleByRow.get(SAMPLE_ROWS[0].id)), 'warning')
const warnOnly = { ...SAMPLE_ISSUES, errors: [], valid: true }
eq('只有警告时 valid 仍是真（保存不被拦）', warnOnly.valid, true)
eq('一条都没报的行仍是「没问题」', rowIssueState(sampleByRow.get(SAMPLE_ARRAY.id)), 'ok')

// 保存被拦时跳第一条**定位得到**的错误：只有错误才算数（警告跳不过去，也不该拦）
eq('没有错误时没有可跳的行', firstErrorRowId(sampleLocated), null)

// 问题列表里那一列的说法：数组元素里的问题带上它所属的数组行，才认得出是哪一行
eq('数组元素里的必填没配：说法带上数组行',
  issueLocation({ target: 'qty', parent: 'bom.bomLines', message: '必填的目标字段没有配置来源: qty' }),
  'bom.bomLines · qty')

// 界面文案里不出现 $：这几条新问题的原文都是后端给的整句中文
SAMPLE_ISSUES.errors.concat(SAMPLE_ISSUES.warnings).forEach(issue => assertNoDollar(issue.message))

// ---------------------------------------------------------------- 校验两档 + 清掉的行（#113）

// 一、一行上的**全部**问题都留在这一行上（不再只显示第一条、其余藏进悬停）。
// 后端那边「结构错与链问题 = 错误，样例类 = 警告」的分法由 mapping 那套 fixture 断言（前端一条规则都不复制）；
// 这里锁的是界面上那条通路：同一行上的多条问题一条都不少，错误在前、警告在后。
const MANY_ISSUES: DraftValidation = {
  valid: false,
  errors: [{ target: 'qty', parent: 'bom.bomLines', message: '目标字段重复映射: qty' }],
  warnings: [
    { target: 'qty', parent: 'bom.bomLines', message: '必填的目标字段没有配置来源: qty' },
    { target: 'qty', parent: 'bom.bomLines', message: '目标字段 bom.bomLines.qty 是数字类型（INT）' },
  ],
  compiled: { jsonata: null, rulesJson: '{}' },
}
const MANY_ROWS: MapRow[] = [leaf('bomNo', 'bom.bomNo', 'STRING')]
const MANY_ARRAY = createGroupRow(1, 'items', 'bom.bomLines')
MANY_ARRAY.children = [createRow(null, 'quantity', 'qty', 'INT')]
MANY_ROWS.push(MANY_ARRAY)
const manyLocated = locateIssues(flattenRows(MANY_ROWS), MANY_ISSUES)
eq('同一行上的三条问题一条都不少', manyLocated.length, 3)
eq('三条都落在那一行上',
  manyLocated.every(issue => issue.rowId === MANY_ARRAY.children[0].id), true)
eq('错误排在警告前面', manyLocated.map(i => i.severity), ['error', 'warning', 'warning'])
const manyByRow = issuesByRow(manyLocated)
eq('行右侧那三种状态照样算得出来（错误压过警告）',
  rowIssueState(manyByRow.get(MANY_ARRAY.children[0].id)), 'error')
eq('一行上的多条问题都留给这一行', manyByRow.get(MANY_ARRAY.children[0].id)?.length, 3)

// 只有警告时保存不该被拦：跳第一条错误跳不动（没有错误可跳）
eq('只有警告时 firstErrorRowId 返回 null',
  firstErrorRowId(locateIssues(flattenRows(MANY_ROWS), { ...MANY_ISSUES, errors: [] })), null)

// 二、清掉的行：保存不再被拦，但那一行仍然提醒「这个必填字段没有配置来源」。
// 后端报得出同一句话（那一行没进草稿，清单里的必填字段就没着落），但那要等人点一次「校验配置」；
// 清完那一刻就该看见，所以这句提醒由编辑器自己出，措辞与后端那条逐字一致。
const REQUIRED_FOR_CLEAR: TargetField[] = [
  { path: 'sheet_no', type: 'VARCHAR', required: true },
  { path: 'remark', type: 'VARCHAR', required: false },
  {
    path: 'bom.bomLines', type: 'ARRAY', required: true, element: [
      { path: 'qty', type: 'INT', required: true },
    ],
  },
]

const CLEARED_REQUIRED = leaf('orderNo', 'sheet_no', 'STRING')
const clearedRequiredSnapshot = clearRowMapping(CLEARED_REQUIRED)
eq('清掉一个必填目标字段的行：这一行不再算配好（也就进不了草稿）',
  [clearedRequiredSnapshot !== null, isRowConfigured(CLEARED_REQUIRED)], [true, false])
const clearedNotices = clearedRequiredNotices(
  [CLEARED_REQUIRED], REQUIRED_FOR_CLEAR, new Set([CLEARED_REQUIRED.id]))
eq('清掉的那一行得到一条「必填没有配置来源」的提醒',
  clearedNotices.map(n => [n.target, n.parent, n.message]),
  [['sheet_no', null, '必填的目标字段没有配置来源: sheet_no']])

// 后端也会报同一句话——两边挂的是同一行、说的是同一件事，定位口径一致
const clearedLocated = locateIssues(flattenRows([CLEARED_REQUIRED]), {
  valid: true,
  errors: [],
  warnings: clearedNotices.map(n => ({ ...n })),
  compiled: { jsonata: null, rulesJson: null },
})
eq('这条提醒落在被清掉的那一行上', clearedLocated[0].rowId, CLEARED_REQUIRED.id)
eq('只有这条提醒时行右侧是「有警告」，不是「有错」',
  rowIssueState(issuesByRow(clearedLocated).get(CLEARED_REQUIRED.id)), 'warning')

// 三种不该提醒的情况：不是必填、清除后又配回来了、不是用户清掉的行
const CLEARED_OPTIONAL = leaf('orderNo', 'remark', 'STRING')
clearRowMapping(CLEARED_OPTIONAL)
eq('清掉的是非必填字段：不提醒',
  clearedRequiredNotices([CLEARED_OPTIONAL], REQUIRED_FOR_CLEAR, new Set([CLEARED_OPTIONAL.id])).length, 0)

const RESTORED = leaf('orderNo', 'sheet_no', 'STRING')
const restoredSnapshot = clearRowMapping(RESTORED)
restoreRowMapping(RESTORED, restoredSnapshot!)
eq('清掉之后又撤销回来：不提醒',
  clearedRequiredNotices([RESTORED], REQUIRED_FOR_CLEAR, new Set([RESTORED.id])).length, 0)

const NEVER_CLEARED = createRow(1, '', 'sheet_no', 'STRING')
eq('本来就没配过的空行不靠这条提醒（它一直显示「还没选来源」）',
  clearedRequiredNotices([NEVER_CLEARED], REQUIRED_FOR_CLEAR, new Set()).length, 0)

// 数组元素里的必填子字段被清掉：提醒挂在子行上，话里点的是**这条规则的目标字段**（相对路径）
// ——后端 `MappingSampleChecks.missingRequiredTargets` 报的就是它（子规则的 target 是相对路径），
// 一边写 bom.bomLines.qty 一边写 qty，点一次「校验配置」行上的话就变了。
const CLEARED_ARRAY = createGroupRow(1, 'items', 'bom.bomLines')
CLEARED_ARRAY.children = [rowFromRule({ source: 'qty', target: 'qty', transform: 'INT' })]
clearRowMapping(CLEARED_ARRAY.children[0])
eq('数组子行被清掉：提醒挂在子行上、说法带上数组行，话里用相对路径（与后端逐字一致）',
  clearedRequiredNotices([CLEARED_ARRAY], REQUIRED_FOR_CLEAR, new Set([CLEARED_ARRAY.children[0].id]))
    .map(n => [n.target, n.parent, n.message]),
  [['qty', 'bom.bomLines', '必填的目标字段没有配置来源: qty']])

// ---------------------------------------------------------------- 配得快一点（#105）

// 搜索：路径与样例值都搜（票里那两条）。数组元素字段连它所属的数组路径一起比——
// 探到的路径是 `product.bomItems[0].qty`，用户想搜的往往是 `bomItems` 那个名字。
const SEARCH_FIELDS: SourceField[] = [
  { name: 'product.productCode', type: 'STRING', samples: ['DBG-9001'] },
  { name: 'status', type: 'STRING', samples: ['ACTIVE', 'INACTIVE'] },
  { name: 'neverUsed', type: 'STRING' },
  { name: 'product.bomItems[0].materialCode', type: 'STRING', samples: ['MAT-718H'] },
  { name: 'product.bomItems[0].qty', type: 'NUMBER', samples: [24] },
]
const searchItems = sourcePickerFor(null, SEARCH_FIELDS, null, 1).flatMap(g => g.items)
const fieldItem = (path: string) => searchItems.find(i => i.path === path)!
const childSearchItems = sourcePickerFor(bomChildRow, SEARCH_FIELDS, bomArrayRow, 1).flatMap(g => g.items)
/** 数组元素子行清单上的一项（存的是相对路径，所以要连组一起看）。 */
const childSearchItem = (path: string) => childSearchItems.find(i => i.path === path)!

// 数组元素字段也列在常驻清单里：按样例值找字段的人（「这个 MAT-718H 是哪个字段」）不该因为它
// 只能在子行里填就搜不到。列出来 ≠ 填得进去，点进顶层行照样会被 `fillRowFromPick` 挡下。
eq('常驻清单里顶层字段与数组元素字段都在',
  searchItems.map(i => [i.kind, i.path]),
  [
    ['field', 'product.productCode'], ['field', 'status'], ['field', 'neverUsed'],
    ['element', 'product.bomItems[0].materialCode'], ['element', 'product.bomItems[0].qty'],
  ])
ok('按路径搜得到', sourceItemMatches(fieldItem('product.productCode'), 'productCode'))
ok('路径的中间一段也搜得到', sourceItemMatches(fieldItem('product.productCode'), 'product'))
ok('按样例值搜得到（字符串）', sourceItemMatches(fieldItem('status'), 'acti'))
ok('按样例值搜得到（数字样例）', sourceItemMatches(childSearchItem('qty'), '24'))
ok('按样例值搜得到数组元素里的字段（常驻清单上那一项也搜得到）',
  sourceItemMatches(fieldItem('product.bomItems[0].materialCode'), 'MAT-718H'))
ok('样例值搜不到别的字段', !sourceItemMatches(fieldItem('status'), '24'))
ok('数组元素字段按它所属的数组路径也搜得到',
  sourceItemMatches(childSearchItem('qty'), 'bomItems'))
ok('常驻清单上那一项也按数组路径搜得到',
  sourceItemMatches(fieldItem('product.bomItems[0].materialCode'), 'bomItems'))
ok('搜一个没有的名字就没命中', !sourceItemMatches(fieldItem('status'), '不存在的字段'))
eq('空查询词等于不筛', sourceItemMatches(fieldItem('status'), '   '), true)

const searchGroups = sourcePickerFor(null, SEARCH_FIELDS, null, 1)
const childSearchGroups = sourcePickerFor(bomChildRow, SEARCH_FIELDS, bomArrayRow, 1)
eq('按路径搜，清单里只剩命中的',
  filterSourceGroups(childSearchGroups, 'materialCode', 'all', new Map(), new Map())
    .flatMap(g => g.items).map(i => i.path),
  ['materialCode'])
eq('按样例值搜，命中的是那个字段',
  filterSourceGroups(searchGroups, 'INACTIVE', 'all', new Map(), new Map())
    .flatMap(g => g.items).map(i => i.path),
  ['status'])
eq('一条都没命中时分组也不留（界面据此说「没搜到」）',
  filterSourceGroups(searchGroups, 'nothing', 'all', new Map(), new Map()).length, 0)

// 已用高亮与次数：键是**存进行里的那个形状**，不是清单上显示的那个
const usageRows: MapRow[] = [
  leaf('status', 'order_status', 'STRING'),
  leaf('status', 'line_status', 'STRING'),
  leaf('product.productCode', 'sku', 'STRING'),
]
const usage = sourceUsageOf(usageRows)
eq('一个来源被两处引用就是 2', usedCountOf(usage, fieldItem('status')), 2)
eq('引用它的那两行都记着（点一下能跳过去）',
  usedRowIdsOf(usage, fieldItem('status')), [usageRows[0].id, usageRows[1].id])
eq('没被用过的来源是 0', usedCountOf(usage, fieldItem('neverUsed')), 0)
eq('别的步骤的同一个字段名不算它被用了', usedCountOf(usage, item('status', { step: 2 })), 0)
eq('这一步引用了几个就是几个', usedCountOf(usage, fieldItem('status')), 2)
// 相对路径与外层引用不存步骤（子规则带上后端会拒），所以计数也按「不存步骤」那个键算
const outerUsed = sourceUsageOf([{ ...leaf('', 'x', 'STRING'), source: '$root.product.productName', sourceStep: 1 }])
// 外层引用取的是组节点那个动作的顶层对象，所以计数也要带上「是哪一步的清单」。
eq('外层引用按它存进去的样子计数',
  usedCountOf(outerUsed, item('product.productName', { kind: 'outer', root: true, step: 1 }), [], 1), 1)
eq('别的动作下的同一句外层引用不算在它头上',
  usedCountOf(outerUsed, item('product.productName', { kind: 'outer', root: true, step: 2 }), [], 2), 0)
const arrayUsageRows = [rowFromRule({
  sourceStep: 1, source: 'product.bomItems', target: 'bom.bomLines', transform: 'STRING',
  iterate: 'FOR_EACH', children: [{ source: 'materialCode', target: 'materialCode', transform: 'STRING' }],
})]
const arrayUsage = sourceUsageOf(arrayUsageRows)
eq('数组行那一行自己算「来源数组」被用了一处',
  arrayUsage.get(sourceRefKeyId({ step: 1, path: 'product.bomItems' }))?.count, 1)
eq('清单上那个数组项也认得出自己被用了（高亮的依据）',
  usedCountOf(arrayUsage, itemsOf(bomArrayForFlow).find(i => i.kind === 'array')!), 1)
eq('行号行不算任何来源字段被用过',
  sourceUsageOf([childOf('$index', 'INDEX')]).size, 0)
eq('没选来源的行不算', sourceUsageOf([createRow(1, '', 'x', 'STRING')]).size, 0)

// 子行的相对路径：键里带上「这一步 + 这一组」——同一句 `qty` 在别的动作下、别的数组里都不是
// 同一个字段。两条都带上，顶层的多行也各算各的。
const childUsageRows = createGroupRow(1, 'product.bomItems', 'bom.bomLines')
childUsageRows.children = [createRow(null, 'materialCode', 'materialCode', 'STRING')]
const childUsage = sourceUsageOf([childUsageRows])
eq('子行的相对路径也算「这个来源被用了一处」',
  usedCountOf(childUsage, childElement('materialCode'), BOM_SOURCE, 1), 1)
eq('子行记在它自己那一行上，不是数组行',
  usedRowIdsOf(childUsage, childElement('materialCode'), BOM_SOURCE, 1),
  [childUsageRows.children[0].id])
const otherActionUsage = sourceUsageOf([{
  ...createGroupRow(2, 'product.bomItems', 'bom.bomLines'),
  children: [createRow(null, 'materialCode', 'materialCode', 'STRING')],
}])
eq('别的动作下的同一句相对路径不算在它头上',
  usedCountOf(otherActionUsage, childElement('materialCode'), BOM_SOURCE, 1), 0)
eq('那一步的这一句才算（组节点是动作 2 的那一份）',
  usedCountOf(otherActionUsage, childElement('materialCode'), BOM_SOURCE, 2), 1)
// 老配置里顶层规则可能没写 `sourceStep`（后端当「任务输入」），它的 `materialCode` 是**另一个**字段，
// 不能跟数组元素里的相对路径合成一处——不然那句「被引用了 2 处」是错的。
const legacyTopUsage = sourceUsageOf([createRow(null, 'materialCode', 'order_no', 'STRING')])
eq('没写来源步骤的顶层同名字段不算在子行那一句头上',
  usedCountOf(legacyTopUsage, childElement('materialCode'), BOM_SOURCE, null), 0)

/** 数组元素子行清单上的一项（`sourcePickerFor` 实际给的那种）。 */
function childElement(name: string): PickableItem {
  return sourcePickerFor(bomChildRow, BOM_SOURCE, bomArrayRow, 1)
    .flatMap(g => g.items).find(i => i.path === name)!
}

eq('「全部」一档什么都不筛',
  filterSourceGroups(searchGroups, '', 'all', usage, new Map()).flatMap(g => g.items).length,
  searchGroups.flatMap(g => g.items).length)
eq('「只看未配置」把用过的摘掉（顶层清单里只剩没被用过的）',
  filterSourceGroups(searchGroups, '', 'unconfigured', usage, new Map())
    .flatMap(g => g.items).filter(i => i.kind === 'field').map(i => i.path),
  ['neverUsed'])
eq('「只看未配置」在一条都没用过时等于全部',
  filterSourceGroups(searchGroups, '', 'unconfigured', new Map(), new Map())
    .flatMap(g => g.items).length,
  searchGroups.flatMap(g => g.items).length)

// 「只看必填」= 还没配好、且目标清单里标了 required 的那些目标字段要用的来源（与进度条同一个口径）。
// 数组目标字段自己也是必填的（「制品 → BOM」里 items 就是必填），它配好之前也在这份清单里。
const REQUIRED_TARGETS: TargetField[] = [
  { path: 'sku', type: 'VARCHAR', required: true },
  { path: 'remark', type: 'VARCHAR', required: false },
  {
    path: 'items', type: 'ARRAY', required: true, element: [
      { path: 'itemCode', type: 'VARCHAR', required: true },
      { path: 'note', type: 'VARCHAR', required: false },
    ],
  },
]
const requiredShape = requiredSourceKeys(rowsFromTargetFields(REQUIRED_TARGETS, 1), REQUIRED_TARGETS, 1)
// 顶层字段与数组字段都要写进行里（那时默认来源是 1），元素字段的相对路径跟着数组行那个步骤。
// 键里带一位「哪一层」：顶层的 `qty` 与数组元素里的 `qty` 不是一回事。
eq('还没配好的必填字段都在（顶层那一步）',
  [...(requiredShape.get(1) ?? [])].sort(),
  [requiredSourceKey('itemCode', true), requiredSourceKey('items', false),
    requiredSourceKey('sku', false)].sort())
eq('数组元素里的必填字段跟着数组行那个步骤（数组行还没选来源数组时跟默认来源走）',
  [...requiredSourceKeys(rowsFromTargetFields(REQUIRED_TARGETS, 2), REQUIRED_TARGETS, 2).get(2) ?? []].sort(),
  [requiredSourceKey('itemCode', true), requiredSourceKey('items', false),
    requiredSourceKey('sku', false)].sort())
const halfFilled = rowsFromTargetFields(REQUIRED_TARGETS, 1)
halfFilled[0].source = 'product.productCode'
eq('配好的必填字段不再算「还差」',
  [...(requiredSourceKeys(halfFilled, REQUIRED_TARGETS, 1).get(1) ?? [])].sort(),
  [requiredSourceKey('itemCode', true), requiredSourceKey('items', false)].sort())
// 数组行选了来源数组之后，元素字段跟着组节点那个动作（这里组节点是 2，所以整份都在 2 那一档）
const arraySourced = rowsFromTargetFields(REQUIRED_TARGETS, 1)
arraySourced[2].sourceStep = 2
eq('数组行选了别的动作的来源数组时，元素字段跟着组节点那一步',
  [...requiredSourceKeys(arraySourced, REQUIRED_TARGETS, 1).get(2) ?? []],
  [requiredSourceKey('itemCode', true)])
const childFilled = rowsFromTargetFields(REQUIRED_TARGETS, 1)
childFilled[2].children[0].source = 'itemCode'
eq('数组元素里配好的那一条也不算了',
  [...(requiredSourceKeys(childFilled, REQUIRED_TARGETS, 1).get(1) ?? [])].sort(),
  [requiredSourceKey('items', false), requiredSourceKey('sku', false)].sort())

// 「只看必填」要能真的筛出东西：那一档给的是**目标字段的名字**，来源与它同名时才显示得出来
// （这正是「同名」的口径——自动匹配也是按同名的）。所以必填目标 `productCode` 对上来源
// `product.productCode`，数组元素里的 `materialCode` / `qty` 对上同名的来源字段。
const NEEDS_ITEMS: TargetField[] = [
  { path: 'productCode', required: true },
  { path: 'status', required: false },
  {
    path: 'bomItems', type: 'ARRAY', required: true, element: [
      { path: 'materialCode', required: true },
      { path: 'qty', required: true },
    ],
  },
]
const needsShape = requiredSourceKeys(rowsFromTargetFields(NEEDS_ITEMS, 1), NEEDS_ITEMS, 1)
eq('「只看必填」按来源步骤分开看（顶层那一步）',
  filterSourceGroups(searchGroups, '', 'required', new Map(), needsShape, 1, false)
    .flatMap(g => g.items).map(i => i.path),
  ['product.productCode'])
eq('「只看必填」在数组子行里按组节点的步骤给（相对路径那一批）',
  filterSourceGroups(sourcePickerFor(bomChildRow, BOM_SOURCE, bomArrayRow, 1), '', 'required',
    new Map(), needsShape, 1, true)
    .flatMap(g => g.items).map(i => i.path),
  ['materialCode', 'qty'])
eq('可选字段不进「只看必填」那一档',
  needsShape.get(1)?.has(requiredSourceKey('status', false)), false)

// 目标字段搜索：搜的是完整路径，数组元素子行带上它所属的数组行（两句 qty 才分得开）
const searchTargetRows: MapRow[] = [
  leaf('a', 'bom.bomNo', 'STRING'),
  createRow(1, '', 'bom.remark', 'STRING'),
  createGroupRow(1, '', 'bom.bomLines'),
]
// 数组行也还没选来源数组（没配好）。子行一个取清单里必填的 `lineNo`（还没配）、
// 一个取清单里的 `materialCode`（配好了）——「只看必填」与「只看未配置」的差别就落在这两行上。
searchTargetRows[2].children = [
  createRow(null, '', 'lineNo', 'STRING'),
  createRow(null, 'x', 'materialCode', 'STRING'),
]
const searchEntries = flattenRows(searchTargetRows)
const allView = targetRowsView(searchEntries, '', 'all', TARGETS)
eq('没搜索词时这一档显示全部有目标字段的行', allView.count, 5)
eq('第一行（配好的那个）也在列表里', allView.rowIds.includes(searchTargetRows[0].id), true)
eq('搜顶层字段',
  targetRowsView(searchEntries, 'bom.remark', 'all', TARGETS).rowIds, [searchTargetRows[1].id])
eq('数组元素子行按「所属数组行 + 相对路径」搜得到',
  targetRowsView(searchEntries, 'bomLines.lineNo', 'all', TARGETS).rowIds, [searchTargetRows[2].children[0].id])
eq('只写相对路径也搜得到（子行自己的目标字段）',
  targetRowsView(searchEntries, 'materialCode', 'all', TARGETS).rowIds, [searchTargetRows[2].children[1].id])
// 搜索按完整路径匹配：数组那一行与它的子行都算命中，别的顶层字段不算
eq('搜数组那一行连它的子行一起命中',
  targetRowsView(searchEntries, 'bomLines', 'all', TARGETS).count, 3)
eq('搜索也说得出命中的行里有几行在数组内',
  targetRowsView(searchEntries, 'bomLines', 'all', TARGETS).inArrayCount, 2)
eq('搜不到时说清是 0', targetRowsView(searchEntries, '不存在', 'all', TARGETS).matched, false)
// 「只看未配置」= 没选来源的行（只填了目标字段但没来源的也算没配）
eq('「只看未配置」只留没配好的行',
  targetRowsView(searchEntries, '', 'unconfigured', TARGETS).rowIds,
  [searchTargetRows[1].id, searchTargetRows[2].id, searchTargetRows[2].children[0].id])
// 「只看必填」认的是**清单里标了 required 的路径**（子行要带上所属数组行才是清单里的路径）：
// 顶层必填的 `bom.bomNo`、数组行自己、以及数组元素里必填的两行都留着；可选的 `bom.remark` 不留。
// 「必填」说的是清单让不让空着，不是说这一行配没配好——配好了也照样看得见。
eq('「只看必填」只留清单里标了 required 的行',
  targetRowsView(searchEntries, '', 'required', TARGETS).rowIds,
  [searchTargetRows[0].id, searchTargetRows[2].id,
    searchTargetRows[2].children[0].id, searchTargetRows[2].children[1].id])
eq('过滤之后再搜（两个条件一起用）',
  targetRowsView(searchEntries, 'bom.bomNo', 'required', TARGETS).count, 1)
// 回车跳行：一路回车在命中项之间走一圈
const loopView = targetRowsView(searchEntries, 'o', 'all', TARGETS)
eq('回车跳到第一个命中', nextMatchRowId(loopView, null), loopView.rowIds[0])
eq('再回车跳到下一个命中', nextMatchRowId(loopView, loopView.rowIds[0]), loopView.rowIds[1])
eq('走到最后一个再回车就转回第一个',
  nextMatchRowId(loopView, loopView.rowIds[loopView.rowIds.length - 1]), loopView.rowIds[0])
eq('没命中时没有可跳的行', nextMatchRowId(targetRowsView(searchEntries, 'zzz', 'all', TARGETS), null), null)

// 刚加进来的空行（还没填目标字段）必须永远铺得出来：搜索与过滤都是围着「目标字段」转的，
// 把它们一起筛掉，用户点完「+ 字段映射」就什么都看不见了——那一行明明是加进去了
//（真机实测踩到过：行加进去了，列表里就是不出现）。
const blankRow = appendLeafRow(searchTargetRows, 1)
const withBlank = flattenRows(searchTargetRows)
eq('空行在「全部」里铺得出来',
  targetRowsView(withBlank, '', 'all', TARGETS).rowIds.includes(blankRow.id), true)
eq('空行在「只看未配置」里也铺得出来（它还没配，当然要看）',
  targetRowsView(withBlank, '', 'unconfigured', TARGETS).rowIds.includes(blankRow.id), true)
eq('空行在「只看必填」里也铺得出来（它可能正要填一个必填字段）',
  targetRowsView(withBlank, '', 'required', TARGETS).rowIds.includes(blankRow.id), true)
eq('搜索时也不把空行筛掉（不然搜一次就找不到刚加的那一行了）',
  targetRowsView(withBlank, 'materialCode', 'all', TARGETS).rowIds.includes(blankRow.id), true)
eq('空行不算「命中」（搜索框旁边那个数字说的是搜到几行有目标字段的行）',
  targetRowsView(withBlank, 'materialCode', 'all', TARGETS).count, 1)
eq('行数统计里也不含空行', targetRowsView(withBlank, '', 'all', TARGETS).total, 5)

// 自动匹配同名：只填空行、不覆盖已经配好的行，整条通路都在浏览器里（这一段一个请求都不发）
// 同名按**最后一段**比：目标 `bomLines.qty` 对来源 `product.bomItems[0].qty`。数组也一样——
// 数组映射行的来源数组要名字对得上（`items` 对 `items`），对不上就留给用户自己选。
const AUTO_FIELDS: SourceField[] = [
  { name: 'productCode', type: 'STRING' },
  { name: 'product.items[0].materialCode', type: 'STRING' },
  { name: 'product.items[0].qty', type: 'NUMBER' },
  { name: 'neverUsed', type: 'STRING' },
]
const autoRows: MapRow[] = [
  createRow(1, '', 'productCode', 'STRING'),
  createRow(1, 'productCode', 'alreadyDone', 'STRING'),
  createRow(1, '', 'nothingMatches', 'STRING'),
  createGroupRow(1, '', 'items'),
]
autoRows[3].children = [createRow(null, '', 'materialCode', 'STRING'), createRow(null, '$index', 'lineNo', 'INDEX')]
const autoResult = autoMatchSameName(autoRows, AUTO_FIELDS, 1)
eq('自动匹配填上了空白的两行（顶层一个 + 数组元素里一个）', autoResult.rowIds,
  [autoRows[0].id, autoRows[3].children[0].id])
eq('填上的来源就是同名的那个', autoRows[0].source, 'productCode')
eq('数组元素里的字段按相对路径填', autoRows[3].children[0].source, 'materialCode')
eq('填上的行带上了这一批字段的来源步骤', autoRows[0].sourceStep, 1)
eq('来源是数字类型时转换跟着猜（与手点一个来源同一个口径）',
  autoRows[3].children[0].transform, 'STRING')
eq('已经配好的行一个字都不动',
  [autoRows[1].source, autoRows[1].transform], ['productCode', 'STRING'])
eq('没有同名字段的行还是空着', autoRows[2].source, '')
eq('同名的数组不算数（数组行留给用户自己选来源数组）', autoRows[3].source, '')
eq('行号行不需要来源，也不会被填', autoRows[3].children[1].source, '$index')
ok('自动匹配不往草稿里塞任何东西（填出来的还是那几行的规则）',
  configFromRows(autoRows).rules.map(r => r.target).includes('alreadyDone'))

// 第二次点「自动匹配同名」不该再改什么：空行已经填过了，剩下的都是对不上的
eq('再点一次没有新的行可填', autoMatchSameName(autoRows, AUTO_FIELDS, 1).rowIds, [])

// 不覆盖已经配好的行：手工填过枚举表的行也算配好（拿一个来源盖上去等于冲掉人工结果）
const handMadeEnum = rowFromRule({ sourceStep: 1, source: '', target: 'status', transform: 'ENUM', enumMap: { A: 'x' } })
eq('手工配过枚举表的行不算空白行', autoMatchSameName([handMadeEnum], [{ name: 'status', type: 'STRING' }], 1).rowIds, [])
eq('常量行本来就不取来源，自动匹配不碰它',
  autoMatchSameName([leaf('', 'kind', 'CONSTANT', { constant: '固定值' })], AUTO_FIELDS, 1).rowIds, [])
eq('没有目标字段的空行不参与', autoMatchSameName([createRow(1, '', '', 'STRING')], AUTO_FIELDS, 1).rowIds, [])

// 拼接行：「来源」是它的片段——空片段按同名补上（顶层行拼元素字段仍然只取第一个元素，不给）
const autoConcat = rowFromRule({
  sourceStep: 1, source: '', target: 'productCode', transform: 'CONCAT',
  parts: ['BOM-', { path: '' }],
})
eq('拼接行的空片段按同名补', autoMatchSameName([autoConcat], AUTO_FIELDS, 1).rowIds, [autoConcat.id])
eq('补上的是那个同名片段', autoConcat.parts, [{ type: 'const', value: 'BOM-' }, { type: 'path', value: 'productCode' }])
eq('已经填了的常量片段不动', autoConcat.parts[0], { type: 'const', value: 'BOM-' })
const childConcat = rowFromRule({ source: '', target: 'materialCode', transform: 'CONCAT', parts: [{ path: '' }] })
const childConcatArray = createGroupRow(1, '', 'items')
childConcatArray.children = [childConcat]
eq('数组元素子行里的拼接片段按相对路径补（组行还没选来源数组也认同名数组）',
  autoMatchSameName([childConcatArray], AUTO_FIELDS, 1).rowIds, [childConcat.id])
eq('补上的是相对路径', childConcat.parts, [{ type: 'path', value: 'materialCode' }])
const noNameConcat = rowFromRule({ sourceStep: 1, source: '', target: 'whatever', transform: 'CONCAT', parts: [{ path: '' }] })
eq('对不上名字的拼接片段不填', autoMatchSameName([noNameConcat], AUTO_FIELDS, 1).rowIds, [])

// 行号片段与加工片段都不是「空片段」：自动匹配同名不该往它们的路径那一格里塞东西（#112）
const indexPartRow = rowFromRule({ sourceStep: 1, source: '', target: 'productCode', transform: 'CONCAT',
  parts: ['LOT-', { transform: 'INDEX', startAt: 1 }] })
eq('只差行号片段的拼接行不算空白行', autoMatchSameName([indexPartRow], AUTO_FIELDS, 1).rowIds, [])
eq('行号片段没被同名来源覆盖', indexPartRow.parts[1], { type: 'path', value: '', transform: 'INDEX', startAt: 1 })

const datedPartRow = rowFromRule({ sourceStep: 1, source: '', target: 'productCode', transform: 'CONCAT',
  parts: [{ path: '', transform: 'DATE', datePattern: 'yyyyMMdd' }] })
eq('加工片段还等着路径，算空白片段', autoMatchSameName([datedPartRow], AUTO_FIELDS, 1).rowIds, [datedPartRow.id])
eq('补上路径时转换与参数留着（补的是路径那一格）', datedPartRow.parts,
  [{ type: 'path', value: 'productCode', transform: 'DATE', datePattern: 'yyyyMMdd' }])

// 自动匹配只认同名：大小写不敏感、按最后一段比（`bomLines.qty` 对 `items[0].qty`）
eq('同名按最后一段比（大小写不敏感）', nameSegment('bom.bomLines.QTY'), 'qty')
// 顶层行拿不到数组元素里的字段（手工点也点不到它），所以对不上就是对不上——不猜
const caseRow = createRow(1, '', 'bom.MaterialCode', 'STRING')
eq('顶层行对不上数组元素里的同名字段（只给完整路径同名的）',
  autoMatchSameName([caseRow], AUTO_FIELDS, 1).rowIds, [])
const caseChild = createRow(null, '', 'MaterialCode', 'STRING')
const caseArray = createGroupRow(1, '', 'items')
caseArray.children = [caseChild]
eq('数组元素子行里大小写不敏感地对上相对路径（数组行还没选来源数组也认，按默认来源里同名的那个数组）',
  autoMatchSameName([caseArray], AUTO_FIELDS, 1).rowIds, [caseChild.id])
eq('填上的是相对路径，不是探测给的完整路径', caseChild.source, 'materialCode')

// 填上的行标成「自动」；校验时给一条提醒确认的提示（票里最后一条）
autoRows[0].auto = true
const autoNotices = autoSourceNotices(autoRows)
eq('自动填上的行给一条提示', autoNotices.length, 1)
eq('提示挂在哪一行（与后端问题同一套定位口径）',
  [autoNotices[0].target, autoNotices[0].parent], ['productCode', null])
ok('提示里说清了「确认之后才算人工结果」', autoNotices[0].message.includes('确认'))
const autoChild = createGroupRow(1, 'items', 'bomLines')
autoChild.children = [createRow(null, 'materialCode', 'materialCode', 'STRING')]
autoChild.children[0].auto = true
eq('数组元素里的提示带上它所属的数组行（界面据此定位到行）',
  autoSourceNotices([autoChild]).map(n => [n.parent, n.target]), [['bomLines', 'materialCode']])
eq('没标「自动」的行没有提示', autoSourceNotices(autoRows).length, 1)

// 「自动」是编辑器里的本地事实：一个字都不进规则（后端那份草稿里也没有这个字段）
autoRows[0].auto = true
eq('「自动」不进规则',
  Object.keys(ruleOf(autoRows[0])).includes('auto'), false)
// 人碰过这一行（手点来源 / 换转换 / 清除）之后就不该再自称自动
const humanTouched = createRow(1, '', 'productCode', 'STRING')
humanTouched.auto = true
markHumanEdited(humanTouched)
eq('人工动过之后「自动」标记掉了', humanTouched.auto, false)
const autoCleared = createRow(1, 'productCode', 'productCode', 'STRING')
autoCleared.auto = true
clearRowMapping(autoCleared)
eq('清除一行之后「自动」标记也掉了', autoCleared.auto, false)
const autoTransformed = createRow(1, 'productCode', 'productCode', 'STRING')
autoTransformed.auto = true
applyTransform(autoTransformed, 'INT')
eq('换过转换之后「自动」标记掉了', autoTransformed.auto, false)

// 票里那条「自动匹配不发起任何网络请求」在这份检查里**不靠读源码断言**（那不是外部行为，
// 也不该把 node 的东西拉进来）：真正的证据在真机那一趟——`.scratch/t7-ui.cjs` 全程盯着后端请求
// 日志，两次「自动匹配同名」都是 0 个请求。这里要保的是「这条通路是纯逻辑」：它只依赖
// `source-picker` 那几个纯函数，没有任何一处 import 过 api 客户端。

// ---------------------------------------------------------------- 手写 JSONata 入口（#106）

// 打开一个动作时进哪一种模式，看的是已存配置里的 mode（与后端 FieldMappingConfig 一个口径：
// 「不是 EXPRESSION 就当 RULES」，文档里那个 STRUCTURED 是旧说法）。
eq('已存配置是手写表达式时，编辑器直接进手写这一边',
  editorModeOf({ mode: 'EXPRESSION', expression: '$s_1.bomNo' }), 'expression')
eq('普通规则配置进逐行配置那一边', editorModeOf({ rules: [] }), 'rows')
eq('没有配置（新建动作）也进逐行配置', editorModeOf(null), 'rows')
eq('mode=EXPRESSION 但表达式空着时也进手写这一边（那是要人去补的）',
  editorModeOf({ mode: 'EXPRESSION', expression: '' }), 'expression')
eq('回填表达式文本', expressionOf({ mode: 'EXPRESSION', expression: '$s_1.bomNo' }), '$s_1.bomNo')
eq('没有表达式时回填空串', expressionOf({ rules: [] }), '')

// 保存/送校验的那份配置：与后端 mode=EXPRESSION 的约定一致（FieldMappingConfig.problems：
// EXPRESSION 必须给非空 expression，且此时不校验规则树）。草稿就是保存会写出去的那一份（#103）。
eq('手写表达式保存成后端认的那份配置',
  expressionConfig('$s_1.bomNo'), { mode: 'EXPRESSION', expression: '$s_1.bomNo', rules: [] })
eq('保存的这份里没有规则树（路由构建时不会拿 rules 去编译）',
  Object.keys(expressionConfig('x')).sort(), ['expression', 'mode', 'rules'])
eq('表达式按用户写的原样保存，不去动它的空白', expressionConfig('  $s_1.a  ').expression, '  $s_1.a  ')
ok('写没写表达式是编辑器里的本地事实（空的不算写了）', !hasExpression('   \n ') && hasExpression('$s_1.a'))

// 两条必须说清的界面话：一条说这条路跳过规则树校验，一条说切回去会被规则树替换掉（票里两条验收）
ok('说明了这条路跳过规则树校验', expressionSkipNotice().includes('跳过规则树校验'))
ok('切回逐行配置时说清了规则树会替换掉手写表达式',
  expressionReplacedNotice().includes('规则树') && expressionReplacedNotice().includes('替换'))
// 切回去看到的是**编辑器里现在这些行**（同一趟会话里没动过它们），不是「按目标字段清单重新生成」
// ——只在「原配置是手写表达式」那一趟里才是按清单生成的（走查时被指出文案与行为不符）
ok('切回的那句话说的是「现在这些行」，没有谎称按清单重新生成',
  expressionReplacedNotice().includes('现在这些行'))

// ---------------------------------------------------------------- 键盘（#106）

// 票里那三个动作都要能不用鼠标完成（换转换 `/`、选来源回车、清除当前行退格），
// 上下走行沿用 #99 的（`ArrowDown` / `ArrowUp`）。这里断言的是「按一下键 = 界面要做的那一件事」。
const KEY_BASE: KeyContext = { inTextEntry: false, hasSelection: true, pickingSource: false, panel: null }
const intent = (key: string, patch: Partial<KeyContext> = {}) => keyIntent(key, { ...KEY_BASE, ...patch })

eq('下键走下一行', intent('ArrowDown'), { kind: 'move', delta: 1 })
eq('上键走上一行', intent('ArrowUp'), { kind: 'move', delta: -1 })
eq('斜杠换转换', intent('/'), { kind: 'transform' })
eq('回车选来源', intent('Enter'), { kind: 'pick-source' })
eq('退格清除当前行', intent('Backspace'), { kind: 'clear' })
eq('删除键也清除当前行', intent('Delete'), { kind: 'clear' })

// 焦点在输入框 / 文本域里时界面不抢键盘：那时 `/` 就是要打一个斜杠（表达式编辑器里更是如此）
eq('在输入框里按斜杠不换转换', intent('/', { inTextEntry: true }), null)
eq('在文本域里按回车不选来源', intent('Enter', { inTextEntry: true }), null)
eq('在输入框里按退格不清行', intent('Backspace', { inTextEntry: true }), null)
eq('没选中行时换转换什么都不做', intent('/', { hasSelection: false }), null)
eq('没选中行时选来源什么都不做', intent('Enter', { hasSelection: false }), null)
eq('没选中行时清除什么都不做', intent('Backspace', { hasSelection: false }), null)
eq('没选中行时上下键照样能走（走到第一行）', intent('ArrowDown', { hasSelection: false }), { kind: 'move', delta: 1 })
eq('没被用到的键什么都不做', intent('a'), null)

// 「选来源」是回车之后的一小段模式：上下在清单里走、回车填进那一行、Esc 退出
const PICKING: Partial<KeyContext> = { pickingSource: true }
eq('选来源时下键在清单里往下走', intent('ArrowDown', PICKING), { kind: 'walk-item', delta: 1 })
eq('选来源时上键在清单里往上走', intent('ArrowUp', PICKING), { kind: 'walk-item', delta: -1 })
eq('选来源时回车把这一项填进那一行', intent('Enter', PICKING), { kind: 'confirm-pick' })
eq('选来源时按 Esc 退出这一段', intent('Escape', PICKING), { kind: 'leave' })
eq('选来源时按 `/` 不开转换面板（那会打断这一段）', intent('/', PICKING), null)

// 换转换的面板开着时，上下键在选项之间走、回车用这一项、Esc 收起（不用 Tab 去够按钮）
const OP_PANEL: Partial<KeyContext> = { panel: 'op' }
eq('换转换面板里下键走下一个选项', intent('ArrowDown', OP_PANEL), { kind: 'walk-option', delta: 1 })
eq('换转换面板里回车用这一个选项', intent('Enter', OP_PANEL), { kind: 'confirm-option' })
eq('换转换面板里 Esc 收起面板', intent('Escape', OP_PANEL), { kind: 'close-panel' })
const DATE_PANEL: Partial<KeyContext> = { panel: 'date' }
eq('日期预设面板也能用键盘选', intent('ArrowDown', DATE_PANEL), { kind: 'walk-option', delta: 1 })
eq('日期预设面板里回车用这一个预设', intent('Enter', DATE_PANEL), { kind: 'confirm-option' })
// 常量 / 枚举 / 拼接那三个面板里是输入框和表格，没有「一排选项」可走：只有 Esc 收起
const CONST_PANEL: Partial<KeyContext> = { panel: 'const' }
eq('常量面板里上下键仍然走行（那里没有选项可走）', intent('ArrowDown', CONST_PANEL), { kind: 'move', delta: 1 })
eq('常量面板里 Esc 收起来', intent('Escape', CONST_PANEL), { kind: 'close-panel' })
ok('只有「一排选项」的两个面板能用键盘选', isPanelChoice('op') && isPanelChoice('date') && !isPanelChoice('enum'))

// 走选项 / 走清单：跳过不能用的那一项，走到头就停在原地（不绕回开头）
const CHOICES = ['STRING', 'INT', 'DATE', 'ENUM']
eq('往下走一格', stepIndex(0, 1, CHOICES.length), 1)
eq('往上走一格', stepIndex(2, -1, CHOICES.length), 1)
eq('走到最后一格就停在原地', stepIndex(3, 1, CHOICES.length), 3)
eq('走到第一格就停在原地', stepIndex(0, -1, CHOICES.length), 0)
eq('跳过不能用的那一项（顶层行上的「行号」）', stepIndex(0, 1, CHOICES.length, i => i === 1), 2)
eq('不能用的连着几项都跳过', stepIndex(0, 1, CHOICES.length, i => i === 1 || i === 2), 3)
eq('后面全是不能用的就停在原地', stepIndex(0, 1, CHOICES.length, i => i > 0), 0)
eq('清单一项都没有时走不动', stepIndex(0, 1, 0), 0)

// 上下走行共用同一套走法，只多一条「还没选中任何一行时从两头进」（第一次按上下键的期待）
eq('走行：往下走一格', rowStepIndex(1, 1, 5), 2)
eq('走行：往上走一格', rowStepIndex(3, -1, 5), 2)
eq('走行：走到最后一行就停在原地', rowStepIndex(4, 1, 5), 4)
eq('走行：走到第一行就停在原地', rowStepIndex(0, -1, 5), 0)
eq('走行：还没选中时往下走是第一行', rowStepIndex(-1, 1, 5), 0)
eq('走行：还没选中时往上走是最后一行', rowStepIndex(-1, -1, 5), 4)
eq('走行：选中的行已经被过滤掉了也当没选中', rowStepIndex(9, 1, 5), 0)
eq('走行：一行都没有时走不动', rowStepIndex(-1, 1, 0), 0)

// 手写表达式这一边送给只读接口的草稿，就是保存会写出去的那一份（#103 定下的口径不变：
// 草稿 = 保存会写出去的配置，前端不另造一份）
const expressionDraft = draftRequest({
  rows: [DRAFT_CONFIGURED, DRAFT_BLANK],
  targets: TARGETS,
  sourceFields: [UNIT_FIELD],
  sourceFieldsStep: 1,
  earlierActionIndexes: [1],
  defaultSourceStep: 1,
  actionIndex: 2,
  draft: expressionConfig('$s_1.bomNo'),
})
eq('手写表达式时草稿换成表达式那一份（没有规则树）',
  expressionDraft.draft, { mode: 'EXPRESSION', expression: '$s_1.bomNo', rules: [] })
eq('其余那些外部事实照旧带上（手写这条路后端也看得见目标清单与样例）',
  [expressionDraft.targets.length, expressionDraft.sourceFieldsStep, expressionDraft.actionIndex],
  [TARGETS.length, 1, 2])

// ---------------------------------------------------------------- 11. 来源样例的两条路（#115）

/**
 * 来源是运行记录还是粘进来的 JSON，推字段那件事都在后端（同一份样例展开）；前端只有两处纯逻辑：
 * 顶上那句话怎么说、换来源步骤时这份样例归到哪一步。
 */
const PROBED: SampleOrigin = 'probe'
const PASTED: SampleOrigin = 'paste'

const PROBED_SAMPLE: EditorSourceSample = {
  step: 1, stepName: '读订单', sourceKey: 'sample', sampledFirstItem: false,
  fields: [{ name: 'order_no', type: 'STRING', nullable: true, samples: ['PO-1'] }],
}
const PASTED_SAMPLE: EditorSourceSample = { ...PROBED_SAMPLE, sourceKey: 'paste' }
/** 粘的是一整个数组：后端按第 1 条推，并把这件事写在 sampledFirstItem 上。 */
const PASTED_ARRAY: EditorSourceSample = { ...PASTED_SAMPLE, sampledFirstItem: true }

// 探测那条路的说法一个字都不变（这一票要求它的行为与之前完全一致）
eq('探测那条路：取自这一步最近一次成功运行',
  sourceTipOf(PROBED_SAMPLE, PROBED), '来自 动作 1 · 读订单 最近一次成功运行')
eq('探测那条路：链上还没有更早的动作', sourceTipOf(null, PROBED), '链上还没有更早的动作')
eq('探测那条路：这一步还没有运行记录',
  sourceTipOf({ ...PROBED_SAMPLE, fields: [] }, PROBED), '动作 1 · 读订单 还没有运行记录')

// 粘的那条路：写明这份样例按哪一步的输出来算（归到哪一步也看得见）
eq('粘的那条路：写明按哪一步的输出来算',
  sourceTipOf(PASTED_SAMPLE, PASTED), '这份样例按「动作 1 · 读订单」的输出来算')
eq('粘的那条路：任务输入那一步也写得出来',
  sourceTipOf({ ...PASTED_SAMPLE, step: 0, stepName: null }, PASTED), '这份样例按「任务输入」的输出来算')

// 是数组就注明「按第 1 条推的」——第 1 条里没有的字段不在清单上，这件事得写在清单上面
eq('整份就是一个对象时没有「按第几条推」那句话', sampleNoteOf(PASTED_SAMPLE), '')
eq('粘的是数组时注明按第 1 条推的', sampleNoteOf(PASTED_ARRAY), '这份样例是数组，按第 1 条推的字段')
ok('界面上那句话就写着「按第 1 条推的」', sampleNoteOf(PASTED_ARRAY).includes('按第 1 条推的'))
ok('探测那条路不注这句话', sampleNoteOf(PROBED_SAMPLE) === '')

// 换来源步骤：粘的那份样例只换归属（清单与「按第几条推」都不动，动作名跟着换）
eq('换来源步骤只换这份样例归到哪一步',
  attributeSample(PASTED_SAMPLE, 3, { 1: '读订单', 3: '洗数' }),
  { ...PASTED_SAMPLE, step: 3, stepName: '洗数' })
eq('换到任务输入那一步时没有动作名', attributeSample(PASTED_SAMPLE, 0, { 1: '读订单' }).stepName, null)
eq('换来源步骤时字段清单不动',
  attributeSample(PASTED_ARRAY, 3, { 3: '洗数' }).fields, PASTED_ARRAY.fields)
eq('换来源步骤时「按第 1 条推」也不动',
  attributeSample(PASTED_ARRAY, 3, { 3: '洗数' }).sampledFirstItem, true)
eq('换来源步骤时来源那条路不变（还是粘的这份）',
  attributeSample(PASTED_ARRAY, 3, { 3: '洗数' }).sourceKey, 'paste')

// 目标字段清单那一侧（#118）：粘出来的那份要说清「必填推不出来」与「按第 1 条推」；探测那条路一个字不加
eq('目标清单是连库探的：没有那一行小字', targetNoteOf(null), '')
eq('目标清单是粘的：写明必填推不出来、一律当不必填',
  targetNoteOf(PASTED_SAMPLE), '按粘的这份样例推的字段：必填推不出来，一律当不必填')
ok('目标清单是粘的、又是数组时，「按第 1 条推的」也在那句话里',
  targetNoteOf(PASTED_ARRAY).includes('必填推不出来') && targetNoteOf(PASTED_ARRAY).includes('按第 1 条推的'),
  targetNoteOf(PASTED_ARRAY))

console.log(`\n# ${passed} passed, ${failed} failed`)
if (failed > 0) {
  process.exitCode = 1
}
