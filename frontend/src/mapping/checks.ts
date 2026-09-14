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
import { rowFromRule, ruleFromRow, type MapRow } from './rows.ts'
import {
  mergeRowsWithTargets, rowReferenceFullLabel, rowReferenceLabel, rowsFromTargetFields,
  targetFieldsFromPaths, type TargetField,
} from './editor-model.ts'
import { fieldReferenceLabel, fieldReferenceFullLabel, stepLabel, emptyRowLabel } from './labels.ts'

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

console.log(`\n# ${passed} passed, ${failed} failed`)
if (failed > 0) {
  process.exitCode = 1
}
