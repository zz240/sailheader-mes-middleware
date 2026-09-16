/*
 * #115 真机 UI 验收：来源字段样例的两条路——「从运行记录探测」与「粘贴 JSON」并列。
 *
 * 用任务 3（DBG-TS，FIELD_MAP 动作 8）：它的来源清单探得出来（探测那条路有东西可比）。
 *   1. 左边面板顶上两个入口都在；探测那条路的说法与以前一模一样；
 *   2. 「从运行记录探测」真发一次 GET（清单换成这一步的运行输出）；
 *   3. 「粘贴 JSON」打开那个框，框里先写明这份样例算在哪一步；
 *   4. 粘一个对象 → 推出字段清单：名称/类型/样例值都显示出来，清单上头写明「按哪一步的输出来算」；
 *   5. 粘一个数组 → 只按第 1 条推（第 2 条才有的字段不在清单上），并注明「按第 1 条推的」；
 *   6. 收不下的文本 → 后端那句话显示在框里、框不关、粘的那段留着；
 *   7. 换顶部来源步骤：**不再发探测请求**，只换这份样例归到哪一步（界面上看得见换了）；
 *   8. 探测取不回来时：清单还是粘的那份，说法也得还是粘的那份（不能拿探测那句话去说它）；
 *   9. 保存：拦下那个 PUT（不写库），断言粘进来的样例值一个字都没进这份配置；
 *  10. 关掉重开：清单回到探测那条路——这一次保存的样例被拦下了（#119 之后真存下去的话，
 *      重开就是「粘的（已存）」那份，见 sample-stored-119.cjs）。
 *
 * 后端（8080）还是改动之前起的那一份时，POST 那条路还不存在（405）：脚本直接打一次接口问清楚，
 * 打印一行 SKIP，并用 page.route 顶一个同样形状的响应，把界面这一半照验（推断本身的断言在后端用例
 * `FieldMappingEditorServiceTest` 里）。**重启后端之后再跑就是真验。**
 *
 * 跑法（playwright 是本目录自带的，先 `npm i` 一次就行）：
 *   cd tools/ui && npm i && node paste-sample-115.cjs
 */
const fs = require('fs')
const path = require('path')
const { chromium } = require('playwright')

const APP = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080/api'
const JOB_ID = 3
const ACTION_ID = 8
const SOURCE_FIELDS = `${API}/jobs/${JOB_ID}/field-mapping-editor/source-fields`
const shots = path.join(__dirname, 'shots')
const failures = []
let browser = null

/** 粘进去的那两份样例；里面这两个值当标记用——它们绝不能出现在保存写出去的配置里。 */
const OBJECT_SAMPLE = '{"order_no":"PO-9","qty":3}'
const ARRAY_SAMPLE = '[{"order_no":"PO-1","qty":3},{"materialCode":"M2","note":"第二条才有"}]'
const MARKERS = ['PO-9', 'PO-1', 'M2']

/** 后端还没那一版时顶上去的两份清单（形状与真响应一致：探测契约 + sampledFirstItem）。 */
const FIELDS_OBJECT = [
  { name: 'order_no', type: 'STRING', nullable: true, samples: ['PO-9'] },
  { name: 'qty', type: 'NUMBER', nullable: true, samples: [3] },
]
const FIELDS_FIRST_ITEM = [
  { name: 'order_no', type: 'STRING', nullable: true, samples: ['PO-1'] },
  { name: 'qty', type: 'NUMBER', nullable: true, samples: [3] },
]

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

/** 左边面板顶上那句话（这份清单取自哪儿）。 */
async function sourceTip(page) {
  return (await page.locator('.wb-source .wb-panel-tip').first().innerText()).trim()
}

/** 左边清单上铺出来的字段名。 */
async function sourceNames(page) {
  return (await page.locator('.wb-source-item .wb-source-name').allInnerTexts()).map(s => s.trim())
}

/** 一行字段的「类型 · 样例 xxx」那一行字。 */
async function sourceMeta(page, name) {
  return page.locator('.wb-source-item', { hasText: name }).first().locator('.wb-source-meta').innerText()
}

async function openPasteDialog(page) {
  await page.locator('.wb-source-entries button', { hasText: '粘贴 JSON' }).click()
  const dialog = page.locator('.el-dialog').filter({ hasText: '粘贴 JSON' })
  await dialog.waitFor({ timeout: 10000 })
  await page.waitForTimeout(300)
  return dialog
}

/** 在粘贴框里填一段文本并点「推出字段清单」。 */
async function submitPaste(page, text) {
  const dialog = await openPasteDialog(page)
  await dialog.locator('.wb-paste-text textarea').fill(text)
  await dialog.getByRole('button', { name: '推出字段清单' }).click()
  await page.waitForTimeout(1200)
}

;(async () => {
  fs.mkdirSync(shots, { recursive: true })
  browser = await chromium.launch()
  const page = await browser.newPage({ viewport: { width: 1680, height: 1000 } })
  page.setDefaultTimeout(20000)
  const pageErrors = []
  page.on('pageerror', e => pageErrors.push(e.message))

  /** 探测那条路的请求发了几次（换来源步骤时不应该再发）。 */
  let probeCalls = 0
  page.on('request', req => {
    if (req.method() === 'GET' && req.url().includes('/source-fields')) probeCalls++
  })
  /** 保存会发的那个 PUT：拦下来看内容，不写库。 */
  let savedBody = null
  page.on('request', req => {
    if (req.method() === 'PUT' && /\/api\/actions\/\d+$/.test(new URL(req.url()).pathname)) {
      savedBody = req.postData()
    }
  })
  await page.route('**/api/actions/*', async route => {
    if (route.request().method() !== 'PUT') return route.continue()
    await route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ code: 'OK', message: 'ok', data: null }),
    })
  })
  // #119 之后保存还会先把「手里那份样例」写进库里（PUT samples）：这个脚本一个字都不写库，也把它拦下
  // （204 就是那个端点成功时的样子）。拦了它，「保存」才会照旧走到上面那个 PUT actions。
  await page.route('**/field-mapping-editor/samples', async route => {
    if (route.request().method() !== 'PUT') return route.continue()
    await route.fulfill({ status: 204, body: '' })
  })

  // ---- 后端有没有这一版（POST 那条路）
  const probe = await fetch(SOURCE_FIELDS, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ step: 1, json: OBJECT_SAMPLE }),
  }).catch(() => null)
  const probeBody = probe ? await probe.json().catch(() => null) : null
  // 「还没有这一版」只有这几种样子：连不上、路由不在（404/405）、或者方法不支持那条 500。
  // 其余任何非 200 都是**这一版自己的问题**，不该悄悄跳过去。
  const missingRoute = !probe || probe.status === 404 || probe.status === 405
    || (!probeBody?.data && /not supported|不支持/i.test(probeBody?.message ?? ''))
  const backendHasIt = !missingRoute && probe.status === 200
  const realNote = backendHasIt
    ? '后端有这一版：下面全是真发的请求'
    : `后端（8080）还是改动之前起的那一份（POST source-fields → ${probe ? probe.status : '连不上'} ${probeBody?.message ?? ''}）`
  if (!missingRoute && !backendHasIt) {
    check('POST source-fields 拿回一份字段清单', false,
      `${probe.status} ${JSON.stringify(probeBody).slice(0, 200)}`)
  }
  // 顶上是哪一步（顶替上来的那份响应得把同一步的名字说回来，界面上的说法才对得上）
  const context = await fetch(`${API}/jobs/${JOB_ID}/field-mapping-editor?actionId=${ACTION_ID}`)
    .then(r => r.json()).then(b => b.data).catch(() => null)
  const stepNameOf = step => (step > 0 ? context?.source?.stepName ?? null : null)
  if (!backendHasIt) {
    skip('粘进来的清单由后端推出来', `${realNote}；这一半的断言在后端用例 FieldMappingEditorServiceTest 里`)
    await page.route('**/field-mapping-editor/source-fields*', async route => {
      if (route.request().method() !== 'POST') return route.continue()
      const sent = JSON.parse(route.request().postData() || '{}')
      const text = String(sent.json ?? '').trim()
      const usable = text.startsWith('{') || text.startsWith('[')
      const body = usable
        ? {
          code: 'OK', message: 'ok',
          data: {
            step: sent.step, stepName: stepNameOf(sent.step), sourceKey: 'paste',
            sampledFirstItem: text.startsWith('['),
            fields: text.startsWith('[') ? FIELDS_FIRST_ITEM : FIELDS_OBJECT,
          },
        }
        : { code: 'PARAM_INVALID', message: '这段 JSON 解析不了: Unrecognized token', data: null }
      await route.fulfill({
        status: usable ? 200 : 400, contentType: 'application/json', body: JSON.stringify(body),
      })
    })
  } else {
    // 真发的那一次也把形状看一眼：字段名/类型/样例值都在（推断本身另有后端用例）
    const fields = probeBody?.data?.fields ?? []
    check('POST source-fields 推出来的字段带着类型与样例值',
      fields.length === 2 && fields.some(f => f.name === 'order_no' && f.type === 'STRING'
        && (f.samples ?? []).includes('PO-9')),
      JSON.stringify(fields))
    check('粘一个对象时没有「按第 1 条推」这件事', probeBody?.data?.sampledFirstItem === false,
      JSON.stringify(probeBody?.data?.sampledFirstItem))
  }

  await openEditor(page, JOB_ID)
  const probedNames = await sourceNames(page)
  const probedTip = await sourceTip(page)
  check('编辑器打开，探测那条路给出的清单还在', probedNames.length > 0, `names=${probedNames.join(',')}`)
  check('探测那条路的说法与以前一模一样（还是那一句）',
    /^来自 .+ 最近一次成功运行$/.test(probedTip), probedTip)

  // ---- 1. 顶上两个入口并列
  const entries = (await page.locator('.wb-source-entries button').allInnerTexts()).map(s => s.trim())
  check('来源面板顶部有两个入口：从运行记录探测 / 粘贴 JSON',
    entries.length === 2 && entries[0] === '从运行记录探测' && entries[1] === '粘贴 JSON',
    JSON.stringify(entries))
  check('入口在清单上面（顶部）',
    await page.locator('.wb-source-entries + .wb-panel-tip').count() === 1)

  // ---- 2. 「从运行记录探测」真发一次请求，清单还是探测那份
  probeCalls = 0
  await page.locator('.wb-source-entries button', { hasText: '从运行记录探测' }).click()
  await page.waitForTimeout(1200)
  check('点「从运行记录探测」发了一次取清单的请求', probeCalls === 1, `probeCalls=${probeCalls}`)
  check('探测完清单与刚才那份一样',
    JSON.stringify(await sourceNames(page)) === JSON.stringify(probedNames))
  check('探测完说法不变', (await sourceTip(page)) === probedTip, await sourceTip(page))

  // ---- 3. 粘贴框：先写明这份样例算在哪一步
  const dialog = await openPasteDialog(page)
  const stepTip = (await dialog.locator('.wb-paste-tip').last().innerText()).trim()
  // 框里那两句：#118 之后「这份样例按『动作 N · X』的输出来算」与「收对象或数组…」并成了一句
  const stepInDialog = stepTip.match(/这份样例按「(.+?)」的输出来算/)
  check('粘贴框里先写明这份样例按哪一步的输出来算', stepInDialog != null, stepTip)
  const dialogStep = stepInDialog ? stepInDialog[1] : ''
  await page.screenshot({ path: `${shots}/paste-sample-dialog.png` })

  // ---- 4. 粘一个对象：拿到字段清单，上头写明算在哪一步
  await dialog.locator('.wb-paste-text textarea').fill(OBJECT_SAMPLE)
  await dialog.getByRole('button', { name: '推出字段清单' }).click()
  await page.waitForTimeout(1500)
  if (await page.locator('.wb-paste-error').count()) {
    check('粘一个对象能推出字段清单', false, await page.locator('.wb-paste-error').innerText())
  } else {
    check('推完框自己关了', await page.locator('.el-dialog:has(.wb-paste-text):visible').count() === 0)
    const names = await sourceNames(page)
    check('清单换成了粘进来的这份（只按样例里的字段）',
      names.length === 2 && names.includes('order_no') && names.includes('qty'), JSON.stringify(names))
    check('类型与样例值都显示出来了',
      (await sourceMeta(page, 'order_no')).includes('STRING')
      && (await sourceMeta(page, 'order_no')).includes('PO-9'),
      await sourceMeta(page, 'order_no'))
    check('整份就是一个对象时没有「按第几条推」那句话',
      await page.locator('.wb-panel-note').count() === 0)
    const pastedTip = await sourceTip(page)
    check('界面写明这份样例按哪一步的输出来算',
      pastedTip === `这份样例按「${dialogStep}」的输出来算`, `${pastedTip} / 框里说的是 ${dialogStep}`)
    check('顶部的来源步骤就是它（界面上看得见）',
      (await page.locator('.wb-step-select input').inputValue()) === dialogStep
      || (await page.locator('.wb-step-select').innerText()).includes(dialogStep.replace(/^动作 \d+ · /, '')),
      await page.locator('.wb-step-select').innerText())
  }
  await page.screenshot({ path: `${shots}/paste-sample-object.png` })

  // ---- 5. 粘一个数组：只按第 1 条推，并注明
  await submitPaste(page, ARRAY_SAMPLE)
  const arrayNames = await sourceNames(page)
  check('数组按第 1 条推：第 1 条里的字段在清单上',
    arrayNames.includes('order_no') && arrayNames.includes('qty'), JSON.stringify(arrayNames))
  check('数组按第 1 条推：只在第 2 条里出现的字段不在清单上',
    !arrayNames.includes('materialCode') && !arrayNames.includes('note'), JSON.stringify(arrayNames))
  const note = await page.locator('.wb-panel-note').innerText().catch(() => '')
  check('界面上注明「按第 1 条推的」', note.includes('按第 1 条推的'), note)
  await page.screenshot({ path: `${shots}/paste-sample-array.png` })

  // ---- 6. 收不下的文本：后端那句话显示在框里，框不关
  const badDialog = await openPasteDialog(page)
  await badDialog.locator('.wb-paste-text textarea').fill('order_no=PO-1')
  await badDialog.getByRole('button', { name: '推出字段清单' }).click()
  await page.waitForTimeout(1200)
  const badText = await page.locator('.wb-paste-error').innerText().catch(() => '')
  check('收不下的文本在框里给出后端那句话', badText.length > 0, badText)
  check('收不下时框不关、粘的那段留着',
    await page.locator('.el-dialog:has(.wb-paste-text):visible').count() === 1
    && (await page.locator('.wb-paste-text textarea').inputValue()) === 'order_no=PO-1')
  await page.screenshot({ path: `${shots}/paste-sample-error.png` })
  await page.locator('.el-dialog').filter({ hasText: '粘贴 JSON' }).getByRole('button', { name: '取消' }).click()
  await page.waitForTimeout(600)

  // ---- 7. 换顶部来源步骤：不发探测请求，只换这份样例归到哪一步
  await submitPaste(page, ARRAY_SAMPLE)
  const beforeNames = await sourceNames(page)
  probeCalls = 0
  await page.locator('.wb-step-select').click()
  await page.locator('.el-select-dropdown__item', { hasText: '任务输入' }).first().click()
  await page.waitForTimeout(1200)
  check('换来源步骤不再发探测请求（粘的那份样例是给定的）', probeCalls === 0, `probeCalls=${probeCalls}`)
  check('换来源步骤时字段清单不动',
    JSON.stringify(await sourceNames(page)) === JSON.stringify(beforeNames))
  check('换来源步骤后这份样例归到新那一步（界面上看得见）',
    (await sourceTip(page)) === '这份样例按「任务输入」的输出来算', await sourceTip(page))

  // ---- 8. 探测取不回来时：清单还是粘的那份，说法也得还是粘的那份
  //      （拿探测那句话去说一份粘来的清单，是把「这份清单取自哪儿」说反了）
  await page.route('**/field-mapping-editor/source-fields*', async route => {
    if (route.request().method() !== 'GET') return route.continue()
    await route.fulfill({
      status: 500, contentType: 'application/json',
      body: JSON.stringify({ code: 'INTERNAL_ERROR', message: '探测炸了（脚本装的）', data: null }),
    })
  })
  await page.locator('.wb-source-entries button', { hasText: '从运行记录探测' }).click()
  await page.waitForTimeout(1200)
  const failedTip = await sourceTip(page)
  check('探测取不回来时说法不跟着变（还是粘的那份）',
    failedTip === '这份样例按「任务输入」的输出来算', failedTip)
  check('探测取不回来时清单也不动',
    JSON.stringify(await sourceNames(page)) === JSON.stringify(beforeNames))
  check('取不回来这件事说清楚了', (await page.locator('.el-message').allInnerTexts()).some(t => t.includes('探测炸了')),
    JSON.stringify(await page.locator('.el-message').allInnerTexts()))
  await page.unroute('**/field-mapping-editor/source-fields*')

  // ---- 9. 保存：拦下 PUT，断言样例值一个都没进这份配置
  await page.locator('.wb-head-actions').getByRole('button', { name: '保存', exact: true }).click()
  for (let i = 0; i < 24 && !savedBody; i++) {
    const confirm = page.locator('.el-message-box__btns button')
      .filter({ hasText: /只保存配好的|确认过了，保存|替换并保存/ })
    if (await confirm.count()) await confirm.first().click()
    await page.waitForTimeout(400)
  }
  check('保存发出去了（PUT 被脚本拦下，没有写库）', savedBody != null)
  if (savedBody) {
    const leaked = MARKERS.filter(m => savedBody.includes(m))
    check('粘进来的样例值一个都没进这份配置', leaked.length === 0, `leaked=${leaked.join(',')}`)
    check('配置里仍然是规则树', savedBody.includes('"rules"'), savedBody.slice(0, 120))
  }

  // ---- 10. 关掉重开：清单回到探测那条路（这一次保存的样例被脚本拦下了，库里什么都没留下；
  //          #119 之后真存下去的话，重开就该是「粘的（已存）」那份——那条路在 sample-stored-119.cjs 里）
  await page.reload({ waitUntil: 'networkidle' })
  await openEditor(page, JOB_ID)
  const reopenedTip = await sourceTip(page)
  check('重开编辑器：清单回到探测那条路（这一次保存没有写库）',
    reopenedTip === probedTip, `${reopenedTip} / 之前是 ${probedTip}`)
  check('重开之后也没有「按第 1 条推」那句话', await page.locator('.wb-panel-note').count() === 0)

  check('页面上没有 JS 报错', pageErrors.length === 0, JSON.stringify(pageErrors))

  console.log(`\n${failures.length ? `FAILED: ${failures.join(' | ')}` : 'ALL OK'}`)
  await browser.close()
  process.exit(failures.length ? 1 : 0)
})().catch(async e => {
  console.error(e)
  if (browser) await browser.close()
  process.exit(1)
})
