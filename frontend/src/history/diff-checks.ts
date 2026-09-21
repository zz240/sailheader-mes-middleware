/**
 * 「与上一步对照」的口径检查（#133）：不测组件，只测纯逻辑的外部行为。
 *
 * 跑法（零新依赖）：
 *   node --experimental-strip-types frontend/src/history/diff-checks.ts
 *
 * 押的是 ADR-0016 决策 9 与 #133 票面那几条：
 *   1. 值变更 / 新增 / 删除（`改` / `增` / `减`），四个计数与行数一致；
 *   2. 嵌套对象的路径写法（`a.b.c`）；
 *   3. 数组**按索引**对齐：元素增减能看出下标错位那个后果（连弱处一起接受的那一条）；
 *   4. 空容器记一格（`{}` / `[]` 自己是一条叶子）；
 *   5. 两侧类型不同（对象 ↔ 标量、对象 ↔ 数组）各有判决，不炸；
 *   6. 值那一格怎么写（null / 空串 / ISO 时刻 / 长串截断 / 缺的那一侧给破折号）；
 *   7. 上下文路径：`3.order_no` → `context[3].order_no`；
 *   8. 挡路的两种：第一步（没有上一步）、任一侧缺快照——**说清是哪一侧、为什么**；
 *   9. 上一步是谁：同一条消息里按步骤号升序紧邻的前一个。
 */
import {
  contextPath, diffCellText, diffCountsText, flattenLeaves, previousStepOf, snapshotDiff,
  stepDiffStart, DIFF_ABSENT, DIFF_LABELS, DIFF_NOTE, DIFF_STATUSES, DIFF_VALUE_CHARS,
  type DiffRow,
} from './diff.ts'
import type { MessageStep } from '../types.ts'

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
  const a = JSON.stringify(actual)
  const e = JSON.stringify(expected)
  ok(name, a === e, `expected ${e} got ${a}`)
}

/** 断言用的最小快照行：只带这一票真正要看的三格。 */
function row(step: number, dataSnapshot: string | null, id = step * 10): Pick<MessageStep, 'step' | 'dataSnapshot'> & { id: number } {
  return { id, step, dataSnapshot }
}

function step(stepNo: number, id = stepNo * 10): MessageStep {
  return { id, runId: 1, messageId: 'm1', step: stepNo, status: 'SUCCESS' }
}

/** 一行的判决缩写成 `路径 状态 上一步值→本步值`，断言整张表时好读。 */
function shape(rows: readonly DiffRow[]): string[] {
  return rows.map((r) => `${r.path} ${r.status} ${diffCellText(r.oldValue)}→${diffCellText(r.newValue)}`)
}

/* ── 1. 四个判决与计数 ─────────────────────────────────────────────── */

{
  const prev = { order_no: 'A-1', qty: 10, note: '旧' }
  const next = { order_no: 'A-1', qty: 12, extra: true }
  const { rows, counts } = snapshotDiff(prev, next)
  eq('路径按上一步的顺序出，本步多出来的接在后面',
    rows.map((r) => r.path), ['order_no', 'qty', 'note', 'extra'])
  eq('同名取值没变是同', rows[0].status, 'same')
  eq('同名取值变了是改', rows[1].status, 'chg')
  eq('只在上一步是减', rows[2].status, 'del')
  eq('只在本步是增', rows[3].status, 'add')
  eq('值带在行上（改那一行的两侧原值都在）',
    [rows[1].oldValue, rows[1].newValue], [10, 12])
  eq('两侧都没有的路径不产生行（容器自己不是叶子）',
    shape(rows).filter((r) => r.startsWith('note')).length, 1)
  eq('四个判决是中文字', DIFF_LABELS, { same: '同', chg: '改', add: '增', del: '减' })
  eq('摘要行的顺序固定', DIFF_STATUSES, ['same', 'chg', 'add', 'del'])
  eq('计数：同 1 改 1 增 1 减 1', counts, { same: 1, chg: 1, add: 1, del: 1 })
  eq('计数之和 = 行数', counts.same + counts.chg + counts.add + counts.del, rows.length)
  eq('摘要四个数的文案', diffCountsText(counts).map((c) => c.text), ['同 1', '改 1', '增 1', '减 1'])
}

/* ── 2. 判等用 JSON.stringify：1 与 "1"、true 与 "true" 都是「改」 ── */

{
  const { rows, counts } = snapshotDiff({ a: 1, b: true, c: null }, { a: '1', b: 'true', c: null })
  eq('数字与字符串不算相等', rows[0].status, 'chg')
  eq('布尔与字符串不算相等', rows[1].status, 'chg')
  eq('null 与 null 相等', rows[2].status, 'same')
  eq('只报了一处改以外的都在同档', counts, { same: 1, chg: 2, add: 0, del: 0 })
}

{
  // 同一份内容、键序不同：路径对齐看的是路径，不是顺序——但行的顺序按上一步的键序
  const { rows, counts } = snapshotDiff({ a: 1, b: 2 }, { b: 2, a: 1 })
  eq('键序不同不影响判决', counts, { same: 2, chg: 0, add: 0, del: 0 })
  eq('行的顺序还是按上一步', rows.map((r) => r.path), ['a', 'b'])
}

/* ── 3. 嵌套对象：路径是 `a.b.c` ───────────────────────────────────── */

{
  const prev = { order: { head: { no: 'A-1', at: '2026-08-26' }, lines: [] } }
  const next = { order: { head: { no: 'A-2', at: '2026-08-26' }, lines: [] } }
  const { rows, counts } = snapshotDiff(prev, next)
  eq('嵌套对象按点号连起来', rows.map((r) => r.path), ['order.head.no', 'order.head.at', 'order.lines'])
  eq('深层那一个改', rows[0].status, 'chg')
  eq('深层没变的那一个同', rows[1].status, 'same')
  eq('嵌套里的空数组自己是一格', rows[2].status, 'same')
  eq('计数对得上', counts, { same: 2, chg: 1, add: 0, del: 0 })
}

/* ── 4. 数组按下标对齐：连弱处一起接受的那一条 ────────────────────── */

{
  const prev = { lines: [{ qty: 1 }, { qty: 2 }, { qty: 3 }] }
  const next = { lines: [{ qty: 1 }, { qty: 2 }, { qty: 3 }, { qty: 4 }] }
  const { rows, counts } = snapshotDiff(prev, next)
  eq('数组按下标摊平', rows.map((r) => r.path),
    ['lines[0].qty', 'lines[1].qty', 'lines[2].qty', 'lines[3].qty'])
  eq('前三个元素没变 → 三格同', counts.same, 3)
  eq('多出来的那一项是增', counts.add, 1)
  eq('按索引对齐：尾部追加不动前面的判决', counts, { same: 3, chg: 0, add: 1, del: 0 })
}

{
  // 弱处：中间插入一项，后续下标全判成「改」——票面明说这一期不做语义 diff
  const prev = { lines: [{ sku: 'A' }, { sku: 'B' }] }
  const next = { lines: [{ sku: 'X' }, { sku: 'A' }, { sku: 'B' }] }
  const { rows, counts } = snapshotDiff(prev, next)
  eq('中间插入后下标整体错位',
    shape(rows), [
      'lines[0].sku chg A→X',
      'lines[1].sku chg B→A',
      'lines[2].sku add —→B',
    ])
  eq('弱处的后果写在计数里（两条看着像改了）', counts, { same: 0, chg: 2, add: 1, del: 0 })
  ok('口径那一句把这个弱处说出来', DIFF_NOTE.includes('数组按索引对齐'))
  ok('口径那一句写明这不是语义级', DIFF_NOTE.includes('不是语义级'))
}

/* ── 5. 空容器记一格 ─────────────────────────────────────────────── */

{
  const { rows, counts } = snapshotDiff({ a: {}, b: [], c: 1 }, { a: {}, b: [], c: 1 })
  eq('空对象空数组各记一格（不是没有）', rows.map((r) => r.path), ['a', 'b', 'c'])
  eq('空容器自己带值', shape(rows).slice(0, 2), ['a same {}→{}', 'b same []→[]'])
  eq('空容器那两格算同', counts, { same: 3, chg: 0, add: 0, del: 0 })
}

{
  const { rows, counts } = snapshotDiff({ a: {} }, { a: { x: 1 } })
  eq('从空对象长出字段：空容器那一格没了（换成增），空容器自己那条是减',
    shape(rows), ['a del {}→—', 'a.x add —→1'])
  eq('计数', counts, { same: 0, chg: 0, add: 1, del: 1 })
}

{
  const { rows } = snapshotDiff({ a: [] }, { a: [1] })
  eq('空数组长出元素同理', shape(rows), ['a del []→—', 'a[0] add —→1'])
}

{
  const { rows } = snapshotDiff({ a: [1, 2] }, { a: [] })
  eq('清空数组：两条减 + 空容器自己那一格是增', shape(rows), ['a[0] del 1→—', 'a[1] del 2→—', 'a add —→[]'])
}

/* ── 6. 两侧类型不同 ─────────────────────────────────────────────── */

{
  // 原理型那一段：两侧都在但一侧是容器 → 容器那条路径自己不是叶子，于是底下全判成增 / 减
  const { rows, counts } = snapshotDiff({ a: { x: 1 } }, { a: 5 })
  eq('对象变成标量：原来的叶子是减，新的标量是增（同一路径上的形状换了）',
    shape(rows), ['a.x del 1→—', 'a add —→5'])
  eq('计数', counts, { same: 0, chg: 0, add: 1, del: 1 })
}

{
  const { rows } = snapshotDiff({ a: { x: 1 } }, { a: { x: 2 } })
  eq('对象内部变，走的还是叶子里那条', shape(rows), ['a.x chg 1→2'])
}

{
  const { rows } = snapshotDiff({ a: { x: 1 } }, { a: [1] })
  eq('对象变数组：底下的叶子分开判', shape(rows), ['a.x del 1→—', 'a[0] add —→1'])
}

/* ── 7. 整份是标量根 ─────────────────────────────────────────────── */

{
  const { rows, counts } = snapshotDiff(['x'], ['y'])
  eq('标量根（整份是数组）：按下标', shape(rows), ['[0] chg x→y'])
  eq('计数', counts, { same: 0, chg: 1, add: 0, del: 0 })
}

{
  const { rows } = snapshotDiff(1, 2)
  eq('整份就是一个标量：路径写「（标量）」', shape(rows), ['（标量） chg 1→2'])
}

{
  const { rows } = snapshotDiff('a', 'a')
  eq('标量根没变就是同', shape(rows), ['（标量） same a→a'])
}

/* ── 8. 摊平本身 ─────────────────────────────────────────────────── */

{
  const leaves = flattenLeaves({ a: 1, b: { c: 2 }, d: [3, { e: 4 }] })
  eq('摊平：只有叶子进表，路径带下标', [...leaves.keys()], ['a', 'b.c', 'd[0]', 'd[1].e'])
  eq('摊平出的值就是原值', [...leaves.values()], [1, 2, 3, 4])
  eq('空对象摊平成一格', [...flattenLeaves({}).keys()], ['（标量）'])
  eq('传进去的表接着用', flattenLeaves({ z: 9 }, '', leaves).size, 5)
}

/* ── 9. 值那一格怎么写 ───────────────────────────────────────────── */

{
  eq('null 写 null', diffCellText(null), 'null')
  eq('空串写 ""（不是空白）', diffCellText(''), '""')
  eq('数字原样', diffCellText(12.5), '12.5')
  eq('false 原样', diffCellText(false), 'false')
  eq('缺的那一侧给破折号', diffCellText(undefined), DIFF_ABSENT)
  eq('ISO 时刻写成带时区的可读形式',
    diffCellText('2026-08-26T10:00:00Z'), '2026-08-26 10:00:00 UTC')
  eq('带偏移的 ISO 时刻保留偏移',
    diffCellText('2026-08-26T10:00:00.123+08:00'), '2026-08-26 10:00:00 +08:00')
  eq('不是 ISO 的串照原样', diffCellText('2026-08-26'), '2026-08-26')
  const long = 'x'.repeat(DIFF_VALUE_CHARS + 10)
  eq('长串截断加省略号', diffCellText(long), `${'x'.repeat(DIFF_VALUE_CHARS)}…`)
  eq('刚好到界不截断', diffCellText('y'.repeat(DIFF_VALUE_CHARS)), 'y'.repeat(DIFF_VALUE_CHARS))
  // 进这一格的容器只有空的那两个（见 flattenLeaves）：印 `[object Object]` 就等于把空对象写成空数组
  eq('空对象印成 {}', diffCellText({}), '{}')
  eq('空数组印成 []', diffCellText([]), '[]')
}

/* ── 10. 上下文路径 ─────────────────────────────────────────────── */

{
  eq('步骤那条上下文路径', contextPath('3.order_no'), 'context[3].order_no')
  eq('数组下标留着', contextPath('3[0].qty'), 'context[3][0].qty')
  eq('只有第一段加方括号', contextPath('12.a.3'), 'context[12].a.3')
  eq('不是数字打头的原样', contextPath('a[0]'), 'a[0]')
  eq('空路径给 context', contextPath(''), 'context')
  eq('标量根那条原样', contextPath('（标量）'), '（标量）')
}

/* ── 11. 上一步是谁 ─────────────────────────────────────────────── */

{
  const message = { steps: [step(1), step(2), step(3)] }
  eq('第一步没有上一步', previousStepOf(message, step(1)), null)
  eq('第二步的上一步是第一步', previousStepOf(message, step(2))?.step, 1)
  eq('第三步的上一步是第二步', previousStepOf(message, step(3))?.id, 20)
  eq('只有一步的消息照样是第一步', previousStepOf({ steps: [step(1)] }, step(1)), null)
  eq('没有消息给 null', previousStepOf(null, step(2)), null)
  eq('没有选中步骤给 null', previousStepOf(message, null), null)
  eq('对不上任何一步给 null（不硬猜）', previousStepOf(message, step(9)), null)
  // id 认不出来时按步骤号兜一层（手里那份行不是这条消息里的 / 没带 id）
  const noId: MessageStep = { id: undefined as unknown as number, runId: 1, messageId: 'm1', step: 3, status: 'SUCCESS' }
  eq('没带 id 时按步骤号认', previousStepOf(message, noId)?.step, 2)
  const strange = { id: 999, runId: 1, messageId: 'm1', step: 3, status: 'SUCCESS' }
  eq('id 对不上时也按步骤号认', previousStepOf(message, strange)?.step, 2)
}

/* ── 12. 挡路的两种 ─────────────────────────────────────────────── */

{
  const current = row(1, '{"order_no":"A-1"}')
  const { start, blocker } = stepDiffStart(null, current)
  eq('第一步：不给起点', start, null)
  eq('第一步那一档是 none', blocker?.state, 'none')
  eq('第一步的说法', blocker?.text, '步骤 1 是这条消息的第一步，没有上一步可对照。')
}

{
  const prev = row(1, '{"order_no":"A-1"}', 10)
  const current = row(2, null, 20)
  const { start, blocker } = stepDiffStart(prev, current, { prevAction: 'READ_SQL', nextAction: 'push-qc' })
  eq('本步没快照：不给起点', start, null)
  eq('那一档是 no-snap', blocker?.state, 'no-snap')
  eq('说清是哪一侧、为什么（本步）', blocker?.text,
    '本步（步骤 2 push-qc） 没有数据快照：失败步、被跳步与运行中的步骤都不写快照，MQ 入口动作也不记成功步快照，没法对照。')
}

{
  const prev = row(1, '   ', 10)
  const current = row(2, '{"a":1}', 20)
  const { blocker } = stepDiffStart(prev, current, { prevAction: 'READ_SQL' })
  ok('空白快照算没落（与 #131 那两个点同一个判据）', !!blocker?.text.startsWith('上一步（步骤 1 READ_SQL） 没有数据快照'))
}

{
  const prev = row(1, 'not json', 10)
  const current = row(2, '{"a":1}', 20)
  const { start, blocker } = stepDiffStart(prev, current, { prevAction: 'push-qc' })
  eq('非 JSON 文本：不给起点', start, null)
  eq('非 JSON 的说法与「没落」分得开', blocker?.text,
    '上一步（步骤 1 push-qc）的数据快照不是 JSON 文本，没有键路径可对照，没法对照。')
}

{
  const huge = `"${'x'.repeat(70000)}"`
  const prev = row(1, '{"a":1}', 10)
  const current = row(2, huge, 20)
  const { blocker } = stepDiffStart(prev, current, { nextAction: 'push-qc' })
  ok('超长那一档照实说（并指向 #127）',
    !!blocker && blocker.text.includes('超过 64 KB') && blocker.text.includes('#127'))
}

{
  const prev = row(1, 'not json', 10)
  const current = row(2, null, 20)
  const { blocker } = stepDiffStart(prev, current)
  ok('两侧都不行时先报上一步那一侧（哪一侧都不含糊）',
    !!blocker && blocker.text.startsWith('上一步（步骤 1）'))
}

{
  const prev = row(1, '{"order_no":"A-1","qty":10}', 10)
  const current = row(2, '{"order_no":"A-1","qty":12}', 20)
  const { start, blocker } = stepDiffStart(prev, current, { prevAction: 'READ_SQL', nextAction: 'push-qc' })
  eq('两侧都有：不给挡路的那句话', blocker, null)
  eq('起点带上两侧的行', [start?.prev, start?.next], [prev, current])
  eq('两侧标题写步骤号与动作（给摘要行与标题共用）',
    [start?.prevHead, start?.nextHead], ['步骤 1 READ_SQL', '步骤 2 push-qc'])
  const diff = snapshotDiff(JSON.parse(prev.dataSnapshot!), JSON.parse(current.dataSnapshot!))
  eq('起点接上 diff：一条改', shape(diff.rows), ['order_no same A-1→A-1', 'qty chg 10→12'])
  eq('起点接上 diff：计数', diff.counts, { same: 1, chg: 1, add: 0, del: 0 })
}

{
  // 空容器那一档有 value（合法 JSON），所以它**不挡路**：两侧都是 `{}` → 空表交给界面的「都没有可对照的字段」
  const prev = row(1, '{}', 10)
  const current = row(2, '[]', 20)
  const { start, blocker } = stepDiffStart(prev, current)
  ok('空容器不挡路（它是合法 JSON，摊平后是一格）', blocker === null && start !== null)
  const diff = snapshotDiff({}, [])
  eq('两侧空容器：一行「改」（同一路径），计数不为零',
    shape(diff.rows), ['（标量） chg {}→[]'])
}

{
  // 两侧都是同一份空对象：仍然各记一格 → 同 1
  const { start } = stepDiffStart(row(1, '{}', 10), row(2, '{}', 20))
  const diff = snapshotDiff({}, {})
  eq('两侧都是空对象时那一格是同', shape(diff.rows), ['（标量） same {}→{}'])
  ok('起点在', start !== null)
}

/* ── 13. 只有一条路径的表也有计数 ───────────────────────────────── */

{
  const { rows, counts } = snapshotDiff({ a: 1 }, { a: 1 })
  eq('全同', counts, { same: 1, chg: 0, add: 0, del: 0 })
  eq('行数 1', rows.length, 1)
}

if (failed) {
  console.log(`\n# ${passed} passed, ${failed} FAILED`)
  process.exitCode = 1
} else {
  console.log(`\n# ${passed} passed, 0 failed`)
}
