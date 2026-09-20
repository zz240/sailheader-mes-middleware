/**
 * 消息历史页的口径检查（#129）：不测组件，只测纯逻辑的外部行为。
 *
 * 跑法（零新依赖）：
 *   node --experimental-strip-types frontend/src/history/checks.ts
 *
 * 断言这几件事：
 *   1. 状态词表只有一份，芯片四档（SUCCESS→ok / FAILED→bad / REDELIVERED→warn / 其余→run）与它对得上；
 *   2. 时间戳的两种写法（详情到秒、列表只留时刻）与空值的破折号；
 *   3. 错误在列表里只占一行（原型 `error.split('\n')[0]`）；
 *   4. 运行视图的查询串：**默认不过滤**（运行那一侧与消息步那一侧的不对称在这里钉住），
 *      任务 / 状态 / 业务键选了才带，分页恒在；
 *   5. 空态三套说法分得开（真的没有 / 筛没了 / 接口错了）；
 *   6. 任务名的写法与面板计数。
 */
import {
  describeFilters, emptyFilters, firstLine, hasAnyFilter, jobOptionLabel, jobTitleOf,
  listCountText, listEmptyCopy, runQuery, stampOf, statusChip,
  RUN_STATUSES, STATUS_BAD, STATUS_OK, STATUS_WARN,
} from './console.ts'

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

// 2. 时间戳
eq('ISO 换成空格分隔', stampOf('2026-09-17T09:12:31'), '2026-09-17 09:12:31')
eq('小数秒截掉', stampOf('2026-09-17T09:12:31.123456'), '2026-09-17 09:12:31')
eq('没有时间给破折号', stampOf(undefined), '—')
eq('行上的开始时间与结束时间同一套写法（不再一个只留时刻、一个带日期）',
  [stampOf('2026-09-17T09:12:31'), stampOf('2026-09-17T09:12:34')],
  ['2026-09-17 09:12:31', '2026-09-17 09:12:34'])

// 3. 错误只占一行
eq('多行错误取首行', firstLine('boom\n\tat A.b(C.java:1)'), 'boom')
eq('单行错误原样', firstLine('boom'), 'boom')
eq('没有错误给空串', firstLine(null), '')

// 4. 查询串：运行视图默认不过滤
eq('什么都没选就是纯分页', runQuery(emptyFilters(), 1, 20), 'page=1&size=20')
eq('选了任务才带 jobId', runQuery({ ...emptyFilters(), jobId: 7 }, 1, 20), 'jobId=7&page=1&size=20')
eq('选了状态才带 status（运行视图默认不过滤）',
  runQuery({ ...emptyFilters(), status: 'FAILED' }, 2, 50), 'status=FAILED&page=2&size=50')
eq('运行视图不带默认 FAILED（与消息步那一侧不对称，别在这里「顺手对齐」）',
  runQuery(emptyFilters(), 1, 20).includes('status'), false)
eq('业务键两侧空白不算', runQuery({ ...emptyFilters(), businessKey: '  SO-2026-0917-0042 ' }, 1, 20),
  'businessKey=SO-2026-0917-0042&page=1&size=20')
eq('三样一起选', runQuery({ jobId: 7, status: 'FAILED', businessKey: 'SO-1' }, 3, 10),
  'jobId=7&status=FAILED&businessKey=SO-1&page=3&size=10')

// hasAnyFilter 与筛选描述
eq('没筛选', hasAnyFilter(emptyFilters()), false)
eq('业务键全空白不算筛选', hasAnyFilter({ ...emptyFilters(), businessKey: '   ' }), false)
eq('选了任务就算筛选', hasAnyFilter({ ...emptyFilters(), jobId: 7 }), true)
eq('筛选那句话', describeFilters({ jobId: 7, status: 'FAILED', businessKey: 'SO-1' }), '任务 #7 · 状态 FAILED · 业务键 SO-1')
eq('只有业务键时的筛选那句话', describeFilters({ ...emptyFilters(), businessKey: 'SO-1' }), '业务键 SO-1')

// 5. 空态三套说法
eq('真的还没有', listEmptyCopy({ filters: emptyFilters() }),
  { title: '还没有任何运行记录。', hint: '任务跑过之后，这里会出现它的运行。' })
eq('筛没了（把当前筛选写出来）', listEmptyCopy({ filters: { ...emptyFilters(), status: 'FAILED' } }),
  { title: '没有符合条件的运行。', hint: '当前筛选：状态 FAILED' })
eq('接口错了有第三套说法', listEmptyCopy({ filters: emptyFilters(), error: 'HTTP 500' }),
  { title: '运行列表没取到。', hint: '接口返回了错误，可以重试。' })
eq('接口错了时不冒充「没有数据」',
  listEmptyCopy({ filters: emptyFilters(), error: 'HTTP 500' }).title !== '还没有任何运行记录。', true)

// 6. 任务名与面板计数
eq('任务下拉的写法', jobOptionLabel({ id: 7, name: 'MDM 编码回写（B6 链）' }), '#7 MDM 编码回写（B6 链）')
eq('按 id 说任务名', jobTitleOf([{ id: 3, name: '站点连通性探测' }], 3), '#3 站点连通性探测')
eq('查不到那个任务就只报 id', jobTitleOf([], 9), '#9')
eq('面板计数的写法', listCountText(128), '共 128 条')

console.log(`\n# ${passed} passed, ${failed} failed`)
if (failed > 0) {
  process.exitCode = 1
}
