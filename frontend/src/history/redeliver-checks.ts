/**
 * 断点重投的口径检查（#135）：不测组件，只测纯逻辑的外部行为。
 *
 * 跑法（零新依赖）：
 *   node --experimental-strip-types frontend/src/history/redeliver-checks.ts
 *
 * 押的是 #135 票面与 ADR-0016 决策 11 那几条：
 *   1. **只有 `FAILED` 能重投**（验收项一）——其余四档（`SUCCESS` / `REDELIVERED` / `RUNNING` /
 *      空状态）一律不行，且不可重投那一档要说清「为什么不行」（#108 的语义）；
 *   2. 入口上的字与不可重投那两句话（原型 `sc-foot` 的两个分支）；
 *   3. 弹窗里要写清的那几样（**消息 id、业务键、步骤号、动作、状态**，验收项二）——
 *      业务键与动作的缺省写法与列表行**同一个口径**（不另立一套）；
 *   4. 进行中：确认按钮上的字、弹窗不可关（关了就不知道那一次到底成没成）；
 *   5. 成功后那句、失败后那三句（标题 / 接口原因 / 「原步仍是 FAILED」那半句，验收项五）；
 *   6. 重投那条路的地址只有一个出处。
 */
import {
  canRedeliver, redeliverBlockedNote, redeliverButtonLabel, redeliverClosable, redeliverConfirmLabel,
  redeliverDialogNote, redeliverDoneMessage, redeliverErrorText, redeliverExplain, redeliverFailure,
  redeliverFacts, redeliverHeadline, redeliverOutcomeNote, redeliverPath, redeliverTimeoutText,
  REDELIVER_CANCEL_LABEL, REDELIVER_TIMEOUT_MS, REDELIVER_TITLE,
} from './redeliver.ts'
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

/** 一条失败消息步：票面要看的那几样都在（消息 id / 业务键 / 步骤号 / 动作 / 状态）。 */
const failedStep: MessageStep = {
  id: 91,
  runId: 12,
  messageId: '3f9a1c02-1d4e-4a6b-9c77-0f2b5e8a1d33',
  step: 3,
  actionName: 'upsert',
  status: 'FAILED',
  businessKey: 'SO-2026-0917-0042',
  createdAt: '2026-09-17T09:12:33',
  attempt: 2,
  error: '约束冲突',
}

/* ── 1. 只有 FAILED 能重投 ─────────────────────────────────────────── */

eq('失败步可以重投', canRedeliver(failedStep), true)
eq('成功的步不行', canRedeliver({ ...failedStep, status: 'SUCCESS' }), false)
eq('已经重投过的步不行（REDELIVERED 不是「再投一次」的入口）',
  canRedeliver({ ...failedStep, status: 'REDELIVERED' }), false)
eq('跑着的步不行', canRedeliver({ ...failedStep, status: 'RUNNING' }), false)
eq('状态缺了不行（不编一个「大概是失败」出来）',
  [canRedeliver({ ...failedStep, status: null as unknown as string }),
    canRedeliver({ ...failedStep, status: '  ' }),
    canRedeliver(null)],
  [false, false, false])
eq('状态两侧的空白不算内容', canRedeliver({ ...failedStep, status: '  FAILED  ' }), true)

/* ── 2. 入口与不可重投那一档的说法 ─────────────────────────────────── */

eq('入口上的字写清从哪一步投', redeliverButtonLabel(failedStep), '从步骤 3 断点重投')
eq('不可重投那档说清状态与唯一可投的档（#108 的语义）',
  redeliverBlockedNote({ ...failedStep, status: 'SUCCESS' }),
  '本步状态 SUCCESS，不可重投：只有 FAILED 消息步能断点续跑（#108）。')
eq('重投过的步额外补一句「这一步已经修好了」',
  redeliverBlockedNote({ ...failedStep, status: 'REDELIVERED' }),
  '本步状态 REDELIVERED，不可重投：只有 FAILED 消息步能断点续跑（#108）。这一步的首跑报错已由重投修复。')
eq('跑着的步没有那句额外的',
  redeliverBlockedNote({ ...failedStep, status: 'RUNNING' }),
  '本步状态 RUNNING，不可重投：只有 FAILED 消息步能断点续跑（#108）。')
eq('状态是空的就印破折号，不印 `undefined`',
  redeliverBlockedNote({ ...failedStep, status: null as unknown as string }),
  '本步状态 —，不可重投：只有 FAILED 消息步能断点续跑（#108）。')

/* ── 3. 弹窗写清「哪条消息的哪一步」 ───────────────────────────────── */

const facts = redeliverFacts(failedStep, failedStep.businessKey)
eq('弹窗的事实五样（消息 id / 业务键 / 步骤号 / 动作 / 状态）',
  [facts.runId, facts.messageId, facts.step, facts.action, facts.status, facts.businessKey],
  [12, '3f9a1c02-1d4e-4a6b-9c77-0f2b5e8a1d33', 3, 'upsert', 'FAILED', 'SO-2026-0917-0042'])
eq('消息 id 给的是**完整**那一份（列表那一行没有消息 id，弹窗要能指名道姓）',
  facts.messageId, failedStep.messageId)
eq('业务键缺了写「无业务键」（与列表行同一句）',
  redeliverFacts({ ...failedStep, businessKey: '   ' }, null).businessKey, '无业务键')
eq('业务键取传入的那一份（右详情手里的是分组后的那个值）',
  redeliverFacts(failedStep, 'SO-FROM-GROUP').businessKey, 'SO-FROM-GROUP')
eq('动作缺了写破折号（与列表行同一个写法，同一处 textOrDash）',
  redeliverFacts({ ...failedStep, actionName: '   ' }, null).action, '—')
eq('状态缺了写破折号', redeliverFacts({ ...failedStep, status: '' }, null).status, '—')
eq('消息 id 缺了也写破折号（不印 undefined）',
  redeliverFacts({ ...failedStep, messageId: '   ' }, null).messageId, '—')

eq('弹窗第一句把运行 / 步骤 / 动作 / 业务键都写出来',
  redeliverHeadline(facts),
  '从失败消息步 #12 / 步骤 3 upsert（业务键 SO-2026-0917-0042）断点续跑。')
eq('后果那半句只有一处出处（单步卡与弹窗那段都从它来）',
  [redeliverOutcomeNote().includes('REDELIVERED'), redeliverOutcomeNote().includes('FAILED')],
  [true, true])
eq('弹窗那段交代把两句拼起来（上游快照 + 后果），不是各写一遍',
  redeliverExplain().endsWith(redeliverOutcomeNote()), true)
eq('弹窗里交代清上游快照会被带上（sourceStep 引用上游时结果与首次一致）',
  redeliverExplain().includes('sourceStep'), true)
eq('弹窗底下那句与单步卡那句各有一份（原型两张地方的两句原话）',
  [redeliverDialogNote(), redeliverDialogNote() === redeliverOutcomeNote()],
  ['重投只投这一条消息，成功后原步置 REDELIVERED。', false])

eq('弹窗标题与取消按钮的字', [REDELIVER_TITLE, REDELIVER_CANCEL_LABEL], ['断点重投', '取消'])

/* ── 4. 进行中：按钮的字与「不许关」 ───────────────────────────────── */

eq('确认按钮平常写「确认重投」', redeliverConfirmLabel(false), '确认重投')
eq('进行中按钮写「重投中…」（不是还写「确认重投」让人以为没点上）',
  redeliverConfirmLabel(true), '重投中…')
eq('进行中不许关弹窗（关了就不知道那一次到底成没成）',
  [redeliverClosable(false), redeliverClosable(true)], [true, false])

/* ── 5. 成功后与失败后的说法 ───────────────────────────────────────── */

eq('成功那句写清原步置成了什么、以及「列表与详情都刷新了」',
  redeliverDoneMessage(facts, 'REDELIVERED'),
  '重投完成：运行 #12 的步骤 3 已置 REDELIVERED，列表与详情都刷新了。')
eq('「已置」那个词取接口回来的那一格，不在界面这一侧写死',
  redeliverDoneMessage(facts, 'SUCCESS').includes('已置 SUCCESS'), true)
eq('接口没给状态时写破折号，不编一个 REDELIVERED 出来',
  redeliverDoneMessage(facts, '').includes('已置 —'), true)

const failCopy = redeliverFailure("重投失败: Field 'order_no' doesn't have a default value")
eq('失败那句：有标题、有接口原因、有「原步仍是 FAILED」那半句',
  [failCopy.title, failCopy.detail], ['重投没成功。', "重投失败: Field 'order_no' doesn't have a default value"])
ok('失败那半句不否认「原步仍 FAILED、没标 REDELIVERED」（#108）',
  failCopy.hint.includes('原步仍是 FAILED') && failCopy.hint.includes('REDELIVERED'), failCopy.hint)
ok('失败那半句**不承诺「再投一次就行」**——源动作失败与任务未启用那两档也在这条路上',
  failCopy.hint.includes('源动作') && failCopy.hint.includes('任务未启用'), failCopy.hint)
eq('接口没给原因时不编一个（写清「没给」）',
  redeliverFailure('   ').detail, '接口没有给出原因。')
eq('失败那份也照原样带着接口的原因（不吞掉前半句）',
  redeliverFailure('HTTP 500').detail, 'HTTP 500')

/* ── 5b. 挂住不回的请求也有个尽头（否则进行中弹窗关不掉 = 只能刷页面） ── */

eq('超时上限是个正整数分钟数（2 分钟：容得下 HikariCP 30s × 重试退避）',
  [REDELIVER_TIMEOUT_MS, Math.round(REDELIVER_TIMEOUT_MS / 60_000)], [120_000, 2])
eq('超时那句写清「结果不知道」与下一步（去刷新消息步，而不是再投一次）',
  [redeliverTimeoutText().includes('2 分钟'), redeliverTimeoutText().includes('结果不知道')],
  [true, true])
eq('超时/中断都走超时那句（`AbortSignal.timeout` 抛的就是这两个名字）',
  [redeliverErrorText(new DOMException('x', 'TimeoutError')),
    redeliverErrorText(new DOMException('x', 'AbortError'))],
  [redeliverTimeoutText(), redeliverTimeoutText()])
eq('接口那条错误照原样带出来（`ApiError` 的 message 就是后端信封那句）',
  redeliverErrorText(new Error('重投失败: boom')), '重投失败: boom')
eq('既不是 Error 也没有名字的东西也说得出来（不吞成 `undefined`）',
  redeliverErrorText('炸了'), '炸了')
eq('Error 但没消息时不编原因', redeliverErrorText(new Error('')), '接口没有给出原因。')

/* ── 6. 重投那条路的地址只有一个出处 ───────────────────────────────── */

eq('重投走 POST 到这一步的 redeliver 子路径',
  redeliverPath(91), '/history/steps/91/redeliver')

console.log(`\n# ${passed} passed, ${failed} failed`)
if (failed > 0) {
  process.exitCode = 1
}
