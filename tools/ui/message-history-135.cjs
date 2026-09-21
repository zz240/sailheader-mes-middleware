/*
 * #135 真机 UI 验收：消息历史页的**断点重投**（弹窗、二次确认、Esc、双刷、失败不停在 pending）。
 *
 * 票面（#128 子票 / ADR-0016 决策 11、12）：
 *   只有 FAILED 的消息步能触发入口（其余状态不可点）；弹窗写清「哪条消息的哪一步」
 *   （消息 id / 业务键 / 步骤号 / 动作 / 状态）；二次确认、进行中按钮 pending 且不可连点、Esc 关闭；
 *   成功后原步 `REDELIVERED` 且**列表与详情都刷新**；接口失败时给出错误、按钮恢复可点。
 *
 * 这一票要跑的是一条**真链路**，所以脚本自己把场景搭出来（不挑库里现成的失败步——那些任务的
 * 依赖在本地多半是坏的，重投只会再失败一次，验不了「成功后原步 REDELIVERED」那一侧）：
 *
 *   ① 起一个**可控目标端**（本地 HTTP 桩，端口由内核给）：默认 500，`/__control?failing=0` 一拨就 200
 *      ——这就是「修好依赖」那个动作（与 `MessageHistoryRedeliverFailureTest` 里那个本地 mock 同义）；
 *   ② 建一个数据源（HTTP → 那个桩）与一个任务：`READ_SQL`（MySQL `mes_db.mes_work_order`）
 *      → `HTTP_CALL`（打向那个桩）；
 *   ③ 跑一次：桩在 500 上 → 第 2 步 FAILED（第 1 步是源动作，**它是唯一不可重投的步**，正好当反例）；
 *   ④ 在界面上走完整条路：入口只在 FAILED 上出现 → 弹窗内容与列表那一行逐格对上 → Esc 关掉且
 *      **一个请求都没发** → 确认（桩还是 500）→ 弹窗里给出错误、按钮恢复可点、原步仍 FAILED
 *      → 把桩拨回 200 再确认 → 原步 `REDELIVERED`，且左侧列表与右侧详情同时是刷新后的内容。
 *
 * 两处挑场景的理由（都踩过）：
 *   - **首动作必须是源动作（READ_SQL / MQ_CONSUME）**：别的首动作根本不记消息步——
 *     `RouteGenerator.buildPollingEntry` 只 `beginRun`、不 `beginMessage`，而 `recordXxx` 见 messageId
 *     为空直接返回。实测任务 146（SH-A4PUSH，FIELD_MAP 打头）跑 FAILED 也只有 run、**零消息步**，
 *     拿它当场景连失败步都没有。（这条缺口记在 `docs/flows/reliability-flow.md` 的建议表里、
 *     状态「待评估」——不属本票、本次未修。）
 *   - **只取一行、且避开三个 datetime 列**：多行时「步骤 2」在列表里有好几行，按步骤号找行会挑错
 *     那一条（第一版就是这么错的）；而 `FPlanStart` 那几个 `LocalDateTime` 会让消息步的快照序列化
 *     当场报 `Java 8 date/time type … not supported`，失败步的错误文本被那件事顶掉。
 *
 * 跑法（playwright 是本目录自带的，先 `npm i` 一次就行）：
 *   cd tools/ui && node message-history-135.cjs
 * 跑之前前端 dev server（5173）与后端（8080）都要在，**后端要能连上 MySQL**（3306，那条 READ_SQL
 * 读的就是它）。跑完会删掉自己建的任务与数据源（历史里的 run / 消息步留着当证据）。
 */
const fs = require('fs')
const http = require('http')
const path = require('path')
const { chromium } = require('playwright')

const APP = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080/api'
/** READ_SQL 的来源数据源：`sh-mes-db`（MySQL mes_db，就是网关自己那台 3306；库里 `mes_work_order` 有 3 行）。 */
const MYSQL_DS = 9
const shots = path.join(__dirname, 'shots')
const failures = []
let browser = null
let stub = null
let jobId = null
let dsId = null

function check(name, cond, detail = '') {
  console.log(`${cond ? 'ok  ' : 'NOT '} - ${name}${cond ? '' : ` :: ${detail}`}`)
  if (!cond) failures.push(name)
}

/* ── 发出去的请求（双刷的证据就靠它） ────────────────────────────────── */

const log = []
const posts = () => log.filter(r => r.method === 'POST' && /\/history\/steps\/\d+\/redeliver$/.test(r.url))
const since = at => log.filter(r => r.at > at)

/* ── 可控目标端 ─────────────────────────────────────────────────────── */

/** 本地 HTTP 桩：默认 500（依赖坏着），`/__control?failing=0` 之后回 200（依赖修好了）。 */
function startStub() {
  return new Promise((resolve, reject) => {
    const state = { failing: true, hits: 0 }
    const server = http.createServer((req, res) => {
      const url = new URL(req.url, 'http://127.0.0.1')
      let body = ''
      req.on('data', chunk => { body += chunk })
      req.on('end', () => {
        if (url.pathname === '/__control') {
          state.failing = url.searchParams.get('failing') !== '0'
          res.writeHead(200, { 'content-type': 'application/json' })
          return res.end(JSON.stringify({ failing: state.failing, hits: state.hits }))
        }
        state.hits += 1
        res.writeHead(state.failing ? 500 : 200, { 'content-type': 'application/json' })
        res.end(JSON.stringify(state.failing
          ? { code: 'INJECTED', message: '目标端临时不可用（#135 真机脚本注入）' }
          : { ok: true, received: body.length }))
      })
    })
    server.on('error', reject)
    server.listen(0, '127.0.0.1', () => resolve({ server, state, port: server.address().port }))
  })
}

/** 拨开关：`true` = 目标端坏着（回 500）。回的是桩自己的计数（用来断言这一投真的到了目标端）。 */
async function setStub(failing) {
  const res = await fetch(`http://127.0.0.1:${stub.port}/__control?failing=${failing ? 1 : 0}`)
  return await res.json()
}

/* ── 接口 ───────────────────────────────────────────────────────────── */

async function api(method, url, body) {
  const res = await fetch(`${API}${url}`, {
    method,
    headers: body === undefined ? undefined : { 'content-type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(60000),
  })
  const text = await res.text()
  if (!res.ok) throw new Error(`${method} ${url} → ${res.status} ${text.slice(0, 200)}`)
  const envelope = text ? JSON.parse(text) : null
  return envelope && 'data' in envelope ? envelope.data : envelope
}

/* ── 浏览器侧的小工具（与 #129/#130/#131/#136 同一套） ──────────────── */

async function settle(page, ms = 4000) {
  const start = log.length
  const deadline = Date.now() + ms
  while (Date.now() < deadline) {
    await page.waitForTimeout(120)
    if (log.length > start) { await page.waitForTimeout(350); return }
  }
}

async function waitForList(page) {
  await page.waitForSelector('.hc-ritem, .hc-list-empty, .hc-errbox', { timeout: 20000 })
}

/** 右栏的消息步取完（链与卡都在那之后才谈得上）。 */
async function waitForDetail(page) {
  await page.waitForSelector('.hc-mchip, .hc-dbody .hc-list-empty, .hc-dbody .hc-errbox', { timeout: 30000 })
}

/**
 * 挑一个 el-select 上的选项。
 *
 * 点的是 **el-select 那层壳**（`.hc-filter-status` / `.hc-filter-job`）：那一格上的 `id` 落在内层
 * `input` 上，而它被占位符那层 `<span>` 盖着，点它会一直被拦（第一版实测 30s 超时）。
 * 下拉是 teleport 到 body 的，所以要按**可见**的那一个找。
 */
async function pickOption(page, selectSelector, matcher) {
  await page.locator(selectSelector).click()
  const option = page.locator('.el-select-dropdown:visible .el-select-dropdown__item')
    .filter({ hasText: matcher }).first()
  await option.click()
  await settle(page)
  await waitForList(page)
}

async function ensureStepsMode(page) {
  const steps = page.locator('.hc-seg .el-radio-button').filter({ hasText: '消息步检索' }).first()
  const active = await steps.evaluate(el => el.classList.contains('is-active')).catch(() => false)
  if (!active) {
    await steps.click()
    await settle(page)
    await waitForList(page)
  }
}

/** 消息步列表里那一行（按 `#运行 · 步骤 n` + 业务键找，不按下标——刷新之后行的次序会变）。 */
const stepRowOnScreen = (page, runId, stepNo, businessKey) => {
  let row = page.locator('.hc-ritem').filter({ hasText: `#${runId} · 步骤 ${stepNo}` })
  if (businessKey) row = row.filter({ hasText: businessKey })
  return row.first()
}

const dialogOnScreen = page => page.locator('[data-od-id="redeliver-dialog"]')
const confirmOnScreen = page => page.locator('[data-od-id="btn-confirm-redeliver"]')
const redeliverBtnOnScreen = page => page.locator('[data-od-id="btn-redeliver"]')

/** 弹窗里那张事实表：`[[标签, 值], …]`。 */
async function dialogFacts(page) {
  return await dialogOnScreen(page).locator('.hc-rd-frow').evaluateAll(rows =>
    rows.map(row => [row.querySelector('dt').innerText.trim(), row.querySelector('dd').innerText.trim()]))
}

const factOf = (facts, label) => (facts.find(([l]) => l === label) || [])[1]

/* ── 主流程 ─────────────────────────────────────────────────────────── */

async function main() {
  // ── ① 可控目标端 ──
  stub = await startStub()
  console.log(`可控目标端: http://127.0.0.1:${stub.port}（默认 500）`)

  // ── ② 数据源 + 任务（READ_SQL → HTTP_CALL） ──
  const stamp = Date.now()
  dsId = (await api('POST', '/data-sources', {
    siteId: 1, name: `__ui135-stub-${stamp}`, type: 'HTTP',
    address: `http://127.0.0.1:${stub.port}`, addition: {},
  })).id
  jobId = (await api('POST', '/jobs', {
    siteId: 1, name: `__ui135-redeliver-${stamp}`, direction: 'NORTH', scheduleType: 'NONE', status: 0,
  })).id
  await api('POST', `/jobs/${jobId}/actions`, {
    name: 'read-work-order', type: 'READ_SQL', index: 1,
    // 只取一行、且**避开三个 datetime 列**：一条消息的场景最干净（多行时「步骤 2」在列表里
    // 有好几行，脚本按步骤号找行会挑错那一条）；而 `FPlanStart` 那几个 `LocalDateTime` 会让
    // 消息步的快照序列化当场报 `Java 8 date/time type … not supported`，把那一步真正的错误顶掉。
    addition: {
      dataSourceId: MYSQL_DS,
      sql: 'select FInterID, FBillNo, FItemNumber, FQty, FStatus from mes_work_order order by FInterID limit 1',
      businessKey: 'FBillNo',
    },
  })
  await api('POST', `/jobs/${jobId}/actions`, {
    name: 'push-report', type: 'HTTP_CALL', index: 2,
    addition: { dataSourceId: dsId, path: '/hook', method: 'POST' },
  })
  await api('PUT', `/jobs/${jobId}/status`, { status: 1 })
  console.log(`任务: ${jobId}（数据源 ${dsId}）`)

  // ── ③ 跑一次：桩在 500 上 → 第 2 步 FAILED ──
  await api('POST', `/jobs/${jobId}/execute`, {})
  const run = (await api('GET', `/history/runs?jobId=${jobId}&page=1&size=1`)).records[0]
  const runSteps = run ? (await api('GET', `/history/runs/${run.id}/steps?page=1&size=50`)).records : []
  const failedSteps = runSteps.filter(s => s.status === 'FAILED')
  const failedStep = failedSteps[0]
  const sourceStep = runSteps.find(s => s.step === 1)
  if (!run || !failedStep || !sourceStep) {
    check('自己的场景搭出来了（run + 一条 FAILED 步 + 一条源动作步）', false,
      `run=${run && run.id} status=${run && run.status} steps=${JSON.stringify(runSteps.map(s => [s.step, s.status]))}`)
    return
  }
  console.log(`run ${run.id} · 失败步 id=${failedStep.id} 步骤 ${failedStep.step} ${failedStep.actionName}`
    + ` · 业务键 ${failedStep.businessKey}`)
  check('场景就绪：新运行 FAILED、一条消息、第 2 步 FAILED 而第 1 步（源动作）SUCCESS',
    run.status === 'FAILED' && failedSteps.length === 1 && failedStep.step === 2
      && sourceStep.status === 'SUCCESS',
    `run=${run.status} steps=${JSON.stringify(runSteps.map(s => [s.step, s.status]))}`)

  // ── 浏览器 ──
  browser = await chromium.launch()
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  const pageErrors = []
  page.on('pageerror', e => pageErrors.push(e.message))
  page.on('request', req => log.push({ method: req.method(), url: req.url(), at: Date.now() }))
  // 确认那一下故意慢 700ms：既看得见 pending，也测得着「连点第二下不会再发一个 POST」
  await page.route('**/history/steps/*/redeliver', async route => {
    await new Promise(resolve => setTimeout(resolve, 700))
    await route.continue()
  })

  await page.goto(`${APP}/history`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.hc-page', { timeout: 20000 })
  await waitForList(page)
  fs.mkdirSync(shots, { recursive: true })

  // 消息步检索 + 全部状态 + 只看我们这个任务：失败步与成功步并排看得见（两个反例都在同一屏）
  await ensureStepsMode(page)
  await pickOption(page, '.hc-filter-status', '全部状态')
  await pickOption(page, '.hc-filter-job', new RegExp(`^#${jobId} `))

  // ── 一、入口只在 FAILED 上出现（票面第一条验收项） ──
  const failedRow = stepRowOnScreen(page, run.id, failedStep.step, failedStep.businessKey)
  await failedRow.waitFor({ timeout: 20000 })
  const rowText = (await failedRow.innerText()).replace(/\s+/g, ' ')
  check('失败步在列表里（这一行就是弹窗要对上的那一行）', rowText.includes('FAILED'), rowText)
  // 先把成功的那一步点开：它**没有**入口，只有一句「为什么不能投」
  const sourceRow = stepRowOnScreen(page, run.id, sourceStep.step, sourceStep.businessKey)
  await sourceRow.click()
  await waitForDetail(page)
  check('SUCCESS 步上不铺重投入口（只有 FAILED 能投）',
    await redeliverBtnOnScreen(page).count() === 0,
    String(await redeliverBtnOnScreen(page).count()))
  const blockedNote = await page.locator('.hc-card .hc-foot-hint').innerText()
  check('SUCCESS 步那一格说清「只有 FAILED 能断点续跑」',
    blockedNote.includes('不可重投') && blockedNote.includes('FAILED'), blockedNote)

  // ── 二、FAILED 步：入口、弹窗内容 ──
  await failedRow.click()
  await waitForDetail(page)
  const cardHead = (await page.locator('.hc-card .hc-sc-head').innerText()).replace(/\s+/g, ' ')
  check('右栏切到失败步（步骤号 / 动作 / 状态都对）',
    cardHead.includes(`步骤 ${failedStep.step}`) && cardHead.includes(failedStep.actionName)
      && cardHead.includes('FAILED'), cardHead)

  const entry = redeliverBtnOnScreen(page)
  check('FAILED 步上有重投入口', await entry.count() === 1, String(await entry.count()))
  check('入口上的字写清从哪一步投',
    (await entry.innerText()).trim() === `从步骤 ${failedStep.step} 断点重投`, await entry.innerText())
  await page.screenshot({ path: path.join(shots, 'history-135-entry.png') })

  await entry.click()
  const dialog = dialogOnScreen(page)
  await dialog.waitFor({ timeout: 10000 })
  const facts = await dialogFacts(page)
  check('弹窗写清消息 id（与接口里那一条一致）',
    factOf(facts, '消息 id') === failedStep.messageId, `${factOf(facts, '消息 id')} vs ${failedStep.messageId}`)
  check('弹窗写清步骤号与动作（与列表里那一行一致）',
    factOf(facts, '步骤') === String(failedStep.step)
      && rowText.includes(`步骤 ${failedStep.step}`)
      && factOf(facts, '动作') === failedStep.actionName
      && rowText.includes(failedStep.actionName),
    JSON.stringify(facts) + ' :: ' + rowText)
  check('弹窗写清状态与业务键（与列表里那一行一致）',
    factOf(facts, '状态') === 'FAILED'
      && factOf(facts, '业务键') === (failedStep.businessKey || '无业务键')
      && rowText.includes(failedStep.businessKey || '无业务键'),
    JSON.stringify(facts) + ' :: ' + rowText)
  check('弹窗写清是哪一条运行', factOf(facts, '运行') === `#${run.id}`, String(factOf(facts, '运行')))
  await page.screenshot({ path: path.join(shots, 'history-135-dialog.png') })

  // ── 三、Esc 关闭，且**一个重投请求都没发** ──
  await page.keyboard.press('Escape')
  await dialog.waitFor({ state: 'hidden', timeout: 5000 })
  check('Esc 能关掉弹窗', !await dialog.isVisible())
  check('Esc 关弹窗不会触发重投（此刻一个 POST 都没有）', posts().length === 0, String(posts().length))

  // ── 四、确认（依赖还没修好）：错误铺出来、按钮恢复可点、原步仍 FAILED ──
  await entry.click()
  await dialog.waitFor({ timeout: 10000 })
  const confirm = confirmOnScreen(page)
  const beforeFail = posts().length
  await confirm.click()
  // 请求在路上（那一下被上面那条 route 拖慢 700ms）：按钮 pending、不可连点
  let pendingSeen = true
  await page.locator('[data-od-id="btn-confirm-redeliver"]:has-text("重投中…")')
    .waitFor({ timeout: 5000 }).catch(() => { pendingSeen = false })
  check('进行中确认按钮写「重投中…」', pendingSeen)
  check('进行中确认按钮不可点（票面：不可连点）', await confirm.isDisabled(),
    String(await confirm.isDisabled()))
  await confirm.click({ force: true, timeout: 1500 }).catch(() => { /* disabled，点不动是应该的 */ })
  await page.locator('[data-od-id="redeliver-error"]').waitFor({ timeout: 20000 })
  check('连点第二下没有再发一个 POST', posts().length === beforeFail + 1,
    `${posts().length} vs ${beforeFail + 1}`)

  const errText = (await page.locator('[data-od-id="redeliver-error"]').innerText()).replace(/\s+/g, ' ')
  check('接口失败时界面给出错误（原样带上接口那句原因）',
    errText.includes('重投') && errText.includes('500'), errText)
  check('失败那句说清「原步仍是 FAILED」', errText.includes('原步仍是 FAILED'), errText)
  check('失败之后按钮恢复可点（界面不停在 pending）',
    !await confirm.isDisabled() && (await confirm.innerText()).trim() === '确认重投',
    `${await confirm.isDisabled()} / ${await confirm.innerText()}`)
  const failedStill = await api('GET', `/history/steps/${failedStep.id}`)
  check('失败的重投不改原步状态（#108：没跑通就不标 REDELIVERED）',
    failedStill.status === 'FAILED', failedStill.status)
  await page.screenshot({ path: path.join(shots, 'history-135-failed.png') })

  // ── 五、修好依赖再投：原步 REDELIVERED + 双刷（票面验收项三） ──
  const hitsBefore = (await setStub(false)).hits
  const beforeOk = posts().length
  await confirm.click()
  await dialog.waitFor({ state: 'hidden', timeout: 30000 })
  check('这一次真的发出去了（第二个 POST）', posts().length === beforeOk + 1,
    `${posts().length} vs ${beforeOk + 1}`)
  const okAt = posts()[posts().length - 1].at

  // 双刷：列表与详情都在那之后重新取过（外加按 id 重取的整行）
  await page.waitForTimeout(600)
  const after = since(okAt)
  check('成功后**左侧列表**重新取过（`/history/steps?…`）',
    after.some(r => r.url.includes('/history/steps?')), after.map(r => r.url).join(' | '))
  check('成功后**右侧详情**重新取过（`/history/runs/{id}/steps`）',
    after.some(r => r.url.includes(`/history/runs/${run.id}/steps`)), after.map(r => r.url).join(' | '))
  check('同一步的整行也重取了一次（同一步的行 id 没变，去重键不会响）',
    after.some(r => r.url.includes(`/history/steps/${failedStep.id}`)), after.map(r => r.url).join(' | '))

  const afterRow = stepRowOnScreen(page, run.id, failedStep.step, failedStep.businessKey)
  await afterRow.waitFor({ timeout: 20000 })
  check('左侧列表那一行现在是 REDELIVERED（列表真的是刷新后的内容）',
    (await afterRow.innerText()).includes('REDELIVERED'), await afterRow.innerText())
  check('右侧详情那一步也是 REDELIVERED（详情真的是刷新后的内容）',
    (await page.locator('.hc-card .hc-sc-head').innerText()).includes('REDELIVERED'),
    await page.locator('.hc-card .hc-sc-head').innerText())
  check('原步在右栏仍是当前那一步（刷新没把选中态甩到别的消息上去）',
    (await page.locator('.hc-card .hc-sc-head').innerText()).includes(`步骤 ${failedStep.step}`),
    await page.locator('.hc-card .hc-sc-head').innerText())

  const afterApi = await api('GET', `/history/steps/${failedStep.id}`)
  check('接口里原步也确实是 REDELIVERED', afterApi.status === 'REDELIVERED', afterApi.status)
  const hitsAfter = (await setStub(false)).hits
  check('这一投真的打到了目标端（不是界面上画出来的成功）', hitsAfter > hitsBefore,
    `${hitsAfter} vs ${hitsBefore}`)
  const newestRun = (await api('GET', `/history/runs?jobId=${jobId}&page=1&size=1`)).records[0]
  check('重投产生了一条新的 SUCCESS 运行（原运行仍是那次失败）',
    newestRun.id > run.id && newestRun.status === 'SUCCESS', `${newestRun.id} ${newestRun.status}`)
  const originalRun = await api('GET', `/history/runs/${run.id}`)
  check('原运行的状态没被改写（REDELIVERED 记在消息步上，不记在运行上）',
    originalRun.status === 'FAILED', originalRun.status)
  await page.screenshot({ path: path.join(shots, 'history-135-redelivered.png') })

  // ── 六、REDELIVERED 那一步不再是可投的入口 ──
  check('REDELIVERED 步上不再铺入口（它不是「再投一次」）',
    await redeliverBtnOnScreen(page).count() === 0, String(await redeliverBtnOnScreen(page).count()))
  const doneNote = await page.locator('.hc-card .hc-foot-hint').innerText()
  check('REDELIVERED 那一格补一句「首跑报错已由重投修复」',
    doneNote.includes('REDELIVERED') && doneNote.includes('已由重投修复'), doneNote)

  // ── 七、**默认档**（消息步检索默认只看 FAILED）下的双刷 ──
  // 上面那一段把状态调成了「全部状态」才好把弹窗与列表那一行逐格对上；可用户默认看到的是
  // **只看 FAILED** 那一档：重投成功后原步不再是 FAILED，**那一行从列表里消失就是刷新后的内容**，
  // 而票面要的「原步仍显示 REDELIVERED」落在右栏（选中态回钉那一处正是为它写的）。
  // 这一段走一遍默认档：再跑一次任务造一个新失败步，不做任何筛选直接投。
  {
    await setStub(true)
    await api('POST', `/jobs/${jobId}/execute`, {})
    const run2 = (await api('GET', `/history/runs?jobId=${jobId}&page=1&size=1`)).records[0]
    const run2Steps = run2 ? (await api('GET', `/history/runs/${run2.id}/steps?page=1&size=50`)).records : []
    const failed2 = run2Steps.find(s => s.status === 'FAILED')
    if (!failed2) {
      check('第二次执行也造出了失败步（默认档那一段跑得起来）', false,
        `run=${run2 && run2.id} steps=${JSON.stringify(run2Steps.map(s => [s.step, s.status]))}`)
    } else {
      // 回到**真正的默认口径**：重开一次页面（进消息步检索时状态那格是「没选」= 只看 FAILED，
      // #136 把这句话写在占位上），不靠去点控件上那个清空叉——那是拿实现细节当判据
      await page.goto(`${APP}/history`, { waitUntil: 'networkidle' })
      await page.waitForSelector('.hc-page', { timeout: 20000 })
      await waitForList(page)
      await ensureStepsMode(page)
      await pickOption(page, '.hc-filter-job', new RegExp(`^#${jobId} `))
      check('状态那格是默认口径（占位上写着「默认 FAILED」）',
        (await page.locator('.hc-filter-status').innerText()).includes('默认 FAILED'),
        await page.locator('.hc-filter-status').innerText())

      const row2 = stepRowOnScreen(page, run2.id, failed2.step, failed2.businessKey)
      await row2.waitFor({ timeout: 20000 })
      await row2.click()
      await waitForDetail(page)
      const entry2 = redeliverBtnOnScreen(page)
      check('默认档下失败步同样有入口', await entry2.count() === 1, String(await entry2.count()))
      await entry2.click()
      await dialogOnScreen(page).waitFor({ timeout: 10000 })
      await setStub(false)
      const beforeOk2 = posts().length
      await confirmOnScreen(page).click()
      await dialogOnScreen(page).waitFor({ state: 'hidden', timeout: 30000 })
      check('默认档下这一投也发出去了', posts().length === beforeOk2 + 1,
        `${posts().length} vs ${beforeOk2 + 1}`)
      const ok2At = posts()[posts().length - 1].at
      await page.waitForTimeout(600)
      const after2 = since(ok2At)
      check('默认档下列表也重新取过（那一趟带的是默认的 status=FAILED）',
        after2.some(r => r.url.includes('/history/steps?') && r.url.includes('status=FAILED')),
        after2.map(r => r.url).join(' | '))
      check('默认档下那一行从「只看 FAILED」的列表里消失了（这就是刷新后的内容）',
        await stepRowOnScreen(page, run2.id, failed2.step, failed2.businessKey).count() === 0,
        await row2.innerText().catch(() => '(行已不在)'))
      check('默认档下右栏仍钉在原步上并显示 REDELIVERED（选中态回钉那一处）',
        (await page.locator('.hc-card .hc-sc-head').innerText()).includes('REDELIVERED'),
        await page.locator('.hc-card .hc-sc-head').innerText())
      const api2 = await api('GET', `/history/steps/${failed2.id}`)
      check('默认档下接口里原步也确实是 REDELIVERED', api2.status === 'REDELIVERED', api2.status)
      await page.screenshot({ path: path.join(shots, 'history-135-default-filter.png') })
    }
  }

  check('整段跑完没有页面级 JS 报错', pageErrors.length === 0, pageErrors.join(' | '))
}

;(async () => {
  try {
    await main()
  } catch (e) {
    failures.push(`脚本异常: ${e.message}`)
    console.log(`NOT - 脚本异常 :: ${e.stack}`)
  } finally {
    if (browser) await browser.close().catch(() => {})
    // 自己搭的场景自己收：任务与数据源删掉（历史里的 run / 消息步留着，那是证据）
    if (jobId) await api('DELETE', `/jobs/${jobId}`).then(() => console.log(`清理: 任务 ${jobId} 已删`)).catch(() => {})
    if (dsId) await api('DELETE', `/data-sources/${dsId}`).then(() => console.log(`清理: 数据源 ${dsId} 已删`)).catch(() => {})
    if (stub) stub.server.close()
    console.log(`\n${failures.length ? `${failures.length} 项没过` : '全部通过'}`)
    if (failures.length) process.exitCode = 1
  }
})()
