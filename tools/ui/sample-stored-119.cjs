/*
 * #119 真机 UI 验收：粘一次就留着——手里那份样例跟着「保存」一起落库，下次打开优先用它。
 *
 * 用任务 3（DBG-TS，FIELD_MAP 动作 8，写目标是真表 dbg_receipt）：它的来源与目标都探得出来，
 * 「存过的那份压过探测」才有得比。分四段：
 *   1. **打开时存过就用存的那份**（把 GET 上下文的响应换成一个「库里存过」的样子：这是纯界面那一半）：
 *      来源顶上写明「粘的（已存）」、清单就是存的那份字段、目标那片不连库探测（一个 probe-target 都不发）；
 *   2. **点保存时那两格**（把 PUT samples 拦下来看请求体）：来源=粘的这份（带原文）、目标=探测来的（不带原文
 *      ——后端据此把库里那一格删掉）；而且它排在动作本身写库**之前**；
 *   3. **「从运行记录探测」之后保存**：来源那一格只报归属、不带原文（回到探测那条路）；
 *   4. **粘了但不保存（取消）**：一个请求都不发（「取消 = 都不留」）；
 *   5. **重开还在**（要后端已经有这一版）：左右各粘一份、真存一次、关掉重开——两份清单与「粘的（已存）」
 *      那句话都还在。后端还是旧进程时这一段 SKIP，并指出后端那几条用例在哪。
 *
 * 第 2～4 段把 PUT samples 与 PUT actions 都拦下，所以不会写库，也不会动动作配置。
 *
 * 跑法（playwright 是本目录自带的，先 `npm i` 一次就行；前端 dev server 与后端都要在）：
 *   cd tools/ui && node sample-stored-119.cjs
 */
const fs = require('fs')
const path = require('path')
const { chromium } = require('playwright')

const APP = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080/api'
const JOB_ID = 3
const ACTION_ID = 8
const SAMPLES_URL = '**/field-mapping-editor/samples'
const shots = path.join(__dirname, 'shots')
const failures = []
let browser = null

/** 存进库 / 粘进框的那份原文（一段好认的文本：断言时按它比对）。 */
const SOURCE_TEXT = '{"order_no":"PO-9","qty":3}'
const TARGET_TEXT = '{"order_no":"PO-9"}'

function check(name, cond, detail = '') {
  console.log(`${cond ? 'ok  ' : 'NOT '} - ${name}${cond ? '' : ` :: ${detail}`}`)
  if (!cond) failures.push(name)
}

function skip(name, why) {
  console.log(`SKIP - ${name} :: ${why}`)
}

/** 打开某个任务的 FIELD_MAP 新版编辑器（按任务 ID 认行，免得被名称编码坑到）。 */
async function openEditor(page) {
  await page.goto(`${APP}/jobs`, { waitUntil: 'networkidle' })
  const rows = page.locator('tbody tr')
  await rows.first().waitFor({ timeout: 20000 })
  const count = await rows.count()
  let target = null
  for (let i = 0; i < count; i++) {
    const id = (await rows.nth(i).locator('td').first().innerText()).trim()
    if (id === String(JOB_ID)) {
      target = rows.nth(i)
      break
    }
  }
  if (!target) throw new Error(`任务列表里没有 id=${JOB_ID} 的行`)
  await target.getByRole('button', { name: '动作' }).click()
  await page.waitForSelector('text=动作链', { timeout: 20000 })
  await page.locator('tbody tr', { hasText: 'FIELD_MAP' }).first()
    .getByRole('button', { name: '编辑映射' }).click()
  await page.waitForSelector('.wb-row, .wb-empty', { timeout: 25000 })
  await page.waitForTimeout(800)
}

/** 左边来源清单上铺出来的字段名。 */
async function sourceNames(page) {
  return (await page.locator('.wb-source-item .wb-source-name').allInnerTexts()).map(s => s.trim())
}

async function sourceTip(page) {
  return (await page.locator('.wb-source .wb-panel-tip').first().innerText()).trim()
}

async function targetTip(page) {
  return (await page.locator('.wb-rows-head .wb-panel-tip').first().innerText()).trim()
}

/** 点保存，并一路点掉那几个确认框（与真机上一样）。 */
async function clickSave(page) {
  await page.locator('.wb-head-actions').getByRole('button', { name: '保存', exact: true }).click()
  for (let i = 0; i < 24; i++) {
    const confirm = page.locator('.el-message-box__btns button')
      .filter({ hasText: /只保存配好的|确认过了，保存|替换并保存/ })
    if (await confirm.count()) await confirm.first().click()
    await page.waitForTimeout(400)
  }
}

;(async () => {
  fs.mkdirSync(shots, { recursive: true })
  browser = await chromium.launch()

  // 后端有没有这一版（PUT samples）。空体不落任何一格，问了也不会写库。
  const probe = await fetch(`${API}/jobs/${JOB_ID}/field-mapping-editor/samples`, {
    method: 'PUT',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({}),
  }).catch(() => null)
  const probeBody = probe ? await probe.json().catch(() => null) : null
  const backendHasIt = probe != null && probe.status === 204
  if (!backendHasIt) {
    skip('真存一次、重开还在（第 5 段）',
      `后端（8080）还是改动之前起的那一份（PUT samples → ${probe ? probe.status : '连不上'} ${probeBody?.message ?? ''}）；`
      + '这一半的断言在后端用例 FieldMappingEditorServiceTest.storedSampleWinsOverTheRunSnapshotWhenOpening '
      + '与 MappingSampleServiceTest 里')
  }

  // 这一份是「库里存过」的样子（第 1 段拿它顶替 GET 上下文的响应）
  const realContext = await fetch(`${API}/jobs/${JOB_ID}/field-mapping-editor?actionId=${ACTION_ID}`)
    .then(r => r.json()).then(b => b.data).catch(() => null)
  const storedSource = {
    step: realContext?.source?.step ?? 0,
    stepName: realContext?.source?.stepName ?? null,
    sourceKey: 'stored',
    sampledFirstItem: false,
    json: SOURCE_TEXT,
    fields: [{ name: 'stored_only', type: 'STRING', nullable: true, samples: ['S1'] }],
  }
  const storedTarget = {
    sourceKey: 'stored',
    sampledFirstItem: false,
    json: TARGET_TEXT,
    fields: [{ name: 'stored_target_col', type: 'STRING', nullable: true, samples: ['T'] }],
  }

  // ---------------------------------------------------------------- 第 1 段：打开时存过就用存的那份
  {
    const page = await browser.newPage({ viewport: { width: 1680, height: 1000 } })
    page.setDefaultTimeout(20000)
    const pageErrors = []
    page.on('pageerror', e => pageErrors.push(e.message))
    let probeTargetCalls = 0
    page.on('request', req => {
      if (req.method() === 'POST' && req.url().includes('/probe-target')) probeTargetCalls++
    })
    await page.route('**/field-mapping-editor?*', async route => {
      if (route.request().method() !== 'GET') return route.continue()
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          code: 'OK', message: 'ok',
          data: { ...realContext, source: storedSource, targetSample: storedTarget },
        }),
      })
    })

    await openEditor(page)
    check('打开时左边就是库里存过的那份（字段清单取自存的原文）',
      (await sourceNames(page)).includes('stored_only'), JSON.stringify(await sourceNames(page)))
    const tip = await sourceTip(page)
    check('来源顶上写明「粘的（已存）」', tip.startsWith('粘的（已存）：'), tip)
    check('来源顶上不再说成「最近一次成功运行」', !tip.includes('最近一次成功运行'), tip)
    check('还写明这份样例按哪一步的输出来算', /按「.+」的输出来算$/.test(tip), tip)
    check('目标那一片也不连库探测（一个 probe-target 都不发）', probeTargetCalls === 0,
      `probeTargetCalls=${probeTargetCalls}`)
    const tTip = await targetTip(page)
    check('目标顶上写明「粘的（已存）」（不再是 db: 表名）',
      tTip.includes('粘的（已存）') && !tTip.includes('db:'), tTip)
    check('粘出来的那份照旧写明必填推不出来',
      (await page.locator('.wb-target-note').innerText().catch(() => '')).includes('必填推不出来'),
      await page.locator('.wb-target-note').innerText().catch(() => ''))
    check('第 1 段页面上没有 JS 报错', pageErrors.length === 0, JSON.stringify(pageErrors))
    await page.screenshot({ path: `${shots}/sample-stored-reopened.png` })
    await page.close()
  }

  // ---------------------------------------------------------------- 第 2 段：点保存发出去的那两格
  {
    const page = await browser.newPage({ viewport: { width: 1680, height: 1000 } })
    page.setDefaultTimeout(20000)
    const pageErrors = []
    page.on('pageerror', e => pageErrors.push(e.message))
    const sent = []
    let samplesBody = null
    let actionBody = null
    await page.route(SAMPLES_URL, async route => {
      if (route.request().method() !== 'PUT') return route.continue()
      sent.push('samples')
      samplesBody = route.request().postData()
      await route.fulfill({ status: 204, body: '' })
    })
    await page.route('**/api/actions/*', async route => {
      if (route.request().method() !== 'PUT') return route.continue()
      sent.push('action')
      actionBody = route.request().postData()
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 'OK', message: 'ok', data: null }),
      })
    })

    await openEditor(page)
    const echoedTip = await sourceTip(page)
    // 粘一份来源样例（这条路 #115 就有，真发请求）
    await page.locator('.wb-source-entries button', { hasText: '粘贴 JSON' }).click()
    const dialog = page.locator('.el-dialog').filter({ hasText: '粘贴 JSON' })
    await dialog.waitFor({ timeout: 10000 })
    await dialog.locator('.wb-paste-text textarea').fill(SOURCE_TEXT)
    await dialog.getByRole('button', { name: '推出字段清单' }).click()
    await page.waitForTimeout(1500)
    const pasteError = await page.locator('.wb-paste-error').innerText().catch(() => '')
    check('粘一份来源样例能推出清单', pasteError === '', pasteError)
    if (backendHasIt) {
      check('探测那条路的说法与粘的那条路分得清',
        echoedTip.startsWith('来自 ') && (await sourceTip(page)).startsWith('这份样例按「'), echoedTip)
    }

    await clickSave(page)
    check('保存发出了那一格（PUT samples）', samplesBody != null)
    check('它排在动作本身写库之前（配置没存成时这份粘的也不丢）',
      sent.join('>') === 'samples>action', sent.join('>'))
    if (samplesBody) {
      const body = JSON.parse(samplesBody)
      check('来源那一格带上了归属（任务 + 步骤）',
        typeof body.source?.step === 'number', samplesBody)
      check('来源那一格带的是**原文**（不是推出来的清单）', body.source?.json === SOURCE_TEXT, samplesBody)
      check('目标那一边手里是探测来的：只报归属、不带原文（后端据此把库里那一格删掉）',
        typeof body.target?.actionId === 'number' && body.target.json === null, samplesBody)
    }
    check('粘进来的样例值照旧一个都没进动作配置（只进样例那一格）',
      actionBody != null && !actionBody.includes('PO-9') && actionBody.includes('"rules"'),
      String(actionBody).slice(0, 120))
    check('第 2 段页面上没有 JS 报错', pageErrors.length === 0, JSON.stringify(pageErrors))
    await page.close()
  }

  // ---------------------------------------------------------------- 第 3 段：重新探测之后保存 → 只报归属
  {
    const page = await browser.newPage({ viewport: { width: 1680, height: 1000 } })
    page.setDefaultTimeout(20000)
    const pageErrors = []
    page.on('pageerror', e => pageErrors.push(e.message))
    let samplesBody = null
    await page.route(SAMPLES_URL, async route => {
      if (route.request().method() !== 'PUT') return route.continue()
      samplesBody = route.request().postData()
      await route.fulfill({ status: 204, body: '' })
    })
    await page.route('**/api/actions/*', async route => {
      if (route.request().method() !== 'PUT') return route.continue()
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 'OK', message: 'ok', data: null }),
      })
    })

    await openEditor(page)
    await page.locator('.wb-source-entries button', { hasText: '从运行记录探测' }).click()
    await page.waitForTimeout(1500)
    await clickSave(page)
    const body = samplesBody ? JSON.parse(samplesBody) : null
    check('「从运行记录探测」之后保存：来源那一格只报归属、不带原文',
      body != null && typeof body.source?.step === 'number' && body.source.json === null,
      String(samplesBody))
    check('第 3 段页面上没有 JS 报错', pageErrors.length === 0, JSON.stringify(pageErrors))
    await page.close()
  }

  // ------------------------------------------- 第 4 段：粘了但不保存（取消）→ 一个请求都不发
  {
    const page = await browser.newPage({ viewport: { width: 1680, height: 1000 } })
    page.setDefaultTimeout(20000)
    const pageErrors = []
    page.on('pageerror', e => pageErrors.push(e.message))
    let samplesCalls = 0
    await page.route(SAMPLES_URL, async route => {
      if (route.request().method() !== 'PUT') return route.continue()
      samplesCalls++
      await route.fulfill({ status: 204, body: '' })
    })

    await openEditor(page)
    await page.locator('.wb-source-entries button', { hasText: '粘贴 JSON' }).click()
    const dialog = page.locator('.el-dialog').filter({ hasText: '粘贴 JSON' })
    await dialog.waitFor({ timeout: 10000 })
    await dialog.locator('.wb-paste-text textarea').fill(SOURCE_TEXT)
    await dialog.getByRole('button', { name: '推出字段清单' }).click()
    await page.waitForTimeout(1500)
    await page.locator('.wb-head-actions').getByRole('button', { name: '取消' }).click()
    await page.waitForTimeout(800)
    check('粘了但点「取消」：样例那一格一个请求都不发（粘了不保存就什么都不留）',
      samplesCalls === 0, `samplesCalls=${samplesCalls}`)
    check('取消之后抽屉关上了',
      (await page.locator('.mapping-workbench-drawer').count()) === 0
      || !(await page.locator('.mapping-workbench-drawer').first().isVisible()),
      '抽屉还开着')
    check('第 4 段页面上没有 JS 报错', pageErrors.length === 0, JSON.stringify(pageErrors))
    await page.close()
  }

  // ---------------------------------------------------------------- 第 5 段（要有新后端）：真存一次、重开还在
  if (backendHasIt) {
    const page = await browser.newPage({ viewport: { width: 1680, height: 1000 } })
    page.setDefaultTimeout(20000)
    const pageErrors = []
    page.on('pageerror', e => pageErrors.push(e.message))
    let probeTargetCalls = 0
    page.on('request', req => {
      if (req.method() === 'POST' && req.url().includes('/probe-target')) probeTargetCalls++
    })
    // 样例那两格真写库；动作那份配置照旧拦下（不动任务 3）
    await page.route('**/api/actions/*', async route => {
      if (route.request().method() !== 'PUT') return route.continue()
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 'OK', message: 'ok', data: null }),
      })
    })
    await openEditor(page)
    // 左右两边各粘一份（右边那份要能原样回来，才算「下次打开，左边 / 右边就是它」）
    await page.locator('.wb-rows-head').getByRole('button', { name: '粘贴 JSON' }).click()
    const targetDialog = page.locator('.el-dialog').filter({ hasText: '粘贴 JSON' })
    await targetDialog.waitFor({ timeout: 10000 })
    await targetDialog.locator('.wb-paste-text textarea').fill(TARGET_TEXT)
    await targetDialog.getByRole('button', { name: '推出字段清单' }).click()
    await page.waitForTimeout(1500)
    await page.locator('.wb-source-entries button', { hasText: '粘贴 JSON' }).click()
    const sourceDialog = page.locator('.el-dialog').filter({ hasText: '粘贴 JSON' })
    await sourceDialog.waitFor({ timeout: 10000 })
    await sourceDialog.locator('.wb-paste-text textarea').fill(SOURCE_TEXT)
    await sourceDialog.getByRole('button', { name: '推出字段清单' }).click()
    await page.waitForTimeout(1500)
    await clickSave(page)
    await page.waitForTimeout(800)

    await page.reload({ waitUntil: 'networkidle' })
    await openEditor(page)
    const reopened = await sourceTip(page)
    check('重开编辑器：来源那份清单还在（没有再连运行记录探测）', reopened.startsWith('粘的（已存）：'), reopened)
    check('重开之后左边就是粘进来的那些字段',
      (await sourceNames(page)).includes('order_no'), JSON.stringify(await sourceNames(page)))
    const reopenedTarget = await targetTip(page)
    check('重开之后右边也是那份粘的（不再是 db: 表名）',
      reopenedTarget.includes('粘的（已存）') && !reopenedTarget.includes('db:'), reopenedTarget)
    check('右边那份存过之后重开也不连库探测', probeTargetCalls === 0, `probeTargetCalls=${probeTargetCalls}`)
    check('第 5 段页面上没有 JS 报错', pageErrors.length === 0, JSON.stringify(pageErrors))
    await page.screenshot({ path: `${shots}/sample-stored-reread.png` })
    await page.close()
  }

  console.log(`\n# ${failures.length ? `${failures.length} 项没过: ${failures.join(' / ')}` : '全部通过'}`)
  await browser.close()
  process.exit(failures.length ? 1 : 0)
})().catch(async e => {
  console.error(e)
  if (browser) await browser.close()
  process.exit(1)
})
