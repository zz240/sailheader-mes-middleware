/*
 * #134 真机 UI 验收：消息历史页的**耗时**（三处）。
 *
 * 票面（#128 子票 / ADR-0016 决策 7）与验收项：
 *   ① 步骤链上能看到耗时且**非空**；
 *   ② 界面三处（运行行 / 步骤链节点 / 单步卡）显示的是同一个口径，**不出现两处不一致**；
 *   ③ 没落库（V4 之前的老数据）那一格显示 `—`，**不是** `0 ms`；
 *   ④ 失败那一步也有耗时（失败往往正是要看耗时的那一步）。
 *
 * 三处的口径**不是同一个数**，脚本也照这个来判：
 *   - 运行行 = **运行耗时**（`finishedAt - startedAt`，跑着写「运行中」）；
 *   - 步骤链节点与单步卡 = 该步的**动作耗时**（`message_step.duration_ms`），两处必须一字不差。
 *
 * 跑法（playwright 是本目录自带的，先 `npm i` 一次就行）：
 *   cd tools/ui && node message-history-134.cjs
 * 跑之前前端 dev server（5173）与后端（8080）都要在。
 * **后端要是旧进程**（响应里没有 `durationMs` 这一格）那是真没过，不装成 SKIP——
 * 与 #133 同一条口径；而「库里还没有一条新链落过耗时」是数据不够，照实 SKIP。
 *
 * 裁判是脚本自己：耗时那个数**不抄前端的格式化**，只按「形状对得上 + 与接口给的那个数在
 * 四舍五入误差内 + 两处一字不差」判——把实现抄进脚本当期望值等于自己给自己盖章（#133 那句话）。
 */
const fs = require('fs')
const path = require('path')
const { chromium } = require('playwright')

const APP = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080/api'
const shots = path.join(__dirname, 'shots')
const failures = []
let browser = null

function check(name, cond, detail = '') {
  console.log(`${cond ? 'ok  ' : 'NOT '} - ${name}${cond ? '' : ` :: ${detail}`}`)
  if (!cond) failures.push(name)
}

const skip = name => console.log(`SKIP - ${name}`)

let requests = []

async function settle(page, ms = 2500) {
  const start = requests.length
  const deadline = Date.now() + ms
  while (Date.now() < deadline) {
    await page.waitForTimeout(120)
    if (requests.length > start) { await page.waitForTimeout(300); return }
  }
}

async function waitForList(page) {
  await page.waitForSelector('.hc-ritem, .hc-list-empty, .hc-errbox', { timeout: 20000 })
}

async function waitForDetail(page) {
  await page.waitForSelector('.hc-mchip, .hc-dbody .hc-list-empty, .hc-dbody .hc-errbox',
    { timeout: 30000 })
}

async function apiGet(pathAndQuery) {
  const res = await fetch(`${API}${pathAndQuery}`)
  const body = await res.json().catch(() => null)
  return res.ok && body && body.code === 'OK' ? body.data : null
}

/** 按消息分组（与界面同一个口径：消息之间按落库先后，消息之内按步骤号升序）。 */
function groupMessages(steps) {
  const by = new Map()
  for (const s of [...steps].sort((a, b) => a.id - b.id)) {
    if (!by.has(s.messageId)) by.set(s.messageId, [])
    by.get(s.messageId).push(s)
  }
  return [...by.entries()].map(([messageId, list]) => ({
    messageId,
    steps: [...list].sort((a, b) => a.step - b.step),
  }))
}

/* ── 裁判：耗时的字 ──────────────────────────────────────────────────────────
 * 只认形状（`N ms` / `N.N s`）与数（四舍五入到那一档之后的误差 ≤ 50 ms），
 * **不**在前端那份格式化之外再造一个「期望字符串」——那是抄实现。 */
const COST_SHAPE = /^(\d+(?:\.\d+)?) (ms|s)$/

function parseCost(text) {
  const m = COST_SHAPE.exec((text || '').trim())
  if (!m) return null
  return m[2] === 'ms' ? Number(m[1]) : Number(m[1]) * 1000
}

/** 界面上那个字与接口给的那个数对得上吗（`s` 那一档只保留一位小数，允许 ±50 ms）。 */
function costMatches(text, ms) {
  const shown = parseCost(text)
  if (shown === null) return false
  return Math.abs(shown - ms) <= 50
}

/* ── 页面上的零件 ─────────────────────────────────────────────────────────── */

const nodes = page => page.locator('.hc-chain .hc-cbtn:not(.hc-cbtn-entry)')
const costs = page => nodes(page).locator('.hc-ccost')

async function ensureRunsMode(page) {
  const runs = page.locator('.hc-seg .el-radio-button').filter({ hasText: '按运行展开' }).first()
  const active = await runs.evaluate(el => el.classList.contains('is-active')).catch(() => false)
  if (!active) {
    await runs.click()
    await settle(page)
    await waitForList(page)
  }
}

async function openRunByBusinessKey(page, runId, businessKey, maxPages = 5) {
  await ensureRunsMode(page)
  if (businessKey) {
    const biz = page.getByPlaceholder('SO-2026-0917-0042')
    await biz.fill(businessKey)
    await biz.press('Enter')
    await settle(page)
    await waitForList(page)
  }
  for (let p = 0; p < maxPages; p += 1) {
    const ids = (await page.locator('.hc-ritem .hc-r-id').allInnerTexts())
      .map(t => Number(String(t).replace(/[^0-9]/g, '')))
    const idx = ids.indexOf(runId)
    if (idx >= 0) {
      await page.locator('.hc-ritem').nth(idx).click()
      await waitForDetail(page)
      return true
    }
    const next = page.locator('.hc-pfoot .el-pagination button.btn-next')
    if (await next.count() === 0 || await next.first().isDisabled()) break
    await next.first().click()
    await settle(page)
    await waitForList(page)
  }
  return false
}

/** 单步卡上「耗时」那一格的字。 */
async function cardCost(page) {
  const fact = page.locator('.hc-card .hc-fact').filter({ hasText: '耗时' })
  if (await fact.count() === 0) return null
  return (await fact.first().innerText()).replace(/^耗时\s*/, '').trim()
}

async function clickNode(page, n) {
  const before = requests.length
  await nodes(page).nth(n).click()
  const deadline = Date.now() + 15000
  while (Date.now() < deadline && requests.length <= before) await page.waitForTimeout(80)
  await page.waitForTimeout(250)
}

async function main() {
  browser = await chromium.launch()
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const page = await context.newPage()
  page.on('request', req => requests.push({ url: req.url() }))

  // 造数据用的补丁：按**步骤 id** 改索引那一份响应里的 `durationMs`（其余字段仍是后端给的真东西）。
  // 真库里本来就有值的，一格都不改——那种情况直接用真值判。
  let durationOverride = null
  await page.route(url => /\/api\/history\/runs\/\d+\/steps/.test(url.pathname), async route => {
    const res = await route.fetch()
    let body = await res.json().catch(() => null)
    if (durationOverride && body && body.data && Array.isArray(body.data.records)) {
      body.data.records = body.data.records.map(row =>
        Object.prototype.hasOwnProperty.call(durationOverride, row.id)
          ? { ...row, durationMs: durationOverride[row.id] }
          : row)
    }
    await route.fulfill({ response: res, json: body })
  })

  await page.goto(`${APP}/history`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.hc-page', { timeout: 20000 })
  await waitForList(page)
  fs.mkdirSync(shots, { recursive: true })

  // ── 找一条运行来演示：**优先挑真的落过耗时的那条**（那才是后端在计时），
  //    挑不出就在步数最多的那条上用 route 补丁造一个（造的是输入，不是期望值） ──
  let run = null
  let messages = []
  let mi = -1
  let allRecords = []
  let measuredStep = null
  let oldPick = null
  {
    const list = await apiGet('/history/runs?page=1&size=20')
    let bestFresh = null
    let bestAny = null
    for (const candidate of (list ? list.records : [])) {
      const ix = await apiGet(`/history/runs/${candidate.id}/steps?page=1&size=500`)
      if (!ix || !ix.records.length) continue
      const grouped = groupMessages(ix.records)
      for (let m = 0; m < grouped.length; m += 1) {
        const steps = grouped[m].steps
        const hit = { candidate, grouped, mi: m, all: ix.records, steps: steps.length }
        if (!bestAny || steps.length > bestAny.steps) bestAny = hit
        if (steps.some(s => typeof s.durationMs === 'number')
          && (!bestFresh || steps.length > bestFresh.steps)) bestFresh = hit
        // 全是 V4 之前落的老数据的那条（票面第一条验收项要拿它判 `—`）
        if (steps.every(s => s.durationMs === null || s.durationMs === undefined)
          && (!oldPick || steps.length > oldPick.steps)) oldPick = hit
      }
    }
    const pick = bestFresh || bestAny
    if (!pick) {
      skip('整段（库里没有一条带消息步的运行）')
      return
    }
    run = pick.candidate
    messages = pick.grouped
    mi = pick.mi
    allRecords = pick.all
    measuredStep = messages[mi].steps.find(s => typeof s.durationMs === 'number') || null
  }

  const hasColumn = allRecords.length > 0
    && Object.prototype.hasOwnProperty.call(allRecords[0], 'durationMs')

  // ── 一、后端这一版带不带这一格（旧进程编不出来，那是真没过） ──
  check('索引接口带 durationMs 这一格（后端是 #134 这一版，不是旧进程）',
    hasColumn, JSON.stringify(allRecords[0] || {}))

  // ── 二、真库有没有落过耗时（有新链跑过就该有；老库照实 SKIP，但下面造的那两段照跑） ──
  if (measuredStep) {
    check('真库里有消息步落了动作耗时（链路在计时）',
      typeof measuredStep.durationMs === 'number' && measuredStep.durationMs >= 0,
      JSON.stringify({ id: measuredStep.id, durationMs: measuredStep.durationMs }))
  } else {
    skip('「真库里有落过耗时的步」那一项（这条库的步全是 V4 之前落的老数据）')
  }

  const order = messages[mi].steps
  const bizKey = order.map(s => (s.businessKey || '').trim()).find(k => k) || ''

  // 演示用的两格：**测过**的那一格与**没测过**的那一格。
  // 真库里没有就造（`durationOverride`）：造的是「后端给了值 / 后端给了空」这两种输入，
  // 判的是界面把它们画成什么——不是把界面的输出当期望值。
  const measured = measuredStep || order[0]
  const measuredIndex = order.indexOf(measured)
  const measuredMs = measuredStep ? measuredStep.durationMs : 210
  durationOverride = {}
  if (!measuredStep) {
    durationOverride[measured.id] = measuredMs
    skip(`「真库那一格的值」那一项（这条消息里没有测过的步，这一格按 ${measuredMs} ms 造）`)
  }
  let oldIndex = order.findIndex((s, i) => i !== measuredIndex
    && (s.durationMs === null || s.durationMs === undefined))
  if (oldIndex < 0 && order.length > 1) {
    oldIndex = order.findIndex((_, i) => i !== measuredIndex)
    durationOverride[order[oldIndex].id] = null
  }
  if (oldIndex < 0) skip('「老数据那一格写 `—`」那一项（这条消息只有一步，凑不出没测过的那一格）')

  if (!await openRunByBusinessKey(page, run.id, bizKey)) {
    skip(`整段（列表里翻不到运行 #${run.id}）`)
    return
  }
  if (mi > 0) {
    await page.locator('.hc-mchip').nth(mi).click()
    await page.waitForTimeout(300)
  }

  // ── 三、运行行那一格：**运行级**耗时（finishedAt - startedAt，跑着写「运行中」） ──
  {
    const rowText = (await page.locator('.hc-ritem').first().innerText()).replace(/\s+/g, ' ')
    // 那一格的值有三种形状：耗时（`N ms` / `N.N s`）、还没跑完的「运行中」、缺时刻的 `—`
    const shown = /耗时\s+(运行中|\d+(?:\.\d+)?\s*(?:ms|s)|—)/.exec(rowText)
    check('运行行上有「耗时」这一格', !!shown, rowText.slice(0, 200))
    if (run.finishedAt) {
      const expectedMs = Date.parse(run.finishedAt) - Date.parse(run.startedAt)
      check(`运行行那一格是运行耗时（脚本按 finishedAt - startedAt 自己算：${expectedMs} ms）`,
        !!shown && costMatches(shown[1], expectedMs), `${shown && shown[1]} vs ${expectedMs} ms`)
    } else {
      check('还没结束的运行写「运行中」（不是 0 ms）',
        !!shown && shown[1] === '运行中', shown && shown[1])
    }
  }

  // ── 四、步骤链：每一格都有耗时的字；测过的那一格非空、与接口那个数对得上；老数据那格是 `—` ──
  {
    const shown = (await costs(page).allInnerTexts()).map(t => t.trim())
    check('步骤链上每一格都带耗时的字（一格不缺）',
      shown.length === order.length, `链上 ${shown.length} 格 vs 消息步 ${order.length} 格`)
    check('入口那格（context[0] 任务输入）不铺耗时（它不是某一步）',
      await page.locator('.hc-cbtn-entry .hc-ccost').count() === 0)

    const measuredText = shown[measuredIndex]
    check('测过的那一格非空、且是耗时的形状（`N ms` / `N.N s`）',
      !!measuredText && measuredText !== '—' && parseCost(measuredText) !== null, String(measuredText))
    check(`链上那一格的数与接口给的 durationMs 对得上（接口 ${measuredMs} ms）`,
      costMatches(measuredText, measuredMs), `${measuredText} vs ${measuredMs} ms`)

    if (oldIndex >= 0) {
      check('没测过（老数据）的那一格写 `—`，不是 `0 ms`',
        shown[oldIndex] === '—', String(shown[oldIndex]))
    }
    await page.screenshot({ path: path.join(shots, 'history-134-chain.png') })

    // ── 五、单步卡：与链上那一格**一字不差**（票面「不出现两处不一致」） ──
    await clickNode(page, measuredIndex)
    const card = await cardCost(page)
    check('单步卡上有「耗时」这一格', card !== null, String(card))
    check('单步卡那一格与步骤链上同一格一字不差',
      card === measuredText, `卡上 ${JSON.stringify(card)} vs 链上 ${JSON.stringify(measuredText)}`)
    check('单步卡那一格也非空', !!card && card !== '—' && parseCost(card) !== null, String(card))

    if (oldIndex >= 0) {
      await clickNode(page, oldIndex)
      const oldCard = await cardCost(page)
      check('没测过的那一步，卡上也写 `—`（与链上同一格一致）',
        oldCard === '—' && shown[oldIndex] === '—',
        `卡上 ${JSON.stringify(oldCard)} / 链上 ${JSON.stringify(shown[oldIndex])}`)
      await page.screenshot({ path: path.join(shots, 'history-134-old.png') })
      await clickNode(page, measuredIndex)
    }
    await page.screenshot({ path: path.join(shots, 'history-134-card.png') })
  }

  // ── 六、失败步也记耗时（票面明说）：拿真库里的失败步判；老数据（V4 之前落的）没有这一格，
  //       那是数据不够，照实 SKIP——**失败那一步的耗时**由后端链路测试
  //       （`MessageHistoryExecutionTest.failedChainRecordsOneRunAndOneFailedStep`）在真链上钉着 ──
  {
    const failed = allRecords.filter(s => s.status === 'FAILED')
    if (!failed.length) {
      skip('「失败那一步也有耗时」那一项（这条运行里没有失败步）')
    } else if (failed.some(s => typeof s.durationMs === 'number')) {
      check('失败步也有耗时（失败往往正是要看耗时的那一步）',
        failed.every(s => typeof s.durationMs === 'number'),
        JSON.stringify(failed.map(s => ({ step: s.step, status: s.status, durationMs: s.durationMs }))))
    } else {
      skip('「失败那一步也有耗时」那一项（这条运行里的失败步是 V4 之前落的，没有这一格）')
    }
  }

  // ── 七、老数据（V4 之前落的步）显示 `—`，**不是** `0 ms`——票面第一条验收项，
  //       拿库里真有的老运行判（不是补丁造出来的那一种）。 ──
  {
    const currentAllOld = !allRecords.some(s => typeof s.durationMs === 'number')
    let target = currentAllOld ? null : oldPick
    if (!currentAllOld && (!target || target.candidate.id === run.id)) {
      target = null
      skip('「老数据那一格写 `—`」那一项（这条库里找不到另一条全是老数据的运行）')
    } else if (target) {
      const key = target.grouped[target.mi].steps.map(s => (s.businessKey || '').trim()).find(k => k) || ''
      if (!await openRunByBusinessKey(page, target.candidate.id, key)) {
        skip('「老数据那一格写 `—`」那一项（列表里翻不到那条老运行）')
        target = null
      } else if (target.mi > 0) {
        await page.locator('.hc-mchip').nth(target.mi).click()
        await page.waitForTimeout(300)
      }
    }

    if (currentAllOld || target) {
      const shown = (await costs(page).allInnerTexts()).map(t => t.trim())
      check('老数据（V4 之前落的步）链上每一格都写 `—`',
        shown.length > 0 && shown.every(t => t === '—'), shown.join(' | '))
      check('那一格不是 `0 ms`（「没测」与「测得就是 0 ms」分得开）',
        !shown.includes('0 ms'), shown.join(' | '))
      const oldCard = await cardCost(page)
      check('老数据的单步卡上也写 `—`', oldCard === '—', String(oldCard))
      await page.screenshot({ path: path.join(shots, 'history-134-old.png') })
    }
  }
}

;(async () => {
  try {
    await main()
  } catch (e) {
    failures.push(`脚本异常: ${e.message}`)
    console.log(`NOT - 脚本异常 :: ${e.stack}`)
  } finally {
    if (browser) await browser.close()
    console.log(`\n${failures.length ? `${failures.length} 项没过` : '全部通过'}`)
    if (failures.length) process.exitCode = 1
  }
})()
