/**
 * 消息历史页的口径检查（#129 骨架 / #136 消息步检索模式）：不测组件，只测纯逻辑的外部行为。
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
 *   8. 任务名的写法与面板计数。
 */
import {
  countItems, countsParts, countsText, describeFilters, detailPath, emptyFilters, filterNote, firstLine,
  hasAnyFilter, HISTORY_MODES, isRunSelected, isStepSelected, jobOptionLabel, jobTitleOf, listCountText,
  listEmptyCopy, listHint, listQuery, listTitle, matchedKeysView, noSelection, runRows, selectRun,
  selectStep, shortId, stampOf, statusChip, statusParam, statusPlaceholder, stepRows, summaryQuery,
  DEFAULT_MODE, RUN_STATUSES, STATUS_ANY, STATUS_BAD, STATUS_OK, STATUS_WARN,
} from './console.ts'
import type { Job, JobRun, MessageStep } from '../types.ts'

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

console.log(`\n# ${passed} passed, ${failed} failed`)
if (failed > 0) {
  process.exitCode = 1
}
