/*
 * #137 真机 UI 验收：窄屏可用与整期收尾。
 *
 * 对着 docs/adr/0016-message-history-console.md（决策 3 / 4 / 11、风险④）与
 * frontend/prototype/04-message-history.html（原型窄屏那一段 `@media (max-width: 920px)`）：
 *
 *   一、**九档视口**（360 / 390 / 430 / 600 / 820 / 1024 / 1366 / 1440 / 1920）：
 *       每档都截一张本页的图（`shots/137-<宽>.png`）与一张**原型**同档的图
 *       （`shots/137-proto-<宽>.png`）——**纯像素那一遍留给人眼**（两份图就摆在那儿），
 *       脚本判的是能机器判的那些：
 *       ①**页面级不横向滚动**（含 `el-main` 那一层）；
 *       ②**窄屏单栏堆叠**（列表在上、详情在下、两栏同左同宽）；
 *       ③**1366 / 1920 仍是第 1 张票的双栏骨架**（列表在左、322px 固定、两栏同顶）；
 *       ④**与原型逐档同一批决定**：工作区两栏 / 单栏、快照并排 / 竖排、步骤链横排 / 竖排
 *       （同一个量法问两份 DOM，`LAYOUT_FACTS`）。
 *
 *   二、**对照行在窄屏只保留新值**：切到「与上一步对照」那一档，窄屏下上一步那一格与箭头
 *       收起来、本步那一格照旧看得见、状态芯片还在（原型 920px 断点里那句
 *       `.drow .dval.old, .drow .darrow { display:none }`，但**不照抄**它把新值挤进 46px
 *       那一列的写法——见 ADR-0016 的 #137 补记）。
 *
 *   三、**页面级那条多余滚动条消失**（ADR-0016 风险④ / #129 实测）：`tokens.css` 的 `body`
 *       自带浏览器默认的 8px 外边距，四个页面实测都是 `htmlScrollH = innerH + 16`。
 *       归零之后四个页面（`/history` `/jobs` `/data-sources` `/templates`）在 1366×900 上
 *       都是 `documentElement.scrollHeight == innerHeight`。
 *
 * 跑法（playwright 是本目录自带的，先 `npm i` 一次就行）：
 *   cd tools/ui && node message-history-137.cjs
 * 跑之前前端 dev server（5173）与后端（8080）都要在。
 *
 * 第三段的**输入是脚本自己造的**（拦 `/history/steps/{id}` 把两份快照换掉，与 #132 / #133 同一套
 * 做法）：这一步要的是「有对照行可看」，靠库里现成数据得看运气。九档视口那一段**不依赖数据**，
 * 永远照跑；真要连「一条消息带两步以上」的运行都没有，第三段才整段 SKIP。
 */
const fs = require('fs')
const path = require('path')
const { chromium } = require('playwright')

const APP = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080/api'
const PROTOTYPE = 'file:///' + path
  .resolve(__dirname, '../../frontend/prototype/04-message-history.html')
  .replace(/\\/g, '/')
const shots = path.join(__dirname, 'shots')
const failures = []
let browser = null

/** 九档视口（票面点名的那九个宽度）。 */
const VIEWPORTS = [360, 390, 430, 600, 820, 1024, 1366, 1440, 1920]
/**
 * 「窄屏」= 该单栏堆叠的那几档。票面点名的是 2–3 档（360 / 390 / 430，那三档也是「不横向滚动」的
 * 验收口径）；600 / 820 两档控制台壳还带着侧栏（760px 以下才折到顶上），把「**有侧栏的窄屏**」
 * 也钉住——那两档正是「侧栏 + 竖排」这个中间形态唯一出现的地方。
 */
const NARROW = [360, 390, 430, 600, 820]
/** 四个页面都要没有页面级滚动条（ADR-0016 风险④）。 */
const PAGES = ['/history', '/jobs', '/data-sources', '/templates']
const DESKTOP = { width: 1366, height: 900 }

function check(name, cond, detail = '') {
  console.log(`${cond ? 'ok  ' : 'NOT '} - ${name}${cond ? '' : ` :: ${detail}`}`)
  if (!cond) failures.push(name)
}

const skip = name => console.log(`SKIP - ${name}`)

/** 后端把数据包在 `{ code:'OK', data }` 里（与 #133 那份 apiGet 同一个口径）。 */
async function apiGet(url) {
  try {
    const res = await fetch(`${API}${url}`, { signal: AbortSignal.timeout(30000) })
    const body = await res.json().catch(() => null)
    return res.ok && body && body.code === 'OK' ? body.data : null
  } catch {
    return null
  }
}

/** 列表铺好（接口回来、骨架撤掉）。 */
async function waitForList(page) {
  await page.waitForFunction(() => !document.querySelector('.hc-list-panel .hc-skel'), null, { timeout: 20000 })
    .catch(() => {})
  await page.waitForTimeout(250)
}

/** 右栏那条运行的头铺出来了（消息步还在路上也够用）。 */
async function waitForDetail(page) {
  await page.waitForSelector('.hc-dbody, .hc-list-empty, .hc-errbox', { timeout: 20000 }).catch(() => {})
  await page.waitForTimeout(300)
}

async function settle(page) {
  await page.waitForTimeout(450)
}

function viewBtn(page, label) {
  return page.locator('[aria-label="快照视图"] button').filter({ hasText: label }).first()
}

/** 一条运行的**消息步索引**按消息分组（索引行上就有两个「快照落没落」的布尔）。 */
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

/** 取整误差的允许量：凡「不超出 / 同宽」的断言都用它（同一把尺子，别一处 +1 一处裸比）。 */
const SLACK = 1

/**
 * 页面级那三样（body 外边距那一票要的）：文档高 / 宽、视口高 / 宽、`body` 的计算外边距。
 * 纵向那条要**严格相等**——`htmlScrollH = innerH + 16` 正是「多出一条页面级滚动条」的样子。
 */
const PAGE_FACTS = () => ({
  scrollH: document.documentElement.scrollHeight,
  scrollW: document.documentElement.scrollWidth,
  innerH: window.innerHeight,
  innerW: window.innerWidth,
  bodyMargin: getComputedStyle(document.body).margin,
})

/**
 * 页面里那几层「谁在横滚」的量法（页面级 / 控制台壳 / `el-main` / 本页根 / 工作区 / 两栏）。
 * `aside` 与 `layoutDir` 是**壳**那一格：壳折没折、折了之后导航条有没有吃满整宽。这一格非有不可
 * ——只看「页面级不横滚」抓不出「导航条还是 200px 宽」（列向布局里 `el-main` 照样吃满宽，
 * App.vue 那条注释里写着这处假绿是怎么来的）。
 */
const OVERFLOW_PROBE = () => {
  const box = sel => {
    const el = document.querySelector(sel)
    if (!el) return null
    const r = el.getBoundingClientRect()
    return {
      x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height),
      scrollW: el.scrollWidth, clientW: el.clientWidth,
    }
  }
  return {
    innerW: window.innerWidth,
    htmlScrollW: document.documentElement.scrollWidth,
    layoutDir: getComputedStyle(document.querySelector('.layout')).flexDirection,
    aside: box('.el-aside'),
    main: box('.el-main'),
    page: box('.hc-page'),
    workspace: box('.hc-workspace'),
    list: box('.hc-list-panel'),
    detail: box('.hc-detail-panel'),
  }
}

/**
 * 与原型逐档比对用的**同一个量法**（两份 DOM 共用这一份定义，不各写一遍）：
 * 工作区是两栏还是单栏 / 快照两块是并排还是竖排 / 步骤链是横排还是竖排。
 * 本页的类名带 `hc-` 前缀，原型没有——所以每一格都是「先问本页那个选择器，没有再问原型那个」。
 */
const LAYOUT_FACTS = () => {
  const dir = sel => {
    const el = document.querySelector(sel)
    return el ? getComputedStyle(el).flexDirection : null
  }
  const cols = sel => {
    const el = document.querySelector(sel)
    if (!el) return null
    const v = getComputedStyle(el).gridTemplateColumns
    return v ? v.trim().split(/\s+/).length : 0
  }
  return {
    workspaceDir: dir('.hc-workspace') ?? dir('.workspace'),
    snapsCols: cols('.hc-snaps') ?? cols('.snaps'),
    chainDir: dir('.hc-chain') ?? dir('.chain'),
  }
}

async function main() {
  browser = await chromium.launch()
  const page = await browser.newPage({ viewport: DESKTOP })
  fs.mkdirSync(shots, { recursive: true })

  // ── 一、页面级那条多余的滚动条（body 的 8px 外边距归零） ──────────────────────
  {
    const measured = []
    for (const routePath of PAGES) {
      await page.setViewportSize(DESKTOP)
      await page.goto(`${APP}${routePath}`, { waitUntil: 'networkidle' })
      await page.waitForTimeout(400)
      const m = await page.evaluate(PAGE_FACTS)
      measured.push({ routePath, ...m })
      check(`${routePath} 没有页面级纵向滚动条（htmlScrollH == innerH）`,
        m.scrollH === m.innerH, `htmlScrollH=${m.scrollH} innerH=${m.innerH}`)
      check(`${routePath} 没有页面级横向滚动条（htmlScrollW <= innerW）`,
        m.scrollW <= m.innerW + SLACK, `htmlScrollW=${m.scrollW} innerW=${m.innerW}`)
    }
    check('body 的默认 8px 外边距归零了（四个页面同一份量法）',
      measured.every(m => m.bodyMargin === '0px'),
      measured.map(m => `${m.routePath}:${m.bodyMargin}`).join(' '))
  }

  // 壳折到顶上（`App.vue` 的 760px）是**全站**的，所以另外三个页面也要在 360 上量一遍：
  // 「页面级不横滚」+「导航条吃满整宽」。本页自己不在这里量——九档那一段逐档钉得更细（还有堆叠）。
  for (const routePath of PAGES.filter(p => p !== '/history')) {
    await page.setViewportSize({ width: 360, height: 900 })
    await page.goto(`${APP}${routePath}`, { waitUntil: 'networkidle' })
    await page.waitForTimeout(400)
    const s = await page.evaluate(OVERFLOW_PROBE)
    check(`${routePath} 在 360 上页面级不横滚`, s.htmlScrollW <= s.innerW + SLACK,
      `scrollW=${s.htmlScrollW} innerW=${s.innerW}`)
    check(`${routePath} 在 360 上导航条吃满整宽（壳真的折到顶上了）`,
      !!s.aside && s.aside.x === 0 && Math.abs(s.aside.w - s.innerW) <= SLACK,
      JSON.stringify(s.aside))
  }

  // ── 二、九档视口：截图 + 不横滚 + 堆叠/双栏 ────────────────────────────────
  const proto = await browser.newPage({ viewport: DESKTOP })
  for (const width of VIEWPORTS) {
    const viewport = { width, height: 900 }
    await page.setViewportSize(viewport)
    await page.goto(`${APP}/history`, { waitUntil: 'networkidle' })
    await page.waitForSelector('.hc-page', { timeout: 20000 })
    await waitForList(page)
    // 先点一条运行把右栏铺出来：原型那份 fixture 一进来就有选中的运行（快照两块与步骤链都在），
    // 本页不点就只有一句「选一条运行看它的消息与消息步」——那样截图与结构都比不出东西。
    const firstRow = page.locator('.hc-ritem').first()
    if (await firstRow.count()) {
      await firstRow.click()
      await waitForDetail(page)
    }
    const m = await page.evaluate(OVERFLOW_PROBE)

    await page.screenshot({ path: path.join(shots, `137-${width}.png`), fullPage: width <= 820 })
    await proto.setViewportSize(viewport)
    await proto.goto(PROTOTYPE, { waitUntil: 'load' })
    await proto.waitForTimeout(400)
    await proto.screenshot({ path: path.join(shots, `137-proto-${width}.png`), fullPage: width <= 820 })

    // ── 与原型逐档比对（结构那一层）：原型没有控制台侧栏，所以不比像素，比**决定**——
    //    工作区是两栏还是单栏、快照两块是并排还是竖排、步骤链是横排还是竖排。
    //    纯像素那一遍留给人眼（九档截图都在 `shots/` 里摆着）。
    const [appFacts, protoFacts] = await Promise.all([
      page.evaluate(LAYOUT_FACTS),
      proto.evaluate(LAYOUT_FACTS),
    ])
    const sameRow = f => f.workspaceDir === 'row'
    check(`${width}：工作区「两栏 / 单栏」与原型同一个决定`,
      appFacts.workspaceDir !== null && protoFacts.workspaceDir !== null
      && sameRow(appFacts) === sameRow(protoFacts),
      `app=${appFacts.workspaceDir} proto=${protoFacts.workspaceDir}`)
    check(`${width}：两块快照「并排 / 竖排」与原型同一个决定`,
      appFacts.snapsCols !== null && protoFacts.snapsCols !== null
      && (appFacts.snapsCols > 1) === (protoFacts.snapsCols > 1),
      `app=${appFacts.snapsCols} proto=${protoFacts.snapsCols}`)
    check(`${width}：步骤链「横排 / 竖排」与原型同一个决定`,
      appFacts.chainDir !== null && protoFacts.chainDir !== null
      && (appFacts.chainDir === 'row') === (protoFacts.chainDir === 'row'),
      `app=${appFacts.chainDir} proto=${protoFacts.chainDir}`)

    check(`${width}：页面不横向滚动（documentElement）`,
      m.htmlScrollW <= m.innerW + SLACK, `scrollW=${m.htmlScrollW} innerW=${m.innerW}`)
    check(`${width}：el-main 那一层不横向滚动`,
      !m.main || m.main.scrollW <= m.main.clientW + SLACK,
      m.main ? `scrollW=${m.main.scrollW} clientW=${m.main.clientW}` : '没量到 .el-main')
    check(`${width}：本页根不横向滚动`,
      !m.page || m.page.scrollW <= m.page.clientW + SLACK,
      m.page ? `scrollW=${m.page.scrollW} clientW=${m.page.clientW}` : '没量到 .hc-page')

    // 控制台壳那一格：≤760 折到顶上（导航条吃满整宽、主区在它下面），>760 仍是「侧栏在左、主区在右」。
    // 少了这一条，「导航条还是 200px 宽但页面级不横滚」那种假绿就混得过去。
    if (width <= 760) {
      check(`${width}：窄屏壳折到顶上——导航条吃满整宽、主区在它下面`,
        !!m.aside && !!m.main && m.layoutDir === 'column' && m.aside.x === 0
        && Math.abs(m.aside.w - m.innerW) <= SLACK
        && m.aside.y + m.aside.h <= m.main.y + SLACK,
        JSON.stringify({ layoutDir: m.layoutDir, aside: m.aside, main: m.main }))
    } else {
      check(`${width}：宽屏壳仍是「侧栏在左（200px）、主区在右」`,
        !!m.aside && !!m.main && m.layoutDir === 'row' && m.aside.x === 0
        && Math.abs(m.aside.w - 200) <= SLACK
        && m.aside.x + m.aside.w <= m.main.x + SLACK,
        JSON.stringify({ layoutDir: m.layoutDir, aside: m.aside, main: m.main }))
    }

    if (NARROW.includes(width)) {
      const stacked = m.list && m.detail
        && Math.abs(m.list.x - m.detail.x) <= SLACK
        && Math.abs(m.list.w - m.detail.w) <= SLACK
        && m.list.y + m.list.h <= m.detail.y + SLACK
      check(`${width}：窄屏单栏堆叠——列表在上、详情在下、两栏同左同宽`, !!stacked,
        JSON.stringify({ list: m.list, detail: m.detail }))
      check(`${width}：堆叠顺序是 页头 → 筛选条 → 列表 → 详情`,
        m.page && m.workspace && m.list && m.detail
        && m.page.y <= m.workspace.y && m.workspace.y <= m.list.y && m.list.y < m.detail.y,
        JSON.stringify({ page: m.page, workspace: m.workspace, list: m.list, detail: m.detail }))
      check(`${width}：工作区那一层不横向滚动`,
        !m.workspace || m.workspace.scrollW <= m.workspace.clientW + SLACK,
        m.workspace ? `scrollW=${m.workspace.scrollW} clientW=${m.workspace.clientW}` : '没量到 .hc-workspace')
    }
    if (width >= 1366) {
      const twoCol = m.list && m.detail
        && m.list.x + m.list.w <= m.detail.x + SLACK
        && Math.abs(m.list.y - m.detail.y) <= SLACK
      check(`${width}：双栏骨架没被响应式改回去（列表在左、两栏同顶）`, !!twoCol,
        JSON.stringify({ list: m.list, detail: m.detail }))
      check(`${width}：左侧列表仍是第 1 张票那个 322px`,
        !!m.list && Math.abs(m.list.w - 322) <= SLACK, m.list ? String(m.list.w) : '没量到')
    }
  }
  await proto.close()

  // ── 三、对照行在窄屏只保留新值 ────────────────────────────────────────────
  // 这一段的**输入**由脚本自己造：拦下 `/history/steps/{id}` 这条整行的路，把两份快照换成
  // 一定可解析、且「本步与上一步不一样」的两份（与 #132 / #133 同一套做法——造的是输入，
  // 判的是界面把行画成什么）。库里现成那几条运行多是单步的成功步（实测：前 8 条运行里
  // 一条「相邻两步都落数据快照」都没有），不造输入这一段就只能永远 SKIP。
  let run = null
  let mi = -1
  let si = -1
  {
    const firstPage = await apiGet('/history/runs?page=1&size=8')
    for (const candidate of (firstPage ? firstPage.records : [])) {
      const ix = await apiGet(`/history/runs/${candidate.id}/steps?page=1&size=500`)
      if (!ix) continue
      const grouped = groupMessages(ix.records)
      for (let m = 0; m < grouped.length && !run; m += 1) {
        if (grouped[m].steps.length >= 2) { run = candidate; mi = m; si = 1 }
      }
      if (run) break
    }
  }

  if (!run) {
    skip('对照行窄屏那一段（这条库里没有「一条消息带两步以上」的运行）')
  } else {
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.route(
      url => /\/api\/history\/steps\/\d+(\?|$)/.test(url.toString()),
      async route => {
        const res = await route.fetch()
        const body = await res.json().catch(() => null)
        const id = String(route.request().url()).match(/steps\/(\d+)/)?.[1] ?? '0'
        if (body && body.data) body.data.dataSnapshot = JSON.stringify({ narrow: `step-${id}`, kept: 1 })
        await route.fulfill({ response: res, json: body })
      },
    )

    await page.goto(`${APP}/history`, { waitUntil: 'networkidle' })
    await waitForList(page)
    const ids = (await page.locator('.hc-ritem .hc-r-id').allInnerTexts())
      .map(t => Number(String(t).replace(/[^0-9]/g, '')))
    const idx = ids.indexOf(run.id)
    if (idx < 0) {
      skip(`对照行窄屏那一段（列表第 1 页里翻不到运行 #${run.id}）`)
    } else {
      await page.locator('.hc-ritem').nth(idx).click()
      await waitForDetail(page)
      if (mi > 0) {
        await page.locator('.hc-mchip').nth(mi).click()
        await page.waitForTimeout(300)
      }
      await page.locator('.hc-chain .hc-cbtn:not(.hc-cbtn-entry)').nth(si).click()
      await page.waitForTimeout(600)
      await viewBtn(page, '与上一步对照').click()
      await page.waitForTimeout(700)

      const rows = page.locator('.hc-dgrid .hc-drow')
      if (await rows.count() === 0) {
        skip('对照行窄屏那一段（这一对上没有可对照的行）')
      } else {
        await page.setViewportSize({ width: 360, height: 900 })
        await page.waitForTimeout(500)
        await page.screenshot({ path: path.join(shots, '137-360-diff.png'), fullPage: true })

        const row = rows.first()
        const oldVisible = await row.locator('.hc-dval-old').first().isVisible().catch(() => false)
        const arrowVisible = await row.locator('.hc-darrow').first().isVisible().catch(() => false)
        const newVisible = await row.locator('.hc-dval-new').first().isVisible().catch(() => false)
        const chipVisible = await row.locator('.hc-dchip').first().isVisible().catch(() => false)
        const oldText = await row.locator('.hc-dval-old').first().innerText().catch(() => '')
        const newText = await row.locator('.hc-dval-new').first().innerText().catch(() => '')

        check('窄屏对照行：上一步那一格收起来了', !oldVisible)
        check('窄屏对照行：那个箭头也收起来了', !arrowVisible)
        check('窄屏对照行：**本步那一格照旧看得见**（不是两格一起收）', newVisible)
        check('窄屏对照行：状态芯片还在（不然「改 / 增 / 减」就没法看了）', chipVisible)
        check('窄屏对照行：收起的是上一步、留下的确实是本步那一格',
          newVisible && newText !== oldText, `old=${oldText} new=${newText}`)
        const grid = await page.evaluate(() => {
          const el = document.querySelector('.hc-dgrid')
          return el ? { scrollW: el.scrollWidth, clientW: el.clientWidth } : null
        })
        check('窄屏对照块自己不横滚', !grid || grid.scrollW <= grid.clientW + SLACK,
          grid ? `scrollW=${grid.scrollW} clientW=${grid.clientW}` : '没量到 .hc-dgrid')
        // 与九档那一段同一个量法（`OVERFLOW_PROBE`），不另写一份 inline 的
        const ov = await page.evaluate(OVERFLOW_PROBE)
        check('窄屏开着对照也不横向滚动（页面 + el-main + 导航条那一格照旧）',
          ov.htmlScrollW <= ov.innerW + SLACK
          && (!ov.main || ov.main.scrollW <= ov.main.clientW + SLACK)
          && !!ov.aside && Math.abs(ov.aside.w - ov.innerW) <= SLACK,
          JSON.stringify({ htmlScrollW: ov.htmlScrollW, innerW: ov.innerW, main: ov.main, aside: ov.aside }))
      }
    }
  }

  await browser.close()
  console.log('')
  if (failures.length) {
    console.log(`${failures.length} 项没过：\n  - ${failures.join('\n  - ')}`)
    process.exit(1)
  }
  console.log('全部通过')
}

main().catch(async e => {
  console.error(e)
  if (browser) await browser.close().catch(() => {})
  process.exit(1)
})
