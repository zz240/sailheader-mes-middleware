/*
 * #132 真机 UI 验收：消息历史页的**快照面板**（单步卡里的输入 / 数据两份快照）。
 *
 * 票面（#128 子票 / ADR-0016 决策 4、8、10）：
 *   格式化树（可折叠展开、长值截断可查、能复制）、按键路径搜索（过滤 + 自动展开到命中路径，
 *   **不做**行内 `<mark>`——决策 10 记下的有意偏离）、三种降级各给一句口径说明
 *   （非 JSON 文本原样显示、空对象 / 空数组、超长不卡死），判定口径落在纯模块里。
 *
 * 对着这些断言：
 *   一、**真机走通**：打开一条有快照的运行，单步卡里出现两块（输入快照 / 数据快照），
 *      标题与去向写得对；树真的画出来了，且树上的键与**接口那份快照解析出来的键**逐一对得上
 *      （顺带证明面板取的是 `/history/steps/{id}` 那条**整行**的路——索引那条路上没有快照原文）；
 *   二、**折叠 / 展开**：点根那个括号能收起能展开；工具栏「展开」三档切过去行数跟着变；
 *   三、**搜索键路径**：命中数由脚本自己按路径算一遍当裁判，命中那句写在面板里；清空搜索恢复原状；
 *   四、**复制**：面板头上那个按钮写进剪贴板的东西 = 接口那份快照的格式化 JSON；
 *   五、**三种降级**（拦接口造数据，票面要求真机至少验两种，这里三种都验）：
 *      非 JSON 文本原样显示并注明、空对象给明确提示（不是一片空白）、超长只画前 8 KB 并注明，
 *      而且超长之后页面还能接着用；
 *   六、**长值截断可查**（同样拦接口造一条两百字的字符串）：一行先截到 72 字 + 「展开 N 字」，
 *      展开后整段都在；一格上的「复制值」复制的是**没截断**的原值；
 *   七、**同一步不重复取**：同一步再点一次不再发请求（一次点击最多一次请求）；
 *   外加两条版式断言：快照树自己横滚、没把右栏撑出横向滚动；右栏够宽时两块是并排的。
 *
 * 跑法（playwright 是本目录自带的，先 `npm i` 一次就行）：
 *   cd tools/ui && node message-history-132.cjs
 * 跑之前前端 dev server（5173）与后端（8080）都要在。这一票**没有后端改动**
 * （快照走的是 #74 就有的 `/history/steps/{id}`），所以没有「后端是旧进程」那一段 SKIP；
 * 前端要是旧的一版（面板不在），那是真没过，不装成 SKIP。
 *
 * 库里数据不够那几段（一条带快照的消息步都没有 / 快照是平的没有嵌套可折 / 这条消息只有一步，
 * 换不动步）时打印 SKIP 并跳过，其余照跑（与 #111/#112/#129/#130/#131/#136 同一套口径）。
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

/**
 * 命中数怎么算：**路径含搜索片段**的节点数（原型 `walk` 里那个 `onMatch`，与纯模块同一口径）。
 * 脚本自己走一遍是当独立裁判——不是把界面上的数字抄回来。
 */
function countHits(value, query) {
  const q = query.toLowerCase()
  let hits = 0
  const walk = (node, p) => {
    if (p && p.toLowerCase().includes(q)) hits += 1
    if (Array.isArray(node)) node.forEach((item, i) => walk(item, `${p}[${i}]`))
    else if (node && typeof node === 'object') {
      for (const [key, item] of Object.entries(node)) walk(item, p ? `${p}.${key}` : key)
    }
  }
  walk(value, '')
  return hits
}

/** 这份快照里有没有嵌套容器——「展开层数」那一档要拿它决定能不能断言（平的快照切层数没区别）。 */
function hasNested(value) {
  if (value && typeof value === 'object') {
    return Object.values(value).some(item => item && typeof item === 'object')
  }
  return false
}

/** 能解析就解析，不能就算了（库里也有非 JSON 文本的快照，#90 那条口径）。 */
function parseMaybe(text) {
  try {
    return text ? JSON.parse(text) : null
  } catch {
    return null
  }
}

const rowsOf = (page, which) => page.locator(`.hc-snap[data-od-id="snapshot-${which}"] .vjs-tree-node`)
const rawOf = (page, which) => page.locator(`.hc-snap[data-od-id="snapshot-${which}"] .hc-sraw`)
const searchNoteOf = (page, which) =>
  page.locator(`.hc-snap[data-od-id="snapshot-${which}"] [data-od-id="snapshot-search-note"]`)
const degradeNoteOf = (page, which) => page.locator(`[data-od-id="snapshot-${which}-note"]`)
const emptyNoteOf = (page, which) => page.locator(`[data-od-id="snapshot-${which}-empty"]`)
const copyBtnOf = (page, which) => page.locator(`.hc-snap[data-od-id="snapshot-${which}"] .hc-snapcp`)

/** 右栏的消息步取完（或落到空态 / 出错）。 */
const chainNodesOnScreen = page => page.locator('.hc-chain .hc-cbtn:not(.hc-cbtn-entry)')

/** 回到「按运行展开」那一侧（消息步行长得不一样，按 id 找行之前得先站对地方）。 */
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

let stepReqs = []
const stepReqUrls = () => stepReqs.map(r => r.url)

/** 等到「这一次点击真的发了那一步的整行请求」并且面板不再是占位。 */
async function waitForSnapshot(page, before) {
  const deadline = Date.now() + 15000
  while (Date.now() < deadline && stepReqs.length <= before) await page.waitForTimeout(80)
  await page.waitForFunction(() => !document.querySelector('.hc-snaps-wrap .hc-skel'), null, { timeout: 15000 })
    .catch(() => {})
  await page.waitForTimeout(150)
  return stepReqs.length > before
}

/** 点链上第 n 格（换一步 = 面板会重新取那一步的整行）。 */
async function clickNode(page, n) {
  const before = stepReqs.length
  await chainNodesOnScreen(page).nth(n).click()
  return waitForSnapshot(page, before)
}

/** 拦 `/api/history/steps/{id}`（**整行**那条路：列表那条是 `/history/steps?page=`，不带 id）。 */
const detailPath = url => /^\/api\/history\/steps\/\d+$/.test(url.pathname)

async function main() {
  browser = await chromium.launch()
  const context = await browser.newContext({
    viewport: { width: 1440, height: 900 },
    permissions: ['clipboard-read', 'clipboard-write'],
  })
  const page = await context.newPage()
  page.on('request', req => {
    const url = req.url()
    if (url.includes('/api/history/steps/')) stepReqs.push({ url })
    requests.push({ url })
  })

  // 造降级数据用的补丁：换一步再点回来时面板会重新取整行，那一刻把两份快照替换掉。
  // 拦的是真响应（`route.fetch()`）再改两格——其余字段仍是后端给的真东西。
  let patch = null
  await page.route(url => detailPath(url), async route => {
    const res = await route.fetch()
    const body = await res.json().catch(() => null)
    if (patch && body && body.data) {
      if (patch.input !== undefined) body.data.inputSnapshot = patch.input
      if (patch.data !== undefined) body.data.dataSnapshot = patch.data
    }
    await route.fulfill({ response: res, json: body })
  })

  await page.goto(`${APP}/history`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.hc-page', { timeout: 20000 })
  await waitForList(page)
  fs.mkdirSync(shots, { recursive: true })

  // ── 找一条「某一步落了数据快照」的运行（逐条问索引，问的是那两个布尔） ──
  // 挑演示那一步时**优先挑数据快照是嵌套的**：库里最新那几条运行的快照大多是平的一层对象，
  // 而「展开层数三档」那段要嵌套才验得出来。每条运行最多问 6 步、最多看 8 条运行。
  let run = null
  let messages = []
  let mi = -1
  let si = -1
  let stepFull = null
  {
    const firstPage = await apiGet('/history/runs?page=1&size=8')
    for (const candidate of (firstPage ? firstPage.records : [])) {
      const ix = await apiGet(`/history/runs/${candidate.id}/steps?page=1&size=500`)
      if (!ix) continue
      const grouped = groupMessages(ix.records)
      let first = null
      let nested = null
      let asked = 0
      for (let m = 0; m < grouped.length && !nested && asked < 6; m += 1) {
        for (let s = 0; s < grouped[m].steps.length && asked < 6; s += 1) {
          const st = grouped[m].steps[s]
          if (!st.hasDataSnapshot) continue
          asked += 1
          const full = await apiGet(`/history/steps/${st.id}`)
          if (!full) continue
          if (!first) first = { mi: m, si: s, full }
          if (hasNested(parseMaybe(full.dataSnapshot))) {
            nested = { mi: m, si: s, full }
            break
          }
        }
      }
      const hit = nested || first
      if (hit) {
        run = candidate
        messages = grouped
        mi = hit.mi
        si = hit.si
        stepFull = hit.full
        break
      }
    }
    if (!run) {
      skip('整段（库里没有一条消息步落过数据快照）')
      return
    }
  }
  const order = messages[mi].steps
  const step = order[si]
  const bizKey = order.map(s => (s.businessKey || '').trim()).find(k => k) || ''

  if (!await openRunByBusinessKey(page, run.id, bizKey)) {
    skip(`整段（列表里翻不到运行 #${run.id}）`)
    return
  }

  // 切到目标那条消息的那一步：点它的消息 chip（第 1 条时不用点），再点链上对应那格
  if (mi > 0) {
    await page.locator('.hc-mchip').nth(mi).click()
    await page.waitForTimeout(250)
  }
  await clickNode(page, si)

  // ── 一、两块快照都在，树与接口那份逐键对得上 ──
  {
    check('单步卡里出现快照面板（票面的真机验收项）',
      await page.locator('.hc-snaps-wrap').count() === 1,
      String(await page.locator('.hc-snaps-wrap').count()))
    const titles = (await page.locator('.hc-snap .hc-snapt').allInnerTexts()).map(t => t.trim())
    check('两块：输入快照在前、数据快照在后', JSON.stringify(titles) === JSON.stringify(['输入快照', '数据快照']),
      titles.join(' | '))
    const wheres = (await page.locator('.hc-snap .hc-snapw').allInnerTexts()).map(t => t.trim())
    check('去向我们写得对（输入是进入本步的报文，数据是本步输出 → context[步骤号]）',
      wheres[0].includes('进入本步的报文') && wheres[1] === `本步输出 → context[${step.step}]`,
      wheres.join(' | '))

    const parsed = JSON.parse(stepFull.dataSnapshot)
    const treeText = await page.locator('.hc-snap[data-od-id="snapshot-data"] .hc-stree').innerText()
    const keys = Object.keys(parsed)
    check('数据快照的树真的画出来了，且顶层键与接口那份逐一对得上',
      keys.length > 0 && keys.every(k => treeText.includes(k)),
      `接口键 ${keys.join(',')} vs 树上 ${JSON.stringify(treeText.slice(0, 120))}`)
    check('面板取的是**整行**那条路（`/history/steps/{id}`，索引那条路上没有快照原文）',
      stepReqUrls().some(u => u.includes(`/history/steps/${step.id}`)), stepReqUrls().join(' '))
    check('树是可折叠的那种（有折叠箭头 / 括号）',
      await rowsOf(page, 'data').count() > 0, String(await rowsOf(page, 'data').count()))
    // 树是**一行一值**的宽东西：它在自己那一格里横滚，不许把快照块（乃至右栏）撑宽
    const box = await page.evaluate(() => {
      const snaps = document.querySelector('.hc-snaps').getBoundingClientRect()
      const panel = document.querySelector('.hc-detail-panel').getBoundingClientRect()
      const scroll = document.querySelector('.hc-scroll')
      return {
        snapsRight: snaps.right, panelRight: panel.right,
        panelScrollX: scroll.scrollWidth, panelClientX: scroll.clientWidth,
      }
    })
    check('快照树自己横滚，没把右栏撑出横向滚动',
      box.snapsRight <= box.panelRight + 1 && box.panelScrollX <= box.panelClientX + 1, JSON.stringify(box))
    check('两块是并排的（右栏够宽时，原型 `.snaps` 那两列）',
      await page.evaluate(() => {
        const [a, b] = document.querySelectorAll('.hc-snap')
        return !!a && !!b && b.getBoundingClientRect().left >= a.getBoundingClientRect().right - 1
      }), '并排没成立')
    await page.screenshot({ path: path.join(shots, 'history-132-snapshots.png') })
  }

  // ── 二、折叠 / 展开 ──
  {
    const parsed = JSON.parse(stepFull.dataSnapshot)
    const parsedInput = parseMaybe(stepFull.inputSnapshot)
    const before = await rowsOf(page, 'data').count()
    const bracket = page.locator('.hc-snap[data-od-id="snapshot-data"] .vjs-tree-brackets').first()
    if (!await bracket.count()) {
      skip('「点根括号折叠 / 展开」那一段（树上没有可点的括号）')
    } else {
      await bracket.click()
      await page.waitForTimeout(200)
      const folded = await rowsOf(page, 'data').count()
      check('点根那个括号能把它整个折起来（行数变少）', folded < before, `${before} → ${folded}`)
      await bracket.click()
      await page.waitForTimeout(200)
      const back = await rowsOf(page, 'data').count()
      check('再点一次展开回来', back === before, `${folded} → ${back} vs ${before}`)
    }

    // 「展开层数」拿哪一块验：脚本挑演示步时优先挑**数据快照是嵌套的**那条（见上面找运行那一段），
    // 但库里也有平的一层对象；真碰上平的就在那一侧 SKIP，别硬凑一个断言出来
    const which = hasNested(parsed) ? 'data' : (hasNested(parsedInput) ? 'input' : null)
    if (!which) {
      skip('「展开层数三档」那一段（两份快照都是平的，切层数没有区别）')
    } else {
      const seg = page.locator('.hc-dseg button')
      await seg.filter({ hasText: '1 层' }).click()
      await page.waitForTimeout(200)
      const one = await rowsOf(page, which).count()
      await seg.filter({ hasText: '全部' }).click()
      await page.waitForTimeout(200)
      const all = await rowsOf(page, which).count()
      check('「展开」三档真的在改默认展开深度（全部 > 1 层）', all > one,
        `${which} 侧：1 层 ${one} vs 全部 ${all}`)
      await seg.filter({ hasText: '2 层' }).click()
      await page.waitForTimeout(200)
    }
    await page.screenshot({ path: path.join(shots, 'history-132-expand.png') })
  }

  // ── 三、搜索键路径（命中数由脚本自己算一遍当裁判） ──
  {
    const parsed = JSON.parse(stepFull.dataSnapshot)
    // 挑一个**至少命中一处**的片段：取叶子路径的最后一段
    const leaves = []
    const walk = (node, p) => {
      if (Array.isArray(node)) node.forEach((item, i) => walk(item, `${p}[${i}]`))
      else if (node && typeof node === 'object') {
        for (const [k, item] of Object.entries(node)) walk(item, p ? `${p}.${k}` : k)
      } else leaves.push(p)
    }
    walk(parsed, '')
    const deepest = leaves.slice().sort((a, b) => b.length - a.length)[0] || ''
    const fragment = (deepest.split(/[.[]/).filter(Boolean).pop() || '').trim()
    if (!fragment) {
      skip('搜索那一段（这份快照里挑不出一个能搜的键名）')
    } else {
      const expected = countHits(parsed, fragment)
      const before = await rowsOf(page, 'data').count()
      const q = page.locator('.hc-sq')
      await q.fill(fragment)
      await page.waitForTimeout(300)
      const after = await rowsOf(page, 'data').count()
      const note = await searchNoteOf(page, 'data').innerText()
      check(`搜索「${fragment}」后树上只剩命中分支（行数不多于搜索前，且没被搜没）`,
        after > 0 && after <= before, `${before} → ${after}`)
      check('命中数那句话与脚本自己按路径算的一致',
        note.includes(`命中 ${expected} 处键路径`), `${JSON.stringify(note)} vs 期望 ${expected} 处`)
      check('那句话写清了「命中分支已自动展开」（决策 10：不做行内高亮，改成过滤 + 展开）',
        note.includes('命中分支已自动展开'), note)
      await page.screenshot({ path: path.join(shots, 'history-132-search.png') })

      await q.fill('')
      await page.waitForTimeout(300)
      const restored = await rowsOf(page, 'data').count()
      check('清空搜索恢复原状（行数回到搜索前）', restored === before, `${after} → ${restored} vs ${before}`)
      check('清空后搜索那句话也不留', await searchNoteOf(page, 'data').count() === 0,
        String(await searchNoteOf(page, 'data').count()))

      await q.fill('zzz-没有这个键')
      await page.waitForTimeout(300)
      check('搜不到时给的是另一句（不是「命中 0 处」就完了）',
        (await searchNoteOf(page, 'data').innerText()).includes('没有匹配的键路径'),
        await searchNoteOf(page, 'data').innerText())
      await q.fill('')
      await page.waitForTimeout(250)
    }
  }

  // ── 四、原始文本 + 复制 ──
  {
    const parsed = JSON.parse(stepFull.dataSnapshot)
    const rawBtn = page.locator('.hc-sctools .hc-sbtn').filter({ hasText: '原始文本' })
    await rawBtn.click()
    await page.waitForTimeout(250)
    const rawText = await rawOf(page, 'data').innerText()
    check('「原始文本」给的是格式化后的 JSON（与接口那份逐字一致）',
      rawText.replace(/\s+$/, '') === JSON.stringify(parsed, null, 2), rawText.slice(0, 120))
    await rawBtn.click()
    await page.waitForTimeout(250)

    check('数据快照那块有「复制 JSON」按钮（文本档才写「复制原文」）',
      (await copyBtnOf(page, 'data').innerText()).trim() === '复制 JSON',
      await copyBtnOf(page, 'data').innerText())
    await copyBtnOf(page, 'data').click()
    await page.waitForTimeout(250)
    // Windows 的剪贴板会把 `\n` 归一成 `\r\n`（Chromium 在写的时候就转了）——
    // 比的是同一份 JSON，差别只在这个平台的换行口径
    const clip = (await page.evaluate(() => navigator.clipboard.readText())).replace(/\r\n/g, '\n')
    check('复制进剪贴板的是这份快照的格式化 JSON',
      clip === JSON.stringify(parsed, null, 2), JSON.stringify(clip.slice(0, 120)))
  }

  // ── 五、三种降级（拦接口造数据；票面要求真机至少两种，这里三种都验） ──
  if (order.length < 2) {
    skip('三种降级那一段（这条消息只有一步，换不动步就取不到造过数据的那一份）')
  } else {
    const other = (si + 1) % order.length

    // ① 非 JSON 文本 + ② 空对象：先把补丁清掉切到「别的步」（把上一次取的那一份顶掉），
    //    再装上补丁切回来——同一步不重复取，所以必须换一次步才会重新请求
    patch = null
    await clickNode(page, other)
    patch = { input: '这一段不是 JSON：{"FCode": 打住', data: '{}' }
    await clickNode(page, si)

    const textRaw = await rawOf(page, 'input').innerText()
    check('降级①非 JSON 文本：**原样显示**（没被当成 JSON 解析）',
      textRaw === patch.input, JSON.stringify(textRaw))
    const textNote = await degradeNoteOf(page, 'input').innerText()
    check('降级①注明它不是 JSON 文本、以及后果（回填只认 JSON 值）',
      textNote.includes('非 JSON 文本') && textNote.includes('进不了上下文'), textNote)
    check('降级①那一块铺的是「复制原文」而不是「复制 JSON」（写 JSON 是撒谎）',
      (await copyBtnOf(page, 'input').innerText()).trim() === '复制原文',
      await copyBtnOf(page, 'input').innerText())

    const emptyNote = await emptyNoteOf(page, 'data').innerText()
    check('降级②空对象：给出明确提示（不是一片空白）',
      emptyNote.includes('空对象 {}') && emptyNote.includes('一个字段都没有'), emptyNote)
    check('降级②提示之外树照画（`{}` 也在，不是只留一句话）',
      await rowsOf(page, 'data').count() > 0, String(await rowsOf(page, 'data').count()))

    // 搜索框是两块共用的：非 JSON 那一档也得回一句，不然敲了字界面一动不动
    await page.locator('.hc-sq').fill('note')
    await page.waitForTimeout(300)
    check('非 JSON 文本那一块对搜索也有回话（照实说没得搜）',
      (await searchNoteOf(page, 'input').innerText()).includes('没有键路径可搜'),
      await searchNoteOf(page, 'input').innerText())
    await page.locator('.hc-sq').fill('')
    await page.waitForTimeout(250)
    await page.screenshot({ path: path.join(shots, 'history-132-degrade-text-empty.png') })

    // ③ 超长：> 64 KB（列上限，见 #127）
    patch = null
    await clickNode(page, other)
    patch = { input: 'x'.repeat(65536), data: '{"codes":[]}' }
    await clickNode(page, si)
    const hugeRaw = await rawOf(page, 'input').innerText()
    const hugeNote = await degradeNoteOf(page, 'input').innerText()
    check('降级③超长：只画前 8 KB（页面因此不会卡死）', hugeRaw.length === 8192, String(hugeRaw.length))
    check('降级③注明共多少字符、截到多少',
      hugeNote.includes('超长快照') && hugeNote.includes('共 65536 字符') && hugeNote.includes('只画前 8192 字符'),
      hugeNote)
    await page.screenshot({ path: path.join(shots, 'history-132-degrade-huge.png') })

    // 页面还活着：把补丁撤掉再切一步，面板照常换过去
    patch = null
    await clickNode(page, other)
    check('降级③之后页面仍然能用（再切一步，面板照常换回来）',
      await page.locator('.hc-snaps-wrap .hc-errbox').count() === 0
        && await page.locator('.hc-snap').count() === 2,
      String(await page.locator('.hc-snap').count()))

    // ④ 长值：一行先截到 72 字，右边那个按钮把整段摊开（票面「长值截断可查」），单格也能复制
    const longValue = 'A'.repeat(200)
    patch = null
    await clickNode(page, other)
    patch = { input: '{}', data: JSON.stringify({ note: longValue }) }
    await clickNode(page, si)
    const longRow = page.locator('.hc-snap[data-od-id="snapshot-data"] .hc-jlong')
    check('长值先截断（不是把两百字都铺在一行里）',
      await longRow.count() === 1 && !(await longRow.innerText()).includes(longValue),
      `${await longRow.count()} 行长值`)
    const more = longRow.locator('.hc-jmore')
    check('长值那一格给了「展开 N 字」的出口', (await more.innerText()).includes('展开 200 字'),
      await more.innerText())
    await more.click()
    await page.waitForTimeout(200)
    check('点开之后整段都在（长值可查）', (await longRow.innerText()).includes(longValue),
      (await longRow.innerText()).slice(0, 60))
    // 叶子那一格的「复制值」（平时透明，hover 那一行才浮出来）：复制的是**没截断**的原值
    const valueBtn = page.locator('.hc-snap[data-od-id="snapshot-data"] .hc-jact').first()
    await valueBtn.click()
    await page.waitForTimeout(250)
    const valueClip = (await page.evaluate(() => navigator.clipboard.readText())).replace(/\r\n/g, '\n')
    check('一格上的「复制值」复制的是原值（没被截断那半段）',
      valueClip === JSON.stringify(longValue, null, 2), JSON.stringify(valueClip.slice(0, 40)))
    patch = null
    await clickNode(page, other)
  }

  // ── 六、同一步不重复取 ──
  {
    const nodeIndex = order.indexOf(step)
    if (si !== 0) await clickNode(page, 0)
    await clickNode(page, nodeIndex)
    const settled = stepReqs.length
    await chainNodesOnScreen(page).nth(nodeIndex).click()
    await page.waitForTimeout(600)
    check('同一步再点一次不再发请求（一次点击最多一次请求，不是按行补拉）',
      stepReqs.length === settled, `${settled} → ${stepReqs.length}：${stepReqUrls().slice(-3).join(' ')}`)
    check('这条路上取的是整行（`/history/steps/{id}`），索引那条路只管步骤链',
      stepReqUrls().every(u => u.includes('/history/steps/')), stepReqUrls().slice(-3).join(' '))
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
