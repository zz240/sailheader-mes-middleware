/*
 * #131 真机 UI 验收：消息历史页的**消息切换与步骤链**（右详情）。
 *
 * 票面（#128 子票 / ADR-0016 决策 4、5）：
 *   消息 chips（短 id + 业务键 + 「全通 / 失败 N 步」，数量为 1 时仍显示）、步骤链（从
 *   `context[0] 任务输入` 起每步一格、两个快照点、失败步可辨、选中态、可访问名称写全）、
 *   单步卡（状态 / 动作 / 尝试次数 / 时刻 / 错误块保留换行），以及把「展开一条运行只取
 *   前 100 个消息步」那个隐藏上限**显式化**。
 *
 * 对着这些断言：
 *   一、**运行 → 消息 → 步骤链 → 单步卡**走通（票面的真机验收项）：点一条运行，chips 上
 *      的消息数与接口按 runId 取到的消息（去重）一致；链上节点数 = 该消息的消息步数 + 入口
 *      那格；节点按步骤号升序；默认选中第 1 条消息的第 1 步；点任一节点，单步卡跟着换；
 *   二、chips 上的「失败 N 步」与实际一致（逐条消息与接口里的 FAILED 数对）；
 *   三、点一颗 chip 就切到那条消息（链与卡都换过去）；
 *   四、一条消息步都没有的运行：右栏说清「还没有消息步」，不铺半条链；
 *   五、单步卡的内容与**消息步列表里那一行**一致（切换模式，点一行，逐格比）；
 *   六、失败步的错误块在、**换行与原文一模一样**（`white-space: pre-wrap`），
 *      成功步**没有**空错误块；
 *   七、超过 100 步的运行：界面**不再只取前 100 步**（真的追了 page=2…），消息数与链上
 *      步数仍与接口对得上；只有真撞上取步上限时才出现那句提示（正常库上不出现）；
 *   八、真撞上取步上限（把接口的 `total` 拦下来报 5000、每页都回第 1 页那份）：追满 10 页
 *      就停，并把「共多少 / 带了前多少 / 上限」写在右栏——票面「要么给出提示」的那一半。
 *
 * 跑法（playwright 是本目录自带的，先 `npm i` 一次就行）：
 *   cd tools/ui && node message-history-131.cjs
 * 跑之前前端 dev server（5173）与后端（8080）都要在。
 *
 * 库里数据不够那几段（一条运行都没有 / 没有失败步 / 没有超过 100 步的运行 / 没有多行错误）
 * 时打印 SKIP 并跳过，其余照跑（与 #111/#112/#129/#130/#136 同一套口径）。
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
const seq = () => requests.length
const stepUrlsSince = since => requests.slice(since).map(r => r.url)
const runStepUrlsFor = runId => requests
  .map(r => r.url).filter(url => url.includes(`runId=${runId}`))

async function settle(page, ms = 4000) {
  const start = requests.length
  const deadline = Date.now() + ms
  while (Date.now() < deadline) {
    await page.waitForTimeout(120)
    if (requests.length > start) { await page.waitForTimeout(350); return }
  }
}

/**
 * 等右栏把这条运行的消息步**追完**：不是「第一条请求回来之后 350ms」，而是
 * 「这条 runId 的请求数不再涨、右栏也不再是占位」——`settle()` 那套在 720 步（8 页）这种
 * 运行上不够用，会在第 3 页还在路上时就去数 chips（脚本最重要的一段就成了看运气）。
 */
async function waitForRunSteps(page, runId, ms = 20000) {
  const deadline = Date.now() + ms
  let last = -1
  while (Date.now() < deadline) {
    const sent = runStepUrlsFor(runId).length
    const settled = sent === last && sent > 0
      && await page.locator('.hc-dbody .hc-skel').count() === 0
    if (settled) return true
    last = sent
    await page.waitForTimeout(250)
  }
  return false
}

async function waitForList(page) {
  await page.waitForSelector('.hc-ritem, .hc-list-empty, .hc-errbox', { timeout: 20000 })
}

/** 右栏的消息步取完（或落到空态 / 出错）——链与卡都在那之后才谈得上。 */
async function waitForDetail(page) {
  await page.waitForSelector('.hc-mchip, .hc-chain-note, .hc-dbody .hc-list-empty, .hc-dbody .hc-errbox',
    { timeout: 30000 })
}

async function apiGet(pathAndQuery) {
  const res = await fetch(`${API}${pathAndQuery}`)
  const body = await res.json().catch(() => null)
  return res.ok && body && body.code === 'OK' ? body.data : null
}

/** 一条运行的全部消息步（与界面同一套追页口径：一页 100，最多 10 页）。 */
async function allRunSteps(runId) {
  const first = await apiGet(`/history/steps?runId=${runId}&page=1&size=100`)
  if (!first) return null
  const all = [...first.records]
  const pages = Math.min(Math.ceil(first.total / 100), 10)
  for (let p = 2; p <= pages && all.length < first.total; p += 1) {
    const next = await apiGet(`/history/steps?runId=${runId}&page=${p}&size=100`)
    if (!next || !next.records.length) break
    all.push(...next.records)
  }
  return all
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
    failed: list.filter(s => s.status === 'FAILED').length,
  }))
}

/** 在运行摘要里找一条符合条件的运行（按 id 倒序逐页扫，找最近的）。 */
async function findRun(pred, maxPages = 30) {
  for (let p = 1; p <= maxPages; p += 1) {
    const page = await apiGet(`/history/runs/summary?page=${p}&size=100`)
    if (!page || !page.summaries.length) return null
    const hit = page.summaries.find(pred)
    if (hit) return hit
  }
  return null
}

const runIdOnScreen = async (page) => {
  const text = await page.locator('.hc-dhead h2').innerText()
  return Number(text.replace(/[^0-9]/g, ''))
}

const chipsOnScreen = page => page.locator('.hc-mchip')
const chainNodesOnScreen = page => page.locator('.hc-chain .hc-cbtn:not(.hc-cbtn-entry)')
/** 链上选中的那一格（入口那格是 div，不会进来）。 */
const selectedNodeOnScreen = page => page.locator('.hc-chain button.hc-cbtn[aria-pressed="true"]')

/** 回到「按运行展开」那一侧：`.hc-r-id` 在两个模式里长得不一样（消息步行是 `#12 · 步骤 3`），
 *  下面的 helper 按运行 id 找行，得先确定自己在运行那一侧。 */
async function ensureRunsMode(page) {
  const runs = page.locator('.hc-seg .el-radio-button').filter({ hasText: '按运行展开' }).first()
  const active = await runs.evaluate(el => el.classList.contains('is-active')).catch(() => false)
  if (!active) {
    await runs.click()
    await settle(page)
    await waitForList(page)
  }
}

/** 用业务键把列表缩到目标运行那一带（命中即保留整条运行），再按 id 找到那一行点进去。 */
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

async function main() {
  browser = await chromium.launch()
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  page.on('request', req => {
    const url = req.url()
    if (url.includes('/api/history/steps') && url.includes('runId=')) requests.push({ url })
  })

  await page.goto(`${APP}/history`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.hc-page', { timeout: 20000 })
  await waitForList(page)
  fs.mkdirSync(shots, { recursive: true })

  // ── 一、运行 → 消息 → 步骤链 → 单步卡（票面的真机验收项） ──
  let firstRunId = null
  let firstSteps = null
  {
    const rowCount = await page.locator('.hc-ritem').count()
    if (rowCount === 0) {
      skip('整段（库里一条运行都没有）')
      return
    }
    await page.locator('.hc-ritem').first().click()
    await waitForDetail(page)
    firstRunId = await runIdOnScreen(page)
    firstSteps = await allRunSteps(firstRunId)
    const messages = firstSteps ? groupMessages(firstSteps) : []
    if (!messages.length) {
      skip('消息 chips / 步骤链 / 单步卡那几段（这条运行没有消息步）')
    } else {
      const chips = await chipsOnScreen(page).count()
      check('消息 chips 的条数与接口按 runId 取到的消息（去重）一致', chips === messages.length,
        `界面 ${chips} vs 接口 ${messages.length}`)
      check('哪怕只有一条消息，chip 也照旧显示（票面明说）', chips >= 1, String(chips))

      const chipTexts = (await chipsOnScreen(page).allInnerTexts()).map(t => t.replace(/\s+/g, ' ').trim())
      // 逐颗照接口对：短 id、业务键（没有就是「无业务键」）、「全通 / 失败 N 步」三样都得在
      const chipMismatch = []
      for (let i = 0; i < messages.length; i += 1) {
        const msg = messages[i]
        const short = msg.messageId.length > 10 ? `${msg.messageId.slice(0, 8)}…` : msg.messageId
        const key = msg.steps.map(s => (s.businessKey || '').trim()).find(k => k) || '无业务键'
        const state = msg.failed > 0 ? `失败 ${msg.failed} 步` : '全通'
        if (!chipTexts[i].includes(short) || !chipTexts[i].includes(key) || !chipTexts[i].includes(state)) {
          chipMismatch.push(`第 ${i + 1} 颗 ${JSON.stringify(chipTexts[i])} vs 接口的 ${short} / ${key} / ${state}`)
        }
      }
      check('每颗 chip 上的短 id、业务键与「全通 / 失败 N 步」都照接口写得对',
        chipMismatch.length === 0, chipMismatch.join(' | '))
      check('链头右端那句写着这条消息几步、入口是哪个动作',
        /[0-9]+ 步 · 入口 /.test(await page.locator('.hc-chain-right').innerText()),
        await page.locator('.hc-chain-right').innerText())

      const entry = await page.locator('.hc-chain .hc-cbtn-entry').innerText()
      check('步骤链第一格是 `context[0] 任务输入`（原型 node0）',
        entry.includes('0 · 任务输入') && entry.includes('context[0]'), entry.replace(/\n/g, ' | '))

      const nodes = await chainNodesOnScreen(page).count()
      check('链上节点数 = 第 1 条消息的消息步数 + 入口那格',
        nodes === messages[0].steps.length, `界面 ${nodes} vs 接口 ${messages[0].steps.length}`)

      const nodeHeads = await chainNodesOnScreen(page).evaluateAll(
        els => els.map(el => Number((el.querySelector('.hc-cn') || {}).innerText.replace(/[^0-9]/g, ''))))
      check('节点按步骤号升序（票面）',
        JSON.stringify(nodeHeads) === JSON.stringify(messages[0].steps.map(s => s.step)),
        `${nodeHeads.join(',')} vs ${messages[0].steps.map(s => s.step).join(',')}`)

      const selected = selectedNodeOnScreen(page)
      check('默认选中第 1 条消息的第 1 步（原型 open-run 的归一）',
        await selected.count() === 1
          && (await selected.first().getAttribute('aria-label')).includes(`步骤 ${messages[0].steps[0].step} `),
        `${await selected.count()} 个选中格`)

      // 点最后一个节点：选中态跟着走、单步卡换成那一步
      const last = messages[0].steps[messages[0].steps.length - 1]
      await chainNodesOnScreen(page).last().click()
      await page.waitForTimeout(200)
      const afterLabel = await selectedNodeOnScreen(page).first().getAttribute('aria-label')
      check('点任一步都能选中它（选中态跟着走）', afterLabel.includes(`步骤 ${last.step} `), afterLabel)
      check('可访问名称写清步骤号 / 动作 / 状态',
        /步骤 [0-9]+ · 动作 .+ · 状态 (SUCCESS|FAILED|REDELIVERED|RUNNING)/.test(afterLabel), afterLabel)

      const cardHead = (await page.locator('.hc-card .hc-sc-head').innerText()).replace(/\s+/g, ' ')
      check('单步卡跟着换到那一步（步骤号 + 动作 + 状态）',
        cardHead.includes(`步骤 ${last.step}`) && cardHead.includes(last.actionName)
          && cardHead.includes(last.status), cardHead)
      check('单步卡写着尝试次数与落库时刻（票面那几格）',
        cardHead.includes(`尝试 ${last.attempt || 1}`)
          && cardHead.includes(String(last.createdAt).slice(0, 19).replace('T', ' ')), cardHead)
      // 步骤链是横向一条长东西：它自己在栏内横滚，不许把右栏（乃至整页）撑宽（ADR-0016 决策 3）
      const box = await page.evaluate(() => {
        const wrap = document.querySelector('.hc-chain-wrap').getBoundingClientRect()
        const panel = document.querySelector('.hc-detail-panel').getBoundingClientRect()
        const scroll = document.querySelector('.hc-scroll')
        return { wrapRight: wrap.right, panelRight: panel.right, panelScrollX: scroll.scrollWidth, panelClientX: scroll.clientWidth }
      })
      check('步骤链自己横滚，没把右栏撑出横向滚动',
        box.wrapRight <= box.panelRight + 1 && box.panelScrollX <= box.panelClientX + 1, JSON.stringify(box))
      await page.screenshot({ path: path.join(shots, 'history-131-chain.png') })

      // ── 二、chips 上的「失败 N 步」与实际一致 ──
      const mismatched = []
      for (let i = 0; i < messages.length; i += 1) {
        const text = await chipsOnScreen(page).nth(i).innerText()
        const shown = text.match(/失败 ([0-9]+) 步/)
        const expect = messages[i].failed
        if (shown ? Number(shown[1]) !== expect : expect !== 0) {
          mismatched.push(`${messages[i].messageId.slice(0, 8)} 界面 ${shown ? shown[1] : '全通'} vs 实际 ${expect}`)
        }
      }
      check('每颗 chip 的「失败 N 步」与接口里那条消息的 FAILED 数一致（票面验收项）',
        mismatched.length === 0, mismatched.join(' | '))

      // ── 三、点一颗 chip 就切到那条消息 ──
      if (messages.length < 2) {
        skip('切换消息那一段（这条运行只有一条消息）')
      } else {
        await chipsOnScreen(page).nth(1).click()
        await page.waitForTimeout(200)
        const pressed = await chipsOnScreen(page).evaluateAll(
          els => els.map(el => el.getAttribute('aria-pressed')))
        check('点第 2 颗 chip 之后只有它按着（消息切换）',
          pressed[1] === 'true' && pressed.filter(p => p === 'true').length === 1, pressed.join(','))
        const second = messages[1]
        const head = (await page.locator('.hc-card .hc-sc-head').innerText()).replace(/\s+/g, ' ')
        check('卡片换成了第 2 条消息的第 1 步',
          head.includes(`步骤 ${second.steps[0].step}`) && head.includes(second.steps[0].actionName), head)
        await page.screenshot({ path: path.join(shots, 'history-131-messages.png') })
        await chipsOnScreen(page).nth(0).click()
        await page.waitForTimeout(200)
      }
    }
  }

  // ── 四、一条消息步都没有的运行：右栏说清「还没有」，不是半条链 ──
  {
    const emptyRun = await findRun(s => s.stepCount === 0)
    if (!emptyRun) {
      skip('空运行那一段（库里没有零消息步的运行）')
    } else if (!await openRunByBusinessKey(page, emptyRun.runId, '')) {
      skip(`空运行那一段（列表里翻不到运行 #${emptyRun.runId}）`)
    } else {
      const empty = page.locator('.hc-dbody .hc-list-empty')
      check('没有消息步的运行：右栏写「还没有消息步」而不是铺半条链',
        await empty.count() === 1 && (await empty.innerText()).includes('还没有消息步'),
        (await empty.innerText().catch(() => '')).replace(/\n/g, ' | '))
      check('没有消息步时链与单步卡都不铺',
        await page.locator('.hc-chain-wrap').count() === 0
          && await page.locator('.hc-card').count() === 0)
    }
  }

  // ── 五、单步卡的内容与消息步列表里那一行一致（切到检索模式点一行） ──
  {
    await page.locator('.hc-seg .el-radio-button').filter({ hasText: '消息步检索' }).first().click()
    await settle(page)
    await waitForList(page)
    if (await page.locator('.hc-ritem').count() === 0) {
      skip('单步卡 vs 列表行那一段（消息步检索这一页没有行）')
    } else {
      await page.locator('.hc-ritem').first().click()
      await waitForDetail(page)
      const rowText = (await page.locator('.hc-ritem').first().innerText()).replace(/\s+/g, ' ')
      const head = (await page.locator('.hc-card .hc-sc-head').innerText()).replace(/\s+/g, ' ')
      const stepNo = (rowText.match(/步骤 ([0-9]+)/) || [])[1]
      const attempt = (rowText.match(/尝试 ([0-9]+)/) || [])[1]
      const at = (rowText.match(/([0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2})/) || [])[1]
      check('单步卡的步骤号与列表那一行一致', !!stepNo && head.includes(`步骤 ${stepNo}`), `${stepNo} :: ${head}`)
      check('单步卡的尝试次数与列表那一行一致', !!attempt && head.includes(`尝试 ${attempt}`), `${attempt} :: ${head}`)
      check('单步卡的落库时刻与列表那一行一致', !!at && head.includes(at), `${at} :: ${head}`)
      check('列表行上的状态与卡片上的芯片同一个',
        !!rowText.match(/(SUCCESS|FAILED|REDELIVERED|RUNNING)/)
          && head.includes(rowText.match(/(SUCCESS|FAILED|REDELIVERED|RUNNING)/)[1]), head)
      await page.screenshot({ path: path.join(shots, 'history-131-from-list.png') })
    }
  }

  // ── 六、失败步的错误块：在、换行与原文一模一样；成功步没有空错误块 ──
  {
    const failedRun = await findRun(s => s.failedSteps > 0)
    if (!failedRun) {
      skip('错误块那一段（库里没有失败步）')
    } else {
      const steps = await allRunSteps(failedRun.runId)
      const failedStep = steps && steps.find(s => s.status === 'FAILED' && s.error)
      const first = await apiGet(`/history/steps?runId=${failedRun.runId}&page=1&size=1`)
      const key = first && first.records.length ? first.records[0].businessKey : ''
      if (!failedStep) {
        skip('错误块那一段（这条运行的失败步没有错误文本）')
      } else if (!await openRunByBusinessKey(page, failedRun.runId, key)) {
        skip(`错误块那一段（列表里翻不到运行 #${failedRun.runId}）`)
      } else {
        const nodes = chainNodesOnScreen(page)
        // 先切到那条消息，再点它那一步
        const grouped = groupMessages(steps)
        const mi = grouped.findIndex(m => m.messageId === failedStep.messageId)
        if (mi > 0) {
          await chipsOnScreen(page).nth(mi).click()
          await page.waitForTimeout(200)
        }
        const idxInChain = grouped[mi].steps.findIndex(s => s.step === failedStep.step)
        await nodes.nth(idxInChain).click()
        await page.waitForTimeout(200)

        const err = page.locator('.hc-card .hc-step-err')
        check('失败步有错误块（票面：失败步的错误块）', await err.count() === 1, String(await err.count()))
        if (await err.count() === 1) {
          const shown = await err.locator('pre').innerText()
          check('错误块里的文本与接口里的**一模一样**（没截断、没吃掉换行）',
            shown.replace(/\r\n/g, '\n') === failedStep.error.replace(/\r\n/g, '\n'),
            `界面 ${JSON.stringify(shown)} vs 接口 ${JSON.stringify(failedStep.error)}`)
          const ws = await err.locator('pre').evaluate(el => getComputedStyle(el).whiteSpace)
          check('错误块是 `pre-wrap`（换行与堆栈因此原样留着，票面：便于直接粘贴）',
            ws === 'pre-wrap', ws)
          const lines = failedStep.error.split('\n').length
          if (lines < 2) {
            skip('「多行错误保留换行」那一条（库里没有一个多行错误；纯模块那一侧锁住了：'
              + 'checks.ts「错误块整份保留换行」）')
          } else {
            const shownLines = (await err.locator('pre').innerText()).split('\n').length
            check('多行错误在界面上仍是那么多行（换行没被折成一行）', shownLines === lines,
              `界面 ${shownLines} 行 vs 接口 ${lines} 行`)
          }
        }
        await page.screenshot({ path: path.join(shots, 'history-131-error.png') })

        const okStep = grouped[mi].steps.find(s => s.status === 'SUCCESS')
        if (!okStep) {
          skip('「成功步不显示空错误块」那一段（这条消息没有成功步）')
        } else {
          await nodes.nth(grouped[mi].steps.indexOf(okStep)).click()
          await page.waitForTimeout(200)
          check('成功步不显示空错误块（票面验收项）',
            await page.locator('.hc-card .hc-step-err').count() === 0,
            String(await page.locator('.hc-card .hc-step-err').count()))
        }
      }
    }
    // 收起业务键筛选，回到全量（后面的段要能自己翻页）
    const biz = page.getByPlaceholder('SO-2026-0917-0042')
    await biz.fill('')
    await biz.press('Enter')
    await settle(page)
    await waitForList(page)
  }

  // ── 七、超过 100 步的运行：不再「只取前 100 步」 ──
  {
    const bigRun = await findRun(s => s.stepCount > 100)
    if (!bigRun) {
      skip('「超过 100 步不静默截断」那一段（库里没有超过 100 步的运行）')
    } else {
      const probe = await apiGet(`/history/steps?runId=${bigRun.runId}&page=1&size=1`)
      const key = probe && probe.records.length ? probe.records[0].businessKey : ''
      const before = seq()
      if (!await openRunByBusinessKey(page, bigRun.runId, key)) {
        skip(`「超过 100 步」那一段（列表里翻不到运行 #${bigRun.runId}）`)
      } else {
        const walked = await waitForRunSteps(page, bigRun.runId)
        check('右栏把这条运行的消息步取完了（不是还在追页的时候就数数）', walked)
        const urls = stepUrlsSince(before)
        check(`这条运行有 ${bigRun.stepCount} 步，界面**追了第二页**（100 不再是天花板）`,
          urls.some(u => /[?&]page=2/.test(u)), urls.join(' '))

        const steps = await allRunSteps(bigRun.runId)
        const messages = groupMessages(steps)
        const chips = await chipsOnScreen(page).count()
        check('超过 100 步时消息 chips 仍与接口的消息数一致（不是只有前 100 步里的那几条）',
          chips === messages.length, `界面 ${chips} vs 接口 ${messages.length}`)
        const nodes = await chainNodesOnScreen(page).count()
        check('步骤链的节点数仍等于该消息的消息步数 + 入口那格',
          nodes === messages[0].steps.length, `界面 ${nodes} vs 接口 ${messages[0].steps.length}`)
        check('没撞上取步上限时，界面上一个字都不提截断（那句话只在真截断时出现）',
          await page.locator('.hc-chain-note').count() === 0,
          String(await page.locator('.hc-chain-note').count()))
        await page.screenshot({ path: path.join(shots, 'history-131-big-run.png') })
      }
    }
  }

  // ── 八、真撞上取步上限：界面上把那句话说出来（票面「要么给出提示」的那一半） ──
  // 库里没有超过 1000 步的运行（最大的一条 720），所以把接口拦下来改 `total`：报 5000、
  // 且每页都回第 1 页那份记录——页面于是会一路追到上限，正好把「追到上限就停」与
  // 「把那句话写在脸上」两件事一次测掉（paste-sample-115 也是这么拦接口的）。
  {
    await ensureRunsMode(page)
    // 先整页重开：上一段按业务键筛过，重开才是「无筛选的第 1 页」——要拦的那条运行得从
    // 重开之后的列表里读（点同一行不再重复取是这一票的有意行为，`stepsRunId` 那道门）
    await page.reload({ waitUntil: 'networkidle' })
    await waitForList(page)
    const rowCount = await page.locator('.hc-ritem').count()
    if (rowCount === 0) {
      skip('截断提示那一段（列表里没有运行）')
    } else {
      const target = Number((await page.locator('.hc-ritem .hc-r-id').first().innerText()).replace(/[^0-9]/g, ''))
      let firstPage = null
      await page.route(/\/api\/history\/steps\?/, async route => {
        const url = new URL(route.request().url())
        if (url.searchParams.get('runId') !== String(target)) return route.continue()
        const res = await route.fetch()
        const body = await res.json()
        if (url.searchParams.get('page') === '1') {
          firstPage = body.data.records || []
          // 这条运行真的一条消息步都没有：别把它编出来（那一段有自己的断言）
          if (!firstPage.length) return route.fulfill({ response: res, json: body })
        }
        body.data.total = 5000
        if (firstPage) body.data.records = firstPage
        return route.fulfill({ response: res, json: body })
      })
      const before = seq()
      await page.locator('.hc-ritem').first().click()
      await waitForDetail(page)

      const note = page.locator('.hc-chain-note')
      check('接口说这条运行有 5000 步时，右栏把「共多少 / 带了前多少 / 上限」写在脸上（不再静默截断）',
        await note.count() === 1 && (await note.innerText()).includes('5000')
          && (await note.innerText()).includes('前 1000 个')
          && (await note.innerText()).includes('上限 1000'),
        (await note.innerText().catch(() => '')).replace(/\n/g, ' '))
      const walked = stepUrlsSince(before).filter(u => u.includes(`runId=${target}`))
      check('追到上限（10 页）就停，不是一直问下去', walked.length === 10, String(walked.length))
      await page.screenshot({ path: path.join(shots, 'history-131-truncated.png') })
      await page.unroute(/\/api\/history\/steps\?/)
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
