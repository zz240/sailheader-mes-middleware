/**
 * 「重跑任务」的口径检查（#157）：不测组件，只测纯逻辑的外部行为。
 *
 * 跑法（零新依赖）：
 *   node --experimental-strip-types frontend/src/history/rerun-checks.ts
 *
 * 押的是：
 *   1. **只有 `FAILED` 且拿得到任务标识**才铺——成功 / 运行中 / 没任务标识 / 运行还没取回来都不铺；
 *   2. 按钮上的字是「重跑任务」；
 *   3. 确认文案写清「从未完成的那一批继续」与「目标端会再收到一次该批请求」；
 *   4. 文案里带任务号（按按钮的人知道跑的是谁）。
 */
import { RERUN_LABEL, rerunConfirmText, runRerun } from './rerun.ts'

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

/* ── 1. 失败的运行铺，别的都不铺 ────────────────────────────────────── */

const target = runRerun({ status: 'FAILED', jobId: 7 })
eq('失败运行铺按钮，指向它的任务', target?.jobId, 7)
eq('按钮上的字是「重跑任务」', target?.label, RERUN_LABEL)
eq('成功运行不铺', runRerun({ status: 'SUCCESS', jobId: 7 }), null)
eq('运行中不铺', runRerun({ status: 'RUNNING', jobId: 7 }), null)
eq('拿不到任务标识不铺', runRerun({ status: 'FAILED', jobId: null }), null)
eq('运行还没取回来时不铺', runRerun(null), null)

/* ── 2. 确认框里那两句必须在 ────────────────────────────────────────── */

const confirm = target?.confirm ?? ''
ok('文案带任务号', confirm.includes('#7'), confirm)
ok('文案说从「未完成的那一批」继续', confirm.includes('未完成的那一批'), confirm)
ok('文案说目标端会再收到一次该批请求', confirm.includes('目标端会再收到一次'), confirm)
ok('文案说按业务键去重（至少一次交付）', confirm.includes('业务键去重'), confirm)
eq('文案只有一个出处：函数返回的就是它', confirm, rerunConfirmText(7))

console.log(`\n${failed ? `${failed} failed` : 'all passed'}（${passed} 项）`)
process.exitCode = failed ? 1 : 0
