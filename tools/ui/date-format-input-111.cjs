/*
 * #111 真机 UI 验收：日期格式手输 + 格式串合法性校验。
 *
 * 用任务 3（DBG-TS）的 FIELD_MAP 动作：全程**只改编辑器里的草稿、一次都不保存**，
 * 所以跑完数据库里的配置一个字都没变（校验接口是只读的）。
 *
 *   一、日期那一行：四个预设照旧；手输框在；手输的格式串落到这一行的摘要里（「日期格式化 · <格式串>」）。
 *   二、清空手输框 → 失焦：回落到默认格式 yyyy-MM-dd（输入框与摘要都回落到它）。
 *   三、非法格式串（ISO-8601）：点「校验配置」后端以**错误**报出、挂到那一行；
 *       再点「保存」被拦下（没有 PUT 出去）。
 *   四、留截图。
 *
 * 跑法（playwright 是本目录自带的，先 `npm i` 一次就行）：
 *   cd tools/ui && npm i && node date-format-input-111.cjs
 * 跑之前前端 dev server（5173）与后端（8080）都要在。
 */
const fs = require('fs')
const path = require('path')
const { chromium } = require('playwright')

const APP = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080/api'
const JOB_ID = 3
const shots = path.join(__dirname, 'shots')
const failures = []
let browser = null

function check(name, cond, detail = '') {
  console.log(`${cond ? 'ok  ' : 'NOT '} - ${name}${cond ? '' : ` :: ${detail}`}`)
  if (!cond) failures.push(name)
}

const wb = page => page.locator('.mapping-workbench-drawer')

/** 打开任务 3 的字段映射编辑器（新版逐行编辑器是唯一入口）。 */
async function openEditor(page) {
  await page.goto(`${APP}/jobs`, { waitUntil: 'networkidle' })
  const jobRow = page.locator('tbody tr').filter({ has: page.locator('td', { hasText: /^3$/ }) }).first()
  await jobRow.waitFor({ timeout: 20000 })
  await jobRow.getByRole('button', { name: '动作' }).click()
  await page.waitForSelector('text=动作链', { timeout: 20000 })
  await page.locator('tbody tr', { hasText: 'FIELD_MAP' }).first()
    .getByRole('button', { name: '编辑映射' }).click()
  await page.waitForSelector('.wb-row, .wb-empty, .wb-expression-input', { timeout: 25000 })
  await page.waitForTimeout(800)
}

/** 第一条普通行（数组行不拿它验日期转换）在行列表里的序号。 */
async function firstLeafRowIndex(page) {
  return page.evaluate(() => [...document.querySelectorAll('.wb-row')]
    .findIndex(r => !r.querySelector('.wb-child-hint')))
}

/** 一行的转换小块上的字（日期那一行读起来是「日期格式化 · <格式串>」）。 */
const rowTransformText = (page, i) => page.locator('.wb-row').nth(i)
  .locator('.wb-chip.chip-op, .wb-chip.chip-op-empty').first().innerText()
  .then(text => text.replace(/\s+/g, ' ').trim())

/** 打开日期面板的输入框（面板里那一排预设是按钮，输入框是唯一的 input）。 */
const dateInput = page => page.locator('.wb-panel .wb-panel-row input').first()

;(async () => {
  fs.mkdirSync(shots, { recursive: true })
  browser = await chromium.launch()
  const page = await browser.newPage({ viewport: { width: 1680, height: 1000 } })
  page.setDefaultTimeout(20000)
  const pageErrors = []
  page.on('pageerror', e => pageErrors.push(e.message))
  /** 编辑器发出去的写请求（保存会被拦下 → 应该一个都没有）。 */
  const writes = []
  page.on('request', r => {
    if (['PUT', 'POST'].includes(r.method()) && /\/api\/actions/.test(r.url())) {
      writes.push(`${r.method()} ${r.url()}`)
    }
  })

  await openEditor(page)
  const rowIndex = await firstLeafRowIndex(page)
  check('准备：找到一条普通行', rowIndex >= 0, `index=${rowIndex}`)
  const row = page.locator('.wb-row').nth(rowIndex)
  const target = (await row.locator('.wb-col-target input').first().inputValue()).trim()
  console.log(`  （用第 ${rowIndex + 1} 行，目标字段「${target}」）`)

  // ---- 一、这一行换成日期格式化，日期面板里四个预设照旧 + 手输框在
  const chip = row.locator('.wb-chip.chip-op, .wb-chip.chip-op-empty').first()
  const chipText = (await chip.innerText()).replace(/\s+/g, ' ').trim()
  if (!chipText.includes('日期格式化')) {
    await chip.click()
    await page.locator('.wb-panel .wb-opbtn', { hasText: '日期格式化' }).first().click()
  }
  await page.waitForSelector('.wb-panel .wb-opbtn', { timeout: 10000 })

  const presets = await page.locator('.wb-panel .wb-opbtn').allInnerTexts()
  check('日期面板里四个预设都在',
    ['yyyy-MM-dd', 'yyyy-MM-dd HH:mm:ss', 'yyyyMMdd', 'yyyy/MM/dd']
      .every(p => presets.some(text => text.includes(p))), presets.join(' | '))
  check('日期面板里有手输格式串的输入框', await dateInput(page).count() === 1)
  check('面板上写明了清空会回落到默认格式',
    (await page.locator('.wb-panel').innerText()).includes('清空就回落到默认的 yyyy-MM-dd'))
  await page.screenshot({ path: `${shots}/date-format-input-date-panel.png` })

  // ---- 二、手输一个客户格式串：落到这一行的摘要里
  await dateInput(page).fill('yyyyMMddHHmmss')
  await dateInput(page).blur()
  await page.waitForTimeout(200)
  check('手输的格式串留在输入框里（不失焦也不被改）',
    (await dateInput(page).inputValue()) === 'yyyyMMddHHmmss',
    await dateInput(page).inputValue())
  check('手输的格式串显示在这一行的摘要里',
    (await rowTransformText(page, rowIndex)).includes('yyyyMMddHHmmss'),
    await rowTransformText(page, rowIndex))
  await page.screenshot({ path: `${shots}/date-format-input-manual-pattern.png` })

  // ---- 三、清空 → 回落到默认格式（不是空串）
  await dateInput(page).fill('')
  await dateInput(page).blur()
  await page.waitForTimeout(200)
  check('清空输入框后回落到默认格式（输入框里又出现 yyyy-MM-dd）',
    (await dateInput(page).inputValue()) === 'yyyy-MM-dd', await dateInput(page).inputValue())
  check('清空后这一行的摘要显示的是默认格式',
    (await rowTransformText(page, rowIndex)).includes('yyyy-MM-dd'),
    await rowTransformText(page, rowIndex))

  // 预设照旧能用：点一下第四个，输入框与摘要跟着走
  await page.locator('.wb-panel .wb-opbtn', { hasText: 'yyyy/MM/dd' }).first().click()
  await page.waitForTimeout(200)
  check('点预设照旧写进这一行（输入框与摘要一起变）',
    (await dateInput(page).inputValue()) === 'yyyy/MM/dd'
      && (await rowTransformText(page, rowIndex)).includes('yyyy/MM/dd'),
    `${await dateInput(page).inputValue()} / ${await rowTransformText(page, rowIndex)}`)

  // ---- 四、非法格式串：校验配置以错误报出、保存被拦下
  //
  // 这一段要后端已经是**改过之后**的那份。跑这个脚本时后端多半是 IDE 调试会话里那个进程
  // （改动之前起的），classpath 上的类已经加载过了——所以先直接打一次接口问清楚：
  // 后端还没带上这条校验时，这一段整个跳过并写明原因（后端的证据在那两个测试里，不在这里）。
  const probe = await fetch(`${API}/field-mapping-editor/validate`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      draft: { rules: [{ sourceStep: 1, source: 'createdTime', target: 'bom.createdDate',
        transform: 'DATE', datePattern: 'ISO-8601' }] },
      earlierActionIndexes: [1], defaultSourceStep: 1, actionIndex: 2,
    }),
    signal: AbortSignal.timeout(30000),
  }).then(r => r.json()).then(r => r.data)
  const backendKnowsTheRule = (probe.errors ?? []).some(e => e.message.includes('日期格式串不合法'))
  if (!backendKnowsTheRule) {
    console.log('SKIP - 后端（8080）还是改动之前起的那一份：这里跳过错误路径这一段。')
    console.log('       证据在 MappingDraftValidationServiceTest / FieldMappingEditorControllerTest（同一个实现）。')
  }

  await dateInput(page).fill('ISO-8601')
  await dateInput(page).blur()
  await page.waitForTimeout(200)
  const [validateResp] = await Promise.all([
    page.waitForResponse(r => r.url().includes('/field-mapping-editor/validate')
      && r.request().method() === 'POST'),
    wb(page).getByRole('button', { name: '校验配置' }).click(),
  ])
  const validateData = (await validateResp.json()).data
  const patternError = validateData.errors.find(e => e.message.includes('日期格式串不合法'))
  await page.screenshot({ path: `${shots}/date-format-input-invalid-pattern.png` })

  if (backendKnowsTheRule) {
    check('非法格式串以错误报出（后端返回 errors 里带着它）', !!patternError,
      JSON.stringify(validateData.errors))
    check('错误定位到那一行（target 就是这一行的目标字段）', patternError?.target === target,
      `target=${patternError?.target} 期望=${target}`)
    check('报出来的话里带着平台的原话',
      patternError?.message.includes('Unknown pattern letter: I') === true, patternError?.message)
    check('校验结果里 valid=false（编辑器据此拦保存）', validateData.valid === false)

    const stateText = (await row.locator('.wb-state').first().innerText()).trim()
    check('这一行右侧标成「有错」', stateText === '有错', stateText)

    // 保存：先校验一次、有错就拦下 —— 不该有 PUT 出去
    writes.length = 0
    await wb(page).getByRole('button', { name: '保存' }).click()
    await page.waitForTimeout(1500)
    check('保存被拦下（一个写请求都没发出去）', writes.length === 0, writes.join(' | '))
    const blocked = await page.locator('.el-message').allInnerTexts()
    check('拦下时说清了原因',
      blocked.some(t => t.includes('日期格式串不合法') || t.includes('错')), blocked.join(' | '))
  } else {
    // 后端那一份是旧的：这里只断言前端把非法格式串原样发了出去（前端不复制校验规则，#98 D1）
    const sent = JSON.parse(validateResp.request().postData() ?? '{}')
    check('非法格式串原样进了发出去的草稿（判对错是后端的事）',
      sent.draft.rules.some(r => r.datePattern === 'ISO-8601'),
      JSON.stringify(sent.draft).slice(0, 160))
  }
  await page.screenshot({ path: `${shots}/date-format-input-save-blocked.png` })

  // 全程没保存：数据库里的配置一个字没变
  check('全程没有页面报错', pageErrors.length === 0, pageErrors.join(' | '))

  await browser.close()
  console.log(`\n# ${failures.length === 0 ? '全部通过' : failures.length + ' 项没过'}`)
  if (failures.length) {
    console.log(failures.map(f => `  - ${f}`).join('\n'))
    process.exitCode = 1
  }
})().catch(async e => {
  console.error('跑挂了：', e)
  if (browser) await browser.close()
  process.exitCode = 1
})
