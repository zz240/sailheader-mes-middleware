/*
 * #136 真机 UI 验收：消息历史页第二个模式——**消息步检索**。
 *
 * 对着 docs/adr/0016-message-history-console.md（决策 5）与 frontend/prototype/04-message-history.html：
 *   一、切到该模式后列表按「运行 × 消息 × 步」拍平成一条条消息步，**默认只看 FAILED**（#74），
 *       请求里带 status=FAILED，并且这句话**写在界面上**（列表头、筛选条、状态那格的占位；
 *       旧实现把它藏在 select 的 placeholder「全部状态」里）；
 *   二、状态选「全部状态」才是真的全看，选具体状态就只看那一个；
 *   三、业务键筛选在该模式下命中**消息步所属消息**的业务键，清空后恢复；
 *   四、点一行直接定位到对应消息与步骤（右栏路径、行选中态、右栏取那条运行）；
 *   五、分页与每页条数在该模式下同样有效；
 *   六、每行写全：`#运行 id · 步骤 n`、状态、动作、业务键（无则「无业务键」）、时刻、尝试次数、
 *       `任务 #id`、错误首行。
 *
 * 跑法（playwright 是本目录自带的，先 `npm i` 一次就行）：
 *   cd tools/ui && node message-history-136.cjs
 * 跑之前前端 dev server（5173）与后端（8080）都要在。
 *
 * 库里没有失败消息步时，依赖数据的那几段打印 SKIP 并跳过，其余照跑（与 #111/#112 同一套口径）；
 * 后端还是旧进程（消息步列表不带 jobId）时，「任务 #id」那一段打印 SKIP——它由
 * MessageHistoryServiceTest#listStepsCarriesRunJobIdInOneBatch 与 HistoryControllerTest 顶着，
 * 重启后端之后再跑就是真验。
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

async function openSelect(page, selector) {
  // 点组件根而不是里面那个 input：没选值时的占位层会挡住 input（element-plus 的老问题）
  const select = page.locator(selector)
  await select.click()
  await page.waitForTimeout(250)
  if (await page.locator(OPEN_DROPDOWN).count() === 0) await select.click()
  await page.waitForSelector(`${OPEN_DROPDOWN} .el-select-dropdown__item`, { timeout: 10000 })
  await page.waitForTimeout(200)
}

/** 选一个选项（按可见文字），并把这次请求的 URL 记下来。 */
async function pickOption(page, selector, label) {
  await openSelect(page, selector)
  const before = requests.length
  await page.locator(OPEN_DROPDOWN).first().locator('.el-select-dropdown__item')
    .filter({ hasText: label }).first().click()
  await settle(page)
  return requests.slice(before).map(r => r.url)
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

/** 整个模式开关里现在亮着的那一档。 */
const activeMode = page => page.locator('.hc-seg .el-radio-button.is-active').first().innerText()

const modeBtn = (page, label) => page.locator('.hc-seg .el-radio-button').filter({ hasText: label }).first()

const rows = page => page.locator('.hc-ritem')
const rowTexts = async page => (await rows(page).allInnerTexts()).map(t => t.replace(/\n/g, ' | '))

/** 只认消息步那一侧的请求（运行那一侧的请求在同一份 requests 里，用 kind 分开）。 */
const stepUrls = since => requests.filter(r => r.kind === 'steps' && r.seq >= since).map(r => r.url)
const runUrls = since => requests.filter(r => r.kind === 'runs' && r.seq >= since).map(r => r.url)

/** 列表行对应的那一页消息步（前端按 records 原序渲染，拿第一行去核对最直接）。 */
async function apiSteps(query) {
  const res = await fetch(`${API}/history/steps?${query}`)
  const body = await res.json().catch(() => null)
  return res.ok && body && body.code === 'OK' ? body.data : null
}

async function main() {
  browser = await chromium.launch()
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  page.on('request', req => {
    const url = req.url()
    const kind = url.includes('/api/history/steps') ? 'steps' : url.includes('/api/history/runs') ? 'runs' : null
    if (kind) requests.push({ url, kind, seq: requests.length })
  })
  const seq = () => requests.length

  await page.goto(`${APP}/history`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.hc-page', { timeout: 20000 })
  await waitForList(page)
  fs.mkdirSync(shots, { recursive: true })

  // 后端带上 #136 那一格（消息步列表补 jobId）没有：直接打一次接口问清楚。
  // 旧进程时下面凡是要写死「任务 #id」的断言都 SKIP（与 #111/#112 同一套口径）。
  const probe = await apiSteps('status=FAILED&page=1&size=1')
  const probeStep = probe && probe.records.length ? probe.records[0] : null
  const jobIdSupported = !!(probeStep && probeStep.jobId !== null && probeStep.jobId !== undefined)

  // ── 一、初始还在运行那一侧，且运行那一侧默认不过滤 ──
  check('模式开关有两档，初始亮着「按运行展开」',
    (await activeMode(page)) === '按运行展开', await activeMode(page))
  check('初始请求打的是 /history/runs 且不带 status',
    runUrls(0).some(u => !/status=/.test(u)) && stepUrls(0).length === 0,
    `runs=${runUrls(0).join(' ')} steps=${stepUrls(0).join(' ')}`)

  // ── 二、切到消息步检索：请求改道、默认只看 FAILED、这句话写在界面上 ──
  {
    const before = seq()
    await modeBtn(page, '消息步检索').click()
    await settle(page)
    await waitForList(page)
    const sent = stepUrls(before).join(' ')
    check('切模式后列表请求改打 /history/steps', stepUrls(before).length > 0 && sent.includes('/history/steps'), sent)
    check('切过去默认只看 FAILED（#74）', /status=FAILED/.test(sent), sent)
    check('切模式回到第 1 页', /page=1/.test(sent), sent)
    check('切模式后开关上亮的是消息步检索',
      (await activeMode(page)) === '消息步检索', await activeMode(page))
    await page.screenshot({ path: path.join(shots, 'history-136-steps.png') })

    const head = await page.locator('.hc-list-panel .hc-phead').innerText()
    check('界面上读得到「默认只看 FAILED」这个口径（列表头）',
      head.includes('默认只看 FAILED'), head.replace(/\n/g, ' | '))
    check('列表头这一侧标题换成「消息步」', /消息步/.test(head), head.replace(/\n/g, ' | '))
    const note = await page.locator('.hc-filters .hc-note').innerText()
    check('筛选条右端也写着当前口径', note.includes('消息步检索') && note.includes('默认只看 FAILED'), note)
    const statusText = await page.locator('.hc-filter-status').innerText()
    check('状态那格的占位说的是真话（不是「全部状态」）',
      statusText.includes('默认 FAILED') && !statusText.includes('全部状态'), statusText)

    // 筛选条上多了一件控件（模式开关）与一句话：横向溢出的第一现场就在这里
    const geom = await page.evaluate(() => {
      const bar = document.querySelector('.hc-filters')
      return {
        docOverflowX: document.documentElement.scrollWidth - window.innerWidth,
        barOverflowX: bar.scrollWidth - bar.clientWidth,
      }
    })
    check('加了模式开关之后页面不横向溢出', geom.docOverflowX <= 0, JSON.stringify(geom))
    check('筛选条自己也不横向溢出', geom.barOverflowX <= 0, JSON.stringify(geom))
  }

  // ── 三、行写全了没有；没有失败消息步就 SKIP 掉依赖数据的那几段 ──
  let haveRows = false
  {
    const rowCount = await rows(page).count()
    if (rowCount === 0) {
      if (await page.locator('.hc-errbox').isVisible().catch(() => false)) {
        check('消息步列表取到了数据（不是错态）', false,
          (await page.locator('.hc-errbox').innerText()).replace(/\n/g, ' | '))
        return
      }
      skip('依赖数据的断言（库里一条 FAILED 消息步都没有）')
      await page.screenshot({ path: path.join(shots, 'history-136-empty.png') })
    } else {
      haveRows = true
      const first = (await rows(page).first().innerText()).replace(/\n/g, ' | ')
      check('每行头是「#运行 id · 步骤 n」', /#[0-9]+ · 步骤 [0-9]+/.test(first), first)
      check('每行带时刻（同一套写法：YYYY-MM-DD HH:MM:SS）',
        /\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/.test(first), first)
      check('每行带尝试次数', /尝试 \d+/.test(first), first)
      if (jobIdSupported) {
        check('每行带任务 id', /任务 #\d+/.test(first), first)
      } else {
        // 旧后端给不了这一格：界面至少要如实写「任务 —」，而不是 #null 或编一个号
        check('后端还不带 jobId 时这一格写「任务 —」（不写 #null）', first.includes('任务 —'), first)
        skip('「每行带任务 #id」（后端还是旧进程：消息步列表不带 jobId，重启后再跑才是真验）')
      }
      const bk = await page.locator('.hc-r-bk').first().innerText()
      check('每行带业务键那一格（没有就写「无业务键」）', bk.trim().length > 0, bk)

      const chips = await page.locator('.hc-ritem .hc-st').allInnerTexts()
      check('默认口径下每一行都是 FAILED（没有别的状态混进来）',
        chips.length > 0 && chips.every(c => c.trim() === 'FAILED'), chips.join(','))
    }
  }

  // ── 四、状态：全部状态才全看，选具体状态只看那一个 ──
  if (haveRows) {
    const sent = await pickOption(page, '.hc-filter-status', '全部状态')
    check('选「全部状态」之后不再带 status', sent.length > 0 && !sent.join(' ').includes('status='), sent.join(' '))
    const note = await page.locator('.hc-filters .hc-note').innerText()
    check('口径那句话跟着改成「全部状态」', note.includes('全部状态'), note)
    const chips = (await page.locator('.hc-ritem .hc-st').allInnerTexts()).map(c => c.trim())
    if (chips.some(c => c !== 'FAILED')) {
      check('「全部状态」下真的能看到非 FAILED 的步',
        chips.includes('SUCCESS') || chips.includes('REDELIVERED') || chips.includes('RUNNING'), chips.join(','))
    } else {
      skip('「全部状态」下出现非 FAILED 的步（库里只有 FAILED 的步）')
    }

    const sent2 = await pickOption(page, '.hc-filter-status', 'FAILED')
    check('选具体状态就只看那一个', sent2.join(' ').includes('status=FAILED'), sent2.join(' '))
    const chips2 = (await page.locator('.hc-ritem .hc-st').allInnerTexts()).map(c => c.trim())
    check('选了 FAILED 之后列表里只有 FAILED',
      chips2.length > 0 && chips2.every(c => c === 'FAILED'), chips2.join(','))
  }

  // ── 五、业务键筛选命中消息步，清空后恢复 ──
  if (haveRows) {
    // 回到干净起点：重置**按原型回到默认那一侧（运行）**并清掉筛选，再切回消息步
    {
      const before = seq()
      await page.getByRole('button', { name: '重置' }).click()
      await settle(page)
      await waitForList(page)
      check('重置回到默认那一侧（运行）', (await activeMode(page)) === '按运行展开', await activeMode(page))
      const sent = runUrls(before).join(' ')
      check('重置之后运行那一侧不带任何筛选（它本来默认就不过滤）',
        !/jobId=/.test(sent) && !/status=/.test(sent) && !/businessKey=/.test(sent), sent)
    }
    await modeBtn(page, '消息步检索').click()
    await settle(page)
    await waitForList(page)
    const chips = (await page.locator('.hc-ritem .hc-st').allInnerTexts()).map(c => c.trim())
    check('再切回消息步检索仍是该模式的默认口径（FAILED）',
      chips.length > 0 && chips.every(c => c === 'FAILED'), chips.join(','))

    const bizKey = (await page.locator('.hc-r-bk').first().innerText()).trim()
    if (bizKey && bizKey !== '无业务键') {
      const before = seq()
      const biz = page.getByPlaceholder('SO-2026-0917-0042')
      await biz.fill(bizKey)
      await biz.press('Enter')
      await settle(page)
      await waitForList(page)
      const sent = stepUrls(before).join(' ')
      check('业务键敲回车带上 businessKey 打给消息步那一侧',
        sent.includes(`businessKey=${encodeURIComponent(bizKey)}`), sent)
      const hit = (await page.locator('.hc-r-bk').allInnerTexts()).map(t => t.trim())
      check('命中的每一行业务键都包含这个词',
        hit.length > 0 && hit.every(t => t.includes(bizKey)), hit.join(','))

      const beforeClear = seq()
      await biz.fill('')
      await biz.press('Enter')
      await settle(page)
      await waitForList(page)
      const cleared = stepUrls(beforeClear).join(' ')
      check('清空之后不再带 businessKey（而且列表回来了）',
        !cleared.includes('businessKey=') && (await rows(page).count()) > 0, cleared)

      // 筛没了：消息步这一侧的空态说法要带上状态口径（不然看起来像「筛得太严」）
      const biz2 = page.getByPlaceholder('SO-2026-0917-0042')
      await biz2.fill('ZZZ-NO-SUCH-BUSINESS-KEY')
      await biz2.press('Enter')
      await settle(page)
      await waitForList(page)
      const emptyText = await page.locator('.hc-list-panel .hc-list-empty').innerText()
      check('消息步这一侧「筛没了」有自己的说法，并把默认口径一起写出来',
        emptyText.includes('没有符合条件的消息步') && emptyText.includes('默认只看 FAILED'),
        emptyText.replace(/\n/g, ' | '))
      const beforeRestore = seq()
      await biz2.fill('')
      await biz2.press('Enter')
      await settle(page)
      await waitForList(page)
      const restored = stepUrls(beforeRestore).join(' ')
      check('再清空一次仍能恢复（清空不是「只灵一次」）',
        !restored.includes('businessKey=') && (await rows(page).count()) > 0, restored)
    } else {
      skip('业务键筛选那一段（库里失败步都没有业务键）')
    }
  }

  // ── 六、点一行直接定位到对应消息与步骤 ──
  if (haveRows) {
    const pageData = await apiSteps('status=FAILED&page=1&size=20')
    const target = pageData && pageData.records.length ? pageData.records[0] : null
    if (!target) {
      skip('点行进详情那一段（接口这一页没给记录）')
    } else {
      const before = seq()
      const firstRow = rows(page).first()
      const rowHead = await firstRow.locator('.hc-r-id').innerText()
      await firstRow.click()
      await settle(page)
      await page.waitForTimeout(400)

      check('点中的那一行写上 aria-pressed',
        await firstRow.getAttribute('aria-pressed') === 'true')
      check('同一时刻只有一行是选中的',
        await page.locator('.hc-ritem[aria-pressed="true"]').count() === 1,
        String(await page.locator('.hc-ritem[aria-pressed="true"]').count()))
      check('行头的「#运行 · 步骤」与接口这一条对得上',
        rowHead.replace(/\s/g, '') === `#${target.runId}·步骤${target.step}`, `${rowHead} vs #${target.runId} · 步骤 ${target.step}`)

      const sent = runUrls(before).join(' ')
      check('右栏按需取了这一步所属的运行', sent.includes(`/api/history/runs/${target.runId}`), sent)
      const panel = await page.locator('.hc-detail-panel').innerText()
      check('右栏是那一条运行的头', panel.includes(`运行 #${target.runId}`), panel.replace(/\n/g, ' | ').slice(0, 120))
      const detailPathText = await page.locator('.hc-detail-panel .hc-phead .hc-ps').innerText()
      const short = target.messageId.length > 10 ? `${target.messageId.slice(0, 8)}…` : target.messageId
      check('右栏路径写清运行 / 消息 / 步骤（定位到对应消息与步骤）',
        detailPathText.includes(`#${target.runId}`) && detailPathText.includes(short)
          && detailPathText.includes(`步骤 ${target.step}`),
        `${detailPathText} vs #${target.runId} · 消息 ${short} · 步骤 ${target.step}`)
      await page.screenshot({ path: path.join(shots, 'history-136-selected.png') })
    }
  }

  // ── 七、分页与每页条数在这一模式下同样有效 ──
  {
    const pager = page.locator('.hc-pfoot .el-pagination')
    if (await pager.count() === 0) {
      skip('分页（当前不足一页，分页条不铺）')
    } else {
      const page2 = pager.locator('.el-pager li.number').filter({ hasText: /^2$/ }).first()
      if (await page2.count() > 0) {
        const firstBefore = await rows(page).first().innerText()
        const before = seq()
        await page2.click()
        await settle(page)
        const sent = stepUrls(before).join(' ')
        check('消息步这一侧翻到第 2 页发出 page=2', sent.includes('page=2'), sent)
        const firstAfter = await rows(page).first().innerText()
        check('翻页之后列表内容确实换了', firstAfter !== firstBefore)
      } else {
        skip('翻页（只有一页）')
      }
      const sizeSel = pager.locator('.el-select').first()
      if (await sizeSel.count() > 0) {
        const before = seq()
        await sizeSel.click()
        await page.waitForSelector(`${OPEN_DROPDOWN} .el-select-dropdown__item`, { timeout: 10000 })
        await page.locator(OPEN_DROPDOWN).first().locator('.el-select-dropdown__item')
          .filter({ hasText: '50' }).first().click()
        await settle(page)
        const sent = stepUrls(before).join(' ')
        check('改每页条数回第 1 页且仍打消息步那一侧',
          /size=50/.test(sent) && /page=1/.test(sent) && sent.includes('/history/steps'), sent)
      } else {
        skip('每页条数下拉')
      }
    }
  }

  // ── 八、每行的「任务 #id」：后端要按页把 jobId 补上（旧进程时 SKIP） ──
  {
    if (!probeStep) {
      skip('「任务 #id」那一段（接口没给记录）')
    } else if (!jobIdSupported) {
      skip('「任务 #id」（后端还是旧进程：见 MessageHistoryServiceTest#listStepsCarriesRunJobIdInOneBatch）')
    } else {
      check('后端在消息步列表里补上了所属运行的 jobId（一行不用再补拉运行）',
        typeof probeStep.jobId === 'number', JSON.stringify(probeStep).slice(0, 160))
      // 回到干净起点：重置把人送回**运行**那一侧（原型口径），得再切回消息步才看得到这一步的行
      await page.getByRole('button', { name: '重置' }).click()
      await settle(page)
      await waitForList(page)
      await modeBtn(page, '消息步检索').click()
      await settle(page)
      await waitForList(page)
      const first = await rows(page).first().innerText()
      check('列表第一行写的任务号与接口这条一致',
        first.includes(`任务 #${probeStep.jobId}`), `${first.replace(/\n/g, ' | ')} vs 任务 #${probeStep.jobId}`)
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
