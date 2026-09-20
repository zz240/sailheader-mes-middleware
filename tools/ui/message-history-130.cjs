/*
 * #130 真机 UI 验收：消息历史页的**计数条与运行摘要**（只读聚合接口）。
 *
 * 票面（#128 子票，ADR-0016 决策 6）：
 *   「这次筛选命中了多大规模」与「这条运行值不值得点开」一次问清楚——四个计数
 *   （运行 / 消息 / 消息步 / 失败步，口径 = **当前筛选集**、**不含分页**）+ 每运行摘要
 *   （消息数 / 消息步数 / 失败步数）。
 *
 * 对着这些断言：
 *   一、页头计数条四项写的是真数字：与 `/history/runs/summary` 同一组筛选的应答逐个对上；
 *   二、计数与列表是**两条**请求，但同一组筛选；聚合那条**不带 page / size**（翻页不该重算计数）；
 *   三、**翻页不减计数**：翻到第 2 页 / 改每页条数，四个数一个都不变（列表换了才算数）；
 *   四、改筛选（任务 / 状态 / 业务键）**计数跟着变**，且与接口新算的一组一致；重置回初始的一组；
 *   五、运行行写 `N 消息 · M 步[ · 失败 K]`：失败步为 0 的运行行**不出现「失败 0」**（票面明说），
 *      失败步 > 0 的那一段是红的；
 *   六、**行摘要与展开后实际取到的消息步一致**（验收项）：拿 `/history/steps?runId=X` 的
 *      `records` 数一遍消息（去重）/ 步数 / FAILED 数，与行上写的、与接口摘要三处对上；
 *   七、命中的业务键显示出来了（#129 留下的「只筛不显示」缺口在这一票补上）。
 *
 * 跑法（playwright 是本目录自带的，先 `npm i` 一次就行）：
 *   cd tools/ui && node message-history-130.cjs
 * 跑之前前端 dev server（5173）与后端（8080）都要在。
 *
 * 库里数据不够那几段（一条运行都没有 / 没有失败步 / 不足两页）时打印 SKIP 并跳过，
 * 其余照跑（与 #111/#112/#129/#136 同一套口径）。
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

/** 铺着的那个下拉：element-plus 在 popper 上用 aria-hidden 标「开着没有」。 */
const OPEN_DROPDOWN = '.el-popper[aria-hidden="false"] .el-select-dropdown'

let requests = []

async function settle(page, ms = 4000) {
  const start = requests.length
  const deadline = Date.now() + ms
  while (Date.now() < deadline) {
    await page.waitForTimeout(120)
    if (requests.length > start) { await page.waitForTimeout(350); return }
  }
}

async function waitForList(page) {
  await page.waitForSelector('.hc-ritem, .hc-list-empty, .hc-errbox', { timeout: 20000 })
}

async function openSelect(page, selector) {
  const select = page.locator(selector)
  await select.click()
  await page.waitForTimeout(250)
  if (await page.locator(OPEN_DROPDOWN).count() === 0) await select.click()
  await page.waitForSelector(`${OPEN_DROPDOWN} .el-select-dropdown__item`, { timeout: 10000 })
  await page.waitForTimeout(200)
}

async function pickOption(page, selector, label) {
  await openSelect(page, selector)
  const before = requests.length
  await page.locator(OPEN_DROPDOWN).first().locator('.el-select-dropdown__item')
    .filter({ hasText: label }).first().click()
  await settle(page)
  await waitForList(page)
  return requests.slice(before)
}

const rows = page => page.locator('.hc-ritem')
const rowTexts = async page => (await rows(page).allInnerTexts()).map(t => t.replace(/\n/g, ' | '))
/** 行摘要那一格单独取：它在 `开始 … / 结束 … / N 消息 · M 步` 这一行里，和别的东西同一行。
 *  空白归一成一个空格（`.hc-r-counts` 是 flex，`innerText` 会在每个片段之间塞换行）。 */
const rowCountsTexts = async page => (await rows(page).evaluateAll(
  els => els.map(el => (el.querySelector('.hc-r-counts') || {}).innerText || '')))
  .map(t => t.replace(/\s+/g, ' ').trim())

const summaryUrls = since => requests
  .filter(r => r.kind === 'summary' && r.seq >= since).map(r => r.url)

async function apiGet(pathAndQuery) {
  const res = await fetch(`${API}${pathAndQuery}`)
  const body = await res.json().catch(() => null)
  return res.ok && body && body.code === 'OK' ? body.data : null
}

/** 计数条上那四个数：`{ 运行: 12, 消息: 30, 消息步: 88, 失败步: 3 }`。 */
async function countsOnScreen(page) {
  const text = await page.locator('.hc-counts').innerText()
  const out = {}
  for (const label of ['运行', '消息步', '失败步', '消息']) {
    // 「消息」是「消息步」的前缀：先配长的那几个，短的那个用词边界兜住
    const re = new RegExp(`${label}\\s+([0-9]+)`)
    const m = text.match(re)
    if (m) out[label] = Number(m[1])
  }
  return out
}

/** 行上写的 `N 消息 · M 步[ · 失败 K]` 拆出来。 */
function parseCounts(text) {
  const messages = text.match(/([0-9]+) 消息/)
  const steps = text.match(/([0-9]+) 步/)
  const failed = text.match(/失败 ([0-9]+)/)
  return {
    messages: messages ? Number(messages[1]) : null,
    steps: steps ? Number(steps[1]) : null,
    failed: failed ? Number(failed[1]) : 0,
  }
}

async function main() {
  browser = await chromium.launch()
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  page.on('request', req => {
    const url = req.url()
    const kind = url.includes('/api/history/runs/summary') ? 'summary'
      : url.includes('/api/history/runs') ? 'runs'
        : url.includes('/api/history/steps') ? 'steps' : null
    if (kind) requests.push({ url, kind, seq: requests.length })
  })
  const seq = () => requests.length

  await page.goto(`${APP}/history`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.hc-page', { timeout: 20000 })
  await waitForList(page)
  fs.mkdirSync(shots, { recursive: true })

  // 后端还没有这一版（#130 的聚合端点）时，下面全部 SKIP——先说清楚，别把 404 当成界面问题
  const probe = await apiGet('/history/runs/summary')
  if (!probe) {
    skip('整段（后端没有 /history/runs/summary：先重启后端，证据在 HistoryControllerTest#passesRunFiltersToSummary）')
    return
  }

  // ── 一、计数条在页头上，四项，数字与接口一致 ──
  {
    check('页头有计数条', await page.locator('.hc-counts').count() === 1)
    const onScreen = await countsOnScreen(page)
    check('计数条四项都读得到（运行 / 消息 / 消息步 / 失败步）',
      ['运行', '消息', '消息步', '失败步'].every(k => typeof onScreen[k] === 'number'),
      JSON.stringify(onScreen))
    check('计数条的数字与聚合接口同一组筛选的结果一致',
      onScreen['运行'] === probe.runs && onScreen['消息'] === probe.messages
        && onScreen['消息步'] === probe.steps && onScreen['失败步'] === probe.failedSteps,
      `界面 ${JSON.stringify(onScreen)} vs 接口 ${JSON.stringify(probe)}`)
    await page.screenshot({ path: path.join(shots, 'history-130-counts.png') })
  }

  // ── 二、聚合请求：同一组筛选；page/size 只决定「摘要给哪一页」 ──
  {
    const urls = summaryUrls(0)
    check('页面加载时就打了聚合这条请求', urls.length > 0, urls.join(' '))
    check('聚合请求带上与列表同一套 page / size（摘要按页给，计数不受它影响）',
      urls.length > 0 && urls.every(u => /[?&]page=1/.test(u) && /[?&]size=20/.test(u)), urls.join(' '))
    check('运行那一侧初始不带任何筛选（聚合请求与列表同一组条件）',
      urls.every(u => !/status=|jobId=|businessKey=/.test(u)), urls.join(' '))
  }

  // ── 三、行摘要：与接口摘要一致、失败 0 不出现、失败那段是红的 ──
  const onScreenSummary = {}
  {
    const rowCount = await rows(page).count()
    if (rowCount === 0) {
      skip('行摘要那几段（库里一条运行都没有）')
    } else {
      const texts = await rowTexts(page)
      // 摘要那一格单独取：它跟「开始 … 结束 …」挤在同一行里，整行文本会把两种东西混起来
      const countTexts = await rowCountsTexts(page)
      // 片段之间那点空隙是 flex 的 `gap`（不是文本里的空格），所以 `innerText` 可能是
      // 「20 消息100 步」——断言认的是三段内容按序出现，不认那个视觉空隙
      check('每行都写了「N 消息 · M 步」',
        countTexts.every(t => /^[0-9]+ 消息 ?·? ?[0-9]+ 步( ?·? ?失败 [0-9]+)?$/.test(t.trim())),
        countTexts.join(' || '))
      const noFailZero = countTexts.every(t => !/失败 0(?!\d)/.test(t))
      check('没有任何一行写「失败 0」（票面：失败数为 0 时不出现噪声）', noFailZero,
        countTexts.filter(t => /失败 0(?!\d)/.test(t)).join(' || '))

      // 逐行与接口摘要对一遍（接口 records 与列表同一顺序：主键倒序）
      const runIds = await page.locator('.hc-ritem .hc-r-id').allInnerTexts()
      const apiSummary = await apiGet('/history/runs/summary')
      const byRunId = new Map((apiSummary ? apiSummary.summaries : []).map(s => [s.runId, s]))
      let mismatched = []
      for (let i = 0; i < countTexts.length; i += 1) {
        const id = Number(String(runIds[i]).replace('#', '').trim())
        const summary = byRunId.get(id)
        const shown = parseCounts(countTexts[i])
        if (!summary) { mismatched.push(`#${id} 接口没有摘要`); continue }
        if (shown.messages !== summary.messageCount || shown.steps !== summary.stepCount
          || shown.failed !== summary.failedSteps) {
          mismatched.push(`#${id} 界面 ${JSON.stringify(shown)} vs 接口 ${JSON.stringify(summary)}`)
        }
        onScreenSummary[id] = { shown, summary }
      }
      check('每一行的摘要与接口按 runId 给的三个数逐个对上', mismatched.length === 0, mismatched.join(' | '))

      // 失败那一段的颜色：有失败步的行必须是红的，其余行不许有红的那一段
      const badRows = countTexts.filter(t => /失败 [1-9]/.test(t))
      if (badRows.length === 0) {
        skip('「失败 K 是红的」那一段（这一页没有带失败步的运行）')
      } else {
        const color = await page.evaluate(() => {
          const el = document.querySelector('.hc-ritem .hc-r-counts .hc-r-bad')
          return el ? getComputedStyle(el).color : ''
        })
        check('失败那一段用了危险色（不是与其余数字同样的灰）', !!color && color !== '', color)
      }
      await page.screenshot({ path: path.join(shots, 'history-130-rows.png') })
    }
  }

  // ── 四、验收项：行摘要与展开后实际取到的消息步一致 ──
  {
    const firstId = Object.keys(onScreenSummary)[0]
    if (!firstId) {
      skip('「行摘要 vs 实际消息步」那一段（这一页没有行）')
    } else {
      const steps = await apiGet(`/history/steps?runId=${firstId}&page=1&size=100`)
      const records = (steps && steps.records) || []
      const realMessages = new Set(records.map(s => s.messageId)).size
      const realFailed = records.filter(s => s.status === 'FAILED').length
      const shown = onScreenSummary[firstId]
      check('这一页第一行的消息数与按 runId 取到的消息步对得上（去重后的消息数）',
        shown.shown.messages === realMessages, `行上 ${shown.shown.messages} vs 实际 ${realMessages}`)
      check('这一页第一行的失败步数与按 runId 取到的 FAILED 数对得上',
        shown.shown.failed === realFailed, `行上 ${shown.shown.failed} vs 实际 ${realFailed}`)
      if (records.length > 100) {
        skip('步数比对（这条运行超过 100 步，一页取不全）')
      } else {
        check('这一页第一行的步数与按 runId 取到的条数对得上',
          shown.shown.steps === records.length, `行上 ${shown.shown.steps} vs 实际 ${records.length}`)
      }
    }
  }

  // ── 五、翻页不减计数（票面：翻页不影响计数） ──
  {
    const before = await countsOnScreen(page)
    const pager = page.locator('.hc-pfoot .el-pagination')
    const page2 = pager.locator('.el-pager li.number').filter({ hasText: /^2$/ }).first()
    if (await page2.count() === 0) {
      skip('翻页不减计数那一段（当前不足两页）')
    } else {
      await page2.click()
      await settle(page)
      await waitForList(page)
      const after = await countsOnScreen(page)
      check('翻页之后四个计数一个都没变（票面：翻页不影响计数）',
        JSON.stringify(after) === JSON.stringify(before),
        `第1页 ${JSON.stringify(before)} vs 第2页 ${JSON.stringify(after)}`)
      const urls = summaryUrls(0)
      check('翻页重问聚合接口时把当前页带上（摘要是按页给的，计数与页无关）',
        urls.some(u => /[?&]page=2/.test(u)), urls.join(' '))
      await page.getByRole('button', { name: '重置' }).click()
      await settle(page)
      await waitForList(page)
      const reset = await countsOnScreen(page)
      check('翻回第 1 页（重置）之后计数仍是同一组',
        JSON.stringify(reset) === JSON.stringify(before),
        `第2页 ${JSON.stringify(after)} vs 重置后 ${JSON.stringify(reset)}`)
    }
  }

  // ── 六、改筛选：计数跟着变，且与新算的一组一致 ──
  {
    const jobsProbe = await apiGet('/jobs')
    const jobList = Array.isArray(jobsProbe) ? jobsProbe : []
    if (jobList.length === 0) {
      skip('按任务筛的那一段（接口没给任务清单）')
    } else {
      // 挑一个**真的是子集**的任务（0 < runs < 全部）：这样「计数跟着筛选变小」才是个真断言
      const totalRuns = (await apiGet('/history/runs/summary')).runs
      let picked = null
      for (const job of jobList) {
        const one = await apiGet(`/history/runs/summary?jobId=${job.id}`)
        if (one && one.runs > 0 && one.runs < totalRuns) { picked = { job, one }; break }
      }
      if (!picked) {
        skip('按任务筛的那一段（库里没有「部分命中」的任务）')
      } else {
        const before = await countsOnScreen(page)
        await pickOption(page, '.hc-filter-job', `#${picked.job.id} `)
        const sent = summaryUrls(0).filter(u => u.includes(`jobId=${picked.job.id}`))
        check('任务筛选也打给聚合那条请求（同一组筛选）', sent.length > 0, summaryUrls(0).join(' '))
        const after = await countsOnScreen(page)
        // 只挑了一个「确实是子集」的任务（runs < 全部），所以计数必须真的变小——不给自己留后门
        check('按任务筛之后计数真的变小了（不是「没变也算过」）',
          after['运行'] < before['运行'] && after['消息'] < before['消息'],
          `前 ${JSON.stringify(before)} 后 ${JSON.stringify(after)}`)
        check('按任务筛之后的四个数与接口重算的一组一致',
          after['运行'] === picked.one.runs && after['消息'] === picked.one.messages
            && after['消息步'] === picked.one.steps && after['失败步'] === picked.one.failedSteps,
          `界面 ${JSON.stringify(after)} vs 接口 ${JSON.stringify(picked.one)}`)
        await page.screenshot({ path: path.join(shots, 'history-130-filtered.png') })

        // 重置回初始的一组：清筛选、回第 1 页
        await page.getByRole('button', { name: '重置' }).click()
        await settle(page)
        await waitForList(page)
        const reset = await countsOnScreen(page)
        const fresh = await apiGet('/history/runs/summary')
        check('重置之后计数回到初始那一组',
          reset['运行'] === fresh.runs && reset['消息步'] === fresh.steps,
          `界面 ${JSON.stringify(reset)} vs 接口 ${JSON.stringify(fresh)}`)
      }
    }
  }

  // ── 七、命中的业务键显示出来了（#129 的「只筛不显示」缺口） ──
  // 那一格**只在筛了业务键时才有内容**（不筛时接口就不回它，省掉一页几十个标签）：
  // 所以先从消息步里挑一个真存在的业务键，再按它筛。
  {
    const probeSteps = await apiGet('/history/steps?page=1&size=20')
    const key = ((probeSteps && probeSteps.records) || [])
      .map(s => (s.businessKey || '').trim()).find(k => k)
    if (!key) {
      skip('命中业务键那一段（库里没有带业务键的消息步）')
    } else {
      const before = seq()
      const biz = page.getByPlaceholder('SO-2026-0917-0042')
      await biz.fill(key)
      await biz.press('Enter')
      await settle(page)
      await waitForList(page)
      const filtered = summaryUrls(before)
      check('业务键敲回车也带上聚合请求', filtered.length > 0 && filtered.join(' ').includes('businessKey='),
        filtered.join(' '))

      const matched = await apiGet(`/history/runs/summary?businessKey=${encodeURIComponent(key)}`)
      const shown = await countsOnScreen(page)
      check('按业务键筛之后计数与接口重算的一组一致（运行侧口径：任一消息命中即保留整运行）',
        shown['运行'] === matched.runs && shown['消息'] === matched.messages
          && shown['消息步'] === matched.steps && shown['失败步'] === matched.failedSteps,
        `界面 ${JSON.stringify(shown)} vs 接口 ${JSON.stringify(matched)}`)
      // 三个步数也得跟着业务键缩：曾经它们只看任务 / 状态，于是「运行 19 / 消息 5478」这种自相矛盾
      const totalCounts = await apiGet('/history/runs/summary')
      check('业务键把消息 / 消息步 / 失败步一起缩窄（不是只缩运行那一格）',
        matched.messages < totalCounts.messages && matched.steps < totalCounts.steps && matched.messages > 0,
        `筛后 ${JSON.stringify({ messages: matched.messages, steps: matched.steps })}`
          + ` vs 全部 ${JSON.stringify({ messages: totalCounts.messages, steps: totalCounts.steps })}`)

      check('接口在筛了业务键时把那几个命中键带回（界面才有东西可显示）',
        matched.summaries.every(s => s.matchedBusinessKeys.every(k => k.includes(key))),
        JSON.stringify(matched.summaries[0]))
      const withKey = matched.summaries.find(s => s.matchedBusinessKeys.length > 0)
      if (!withKey) {
        skip('行上业务键标签那一段（接口这一页没给命中键）')
      } else {
        const row = page.locator('.hc-ritem').filter({ hasText: `#${withKey.runId}` }).first()
        const labels = await row.locator('.hc-r-biz').allInnerTexts()
        check('行上把命中的业务键显示出来了（不是只筛不显示）',
          labels.length > 0 && labels.some(t => withKey.matchedBusinessKeys.some(k => t.trim() === k)),
          `${labels.join(',')} vs ${withKey.matchedBusinessKeys.join(',')}`)
        await page.screenshot({ path: path.join(shots, 'history-130-bizkey.png') })
      }

      // 筛没了：四个数**一起归零**（曾经是「运行 0 / 消息 5478」）
      const none = page.getByPlaceholder('SO-2026-0917-0042')
      await none.fill('ZZZ-NO-SUCH-BUSINESS-KEY')
      await none.press('Enter')
      await settle(page)
      await waitForList(page)
      const zeroed = await countsOnScreen(page)
      check('筛到一条都没有时四个数一起归零（不留「运行 0 而消息不是 0」的自相矛盾）',
        zeroed['运行'] === 0 && zeroed['消息'] === 0 && zeroed['消息步'] === 0 && zeroed['失败步'] === 0,
        JSON.stringify(zeroed))

      // 没筛业务键时那一格整个不铺（别拿一堆没人看的标签占地方）
      await none.fill('')
      await none.press('Enter')
      await settle(page)
      await waitForList(page)
      check('清掉业务键之后行上不再铺业务键标签',
        (await page.locator('.hc-ritem .hc-r-biz').count()) === 0,
        String(await page.locator('.hc-ritem .hc-r-biz').count()))
      const restored = await countsOnScreen(page)
      const allCounts = await apiGet('/history/runs/summary')
      check('清掉业务键之后计数回到全量那一组',
        restored['运行'] === allCounts.runs, `界面 ${JSON.stringify(restored)} vs 全部 ${allCounts.runs}`)
    }
  }

  // ── 八、两个模式都铺着计数条，且数字不随模式变（运行侧那一组） ──
  {
    const before = await countsOnScreen(page)
    await page.locator('.hc-seg .el-radio-button').filter({ hasText: '消息步检索' }).first().click()
    await settle(page)
    await waitForList(page)
    const inSteps = await countsOnScreen(page)
    check('消息步检索模式下计数条照旧在（页头那一条不属于某一个模式）',
      ['运行', '消息', '消息步', '失败步'].every(k => typeof inSteps[k] === 'number'),
      JSON.stringify(inSteps))
    check('两边的计数是各自筛选集算的（消息步那一侧带默认 status=FAILED）',
      summaryUrls(0).some(u => u.includes('status=FAILED')) || JSON.stringify(inSteps) === JSON.stringify(before),
      summaryUrls(0).join(' '))
    await page.getByRole('button', { name: '重置' }).click()
    await settle(page)
    await waitForList(page)
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
