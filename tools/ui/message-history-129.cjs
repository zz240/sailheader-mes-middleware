/*
 * #129 真机 UI 验收：消息历史页的双栏骨架与筛选（第 1 张票，也就是那条**第一条纵向切片**）。
 *
 * 对着 docs/adr/0016-message-history-console.md 与 frontend/prototype/04-message-history.html：
 *   一、页内不滚动、两栏各自滚（1440×900 下不比别的页面多出页面级滚动）；
 *   二、任务 / 状态两个下拉即时生效、业务键靠查询（或回车）、重置回到初始；
 *   三、改筛选回到第 1 页、分页能翻页与改每页条数；
 *   四、加载中 / 无数据 / 筛选无结果 / 接口出错并重试，四种状态都看得到；
 *   五、选中一条运行，右栏是那条运行的头与错误块（消息与步骤链在后续票里长出来）。
 *
 * 跑法（playwright 是本目录自带的，先 `npm i` 一次就行）：
 *   cd tools/ui && node message-history-129.cjs
 * 跑之前前端 dev server（5173）与后端（8080）都要在。
 *
 * 库里一条运行都没有时，依赖数据的那几段打印 SKIP 并跳过，其余照跑（与 #111/#112 同一套口径）。
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

async function openSelect(page, index) {
  // 点组件根而不是里面那个 input：没选值时的占位层会挡住 input（element-plus 的老问题）
  const select = page.locator('.hc-filters .el-select').nth(index)
  await select.click()
  await page.waitForTimeout(250)
  if (await page.locator(OPEN_DROPDOWN).count() === 0) await select.click()
  await page.waitForSelector(`${OPEN_DROPDOWN} .el-select-dropdown__item`, { timeout: 10000 })
  await page.waitForTimeout(200)
}

/** 选一个选项（按可见文字），并把这次请求的 URL 记下来。 */
async function pickOption(page, index, label) {
  await openSelect(page, index)
  const before = requests.length
  await page.locator(OPEN_DROPDOWN).first().locator('.el-select-dropdown__item')
    .filter({ hasText: label }).first().click()
  await settle(page)
  return requests.slice(before)
}

/** 等这一次列表请求落定（或超时）。 */
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

const listCount = page => page.locator('.hc-list-panel .hc-phead .hc-ps').first().innerText()
/** 空态只认**列表**那一栏：右栏没选中时的提示用的是同一个样式类。 */
const listEmpty = page => page.locator('.hc-list-panel .hc-list-empty')
const isListEmpty = page => listEmpty(page).isVisible().catch(() => false)

let requests = []

async function main() {
  browser = await chromium.launch()
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  page.on('request', req => {
    const url = req.url()
    if (url.includes('/api/history/runs')) requests.push(url)
  })

  await page.goto(`${APP}/history`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.hc-page', { timeout: 20000 })
  await waitForList(page)

  fs.mkdirSync(shots, { recursive: true })
  await page.screenshot({ path: path.join(shots, 'history-128-wide.png') })

  // ── 一、双栏骨架：页内不滚动、两栏各自滚 ──
  // 注意：控制台全局有 `body` 默认的 8px 外边距（仓里既有问题，#129 不动 App 壳），
  // 所以这里断的是「**不比别的页面多**溢出」，不是「绝对为零」。
  const baselinePage = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  await baselinePage.goto(`${APP}/jobs`, { waitUntil: 'networkidle' })
  await baselinePage.waitForTimeout(500)
  const baselineOverflow = await baselinePage.evaluate(
    () => document.documentElement.scrollHeight - window.innerHeight)
  await baselinePage.close()

  const geom = await page.evaluate(() => {
    const main = document.querySelector('.el-main')
    const page = document.querySelector('.hc-page')
    const list = document.querySelector('.hc-list-panel')
    const detail = document.querySelector('.hc-detail-panel')
    return {
      docScroll: document.documentElement.scrollHeight - window.innerHeight,
      mainOverflow: main ? main.scrollHeight - main.clientHeight : -1,
      pageTop: page ? Math.round(page.getBoundingClientRect().top) : -1,
      pageBottom: page ? Math.round(page.getBoundingClientRect().bottom) : -1,
      pageH: page ? Math.round(page.getBoundingClientRect().height) : -1,
      viewH: window.innerHeight,
      listW: list ? Math.round(list.getBoundingClientRect().width) : -1,
      detailW: detail ? Math.round(detail.getBoundingClientRect().width) : -1,
      listScrollable: list ? getComputedStyle(list.querySelector('.hc-scroll')).overflowY : '',
    }
  })
  check('页面级滚动不比控制台基线多（8px body 外边距是既有问题，另票）',
    geom.docScroll <= baselineOverflow, `本页=${geom.docScroll} 基线(/jobs)=${baselineOverflow}`)
  check('el-main 也不滚动（高度交给本页自己管）', geom.mainOverflow <= 1, `overflow=${geom.mainOverflow}`)
  check('本页自己的盒子没有溢出视口',
    geom.pageBottom <= geom.viewH + baselineOverflow + 1 && geom.pageH > geom.viewH * 0.9,
    `top=${geom.pageTop} bottom=${geom.pageBottom} h=${geom.pageH} viewport=${geom.viewH}`)
  check('两栏并排：左栏固定 322、右栏更宽', geom.listW === 322 && geom.detailW > geom.listW,
    `list=${geom.listW} detail=${geom.detailW}`)
  check('栏内是可滚动的（滚动留在栏里）', geom.listScrollable === 'auto', geom.listScrollable)

  const rowCount = await page.locator('.hc-ritem').count()
  const totalText = await listCount(page).catch(() => '')
  if (rowCount === 0) {
    // 「0 行」有两种来由，别混：接口出错（要报 NOT）与库里真的没有（SKIP）
    if (await page.locator('.hc-errbox').isVisible().catch(() => false)) {
      check('列表取到了数据（不是错态）', false, (await page.locator('.hc-errbox').innerText()).replace(/\n/g, ' | '))
      return
    }
    skip('依赖数据的断言（库里一条运行都没有）')
    await page.screenshot({ path: path.join(shots, 'history-129-empty.png') })
    return
  }

  // 空提示不该同时出现
  check('有数据时不显示空态', !(await isListEmpty(page)))
  check('面板头写着总数', /共 \d+ 条/.test(totalText), totalText)

  // 后端是不是已经带上 #129 那一格：拿一个绝不存在的业务键探一次。
  // 旧进程会忽略未知参数（照旧返回全部运行），那几段就 SKIP——与 #111/#112 同一个口径。
  let bizSupported = false
  {
    const res = await fetch(`${API}/history/runs?businessKey=ZZZ-NO-SUCH-BUSINESS-KEY&page=1&size=20`)
    const body = await res.json().catch(() => null)
    bizSupported = !!res.ok && body && body.code === 'OK' && body.data.total === 0
  }

  // ── 二、选中一条运行，右栏是那条运行 ──
  {
    const rowText = await page.locator('.hc-ritem').first().innerText()
    check('列表行把开始与结束都写出来（同一套写法，不混粒度）',
      rowText.includes('开始') && rowText.includes('结束'), rowText.replace(/\n/g, ' | '))
  }
  const firstId = (await page.locator('.hc-ritem .hc-r-id').first().innerText()).replace('#', '').trim()
  await page.locator('.hc-ritem').first().click()
  await page.waitForTimeout(250)
  check('选中的那条写上 aria-pressed',
    await page.locator('.hc-ritem').first().getAttribute('aria-pressed') === 'true')
  const detailHead = await page.locator('.hc-detail-panel').innerText()
  check('右栏是选中那条运行的头', detailHead.includes(`运行 #${firstId}`), detailHead.slice(0, 80))
  check('右栏带上开始与结束时刻', detailHead.includes('开始') && detailHead.includes('结束'))

  // ── 三、任务下拉即时生效，并回到第 1 页 ──
  const jobOptions = await (async () => {
    await openSelect(page, 0)
    return page.locator(OPEN_DROPDOWN).first().locator('.el-select-dropdown__item')
      .evaluateAll(items => items.map(i => i.textContent.trim()))
  })()
  await page.keyboard.press('Escape')
  await page.waitForTimeout(200)
  const realJob = jobOptions.find(t => !t.includes('全部'))
  if (realJob) {
    const before = requests.length
    await pickOption(page, 0, realJob)
    const sent = requests.slice(before).join(' ')
    check('选任务后发出带 jobId 与 page=1 的请求', /jobId=\d+/.test(sent) && /page=1/.test(sent), sent)
  } else {
    skip('任务下拉只有「全部任务」')
  }

  // ── 四、状态下拉即时生效；清掉之后运行这一侧默认不过滤 ──
  {
    const before = requests.length
    await pickOption(page, 1, 'FAILED')
    const sent = requests.slice(before).join(' ')
    check('选状态后发出带 status=FAILED 的请求', /status=FAILED/.test(sent), sent)
  }
  {
    const before = requests.length
    await page.getByRole('button', { name: '重置' }).click()
    await settle(page)
    const sent = requests.slice(before).join(' ')
    check('重置之后不带任何筛选（运行这一侧默认不过滤）',
      !/jobId=/.test(sent) && !/status=/.test(sent) && !/businessKey=/.test(sent), sent)
    check('重置回到第 1 页', /page=1/.test(sent), sent)
  }

  // ── 五、业务键：敲回车 / 点查询；筛没了有专门的说法 ──
  {
    const before = requests.length
    const biz = page.getByPlaceholder('SO-2026-0917-0042')
    await biz.fill('SO-2026')
    await page.getByRole('button', { name: '查询' }).click()
    await settle(page)
    const sent = requests.slice(before).join(' ')
    check('业务键按下查询后带上 businessKey', /businessKey=SO-2026/.test(sent), sent)
    check('按下查询也回到第 1 页', /page=1/.test(sent) || sent === '', sent)
  }
  if (!bizSupported) {
    skip('「筛没了」的空态与后端的 businessKey 那一格（后端还是旧进程：忽略未知参数，重启后再跑才是真验）')
  } else {
    const before = requests.length
    const biz = page.getByPlaceholder('SO-2026-0917-0042')
    await biz.fill('ZZZ-NO-SUCH-BUSINESS-KEY')
    await biz.press('Enter')
    await settle(page)
    await waitForList(page)
    const text = isListEmpty(page) ? await listEmpty(page).innerText() : (await page.locator('.hc-errbox').innerText().catch(() => ''))
    check('筛没了写「没有符合条件的运行」', text.includes('没有符合条件的运行'), text)
    check('并且把当前筛选写出来', text.includes('业务键 ZZZ-NO-SUCH-BUSINESS-KEY'), text)
    check('这一段发出的请求确实带上了这个业务键',
      requests.slice(before).join(' ').includes('businessKey=ZZZ-NO-SUCH-BUSINESS-KEY'))
  }
  // 不管上面走哪一支，业务键这一格都要清干净，后面的分页/错态两段才是干净起点
  await page.getByRole('button', { name: '重置' }).click()
  await settle(page)
  await waitForList(page)

  // ── 六、分页：改每页条数回第 1 页；翻页带 page=2 ──
  {
    const pager = page.locator('.hc-pfoot .el-pagination')
    check('分页条在页级（322px 的左栏装不下完整的 element-plus 分页）',
      await page.locator('.hc-list-panel .hc-pfoot').count() === 0)
    if (await pager.count() === 0) {
      skip('分页（当前不足一页，分页条不铺）')
    } else {
      // 先翻到第 2 页（element-plus 的页码是 li.number，没有 button 角色）
      const page2 = pager.locator('.el-pager li.number').filter({ hasText: /^2$/ }).first()
      if (await page2.count() > 0) {
        const firstBefore = await page.locator('.hc-ritem .hc-r-id').first().innerText()
        const before = requests.length
        await page2.click()
        await settle(page)
        const sent = requests.slice(before).join(' ')
        check('翻到第 2 页发出 page=2', sent.includes('page=2'), sent)
        const firstAfter = await page.locator('.hc-ritem .hc-r-id').first().innerText()
        check('翻页之后列表内容确实换了', firstAfter !== firstBefore, `${firstBefore} -> ${firstAfter}`)
      } else {
        skip('翻页（只有一页）')
      }
      // 改每页条数：应回到第 1 页
      const sizeSel = pager.locator('.el-select').first()
      if (await sizeSel.count() > 0) {
        const before = requests.length
        await sizeSel.click()
        await page.waitForSelector(`${OPEN_DROPDOWN} .el-select-dropdown__item`, { timeout: 10000 })
        await page.locator(OPEN_DROPDOWN).first().locator('.el-select-dropdown__item')
          .filter({ hasText: '50' }).first().click()
        await settle(page)
        const sent = requests.slice(before).join(' ')
        check('改每页条数回第 1 页（size=50&page=1 或 page=1&size=50）',
          /size=50/.test(sent) && /page=1/.test(sent), sent)
      } else {
        skip('每页条数下拉')
      }
    }
  }

  // ── 七、加载中占位：把这一次请求拖慢，界面上要先是占位而不是空白 ──
  {
    await page.route('**/api/history/runs*', async route => {
      await new Promise(r => setTimeout(r, 900))
      // unroute 与这一次请求可能撞上：撞上时这个 route 已经被处理过了
      await route.continue().catch(() => {})
    })
    const inflight = page.waitForResponse(r => r.url().includes('/api/history/runs'), { timeout: 20000 })
    await page.getByRole('button', { name: '查询' }).click()
    await page.waitForTimeout(350)
    const skelVisible = await page.locator('.hc-skel').isVisible().catch(() => false)
    check('请求在路上时列表是占位（不是空白）', skelVisible)
    await inflight
    await page.unroute('**/api/history/runs*')
    await settle(page)
  }

  // ── 八、接口出错：给出错态与重试，重试后能回来 ──
  {
    await page.route('**/api/history/runs*', route => route.fulfill({
      status: 500,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'INTERNAL_ERROR', message: 'boom', data: null }),
    }))
    await page.getByRole('button', { name: '查询' }).click()
    await page.waitForSelector('.hc-errbox', { timeout: 10000 })
    const errText = await page.locator('.hc-errbox').innerText()
    check('接口出错时给出错态（不是「没有数据」）', errText.includes('运行列表没取到'), errText)
    check('错态里带上接口的说法', errText.includes('boom'), errText)
    await page.screenshot({ path: path.join(shots, 'history-128-error.png') })

    await page.unroute('**/api/history/runs*')
    await page.getByRole('button', { name: '重试' }).click()
    // 先等错态框消失：只等「有 .hc-ritem / .hc-list-empty / .hc-errbox」会被**旧**的错态框满足
    await page.waitForSelector('.hc-errbox', { state: 'detached', timeout: 15000 })
    await page.waitForSelector('.hc-ritem', { timeout: 15000 })
    check('点重试之后列表回来了', await page.locator('.hc-ritem').count() > 0)
  }

  // ── 九、直接问一次后端：业务键这一格真的接上了 ──
  if (bizSupported) {
    const res = await fetch(`${API}/history/runs?businessKey=ZZZ-NO-SUCH-BUSINESS-KEY&page=1&size=20`)
    const body = await res.json()
    check('后端认 businessKey 这一格（空结果而不是 400）',
      res.ok && body.code === 'OK' && body.data.total === 0,
      `${res.status} ${JSON.stringify(body).slice(0, 120)}`)
  } else {
    skip('#129 新接的 businessKey 那一格（后端还是旧进程）')
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
