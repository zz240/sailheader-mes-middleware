/**
 * 消息历史页的口径检查（#129 骨架 / #136 消息步检索模式 / #131 消息切换与步骤链）：
 * 不测组件，只测纯逻辑的外部行为。
 *
 * 跑法（零新依赖）：
 *   node --experimental-strip-types frontend/src/history/checks.ts
 *
 * 断言这几件事：
 *   1. 状态词表只有一份，芯片四档（SUCCESS→ok / FAILED→bad / REDELIVERED→warn / 其余→run）与它对得上；
 *   2. 时间戳的两种写法（详情到秒、列表只留时刻）与空值的破折号、长 id 的缩写；
 *   3. 错误在列表里只占一行（原型 `error.split('\n')[0]`）；
 *   4. 两个模式的查询串：运行那一侧**默认不过滤**，消息步那一侧**默认只看 FAILED**（#74），
 *      而「全部状态」是显式的一档、能把那个默认关掉——这条不对称是本节的重点；
 *   5. 空态说法分得开（真的没有 / 筛没了 / 接口错了），且消息步那一侧把状态口径写出来；
 *   6. 两个模式的行怎么写（运行行与消息步行各一次的完整形状）；
 *   7. 选中态：三样（运行 / 消息 / 步骤）都对上才算选中，右详情的路径从它推出来；
 *   8. 任务名的写法与面板计数；
 *   9. 计数条与每运行摘要（#130）；
 *  10. 一条运行的消息步怎么取（#131）：走索引接口、按 total 追页、**没有上限**；
 *  11. 消息分组与步骤链与单步卡（#131）：消息的先后、步骤号升序、快照点、可访问名称、
 *      单步卡的事实格与「错误块保留换行、成功步不留空错误块」。
 */
import {
  chainNodes, chainSummary, countItems, countsParts, countsText, describeFilters, detailPath, emptyFilters,
  filterNote, firstLine, hasAnyFilter, HISTORY_MODES, isRunSelected, isStepSelected, jobOptionLabel, jobTitleOf,
  listCountText, listEmptyCopy, listHint, listQuery, listTitle, matchedKeysView, messageChip, noSelection,
  runDetail, runMessages, runRows, runStepsPages, runStepsPath, selectRun, selectStep, shortId,
  stampOf, statusChip, statusParam, statusPlaceholder, stepCardView, stepRows, summaryQuery,
  DEFAULT_MODE, RUN_STATUSES, RUN_STEPS_PAGE, STATUS_ANY, STATUS_BAD, STATUS_OK, STATUS_WARN,
} from './console.ts'
import {
  DEFAULT_SNAPSHOT_DEPTH, LONG_VALUE_CHARS, SNAPSHOT_DEPTHS, SNAPSHOT_DEEP_ALL, SNAPSHOT_PREVIEW_CHARS,
  SNAPSHOT_TEXT_CAP, deepOf, filterSnapshot, isLongValue, longPreview, readSnapshot, snapshotSides, treeDeep,
} from './snapshot.ts'
import type { Job, JobRun, MessageStep } from '../types.ts'
import type { SnapshotValue } from './snapshot.ts'

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

// 1. 状态词表与芯片四档
eq('状态词表就一份，下拉的顺序也用它', [...RUN_STATUSES], ['FAILED', 'SUCCESS', 'REDELIVERED', 'RUNNING'])
eq('词表与芯片判档对得上',
  [STATUS_OK, STATUS_BAD, STATUS_WARN].map((s) => statusChip(s).kind), ['ok', 'bad', 'warn'])
eq('SUCCESS 是好', statusChip('SUCCESS'), { kind: 'ok', label: 'SUCCESS' })
eq('FAILED 是坏', statusChip('FAILED'), { kind: 'bad', label: 'FAILED' })
eq('REDELIVERED 是告警档（重投过不等于这次成功）',
  statusChip('REDELIVERED'), { kind: 'warn', label: 'REDELIVERED' })
eq('其余状态走中性档', statusChip('RUNNING'), { kind: 'run', label: 'RUNNING' })
eq('空状态给破折号', statusChip(null), { kind: 'run', label: '—' })
eq('状态两侧的空白不算内容', statusChip('  FAILED  '), { kind: 'bad', label: 'FAILED' })

// 2. 时间戳与长 id
eq('ISO 换成空格分隔', stampOf('2026-09-17T09:12:31'), '2026-09-17 09:12:31')
eq('小数秒截掉', stampOf('2026-09-17T09:12:31.123456'), '2026-09-17 09:12:31')
eq('没有时间给破折号', stampOf(undefined), '—')
eq('行上的开始时间与结束时间同一套写法（不再一个只留时刻、一个带日期）',
  [stampOf('2026-09-17T09:12:31'), stampOf('2026-09-17T09:12:34')],
  ['2026-09-17 09:12:31', '2026-09-17 09:12:34'])
eq('消息 id 只留前 8 位', shortId('3f9a1c02-1d4e-4a6b-9c77-0f2b5e8a1d33'), '3f9a1c02…')
eq('本来就短的原样给', shortId('msg-1'), 'msg-1')
eq('没有消息 id 给破折号', shortId(null), '—')

// 3. 错误只占一行
eq('多行错误取首行', firstLine('boom\n\tat A.b(C.java:1)'), 'boom')
eq('单行错误原样', firstLine('boom'), 'boom')
eq('没有错误给空串', firstLine(null), '')

// 4. 查询串：两个模式的口径不对称，且「全部状态」能关掉默认
eq('模式开关就两档、顺序照原型', [...HISTORY_MODES], [
  { value: 'runs', label: '按运行展开' },
  { value: 'steps', label: '消息步检索' },
])
eq('默认进的是运行那一侧', DEFAULT_MODE, 'runs')

eq('什么都没选就是纯分页', listQuery('runs', emptyFilters(), 1, 20), 'page=1&size=20')
eq('选了任务才带 jobId', listQuery('runs', { ...emptyFilters(), jobId: 7 }, 1, 20), 'jobId=7&page=1&size=20')
eq('选了状态才带 status（运行视图默认不过滤）',
  listQuery('runs', { ...emptyFilters(), status: 'FAILED' }, 2, 50), 'status=FAILED&page=2&size=50')
eq('运行视图不带默认 FAILED（与消息步那一侧不对称，别在这里「顺手对齐」）',
  listQuery('runs', emptyFilters(), 1, 20).includes('status'), false)
eq('业务键两侧空白不算',
  listQuery('runs', { ...emptyFilters(), businessKey: '  SO-2026-0917-0042 ' }, 1, 20),
  'businessKey=SO-2026-0917-0042&page=1&size=20')
eq('三样一起选', listQuery('runs', { jobId: 7, status: 'FAILED', businessKey: 'SO-1' }, 3, 10),
  'jobId=7&status=FAILED&businessKey=SO-1&page=3&size=10')

eq('消息步这一侧默认只看 FAILED（#74）',
  listQuery('steps', emptyFilters(), 1, 20), 'status=FAILED&page=1&size=20')
eq('清掉状态不等于放开默认（清掉就是回到默认 FAILED）',
  listQuery('steps', { ...emptyFilters(), status: '' }, 2, 20), 'status=FAILED&page=2&size=20')
eq('明确选「全部状态」才真的不带 status',
  listQuery('steps', { ...emptyFilters(), status: STATUS_ANY }, 1, 20), 'page=1&size=20')
eq('选了具体状态就只看那一个',
  listQuery('steps', { ...emptyFilters(), status: 'SUCCESS' }, 1, 20), 'status=SUCCESS&page=1&size=20')
eq('业务键与分页在消息步这一侧同样有效',
  listQuery('steps', { ...emptyFilters(), businessKey: 'SO-2026', status: STATUS_ANY }, 4, 50),
  'businessKey=SO-2026&page=4&size=50')

// 4b. 计数与运行摘要那条聚合请求（#130）：筛选与列表逐字一致；page/size 只决定「摘要给哪一页」
eq('聚合请求带上同一组筛选与分页',
  summaryQuery(emptyFilters(), '', 1, 20), 'page=1&size=20')
eq('聚合请求的筛选与列表同一套（只有路径不同）',
  summaryQuery({ jobId: 7, status: '', businessKey: ' SO-1 ' }, 'FAILED', 3, 50),
  'jobId=7&status=FAILED&businessKey=SO-1&page=3&size=50')
eq('运行那一侧默认不过滤时聚合请求也不带 status',
  summaryQuery(emptyFilters(), statusParam('runs', emptyFilters()), 1, 20), 'page=1&size=20')
eq('消息步那一侧的默认 FAILED 也带进聚合请求',
  summaryQuery(emptyFilters(), statusParam('steps', emptyFilters()), 1, 20),
  'status=FAILED&page=1&size=20')
eq('「全部状态」在运行那一侧也不带 status',
  listQuery('runs', { ...emptyFilters(), status: STATUS_ANY }, 1, 20), 'page=1&size=20')

eq('口径函数与查询串同一个判决',
  [statusParam('runs', emptyFilters()), statusParam('steps', emptyFilters()),
    statusParam('steps', { ...emptyFilters(), status: STATUS_ANY })],
  ['', 'FAILED', ''])
eq('状态下拉的占位在消息步那一侧就说真实口径（旧实现藏在「全部状态」里）',
  [statusPlaceholder('runs'), statusPlaceholder('steps')], ['全部状态', '默认 FAILED'])
eq('筛选条那句口径：消息步这一侧把状态写出来',
  [filterNote('steps', emptyFilters()),
    filterNote('steps', { ...emptyFilters(), status: STATUS_ANY }),
    filterNote('steps', { ...emptyFilters(), status: 'SUCCESS' })],
  ['消息步检索：默认只看 FAILED（#74） · 业务键按消息步过滤',
    '消息步检索：全部状态 · 业务键按消息步过滤',
    '消息步检索：只看 SUCCESS · 业务键按消息步过滤'])
eq('运行那一侧的筛选条口径不变',
  filterNote('runs', emptyFilters()), '选任务 / 状态即时生效；业务键敲回车或点查询')
eq('面板头：标题与提示都按模式换',
  [listTitle('runs'), listTitle('steps'), listHint('runs'), listHint('steps')],
  ['运行', '消息步', '选一条运行看它的消息与消息步', '默认只看 FAILED（#74）'])

// hasAnyFilter 与筛选描述
eq('没筛选', hasAnyFilter(emptyFilters()), false)
eq('业务键全空白不算筛选', hasAnyFilter({ ...emptyFilters(), businessKey: '   ' }), false)
eq('选了任务就算筛选', hasAnyFilter({ ...emptyFilters(), jobId: 7 }), true)
eq('选了「全部状态」也算做过选择（它关掉了默认 FAILED）',
  hasAnyFilter({ ...emptyFilters(), status: STATUS_ANY }), true)
eq('筛选那句话', describeFilters({ jobId: 7, status: 'FAILED', businessKey: 'SO-1' }),
  '任务 #7 · 状态 FAILED · 业务键 SO-1')
eq('「全部状态」在筛选那句话里写全', describeFilters({ ...emptyFilters(), status: STATUS_ANY }),
  '状态 全部状态')
eq('只有业务键时的筛选那句话', describeFilters({ ...emptyFilters(), businessKey: 'SO-1' }), '业务键 SO-1')

// 5. 空态说法
eq('真的还没有', listEmptyCopy({ mode: 'runs', filters: emptyFilters() }),
  { title: '还没有任何运行记录。', hint: '任务跑过之后，这里会出现它的运行。' })
eq('筛没了（把当前筛选写出来）',
  listEmptyCopy({ mode: 'runs', filters: { ...emptyFilters(), status: 'FAILED' } }),
  { title: '没有符合条件的运行。', hint: '当前筛选：状态 FAILED' })
eq('接口错了有第三套说法', listEmptyCopy({ mode: 'runs', filters: emptyFilters(), error: 'HTTP 500' }),
  { title: '运行列表没取到。', hint: '接口返回了错误，可以重试。' })
eq('接口错了时不冒充「没有数据」',
  listEmptyCopy({ mode: 'runs', filters: emptyFilters(), error: 'HTTP 500' }).title !== '还没有任何运行记录。', true)
eq('运行那一侧「全部状态」不算收窄（没筛窄就不该说「没有符合条件」）',
  listEmptyCopy({ mode: 'runs', filters: { ...emptyFilters(), status: STATUS_ANY } }),
  { title: '还没有任何运行记录。', hint: '任务跑过之后，这里会出现它的运行。' })
eq('运行那一侧只要有一格真的筛窄了，就写「没有符合条件」并把筛选列出来',
  listEmptyCopy({ mode: 'runs', filters: { ...emptyFilters(), jobId: 7, status: STATUS_ANY } }),
  { title: '没有符合条件的运行。', hint: '当前筛选：任务 #7 · 状态 全部状态' })
eq('消息步那一侧没数据时把那句话写出来',
  listEmptyCopy({ mode: 'steps', filters: emptyFilters() }),
  { title: '没有符合条件的消息步。', hint: '默认只看 FAILED（#74），可把状态改成「全部状态」。' })
eq('消息步那一侧筛没了：既有当前筛选、也提默认只看 FAILED',
  listEmptyCopy({ mode: 'steps', filters: { ...emptyFilters(), businessKey: 'SO-404' } }),
  { title: '没有符合条件的消息步。', hint: '当前筛选：业务键 SO-404 · 默认只看 FAILED（#74）' })
eq('状态明确选了就不再提默认 FAILED',
  listEmptyCopy({ mode: 'steps', filters: { ...emptyFilters(), status: STATUS_ANY } }),
  { title: '没有符合条件的消息步。', hint: '当前筛选：状态 全部状态' })
eq('消息步那一侧接口错了也有自己的说法',
  listEmptyCopy({ mode: 'steps', filters: emptyFilters(), error: 'HTTP 500' }).title, '消息步列表没取到。')

// 6. 两个模式的行
const jobs: Job[] = [
  { id: 7, siteId: 1, name: 'MDM 编码回写（B6 链）', status: 1 },
  { id: 3, siteId: 1, name: '站点连通性探测', status: 1 },
]
const runs: JobRun[] = [
  { id: 12, jobId: 7, status: 'FAILED', startedAt: '2026-09-17T09:12:31', finishedAt: '2026-09-17T09:12:34', error: 'boom\n\tat A.b' },
]
const steps: MessageStep[] = [
  {
    id: 91, runId: 12, messageId: '3f9a1c02-1d4e-4a6b-9c77-0f2b5e8a1d33', step: 3, actionName: 'upsert',
    status: 'FAILED', createdAt: '2026-09-17T09:12:33', attempt: 2, businessKey: 'SO-2026-0917-0042',
    jobId: 7, error: '约束冲突\n\tat com.example.A.b(A.java:1)',
  },
  {
    id: 92, runId: 12, messageId: '3f9a1c02-1d4e-4a6b-9c77-0f2b5e8a1d33', step: 4, actionName: null,
    status: 'SUCCESS', createdAt: null, attempt: null, businessKey: '  ', jobId: null,
  },
]

eq('运行行一次算好（芯片 / 任务名 / 两个时刻 / 错误首行）', runRows(runs, jobs), [{
  run: runs[0],
  chip: { kind: 'bad', label: 'FAILED' },
  jobTitle: '#7 MDM 编码回写（B6 链）',
  started: '2026-09-17 09:12:31',
  finished: '2026-09-17 09:12:34',
  errLine: 'boom',
  counts: [{ text: '—', bad: false }],
  keys: [],
}])

const stepRowList = stepRows(steps)
eq('消息步行：运行 id 与步骤号合成一行头', stepRowList.map((r) => r.head), ['#12 · 步骤 3', '#12 · 步骤 4'])
eq('消息步行：状态芯片', stepRowList.map((r) => r.chip), [
  { kind: 'bad', label: 'FAILED' }, { kind: 'ok', label: 'SUCCESS' },
])
eq('消息步行：动作（缺了给破折号）', stepRowList.map((r) => r.action), ['upsert', '—'])
eq('消息步行：业务键（没有就写「无业务键」，空白也算没有）',
  stepRowList.map((r) => r.businessKey), ['SO-2026-0917-0042', '无业务键'])
eq('消息步行：时刻与尝试次数（缺了给破折号与 1）',
  stepRowList.map((r) => [r.at, r.attempt]),
  [['2026-09-17 09:12:33', 2], ['—', 1]])
eq('消息步行：任务 id（投影缺失时写「任务 —」，不写 #null）',
  stepRowList.map((r) => r.job), ['任务 #7', '任务 —'])
eq('消息步行：错误只占一行（成功步没有错误行）',
  stepRowList.map((r) => r.errLine), ['约束冲突', ''])

// 7. 选中态与右详情路径
eq('什么都没选', noSelection(), { runId: null, messageId: null, step: null })
eq('选运行只定运行', selectRun(12), { runId: 12, messageId: null, step: null })
eq('选消息步三样一起定', selectStep(steps[0]),
  { runId: 12, messageId: '3f9a1c02-1d4e-4a6b-9c77-0f2b5e8a1d33', step: 3 })
eq('运行模式按 runId 认选中', isRunSelected(selectRun(12), 12), true)
eq('别的运行不算选中', isRunSelected(selectRun(12), 13), false)
eq('消息步要三样都对上才算选中', isStepSelected(selectStep(steps[0]), steps[0]), true)
eq('同运行同消息、步骤不同不算选中（别把一条消息的所有步都点亮）',
  isStepSelected(selectStep(steps[0]), steps[1]), false)
eq('同运行同步骤、消息不同不算选中',
  isStepSelected(selectStep(steps[0]), { ...steps[0], messageId: 'other' }), false)
eq('没选中任何消息步时也不点亮', isStepSelected(noSelection(), steps[0]), false)
eq('什么都没选时路径给破折号', detailPath(noSelection()), '—')
eq('只选了运行，路径就写到运行', detailPath(selectRun(12)), '#12')
eq('选中消息步后路径写清运行 / 消息 / 步骤（原型 detailPath）',
  detailPath(selectStep(steps[0])), '#12 · 消息 3f9a1c02… · 步骤 3')

// 8. 任务名与面板计数
eq('任务下拉的写法', jobOptionLabel({ id: 7, name: 'MDM 编码回写（B6 链）' }), '#7 MDM 编码回写（B6 链）')
eq('按 id 说任务名', jobTitleOf([{ id: 3, name: '站点连通性探测' }], 3), '#3 站点连通性探测')
eq('查不到那个任务就只报 id', jobTitleOf([], 9), '#9')
eq('面板计数的写法', listCountText(128), '共 128 条')

// 9. 计数条与运行摘要（#130）
eq('计数条四项、顺序照原型', countItems({ runs: 12, messages: 30, steps: 88, failedSteps: 3 }),
  [{ key: 'runs', label: '运行', value: 12 }, { key: 'messages', label: '消息', value: 30 },
    { key: 'steps', label: '消息步', value: 88 }, { key: 'failed', label: '失败步', value: 3 }])
eq('还没取到计数时一项都不编', countItems(null), [])
eq('计数全是 0 也是真计数（与「没取到」分得开）',
  countItems({ runs: 0, messages: 0, steps: 0, failedSteps: 0 }).map((i) => i.value), [0, 0, 0, 0])

const summaryOf = (n: number, failed: number, keys: string[] = []) => ({
  runId: runs[0].id, messageCount: n, stepCount: n * 2, failedSteps: failed, matchedBusinessKeys: keys,
})
eq('行摘要按票面写法', countsText(summaryOf(3, 1)), '3 消息 · 6 步 · 失败 1')
eq('失败步为 0 时不出现「失败 0」这段噪声', countsText(summaryOf(3, 0)), '3 消息 · 6 步')
eq('一条消息都没有的运行写全 0（不是破折号）', countsText(summaryOf(0, 0)), '0 消息 · 0 步')
eq('行摘要切段：只有失败那一段是红的', countsParts(summaryOf(3, 1)),
  [{ text: '3 消息', bad: false }, { text: '6 步', bad: false }, { text: '失败 1', bad: true }])
eq('没有失败步时切段里也就没有红的那一段', countsParts(summaryOf(3, 0)),
  [{ text: '3 消息', bad: false }, { text: '6 步', bad: false }])
eq('整句就是切段拼出来的（一处说法，改标签不会只改一半）',
  countsText(summaryOf(3, 1)), countsParts(summaryOf(3, 1)).map((p) => p.text).join(' · '))

eq('命中业务键照原样给标签', matchedKeysView(['SO-1', ' SO-2 ']), ['SO-1', 'SO-2'])
eq('空业务键写「无业务键」（与消息步行同一个口径）', matchedKeysView(['']), ['无业务键'])
eq('一条都没有时整块不铺', matchedKeysView(undefined), [])

eq('运行行带上摘要与命中业务键',
  runRows(runs, jobs, [summaryOf(3, 1, ['SO-2026-0917-0042'])]).map((r) => [r.counts, r.keys]),
  [[[{ text: '3 消息', bad: false }, { text: '6 步', bad: false }, { text: '失败 1', bad: true }],
    ['SO-2026-0917-0042']]])
eq('没有这条运行的摘要时写破折号（不是 0 消息 · 0 步）',
  runRows(runs, jobs, [{ ...summaryOf(1, 0), runId: 999 }]).map((r) => r.counts.map((p) => p.text)),
  [['—']])

// 10. 一条运行的消息步怎么取（#131：那个「只取前 100 个」的隐藏上限在这一票里去掉了）
eq('一页 500：索引行不带快照，一次能多拿一点', RUN_STEPS_PAGE, 500)
eq('取一条运行的消息步走索引那条路（runId 在路径里，不是查询参数）',
  runStepsPath(12, 2), '/history/runs/12/steps?page=2&size=500')
eq('刚好一页就只取一页', runStepsPages(500), 1)
eq('一条都没有也要问一次（才知道「真的没有」）', runStepsPages(0), 1)
eq('多一条就追第二页', runStepsPages(501), 2)
eq('库里最大的一条运行（720 步）两次拿完', runStepsPages(720), 2)
eq('**没有上限**：两万步也就是 40 页，不是「只取前 N 个」', runStepsPages(20_000), 40)

// 11. 消息切换与步骤链（#131）。索引那条路的行只带「快照落没落」两个布尔（没有快照原文），
//     夹具因此照索引的形状摆——步骤链上那两个小点问的就是这两格。
const runStepData: MessageStep[] = [
  // 索引接口给的是 **id 升序**（运行的步骤顺序）；这里仍按 id 打乱摆，考的是分组自己会归一
  { id: 95, runId: 12, messageId: 'msg-B', step: 3, actionName: 'upsert', status: 'SUCCESS', businessKey: 'SO-B' },
  { id: 94, runId: 12, messageId: 'msg-A', step: 5, actionName: 'push', status: 'FAILED', businessKey: 'SO-A',
    error: '约束冲突\n\tat com.example.A.b(A.java:1)\n\tat com.example.A.a(A.java:2)',
    hasInputSnapshot: true, hasDataSnapshot: false, createdAt: '2026-09-17T09:12:35', attempt: 2 },
  { id: 93, runId: 12, messageId: 'msg-B', step: 1, actionName: 'read', status: 'SUCCESS', businessKey: 'SO-B',
    hasInputSnapshot: true, hasDataSnapshot: true, createdAt: '2026-09-17T09:12:34' },
  { id: 91, runId: 12, messageId: 'msg-A', step: 3, actionName: '  ', status: 'SUCCESS', businessKey: '  ',
    hasInputSnapshot: false, hasDataSnapshot: false, createdAt: null },
]
const msgs = runMessages(runStepData)
eq('消息按落库先后排（接口的 id 倒序先翻回来）', msgs.map((m) => m.messageId), ['msg-A', 'msg-B'])
eq('消息之内的步骤号升序（票面：步骤链按步骤号升序）', msgs.map((m) => m.steps.map((s) => s.step)),
  [[3, 5], [1, 3]])
eq('失败步数按消息各数一遍', msgs.map((m) => m.failed), [1, 0])
eq('chip：有失败步写「失败 N 步」、全通写「全通」', msgs.map((m) => m.chip),
  [{ kind: 'bad', label: '失败 1 步' }, { kind: 'ok', label: '全通' }])
eq('chip 的判决只在一个地方', [messageChip(0), messageChip(2)],
  [{ kind: 'ok', label: '全通' }, { kind: 'bad', label: '失败 2 步' }])
eq('业务键取该消息上第一个非空的（空白不算）', msgs.map((m) => m.businessKey), ['SO-A', 'SO-B'])
eq('一条消息一步都没有时整个列表是空的', runMessages([]), [])
eq('业务键全空白的消息写「无业务键」',
  runMessages([{ id: 1, runId: 1, messageId: 'm', step: 1, status: 'SUCCESS', businessKey: '  ' }])
    .map((m) => m.businessKey),
  ['无业务键'])

const detailA = runDetail(runStepData, selectStep({ runId: 12, messageId: 'msg-A', step: 5 }))
eq('选中哪条消息就是哪条', detailA.index, 0)
eq('选中的步骤就在那一条消息里', detailA.step?.step, 5)
eq('没选消息时归到第 1 条（原型 cur() 的下标 0）',
  runDetail(runStepData, selectRun(12)).message?.messageId, 'msg-A')
eq('选中的消息不在了也归到第 1 条（换页 / 换运行之后）',
  runDetail(runStepData, { runId: 12, messageId: 'msg-GONE', step: 9 }).index, 0)
eq('选中的步骤不在这条消息里就归到它的第 1 步（换消息之后）',
  runDetail(runStepData, { runId: 12, messageId: 'msg-B', step: 5 }).step?.step, 1)
eq('一条消息都没有时右栏没有当前消息',
  runDetail([], selectRun(12)), { messages: [], index: 0, message: null, step: null })

const chain = chainNodes(msgs[0], detailA.step)
eq('链头第一格永远是 context[0] 任务输入（原型 node0）',
  [chain[0].head, chain[0].action, chain[0].note, chain[0].step],
  ['0 · 任务输入', 'context[0]', '本次运行的触发报文', null])
eq('步骤链节点数 = 这条消息的消息步数 + 入口那格', chain.length, msgs[0].steps.length + 1)
eq('步骤链按步骤号升序', chain.slice(1).map((n) => n.n), [3, 5])
eq('失败步那一格看得出来（芯片是坏档）', chain.map((n) => n.chip.kind), ['run', 'ok', 'bad'])
eq('选中态只落在选中的那一步上', chain.map((n) => n.selected), [false, false, true])
eq('刚点开运行、选中态里还没有步骤时，链上亮的也是归一之后的第 1 步（原型 open-run 的先归一）',
  chainNodes(msgs[0], runDetail(runStepData, selectRun(12)).step).map((n) => n.selected),
  [false, true, false])
eq('两个快照点亮不亮按索引回的那两个布尔（索引里没有快照原文）',
  chain.slice(1).map((n) => [n.hasInput, n.hasData]), [[false, false], [true, false]])
eq('布尔缺了（老接口 / 没给）当没落，不编一个亮着的点出来',
  chainNodes({ messageId: 'm', businessKey: '无业务键', failed: 0, chip: { kind: 'ok', label: '全通' },
    steps: [{ id: 1, runId: 1, messageId: 'm', step: 1, status: 'SUCCESS' }] }, null)
    .slice(1).map((n) => [n.hasInput, n.hasData]),
  [[false, false]])
eq('没有动作名的那一格写破折号', chain[1].action, '—')
eq('可访问名称写清步骤号 / 动作 / 状态 / 两个快照点',
  chain[2].label, '步骤 5 · 动作 push · 状态 FAILED · 输入快照 有 · 数据快照 无')
eq('入口那格的可访问名称',
  chain[0].label, '步骤 0 · 任务输入 · context[0] · 本次运行的触发报文')
eq('没有消息时链上只剩入口那一格，不整块抽掉', chainNodes(null, null).length, 1)
eq('链头右端那句：几步 + 入口动作',
  [chainSummary(msgs[0]), chainSummary(null)], ['2 步 · 入口 —', '还没有消息步'])

const card = stepCardView(msgs[0].steps[1])
eq('单步卡：步骤号 / 动作 / 状态芯片',
  [card?.n, card?.action, card?.chip], [5, 'push', { kind: 'bad', label: 'FAILED' }])
eq('单步卡的事实格：尝试 / 落库 / 输出（失败步没写上下文）', card?.facts,
  [{ label: '尝试', value: '2' }, { label: '落库', value: '2026-09-17 09:12:35' },
    { label: '输出', value: 'context[5]（未写）' }])
eq('成功步写「输出 context[N]」不带「未写」',
  stepCardView(msgs[1].steps[0])?.facts[2].value, 'context[1]')
eq('错误块**整份保留换行**（堆栈要能直接贴给开发）', card?.error,
  '约束冲突\n\tat com.example.A.b(A.java:1)\n\tat com.example.A.a(A.java:2)')
eq('只把第一行切出来加粗，其余原样',
  [card?.errorFirst, card?.errorRest],
  ['约束冲突', '\n\tat com.example.A.b(A.java:1)\n\tat com.example.A.a(A.java:2)'])
eq('单行错误没有「其余各行」', stepCardView({ ...msgs[0].steps[1], error: 'boom' })?.errorRest, '')
eq('成功步没有错误块（不留一个空的）',
  stepCardView(msgs[1].steps[0])?.error, '')
eq('只有空白的错误也当没有', stepCardView({ ...msgs[0].steps[1], error: '   ' })?.error, '')
eq('尝试次数缺了给 1（与消息步行同一个口径）',
  stepCardView(msgs[1].steps[0])?.attempt, 1)
eq('没选中步骤时不出单步卡', stepCardView(null), null)

// 12. 快照面板的三种降级（#132）：口径全在 `snapshot.ts` —— 什么算非 JSON、什么算空、
//     多长算超长。组件与真机脚本都从它取，界面里不再各判一遍。
eq('超长的界线就是那条 TEXT 列的上限（#127）', SNAPSHOT_TEXT_CAP, 65535)
eq('超长那一档只画前 8 KB 的原文', SNAPSHOT_PREVIEW_CHARS, 8192)

eq('空白当「没落」，不是「空对象」——与步骤链上那两个点同一个口径',
  ['', '   ', '\n', null, undefined].map((raw) => readSnapshot(raw).kind),
  ['missing', 'missing', 'missing', 'missing', 'missing'])
eq('「没落」那一档不编任何说明（原型的 missing 是 fixture 写的现场解释，生产里没有这条信息）',
  [readSnapshot('').note, readSnapshot('').text, readSnapshot('').value, readSnapshot('').emptyLabel],
  ['', '', undefined, ''])

eq('合法 JSON 走格式化树那一档',
  ['{"a":1}', ' {"a":1}\n', '[]', '{}'].map((raw) => readSnapshot(raw).kind),
  ['json', 'json', 'empty', 'empty'])
eq('解析出来的值原样交给树', readSnapshot('{"a":[1,2]}').value, { a: [1, 2] })
eq('标量 JSON 也是 JSON（树里那格「（标量）」）',
  ['123', '"x"', 'true', 'null'].map((raw) => readSnapshot(raw).kind),
  ['json', 'json', 'json', 'json'])
eq('JSON 那一档不写降级说明', readSnapshot('{"a":1}').note, '')
eq('原文照留（复制那一头要的是它，不是解析结果）',
  [readSnapshot('{"a":1}').raw, readSnapshot('hello').raw], ['{"a":1}', 'hello'])

eq('非 JSON 文本不当 JSON 解析、原样显示',
  [readSnapshot('hello world').kind, readSnapshot('hello world').text],
  ['text', 'hello world'])
eq('半截 JSON 也是非 JSON 文本（不是「解析失败就当空」）',
  ['{"a":', '{a:1}', 'undefined'].map((raw) => readSnapshot(raw).kind),
  ['text', 'text', 'text'])
eq('非 JSON 那一档把后果写出来（回填只认 JSON 值，下游取到空）',
  readSnapshot('hello').note,
  '非 JSON 文本：解析不出 JSON 值，断点续投回填时它进不了上下文，下游引用该步只会取到空（#90 / #123 口径）。')

eq('空对象给出明确提示（不是一片空白）',
  [readSnapshot('{}').emptyLabel, readSnapshot('{ }').emptyLabel, readSnapshot('[]').emptyLabel],
  ['空对象 {}', '空对象 {}', '空数组 []'])
eq('空那一档的说明写清「合法但是空的」与它的后果',
  [readSnapshot('{}').note, readSnapshot('[]').note],
  ['空对象 {}：这一份快照是合法 JSON，但一个字段都没有——下游按路径取它下面的值只会拿到空（#90 口径）。',
    '空数组 []：这一份快照是合法 JSON，但一项都没有——下游按下标取它里面的值只会拿到空（#90 口径）。'])
eq('空那一档照样把结果给树（`{}` 也要画出来，不是只留一句提示）', readSnapshot('{}').value, {})

const hugeSnap = 'x'.repeat(SNAPSHOT_TEXT_CAP + 1)
eq('超过列上限就是超长那一档（**不**尝试解析）', readSnapshot(hugeSnap).kind, 'oversized')
eq('超长只把前 8 KB 交给界面（页面不卡死）',
  [readSnapshot(hugeSnap).text.length, readSnapshot(hugeSnap).value], [SNAPSHOT_PREVIEW_CHARS, undefined])
eq('超长那一档原文全文还在手里（复制走全文，不是截断那半段）',
  readSnapshot(hugeSnap).raw.length, SNAPSHOT_TEXT_CAP + 1)
eq('超长那一档注明共多少字符、截到多少',
  readSnapshot(hugeSnap).note,
  '超长快照：共 65536 字符，超过 64 KB 的列上限（#127）——这里只画前 8192 字符。')
eq('刚好到上限不算超长（界线是列上限本身）', readSnapshot('x'.repeat(SNAPSHOT_TEXT_CAP)).kind, 'text')

// 13. 展开层数与键路径搜索（#132）：ADR-0016 决策 10 那处有意偏离的落法——
//     不引 `<mark>`，改成「按路径过滤 + 把命中分支整个展开」。
eq('展开层数三档、原型同序', [...SNAPSHOT_DEPTHS],
  [{ value: '1', label: '1 层' }, { value: '2', label: '2 层' }, { value: 'all', label: '全部' }])
eq('默认两层（原型 `state.depth = 2`）', DEFAULT_SNAPSHOT_DEPTH, '2')
eq('库的 deep 比「层数」大一（库判的是 `node.level >= deep`，根那行是 0 层）',
  [deepOf('1'), deepOf('2'), deepOf('all')], [2, 3, SNAPSHOT_DEEP_ALL])
eq('搜了就整个展开（不然命中藏在折叠里——决策 10 的「自动展开到命中路径」）',
  [treeDeep('2', 'handle'), treeDeep('2', ''), treeDeep('2', '   ')], [SNAPSHOT_DEEP_ALL, 3, 3])

const treeData: SnapshotValue = {
  FCode: 'SH-CODE-0001',
  nested: { handle: 'MD-SH-0001', other: 2 },
  codes: [{ handle: 'a' }, { keep: 'b' }],
}
/** 按路径取一格：断言里要看的都是原值，不重新算一遍（那样就成了同义反复）。 */
function atPath(value: unknown, ...keys: string[]): unknown {
  return keys.reduce<unknown>((node, key) => (node as Record<string, unknown> | undefined)?.[key], value)
}

eq('没搜就是原值（一个键都不动）', filterSnapshot(treeData, '').value, treeData)
eq('只留命中的分支与它的祖先，别的键整个不见',
  filterSnapshot(treeData, 'nested.handle').value, { nested: { handle: 'MD-SH-0001' } })
eq('命中数 = 路径含这个片段的节点数（照原型 walk 里那个 onMatch 数）',
  filterSnapshot(treeData, 'handle').matches, 2)
eq('大小写不敏感（原型 toLowerCase 那一句）', filterSnapshot(treeData, 'HANDLE').matches, 2)
eq('一条都不命中时树是空的、命中数是 0（空容器，不是 `undefined`——那不是「没有快照」那一档）',
  filterSnapshot(treeData, 'zzz'), { value: {}, matches: 0, arraysTrimmed: false })
eq('命中数是 0 的数组根给空数组（同型容器，不是 `undefined`）',
  filterSnapshot([{ handle: 'a' }], 'zzz').value, [])
eq('命中的值原样保留（树里看到的、复制的都是原值）',
  atPath(filterSnapshot(treeData, 'handle').value, 'nested', 'handle'), 'MD-SH-0001')
eq('片段命中那一格时，它下面整棵子树都留下（路径是前缀匹配，孩子自然也含这个片段）',
  filterSnapshot(treeData, 'nested').value, { nested: { handle: 'MD-SH-0001', other: 2 } })
eq('数组被裁过就折成原下标做键的对象（不然界面上的 [0] 与快照里的 [7] 不是一回事）',
  atPath(filterSnapshot(treeData, 'handle').value, 'codes'), { 0: { handle: 'a' } })
eq('折过数组这件事要标出来（说明里得交代一句）',
  filterSnapshot(treeData, 'handle').arraysTrimmed, true)
eq('数组整个留下时还是数组，也不必交代',
  [filterSnapshot({ codes: [{ handle: 'a' }] }, 'handle').value,
    filterSnapshot({ codes: [{ handle: 'a' }] }, 'handle').arraysTrimmed],
  [{ codes: [{ handle: 'a' }] }, false])
eq('标量快照没有键路径可搜：原样留着（「搜不了」不是「被搜没了」）',
  filterSnapshot('hello', 'zzz'), { value: 'hello', matches: 0, arraysTrimmed: false })

const snapStep = {
  id: 94,
  runId: 12,
  messageId: 'msg-A',
  step: 5,
  status: 'FAILED',
  inputSnapshot: '{"FCode":"SH-1","nested":{"handle":"MD-1"}}',
  dataSnapshot: 'not json at all',
}
const sidesPlain = snapshotSides(snapStep, '')
eq('两块：输入在前、数据在后（原型 `.snaps` 的并排顺序）',
  sidesPlain.map((s) => s.which), ['input', 'data'])
eq('两块的标题与去向照原型',
  sidesPlain.map((s) => [s.title, s.where]),
  [['输入快照', '进入本步的报文'], ['数据快照', '本步输出 → context[5]']])
eq('输入那一块给树、数据那一块走非 JSON 降级',
  sidesPlain.map((s) => s.read.kind), ['json', 'text'])
eq('没搜索时树就是原值', sidesPlain[0].tree, { FCode: 'SH-1', nested: { handle: 'MD-1' } })
eq('没搜索时两块都没有搜索那句话', sidesPlain.map((s) => s.searchNote), ['', ''])
eq('复制：JSON 那一块给格式化后的 JSON（不是原文那一行）',
  sidesPlain[0].copyText, '{\n  "FCode": "SH-1",\n  "nested": {\n    "handle": "MD-1"\n  }\n}')
eq('复制：非 JSON 那一块写「复制原文」（写「复制 JSON」是撒谎）',
  sidesPlain.map((s) => s.copyLabel), ['复制 JSON', '复制原文'])
eq('复制那一格有档位（组件按它分派，不拿展示文案当判据）',
  sidesPlain.map((s) => s.copyKind), ['json', 'text'])
eq('复制：原文那一块给的就是原文', sidesPlain[1].copyText, 'not json at all')
eq('「原始文本」那一档给的是整份格式化 JSON（与树那份分开）',
  sidesPlain[0].rawText, '{\n  "FCode": "SH-1",\n  "nested": {\n    "handle": "MD-1"\n  }\n}')
eq('搜索只改树，不改「原始文本」那一份（那是整份，不是过滤后的那半份）',
  snapshotSides(snapStep, 'handle')[0].rawText, sidesPlain[0].rawText)
eq('没落那一块不铺复制按钮（没有东西可复制）',
  [snapshotSides({ ...snapStep, inputSnapshot: '' }, '')[0].copyLabel,
    snapshotSides({ ...snapStep, inputSnapshot: '' }, '')[0].copyKind],
  ['', ''])
eq('空对象那一块照样复制（`{}` 也是合法的 JSON）',
  snapshotSides({ ...snapStep, inputSnapshot: '{}' }, '')[0].copyText, '{}')
eq('没有这一步就一块都不铺', snapshotSides(null, ''), [])

const sidesSearched = snapshotSides(snapStep, 'handle')
eq('搜索后树是过滤过的', sidesSearched[0].tree, { nested: { handle: 'MD-1' } })
eq('搜索那句写在**每一块**里（各自数自己的命中）',
  sidesSearched.map((s) => s.searchNote),
  ['“handle” 命中 1 处键路径，命中分支已自动展开', '“handle”：这一段不是 JSON 文本，没有键路径可搜。'])
eq('非 JSON 那一块也得回一句（搜索框是两块共用的，敲了字一点反应都没有像坏了）',
  snapshotSides(snapStep, 'handle')[1].searchNote, '“handle”：这一段不是 JSON 文本，没有键路径可搜。')
eq('没落那一块不回话（那一块本来就没东西可搜）',
  snapshotSides({ ...snapStep, inputSnapshot: '' }, 'handle')[0].searchNote, '')
eq('折过数组就补一句口径（`{"7": …}` 与 `[7]` 是一回事）',
  snapshotSides({ ...snapStep, inputSnapshot: '{"rows":[{"handle":"a"},{"x":1}]}' }, 'handle')[0].searchNote,
  '“handle” 命中 1 处键路径，命中分支已自动展开 · 数组按原下标保留为键（界面上写成对象）')
eq('搜索那几句不吃空白（`  handle  ` 与 `handle` 同一件事）',
  snapshotSides(snapStep, '  handle  ')[0].searchNote, sidesSearched[0].searchNote)

eq('长值的界线是 72 字（原型那 72）', LONG_VALUE_CHARS, 72)
eq('只有字符串谈得上「长值」',
  [isLongValue('x'.repeat(80)), isLongValue('x'.repeat(72)), isLongValue(80), isLongValue({ a: 1 })],
  [true, false, false, false])
eq('长值截断后带引号带省略号（与库里那份 `"…"` 写法对齐）',
  [longPreview('x'.repeat(80)), longPreview('abc')], [`"${'x'.repeat(72)}…"`, '"abc"'])

console.log(`\n# ${passed} passed, ${failed} failed`)
if (failed > 0) {
  process.exitCode = 1
}
