/*
 * #147 真机 UI 验收：「重跑任务」在界面上真的可用，且组头不再多算一条。
 *
 * 票面那几条 AC 的**界面那一半**（接口那一半归 `tools/shadow-site/verify-scenario.ps1`）：
 *   ① 失败批组头上的按钮文案是「重跑任务」、有**二次确认弹窗**（文案说明"从未完成的那一批继续"、
 *      目标端会再收到一次该批请求），确认后调**任务执行接口** `POST /api/jobs/{id}/execute`
 *      ——与任务列表上那颗「执行」同一个接口；
 *   ② 历史页分组仍分得清「读取批 / 发送批」、组头「一次请求 N 条 · 失败 M 条」**不再多算那一条**
 *      （#143 记的偏差：20 条码写成「一次请求 21 条」），界面上不再有「重发这一批」；
 *   ③ 拆分链（A2 头行分表）新造出来的那一批仍是「读取批 · 一次请求 4 条 · 失败 0 条」；
 *   ④ **失败恢复闭环**：重跑之后 `WRITE_UPSERT` 补跑成功（原先"出站成功、下游失败"丢下的写回补上）。
 *
 * 场景自己搭（与 #143 同一套，不挑库里现成的失败批——那些任务的外部依赖在本地多半是坏的，
 * 而「重跑成功」这一侧恰恰需要一个**可控的目标端**）：
 *   ① 用 MES 替身（9100）的故障注入把标识注册打回 500；
 *   ② **克隆**造数脚本里那条批量链（`SH-B6-Register-Batch`，7 个动作：读 → 包装 → 合并 → 出站 →
 *      还原 → 映射 → 写回）：克隆出来的新任务 id 是新的，水位键 `job:<新id>` 因此是空的，
 *      不必去动 MySQL 里的水位（脚本不去碰库、也不起子进程）；
 *   ③ 跑一次 → 出站 500 → 出站那一步「信封 1 行 + 20 条成员同行」整批同因 FAILED、
 *      合并那一步（AGGREGATE）只有信封那一行（**批号在合并那一步转手**，所以信封是它那一批的第一步）；
 *   ④ 在界面上：消息步检索 → 这一批的那个任务 → 点一条失败成员 → 步骤链上出站那一格的批徽标
 *      → 组头「发送批 · 一次请求 **20** 条 · 失败 20 条」+ 21 颗 chip（20 条消息 + 打包那一颗）
 *      → 点「重跑任务」→ 弹窗确认 → 替身只多收到**一次**该批请求 → 新运行 SUCCESS 且写回那一步补跑成功。
 *
 * 另加一段（同一张票 AC 的界面那一半）：**A2 头行分表**也克隆出来跑一次，然后在历史页上看
 * 「父子谱系与批号」——子行的步骤链上看得见拆分那一格、子件那一格带批徽标、点开是
 * 「读取批 · 一次请求 4 条 · 失败 0 条」与那 4 个编号。
 *
 * 跑法（playwright 是本目录自带的，先 `npm i` 一次就行）：
 *   cd tools/ui && node batch-rerun-147.cjs
 * 跑之前：前端 dev server（5173）、后端（8080）、MES 替身（9100，`node tools/shadow-site/mes-mock.cjs`）
 * 都要在，且造数脚本已经跑过（`SH-B6-Register-Batch` 存在——克隆它的动作）。跑完删掉克隆出来的任务。
 *
 * 与 `message-history-135.cjs` 的关系：那个脚本验的是**逐条断点重投**（`/history/steps/{id}/redeliver`，
 * 有弹窗、要二次确认）；这一支验的是**重跑任务**（`/jobs/{id}/execute`，也有二次确认，
 * 但那一下说的是"整批重走、目标端会再收到一次"，不是"只重发请求"）。
 */
const fs = require('fs')
const path = require('path')
const { chromium } = require('playwright')

const APP = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080/api'
const MOCK = 'http://localhost:9100'
/** 造数脚本里的那条批量链：克隆它的动作，克隆体拿到新的任务 id（= 空水位）。 */
const SOURCE_JOB = 'SH-B6-Register-Batch'
/** 链上各步的序号：合并（AGGREGATE）/ 出站（HTTP_CALL）/ 写回（WRITE_UPSERT，链上最后一个动作）。 */
const AGGREGATE_STEP = 3
const OUTLET_STEP = 4
const WRITE_STEP = 7
/** 这一批几条码（= 造数脚本那一条链读出来的行数）。 */
const EXPECTED = 20
/** 确认弹窗里必须出现的那两句（文案在 `frontend/src/history/batch.ts` 的 `rerunConfirmText`，一处写）。 */
const CONFIRM_SAYS = ['未完成的那一批', '目标端会再收到一次该批请求']
/** 已删除的那条路径在界面上一个字都不该剩（#147）。 */
const GONE_TEXT = '重发这一批'
const shots = path.join(__dirname, 'shots')
const failures = []
const log = []
let browser = null
let jobId = null

function check(name, cond, detail = '') {
  console.log(`${cond ? 'ok  ' : 'NOT '} - ${name}${cond ? '' : ` :: ${detail}`}`)
  if (!cond) failures.push(name)
}

/* ── 发出去的请求（「点一次调一次执行接口」的证据就靠它） ─────────────── */

const executePosts = () => log.filter(r => r.method === 'POST' && /\/jobs\/\d+\/execute$/.test(r.url))

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

/** 替身收到过几次标识注册（「重跑又发了一次」的裁判）。 */
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

/* ── 浏览器侧的小工具（与 #129/#130/#131/#135/#136/#143 同一套） ─────── */

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
  jobId = await cloneJob(source, `__ui147-rerun-${Date.now()}`, 7)
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
  // 信封消息 = 合并那一步（AGGREGATE）的那一条：它的消息标识在出站那一步也有一行，
  // 所以判「成员」要把这一步的消息标识排除掉（场景侧的事实，不是把分组判据抄一遍）
  const envelopeIds = new Set(runSteps.filter(s => s.step === AGGREGATE_STEP).map(s => s.messageId))
  const failedMembers = runSteps.filter(s => s.step === OUTLET_STEP && s.status === 'FAILED'
    && !envelopeIds.has(s.messageId))
  check('场景就绪：这一次运行 FAILED、出站那一步是「信封 1 行 + 20 条成员同行」（同因）',
    !!run && run.status === 'FAILED' && envelopeIds.size === 1 && failedMembers.length === EXPECTED,
    `run=${run && run.status} envelope=${envelopeIds.size} failedMembers=${failedMembers.length}`)
  if (!run || failedMembers.length !== EXPECTED) return
  const member = failedMembers[0]
  console.log(`run ${run.id} · 信封 ${[...envelopeIds][0]} · 失败成员 ${failedMembers.length} 条（如 ${member.businessKey}）`)

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

  // 消息步检索 + 全部状态 + 只看我们这个任务
  await ensureStepsMode(page)
  await pickOption(page, '.hc-filter-status', '全部状态')
  await pickOption(page, '.hc-filter-job', new RegExp(`^#${jobId} `))

  // 点一条**失败成员**那一行（不是信封那一条：成员的消息链上才有完整的 1..4 步）
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
  check('组头写清这是发送批，且**不再多算一条**（#143 那处偏差：原来是「一次请求 21 条 · 失败 20 条」）',
    headText === `发送批 · 一次请求 ${EXPECTED} 条 · 失败 ${EXPECTED} 条`, headText)
  const chips = await group.locator('.hc-mchip').count()
  check('这一组 21 颗 chip（20 条消息 + 打包那一颗）', chips === EXPECTED + 1, String(chips))
  const packed = group.locator('.hc-mchip-packed')
  const packedCount = await packed.count()
  check('打包消息那颗 chip 一眼认得出来（虚线 + 「打包报文」标）',
    packedCount === 1 && (await packed.innerText()).includes('打包报文'),
    `${packedCount} / ${packedCount ? await packed.innerText() : ''}`)
  check('界面上不再有「重发这一批」那颗按钮', await group.locator('.hc-bredeliver').count() === 0,
    String(await group.locator('.hc-bredeliver').count()))

  const button = group.locator('.hc-brerun')
  check('组头铺了「重跑任务」', await button.count() === 1, String(await button.count()))
  check('按钮上就是「重跑任务」四个字', (await button.innerText()).trim() === '重跑任务', await button.innerText())
  check('按钮可点（不是置灰摆样子）', !(await button.isDisabled()), String(await button.isDisabled()))
  check('按钮的无障碍标签说清「整批重走、目标端会再收到一次」',
    (await button.getAttribute('aria-label') || '').includes('目标端会再收到一次'), await button.getAttribute('aria-label'))
  await page.screenshot({ path: path.join(shots, '147-rerun-entry.png') })

  // ── ③ 修好目标端，点按钮：先二次确认，再调任务执行接口 ──
  await fault(null)
  const before = await registrations()
  await button.click()
  const box = page.locator('.el-message-box')
  await box.waitFor({ timeout: 10000 })
  const boxText = (await box.innerText()).replace(/\s+/g, ' ')
  check('点一下就出来**二次确认弹窗**（不是点一下就发）', await box.isVisible(), boxText.slice(0, 120))
  check('确认文案说明会从「未完成的那一批」继续', boxText.includes(CONFIRM_SAYS[0]), boxText)
  check('确认文案点明目标端会再收到一次该批请求', boxText.includes(CONFIRM_SAYS[1]), boxText)
  await page.screenshot({ path: path.join(shots, '147-rerun-confirm.png') })
  await box.locator('.el-message-box__btns .el-button--primary').click()
  await page.locator('.el-message').filter({ hasText: '重跑完成' }).first().waitFor({ timeout: 60000 })
  const toast = (await page.locator('.el-message').first().innerText()).replace(/\s+/g, ' ')
  check('成功后给出「重跑完成：<状态>」那句', toast.includes('重跑完成'), toast)
  check('这一次点击调的是**任务执行接口**（`POST /jobs/{id}/execute`，与任务列表那颗同一个）',
    executePosts().some(r => r.url.endsWith(`/jobs/${jobId}/execute`)), JSON.stringify(executePosts().map(r => r.url)))
  check('这一次点击只调了一次（按钮 pending 挡住连点）', executePosts().length === 1, String(executePosts().length))
  const after = await registrations()
  check('重跑把这一批又发了一次（替身侧的真数 +1，不看界面画的字）', after - before === 1, `${before} -> ${after}`)

  // ── ④ 闭环：新运行 SUCCESS、写回那一步补跑成功、原运行仍是那次 FAILED ──
  await page.waitForTimeout(1500)
  const newRun = listOf(await api('GET', `/history/runs?jobId=${jobId}&page=1&size=1`))[0]
  check('重跑产生了一条新的 SUCCESS 运行', !!newRun && newRun.id > run.id && newRun.status === 'SUCCESS',
    `${newRun && newRun.id} ${newRun && newRun.status}`)
  const newSteps = newRun ? listOf(await api('GET', `/history/runs/${newRun.id}/steps?page=1&size=1000`)) : []
  const writeRows = newSteps.filter(s => s.step === WRITE_STEP)
  check('重跑之后 WRITE_UPSERT（步骤 7）补跑成功：这一批 20 条都写了（原先丢下的写回因此补上）',
    writeRows.length === EXPECTED && writeRows.every(s => s.status === 'SUCCESS'),
    `rows=${writeRows.length} 其中非 SUCCESS=${writeRows.filter(s => s.status !== 'SUCCESS').length}`)
  const originalRun = await api('GET', `/history/runs/${run.id}`)
  check('原运行的状态没被改写（重跑是新的一条运行）', originalRun.status === 'FAILED', originalRun.status)
  const pageText = await page.content()
  check('整页上都不再有「重发这一批」', !pageText.includes(GONE_TEXT))
  await page.screenshot({ path: path.join(shots, '147-rerun-done.png') })

  // ── ⑤ A2 头行分表：拆分链新造出来的那一批仍是「读取批」 ──
  const hlName = 'SH-A2-Bom-Head-Lines'
  const hlSource = listOf(await api('GET', `/jobs?name=${hlName}`)).filter(j => j.name === hlName)[0]
  if (!hlSource) {
    check(`造数脚本里那条头行分表链在（${hlName}）`, false, '先跑 seed-scenario.ps1')
  } else {
    const hlJob = await cloneJob(hlSource, `__ui147-a2-head-lines-${Date.now()}`, 7)
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
        check('拆分链仍是**读取批**：这一批 4 条子件、失败 0 条（拆分新造的批号里没有基数变化步，也没有打包消息）',
          kidHead === '读取批 · 一次请求 4 条 · 失败 0 条', kidHead)
        check('拆分链那一组里既没有打包消息颗、也没有「重跑任务」',
          await kidGroup.locator('.hc-mchip-packed').count() === 0
          && await kidGroup.locator('.hc-brerun').count() === 0)
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
        const rawSnap = await page.locator('[data-od-id="snapshot-panel"]').innerText().catch(() => '')
        check('拆分那一格的数据快照就是那 4 条子件（谱系在快照原文里看得见）',
          ['IT-SH-001', 'IT-SH-002', 'IT-SH-003', 'IT-SH-005'].every(k => rawSnap.includes(k)),
          rawSnap.replace(/\s+/g, ' ').slice(0, 200))
        await page.screenshot({ path: path.join(shots, '147-a2-head-lines.png') })
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
