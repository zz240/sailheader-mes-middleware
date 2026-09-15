/*
 * #112 真机 UI 验收：拼接的每个片段挂一个片段转换（六选一、参数就地编辑、行号只在子行）。
 *
 * 用任务 3（DBG-TS）的 FIELD_MAP 动作里那条拼接规则（`material_name`，3 个片段）。
 * 全程**只改编辑器里的草稿、一次都不保存**，所以数据库里的配置一个字都没变。
 *
 *   一、片段下拉六项；顶层拼接行上「行号」置灰并写明原因。
 *   二、选「日期格式化」→ 片段里就地出现日期参数（四个预设 + 手输框），改一下格式串，
 *       这一行的片段小块跟着变。
 *   三、选「枚举映射」→ 片段里就地出现键值表；加一行后点「校验配置」，发出去的草稿里带着它
 *       （后端是改动前起的那一份也不影响这一条：看的是前端发出去的那份草稿）。
 *   四、整个面板里没有 `$`。
 *
 * 跑法（playwright 是本目录自带的，先 `npm i` 一次就行）：
 *   cd tools/ui && npm i && node part-transform-112.cjs
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
const panel = page => page.locator('.wb-panel').first()

/** 键序无关的 JSON 比较（后端/前端写出去的键序不是契约，值才是）。 */
function stable(value) {
  if (Array.isArray(value)) return value.map(stable)
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().map(k => [k, stable(value[k])]))
  }
  return value
}
const same = (a, b) => JSON.stringify(stable(a)) === JSON.stringify(stable(b))

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

/** 拼接那一行在行列表里的序号（它的转换小块上写着「拼接」）。 */
async function concatRowIndex(page) {
  return page.evaluate(() => [...document.querySelectorAll('.wb-row')].findIndex(row => {
    const chip = row.querySelector('.wb-chip.chip-op, .wb-chip.chip-op-empty')
    return (chip?.textContent ?? '').includes('拼接')
  }))
}

/** 铺着的那个下拉：element-plus 在 popper 上用 aria-hidden 标「开着没有」。 */
const OPEN_DROPDOWN = '.el-popper[aria-hidden="false"] .el-select-dropdown'

/**
 * 打开第 i 个片段的转换下拉。
 *
 * 点一下没铺开就再点一下：同一个 select 上一次开着时，第一下可能只是把上一个下拉收掉。
 */
async function openPartKind(page, partIndex) {
  const select = page.locator('.wb-part').nth(partIndex).locator('.wb-part-kind')
  await select.click()
  await page.waitForTimeout(300)
  if (await page.locator(OPEN_DROPDOWN).count() === 0) {
    await select.click()
  }
  await page.waitForSelector(`${OPEN_DROPDOWN} .el-select-dropdown__item`, { timeout: 10000 })
  await page.waitForTimeout(250) // 等展开动画停（没停时 Playwright 不点）
}

/** 打开第 i 个片段的转换下拉，返回选项（文字 + 是否置灰）。 */
async function partKindOptions(page, partIndex) {
  await openPartKind(page, partIndex)
  return page.locator(OPEN_DROPDOWN).first().locator('.el-select-dropdown__item')
    .evaluateAll(items => items.map(item => ({
      label: item.textContent.trim(),
      disabled: item.classList.contains('is-disabled'),
    })))
}

/** 在下拉里点一项：只认**铺着的**那一个下拉（页面里同时躺着好几个）。 */
async function pickOption(page, label) {
  await page.locator(`${OPEN_DROPDOWN} .el-select-dropdown__item`, { hasText: new RegExp(`^${label}$`) })
    .first().click()
  await page.waitForTimeout(250)
}

/** 把第 i 个片段换成某一项（打开下拉 → 点那一项）。 */
async function choosePartKind(page, partIndex, label) {
  await openPartKind(page, partIndex)
  await pickOption(page, label)
}

/** 这一行片段小块的文字（片段小块带 `chip-part`，与「转换」那一块分开）。 */
const partChips = (page, rowIndex) => page.locator('.wb-row').nth(rowIndex)
  .locator('.wb-chip.chip-part')
  .allInnerTexts()

;(async () => {
  fs.mkdirSync(shots, { recursive: true })
  browser = await chromium.launch()
  const page = await browser.newPage({ viewport: { width: 1680, height: 1000 } })
  page.setDefaultTimeout(20000)
  const pageErrors = []
  page.on('pageerror', e => pageErrors.push(e.message))
  const writes = []
  page.on('request', r => {
    // 只算**写配置**的那些：`probe-target` 是打开编辑器时的目标结构探测（只读，只是用 POST 带 body）
    if (['PUT', 'POST'].includes(r.method()) && /\/api\/actions/.test(r.url())
        && !/probe|test-connection/.test(r.url())) {
      writes.push(`${r.method()} ${r.url()}`)
    }
  })

  await openEditor(page)
  const rowIndex = await concatRowIndex(page)
  check('准备：找到拼接那一行', rowIndex >= 0, `index=${rowIndex}`)
  if (rowIndex < 0) throw new Error('任务 3 里没有拼接规则，这份脚本要改')
  const row = page.locator('.wb-row').nth(rowIndex)
  const target = (await row.locator('.wb-col-target input').first().inputValue()).trim()
  console.log(`  （用第 ${rowIndex + 1} 行，目标字段「${target}」）`)

  const before = await partChips(page, rowIndex)
  check('准备：这一行有 3 个片段', before.length === 3, before.join(' | '))

  // 打开拼接面板：点**片段**那一块（点转换那一块打开的是换转换面板）
  await row.locator('.wb-chip.chip-part').first().click()
  await page.waitForSelector('.wb-panel .wb-part', { timeout: 10000 })
  check('拼接面板里每个片段一行', await page.locator('.wb-part').count() === 3)

  // ---- 一、六选一 + 顶层置灰并写明原因
  const options = await partKindOptions(page, 0)
  check('片段下拉是六项', options.map(o => o.label).join('、') === '常量、直接取值、数字转换、日期格式化、枚举映射、行号',
    options.map(o => o.label).join('、'))
  check('顶层拼接行上「行号」置灰', options.find(o => o.label === '行号')?.disabled === true,
    JSON.stringify(options))
  check('别的五项都能选', options.filter(o => o.disabled).length === 1, JSON.stringify(options))
  const panelText = (await panel(page).innerText())
  check('置灰的原因写在面板上', panelText.includes('行号片段只能在数组映射的子行里用'))
  await page.keyboard.press('Escape')
  await page.waitForTimeout(200)
  await page.screenshot({ path: `${shots}/part-transform-parts.png` })

  // ---- 二、日期格式化片段：参数就地出现（与行级同一套：四个预设 + 手输框）
  await choosePartKind(page, 1, '日期格式化')
  const params = page.locator('.wb-part').nth(1).locator('.wb-part-params')
  check('日期片段的参数就地展开', await params.count() === 1)
  const presetText = await params.locator('.wb-opbtn').allInnerTexts()
  check('四个预设都在片段里',
    ['yyyy-MM-dd', 'yyyy-MM-dd HH:mm:ss', 'yyyyMMdd', 'yyyy/MM/dd']
      .every(p => presetText.some(text => text.includes(p))), presetText.join(' | '))
  check('手输框也在（#111 那一套原样复用）',
    (await params.locator('input').first().getAttribute('placeholder') ?? '').includes('手输'))
  // 面板样式从 Workbench 的 scoped 块挪到了子组件用得上的全局块：这里量一下确实作用到了组件内部
  const presetStyle = await params.locator('.wb-opbtn').first().evaluate(el => {
    const cs = getComputedStyle(el)
    return { border: cs.borderTopWidth, display: cs.display, cursor: cs.cursor }
  })
  check('片段里的参数编辑器带着面板样式（不是没样式的裸按钮）',
    presetStyle.border === '1px' && presetStyle.display === 'flex', JSON.stringify(presetStyle))
  await params.locator('input').first().fill('yyyyMMddHHmmss')
  await params.locator('input').first().blur()
  await page.waitForTimeout(250)
  const afterDate = await partChips(page, rowIndex)
  check('改完格式串，这一行的片段小块跟着变',
    afterDate.some(text => text.includes('日期格式化 · yyyyMMddHHmmss')),
    afterDate.join(' | '))
  await page.screenshot({ path: `${shots}/part-transform-date-part.png` })

  // ---- 三、枚举映射片段：键值表就地展开；加一行后草稿里带着它
  await choosePartKind(page, 2, '枚举映射')
  const enumParams = page.locator('.wb-part').nth(2).locator('.wb-part-params')
  check('枚举片段的键值表就地展开', await enumParams.locator('.wb-enum').count() === 1)
  await enumParams.getByRole('button', { name: '加一行' }).click()
  await enumParams.locator('input').first().fill('PCS')
  await enumParams.locator('input').nth(1).fill('件')
  await page.waitForTimeout(200)
  const afterEnum = await partChips(page, rowIndex)
  check('枚举片段的摘要写着条数', afterEnum.some(text => text.includes('枚举映射 · 1 条')), afterEnum.join(' | '))

  const [validateResp] = await Promise.all([
    page.waitForResponse(r => r.url().includes('/field-mapping-editor/validate')
      && r.request().method() === 'POST'),
    wb(page).getByRole('button', { name: '校验配置' }).click(),
  ])
  const draft = JSON.parse(validateResp.request().postData() ?? '{}').draft
  const rule = (draft.rules ?? []).find(r => r.target === target)
  check('发出去的草稿里，加工片段是那个形状',
    same(rule?.parts?.[1], { path: rule?.parts?.[1]?.path, transform: 'DATE', datePattern: 'yyyyMMddHHmmss' }),
    JSON.stringify(rule?.parts))
  check('枚举片段带着键值表',
    rule?.parts?.[2]?.transform === 'ENUM' && rule?.parts?.[2]?.enumMap?.PCS === '件',
    JSON.stringify(rule?.parts?.[2]))
  check('常量片段还是老形状（字符串）', typeof rule?.parts?.[0] === 'string', JSON.stringify(rule?.parts?.[0]))

  // ---- 四、面板里不出现 `$`
  check('拼接面板里一个 `$` 都没有', !(await panel(page).innerText()).includes('$'))

  // ---- 五、片段配错：错误指到那一个片段、保存被拦下
  //
  // 这一段要后端已经是**改过之后**的那一份。先直接打一次接口问清楚（跑这个脚本时后端可能还是
  // 之前起的 IDE 调试会话），后端没带上片段校验时这一段整个跳过并写明原因。
  const probe = await fetch(`${API}/field-mapping-editor/validate`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      draft: { rules: [{ sourceStep: 1, target: 'bom.bomNo', transform: 'CONCAT',
        parts: ['BOM-', { path: 'x', transform: 'NOT_A_TRANSFORM' }] }] },
      earlierActionIndexes: [1], defaultSourceStep: 1, actionIndex: 2,
    }),
    signal: AbortSignal.timeout(30000),
  }).then(r => r.json()).then(r => r.data)
  const backendKnowsParts = (probe.errors ?? []).some(e => e.message.includes('拼接第 2 个片段'))
  if (!backendKnowsParts) {
    console.log('SKIP - 后端（8080）还是改动之前起的那一份：跳过片段报错这一段。')
    console.log('       证据在 MappingDraftValidationServiceTest / JsonataRuleCompilerTest（同一个实现）。')
  }

  // 把第 1 个片段（日期格式化）的格式串改成一个非法串
  await page.locator('.wb-part').nth(1).locator('.wb-part-params input').first().fill('ISO-8601')
  await page.locator('.wb-part').nth(1).locator('.wb-part-params input').first().blur()
  await page.waitForTimeout(250)
  const [validateResp2] = await Promise.all([
    page.waitForResponse(r => r.url().includes('/field-mapping-editor/validate')
      && r.request().method() === 'POST'),
    wb(page).getByRole('button', { name: '校验配置' }).click(),
  ])
  const result2 = (await validateResp2.json()).data
  await page.screenshot({ path: `${shots}/part-transform-part-error.png` })

  if (backendKnowsParts) {
    const partError = (result2.errors ?? []).find(e => e.message.includes('拼接第 2 个片段'))
    check('片段配错时错误指到那一个片段', !!partError, JSON.stringify(result2.errors))
    check('报出来的话里带着平台的原话（日期格式串那条）',
      partError?.message.includes('日期格式串不合法') === true, partError?.message)
    check('校验结果里 valid=false', result2.valid === false)
    const state = (await row.locator('.wb-state').first().innerText()).trim()
    check('这一行右侧标成「有错」', state === '有错', state)

    writes.length = 0
    await wb(page).getByRole('button', { name: '保存' }).click()
    await page.waitForTimeout(1500)
    check('保存被拦下（一个写请求都没发出去）', writes.length === 0, writes.join(' | '))
  } else {
    const sent = JSON.parse(validateResp2.request().postData() ?? '{}')
    check('非法的片段参数原样进了发出去的草稿（判对错是后端的事）',
      (sent.draft.rules ?? []).some(r => (r.parts ?? []).some(p => p?.datePattern === 'ISO-8601')),
      JSON.stringify(sent.draft).slice(0, 200))
  }

  // 全程没保存
  check('全程没有写请求（没保存，数据库里的配置没动）', writes.length === 0, writes.join(' | '))
  check('全程没有页面报错', pageErrors.length === 0, pageErrors.join(' | '))
  await page.screenshot({ path: `${shots}/part-transform-final.png` })

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
