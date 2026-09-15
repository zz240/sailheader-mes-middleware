/*
 * #101 真机 UI 验收：用真浏览器在新版编辑器里把「制品 → BOM」的层级映射配出来，并跑穿真链路看目标系统收到什么。
 *
 *   1. 建一条链：读制品（表头字段 + 一个真数组 items）→ FIELD_MAP → HTTP_CALL 打到本地 mock
 *   2. 打开新版编辑器，在界面上改数组行的来源数组、给子行选相对路径、给子行选「外层 · 」字段
 *   3. 保存（截下 PUT 的请求体 = 编辑器存下去的规则），再从后端读一遍落库的规则
 *   4. 拿这份**编辑器存下来的**规则另起一条真链路，核对目标系统收到的 bomLines 每一行五个字段
 *
 * 目标结构按票里的约定：表头三个字段 + bom.bomLines 数组，元素里五个字段
 * （lineNo 行号 / itemCode / itemName / qty / unit），行号从 1 开始。
 *
 * 前置数据：customer_db.dbg_product（见 seed/t3-source-table.sql）的列名写成
 * `items[0].materialCode` 这种形状，结构探测读出来就是「表头字段 + 一个数组的元素字段」。
 *
 * 跑法（playwright 是本目录自带的，先 `npm i` 一次就行）：cd tools/ui && npm i && node array-mapping-101.cjs
 */
const fs = require('fs')
const http = require('http')
const path = require('path')
const { chromium } = require('playwright')

const APP = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080/api'
const HTTP_DATA_SOURCE_ID = 6
const MQ_DATA_SOURCE_ID = 3 // mq-local → amqp://localhost:5672
const MOCK_PORT = 9099
const SAMPLE_ITEMS = [
  { materialCode: 'MAT-718H', materialName: '718H模具钢', qty: 1, unit: 'PCS' },
  { materialCode: 'MAT-SKD61', materialName: 'SKD61', qty: 24, unit: 'SET' },
]
const logDir = path.join(process.env.TEMP || '.', 'edge-mock')
const logFile = path.join(logDir, 'array-mapping-ui-bom.log')
const shots = path.join(__dirname, 'shots')
const failures = []

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

/** 键序无关的 JSON 比较（后端的 toMap 会把键重排，规则的值才是契约）。 */
function stable(value) {
  if (Array.isArray(value)) return value.map(stable)
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().map(k => [k, stable(value[k])]))
  }
  return value
}

/** 拿到这次的规则：与编辑器存下去的那一份比对用。 */
;(async () => {
  fs.mkdirSync(shots, { recursive: true })
  const mock = await startMock()

  const jobName = `T3-UI-${Date.now()}`
  const jobId = (await api('POST', '/jobs', {
    siteId: 1, name: jobName, direction: 'NORTH', scheduleType: 'NONE', status: 0,
  })).data.id
  console.log('任务:', jobId, jobName)

  await api('POST', `/jobs/${jobId}/actions`, {
    name: 'read', type: 'READ_SQL', index: 1,
    // 源是一条真正的制品 JSON（表头 + items 数组）：列名与 JSON 里的键同名，
    // `items[0].*` 那几个别名让左边来源清单认得出「数组元素内的字段」。
    //
    // 注意：items 必须是 JSON_ARRAY 出来的真数组。直接读 MySQL 的 JSON 列不行——
    // JDBC 交给映射的是字符串（本机实测），源那边就不是数组，那是源的问题不是字段映射的问题。
    addition: {
      sql: 'select j->>\'$.order_no\' as order_no,'
        + ' j->>\'$.status\' as status,'
        + ' j->>\'$.productName\' as productName,'
        + ' j->>\'$.items[0].materialCode\' as `items[0].materialCode`,'
        + ' j->>\'$.items[0].qty\' as `items[0].qty`,'
        + ' j->>\'$.items[0].unit\' as `items[0].unit`,'
        + ' j->>\'$.items[1].materialCode\' as `items[1].materialCode`,'
        + ' j->\'$.items\' as items'
        + ' from (select cast(\'{"order_no":"DBG-9001","status":"ACTIVE","productName":"前保险杠","items":['
        + '{"materialCode":"MAT-718H","qty":1,"unit":"PCS"},'
        + '{"materialCode":"MAT-SKD61","qty":24,"unit":"SET"}]}\' as json) as j) j',
      dataSourceId: 1, businessKey: 'order_no',
    },
  })
  // 已存配置先放一份「数组行 + 五个子行的空壳」：HTTP 目标探不出结构（#98 H3），
  // 数组行得靠已存配置回填出来；手工新增行是 T4 #102。
  await api('POST', `/jobs/${jobId}/actions`, {
    name: 'map', type: 'FIELD_MAP', index: 2,
    addition: {
      rules: [
        { source: 'order_no', target: 'bom.sku', transform: 'STRING', sourceStep: 1 },
        { source: 'status', target: 'bom.name', transform: 'STRING', sourceStep: 1 },
        {
          sourceStep: 1, source: 'items', target: 'bom.bomLines', transform: 'STRING', iterate: 'FOR_EACH',
          children: [
            { source: '$index', target: 'lineNo', transform: 'INDEX', startAt: 1 },
            { source: 'materialCode', target: 'itemCode', transform: 'STRING' },
            { source: 'productName', target: 'itemName', transform: 'STRING' },
            { source: 'qty', target: 'qty', transform: 'INT' },
            { source: 'unit', target: 'unit', transform: 'ENUM', enumMap: { PCS: '件', SET: '套' } },
          ],
        },
      ],
    },
  })
  await api('POST', `/jobs/${jobId}/actions`, {
    name: 'push', type: 'HTTP_CALL', index: 3,
    addition: { dataSourceId: HTTP_DATA_SOURCE_ID, path: '/mock/mes/bom', method: 'POST' },
  })
  await api('PUT', `/jobs/${jobId}/status`, { status: 1 })
  // 先跑一次：左边来源清单取的是「动作 1 最近一次成功运行的输出」，没有它清单是空的。
  await api('POST', `/jobs/${jobId}/execute`, {})

  const browser = await chromium.launch()
  const page = await browser.newPage({ viewport: { width: 1680, height: 1000 } })
  const pageErrors = []
  page.on('pageerror', e => pageErrors.push(e.message))
  let saved = null
  page.on('request', req => {
    if (req.method() === 'PUT' && /\/api\/actions\/\d+$/.test(req.url())) {
      try { saved = JSON.parse(req.postData() ?? '{}') } catch { /* 忽略 */ }
    }
  })

  await page.goto(`${APP}/jobs`, { waitUntil: 'networkidle' })
  const jobRow = page.locator('tbody tr', { hasText: jobName }).first()
  await jobRow.waitFor({ timeout: 20000 })
  await jobRow.getByRole('button', { name: '动作' }).click()
  await page.waitForSelector('text=动作链', { timeout: 20000 })
  await page.locator('tbody tr', { hasText: 'FIELD_MAP' }).first()
    .getByRole('button', { name: '编辑映射' }).click()
  await page.waitForSelector('.wb-row', { timeout: 25000 })
  await page.waitForTimeout(600)
  console.log('编辑器打开了')

  /** 按目标字段路径找一行（数组行带「+ 子字段」按钮，据此和同名的普通行区分开）。 */
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
  const selectedTarget = () => page.locator('.wb-row.selected .wb-col-target input').first().inputValue()
  const selectedValue = async () => (await page.locator('.wb-row.selected .wb-col-value').first().innerText())
    .replace(/\s+/g, ' ').trim()

  const rowTexts = await page.locator('.wb-row .wb-col-target input').evaluateAll(els => els.map(e => e.value))
  console.log('目标字段行:', JSON.stringify(rowTexts))
  const arrayRow = await rowByTarget('bom.bomLines', { array: true })
  check('已存配置里的数组行回填出来了', !!arrayRow, JSON.stringify(rowTexts))
  check('数组的子字段按相对路径铺开',
    ['lineNo', 'itemCode', 'itemName', 'qty', 'unit'].every(t => rowTexts.includes(t)), JSON.stringify(rowTexts))

  // 1. 数组行：左边清单只给数组，点一下就填进这一行
  //    点行里的来源那块（不是行的正中间——空块塌成一小撮时「+ 子字段」会挪到正中间）
  await arrayRow.locator('.wb-chip.chip-src').first().click()
  await page.waitForTimeout(300)
  check('选中了数组行', await selectedTarget() === 'bom.bomLines', await selectedTarget())
  const sourcePanel = await page.locator('.wb-source').innerText()
  console.log('--- 数组行时的来源清单 ---\n' + sourcePanel)
  check('数组行时清单只给数组一组',
    (await page.locator('.wb-source-group-title').allInnerTexts()).join(',') === '数组',
    (await page.locator('.wb-source-group-title').allInnerTexts()).join(','))
  const arrayItem = page.locator('.wb-source-item').first()
  check('清单里那一项标着「数组」', (await arrayItem.innerText()).includes('数组'), await arrayItem.innerText())
  await arrayItem.click()
  await page.waitForTimeout(400)
  const arrayRowValue = await arrayRow.locator('.wb-col-value').innerText()
  check('数组行读出来是「动作 1 · read · items」', arrayRowValue.includes('items'), arrayRowValue)
  check('选完来源数组后自动落到第一个子行（接着填元素字段）',
    await selectedTarget() === 'lineNo', await selectedTarget())

  // 2. 分组标题「数组元素内」（票里要求分组标题写明）
  check('分组标题写着「数组元素内」',
    (await page.locator('.wb-group-title').first().innerText()).trim() === '数组元素内',
    await page.locator('.wb-group-title').first().innerText())

  // 3. 子行：来源默认按相对路径解析，清单给的是相对路径
  const childCount = await page.locator('.wb-row.child').count()
  check('数组行下面铺出了五个子行', childCount === 5, `只有 ${childCount} 行`)
  const childTargets = await page.locator('.wb-row.child .wb-col-target input').evaluateAll(els => els.map(e => e.value))
  console.log('子行:', JSON.stringify(childTargets))
  check('子行的目标字段是相对路径', childTargets.every(t => !t.includes('.') && !t.includes('[')),
    JSON.stringify(childTargets))

  const itemCodeRow = page.locator('.wb-row.child').nth(childTargets.indexOf('itemCode'))
  await itemCodeRow.locator('.wb-chip.chip-src').first().click()
  await page.waitForTimeout(300)
  const elementGroup = page.locator('.wb-source-group').first()
  const elementText = await elementGroup.innerText()
  console.log('--- 子行的「数组元素内」清单 ---\n' + elementText)
  check('子行的清单里是相对路径（不带数组前缀）',
    elementText.includes('materialCode') && !elementText.includes('[0].materialCode'), elementText)
  await elementGroup.locator('.wb-source-item').first().click()
  await page.waitForTimeout(300)
  check('子行填上相对路径', (await selectedValue()).includes('materialCode'), await selectedValue())

  // 4. 子行引用数组外层的表头字段：界面上显示成「外层 · 字段名」
  const itemNameRow = page.locator('.wb-row.child').nth(childTargets.indexOf('itemName'))
  await itemNameRow.locator('.wb-chip.chip-src').first().click()
  await page.waitForTimeout(300)
  const outerGroup = page.locator('.wb-source-group').nth(1)
  const outerText = await outerGroup.innerText()
  console.log('--- 子行的「外层」清单 ---\n' + outerText)
  check('外层清单里的字段显示成「外层 · 字段名」', outerText.includes('外层 · productName'), outerText)
  await outerGroup.locator('.wb-source-item', { hasText: '外层 · productName' }).first().click()
  await page.waitForTimeout(300)
  check('子行读出来是「外层 · productName」（界面上不出现 $root）',
    (await selectedValue()).startsWith('外层 · productName'), await selectedValue())

  await page.screenshot({ path: `${shots}/array-mapping-editor.png` })

  // 5. 展开 / 收起
  await page.locator('.wb-chip.chip-toggle').first().click()
  await page.waitForTimeout(300)
  check('收起后子行不铺出来', await page.locator('.wb-row.child').count() === 0,
    `还有 ${await page.locator('.wb-row.child').count()} 行`)
  await page.locator('.wb-chip.chip-toggle').first().click()
  await page.waitForTimeout(300)
  check('再展开又回来了', await page.locator('.wb-row.child').count() === childCount)

  // 6. 加一个子行
  await arrayRow.locator('.wb-chip.chip-src').first().click()
  await page.waitForTimeout(300)
  await page.locator('.wb-row.selected').getByRole('button', { name: '+ 子字段' }).click()
  await page.waitForTimeout(300)
  check('往数组行下面加了一个子行', await page.locator('.wb-row.child').count() === childCount + 1,
    `${childCount} → ${await page.locator('.wb-row.child').count()}`)
  check('新子行是空的（目标字段写相对路径，所以从空开始）', await selectedTarget() === '', await selectedTarget())

  // 7. 界面上不该出现 $（保存会关掉抽屉，所以先读下来）
  const editorText = await page.locator('.wb').innerText()
  check('界面上没有 $ 符号', !editorText.includes('$'))

  // 8. 保存并核对存下去的规则
  await page.getByRole('button', { name: '保存', exact: true }).click()
  const confirm = page.getByRole('button', { name: '只保存配好的' })
  if (await confirm.count()) await confirm.click()
  await page.waitForTimeout(2500)
  check('保存动作发出去了', saved !== null)
  const rules = saved?.addition?.rules ?? []
  console.log('存下去的规则:\n' + JSON.stringify(rules, null, 2))
  const bomLines = rules.find(r => r.target === 'bom.bomLines')
  check('数组行带 sourceStep', bomLines?.sourceStep === 1, JSON.stringify(bomLines?.sourceStep))
  check('数组行是 iterate=FOR_EACH 且来源是来源数组',
    bomLines?.iterate === 'FOR_EACH' && bomLines?.source === 'items',
    JSON.stringify({ iterate: bomLines?.iterate, source: bomLines?.source }))
  check('子规则一个都不带 sourceStep',
    (bomLines?.children ?? []).every(c => c.sourceStep === undefined),
    JSON.stringify((bomLines?.children ?? []).map(c => c.sourceStep)))
  check('子规则五条：行号 + 三条元素字段 + 一条外层引用',
    (bomLines?.children ?? []).length === 5, JSON.stringify((bomLines?.children ?? []).map(c => c.target)))
  check('外层引用存成 $root. 前缀（界面上没有 $root）',
    (bomLines?.children ?? []).some(c => String(c.source).startsWith('$root.')),
    JSON.stringify((bomLines?.children ?? []).map(c => c.source)))
  check('顶层规则都显式带 sourceStep', rules.every(r => r.sourceStep !== undefined),
    JSON.stringify(rules.map(r => r.sourceStep)))

  await browser.close()

  // 9. 库里存下来的到底是什么（浏览器走的是保存接口，这里再从后端读一遍）
  const persisted = (await api('GET', `/jobs/${jobId}/actions`)).data.find(a => a.type === 'FIELD_MAP')
  check('落库的规则与界面存下去的一致（键序不算）',
    JSON.stringify(stable(persisted?.addition?.rules)) === JSON.stringify(stable(rules)),
    JSON.stringify(persisted?.addition?.rules))

  // 10. 拿这份规则跑穿一条真链路：MQ 制品 → FIELD_MAP → HTTP，核对目标系统收到的 bomLines
  if (saved) {
    const verifyName = `T3-LIVE-${Date.now()}`
    const verifyQueue = `t3.ui.live.${Date.now()}`
    const verifyId = (await api('POST', '/jobs', {
      siteId: 1, name: verifyName, direction: 'SOUTH', scheduleType: 'NONE', status: 0,
    })).data.id
    await api('POST', `/jobs/${verifyId}/actions`, {
      name: 'consume', type: 'MQ_CONSUME', index: 1,
      addition: { dataSourceId: MQ_DATA_SOURCE_ID, queue: verifyQueue, businessKey: 'order_no' },
    })
    // 这一份规则就是编辑器刚存下去的那一份
    await api('POST', `/jobs/${verifyId}/actions`, {
      name: 'map', type: 'FIELD_MAP', index: 2, addition: { rules },
    })
    await api('POST', `/jobs/${verifyId}/actions`, {
      name: 'push', type: 'HTTP_CALL', index: 3,
      addition: { dataSourceId: HTTP_DATA_SOURCE_ID, path: '/mock/mes/bom', method: 'POST' },
    })
    await api('PUT', `/jobs/${verifyId}/status`, { status: 1 })
    await new Promise(r => setTimeout(r, 2500))

    fs.writeFileSync(logFile, '')
    const again = await publish(verifyQueue, {
      order_no: 'DBG-9002', status: 'ACTIVE', productName: '前保险杠', items: SAMPLE_ITEMS,
    })
    console.log('发一条制品消息:', JSON.stringify(again))
    for (let i = 0; i < 30 && mockBodies().length === 0; i++) await new Promise(r => setTimeout(r, 500))
    const bom = mockBodies()[0]?.bom ?? {}
    const lines = Array.isArray(bom.bomLines) ? bom.bomLines : []
    console.log('目标系统收到的 bomLines:\n' + JSON.stringify(lines, null, 2))
    check('来源两个元素 → bomLines 两行', lines.length === 2, `只有 ${lines.length} 行`)
    check('每行五个字段都在',
      lines.every(l => ['lineNo', 'itemCode', 'itemName', 'qty', 'unit'].every(k => k in l)),
      JSON.stringify(lines))
    check('行号从 1 开始（1、2）',
      lines[0]?.lineNo === 1 && lines[1]?.lineNo === 2, JSON.stringify(lines.map(l => l.lineNo)))
    check('第一行五个字段都对',
      lines[0]?.itemCode === 'MAT-718H' && lines[0]?.itemName === '前保险杠'
        && lines[0]?.qty === 1 && lines[0]?.unit === '件',
      JSON.stringify(lines[0]))
    check('第二行五个字段都对',
      lines[1]?.itemCode === 'MAT-SKD61' && lines[1]?.itemName === '前保险杠'
        && lines[1]?.qty === 24 && lines[1]?.unit === '套',
      JSON.stringify(lines[1]))
    console.log(`# 真链路任务 id=${verifyId}（${verifyName}）`)
  }

  console.log(`\n# ${failures.length ? `${failures.length} 条不通过: ${failures.join(' / ')}` : '全部通过'}`)
  if (pageErrors.length) console.log('页面异常:', pageErrors.join(' | '))
  console.log(`# 任务 id=${jobId}，截图 ${shots}/array-mapping-editor.png`)
  mock.close()
  if (failures.length) process.exitCode = 1
})().catch(e => {
  console.error('FAILED:', e.message)
  process.exitCode = 1
})
