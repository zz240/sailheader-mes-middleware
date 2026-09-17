/*
 * #106 真机 UI 验收：窄屏、键盘、手写 JSONata 入口。
 *
 * 用任务 3（DBG-TS）的 FIELD_MAP 动作（id 8）：9 条已存规则 + 目标表 dbg_receipt 探出来的清单，
 * 正好够验「每一行读得通」「键盘配得完」「手写表达式切得过去、存得下、切得回来」。
 *
 *   一、窄屏：1440 / 1280 / 1100 / 1024 / 900 / 820 / 700 / 560 八个宽度上，
 *       页面没有横向溢出、行里「目标字段 / 映射 / 问题 / 行操作」四块互不重叠、
 *       窄的时候竖着依次排列、宽的时候并排；关键宽度留截图。
 *   二、键盘：↑↓ 走行、`/` 换转换（↑↓ 选、回车用）、回车选来源（↑↓ 走清单、回车填）、
 *       退格清除当前行（清完还能撤销）、Esc 收起面板 / 退出选来源。
 *   三、手写 JSONata：从「高级：手写 JSONata」入口切过去 → 写一整段表达式 → 校验配置
 *       （后端真的跑了一遍：草稿就是 `{mode:EXPRESSION, expression, rules:[]}`、返回 valid）→
 *       保存（截下 PUT 的那份 addition）→ 重开编辑器，直接回到手写这一边且表达式回填 →
 *       切回逐行配置（说清规则树会替换掉表达式）→ 键盘配一行 → 保存（规则树那一边）→
 *       最后把任务 3 原来的映射规则放回去。
 *
 * 跑法（playwright 是本目录自带的，先 `npm i` 一次就行）：
 *   cd tools/ui && npm i && node keyboard-narrow-screen-106.cjs
 * 跑之前前端 dev server（5173）与后端（8080）都要在。任务 3 的规则跑完会还原。
 */
const fs = require('fs')
const path = require('path')
const { chromium } = require('playwright')

const APP = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080/api'
const JOB_ID = 3
const MAP_ACTION_NAME = 'map'
const shots = path.join(__dirname, 'shots')
const failures = []
let browser = null
/** 把任务 3 原来的动作配置放回去（中途出错也要放，见文件末尾）。 */
let restore = null

const EXPRESSION = '{\n  "order_no": $s_1.order_no,\n  "kind": "固定值"\n}'

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

/** 键序无关的 JSON 比较（后端 toMap 会重排键，值才是契约）。 */
function stable(value) {
  if (Array.isArray(value)) return value.map(stable)
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().map(k => [k, stable(value[k])]))
  }
  return value
}

const same = (a, b) => JSON.stringify(stable(a)) === JSON.stringify(stable(b))

/** 打开任务 3 的 FIELD_MAP 新版编辑器。 */
async function openEditor(page) {
  await page.goto(`${APP}/jobs`, { waitUntil: 'networkidle' })
  const jobRow = page.locator('tbody tr').filter({ has: page.locator('td', { hasText: /^3$/ }) }).first()
  await jobRow.waitFor({ timeout: 20000 })
  await jobRow.getByRole('button', { name: '动作' }).click()
  await page.waitForSelector('text=动作链', { timeout: 20000 })
  await page.locator('tbody tr', { hasText: 'FIELD_MAP' }).first()
    .getByRole('button', { name: '编辑映射' }).click()
  // 逐行配置那一边铺的是行；手写表达式那一边铺的是表达式输入框
  await page.waitForSelector('.wb-row, .wb-empty, .wb-expression-input', { timeout: 25000 })
  await page.waitForTimeout(800)
}

/** 行里那四块的几何（重叠 / 依次排列靠它判）。 */
async function rowGeometry(page) {
  return page.evaluate(() => {
    const box = el => {
      if (!el) return null
      const b = el.getBoundingClientRect()
      return { left: b.left, top: b.top, right: b.right, bottom: b.bottom, width: b.width, height: b.height }
    }
    return [...document.querySelectorAll('.wb-row')].map(row => ({
      row: box(row),
      target: box(row.querySelector('.wb-col-target')),
      value: box(row.querySelector('.wb-col-value')),
      issues: box(row.querySelector('.wb-col-issues')),
      actions: box(row.querySelector('.wb-col-actions')),
      target_: row.querySelector('.wb-col-target input')?.value ?? '',
    }))
  })
}

const overlaps = (a, b) => !!a && !!b
  && Math.min(a.right, b.right) - Math.max(a.left, b.left) > 1
  && Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 1
const above = (a, b) => !!a && !!b && a.bottom <= b.top + 1
const leftOf = (a, b) => !!a && !!b && a.right <= b.left + 1
const inside = (outer, inner) => !!outer && !!inner && inner.left >= outer.left - 1 && inner.right <= outer.right + 1

/** 选中的那一行的目标字段。 */
const wb = page => page.locator('.mapping-workbench-drawer')
const selectedTarget = page => page.locator('.wb-row.selected .wb-col-target input').first().inputValue()
/** 选中的那一行的来源小块上的字。 */
const selectedSource = page => page.locator('.wb-row.selected .wb-chip.chip-src').first().innerText()

/** 按目标字段找回一行（填完来源会自己往下走一行，选中的未必是刚填的那一行）。 */
async function rowByTarget(page, target) {
  const index = await page.evaluate(t => [...document.querySelectorAll('.wb-row')]
    .findIndex(r => (r.querySelector('.wb-col-target input')?.value ?? '') === t), target)
  if (index < 0) throw new Error(`找不到目标字段为「${target}」的行`)
  return page.locator('.wb-row').nth(index)
}
/** 选中的那一行的转换小块上的字（直接映射时是「换转换」那个虚线块）。 */
async function selectedTransform(page) {
  const row = page.locator('.wb-row.selected').first()
  const op = row.locator('.wb-chip.chip-op').first()
  if (await op.count()) return (await op.innerText()).trim()
  return (await row.locator('.wb-chip.chip-op-empty').first().innerText()).trim()
}

;(async () => {
  fs.mkdirSync(shots, { recursive: true })
  browser = await chromium.launch()
  const page = await browser.newPage({ viewport: { width: 1680, height: 1000 } })
  page.setDefaultTimeout(20000)
  const pageErrors = []
  page.on('pageerror', e => pageErrors.push(e.message))

  // ---- 准备：记下任务 3 那个字段映射动作原来的配置（跑完一定放回去：
  //      中途失败也一样——这一步写坏了会把这条调试链的规则丢掉）
  const actions = (await api('GET', `/jobs/${JOB_ID}/actions`)).data
  const mapAction = actions.find(a => a.type === 'FIELD_MAP' && a.name === MAP_ACTION_NAME)
  if (!mapAction) throw new Error(`任务 ${JOB_ID} 里没有 FIELD_MAP 动作「${MAP_ACTION_NAME}」`)
  const before = { name: mapAction.name, type: mapAction.type, index: mapAction.index, addition: mapAction.addition }
  restore = () => api('PUT', `/actions/${mapAction.id}`, before)
  const beforeRules = before.addition.rules
  check('准备：任务 3 的字段映射动作有已存规则', beforeRules.length > 0, `${beforeRules.length} 条`)

  await openEditor(page)
  check('编辑器打开：行铺出来了', await page.locator('.wb-row').count() > 0)
  check('逐行配置这一边：没有手写表达式那一块',
    await page.locator('.wb-expression-input').count() === 0)

  // 先点一次「校验配置」：行右侧的「问题」那一列才会出现（窄屏要连它一起验）
  const [validateResp] = await Promise.all([
    page.waitForResponse(r => r.url().includes('/field-mapping-editor/validate') && r.request().method() === 'POST'),
    wb(page).getByRole('button', { name: '校验配置' }).click(),
  ])
  const validateBody = JSON.parse(validateResp.request().postData() ?? '{}')
  const validateData = (await validateResp.json()).data
  check('逐行配置的草稿就是规则树那一份（没有 mode / expression）',
    !!validateBody.draft.rules && validateBody.draft.mode === undefined,
    JSON.stringify(validateBody.draft).slice(0, 120))
  check('校验回来了：没问题（这条调试链本来就是配好的）', validateData.valid === true,
    JSON.stringify(validateData.errors))
  await page.waitForSelector('.wb-col-issues', { timeout: 10000 })

  // ---------------------------------------------------------------- 一、窄屏

  for (const width of [1440, 1280, 1100, 1024, 900, 820, 700, 560]) {
    await page.setViewportSize({ width, height: 950 })
    await page.waitForTimeout(260)
    const geometry = await rowGeometry(page)
    const overflow = await page.evaluate(() => ({
      doc: document.documentElement.scrollWidth,
      inner: window.innerWidth,
      headRight: Math.max(...[...document.querySelectorAll('.wb-head-actions > *')]
        .map(el => el.getBoundingClientRect().right)),
    }))
    check(`${width}px：页面没有横向溢出`, overflow.doc <= overflow.inner + 1,
      `scrollWidth=${overflow.doc} innerWidth=${overflow.inner}`)
    check(`${width}px：行头没有顶出窗口`, overflow.headRight <= overflow.inner + 1,
      `headRight=${Math.round(overflow.headRight)}`)
    const bad = []
    for (const [i, g] of geometry.entries()) {
      if (overlaps(g.target, g.value)) bad.push(`第 ${i + 1} 行：目标字段与映射重叠`)
      if (overlaps(g.value, g.issues)) bad.push(`第 ${i + 1} 行：映射与问题重叠`)
      if (overlaps(g.issues, g.actions)) bad.push(`第 ${i + 1} 行：问题与行操作重叠`)
      if (!inside(g.row, g.target) || !inside(g.row, g.value) || !inside(g.row, g.issues)) {
        bad.push(`第 ${i + 1} 行：有一块越过行边界`)
      }
      if (width <= 820) {
        if (!above(g.target, g.value)) bad.push(`第 ${i + 1} 行：目标字段没有排在映射上面`)
        if (g.issues && !above(g.value, g.issues)) bad.push(`第 ${i + 1} 行：问题没有排在映射下面`)
      } else if (!leftOf(g.target, g.value)) {
        bad.push(`第 ${i + 1} 行：目标字段与映射没有并排`)
      }
    }
    check(`${width}px：${geometry.length} 行的四块互不重叠、依次排列`, bad.length === 0, bad.slice(0, 3).join('；'))
    if ([1024, 820, 560].includes(width)) {
      await page.screenshot({ path: `${shots}/narrow-${width}.png` })
    }
  }

  await page.setViewportSize({ width: 1440, height: 950 })
  await page.waitForTimeout(200)

  // ---------------------------------------------------------------- 二、键盘

  /** 焦点不在输入框里（点空白处就是这个效果）。 */
  const blurActive = () => page.evaluate(() => document.activeElement?.blur?.())

  // 点「这一行本身」而不是它的几何中心：行里从左到右铺着来源块 / 「换转换」这类小块，
  // 中心压在哪一块上会跟着字号走——#122 把字号收成两级令牌之后（12px → 13.5px）中心正好压在
  // 「换转换」上，那一下会开转换面板，于是 ↓ 归面板、不走行。这里要验的是「选中一行之后键盘走行」，
  // 点行的左内边距（x < 8px 那条）才是「选中这一行」的稳定落点。
  await page.locator('.wb-row').first().click({ position: { x: 4, y: 12 } })
  await blurActive()
  const firstSelected = await selectedTarget(page)
  await page.keyboard.press('ArrowDown')
  const afterDown = await selectedTarget(page)
  check('键盘：下键走到下一行', afterDown !== firstSelected, `${firstSelected} → ${afterDown}`)
  await page.keyboard.press('ArrowUp')
  check('键盘：上键走回上一行', await selectedTarget(page) === firstSelected)

  // 换转换：`/` 打开面板，↑↓ 走选项，回车用这一个
  const beforeOp = await selectedTransform(page)
  await page.keyboard.press('/')
  await page.waitForSelector('.wb-panel .wb-opbtn', { timeout: 5000 })
  check('键盘：`/` 打开了换转换面板', await page.locator('.wb-panel .wb-opbtn').count() === 7)
  const kbFirst = (await page.locator('.wb-opbtn.kb-current').first().innerText()).trim()
  check('键盘：面板打开时停在现在用的那一种上', kbFirst.startsWith(beforeOp) || beforeOp === '换转换',
    `${beforeOp} vs ${kbFirst}`)
  await page.keyboard.press('ArrowDown')
  const kbSecond = (await page.locator('.wb-opbtn.kb-current').first().innerText()).trim()
  check('键盘：↓ 在选项之间走', kbSecond !== kbFirst, `${kbFirst} → ${kbSecond}`)
  await page.keyboard.press('ArrowUp')
  await page.keyboard.press('Escape')
  check('键盘：Esc 收起面板', await page.locator('.wb-panel').count() === 0)

  // 真换一次：`/` → ↓（数字转换）→ 回车
  await page.keyboard.press('/')
  await page.waitForSelector('.wb-panel .wb-opbtn', { timeout: 5000 })
  await page.keyboard.press('ArrowDown')
  await page.keyboard.press('Enter')
  const afterOp = await selectedTransform(page)
  check('键盘：回车用键盘停着的那一种转换', afterOp.includes('数字转换'), afterOp)
  check('键盘：不需要参数的转换选完就收面板', await page.locator('.wb-panel').count() === 0)
  // 换回去（直接映射）
  await page.keyboard.press('/')
  await page.waitForSelector('.wb-panel .wb-opbtn', { timeout: 5000 })
  await page.keyboard.press('ArrowUp')
  await page.keyboard.press('Enter')
  check('键盘：能换回直接映射', (await selectedTransform(page)) === '换转换')

  // 选来源：真的只用键盘走一遍——加一行（光标就落在目标字段里）→ 补全归它 → Esc 关下拉 →
  // 回车进「选来源」→ ↑↓ 走清单 → 回车填进去。全程不点鼠标、也不靠脚本替用户失焦。
  // 注意：补全下拉是 teleport 到 body 的（每行一个 popper，10 个都在 DOM 里），
  // 「开着」认的是「有高度的那个」（关着时高度为 0）。
  const suggestionOpen = (open) => page.waitForFunction(want => {
    const visible = [...document.querySelectorAll('.el-autocomplete-suggestion')]
      .filter(el => el.getBoundingClientRect().height > 0)
    return (visible.length > 0) === want
  }, open, { timeout: 5000 })

  await wb(page).getByRole('button', { name: '+ 字段映射' }).click()
  await page.waitForTimeout(200)
  // ① 补全下拉开着时，↑↓ 与回车都归补全（这一条是那个「输入框里也让开」的例外的反面）
  await page.keyboard.type('order')
  await suggestionOpen(true)
  const beforeSuggestion = await selectedTarget(page)
  await page.keyboard.press('ArrowDown')
  await page.waitForTimeout(150)
  check('补全下拉开着时 ↑↓ 换的是补全项，不换行',
    (await selectedTarget(page)) === beforeSuggestion, await selectedTarget(page))
  await page.keyboard.press('Enter')
  await page.waitForTimeout(250)
  check('补全下拉开着时回车选中补全项，这一次不进「选来源」',
    (await selectedTarget(page)) === 'order_no' && await page.locator('.wb-source-list.picking').count() === 0,
    await selectedTarget(page))
  // ② 手输一个清单里没有的路径：下拉没有候选就自己收了，这时同一个输入框里回车就是「选来源」
  //    （不用先 Tab 走开，也不用鼠标）
  await page.locator('.wb-row.selected .wb-col-target input').fill('keyboard_target')
  await suggestionOpen(false)
  check('手输清单里没有的路径：补全不再铺一整列不相干的字段', true)
  check('焦点还在目标字段里（没有被谁挪走）',
    await page.evaluate(() => document.activeElement?.tagName === 'INPUT'),
    await page.evaluate(() => `${document.activeElement?.tagName}.${document.activeElement?.className}`))
  await page.keyboard.press('Enter')
  await page.waitForSelector('.wb-source-list.picking', { timeout: 5000 })
  check('键盘：回车进了「选来源」这一段', await page.locator('.wb-source-item.kb-current').count() === 1)
  check('键盘：这一段开了就说清怎么走',
    (await page.locator('.wb-source-list').locator('..').innerText()).includes('回车填进这一行'))
  const pickFirst = (await page.locator('.wb-source-item.kb-current .wb-source-name').first().innerText()).trim()
  await page.keyboard.press('ArrowDown')
  const pickSecond = (await page.locator('.wb-source-item.kb-current .wb-source-name').first().innerText()).trim()
  check('键盘：↓ 在来源清单里走', pickSecond !== pickFirst, `${pickFirst} → ${pickSecond}`)
  await page.keyboard.press('Enter')
  await page.waitForTimeout(250)
  // 填完会自己往下走一行（走到哪儿不由这一行说了算），所以按目标字段找回刚填的那一行
  const filledRow = await rowByTarget(page, 'keyboard_target')
  const pickedSource = (await filledRow.locator('.wb-chip.chip-src').first().innerText()).trim()
  check('键盘：回车把清单里那一项填进了这一行', pickedSource.includes(pickSecond.split(' · ').pop()),
    `${pickedSource} vs ${pickSecond}`)
  check('键盘：填完退出「选来源」这一段', await page.locator('.wb-source-list.picking').count() === 0)
  check('键盘：填了一行的来源之后，这一行有来源小块',
    await filledRow.locator('.wb-chip.chip-src').count() === 1)
  await page.screenshot({ path: `${shots}/keyboard-source.png` })

  // 清除当前行：退格（清除的是「选中这一行」，所以先把这一行选上——点行里的空白处，
  // 不点那些小块：小块各有各的点击行为，点「清除」就不是「用键盘清除」了）
  await filledRow.click({ position: { x: 4, y: 4 } })
  await blurActive()
  await page.waitForTimeout(120)
  check('先选中要清除的那一行',
    (await selectedTarget(page)) === 'keyboard_target', await selectedTarget(page))
  await page.keyboard.press('Backspace')
  await page.waitForTimeout(200)
  check('键盘：退格清除了当前行的取值方式',
    (await filledRow.locator('.wb-chip.chip-src').first().innerText()).includes('还没选来源'),
    await filledRow.locator('.wb-col-value').first().innerText())
  check('键盘：清除之后给了撤销', await filledRow.locator('.wb-chip.chip-undo').count() === 1)
  await filledRow.locator('.wb-chip.chip-undo').click()
  await page.waitForTimeout(200)
  check('键盘清除之后照样能撤销回来',
    (await filledRow.locator('.wb-chip.chip-src').first().innerText()).includes(pickSecond.split(' · ').pop()))

  // ---------------------------------------------------------------- 三、手写 JSONata

  check('「高级：手写 JSONata」入口在界面上', await page.locator('.wb-advanced').count() === 1)
  await page.locator('.wb-advanced').click()
  const dialog = page.locator('.el-message-box')
  await dialog.waitFor({ timeout: 5000 })
  const dialogText = await dialog.innerText()
  check('切过去之前说清了这条路跳过规则树校验', dialogText.includes('跳过规则树校验'), dialogText.slice(0, 160))
  check('切过去之前说清了规则树会被替换', dialogText.includes('规则树') && dialogText.includes('替换'))
  await page.screenshot({ path: `${shots}/expression-entry.png` })
  await dialog.getByRole('button', { name: '切到手写表达式' }).click()
  await page.waitForSelector('.wb-expression-input textarea', { timeout: 5000 })
  check('切过去之后是写完整表达式的地方', await page.locator('.wb-expression-input textarea').count() === 1)
  check('手写这一边没有「默认来源」下拉了', await page.locator('.wb-step-select').count() === 0)
  const exprAlert = await page.locator('.wb-expression .wb-alert').innerText()
  check('界面上写着这条路跳过规则树校验', exprAlert.includes('跳过规则树校验'), exprAlert.slice(0, 120))

  await page.locator('.wb-expression-input textarea').fill(EXPRESSION)
  await page.screenshot({ path: `${shots}/expression.png` })

  const [exprValidate] = await Promise.all([
    page.waitForResponse(r => r.url().includes('/field-mapping-editor/validate') && r.request().method() === 'POST'),
    wb(page).getByRole('button', { name: '校验配置' }).click(),
  ])
  const exprDraft = JSON.parse(exprValidate.request().postData() ?? '{}')
  const exprResult = (await exprValidate.json()).data
  check('手写这一边送出去的草稿与后端 mode=EXPRESSION 的约定一致',
    same(exprDraft.draft, { mode: 'EXPRESSION', expression: EXPRESSION, rules: [] }),
    JSON.stringify(exprDraft.draft).slice(0, 160))
  check('后端收下了这份手写表达式（valid）', exprResult.valid === true, JSON.stringify(exprResult.errors))
  check('这条路跳过规则树校验：后端原样返回表达式当编译产物',
    exprResult.compiled.jsonata === EXPRESSION, String(exprResult.compiled.jsonata).slice(0, 80))
  const issueLine = await page.locator('.wb-expression-issues').innerText()
  check('手写这一边也把校验结果显示出来', issueLine.includes('没有发现问题'), issueLine.slice(0, 120))

  // 保存：等真正那次 PUT 回来再断言（不靠提示条——上一条提示可能还在屏幕上）
  const [exprPut] = await Promise.all([
    page.waitForResponse(r => r.request().method() === 'PUT' && /\/api\/actions\/\d+$/.test(r.url()) && r.ok(),
      { timeout: 30000 }),
    wb(page).getByRole('button', { name: '保存', exact: true }).click(),
  ])
  const exprSaved = JSON.parse(exprPut.request().postData() ?? '{}')
  check('保存发出去的就是 mode=EXPRESSION 那份配置',
    same(exprSaved.addition, { mode: 'EXPRESSION', expression: EXPRESSION, rules: [] }),
    JSON.stringify(exprSaved).slice(0, 300))
  const savedAction = (await api('GET', `/jobs/${JOB_ID}/actions`)).data.find(a => a.id === mapAction.id)
  check('数据库里存下来的就是这份手写表达式',
    savedAction.addition.mode === 'EXPRESSION' && savedAction.addition.expression === EXPRESSION,
    JSON.stringify(savedAction.addition).slice(0, 160))

  // 重开：直接回到手写这一边，表达式回填
  await openEditor(page)
  await page.waitForSelector('.wb-expression-input textarea', { timeout: 10000 })
  check('重开编辑器：直接进手写这一边（不用再点一次入口）',
    await page.locator('.wb-expression-input textarea').count() === 1)
  check('重开编辑器：表达式回填了',
    (await page.locator('.wb-expression-input textarea').inputValue()) === EXPRESSION)

  // ---------------------------------------------------------------- 切回逐行配置

  await wb(page).getByRole('button', { name: '切回逐行配置' }).click()
  const backDialog = page.locator('.el-message-box')
  await backDialog.waitFor({ timeout: 5000 })
  const backText = await backDialog.innerText()
  check('切回之前说清了规则树会替换掉这份表达式',
    backText.includes('规则树') && backText.includes('替换'), backText.slice(0, 160))
  await backDialog.getByRole('button', { name: '切回去' }).click()
  await page.waitForSelector('.wb-row', { timeout: 10000 })
  const backAlert = await page.locator('.wb > .wb-alert').first().innerText()
  check('切回之后界面上常驻一句「规则树会替换掉这份表达式」',
    backAlert.includes('规则树') && backAlert.includes('替换'), backAlert.slice(0, 160))
  check('切回之后又看到逐行配置的行列表', await page.locator('.wb-row').count() > 0)
  await page.screenshot({ path: `${shots}/back-to-rows.png` })

  // 键盘配一行，好让「规则树这一边」也有东西可存
  await page.locator('.wb-row').first().click({ position: { x: 4, y: 4 } })
  await blurActive()
  const backRowTarget = await selectedTarget(page)
  await page.keyboard.press('Enter')
  await page.waitForSelector('.wb-source-list.picking', { timeout: 5000 })
  await page.keyboard.press('Enter')
  await page.waitForTimeout(250)
  const backFilled = await rowByTarget(page, backRowTarget)
  check('切回逐行之后键盘照样能配一行',
    !(await backFilled.locator('.wb-chip.chip-src').first().innerText()).includes('还没选来源'),
    await backFilled.locator('.wb-col-value').first().innerText())

  await wb(page).getByRole('button', { name: '保存', exact: true }).click()
  const replaceDialog = page.locator('.el-message-box')
  await replaceDialog.waitFor({ timeout: 5000 })
  check('保存前再确认一次「会替换手写表达式」',
    (await replaceDialog.innerText()).includes('替换'))
  const [rowsPut] = await Promise.all([
    page.waitForResponse(r => r.request().method() === 'PUT' && /\/api\/actions\/\d+$/.test(r.url()) && r.ok(),
      { timeout: 30000 }),
    (async () => {
      await replaceDialog.getByRole('button', { name: '替换并保存' }).click()
      // 刚切回来的一边只有一行配好了，其余的行还没配：照旧先问一次「这些行不会被保存」
      //（上一个确认框这时还在 DOM 里做退场动画，所以按文案认这一个）
      const keepDialog = page.locator('.el-message-box').filter({ hasText: '不会被保存' })
      await keepDialog.waitFor({ timeout: 5000 })
      check('没配好的行还会再问一次（说清这些行不会被保存）',
        (await keepDialog.innerText()).includes('不会被保存'))
      await keepDialog.getByRole('button', { name: '只保存配好的' }).click()
    })(),
  ])
  const rowsSaved = JSON.parse(rowsPut.request().postData() ?? '{}').addition
  check('保存发出去的是规则树那一份（没有 mode）',
    !!rowsSaved && Array.isArray(rowsSaved.rules) && rowsSaved.mode === undefined,
    JSON.stringify(rowsSaved).slice(0, 160))
  check('规则树这一份里每条顶层规则都显式写着 sourceStep',
    !!rowsSaved && rowsSaved.rules.every(r => r.sourceStep !== undefined),
    JSON.stringify(rowsSaved?.rules?.map(r => [r.target, r.sourceStep])).slice(0, 200))
  const afterRows = (await api('GET', `/jobs/${JOB_ID}/actions`)).data.find(a => a.id === mapAction.id)
  check('数据库里手写表达式换成了规则树',
    afterRows.addition.mode === undefined && Array.isArray(afterRows.addition.rules))

  // ---- 还原：任务 3 原来的映射规则放回去
  await restore()
  const restored = (await api('GET', `/jobs/${JOB_ID}/actions`)).data.find(a => a.id === mapAction.id)
  check('跑完把任务 3 原来的规则放回去了', same(restored.addition, before.addition))

  check('全程没有页面报错', pageErrors.length === 0, pageErrors.slice(0, 2).join(' | '))

  console.log(`\n# ${failures.length ? `${failures.length} 项没过` : '全部通过'}`)
  await browser.close()
  browser = null
  process.exit(failures.length ? 1 : 0)
})().catch(async e => {
  console.error('脚本自己出错了：', e)
  // 中途出错也要把任务 3 原来的配置放回去（否则这条调试链的规则就丢了）
  if (restore) {
    try {
      await restore()
      console.log('（已把任务 3 原来的配置放回去）')
    } catch (e2) {
      console.error('还原也失败了：', e2.message)
    }
  }
  if (browser) await browser.close()
  process.exit(1)
})
