/*
 * #118 真机 UI 验收：目标字段清单多一条路——「粘贴 JSON」与「重新探测目标字段」并列。
 *
 * 用任务 3（DBG-TS，FIELD_MAP 动作 8，写目标是真表 dbg_receipt）：它的目标字段探得出来，边界才有得比。
 *   1. 目标那一片（行列表头）两个入口都在，且都在「重新探测目标字段」原来的位置上；
 *   2. 「粘贴 JSON」打开那个框，框里先写明「必填推不出来，一律当不必填」与「是数组就按第 1 条推」；
 *   3. 粘一份带数组的目标数据 → 数组折成一行数组映射加子行；目标字段就是粘进来的那些；
 *   4. 那一行小字写出来：必填推不出来（一律当不必填）+ 数组按第 1 条推；
 *   5. 目标那句说明写明「按粘的样例推的」（不再冒充连库探测那一个）；
 *   6. 已经配好的行没被冲掉：同名的行沿用原来的来源，名字不在粘的清单里的行原样留着；
 *   7. 「重新探测目标字段」把清单换回连库那份，小字与说明都跟着回去（探测那条路一个字没变）；
 *   8. 全程不保存：重开编辑器什么都不留。
 *
 * 脚本会自己读当前的目标字段名来造那份数据（不依赖某个固定内容）。
 *
 * 跑法（playwright 是本目录自带的，先 `npm i` 一次就行）：
 *   cd tools/ui && npm i && node target-fields-118.cjs
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

function skip(name, why) {
  console.log(`SKIP - ${name} :: ${why}`)
}

/**
 * 后端还没这一版时顶上去的那份展开（与 `StructureProbeService.probeSample` 同一个口径：
 * 对象按点号接路径、数组取第 1 条并加 `[0]` 段、类型粗推断、nullable 一律 true）。
 * 真推断的断言在后端用例里；这里只是让界面那一半照跑。
 */
function expand(sample, prefix = '') {
  const fields = []
  if (Array.isArray(sample)) {
    if (sample.length) fields.push(...expand(sample[0], `${prefix}[0]`))
    return fields
  }
  if (sample && typeof sample === 'object') {
    for (const [key, value] of Object.entries(sample)) {
      fields.push(...expand(value, prefix ? `${prefix}.${key}` : key))
    }
    return fields
  }
  const type = sample === null ? 'NULL' : typeof sample === 'number' ? 'NUMBER'
    : typeof sample === 'boolean' ? 'BOOLEAN' : 'STRING'
  return [{ name: prefix, type, nullable: true, samples: sample === null ? [] : [sample] }]
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

/** 现在行列表上铺出来的目标字段（每一行的目标字段输入框）。 */
async function targetNames(page) {
  return (await page.locator('.wb-row .wb-col-target input').evaluateAll(els => els.map(e => e.value)))
    .map(v => v.trim())
}

/**
 * 某个目标字段所在的那一行。
 *
 * 按 `data-row-id` 找，不按 `input[value=...]`：`v-model` 只把值写在 DOM 属性上，属性选择器认不到
 * （真机上撞到过一次超时）。
 */
async function rowOf(page, target) {
  const id = await page.evaluate(t => {
    const el = [...document.querySelectorAll('.wb-row .wb-col-target input')]
      .find(i => i.value.trim() === t)
    return el ? el.closest('.wb-row')?.getAttribute('data-row-id') ?? null : null
  }, target)
  return id == null ? null : page.locator(`.wb-row[data-row-id="${id}"]`)
}

/** 目标那句说明 + 粘出来时那一行小字。 */
async function targetTip(page) {
  return (await page.locator('.wb-rows-head .wb-panel-tip').first().innerText()).trim()
}
async function targetNote(page) {
  return page.locator('.wb-rows-head .wb-target-note').innerText().catch(() => '')
}

/** 某个目标字段那一行的来源小块上写着什么（验「已配好的行沿用」）。 */
async function sourceOf(page, target) {
  const row = await rowOf(page, target)
  if (!row) return null
  return (await row.locator('.wb-chip.chip-src').first().innerText().catch(() => '')).trim()
}

;(async () => {
  fs.mkdirSync(shots, { recursive: true })
  browser = await chromium.launch()
  const page = await browser.newPage({ viewport: { width: 1680, height: 1000 } })
  page.setDefaultTimeout(20000)
  const pageErrors = []
  page.on('pageerror', e => pageErrors.push(e.message))

  /** 保存会发的那个 PUT：这一步一个都不该有（这个脚本全程不保存）。 */
  let saveCalls = 0
  page.on('request', req => {
    if (req.method() === 'PUT' && /\/api\/actions\/\d+$/.test(new URL(req.url()).pathname)) saveCalls++
  })
  /** 目标字段那两条路各发了几次请求。 */
  let probeTargetCalls = 0
  page.on('request', req => {
    if (req.method() === 'POST' && req.url().includes('/probe-target')) probeTargetCalls++
  })

  // 后端有没有这一版（POST target-fields）
  const probe = await fetch(`${API}/field-mapping-editor/target-fields`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ json: '{"order_no":"PO-1"}' }),
  }).catch(() => null)
  const probeBody = probe ? await probe.json().catch(() => null) : null
  const backendHasIt = probe != null && probe.status === 200
  if (!backendHasIt) {
    skip('粘进来的目标字段清单由后端推出来',
      `后端（8080）还是这一版之前起的（POST target-fields → ${probe ? probe.status : '连不上'} ${probeBody?.message ?? ''}）；`
      + '这一半的断言在后端用例 FieldMappingEditorServiceTest.pastedSampleGivesTargetFieldsExactlyLikeTheProbedOne 里')
  } else {
    check('POST target-fields 推出来的字段带着类型与样例值',
      (probeBody?.data?.fields ?? []).length === 1
      && probeBody.data.fields[0].name === 'order_no'
      && probeBody.data.fields[0].type === 'STRING',
      JSON.stringify(probeBody?.data?.fields))
  }
  if (!backendHasIt) {
    await page.route('**/field-mapping-editor/target-fields', async route => {
      if (route.request().method() !== 'POST') return route.continue()
      const sent = JSON.parse(route.request().postData() || '{}')
      const text = String(sent.json ?? '').trim()
      const usable = text.startsWith('{') || text.startsWith('[')
      if (!usable) {
        return route.fulfill({
          status: 400, contentType: 'application/json',
          body: JSON.stringify({ code: 'PARAM_INVALID', message: '收对象或数组：粘一份 JSON', data: null }),
        })
      }
      const parsed = JSON.parse(text)
      const isArray = Array.isArray(parsed)
      const fields = expand(isArray ? parsed[0] : parsed)
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          code: 'OK', message: 'ok',
          data: { sourceKey: 'paste', sampledFirstItem: isArray, fields },
        }),
      })
    })
  }

  await openEditor(page, JOB_ID)
  const before = await targetNames(page)
  check('编辑器打开，目标字段探出来了', before.length > 0, `targets=${before.join(',')}`)
  check('探测那条路的目标说明还是老样子',
    /^目标：.+ · db:/.test(await targetTip(page)), await targetTip(page))
  check('探测那条路没有「粘出来的」那一行小字',
    (await page.locator('.wb-target-note').count()) === 0)

  // ---- 1. 两个入口并列（都在行列表头上）
  const head = page.locator('.wb-rows-head')
  const entries = (await head.getByRole('button').allInnerTexts()).map(s => s.trim())
  check('目标那一片两个入口都在：重新探测目标字段 / 粘贴 JSON',
    entries.includes('重新探测目标字段') && entries.includes('粘贴 JSON'), JSON.stringify(entries))

  // ---- 2. 粘贴框：先说清必填推不出来、数组按第 1 条推
  await head.getByRole('button', { name: '粘贴 JSON' }).click()
  const dialog = page.locator('.el-dialog').filter({ hasText: '粘贴 JSON' })
  await dialog.waitFor({ timeout: 10000 })
  await page.waitForTimeout(300)
  const dialogText = await dialog.innerText()
  check('粘贴框里写明必填推不出来、一律当不必填', dialogText.includes('必填'), dialogText.slice(0, 200))
  check('粘贴框里写明是数组就按第 1 条推', dialogText.includes('第 1 条'))

  // ---- 3. 粘一份带数组的目标数据
  //      用当前清单里的头一个字段名当那个标量字段（好验「同名的行沿用」），数组里再放一个。
  const scalar = before[0]
  const child = `${scalar}_child`
  const payload = JSON.stringify({ [scalar]: 'PO-1', items: [{ [child]: 'M1', qty: 3 }] })
  await dialog.locator('.wb-paste-text textarea').fill(payload)
  await dialog.getByRole('button', { name: '推出字段清单' }).click()
  await page.waitForTimeout(1500)
  const pasteError = await page.locator('.wb-paste-error').innerText().catch(() => '')
  check('粘一份带数组的目标数据能推出清单', pasteError === '', pasteError)

  const after = await targetNames(page)
  check('清单换成了粘进来的那些字段（标量 + 数组）',
    after.includes(scalar) && after.includes('items'), JSON.stringify(after))
  check('粘进来这一份没有把那 1 个标量字段丢掉', after.filter(t => t === scalar).length === 1)
  // 数组折成一行数组映射 + 「数组元素内」的子行（相对路径）——拍平的 `items[0].x` 折出来的样子。
  // 子行在 DOM 里是数组行的**兄弟**（中间隔着那个分组标题），所以按铺出来的顺序看。
  const arrayRow = await rowOf(page, 'items')
  check('数组折成了一行数组映射', arrayRow != null)
  check('数组行下面铺出了「数组元素内」那一组',
    (await page.locator('.wb-group-title').allInnerTexts()).some(t => t.includes('数组元素内')),
    JSON.stringify(await page.locator('.wb-group-title').allInnerTexts()))
  const itemsAt = after.indexOf('items')
  const childrenAfter = after.slice(itemsAt + 1, itemsAt + 3)
  check('数组里的字段折成了它的子行（相对路径）',
    childrenAfter.includes(child) && childrenAfter.includes('qty'), JSON.stringify(childrenAfter))

  // ---- 4. 那一行小字：必填推不出来（这一份是对象，所以不该提「按第 1 条推」）
  const note = await targetNote(page)
  check('小字写明必填推不出来、一律当不必填', note.includes('必填推不出来'), note)
  check('粘的是对象时小字不提「按第 1 条推」', !note.includes('按第 1 条推的'), note)

  // ---- 5. 目标那句说明写明是粘出来的
  check('目标说明写明「按粘的样例推的」（不冒充连库探测）',
    (await targetTip(page)).includes('按粘的样例推的'), await targetTip(page))
  await page.screenshot({ path: `${shots}/target-fields-pasted.png` })

  // ---- 6. 已经配好的行没被冲掉
  const missing = before.filter(t => !after.includes(t))
  check('原来那些行一个都没丢（名字不在粘的清单里的原样留着）',
    missing.length === 0, `丢了：${JSON.stringify(missing)}`)
  const keptSource = await sourceOf(page, before[1] ?? before[0])
  check('那些行原来的来源还在（没被这次粘贴清掉）', keptSource && !keptSource.includes('还没选来源'), keptSource ?? '(找不到那一行)')

  // ---- 6b. 再粘一个**数组**：小字里要出现「按第 1 条推的」
  await head.getByRole('button', { name: '粘贴 JSON' }).click()
  const arrayDialog = page.locator('.el-dialog').filter({ hasText: '粘贴 JSON' })
  await arrayDialog.waitFor({ timeout: 10000 })
  await arrayDialog.locator('.wb-paste-text textarea').fill(JSON.stringify([
    { [scalar]: 'PO-1', items: [{ [child]: 'M1', qty: 3 }] },
    { [scalar]: 'PO-2', remark: '第二条才有' },
  ]))
  await arrayDialog.getByRole('button', { name: '推出字段清单' }).click()
  await page.waitForTimeout(1500)
  const arrayNote = await targetNote(page)
  check('粘的是数组时小字写明按第 1 条推的', arrayNote.includes('按第 1 条推的'), arrayNote)
  check('数组时第 2 条才有的字段不在清单上',
    !(await targetNames(page)).includes('remark'), JSON.stringify(await targetNames(page)))

  // ---- 7. 「重新探测目标字段」换回连库那份，说法都回去
  probeTargetCalls = 0
  await head.getByRole('button', { name: '重新探测目标字段' }).click()
  await page.waitForTimeout(2000)
  check('点「重新探测目标字段」真发了一次探测请求', probeTargetCalls === 1, `probeTargetCalls=${probeTargetCalls}`)
  const reprobed = await targetNames(page)
  check('连库探出来的字段一个不少（原来那些都还在）',
    before.every(t => reprobed.includes(t)), JSON.stringify(reprobed))
  check('说明与那一行小字都跟着回去',
    (await page.locator('.wb-target-note').count()) === 0 && /^目标：.+ · db:/.test(await targetTip(page)),
    `${await targetTip(page)} / note=${await targetNote(page)}`)

  // ---- 8. 全程没保存
  check('全程没有发出保存请求', saveCalls === 0, `saveCalls=${saveCalls}`)
  check('页面上没有 JS 报错', pageErrors.length === 0, JSON.stringify(pageErrors))

  console.log(`\n${failures.length ? `FAILED: ${failures.join(' | ')}` : 'ALL OK'}`)
  await browser.close()
  process.exit(failures.length ? 1 : 0)
})().catch(async e => {
  console.error(e)
  if (browser) await browser.close()
  process.exit(1)
})
