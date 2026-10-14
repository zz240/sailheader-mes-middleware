/**
 * 批次口径的检查（#139 起，#147 换了判据）：不测组件，只测纯逻辑的外部行为。
 *
 * 跑法（零新依赖）：
 *   node --experimental-strip-types frontend/src/history/batch-checks.ts
 *
 * 押的是 #139 / #142 / #147 那几条：
 *   1. **按批分组**：分组键是「步骤号 + 批号」——同一步上同批的一组，同一个批号在两步上是两组
 *      （批次是「某一步上的分组」，决策 1）；
 *   2. 组头文案：`读取批 / 发送批 · 一次请求 N 条 · 失败 M 条`；**这一批有打包消息**的那一批是
 *      `发送批`（#147 的新判据，取代旧的摘要行）；
 *   3. **打包消息怎么认**（#147）：两级判据——**先**看批内序号（`batchSeq` 为空那一条唯一的消息，
 *      且这一批下存在序号非空的行），取不到再退回「某批号**最小步骤**上独占该步、而该批号下还有别的
 *      消息标识」。首选那级是「合并那一步**自己**失败」那一档唯一还分得开信封与成员的东西（第 3d 节）；
 *   4. 单条那档：某一步上这条消息**自己一组** → `单条 · 不是一批`，且**没有数据时不下结论**；
 *   5. 链上哪一格画徽标：**这一步换了号**才画，中间那些步骤不画（决策 2）；
 *   6. 失败恢复那一档：**发送批、这一批有失败、而且拿得到任务标识**才铺「重跑任务」（#147），
 *      而且一个批号只铺**一颗**——落在「该批号里最深的、还带着打包消息的那一步」那一组；
 *   7. **已知限制**（第 3d 节末）：无还原档且这一批**成功**时只剩信封一行，没有成员行可作锚，
 *      两级判据都取不到它 → 只能按「单条 · 不是一批」画（只记账，不做机制）。
 */
import {
  BATCH_BADGE, RERUN_LABEL, SINGLE_NOTE, batchGroupAt, batchGroups, batchHead, batchKey,
  jobRerun, nodeBatch, normalizeBatchId, type BatchGroup,
} from './batch.ts'
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

/** 一条消息步：默认是「读取批里的一条」，要哪一格不一样就覆盖哪一格。 */
function step(partial: Partial<MessageStep> & { id: number }): MessageStep {
  return {
    runId: 12,
    messageId: `m-${partial.id}`,
    step: 1,
    status: 'SUCCESS',
    batchId: 'batch-A',
    ...partial,
  }
}

/* ── 1. 按批分组：分组键是「步骤号 + 批号」 ─────────────────────────── */

const readBatch = [
  step({ id: 1, messageId: 'm-1', batchId: 'batch-A' }),
  step({ id: 2, messageId: 'm-2', batchId: 'batch-A', status: 'FAILED' }),
  step({ id: 3, messageId: 'm-3', batchId: 'batch-A' }),
]
const oneReadBatch = batchGroups(readBatch)
eq('一批三条归到一个组', oneReadBatch.length, 1)
eq('组的步骤号 / 批号 / 条数 / 失败数',
  [oneReadBatch[0].step, oneReadBatch[0].batchId, oneReadBatch[0].total, oneReadBatch[0].failed],
  [1, 'batch-A', 3, 1])
eq('读取批那档的组头文案', oneReadBatch[0].head, '读取批 · 一次请求 3 条 · 失败 1 条')
eq('组里每条 chip 就是一条消息步（按落库先后）',
  oneReadBatch[0].members.map((s) => s.messageId), ['m-1', 'm-2', 'm-3'])
eq('读取批里没有打包消息', oneReadBatch[0].packed, null)

const twoBatchesAtOneStep = batchGroups([
  step({ id: 1, messageId: 'm-1', batchId: 'batch-A' }),
  step({ id: 2, messageId: 'm-2', batchId: 'batch-A' }),
  step({ id: 3, messageId: 'm-3', batchId: 'batch-B' }),
  step({ id: 4, messageId: 'm-4', batchId: 'batch-B' }),
])
eq('同一步上两个批号 = 两组', twoBatchesAtOneStep.length, 2)
eq('两组各两条', twoBatchesAtOneStep.map((g) => g.total), [2, 2])

const inheritedAcrossSteps = batchGroups([
  step({ id: 1, messageId: 'm-1', step: 1, batchId: 'batch-A' }),
  step({ id: 2, messageId: 'm-2', step: 1, batchId: 'batch-A' }),
  step({ id: 3, messageId: 'm-1', step: 2, batchId: 'batch-A' }),
  step({ id: 4, messageId: 'm-2', step: 2, batchId: 'batch-A' }),
])
eq('批号继承到下一步：那一步是**另一组**（批次是某一步上的分组）',
  inheritedAcrossSteps.map((g) => [g.step, g.total]), [[1, 2], [2, 2]])

/* ── 2. 没有批号那些链：自己一组 = 单条 ───────────────────────────── */

const mqChain = batchGroups([
  step({ id: 1, messageId: 'm-1', step: 1, batchId: null, actionName: 'consume' }),
  step({ id: 2, messageId: 'm-1', step: 2, batchId: null, actionName: 'map' }),
])
eq('没有批号的链：每一步一组', mqChain.map((g) => [g.step, g.batchId, g.total]), [[1, null, 1], [2, null, 1]])
eq('那一组的档是单条', mqChain.map((g) => g.kind), ['single', 'single'])
eq('单条那档的组头就是那句话', mqChain[0].head, SINGLE_NOTE)
eq('单条那档的组头不写「一次请求 1 条」', batchHead('single', 1, 0), '单条 · 不是一批')
eq('批大小 1 的读库链同理：库里那行带着批号，但它没有「一组 chip」可看——按「不是一批」画',
  batchGroups([step({ id: 1, batchId: 'batch-A' })])[0].kind, 'single')
eq('批大小 1 的读库批也没有打包消息（这一批下没有别的消息标识）',
  batchGroups([step({ id: 1, batchId: 'batch-A' })])[0].packed, null)

/* ── 3. 打包消息分「读取批 / 发送批」（#147） ─────────────────────── */

/**
 * 合并链的一批（照着库里的真形状摆）：读库 20 条走 1、2 两步（`batch-A`）；**合并那一步批号转手**
 * （`AggregateFragment.takeBatch` 现生成新批号、并给打包出来那一条一个自己的消息标识），信封 `e-1`
 * 从第 3 步起、独占合并那一步；出站那一步是「信封 1 行 + 20 条成员同行」（整批同因）。
 */
const members: string[] = []
for (let i = 1; i <= 20; i++) members.push(`m-${i}`)

function mergeChain(outlet: 'SUCCESS' | 'FAILED'): MessageStep[] {
  const rows: MessageStep[] = []
  let id = 1
  for (const messageId of members) rows.push(step({ id: id++, messageId, step: 1, batchId: 'batch-A' }))
  for (const messageId of members) rows.push(step({ id: id++, messageId, step: 2, batchId: 'batch-A' }))
  // 合并那一步：批号在这一步转手，信封独占这一步
  rows.push(step({ id: id++, messageId: 'e-1', step: 3, batchId: 'batch-B' }))
  // 出站那一步：信封 1 行 + 20 条成员同行
  rows.push(step({ id: id++, messageId: 'e-1', step: 4, batchId: 'batch-B', status: outlet }))
  for (const messageId of members) rows.push(step({ id: id++, messageId, step: 4, batchId: 'batch-B', status: outlet }))
  // 出站成功才有还原那一步（失败即停）
  if (outlet === 'SUCCESS') {
    for (const messageId of members) rows.push(step({ id: id++, messageId, step: 5, batchId: 'batch-B' }))
  }
  return rows
}

const mergeFailed = batchGroups(mergeChain('FAILED'))
const readGroup = mergeFailed.find((g) => g.step === 1)
const aggregateGroup = mergeFailed.find((g) => g.step === 3)
const outletGroup = mergeFailed.find((g) => g.step === 4)
eq('合并链按「步骤号 + 批号」分成四组（合并那一步换了号，所以 1/2 与 3/4 不是同一批）',
  mergeFailed.map((g) => [g.step, g.batchId, g.total]),
  [[1, 'batch-A', 20], [2, 'batch-A', 20], [3, 'batch-B', 1], [4, 'batch-B', 21]])
eq('合并那一步（批号在这一步转手）信封独占该步：它就是打包消息，这一批因此是发送批',
  [aggregateGroup?.packed?.messageId, aggregateGroup?.kind], ['e-1', 'send'])
eq('出站那一步：信封 1 行 + 20 条成员同行，打包消息认在同一组里',
  [outletGroup?.packed?.messageId, outletGroup?.total], ['e-1', 21])
eq('组头不再多算一条（#143 记的偏差：原来是「发送批 · 一次请求 21 条 · 失败 20 条」）',
  outletGroup?.head, '发送批 · 一次请求 20 条 · 失败 20 条')
eq('「N 条 / 失败 M 条」都不含打包消息（它自己那行也是 FAILED，但不计）',
  [outletGroup?.messages, outletGroup?.failed], [20, 20])
eq('读库那一批（batch-A）没有打包消息：它仍是读取批，20 条',
  [readGroup?.kind, readGroup?.packed, readGroup?.head],
  ['read', null, '读取批 · 一次请求 20 条 · 失败 0 条'])
eq('合并那一步那一组也只有信封一行，但组头与出站那一组说的是同一句（kind / 条数都按**批号**算，不按这一组算）',
  aggregateGroup?.head, '发送批 · 一次请求 20 条 · 失败 20 条')

const mergeOk = batchGroups(mergeChain('SUCCESS'))
eq('出站成功时同一个批号的还原那一步也是发送批（批号没换），但打包那一行不在这一组里',
  [mergeOk.find((g) => g.step === 5)?.kind, mergeOk.find((g) => g.step === 5)?.packed,
    mergeOk.find((g) => g.step === 5)?.total],
  ['send', null, 20])

/* ── 3b. 打包那一行与成员不在同一步（出站有打包、还原那一步是成员） ── */

const acrossSteps = batchGroups([
  step({ id: 1, messageId: 'm-1', step: 5, batchId: 'batch-E' }),
  step({ id: 2, messageId: 'm-2', step: 5, batchId: 'batch-E', status: 'FAILED' }),
  step({ id: 3, messageId: 'e-1', step: 4, batchId: 'batch-E', status: 'FAILED' }),
])
eq('打包消息在最小步骤（出站）、成员在还原：两组都算发送批',
  acrossSteps.map((g) => [g.step, g.kind]), [[4, 'send'], [5, 'send']])
eq('「N 条 / 失败 M 条」按批号跨步骤去重，且不含打包消息（它自己那行也 FAILED）',
  acrossSteps.map((g) => [g.messages, g.failed]), [[2, 1], [2, 1]])
eq('出站那一组只有打包那一行，也不算「单条 · 不是一批」', acrossSteps[0].kind, 'send')
eq('打包消息只认在**含它那一行**的组里：还原那一组没有它',
  [acrossSteps[0].packed?.messageId, acrossSteps[1].packed], ['e-1', null])

/* ── 3c. 判据为什么必须挂在**最小步骤**上（#147 复查出来的那个误判） ── */

// 一条读取批：读库那一步 20 条都在（最小步骤）；下一步只留下 1 条失败行（失败即停，后面的没跑到）。
// 只按「某一步上独占该步」判的话，这一条普通消息会被误认成打包消息——档位错成发送批、条数还少算一条。
const partialRows: MessageStep[] = []
for (let i = 1; i <= 20; i++) partialRows.push(step({ id: i, messageId: `m-${i}`, step: 1, batchId: 'batch-A' }))
partialRows.push(step({ id: 21, messageId: 'm-1', step: 2, batchId: 'batch-A', status: 'FAILED' }))
const partialRead = batchGroups(partialRows)
eq('读取批在链中途只剩一条行时**不**被当成打包消息：这一批的档位仍是读取批、条数仍是 20',
  [partialRead.find((g) => g.step === 1)?.kind, partialRead.find((g) => g.step === 1)?.messages,
    partialRead.find((g) => g.step === 1)?.head],
  ['read', 20, '读取批 · 一次请求 20 条 · 失败 1 条'])
eq('这一批里一个打包消息都没有（最小步骤上是 20 条，不是一条独占）',
  partialRead.map((g) => g.packed), [null, null])
eq('那一条（第二步骤独）自己一组，仍是旧的单条档（决策 10），不是「重跑任务」的落点',
  [partialRead.find((g) => g.step === 2)?.kind, partialRead.find((g) => g.step === 2)?.messages],
  ['single', 20])

/* ── 3d. 两级判据（#147 复查：合并那一步**自己**失败时，退路判据取不到打包消息） ── */

/**
 * 合并那一步**自己**失败：`AggregateFragment.appendAggregatePack` 挂完成员清单、紧接着那句
 * `recordSuccessStep` 落库抛（#127「成功的动作反而被判失败」那条路）→ `StepLedger.recordFailure`
 * 先按信封自己的标识记一行、再按成员清单给这一批**每条**记一行，**全记在合并这一步上**。
 *
 * <p>于是这个批号**只有合并这一步**：一行信封（序号为空）+ 20 行成员（序号 1..20，全 FAILED）。
 * 退路判据（最小步骤上独占）在这一步上取不到打包消息——21 个不同标识谁也不独占；新增的首选判据
 * （序号为空的那一条唯一的消息）才认得出信封。</p>
 */
const mergeStepFailedRows: MessageStep[] = [
  step({ id: 1, messageId: 'e-1', step: 3, batchId: 'batch-M', status: 'FAILED' }),
  ...Array.from({ length: 20 }, (_, i) => step({
    id: i + 2, messageId: `m-${i + 1}`, step: 3, batchId: 'batch-M', batchSeq: i + 1, status: 'FAILED',
  })),
]
const mergeStepFailed = batchGroups(mergeStepFailedRows)
eq('合并那一步自己失败：信封（序号为空那一条）仍被认成打包消息 → 这一批是发送批',
  [mergeStepFailed.length, mergeStepFailed[0].kind, mergeStepFailed[0].packed?.messageId],
  [1, 'send', 'e-1'])
eq('组头不多算一条：一次请求 20 条、失败 20 条（信封在合并这一步也有一行，但它不计）',
  [mergeStepFailed[0].messages, mergeStepFailed[0].failed, mergeStepFailed[0].head],
  [20, 20, '发送批 · 一次请求 20 条 · 失败 20 条'])
eq('唯一那颗「重跑任务」落在合并那一步（这一批只有这一步）',
  [mergeStepFailed[0].rerunStep, rerunSteps(mergeStepFailed)], [3, [3]])

// 首选判据不能把**读取批 / 拆分批**误认成发送批：那两档整批同为空（读库那条路不写序号）或
// 同为非空（拆分按 1..N 编），任一条都不满足「空序号那一条唯一 + 存在序号非空的行」。
const readBatchWithSeq = batchGroups([
  step({ id: 1, messageId: 'm-1', step: 1, batchId: 'batch-A', batchSeq: 1 }),
  step({ id: 2, messageId: 'm-2', step: 1, batchId: 'batch-A', batchSeq: 2 }),
  step({ id: 3, messageId: 'm-1', step: 2, batchId: 'batch-A', batchSeq: 1, status: 'FAILED' }),
])
eq('整批序号都非空（拆分批那种形状）时首选判据不响：仍是读取批、没有打包消息',
  [readBatchWithSeq.find((g) => g.step === 1)?.kind, readBatchWithSeq.find((g) => g.step === 1)?.packed,
    readBatchWithSeq.find((g) => g.step === 2)?.packed],
  ['read', null, null])

/**
 * **无还原档、出站失败**（链上合并之后没有 ONE_TO_MANY 还原）：信封在第 3、4 步各一行，
 * 成员只在出站那一步。批量失败补记把成员那一批编成 1..N，所以出站那一步的首选判据同样成立。
 */
const noRestoreOutletFailed = batchGroups([
  step({ id: 1, messageId: 'e-1', step: 3, batchId: 'batch-N' }),
  step({ id: 2, messageId: 'e-1', step: 4, batchId: 'batch-N', status: 'FAILED' }),
  ...Array.from({ length: 20 }, (_, i) => step({
    id: i + 3, messageId: `m-${i + 1}`, step: 4, batchId: 'batch-N', batchSeq: i + 1, status: 'FAILED',
  })),
])
eq('无还原档、出站失败：这一批仍是发送批、20 条消息全失败',
  [noRestoreOutletFailed.find((g) => g.step === 3)?.kind, noRestoreOutletFailed.find((g) => g.step === 4)?.kind,
    noRestoreOutletFailed.find((g) => g.step === 4)?.messages,
    noRestoreOutletFailed.find((g) => g.step === 4)?.failed],
  ['send', 'send', 20, 20])
eq('那颗「重跑任务」只铺一颗，落在出站那一步（信封最深的那一行在那儿）',
  rerunSteps(noRestoreOutletFailed), [4])
eq('打包消息认在那两组的含它那一行上（合并那一步也有它）',
  [noRestoreOutletFailed.find((g) => g.step === 3)?.packed?.messageId,
    noRestoreOutletFailed.find((g) => g.step === 4)?.packed?.messageId],
  ['e-1', 'e-1'])

// **已知限制**（只记账，不做机制，理由见 `batch.ts` 文件头）：**无还原档、且这一批成功**时，
// 这个批号在库里只剩信封那一行——没有成员行可作锚，两级判据都取不到它（首选那条要求「存在序号非空
// 的行」，退路那条要求「还有别的消息标识」），于是只能当「单条 · 不是一批」。**改前那一档是
// 「发送批 · 一次请求 1 条」，那个 1 本来也是错的**（成员挂在读取批那个号上）。
const noRestoreSucceeded = batchGroups([
  step({ id: 1, messageId: 'e-1', step: 3, batchId: 'batch-P' }),
  step({ id: 2, messageId: 'e-1', step: 4, batchId: 'batch-P' }),
])
eq('已知限制：无还原档且这一批成功时，只剩信封一行 → 只能按「单条 · 不是一批」画',
  [noRestoreSucceeded.map((g) => g.kind), noRestoreSucceeded.map((g) => g.packed),
    noRestoreSucceeded[0].head],
  [['single', 'single'], [null, null], '单条 · 不是一批'])

// 拆分链（A2 头行分表那一档）：拆出来的子消息是新批号，**没有任何消息独占某一步**，所以不是发送批。
const splitChildren = batchGroups([
  step({ id: 1, messageId: 'c-1', step: 6, batchId: 'batch-S' }),
  step({ id: 2, messageId: 'c-2', step: 6, batchId: 'batch-S' }),
  step({ id: 3, messageId: 'c-3', step: 6, batchId: 'batch-S' }),
  step({ id: 4, messageId: 'c-4', step: 6, batchId: 'batch-S' }),
  step({ id: 5, messageId: 'c-1', step: 7, batchId: 'batch-S' }),
  step({ id: 6, messageId: 'c-2', step: 7, batchId: 'batch-S' }),
  step({ id: 7, messageId: 'c-3', step: 7, batchId: 'batch-S' }),
  step({ id: 8, messageId: 'c-4', step: 7, batchId: 'batch-S' }),
])
eq('拆分新造出来的那一批仍是读取批（子消息的批号里没有基数变化步，也没有打包消息）',
  [splitChildren.find((g) => g.step === 6)?.kind, splitChildren.find((g) => g.step === 6)?.packed,
    splitChildren.find((g) => g.step === 6)?.head],
  ['read', null, '读取批 · 一次请求 4 条 · 失败 0 条'])

/* ── 4. 链上哪一格画徽标：换了号才画（决策 2） ─────────────────────── */

const chainGroups = batchGroups([
  step({ id: 1, messageId: 'm-1', step: 1, batchId: 'batch-A' }),
  step({ id: 2, messageId: 'm-2', step: 1, batchId: 'batch-A' }),
  step({ id: 3, messageId: 'm-1', step: 2, batchId: 'batch-A' }),
  step({ id: 4, messageId: 'm-2', step: 2, batchId: 'batch-A' }),
  step({ id: 5, messageId: 'm-1', step: 3, batchId: 'batch-D' }),
  step({ id: 6, messageId: 'm-2', step: 3, batchId: 'batch-D' }),
])
const first = step({ id: 1, messageId: 'm-1', step: 1, batchId: 'batch-A' })
const second = step({ id: 3, messageId: 'm-1', step: 2, batchId: 'batch-A' })
const third = step({ id: 5, messageId: 'm-1', step: 3, batchId: 'batch-D' })
eq('第一步（入口之后第一个带号的）画徽标：这是这一步上的一批',
  nodeBatch(null, first, chainGroups).tier, 'batch')
eq('中间那一步批号没换：什么都不画', nodeBatch(first, second, chainGroups).tier, 'plain')
eq('换了号的那一步再画一次（基数变化动作在那儿）', nodeBatch(second, third, chainGroups).tier, 'batch')
eq('画徽标那档挂着的就是这一组', nodeBatch(null, first, chainGroups).group?.total, 2)
eq('徽标上的字只说「这一步是一批」，不摊成员', BATCH_BADGE, '一批')

const singleStep = step({ id: 9, messageId: 'm-9', step: 1, batchId: null })
eq('自己一组的那一格画虚线框那档', nodeBatch(null, singleStep, batchGroups([singleStep])).tier, 'single')
eq('虚线框那档挂着的组就是它自己（一条）',
  nodeBatch(null, singleStep, batchGroups([singleStep])).group?.total, 1)

/* ── 5. 没有数据时不下结论 ───────────────────────────────────────── */

eq('整条运行的消息步还没到齐（分组为空）时不下结论',
  nodeBatch(null, first, []).tier, 'plain')
eq('那时也没有组可点开', nodeBatch(null, first, []).group, null)
eq('组里找不到这一步（步骤号对不上）同样是「不下结论」',
  nodeBatch(null, step({ id: 7, step: 9, batchId: 'batch-Z' }), chainGroups).tier, 'plain')
eq('查一组也走同一处判据（步骤号 + 批号都要对上）',
  [batchGroupAt(chainGroups, first)?.total, batchGroupAt(chainGroups, third)?.batchId],
  [2, 'batch-D'])

/* ── 6. 失败恢复那一档：重跑任务（#147） ─────────────────────────── */

/** 这一次运行里铺了几颗「重跑任务」、落在哪几步（一个批号只该有一颗，见本节后半那几条）。 */
function rerunSteps(groups: BatchGroup[]): number[] {
  return groups.filter((group) => jobRerun(group, 7) !== null).map((group) => group.step)
}

eq('读取批那一档不铺「重跑任务」（合并之前的步骤仍是逐条断点重投）',
  jobRerun(oneReadBatch[0], 7), null)
eq('单条那档更不铺', jobRerun(batchGroups([singleStep])[0], 7), null)
eq('没有组就什么都不铺', jobRerun(null, 7), null)
eq('发送批且这一批有失败：铺「重跑任务」，要的是**任务标识**（不是哪一行）',
  [jobRerun(outletGroup ?? null, 7)?.jobId, jobRerun(outletGroup ?? null, 7)?.label],
  [7, RERUN_LABEL])
eq('那颗按钮上的字就是「重跑任务」', RERUN_LABEL, '重跑任务')
const confirm = jobRerun(outletGroup ?? null, 7)?.confirm ?? ''
ok('确认文案说清「从未完成的那一批继续」', confirm.includes('未完成的那一批'), confirm)
ok('确认文案点明目标端会再收到一次该批请求', confirm.includes('目标端会再收到一次该批请求'), confirm)
ok('确认文案带上这一批的条数与失败数', confirm.includes('20 条消息') && confirm.includes('20 条失败'), confirm)
eq('发送批全成时不铺（没什么可重跑的）',
  jobRerun(mergeOk.find((g) => g.step === 4) ?? null, 7), null)
eq('取不到任务标识时不铺（`MessageDetailPanel` 那条「拿不到 jobId 就别铺按钮」）',
  [jobRerun(outletGroup ?? null, null), jobRerun(outletGroup ?? null, undefined)], [null, null])
eq('打包那一行不在这一组时也不铺（还原那一组分不到按钮）',
  [jobRerun(acrossSteps[0], 7)?.label, jobRerun(acrossSteps[1], 7)], [RERUN_LABEL, null])

/* 落点：一个批号只铺**一颗**，落在「该批号里**最深的、还带着打包消息的那一步**」（#147 的裁决） */

eq('出站失败：整个运行只铺一颗，落在**出站那一步**（第 4 步）',
  rerunSteps(mergeFailed), [4])
eq('打包那一步那一组（第 3 步）自己没有按钮，尽管打包消息那一行就在它里面',
  jobRerun(aggregateGroup ?? null, 7), null)

// 还原失败那档：信封在第 3、4、5 步各一行——还原那一步它也有一行，所以「最深的那一步」是还原，
// 按钮跟着失败发生的地方走（这一档落到还原那一步是可接受的偏差）
const restoreFailed = batchGroups([
  step({ id: 1, messageId: 'e-1', step: 3, batchId: 'batch-F' }),
  step({ id: 2, messageId: 'e-1', step: 4, batchId: 'batch-F' }),
  step({ id: 3, messageId: 'e-1', step: 5, batchId: 'batch-F', status: 'FAILED' }),
  step({ id: 4, messageId: 'm-1', step: 5, batchId: 'batch-F', status: 'FAILED' }),
  step({ id: 5, messageId: 'm-2', step: 5, batchId: 'batch-F', status: 'FAILED' }),
])
eq('还原失败：仍然只铺一颗，落在**还原那一步**（第 5 步）', rerunSteps(restoreFailed), [5])
eq('那一批的档位与计数不受落点口径影响（还是发送批、2 条消息、失败 2 条）',
  [restoreFailed.find((g) => g.step === 5)?.kind, restoreFailed.find((g) => g.step === 5)?.messages,
    restoreFailed.find((g) => g.step === 5)?.failed],
  ['send', 2, 2])

// 多出口（链上两个出站动作）：信封在第 3（合并）、4、5（两个出站）步各一行，成员在还原之后的第 6 步。
// 改前是「每个出口各一颗重发按钮」，#147 把它收敛成一颗。
const twoOutlets = batchGroups([
  step({ id: 1, messageId: 'e-1', step: 3, batchId: 'batch-G' }),
  step({ id: 2, messageId: 'e-1', step: 4, batchId: 'batch-G' }),
  step({ id: 3, messageId: 'e-1', step: 5, batchId: 'batch-G' }),
  step({ id: 4, messageId: 'm-1', step: 6, batchId: 'batch-G', status: 'FAILED' }),
  step({ id: 5, messageId: 'm-2', step: 6, batchId: 'batch-G' }),
])
eq('多出口：整个运行里只有一颗「重跑任务」（多出口各自一颗重发按钮那件事就此消失）',
  rerunSteps(twoOutlets), [5])
eq('打包那一条行在几步上有，就只在最深那一步铺（第 3、4 步都不铺）',
  [jobRerun(twoOutlets.find((g) => g.step === 3) ?? null, 7),
    jobRerun(twoOutlets.find((g) => g.step === 4) ?? null, 7)], [null, null])
eq('读取批 / 单条那两档一颗都没有',
  [rerunSteps(oneReadBatch), rerunSteps(batchGroups([singleStep]))], [[], []])

/* ── 7. 归一与计数那两句 ─────────────────────────────────────────── */

eq('批号两侧的空白不算内容', normalizeBatchId('  batch-A  '), 'batch-A')
eq('空串 / 缺省都算没有批号', [normalizeBatchId(''), normalizeBatchId(null), normalizeBatchId(undefined)],
  [null, null, null])
eq('一条消息步 / 一个组的键就是「步骤号 + 批号」——分组、查组、界面上开哪一组都用这一处',
  [batchKey({ step: 3, batchId: 'batch-A' }), batchKey({ step: 3, batchId: null }),
    batchKey({ step: 3, batchId: '  ' }), batchKey({ step: 3, batchId: 'batch-A' }) === batchKey({ step: 3, batchId: ' batch-A ' })],
  ['3\u0000batch-A', '3\u0000', '3\u0000', true])
eq('空白批号与没有批号是同一组（不分成两组）',
  batchGroups([step({ id: 1, batchId: '   ' }), step({ id: 2, batchId: null })])[0].total, 2)

console.log(`\n# ${passed} passed, ${failed} failed`)
if (failed > 0) {
  process.exitCode = 1
}
