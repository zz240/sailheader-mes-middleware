/*
 * #102 真机 UI 验收：手工新增行、手工写目标字段。
 *
 *   第一段（任务 3 DBG-TS，写目标是真表 dbg_receipt，目标字段清单探得出来）：
 *     「+ 字段映射」加一行 → 点目标字段，**从清单里选**一个（下拉补全）→ 选来源 →
 *     再把这一行的目标字段**改成另一个清单字段** → 清除 → 撤销 → 保存，核对存下去的规则。
 *     这一段跑完把任务 3 原来的映射规则放回去（它是一条调试链）。
 *
 *   第二段（新任务 T4-UI-*，写目标是 HTTP，探不出目标结构 —— #98 H3 的保险）：
 *     目标清单是空的，全部**手输**：「+ 字段映射」×2、改目标字段、清除 + 撤销、
 *     「+ 数组映射」+ 选来源数组 + 「+ 行号」+ 「+ 子字段」×2，保存后拿这份规则跑穿真链路
 *     （读制品 → 映射 → HTTP mock），核对目标系统收到的请求体。
 *
 * 跑法（playwright 是本目录自带的，先 `npm i` 一次就行）：cd tools/ui && npm i && node manual-rows-102.cjs
 */
const fs = require('fs')
const http = require('http')
const path = require('path')
const { chromium } = require('playwright')

const APP = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080/api'
const HTTP_DATA_SOURCE_ID = 6
const MQ_DATA_SOURCE_ID = 3 // mq-local → amqp://localhost:5672
const T2_JOB_ID = 3
const MOCK_PORT = 9099
const logDir = path.join(process.env.TEMP || '.', 'edge-mock')
const logFile = path.join(logDir, 'manual-rows-ui-manual.log')
const shots = path.join(__dirname, 'shots')
const failures = []
// 失败也要收干净：mock 还听着端口时 node 的事件循环不会空，进程会挂在那儿不退（实测踩到过）。
let mock = null
let browser = null

function check(name, cond, detail = '') {
  console.log(`${cond ? 'ok  ' : 'NOT '} - ${name}${cond ? '' : ` :: ${detail}`}`)
  if (!cond) failures.push(name)
}

async function api(method, url, body) {
  const res = await fetch(`${API}${url}`, {
    method,
    headers: body ? { 'content-type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(60000),
  })
  const text = await res.text()
  if (!res.ok) throw new Error(`${method} ${url} → ${res.status} ${text.slice(0, 200)}`)
  return text ? JSON.parse(text) : null
}

function startMock() {
  fs.mkdirSync(logDir, { recursive: true })
  fs.writeFileSync(logFile, '')
  const server = http.createServer((req, res) => {
    let data = ''
    req.on('data', c => (data += c))
    req.on('end', () => {
      fs.appendFileSync(logFile, data + '\n')
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end('{"code":"0"}')
    })
  })
  return new Promise((resolve, reject) => {
    server.on('error', reject)
    server.listen(MOCK_PORT, '0.0.0.0', () => resolve(server))
  })
}

function mockBodies() {
  return fs.readFileSync(logFile, 'utf8').split('\n').filter(Boolean).map(l => JSON.parse(l))
}

/** 往 RabbitMQ 默认交换机发一条消息（管理 HTTP API，无需额外依赖）。 */
async function publish(queue, payload) {
  const auth = Buffer.from('guest:guest').toString('base64')
  const res = await fetch('http://localhost:15672/api/exchanges/%2F/amq.default/publish', {
    method: 'POST',
    headers: { authorization: `Basic ${auth}`, 'content-type': 'application/json' },
    body: JSON.stringify({
      properties: { content_type: 'application/json' },
      routing_key: queue,
      payload: JSON.stringify(payload),
      payload_encoding: 'string',
    }),
    signal: AbortSignal.timeout(15000),
  })
  return res.json()
}

/** 键序无关的 JSON 比较（后端的 toMap 会把键重排，规则的值才是契约）。 */
function stable(value) {
  if (Array.isArray(value)) return value.map(stable)
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().map(k => [k, stable(value[k])]))
  }
  return value
}

/** 打开某个任务的 FIELD_MAP 新版编辑器。 */
async function openEditor(page, jobName, { create = false } = {}) {
  await page.goto(`${APP}/jobs`, { waitUntil: 'networkidle' })
  const jobRow = page.locator('tbody tr', { hasText: jobName }).first()
  await jobRow.waitFor({ timeout: 20000 })
  await jobRow.getByRole('button', { name: '动作' }).click()
  await page.waitForSelector('text=动作链', { timeout: 20000 })
  await page.locator('tbody tr', { hasText: 'FIELD_MAP' }).first()
    .getByRole('button', { name: '编辑映射' }).click()
  await page.waitForSelector('.wb-row, .wb-empty', { timeout: 25000 })
  await page.waitForTimeout(600)
  if (create) return
}

;(async () => {
  fs.mkdirSync(shots, { recursive: true })
  mock = await startMock()
  browser = await chromium.launch()
  const page = await browser.newPage({ viewport: { width: 1680, height: 1000 } })
  page.setDefaultTimeout(20000)
  const pageErrors = []
  page.on('pageerror', e => pageErrors.push(e.message))

  /** 截下编辑器保存时 PUT 的那份动作（= 编辑器存下去的规则）。 */
  let saved = null
  page.on('request', req => {
    if (req.method() === 'PUT' && /\/api\/actions\/\d+$/.test(req.url())) {
      try { saved = JSON.parse(req.postData() ?? '{}') } catch { /* 忽略 */ }
    }
  })

  /** 按目标字段路径取一行（数组行带「+ 子字段」，据此和同名的普通行区分开）。 */
  async function rowByTarget(target, { array = false } = {}) {
    const all = page.locator('.wb-row')
    for (let i = 0; i < await all.count(); i++) {
      const row = all.nth(i)
      if (await row.locator('.wb-col-target input').inputValue() !== target) continue
      if (array && await row.getByRole('button', { name: '+ 子字段' }).count() === 0) continue
      return row
    }
    return null
  }
  const targetValues = () => page.locator('.wb-row .wb-col-target input').evaluateAll(els => els.map(e => e.value))
  const selectedTarget = () => page.locator('.wb-row.selected .wb-col-target input').first().inputValue()
  const selectedValue = async () => valueOf(page.locator('.wb-row.selected').first())
  /** 某一行的「值怎么取」那一栏读出来是什么（点来源之后选中行会自己往前走，所以按行断言，不按选中行）。 */
  const valueOf = async row => (await row.locator('.wb-col-value').first().innerText())
    .replace(/\s+/g, ' ').trim()

  /** 目标字段补全下拉里的候选项（点一下输入框就出来）。 */
  async function suggestionsOf(row) {
    await row.locator('.wb-col-target input').click()
    await page.waitForTimeout(400)
    const items = await page.locator('.el-autocomplete-suggestion li').allInnerTexts()
    return items.map(t => t.trim())
  }
  async function pickSuggestion(row, text) {
    await row.locator('.wb-col-target input').click()
    await page.waitForTimeout(400)
    await page.locator('.el-autocomplete-suggestion li').filter({ hasText: text }).first().click()
    await page.waitForTimeout(400)
  }

  /* ══════════════════════════════ 第一段：目标清单探得出来 ══════════════════════════════ */

  console.log('\n=== 第一段：清单里选目标字段（任务 3，写目标是真表） ===')
  const t2Actions = (await api('GET', `/jobs/${T2_JOB_ID}/actions`)).data
  const t2Map = t2Actions.find(a => a.type === 'FIELD_MAP')
  const t2RulesBefore = t2Map.addition?.rules ?? []

  await openEditor(page, 'DBG-TS')
  console.log('打开时已有的目标字段行:', JSON.stringify(await targetValues()))

  const beforeCount = await page.locator('.wb-row').count()
  await page.getByRole('button', { name: '+ 字段映射' }).click()
  await page.waitForTimeout(500)
  const afterAdd = await page.locator('.wb-row').count()
  check('「+ 字段映射」加出一行空的字段映射', afterAdd === beforeCount + 1, `${beforeCount} → ${afterAdd}`)
  check('新加的行是空的、并且已经选中', await selectedTarget() === '', await selectedTarget())

  const added = page.locator('.wb-row').last()
  const candidates = await suggestionsOf(added)
  console.log('目标字段补全清单:', JSON.stringify(candidates))
  check('点目标字段就把清单里的字段列出来（补全能用）',
    candidates.includes('vendor_name') && candidates.includes('material_name'), JSON.stringify(candidates))
  check('补全清单里没有数组字段混进来（普通行给标量字段）',
    !candidates.some(c => c.includes('bomLines')), JSON.stringify(candidates))

  await pickSuggestion(added, 'unit')
  check('从清单里选中的目标字段落到这一行上',
    await added.locator('.wb-col-target input').inputValue() === 'unit',
    await added.locator('.wb-col-target input').inputValue())
  check('清单里的字段不标「手写」', await added.locator('.wb-col-target .wb-chip').count() === 0)
  check('清单里没配过的字段行也有一份空映射（这不是这一行配出来的）', (await valueOf(added)).includes('还没选来源'),
    await valueOf(added))

  // 选来源：左边清单一组普通字段（点完选中会自己往前走，所以按行断言）
  await page.locator('.wb-source-item').filter({ hasText: 'order_no' }).first().click()
  await page.waitForTimeout(400)
  check('给这一行选上来源', (await valueOf(added)).includes('order_no'), await valueOf(added))

  // 已有行能改目标字段（再从清单里换一个：点一个已经填好的目标字段，清单要整个给出来）
  await pickSuggestion(added, 'vendor_name')
  check('已有行的目标字段能改（从清单里换一个）',
    await added.locator('.wb-col-target input').inputValue() === 'vendor_name',
    await added.locator('.wb-col-target input').inputValue())
  check('改完目标字段来源还在（改的是目标，不是整行）',
    (await valueOf(added)).includes('order_no'), await valueOf(added))

  // 清除 + 撤销
  await added.getByRole('button', { name: '清除' }).click()
  await page.waitForTimeout(400)
  check('清除后这一行的取值方式没了', (await valueOf(added)).includes('还没选来源'), await valueOf(added))
  check('清除后目标字段留着（清的是取值方式）',
    await added.locator('.wb-col-target input').inputValue() === 'vendor_name',
    await added.locator('.wb-col-target input').inputValue())
  const undoBtn = added.getByRole('button', { name: '撤销' })
  check('清除后这一行给出「撤销」入口', await undoBtn.count() === 1)
  await page.screenshot({ path: `${shots}/manual-rows-cleared.png` })
  await undoBtn.click()
  await page.waitForTimeout(400)
  check('撤销后来源回来了', (await valueOf(added)).includes('order_no'), await valueOf(added))
  check('撤销之后「撤销」入口收起来（这一行已经配回来了）',
    await added.getByRole('button', { name: '撤销' }).count() === 0)

  // 存档核对：手工加的行进了规则
  await page.getByRole('button', { name: '保存', exact: true }).click()
  const confirm = page.getByRole('button', { name: '只保存配好的' })
  if (await confirm.count()) await confirm.click()
  await page.waitForTimeout(2500)
  const t2SavedRules = saved?.addition?.rules ?? []
  // 手工加的那一行取的是 order_no；任务 3 原来那条 vendor_name 取的是 status，靠来源分得开。
  const manualRule = t2SavedRules.find(r => r.target === 'vendor_name' && r.source === 'order_no')
  check('手工加的行保存时进了规则', !!manualRule, JSON.stringify(t2SavedRules.map(r => [r.target, r.source])))
  check('手工加的行显式带 sourceStep', manualRule?.sourceStep !== undefined, JSON.stringify(manualRule))
  console.log('第一段存下去的规则:\n' + JSON.stringify(t2SavedRules, null, 2))

  // 把任务 3 的映射规则放回去（它是调试链，后面还要用）
  await api('PUT', `/actions/${t2Map.id}`, { ...t2Map, addition: { ...t2Map.addition, rules: t2RulesBefore } })
  const restored = (await api('GET', `/jobs/${T2_JOB_ID}/actions`)).data.find(a => a.type === 'FIELD_MAP')
  check('第一段跑完把任务 3 原来的规则放回去了',
    JSON.stringify(stable(restored.addition.rules)) === JSON.stringify(stable(t2RulesBefore)))

  /* ══════════════════════════════ 第二段：目标清单探不出来，全手输 ══════════════════════════════ */

  console.log('\n=== 第二段：目标清单是空的，全部手输（新任务，写目标是 HTTP） ===')
  const jobName = `T4-UI-${Date.now()}`
  const jobId = (await api('POST', '/jobs', {
    siteId: 1, name: jobName, direction: 'NORTH', scheduleType: 'NONE', status: 0,
  })).data.id
  console.log('任务:', jobId, jobName)

  await api('POST', `/jobs/${jobId}/actions`, {
    name: 'read', type: 'READ_SQL', index: 1,
    addition: {
      sql: 'select j->>\'$.order_no\' as order_no,'
        + ' j->>\'$.status\' as status,'
        + ' j->>\'$.productName\' as productName,'
        + ' j->>\'$.items[0].materialCode\' as `items[0].materialCode`,'
        + ' j->>\'$.items[0].qty\' as `items[0].qty`,'
        + ' j->\'$.items\' as items'
        + ' from (select cast(\'{"order_no":"DBG-9001","status":"ACTIVE","productName":"前保险杠","items":['
        + '{"materialCode":"MAT-718H","qty":1},{"materialCode":"MAT-SKD61","qty":24}]}\' as json) as j) j',
      dataSourceId: 1, businessKey: 'order_no',
    },
  })
  // 后端要求字段映射动作至少有规则，所以先放一条；目标清单仍然是空的（HTTP 目标探不出结构，#98 H3），
  // 剩下的行全部在界面上手工加出来。
  await api('POST', `/jobs/${jobId}/actions`, {
    name: 'map', type: 'FIELD_MAP', index: 2,
    addition: { rules: [{ source: 'order_no', target: 'bom.sku', transform: 'STRING', sourceStep: 1 }] },
  })
  await api('POST', `/jobs/${jobId}/actions`, {
    name: 'push', type: 'HTTP_CALL', index: 3,
    addition: { dataSourceId: HTTP_DATA_SOURCE_ID, path: '/mock/mes/manual', method: 'POST' },
  })
  await api('PUT', `/jobs/${jobId}/status`, { status: 1 })
  // 先跑一次：左边来源清单取的是「动作 1 最近一次成功运行的输出」，没有它清单是空的。
  await api('POST', `/jobs/${jobId}/execute`, {})
  await page.waitForTimeout(1500)

  saved = null
  await openEditor(page, jobName)
  check('写目标动作探不出结构时编辑器说清目标字段要手工填',
    (await page.locator('.wb-rows-head .wb-panel-tip').first().innerText()).includes('手工导入'),
    await page.locator('.wb-rows-head .wb-panel-tip').first().innerText())
  const seededRows = await targetValues()
  check('打开时只有已存的那一行（没有目标清单，行都得自己加）',
    JSON.stringify(seededRows) === JSON.stringify(['bom.sku']), JSON.stringify(seededRows))
  await page.locator('.wb-row').first().locator('.wb-col-target input').click()
  await page.waitForTimeout(500)
  check('目标清单是空的：点目标字段没有补全项（只能手输）',
    await page.locator('.el-autocomplete-suggestion li').count() === 0,
    JSON.stringify(await page.locator('.el-autocomplete-suggestion li').allInnerTexts()))
  await page.keyboard.press('Escape')

  /** 手输目标字段：先清空再逐字敲进去（走的是真的键盘输入，不是直接改数据）。 */
  async function typeTarget(row, text) {
    const input = row.locator('.wb-col-target input')
    await input.click()
    await input.fill('')
    await input.type(text, { delay: 10 })
    await page.keyboard.press('Escape') // 关掉补全下拉（清单空时下拉没开，Esc 不能把编辑器关掉）
    await page.waitForTimeout(300)
  }

  // 1) + 字段映射：手输目标字段（清单里没有）
  await page.getByRole('button', { name: '+ 字段映射' }).click()
  await page.waitForTimeout(400)
  const nameRow = page.locator('.wb-row').last()
  await typeTarget(nameRow, 'bom.name')
  check('手输目标字段时按 Esc 不会把编辑器关掉（草稿还在）',
    await page.locator('.mapping-workbench-drawer').count() === 1)
  check('手输一个清单里没有的目标字段路径（清单本来就是空的）',
    await nameRow.locator('.wb-col-target input').inputValue() === 'bom.name',
    await nameRow.locator('.wb-col-target input').inputValue())
  check('手输的目标字段标着「手写」', await nameRow.locator('.wb-col-target .wb-chip').count() === 1)
  await page.locator('.wb-source-item').filter({ hasText: 'status' }).first().click()
  await page.waitForTimeout(400)
  check('手输的字段配上来源', (await valueOf(nameRow)).includes('status'), await valueOf(nameRow))

  // 2) 清除 + 撤销（在刚配好的这一行上）
  await nameRow.getByRole('button', { name: '清除' }).click()
  await page.waitForTimeout(400)
  check('清除后这一行空着', (await valueOf(nameRow)).includes('还没选来源'), await valueOf(nameRow))
  check('清除不动目标字段', await nameRow.locator('.wb-col-target input').inputValue() === 'bom.name',
    await nameRow.locator('.wb-col-target input').inputValue())
  await nameRow.getByRole('button', { name: '撤销' }).click()
  await page.waitForTimeout(400)
  check('撤销把这一行配回来', (await valueOf(nameRow)).includes('status'), await valueOf(nameRow))

  // 3) + 数组映射：手输数组路径，来源选一个数组
  await page.getByRole('button', { name: '+ 数组映射' }).click()
  await page.waitForTimeout(400)
  await typeTarget(page.locator('.wb-row').last(), 'bom.bomLines')
  /** 手工加的数组行：按目标字段找（加了子行之后 `.last()` 就不是它了）。 */
  const arrayRowOf = async () => {
    const row = await rowByTarget('bom.bomLines', { array: true })
    if (!row) throw new Error('找不到手工加的数组行')
    return row
  }
  const linesRow = await arrayRowOf()
  check('手工加的数组行目标写数组路径', await selectedTarget() === 'bom.bomLines', await selectedTarget())
  await linesRow.locator('.wb-chip.chip-src').first().click()
  await page.waitForTimeout(400)
  check('选中数组行时左边清单只给数组',
    (await page.locator('.wb-source-group-title').allInnerTexts()).join(',') === '数组',
    (await page.locator('.wb-source-group-title').allInnerTexts()).join(','))
  await page.locator('.wb-source-item').first().click()
  await page.waitForTimeout(400)
  check('给手工加的数组行选上来源数组', (await valueOf(linesRow)).includes('items'), await valueOf(linesRow))

  // 4) 数组行下面 + 行号 / + 子字段
  await (await arrayRowOf()).getByRole('button', { name: '+ 行号' }).click()
  await page.waitForTimeout(400)
  const lineNoRow = page.locator('.wb-row.child').last()
  await typeTarget(lineNoRow, 'lineNo')
  check('「+ 行号」加出来的子行自己做行号', (await valueOf(lineNoRow)).includes('行号'), await valueOf(lineNoRow))

  await (await arrayRowOf()).getByRole('button', { name: '+ 子字段' }).click()
  await page.waitForTimeout(400)
  const itemCodeRow = page.locator('.wb-row.child').last()
  await typeTarget(itemCodeRow, 'itemCode')
  await itemCodeRow.locator('.wb-chip.chip-src').first().click()
  await page.waitForTimeout(400)
  check('子行的来源清单开头是「数组元素内」',
    (await page.locator('.wb-source-group-title').first().innerText()).trim() === '数组元素内',
    await page.locator('.wb-source-group-title').first().innerText())
  await page.locator('.wb-source-group').first().locator('.wb-source-item').filter({ hasText: 'materialCode' })
    .first().click()
  await page.waitForTimeout(400)
  check('子行按相对路径取数组元素里的字段', (await valueOf(itemCodeRow)).includes('materialCode'),
    await valueOf(itemCodeRow))

  await (await arrayRowOf()).getByRole('button', { name: '+ 子字段' }).click()
  await page.waitForTimeout(400)
  const qtyRow = page.locator('.wb-row.child').last()
  await typeTarget(qtyRow, 'qty')
  await page.locator('.wb-source-group').first().locator('.wb-source-item').filter({ hasText: 'qty' })
    .first().click()
  await page.waitForTimeout(400)
  check('第二个子字段也配上了（数字字段自动套数字转换）', (await valueOf(qtyRow)).includes('qty'), await valueOf(qtyRow))

  await page.screenshot({ path: `${shots}/manual-rows-manual.png` })
  const finalTargets = await targetValues()
  console.log('手工配出来的行:', JSON.stringify(finalTargets))
  check('手工配出来的行都在界面上',
    JSON.stringify(finalTargets) === JSON.stringify(['bom.sku', 'bom.name', 'bom.bomLines', 'lineNo', 'itemCode', 'qty']),
    JSON.stringify(finalTargets))

  const editorText = await page.locator('.wb').innerText()
  check('界面上没有 $ 符号', !editorText.includes('$'))

  // 5) 保存并核对存下去的规则
  saved = null
  await page.getByRole('button', { name: '保存', exact: true }).click()
  const confirm2 = page.getByRole('button', { name: '只保存配好的' })
  if (await confirm2.count()) await confirm2.click()
  await page.waitForTimeout(2500)
  check('保存动作发出去了', saved !== null)
  const rules = saved?.addition?.rules ?? []
  console.log('第二段存下去的规则:\n' + JSON.stringify(rules, null, 2))
  check('手输的两行进了规则', rules.map(r => r.target).join(',') === 'bom.sku,bom.name,bom.bomLines',
    JSON.stringify(rules.map(r => r.target)))
  const manualLines = rules.find(r => r.target === 'bom.bomLines')
  check('手工加的数组行是 iterate=FOR_EACH 且带 sourceStep',
    manualLines?.iterate === 'FOR_EACH' && manualLines?.sourceStep === 1,
    JSON.stringify({ iterate: manualLines?.iterate, sourceStep: manualLines?.sourceStep }))
  check('数组行的来源是选中的那个数组', manualLines?.source === 'items', JSON.stringify(manualLines?.source))
  check('子规则四条：行号 + 三个元素字段',
    (manualLines?.children ?? []).map(c => c.target).join(',') === 'lineNo,itemCode,qty',
    JSON.stringify((manualLines?.children ?? []).map(c => c.target)))
  check('行号子规则 source 是 $index、起始值 1',
    manualLines?.children?.[0]?.source === '$index' && manualLines?.children?.[0]?.startAt === 1,
    JSON.stringify(manualLines?.children?.[0]))
  check('子规则一个都不带 sourceStep',
    (manualLines?.children ?? []).every(c => c.sourceStep === undefined),
    JSON.stringify((manualLines?.children ?? []).map(c => c.sourceStep)))
  check('顶层规则都显式带 sourceStep', rules.every(r => r.sourceStep !== undefined),
    JSON.stringify(rules.map(r => r.sourceStep)))

  // 6) 拿这份规则跑穿真链路：读制品 → 映射 → HTTP mock
  const persisted = (await api('GET', `/jobs/${jobId}/actions`)).data.find(a => a.type === 'FIELD_MAP')
  check('落库的规则与界面存下去的一致（键序不算）',
    JSON.stringify(stable(persisted?.addition?.rules)) === JSON.stringify(stable(rules)),
    JSON.stringify(persisted?.addition?.rules))

  fs.writeFileSync(logFile, '')
  const verifyName = `T4-LIVE-${Date.now()}`
  const verifyQueue = `t4.ui.live.${Date.now()}`
  const verifyId = (await api('POST', '/jobs', {
    siteId: 1, name: verifyName, direction: 'SOUTH', scheduleType: 'NONE', status: 0,
  })).data.id
  await api('POST', `/jobs/${verifyId}/actions`, {
    name: 'consume', type: 'MQ_CONSUME', index: 1,
    addition: { dataSourceId: MQ_DATA_SOURCE_ID, queue: verifyQueue, businessKey: 'order_no' },
  })
  // 这一份规则就是编辑器刚存下去的那一份
  await api('POST', `/jobs/${verifyId}/actions`, { name: 'map', type: 'FIELD_MAP', index: 2, addition: { rules } })
  await api('POST', `/jobs/${verifyId}/actions`, {
    name: 'push', type: 'HTTP_CALL', index: 3,
    addition: { dataSourceId: HTTP_DATA_SOURCE_ID, path: '/mock/mes/manual', method: 'POST' },
  })
  await api('PUT', `/jobs/${verifyId}/status`, { status: 1 })
  await new Promise(r => setTimeout(r, 2500))

  // 从 MQ 发一条真 JSON（数组是真数组）。走 READ_SQL 读 MySQL 的 JSON 列不行：JDBC 交给映射的是
  // 字符串，`$map` 会把整串当成一个元素（docs/features/field-mapping/README.md 里记着这条）。
  await publish(verifyQueue, {
    order_no: 'DBG-9002', status: 'ACTIVE',
    items: [{ materialCode: 'MAT-718H', qty: 1 }, { materialCode: 'MAT-SKD61', qty: 24 }],
  })
  for (let i = 0; i < 30 && mockBodies().length === 0; i++) await new Promise(r => setTimeout(r, 500))
  const body = mockBodies()[0] ?? {}
  console.log('目标系统收到的请求体:\n' + JSON.stringify(body, null, 2))
  check('目标系统收到了手工配出来的 bom', body.bom?.sku === 'DBG-9002', JSON.stringify(body.bom))
  check('手输的第二个字段也对', body.bom?.name === 'ACTIVE', JSON.stringify(body.bom?.name))
  const lines = Array.isArray(body.bom?.bomLines) ? body.bom.bomLines : []
  check('数组行收成两行', lines.length === 2, JSON.stringify(lines))
  check('子字段都取到了值',
    lines.every(l => l.itemCode && l.qty != null), JSON.stringify(lines))
  check('行号从 1 起（手工加的 + 行号）',
    lines[0]?.lineNo === 1 && lines[1]?.lineNo === 2, JSON.stringify(lines.map(l => l.lineNo)))
  check('两行的值都对',
    lines[0]?.itemCode === 'MAT-718H' && lines[1]?.itemCode === 'MAT-SKD61'
    && lines[0]?.qty === 1 && lines[1]?.qty === 24,
    JSON.stringify(lines))
  console.log(`# 真链路任务 id=${verifyId}（${verifyName}，队列 ${verifyQueue}）`)

  await browser.close()
  console.log(`\n# ${failures.length ? `${failures.length} 条不通过: ${failures.join(' / ')}` : '全部通过'}`)
  if (pageErrors.length) console.log('页面异常:', pageErrors.join(' | '))
  console.log(`# 第二段任务 id=${jobId}，截图 ${shots}/manual-rows-manual.png / ${shots}/manual-rows-cleared.png`)
  mock.close()
  process.exit(failures.length ? 1 : 0)
})().catch(async e => {
  console.error('FAILED:', e.message)
  try { await browser?.close() } catch { /* 关不掉就算了 */ }
  try { mock?.close() } catch { /* 同上 */ }
  process.exit(1)
})
