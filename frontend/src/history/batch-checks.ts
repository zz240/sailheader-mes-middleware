/**
 * 批次口径的检查（#139）：不测组件，只测纯逻辑的外部行为。
 *
 * 跑法（零新依赖）：
 *   node --experimental-strip-types frontend/src/history/batch-checks.ts
 *
 * 押的是 #139 票面与 ADR-0017 那几条：
 *   1. **按批分组**：分组键是「步骤号 + 批号」——同一步上同批的一组，同一个批号在两步上是两组
 *      （批次是「某一步上的分组」，决策 1）；
 *   2. 组头文案：`读取批 · 一次请求 N 条 · 失败 M 条`；有摘要行的那一组是 `发送批`（决策 6）；
 *   3. 单条那档：某一步上这条消息**自己一组** → `单条 · 不是一批`，且**没有数据时不下结论**；
 *   4. 链上哪一格画徽标：**这一步换了号**才画，中间那些步骤不画（决策 2）；
 *   5. 某一步能不能重发：只有发送批且这一批有失败才铺「重发这一批（N 条）」（决策 7）。
 */
import {
  BATCH_BADGE, SINGLE_NOTE, SUMMARY_MESSAGE_PREFIX, batchGroupAt, batchGroups, batchHead, batchKey,
  batchRedeliver, batchRedeliverDone, isSummaryRow, nodeBatch, normalizeBatchId, redeliverBatchPath,
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

/* ── 3. 摘要行分「读取批 / 发送批」（决策 6） ─────────────────────── */

const sendBatch = batchGroups([
  step({ id: 1, messageId: 'm-1', step: 4, batchId: 'batch-C' }),
  step({ id: 2, messageId: 'm-2', step: 4, batchId: 'batch-C', status: 'FAILED' }),
  step({ id: 3, messageId: `${SUMMARY_MESSAGE_PREFIX}batch-C`, step: 4, batchId: 'batch-C', status: 'FAILED' }),
])
eq('有摘要行的那一组是发送批', sendBatch[0].kind, 'send')
eq('发送批的组头文案（「N 条」数的是消息，不含摘要行）', sendBatch[0].head, '发送批 · 一次请求 2 条 · 失败 1 条')
eq('摘要行也是一颗 chip（与消息 chip 并排），但不计入「N 条」',
  [sendBatch[0].total, sendBatch[0].messages, sendBatch[0].failed], [3, 2, 1])
eq('摘要行认得出来（就一条前缀）', sendBatch[0].summary?.messageId, `${SUMMARY_MESSAGE_PREFIX}batch-C`)
eq('读取批没有摘要行', oneReadBatch[0].summary, null)
eq('摘要行的认出法就一条前缀', [isSummaryRow({ messageId: `${SUMMARY_MESSAGE_PREFIX}x` }),
  isSummaryRow({ messageId: 'm-1' })], [true, false])

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

/* ── 6. 某一步能不能重发（决策 7） ───────────────────────────────── */

eq('读取批那一档不铺「重发这一批」（合并之前的步骤仍是逐条重投）',
  batchRedeliver(oneReadBatch[0]), null)
eq('单条那档更不铺', batchRedeliver(batchGroups([singleStep])[0]), null)
eq('没有组就什么都不铺', batchRedeliver(null), null)
eq('发送批且这一批有失败：一次点击 = 一次请求，N 数的是消息',
  batchRedeliver(sendBatch[0]), { count: 2, label: '重发这一批（2 条）' })
eq('发送批全成时不铺（没什么可重发的）',
  batchRedeliver(batchGroups([
    step({ id: 1, messageId: 'm-1', step: 4, batchId: 'batch-C' }),
    step({ id: 2, messageId: 'm-2', step: 4, batchId: 'batch-C' }),
  ])[0]), null)
eq('按批重发的接口路径指向**摘要行**那一条（一次点击 = 一次请求）',
  redeliverBatchPath(77), '/history/batches/77/redeliver')
eq('重发成功那一句带上条数与新运行',
  [batchRedeliverDone(2, 31), batchRedeliverDone(2, null)],
  ['已重发这一批（2 条），新运行 #31', '已重发这一批（2 条）'])

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
