/*
 * #103 真机 UI 验收：「校验配置」——后端只读接口 + 界面接线。
 *
 * 用任务 3（DBG-TS，FIELD_MAP 动作 8，映射规则 9 条，来源样例与目标字段都探得出来）：
 *   1. 点「校验配置」→ 接口返回 OK；界面显示「没有发现问题」，每一行右侧是「没问题」；
 *      底部两个标签页有编译产物（JSONata 里是 $s_1.xxx、规则 JSON 里是存进去的那份 rules）。
 *   2. 加一行**重复目标字段**的行（+ 字段映射 → 目标写 order_no → 选来源 order_no）→
 *      再点「校验配置」→ 报一条错误、落在那一行上（「有错」）、问题列表点一下跳到那一行。
 *   3. 点「保存」→ 被拦下（没有发出保存请求、编辑器还开着、跳到第一条错误所在的行）。
 *   全程不保存，数据库不动。
 *
 * 跑法（playwright 是本目录自带的，先 `npm i` 一次就行）：
 *   cd tools/ui && npm i && node draft-validation-103.cjs
 */
const fs = require('fs')
const path = require('path')
const { chromium } = require('playwright')

const APP = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080/api'
const JOB_ID = 3
const ACTION_ID = 8
const shots = path.join(__dirname, 'shots')
const failures = []
let browser = null

function check(name, cond, detail = '') {
  console.log(`${cond ? 'ok  ' : 'NOT '} - ${name}${cond ? '' : ` :: ${detail}`}`)
  if (!cond) failures.push(name)
}

/** 打开某个任务的 FIELD_MAP 新版编辑器（按任务 ID 认行，免得被名称编码坑到）。 */
async function openEditor(page, jobId) {
  await page.goto(`${APP}/jobs`, { waitUntil: 'networkidle' })
  const rows = page.locator('tbody tr')
  await rows.first().waitFor({ timeout: 20000 })
  const count = await rows.count()
  let target = null
  for (let i = 0; i < count; i++) {
    const id = (await rows.nth(i).locator('td').first().innerText()).trim()
    if (id === String(jobId)) {
      target = rows.nth(i)
      break
    }
  }
  if (!target) throw new Error(`任务列表里没有 id=${jobId} 的行`)
  await target.getByRole('button', { name: '动作' }).click()
  await page.waitForSelector('text=动作链', { timeout: 20000 })
  await page.locator('tbody tr', { hasText: 'FIELD_MAP' }).first()
    .getByRole('button', { name: '编辑映射' }).click()
  await page.waitForSelector('.wb-row, .wb-empty', { timeout: 25000 })
  await page.waitForTimeout(800)
}

/** 点「校验配置」并等它那一次请求回来（定位限定在编辑器里：旧编辑器也有同名按钮）。 */
async function clickValidate(page) {
  const button = page.locator('.wb-head-actions').getByRole('button', { name: '校验配置' })
  const [response] = await Promise.all([
    page.waitForResponse(
      r => r.url().includes('/field-mapping-editor/validate') && r.request().method() === 'POST',
      { timeout: 30000 }),
    button.click(),
  ])
  const body = await response.json()
  await page.waitForTimeout(400)
  return { status: response.status(), data: body.data }
}

;(async () => {
  fs.mkdirSync(shots, { recursive: true })
  browser = await chromium.launch()
  const page = await browser.newPage({ viewport: { width: 1680, height: 1000 } })
  page.setDefaultTimeout(20000)
  const pageErrors = []
  page.on('pageerror', e => pageErrors.push(e.message))

  /** 保存会发的那个请求（PUT /api/actions/{id}）：有错时必须一个都没有。 */
  let saveCalls = 0
  page.on('request', req => {
    if (req.method() === 'PUT' && /\/api\/actions\/\d+$/.test(new URL(req.url()).pathname)) saveCalls++
  })

  await openEditor(page, JOB_ID)
  const rowCount = await page.locator('.wb-row').count()
  check('编辑器打开，行铺出来了', rowCount > 0, `rows=${rowCount}`)

  // ---- 1. 校验保存下来的那份配置：没有错
  // 先把校验那一次请求延后 800ms，好看见按钮上的进行中状态
  await page.route('**/field-mapping-editor/validate', async route => {
    await new Promise(resolve => setTimeout(resolve, 800))
    await route.continue()
  })
  const pending = clickValidate(page)
  await page.waitForTimeout(250)
  check('校验进行中时按钮上有 loading',
    await page.locator('.wb-head-actions button.is-loading').count() > 0)
  const first = await pending
  await page.unroute('**/field-mapping-editor/validate')

  check('校验接口返回 200', first.status === 200, `status=${first.status}`)
  check('这份配置没有错误', (first.data.errors ?? []).length === 0,
    JSON.stringify(first.data.errors))
  check('每一行右侧都显示「没问题」',
    await page.locator('.wb-state.ok').count() === rowCount,
    `ok=${await page.locator('.wb-state.ok').count()} rows=${rowCount}`)
  check('问题列表说没有发现问题',
    (await page.locator('.wb-issues').innerText()).includes('没有发现问题'),
    await page.locator('.wb-issues').innerText())

  // 底部编译产物：两个标签页
  const jsonata = await page.locator('.wb-artifact:visible').innerText()
  check('JSONata 那一页是编译出来的表达式',
    jsonata.includes('$s_1.order_no'), jsonata.slice(0, 120))
  await page.getByRole('tab', { name: '规则 JSON' }).click()
  await page.waitForTimeout(400)
  const rulesJson = await page.locator('.wb-artifact:visible').innerText()
  check('规则 JSON 那一页是存进去的那份 rules',
    rulesJson.includes('"rules"') && rulesJson.includes('"order_no"'), rulesJson.slice(0, 120))
  await page.screenshot({ path: `${shots}/draft-validation-valid.png` })

  // ---- 2. 加一行重复目标字段的行：这一行要报「有错」
  await page.getByRole('button', { name: '+ 字段映射' }).click()
  await page.waitForTimeout(400)
  const addedRow = page.locator('.wb-row').last()
  await addedRow.locator('.wb-col-target input').click()
  await addedRow.locator('.wb-col-target input').fill('order_no')
  await page.keyboard.press('Escape') // 关掉补全下拉（Esc 不冒到抽屉上）
  await page.waitForTimeout(300)
  await page.locator('.wb-source-item').filter({ hasText: 'order_no' }).first().click()
  await page.waitForTimeout(400)

  const second = await clickValidate(page)
  check('重复的目标字段被报出来',
    (second.data.errors ?? []).length === 1
      && second.data.errors[0].target === 'order_no'
      && second.data.errors[0].message.includes('重复映射'),
    JSON.stringify(second.data.errors))
  const addedState = await addedRow.locator('.wb-state').innerText()
  check('出错的那一行右侧显示「有错」', addedState.trim() === '有错', addedState)
  check('行里直接写着这条问题',
    (await addedRow.locator('.wb-issue-inline').innerText()).includes('重复映射'),
    await addedRow.locator('.wb-issue-inline').innerText())
  await page.getByRole('tab', { name: 'JSONata' }).click()
  await page.waitForTimeout(400)
  check('底部 JSONata 那一页没有编译产物（这份草稿编译不出来）',
    (await page.locator('.wb-artifact:visible').innerText()).includes('先把错误改掉'),
    await page.locator('.wb-artifact:visible').innerText())
  await page.screenshot({ path: `${shots}/draft-validation-invalid.png` })

  // 问题列表点一下 → 跳到那一行
  await page.locator('.wb-issue').first().click()
  await page.waitForTimeout(400)
  const selectedId = await page.locator('.wb-row.selected').first().getAttribute('data-row-id')
  const addedId = await addedRow.getAttribute('data-row-id')
  check('问题列表点一下就跳到出错的那一行', selectedId === addedId,
    `selected=${selectedId} added=${addedId}`)

  // ---- 3. 有错时保存被拦下
  saveCalls = 0
  let validateCalls = 0
  page.on('request', req => {
    if (req.url().includes('/field-mapping-editor/validate')) validateCalls++
  })
  await page.locator('.wb-head-actions').getByRole('button', { name: '保存', exact: true }).click()
  await page.waitForSelector('.el-message', { timeout: 15000 })
  const toast = await page.locator('.el-message').allInnerTexts()
  await page.waitForTimeout(1500)
  check('保存自己先跑了一次校验', validateCalls >= 1, `validateCalls=${validateCalls}`)
  check('有错时保存没有发出去', saveCalls === 0, `saveCalls=${saveCalls}`)
  check('编辑器还开着（没被关掉）', await page.locator('.mapping-workbench-drawer').count() === 1)
  check('给出了拦下的原因', toast.some(t => t.includes('错误')), JSON.stringify(toast))
  check('有错时不再先问「只保存配好的」',
    await page.getByRole('button', { name: '只保存配好的' }).count() === 0)
  const selectedAfterSave = await page.locator('.wb-row.selected').first().getAttribute('data-row-id')
  check('保存被拦下时停在第一条错误所在的行', selectedAfterSave === addedId,
    `selected=${selectedAfterSave} added=${addedId}`)
  await page.screenshot({ path: `${shots}/draft-validation-save-blocked.png` })

  check('页面上没有 JS 报错', pageErrors.length === 0, JSON.stringify(pageErrors))

  // ---- 4. 第三种状态：后端给警告时那一行显示「有警告」
  //      （真正的警告来自下一张票那六条基于样例的检查；这里把界面这一半先验掉——警告挂到行上、
  //       和错误分得开、不拦保存。）
  //      挑一个**只出现一次**的目标字段：上面加的那一行 order_no 和原来那行同名，
  //      而同名时问题按既定规则挂在后加的那一行上（那正是「错挂在新增行上」的语义）。
  const targets = await page.locator('.wb-row .wb-col-target input').evaluateAll(els => els.map(e => e.value))
  const unique = targets.find((v, i) => v && targets.indexOf(v) === targets.lastIndexOf(v))
  await page.route('**/field-mapping-editor/validate', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      code: 'OK',
      message: 'ok',
      data: {
        valid: true,
        errors: [],
        warnings: [{ target: unique, parent: null, message: '来源字段不在样例里（提醒确认）' }],
        compiled: { jsonata: '{\n  "order_no": $s_1.order_no\n}', rulesJson: '{ "rules" : [ ] }' },
      },
    }),
  }))
  await clickValidate(page)
  const warnRow = page.locator('.wb-row').nth(targets.indexOf(unique))
  const warnState = (await warnRow.locator('.wb-state').innerText()).trim()
  check('有警告的行显示「有警告」', warnState === '有警告', `${warnState}（目标字段 ${unique}）`)
  check('警告不拦保存（这一行仍然只是警告，不是有错）',
    await page.locator('.wb-state.error').count() === 0)
  const summary = await page.locator('.wb-issue-summary').innerText()
  check('问题列表把警告数出来', summary.includes('0 个错误') && summary.includes('1 个警告'), summary)
  await page.unroute('**/field-mapping-editor/validate')

  console.log(`\n${failures.length ? `FAILED: ${failures.join(' | ')}` : 'ALL OK'}`)
  await browser.close()
  process.exit(failures.length ? 1 : 0)
})().catch(async e => {
  console.error(e)
  if (browser) await browser.close()
  process.exit(1)
})
