/*
 * #133 真机 UI 验收：消息历史页的**「与上一步对照」**（快照面板的第四档视图）。
 *
 * 票面（#128 子票 / ADR-0016 决策 9）与验收项：
 *   ① 上一步与本步同为快照时，逐路径给出 同 / 改 / 增 / 减 与计数，**计数与行数一致**；
 *   ② 数组元素增减能看出「按下标对齐」的结果，界面的口径说明能解释它；
 *   ③ 第一步显示「没有上一步可对照」，不显示空表格；
 *   ④ 任一侧缺快照时显示明确原因，不是「没数据」；
 *   ⑤ 真机断言**计数与至少一条「改」的行**。
 * 票面之外（同一段视图的分内事，一起验）：视图四档（并排 / 只看输入 / 只看数据 / 对照）、
 * 切到对照再切回来不重挂树（用户在树上开合过的状态还在）；外加一条取数口径：
 * 上一步的整行与当前那一步各取一次、都不重复取，第一步**不发**上一步那次请求。
 *
 * 跑法（playwright 是本目录自带的，先 `npm i` 一次就行）：
 *   cd tools/ui && node message-history-133.cjs
 * 跑之前前端 dev server（5173）与后端（8080）都要在。这一票**没有后端改动**
 * （两份快照都走 #74 就有的 `/history/steps/{id}`），所以没有「后端是旧进程」那一段 SKIP；
 * 前端要是旧的一版（没有 view 分段 / 对照块不在），那是真没过，不装成 SKIP。
 *
 * 库里的数据不够那几段（找不到一条消息里相邻两步都落了可解析的数据快照）时打印 SKIP 并跳过，
 * 其余照跑（与 #129/#130/#131/#132/#136 同一套口径）。
 *
 * **对照的裁判是脚本自己**：下面 `flatten` / `diffOf` 是按票面口径另写的一份（不是 import 前端那份），
 * 两边算出来一样才算过——把前端的口径抄进脚本里当期望值，等于自己给自己盖章。
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

async function settle(page, ms = 3000) {
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

function parseMaybe(text) {
  try {
    return text ? JSON.parse(text) : null
  } catch {
    return null
  }
}

/** 两份快照能不能拿去对照：可解析，且不是空白（与界面「有数据快照」那一档同一个判据）。 */
function usable(text) {
  if (typeof text !== 'string' || !text.trim()) return false
  try {
    JSON.parse(text)
    return true
  } catch {
    return false
  }
}

/* ── 裁判：按票面口径自己算一遍（叶子路径对齐 / 逐格判决 / 四个计数） ───────── */

function flatten(value, at = '', out = new Map()) {
  if (Array.isArray(value)) {
    if (!value.length) out.set(at || '（标量）', value)
    value.forEach((item, i) => flatten(item, `${at}[${i}]`, out))
  } else if (value && typeof value === 'object') {
    const keys = Object.keys(value)
    if (!keys.length) out.set(at || '（标量）', value)
    for (const k of keys) flatten(value[k], at ? `${at}.${k}` : k, out)
  } else {
    out.set(at || '（标量）', value)
  }
  return out
}

function diffOf(before, after) {
  const a = flatten(before)
  const b = flatten(after)
  const paths = [...a.keys(), ...[...b.keys()].filter(k => !a.has(k))]
  const rows = paths.map(p => {
    const inA = a.has(p)
    const inB = b.has(p)
    const status = inA && inB
      ? (JSON.stringify(a.get(p)) === JSON.stringify(b.get(p)) ? 'same' : 'chg')
      : (inB ? 'add' : 'del')
    return { path: p, status, oldValue: a.get(p), newValue: b.get(p) }
  })
  const counts = { same: 0, chg: 0, add: 0, del: 0 }
  rows.forEach(r => { counts[r.status] += 1 })
  return { rows, counts }
}

const LABELS = { same: '同', chg: '改', add: '增', del: '减' }

/* ── 页面上的零件 ─────────────────────────────────────────────────────────── */

let stepReqs = []

const detailPath = url => /^\/api\/history\/steps\/\d+$/.test(url.pathname)

const diffRows = page => page.locator('[data-od-id="snapshot-diff-grid"] .hc-drow')

/** 视图那一排按钮（第 4 颗就是「与上一步对照」）。 */
const viewBtn = (page, label) => page.locator('[aria-label="快照视图"] button').filter({ hasText: label })

/** 等到「这一次点击真的发了那一步的整行请求」并且面板不再是占位。 */
async function waitForSnapshot(page, before) {
  const deadline = Date.now() + 15000
  while (Date.now() < deadline && stepReqs.length <= before) await page.waitForTimeout(80)
  await page.waitForFunction(() => !document.querySelector('.hc-snaps-wrap .hc-skel'), null, { timeout: 15000 })
    .catch(() => {})
  await page.waitForTimeout(150)
  return stepReqs.length > before
}

async function clickNode(page, n) {
  const before = stepReqs.length
  await page.locator('.hc-chain .hc-cbtn:not(.hc-cbtn-entry)').nth(n).click()
  return waitForSnapshot(page, before)
}

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

/** 把两格快照读成脚本要的形状（整行里那两个字段）。 */
function rowShape(full) {
  return { hasData: usable(full.dataSnapshot), data: parseMaybe(full.dataSnapshot) }
}

async function main() {
  browser = await chromium.launch()
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const page = await context.newPage()
  page.on('request', req => {
    const url = req.url()
    if (url.includes('/api/history/steps/')) stepReqs.push({ url })
    requests.push({ url })
  })

  // 造数据用的补丁：换一步再点回来时面板会重新取整行，那一刻把快照换掉。
  // 拦的是真响应（`route.fetch()`）再改那一格——其余字段仍是后端给的真东西。
  // `patchOf(body)` 按**哪一步**自己判（`body.data.id`），两个方向（上一步 / 本步）都能造。
  let patchOf = null
  await page.route(url => detailPath(url), async route => {
    const res = await route.fetch()
    let body = await res.json().catch(() => null)
    if (patchOf && body) body = patchOf(body) || body
    await route.fulfill({ response: res, json: body })
  })

  await page.goto(`${APP}/history`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.hc-page', { timeout: 20000 })
  await waitForList(page)
  fs.mkdirSync(shots, { recursive: true })

  // ── 找一条消息：相邻两步都落了**可解析的**数据快照（对照要的正是这两份） ──
  let run = null
  let messages = []
  let mi = -1
  let si = -1
  let prevFull = null
  let stepFull = null
  {
    const firstPage = await apiGet('/history/runs?page=1&size=8')
    for (const candidate of (firstPage ? firstPage.records : [])) {
      const ix = await apiGet(`/history/runs/${candidate.id}/steps?page=1&size=500`)
      if (!ix) continue
      const grouped = groupMessages(ix.records)
      for (let m = 0; m < grouped.length; m += 1) {
        const steps = grouped[m].steps
        for (let s = 1; s < steps.length; s += 1) {
          const prev = steps[s - 1]
          const cur = steps[s]
          if (!prev.hasDataSnapshot || !cur.hasDataSnapshot) continue
          const pf = await apiGet(`/history/steps/${prev.id}`)
          const cf = await apiGet(`/history/steps/${cur.id}`)
          if (!pf || !cf) continue
          if (!rowShape(pf).hasData || !rowShape(cf).hasData) continue
          run = candidate
          messages = grouped
          mi = m
          si = s
          prevFull = pf
          stepFull = cf
          break
        }
        if (run) break
      }
      if (run) break
    }
    if (!run) {
      skip('整段（这条库里找不到「同一条消息里相邻两步都落了可解析的数据快照」）')
      return
    }
  }
  const order = messages[mi].steps
  const prevStep = order[si - 1]
  const step = order[si]
  const bizKey = order.map(s => (s.businessKey || '').trim()).find(k => k) || ''

  if (!await openRunByBusinessKey(page, run.id, bizKey)) {
    skip(`整段（列表里翻不到运行 #${run.id}）`)
    return
  }
  if (mi > 0) {
    await page.locator('.hc-mchip').nth(mi).click()
    await page.waitForTimeout(250)
  }
  await clickNode(page, si)

  // ── 一、视图四档与「对照」那一档进来 ──
  {
    // 先把线画在这儿：**换一步**（选中态一变）才会去取上一步的整行，光是切视图不该发请求
    const before = stepReqs.length
    const labels = (await page.locator('[aria-label="快照视图"] button').allInnerTexts()).map(t => t.trim())
    check('工具栏上视图是一排四档（并排 / 只看输入 / 只看数据 / 与上一步对照）',
      JSON.stringify(labels) === JSON.stringify(['并排对照', '只看输入', '只看数据', '与上一步对照']),
      labels.join(' | '))

    await viewBtn(page, '与上一步对照').click()
    await page.waitForTimeout(400)
    check('这一档是**换一步**时才去取上一步的整行（切视图本身不发请求）',
      stepReqs.slice(before).length === 0, stepReqs.slice(before).map(r => r.url).join(' '))
    check('上一步的整行确实取到了（换到这一步时就取过，两格快照都在手里）',
      stepReqs.some(r => r.url.endsWith(`/history/steps/${prevStep.id}`)),
      stepReqs.map(r => r.url).join(' '))
    check('对照那一档铺的是对照块（两块快照同时收起来）',
      await page.locator('[data-od-id="snapshot-diff"]').count() === 1
        && await page.locator('.hc-snap:visible').count() === 0,
      `diff=${await page.locator('[data-od-id="snapshot-diff"]').count()} 可见快照块=${await page.locator('.hc-snap:visible').count()}`)
  }

  // ── 二、计数与行数一致，且「改」那一行与脚本自己算的一致（票面第 ① 与第 ⑤ 条） ──
  {
    const expected = diffOf(rowShape(prevFull).data, rowShape(stepFull).data)
    const rows = await diffRows(page).count()
    check('行数 = 脚本自己按叶子路径对齐算出的行数',
      rows === expected.rows.length, `界面 ${rows} vs 期望 ${expected.rows.length}`)

    const summary = (await page.locator('.hc-dsum').innerText()).replace(/\s+/g, ' ')
    const wanted = ['same', 'chg', 'add', 'del'].map(k => `${LABELS[k]} ${expected.counts[k]}`)
    check('摘要四个计数与脚本自己算的一致',
      wanted.every(t => summary.includes(t)), `${summary} vs ${wanted.join(' / ')}`)

    // 逐行核：路径、两侧的显示值、判决那一格的字
    const grid = await page.locator('[data-od-id="snapshot-diff-grid"]').innerText()
    check('每一行都在表里（路径逐条对得上）',
      expected.rows.every(r => grid.includes(r.path)),
      grid.slice(0, 160))

    const firstChg = expected.rows.find(r => r.status === 'chg')
    if (!firstChg) {
      // 真库扫下来是这样：实测 107 对相邻数据快照里**一对「改」都没有**——这条链路每一步换的是
      // 键的集合（`FID/FCode/…` → `codes[]` → `code/count/results[]`），同名路径变值一次没出现过。
      // 所以「至少一条改」（票面第 ⑤ 条）由下面第四段**造数据**满足，这里照实 SKIP。
      skip('「至少一条改」那一项（这条库的相邻快照里没有同名路径变值）')
    } else {
      const rowBox = diffRows(page).filter({ hasText: firstChg.path }).first()
      const cells = (await rowBox.locator('span').allInnerTexts()).map(t => t.trim())
      check(`「改」那一行的路径对得上（${firstChg.path}）`,
        cells[0] === firstChg.path, cells.join(' | '))
      // 两侧的值：**只核「两边都在、且两边不一样、判决是改」**这条不变量，不逐字抄界面那份格式化。
      // 界面那份（`diffCellText`）会把 ISO 时刻写成 `2026-08-26 10:00:00 UTC`、空串写成 `""`——
      // 脚本里照抄一份就成了「抄实现当期望值」，那份格式化该由纯模块自测去锁（diff-checks.ts 里锁着）。
      check('「改」那一行两侧的值都在、且不相等（上一步 → 本步）',
        cells[1] !== '' && cells[3] !== '' && cells[1] !== cells[3],
        `界面 ${JSON.stringify(cells[1])} → ${JSON.stringify(cells[3])}`
          + `（脚本算的原值 ${JSON.stringify(firstChg.oldValue)} → ${JSON.stringify(firstChg.newValue)}）`)
      check('「改」那一行的判决字是「改」', cells[4] === '改', cells.join(' | '))
      check('路径那一格的 title 就是这条路径（不是原型那套 `context[N]` 前缀）',
        (await rowBox.locator('.hc-dpath').getAttribute('title')) === firstChg.path,
        `${await rowBox.locator('.hc-dpath').getAttribute('title')} vs ${firstChg.path}`)
    }

    const counts = ['same', 'chg', 'add', 'del'].reduce((sum, k) => sum + expected.counts[k], 0)
    check('四个计数之和 = 行数（票面「计数与行数一致」）',
      counts === rows, `${counts} vs ${rows}`)
    await page.screenshot({ path: path.join(shots, 'history-133-diff.png') })
  }

  // ── 三、口径说明写在界面上（票面第 ② 条后半：能解释按下标对齐那个后果） ──
  {
    const note = await page.locator('[data-od-id="snapshot-diff-note"]').innerText()
    check('对照那一块带一句口径说明（叶子路径级、不是语义级）',
      note.includes('对照口径') && note.includes('不是语义级'), note)
    check('口径说明点明了数组按索引对齐的后果',
      note.includes('数组按索引对齐') && note.includes('后续下标全判成改'), note)
  }

  // ── 四、造数据补两个真库造不出来的判决（数组按下标错位 + 「改」与「减」） ──
  // 真库实测（107 对相邻快照）里**一个「改」、一个「减」都没有**：这条链路每一步换的是键的集合，
  // 同名路径变值 / 只在上一步出现的叶子一次没出现过。票面第 ② 与第 ⑤ 条要的那两样，只能造。
  {
    const other = si === 0 ? 1 : 0  // 换一步把上一次取的那一份顶掉（同一步不重复取）
    const prevData = JSON.stringify({ lines: [{ sku: 'A' }, { sku: 'B' }], note: '旧', gone: 1 })
    const stepData = JSON.stringify({ lines: [{ sku: 'X' }, { sku: 'A' }, { sku: 'B' }], note: '新' })
    patchOf = null
    await clickNode(page, other)
    // 上一步与当前那一步**都要**换成造出来的那份：对照读的正是这两份
    patchOf = (body) => {
      if (!body.data) return body
      if (body.data.id === prevStep.id) body.data.dataSnapshot = prevData
      if (body.data.id === step.id) body.data.dataSnapshot = stepData
      return body
    }
    await clickNode(page, si)

    const expected = diffOf(JSON.parse(prevData), JSON.parse(stepData))
    const rows = await diffRows(page).count()
    const summary = (await page.locator('.hc-dsum').innerText()).replace(/\s+/g, ' ')
    check('造出来的那份：行数与四个计数照脚本自己算的来（同 3 / 改 3 / 增 1 / 减 1）',
      rows === expected.rows.length
        && ['same', 'chg', 'add', 'del'].every(k => summary.includes(`${LABELS[k]} ${expected.counts[k]}`)),
      `${rows} 行 :: ${summary} vs ${JSON.stringify(expected.counts)}`)
    const grid = await page.locator('[data-od-id="snapshot-diff-grid"]').innerText()
    check('数组中间插入后按下标错位（`lines[0].sku` 与 `lines[1].sku` 都判成改）',
      grid.includes('lines[0].sku') && grid.includes('lines[1].sku'), grid.slice(0, 120))

    // **票面第 ⑤ 条那一条「改」在这里满足**：同名路径 `note` 的值从「旧」变成「新」
    const changed = diffRows(page).filter({ hasText: 'note' }).first()
    const changedCells = (await changed.locator('span').allInnerTexts()).map(t => t.trim())
    check('同名路径变值那一行：判决是「改」，两侧的值都在且不一样',
      changedCells[0] === 'note' && changedCells[4] === '改'
        && changedCells[1] !== '' && changedCells[3] !== '' && changedCells[1] !== changedCells[3],
      changedCells.join(' | '))

    const added = diffRows(page).filter({ hasText: 'lines[2].sku' }).first()
    const addedCells = (await added.locator('span').allInnerTexts()).map(t => t.trim())
    check('多出来的那一项是「增」，左边是破折号',
      addedCells[4] === '增' && addedCells[1] === '—', addedCells.join(' | '))

    const gone = diffRows(page).filter({ hasText: 'gone' }).first()
    const goneCells = (await gone.locator('span').allInnerTexts()).map(t => t.trim())
    check('只在上一步出现的那一项是「减」，右边是破折号',
      goneCells[4] === '减' && goneCells[3] === '—', goneCells.join(' | '))
    await page.screenshot({ path: path.join(shots, 'history-133-array.png') })
    patchOf = null
    await clickNode(page, other)
  }

  // ── 五、第一步：说「没有上一步可对照」，并且**不发**上一步那次请求 ──
  {
    // 这一段要一条**与演示那条不同**的消息：换消息会把上一步那一格清掉，
    // 在它的第一步上数请求数才说明得了「没为上一步发请求」。
    const otherIndex = messages.findIndex((m, i) => i !== mi && m.steps[0].id !== order[0].id)
    if (otherIndex < 0) {
      skip('「第一步不发上一步请求」那一段（这条运行里没有第二条消息）')
    } else {
      const otherFirst = messages[otherIndex].steps[0]
      // 基线画在**点消息 chip 之前**：换消息本身会把上一步那一格清掉，取数也在那一刻发生
      const before = stepReqs.length
      await page.locator('.hc-mchip').nth(otherIndex).click()
      await page.waitForTimeout(400)
      const after = stepReqs.slice(before)
      const text = await page.locator('[data-od-id="snapshot-diff-blocked"]').first().innerText().catch(() => '')
      check('第一步显示「没有上一步可对照」（不显示空表格）',
        text.includes('是这条消息的第一步') && text.includes('没有上一步可对照')
          && await diffRows(page).count() === 0,
        `${JSON.stringify(text)} 行数=${await diffRows(page).count()}`)
      check('第一步只取了自己那一行（没为上一步、也没为别的步发请求）',
        after.length >= 1
          && after.every(r => r.url.endsWith(`/history/steps/${otherFirst.id}`)),
        `${after.length} 个：${after.map(r => r.url).join(' ')}`)
      await page.screenshot({ path: path.join(shots, 'history-133-first.png') })

      // 回到演示那条消息与那一步，后面两段还要用它
      await page.locator('.hc-mchip').nth(mi).click()
      await settle(page)
      await page.waitForTimeout(400)
      await clickNode(page, si)
    }
  }

  // ── 六、任一侧缺快照：说清是哪一侧、为什么（拦接口把上一步那份换成没落 / 非 JSON） ──
  {
    const other = si === 0 ? 1 : 0
    patchOf = null
    await clickNode(page, other)
    patchOf = (body) => {
      if (body.data && body.data.id === prevStep.id) body.data.dataSnapshot = null
      return body
    }
    await clickNode(page, si)
    const text = await page.locator('[data-od-id="snapshot-diff-blocked"]').innerText().catch(() => '')
    check('上一步没有数据快照时，说清是**哪一侧**、以及为什么（不是「没数据」三个字）',
      text.includes(`上一步（步骤 ${prevStep.step}`) && text.includes('没有数据快照')
        && text.includes('MQ 入口动作也不记成功步快照') && text.includes('没法对照'),
      text)
    await page.screenshot({ path: path.join(shots, 'history-133-blocked.png') })

    patchOf = null
    await clickNode(page, other)
    const notJson = '这一段不是 JSON：{"FCode": 打住'
    patchOf = (body) => {
      if (body.data && body.data.id === prevStep.id) body.data.dataSnapshot = notJson
      return body
    }
    await clickNode(page, si)
    const text2 = await page.locator('[data-od-id="snapshot-diff-blocked"]').innerText().catch(() => '')
    check('上一步那份不是 JSON 文本时，给的是另一句（与「没有数据快照」分得开）',
      text2.includes('不是 JSON 文本') && text2.includes('没有键路径可对照'), text2)
    patchOf = null
    await clickNode(page, other)
    await clickNode(page, si)
  }

  // ── 七、前三档取景（并排 / 只看输入 / 只看数据），以及切走再切回来不重挂 ──
  {
    // 视图那一档的「只看某一块」现在是 `v-show`（两块都在 DOM 里，只切显示）——
    // 判据因此是**可见性**，不是「在不在 DOM 里」：前者是用户看到的，后者是实现细节。
    await viewBtn(page, '并排对照').click()
    await page.waitForTimeout(300)
    check('「并排对照」两块都看得见',
      await page.locator('.hc-snap:visible').count() === 2,
      String(await page.locator('.hc-snap:visible').count()))
    await viewBtn(page, '只看输入').click()
    await page.waitForTimeout(250)
    check('「只看输入」只剩输入那一块看得见',
      await page.locator('.hc-snap[data-od-id="snapshot-input"]:visible').count() === 1
        && await page.locator('.hc-snap[data-od-id="snapshot-data"]:visible').count() === 0)
    await viewBtn(page, '只看数据').click()
    await page.waitForTimeout(250)
    check('「只看数据」只剩数据那一块看得见',
      await page.locator('.hc-snap[data-od-id="snapshot-data"]:visible').count() === 1
        && await page.locator('.hc-snap[data-od-id="snapshot-input"]:visible').count() === 0)

    // 在树上折起一个节点 → 切到对照 → 切回来：折叠态还在（树的 DOM 没被销毁重建）。
    // 先**真的折出一次行数变少**再往下断言，不然「折」那一下可能正好是把默认折叠的展开，
    // 后面比出来的两个数都不是「用户折过的那一下」。
    const bracket = page.locator('.hc-snap[data-od-id="snapshot-data"] .vjs-tree-brackets').first()
    if (await bracket.count() === 0) {
      skip('「切到对照再切回来不重挂」那一段（这份快照在树上没有可折的括号）')
    } else {
      await viewBtn(page, '只看数据').click()
      await page.waitForTimeout(250)
      const rowsBefore = await page.locator('.hc-snap[data-od-id="snapshot-data"] .vjs-tree-node').count()
      await bracket.click()
      await page.waitForTimeout(250)
      const folded = await page.locator('.hc-snap[data-od-id="snapshot-data"] .vjs-tree-node').count()
      await viewBtn(page, '与上一步对照').click()
      await page.waitForTimeout(300)
      await viewBtn(page, '只看数据').click()
      await page.waitForTimeout(300)
      const back = await page.locator('.hc-snap[data-od-id="snapshot-data"] .vjs-tree-node').count()
      check('切到对照再切回来，树上手工折起的那一节还是折着的（树没被重挂）',
        back === folded, `折后 ${folded}，切回来 ${back}（默认全展开是 ${rowsBefore}）`)
    }
    await viewBtn(page, '并排对照').click()
    await page.waitForTimeout(250)
  }

  // ── 八、取数口径：换一步恰好两次请求（当前那一步 + 上一步），同一步再来一次不再发 ──
  {
    const other = si === 0 ? 1 : 0
    await clickNode(page, other)
    const before = stepReqs.length
    await clickNode(page, si)
    const after = stepReqs.slice(before).map(r => r.url)
    check('换一步正好两次请求：当前那一步的整行 + 上一步的整行（不随消息步数增长）',
      after.length === 2
        && after.some(u => u.endsWith(`/history/steps/${step.id}`))
        && after.some(u => u.endsWith(`/history/steps/${prevStep.id}`)),
      after.join(' '))

    const settled = stepReqs.length
    await page.locator('.hc-chain .hc-cbtn:not(.hc-cbtn-entry)').nth(si).click()
    await page.waitForTimeout(700)
    check('同一步再点一次两个请求都不再发',
      stepReqs.length === settled, `${settled} → ${stepReqs.length}`)
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
