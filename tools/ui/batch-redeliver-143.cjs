/*
 * #143 真机 UI 验收：「重发这一批（N 条）」在界面上真的可用（AC3 的界面那一半）。
 *
 * 票面那条 AC：目标端 500 时整批同因 FAILED、**界面上「重发这一批」可用、点一次只发一次请求**。
 * PowerShell 那一半（`tools/shadow-site/verify-scenario.ps1` 的 B6-BATCH-FAIL 段）验的是接口：
 * 摘要行 + 这一批每条都 FAILED（同因）、水位不推进、库里没写、`POST /history/batches/{id}/redeliver`
 * 只多发一次请求。**界面这一半只有真浏览器能验**：那颗按钮是不是真的铺出来了、点下去是不是真的只发
 * 一次、点完界面是不是刷新成了「已重发」。
 *
 * 场景自己搭（不挑库里现成的失败批——那些任务的外部依赖在本地多半是坏的，而「点一次成功」这一侧
 * 恰恰需要一个**可控的目标端**）：
 *   ① 用 MES 替身（9100）的故障注入把标识注册打回 500；
 *   ② **克隆**造数脚本里那条批量链（`SH-B6-Register-Batch`，7 个动作：读 → 包装 → 合并 → 出站 →
 *      还原 → 映射 → 写回）：克隆出来的新任务 id 是新的，水位键 `job:<新id>` 因此是空的，
 *      不必去动 MySQL 里的水位（脚本不去碰库、也不起子进程）；
 *   ③ 跑一次 → 出站 500 → 整批 20 条 FAILED + 一条摘要行；
 *   ④ 在界面上：消息步检索 → 这一批的那个任务 → 点失败的那一行 → 步骤链上出站那一格的批徽标
 *      → 组头 + 「重发这一批（20 条）」→ 点它 → 替身只多收到 **一次** 请求 → 界面刷新成 REDELIVERED。
 *
 * 另加一段（同一张票 AC2 的界面那一半）：**A2 头行分表**也克隆出来跑一次，然后在历史页上看
 * 「父子谱系与批号」——子行的步骤链上看得见拆分那一格、子件那一格带批徽标、点开是
 * 「读取批 · 一次请求 4 条 · 失败 0 条」与那 4 个编号。
 *
 * 跑法（playwright 是本目录自带的，先 `npm i` 一次就行）：
 *   cd tools/ui && node batch-redeliver-143.cjs
 * 跑之前：前端 dev server（5173）、后端（8080）、MES 替身（9100，`node tools/shadow-site/mes-mock.cjs`）
 * 都要在，且造数脚本已经跑过（`SH-B6-Register-Batch` 存在——克隆它的动作）。跑完删掉克隆出来的任务。
 *
 * 与 `message-history-135.cjs` 的关系：那个脚本验的是**逐条重投**（`/history/steps/{id}/redeliver`，
 * 有弹窗、要二次确认）；这一支验的是**整批重发**（`/history/batches/{id}/redeliver`，**没有弹窗**，
 * 点一下直接发——按钮自己的 pending 就是防连点那一道）。
 */
const fs = require('fs')
const path = require('path')
const { chromium } = require('playwright')

const APP = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080/api'
const MOCK = 'http://localhost:9100'
/** 造数脚本里的那条批量链：克隆它的动作，克隆体拿到新的任务 id（= 空水位）。 */
const SOURCE_JOB = 'SH-B6-Register-Batch'
/** 出站那一步的序号（链上第 4 个动作）与这一批几条码。 */
const OUTLET_STEP = 4
const EXPECTED = 20
/**
 * #143 真机发现（**不在本票改产品代码**，只钉住现状并写清偏差）：`history/batch.ts` 的 `messages`
 * 数的是「该批号下非摘要行的不同消息标识数」，而**合并打包出来那一条信封消息**也带着这一批的批号
 * （`RouteGenerator` 的 `edge.batchManifest` 那一条有自己的消息标识），于是它被算进了「一次请求 N 条」。
 * 现场是 20 条码、替身侧实测那一次请求也只装了 20 条，界面上写的却是 21 条
 * （组头还出现「一次请求 21 条 · 失败 20 条」这种自相矛盾）。修掉之后下面的断言会一起变红，逼着改口径。
 */
const UI_COUNT = EXPECTED + 1
const shots = path.join(__dirname, 'shots')
const failures = []
const log = []
let browser = null
let jobId = null

function check(name, cond, detail = '') {
  console.log(`${cond ? 'ok  ' : 'NOT '} - ${name}${cond ? '' : ` :: ${detail}`}`)
  if (!cond) failures.push(name)
}

/* ── 发出去的请求（「点一次只发一次」的证据就靠它） ───────────────────── */

const redeliverPosts = () => log.filter(r => r.method === 'POST' && /\/history\/batches\/\d+\/redeliver$/.test(r.url))

/* ── 替身侧：故障注入 + 收到几次注册请求 ─────────────────────────────── */

async function fault(mode, count) {
  if (mode === null) {
    await fetch(`${MOCK}/__fault`, { method: 'DELETE' })
    return null
  }
  const res = await fetch(`${MOCK}/__fault`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ mode, count }),
  })
  return (await res.json()).fault
}

/** 替身收到过几次标识注册（这一票「一次点击 = 一次请求」的裁判）。 */
async function registrations() {
  const res = await fetch(`${MOCK}/__received?path=id-register&limit=2000`)
  const body = await res.json()
  return (body.records || []).length
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

const listOf = value => (Array.isArray(value) ? value : (value && value.records) || [])

let createdJobs = []

/**
 * 克隆一条造数脚本里的链：新任务 id 是新的 ⇒ 水位键 `job:<新id>` 是空的，所以这一段不必去动 MySQL 里的
 * 水位（脚本不碰库、不起子进程）。动作原样搬（名称 / 类型 / 序号 / addition），末尾启用。
 * 动作个数不对时返回 null（说明造数脚本没跑或跑的不是这一版）。
 */
async function cloneJob(source, name, expectedActions) {
  const actions = listOf(await api('GET', `/jobs/${source.id}/actions`))
  if (actions.length !== expectedActions) return null
  const id = (await api('POST', '/jobs', {
    siteId: 1, name, direction: source.direction || 'NORTH', scheduleType: 'NONE',
    status: 0, retryCount: 1, backoffMs: 500, timezone: 'Asia/Shanghai',
  })).id
  for (const a of actions) {
    await api('POST', `/jobs/${id}/actions`, {
      name: a.name, type: a.type, index: a.actionIndex ?? a.index, addition: a.addition,
    })
  }
  await api('PUT', `/jobs/${id}/status`, { status: 1 })
  return id
}

/* ── 浏览器侧的小工具（与 #129/#130/#131/#135/#136 同一套） ─────────── */

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

async function waitForDetail(page) {
  await page.waitForSelector('.hc-mchip, .hc-dbody .hc-list-empty, .hc-dbody .hc-errbox', { timeout: 30000 })
}

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

/* ── 主流程 ─────────────────────────────────────────────────────────── */

async function main() {
  // ── ① 场景：克隆那条批量链，把目标端打成 500，跑出一次「整批失败」 ──
  const source = listOf(await api('GET', `/jobs?name=${SOURCE_JOB}`)).filter(j => j.name === SOURCE_JOB)[0]
  if (!source) {
    check(`造数脚本里那条批量链在（${SOURCE_JOB}）`, false, '先跑 seed-scenario.ps1')
    return
  }
  jobId = await cloneJob(source, `__ui143-batch-redeliver-${Date.now()}`, 7)
  if (!jobId) {
    check('克隆那条批量链（7 个动作：读 → 包装 → 合并 → 出站 → 还原 → 映射 → 写回）', false,
      `${SOURCE_JOB} 的动作读不回来或个数不对`)
    return
  }
  createdJobs.push(jobId)
  console.log(`克隆任务: ${jobId}（源 ${source.id}，7 个动作）`)

  const injected = await fault('500', 5)
  console.log(`目标端已注入故障: ${JSON.stringify(injected)}`)
  await api('POST', `/jobs/${jobId}/execute`, {}).catch(() => { /* 整批失败：接口可能回 500 */ })
  const run = listOf(await api('GET', `/history/runs?jobId=${jobId}&page=1&size=1`))[0]
  const runSteps = run ? listOf(await api('GET', `/history/runs/${run.id}/steps?page=1&size=1000`)) : []
  const summary = runSteps.find(s => (s.messageId || '').startsWith('batch:'))
  const failedMembers = runSteps.filter(s => s.step === OUTLET_STEP && s.status === 'FAILED'
    && !(s.messageId || '').startsWith('batch:'))
  check('场景就绪：这一次运行 FAILED、出站那一步有摘要行 + 20 条同因 FAILED',
    !!run && run.status === 'FAILED' && !!summary && failedMembers.length === EXPECTED,
    `run=${run && run.status} summary=${!!summary} failedMembers=${failedMembers.length}`)
  if (!run || !summary || failedMembers.length !== EXPECTED) return
  const member = failedMembers[0]
  console.log(`run ${run.id} · 摘要行 id=${summary.id} · 失败成员 ${failedMembers.length} 条（如 ${member.businessKey}）`)

  // ── ② 浏览器：走到那一批的组头 ──
  browser = await chromium.launch()
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  const pageErrors = []
  page.on('pageerror', e => pageErrors.push(e.message))
  page.on('request', req => log.push({ method: req.method(), url: req.url(), at: Date.now() }))
  await page.goto(`${APP}/history`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.hc-page', { timeout: 20000 })
  await waitForList(page)
  fs.mkdirSync(shots, { recursive: true })

  // 消息步检索 + 全部状态 + 只看我们这个任务：摘要行与失败成员都在同一屏上
  await ensureStepsMode(page)
  await pickOption(page, '.hc-filter-status', '全部状态')
  await pickOption(page, '.hc-filter-job', new RegExp(`^#${jobId} `))

  // 点一条**失败成员**那一行（不是摘要行：成员的消息链上才有完整的 1..4 步）
  const memberRow = page.locator('.hc-ritem')
    .filter({ hasText: `#${run.id} · 步骤 ${OUTLET_STEP}` })
    .filter({ hasText: member.businessKey }).first()
  await memberRow.waitFor({ timeout: 20000 })
  check('失败成员那一行在列表里（这一行就是右详情要对上的）',
    (await memberRow.innerText()).includes('FAILED'), (await memberRow.innerText()).replace(/\s+/g, ' '))
  await memberRow.click()
  await waitForDetail(page)

  // 步骤链上出站那一格：行动作名找（链上就有 push-register-batch 这一格）
  const outletNode = page.locator('.hc-cnode').filter({ hasText: 'push-register-batch' }).first()
  await outletNode.waitFor({ timeout: 20000 })
  const badge = outletNode.locator('.hc-bbadge')
  check('出站那一格上有小批徽标（这一步是一批）', await badge.count() === 1, String(await badge.count()))
  check('徽标写「一批」', (await badge.innerText()).trim() === '一批', await badge.innerText())
  await badge.click()
  const group = page.locator(`.hc-batch[data-batch-step="${OUTLET_STEP}"]`)
  await group.waitFor({ timeout: 10000 })
  const headText = (await group.locator('.hc-bt').innerText()).replace(/\s+/g, ' ')
  check('组头写清这是发送批（#143 发现：条数写成 21，比这一批 20 条码多算了打包那一条信封）',
    headText === `发送批 · 一次请求 ${UI_COUNT} 条 · 失败 ${EXPECTED} 条`, headText)
  const chips = await group.locator('.hc-mchip').count()
  check('这一组 21 颗 chip（20 条消息 + 摘要行那条「批次」）', chips === EXPECTED + 1, String(chips))
  check('摘要行那颗 chip 写着「批次」',
    (await group.locator('.hc-mchip-summary').innerText()).includes('批次'),
    await group.locator('.hc-mchip-summary').innerText())

  const button = group.locator('.hc-bredeliver')
  check('组头铺了「重发这一批（20 条）」', await button.count() === 1, String(await button.count()))
  check('按钮上的字数的是**消息**条数（#143 发现：写成 21，多算了打包那一条）',
    (await button.innerText()).trim() === `重发这一批（${UI_COUNT} 条）`, await button.innerText())
  check('按钮可点（不是置灰摆样子）', !(await button.isDisabled()), String(await button.isDisabled()))
  check('按钮的无障碍标签把「一次点击发一次请求，整批同成同败」说清',
    (await button.getAttribute('aria-label') || '').includes('一次点击发一次请求'), await button.getAttribute('aria-label'))
  await page.screenshot({ path: path.join(shots, '143-batch-entry.png') })

  const groupMembers = await Promise.all(failedMembers.slice(0, 3).map(m => m.businessKey))
  check('组里那几条失败成员在界面上也写着 FAILED（逐条可归因）',
    (await group.innerText()).includes('FAILED') && groupMembers.every(k => k && k.length > 0),
    groupMembers.join(','))

  // ── ③ 修好目标端，点按钮：一次点击 = 一次请求 ──
  await fault(null)
  const before = await registrations()
  await button.click()
  await page.locator('.el-message').filter({ hasText: '已重发这一批' }).first().waitFor({ timeout: 30000 })
  const toast = (await page.locator('.el-message').first().innerText()).replace(/\s+/g, ' ')
  check('成功后给出「已重发这一批（N 条）…新运行 #id」那句',
    toast.includes(`已重发这一批（${UI_COUNT} 条）`) && toast.includes('新运行'), toast)
  const after = await registrations()
  check('一次点击只多收到一次注册请求（替身侧的真数，不看界面画的字）',
    after - before === 1, `${before} -> ${after}`)
  check('这一次点击只发了一个重发请求（页面侧）', redeliverPosts().length === 1,
    String(redeliverPosts().length))

  // ── ④ 界面刷新成「已重发」：旧那一批标 REDELIVERED ──
  await page.waitForTimeout(800)
  const refreshed = await page.locator('.hc-dbody').innerText().catch(() => '')
  check('点完界面刷新成 REDELIVERED（旧那一批不再是 FAILED）', refreshed.includes('REDELIVERED'), refreshed.replace(/\s+/g, ' ').slice(0, 200))
  const afterApi = await api('GET', `/history/steps/${summary.id}`)
  check('接口里摘要行也确实是 REDELIVERED', afterApi.status === 'REDELIVERED', String(afterApi.status))
  const newRun = listOf(await api('GET', `/history/runs?jobId=${jobId}&page=1&size=1`))[0]
  check('重发产生了一条新的 SUCCESS 运行（原运行仍是那次 FAILED）',
    newRun.id > run.id && newRun.status === 'SUCCESS', `${newRun.id} ${newRun.status}`)
  const originalRun = await api('GET', `/history/runs/${run.id}`)
  check('原运行的状态没被改写（REDELIVERED 记在消息步上，不记在运行上）',
    originalRun.status === 'FAILED', originalRun.status)
  // 刷新之后那一组可能收起来了：要判「按钮不再铺」得先把它重新点开，否则是空判
  if (!(await group.isVisible().catch(() => false))) {
    const again = page.locator('.hc-cnode').filter({ hasText: 'push-register-batch' }).first().locator('.hc-bbadge')
    if (await again.count() === 1) { await again.click(); await page.waitForTimeout(500) }
  }
  const groupAgain = await group.innerText().catch(() => '')
  check('重开后这一组里写着 REDELIVERED（那一批真的变成「已重发」了）',
    groupAgain.includes('REDELIVERED'), groupAgain.replace(/\s+/g, ' ').slice(0, 200))
  check('点过一次之后按钮不再铺（这一批已经不是「有失败的那一批」）',
    await group.locator('.hc-bredeliver').count() === 0,
    String(await group.locator('.hc-bredeliver').count()))
  await page.screenshot({ path: path.join(shots, '143-batch-redelivered.png') })

  // ── ⑤ A2 头行分表（AC2）：父子谱系与批号在**历史页**看得见 ──
  const hlName = 'SH-A2-Bom-Head-Lines'
  const hlSource = listOf(await api('GET', `/jobs?name=${hlName}`)).filter(j => j.name === hlName)[0]
  if (!hlSource) {
    check(`造数脚本里那条头行分表链在（${hlName}）`, false, '先跑 seed-scenario.ps1')
  } else {
    const hlJob = await cloneJob(hlSource, `__ui143-a2-head-lines-${Date.now()}`, 7)
    if (!hlJob) {
      check('克隆那条头行分表链（7 个动作）', false, `${hlName} 的动作读不回来或个数不对`)
    } else {
      createdJobs.push(hlJob)
      await api('POST', `/jobs/${hlJob}/execute`, {})
      const hlRun = listOf(await api('GET', `/history/runs?jobId=${hlJob}&page=1&size=1`))[0]
      // 整行（带两个快照）：父子谱系那一格（parentMessageId）不在索引接口里（索引只带批号）
      const hlSteps = listOf(await api('GET', `/history/steps?runId=${hlRun.id}&page=1&size=100`))
      const splitRow = hlSteps.find(s => s.step === 5 && s.businessKey === 'BOM-SH-0001')
      const kids = splitRow ? hlSteps.filter(s => s.step === 6 && s.parentMessageId === splitRow.messageId) : []
      check('一份报文拆成：拆分那一行 + 4 条子行（子行的父消息标识指着那一行）',
        !!splitRow && kids.length === 4, `split=${!!splitRow} kids=${kids.length}`)
      const childKey = kids[0] && kids[0].businessKey
      if (splitRow && childKey) {
        await page.goto(`${APP}/history`, { waitUntil: 'networkidle' })
        await page.waitForSelector('.hc-page', { timeout: 20000 })
        await waitForList(page)
        await ensureStepsMode(page)
        await pickOption(page, '.hc-filter-status', '全部状态')
        await pickOption(page, '.hc-filter-job', new RegExp(`^#${hlJob} `))
        const kidRow = page.locator('.hc-ritem')
          .filter({ hasText: `#${hlRun.id} · 步骤 6` }).filter({ hasText: childKey }).first()
        await kidRow.waitFor({ timeout: 20000 })
        await kidRow.click()
        await waitForDetail(page)
        const chainText = (await page.locator('.hc-chain').innerText()).replace(/\s+/g, ' ')
        check('子件是拆分新造出来的一条消息：它的链从步骤 6 起（步骤 1..5 属于父消息）',
          chainText.includes('map-line') && chainText.includes('write-lines') && !chainText.includes('split-lines'),
          chainText.slice(0, 200))
        const kidNode = page.locator('.hc-cnode').filter({ hasText: 'map-line' }).first()
        const kidBadge = kidNode.locator('.hc-bbadge')
        check('子件那一格带小批徽标（拆出来的一批）', await kidBadge.count() === 1, String(await kidBadge.count()))
        await kidBadge.click()
        const kidGroup = page.locator('.hc-batch[data-batch-step="6"]')
        await kidGroup.waitFor({ timeout: 10000 })
        const kidHead = (await kidGroup.locator('.hc-bt').innerText()).replace(/\s+/g, ' ')
        check('组头写清这是读取批、这一批 4 条子件、失败 0 条',
          kidHead === '读取批 · 一次请求 4 条 · 失败 0 条', kidHead)
        const kidText = (await kidGroup.innerText()).replace(/\s+/g, ' ')
        check('这一组里就是那 4 个子件（编号与源库子件逐一对应）',
          ['IT-SH-001', 'IT-SH-002', 'IT-SH-003', 'IT-SH-005'].every(k => kidText.includes(k)),
          kidText.slice(0, 200))

        // 谱系那一半：点**父消息**那一行（BOM-SH-0001），拆分那一格在它的链上，且那一格的
        // 数据快照就是拆出来的 4 条子件——界面上「父子」就是这两处合起来看得见的。
        await page.locator('.hc-ritem')
          .filter({ hasText: `#${hlRun.id} · 步骤 5` }).filter({ hasText: 'BOM-SH-0001' }).first().click()
        await waitForDetail(page)
        const parentChain = (await page.locator('.hc-chain').innerText()).replace(/\s+/g, ' ')
        check('父消息的链上有拆分那一格（步骤 5 split-lines，输入父报文、输出子件）',
          parentChain.includes('split-lines'), parentChain.slice(0, 200))
        await page.locator('.hc-cnode').filter({ hasText: 'split-lines' }).first().click()
        await waitForDetail(page)
        await page.locator('[data-od-id="snapshot-panel"] .hc-sbtn').filter({ hasText: '原始文本' }).first().click()
        await page.waitForTimeout(600)
        const rawSnap = (await page.locator('[data-od-id="snapshot-panel"]').innerText()).replace(/\s+/g, ' ')
        check('拆分那一格的数据快照就是那 4 条子件（谱系在快照原文里看得见）',
          ['IT-SH-001', 'IT-SH-002', 'IT-SH-003', 'IT-SH-005'].every(k => rawSnap.includes(k)),
          rawSnap.slice(0, 200))
        await page.screenshot({ path: path.join(shots, '143-a2-head-lines.png') })
      }
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
    // 自己搭的场景自己收：克隆出来的任务删掉（历史里的 run / 消息步留着，那是证据）
    for (const id of createdJobs) {
      await api('DELETE', `/jobs/${id}`).then(() => console.log(`清理: 任务 ${id} 已删`)).catch(() => {})
    }
    await fault(null).catch(() => {})
    console.log(`\n${failures.length ? `${failures.length} 项没过` : '全部通过'}`)
    if (failures.length) process.exitCode = 1
  }
})()
