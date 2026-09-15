/*
 * #105 真机 UI 验收：配得快一点（搜索、过滤、自动匹配）。
 *
 * 用任务 3（DBG-TS，FIELD_MAP 动作 8）：9 条已存规则，目标表 dbg_receipt 探得出来，
 * 其中 `unit` 这一列既没配过、也不是必填（nullable），正好把空行与必填行区分开。
 *
 * 验的八条（票里的验收）：
 *   1. 来源清单按路径与样例值搜；
 *   2. 已引用的来源有高亮并写着被引用几处；
 *   3. 三档过滤（全部 / 只看未配置 / 只看必填）；
 *   4. 按目标字段搜并跳到那一行；
 *   5. 一键展开 / 收起所有数组行；
 *   6. 「自动匹配同名」只填空行、不覆盖已经配好的行；
 *   7. 自动匹配一次**网络请求都不发**（全过程盯着后端请求日志）；
 *   8. 自动填上的行标「自动」，校验时给一条提醒确认的提示，点一下能跳到那一行。
 * 另外顺手验：手点一次来源之后「自动」标记掉（提醒只对没碰过的行有用）。
 *
 * 全程不保存：数据库不动，跑完直接关页面。
 *
 * 跑法（playwright 是本目录自带的，先 `npm i` 一次就行）：
 *   cd tools/ui && npm i && node fast-config-105.cjs
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
  await page.waitForSelector('.wb-row', { timeout: 25000 })
  await page.waitForTimeout(1200)
}

/** 左边清单上现在铺出来的那些项。 */
async function pickerItems(page) {
  return page.locator('.wb-source-item').evaluateAll(els => els.map(el => ({
    name: el.querySelector('.wb-source-name')?.textContent?.trim() ?? '',
    used: el.classList.contains('used'),
    badges: [...el.querySelectorAll('.wb-chip')].map(c => c.textContent.trim()),
    title: el.getAttribute('title') ?? '',
  })))
}

/** 左边三档按钮上的数字：`{ 全部: 4, 只看未配置: 0, 只看必填: 0 }`。 */
async function filterCounts(page) {
  const texts = await page.locator('.wb-source-filters .wb-filterbtn').allInnerTexts()
  const out = {}
  for (const text of texts) {
    const [label, count] = text.trim().split(/\s+/)
    out[label] = Number(count)
  }
  return out
}

/** 点左边清单的某一档（限在左边那一排按钮里：右边行列表也有一排同名按钮）。 */
function sourceFilterButton(page, label) {
  return page.locator('.wb-source-filters .wb-filterbtn').filter({ hasText: label })
}

/**
 * 按目标字段路径找一行。
 *
 * 不能用 `input[value="…"]` 选：Vue 往 el-input 里写的是 DOM 属性，HTML 属性还停在初始值上
 * （真机实测：那样选一个也选不到，等到超时）。所以按 `.wb-col-target input` 的实际值找下标。
 */
async function rowByTarget(page, target) {
  const values = await page.locator('.wb-row .wb-col-target input').evaluateAll(els => els.map(e => e.value))
  const index = values.indexOf(target)
  if (index < 0) throw new Error(`找不到目标字段为 ${target} 的行（现有：${values.join(',')}）`)
  return page.locator('.wb-row').nth(index)
}

/**
 * 从这一批目标字段里挑一个「正好命中两行」的搜索词（子串）。
 *
 * 不写死一个词：换一份配置、改一个字段名，写死的词就红了。找不到这样的子串时返回 null，
 * 调用方会把这一条报出来（那时这份配置确实验不了「回车在命中项之间走一圈」）。
 */
function pickSharedFragment(values) {
  for (const value of values) {
    for (let len = value.length - 1; len >= 2; len--) {
      for (let start = 0; start + len <= value.length; start++) {
        const piece = value.slice(start, start + len)
        if (values.filter(v => v.includes(piece)).length === 2) return piece
      }
    }
  }
  return null
}

;(async () => {
  fs.mkdirSync(shots, { recursive: true })
  browser = await chromium.launch()
  const page = await browser.newPage({ viewport: { width: 1680, height: 1000 } })
  page.setDefaultTimeout(20000)
  const pageErrors = []
  page.on('pageerror', e => pageErrors.push(e.message))

  /** 全过程的后端请求日志（自动匹配那两段要断言它一条都没发）。 */
  const apiCalls = []
  page.on('request', req => {
    const url = req.url()
    if (url.startsWith(API)) apiCalls.push(`${req.method()} ${new URL(url).pathname}`)
  })

  await openEditor(page, JOB_ID)
  const rowCount = await page.locator('.wb-row').count()
  // 已存 9 条规则 + 目标表里那个没配过的 `unit` 列 → 10 行
  check('编辑器打开，已存规则 + 目标清单里那个空行都铺出来了', rowCount === 10, `rows=${rowCount}`)

  // ---------------------------------------------------------------- 1. 来源清单：搜路径、搜样例值
  const allItems = await pickerItems(page)
  check('来源清单列出了这一来源的 4 个字段',
    allItems.map(i => i.name).join(',') === 'id,order_no,status,updated_at',
    JSON.stringify(allItems.map(i => i.name)))

  const search = page.locator('.wb-source-search input')
  await search.fill('order_no')
  await page.waitForTimeout(300)
  check('按路径搜：只剩 order_no 一项',
    (await pickerItems(page)).map(i => i.name).join(',') === 'order_no',
    JSON.stringify((await pickerItems(page)).map(i => i.name)))

  await search.fill('DBG-9002')
  await page.waitForTimeout(300)
  check('按样例值搜：`DBG-9002` 搜到 order_no（它的样例值）',
    (await pickerItems(page)).map(i => i.name).join(',') === 'order_no',
    JSON.stringify((await pickerItems(page)).map(i => i.name)))

  await search.fill('INACTIVE')
  await page.waitForTimeout(300)
  check('按样例值搜：`INACTIVE` 搜到 status',
    (await pickerItems(page)).map(i => i.name).join(',') === 'status',
    JSON.stringify((await pickerItems(page)).map(i => i.name)))

  await search.fill('不存在的字段')
  await page.waitForTimeout(300)
  check('搜不到时说清是搜索词没对上',
    (await page.locator('.wb-source .wb-empty').innerText()).includes('没有路径或样例值里有'),
    await page.locator('.wb-source .wb-empty').innerText())

  await search.fill('')
  await page.waitForTimeout(300)
  check('清掉搜索词之后 4 个字段都回来了', (await pickerItems(page)).length === 4)
  await page.screenshot({ path: `${shots}/fast-config-source-search.png` })

  // ---------------------------------------------------------------- 2. 已引用的来源：高亮 + 计数
  const usedItems = await pickerItems(page)
  const usedMap = Object.fromEntries(usedItems.map(i => [i.name, i.badges.filter(b => b.endsWith('处'))[0] ?? '']))
  // 已存规则的引用：order_no×2（order_no 那一行 + material_name 那一行）、status×2、updated_at×2、id×2
  check('order_no 有高亮', usedItems.find(i => i.name === 'order_no').used, JSON.stringify(usedItems))
  check('order_no 的处数是 2（order_no + material_name）', usedMap.order_no === '2 处', usedMap.order_no)
  check('status 的处数是 2（status + vendor_name）', usedMap.status === '2 处', usedMap.status)
  check('updated_at 的处数是 2', usedMap.updated_at === '2 处', usedMap.updated_at)
  check('id 的处数是 2', usedMap.id === '2 处', usedMap.id)
  const hint = usedItems.find(i => i.name === 'order_no').title
  check('悬停提示说清被几处引用、第一处是哪一行',
    hint.includes('已经被引用了 2 处') && hint.includes('order_no'), hint)

  // ---------------------------------------------------------------- 3. 三档过滤
  //    这份配置的必填目标只有 order_no（进度条：必填 1/1），它已经配好了；四个来源字段全都被用过。
  //    所以「只看未配置」与「只看必填」此刻都空着——两个 0 都是有意义的答案，界面也要说清原因。
  const counts = await filterCounts(page)
  check('三档按钮上的数字：全部 4 / 只看未配置 0 / 只看必填 0',
    counts['全部'] === 4 && counts['只看未配置'] === 0 && counts['只看必填'] === 0,
    JSON.stringify(counts))

  await sourceFilterButton(page, '只看未配置').click()
  await page.waitForTimeout(300)
  check('「只看未配置」：4 个字段都被用过，清单空着并说明原因',
    (await pickerItems(page)).length === 0
      && (await page.locator('.wb-source .wb-empty').innerText()).includes('都已经被用过了'),
    await page.locator('.wb-source .wb-empty').innerText())

  await sourceFilterButton(page, '只看必填').click()
  await page.waitForTimeout(300)
  check('「只看必填」：必填的 order_no 已经配好了，这一档也空着',
    (await pickerItems(page)).length === 0
      && (await page.locator('.wb-source .wb-empty').innerText()).includes('必填的目标字段都配好了'),
    await page.locator('.wb-source .wb-empty').innerText())

  await sourceFilterButton(page, '全部').click()
  await page.waitForTimeout(300)
  check('「全部」：4 个字段回来', (await pickerItems(page)).length === 4)
  await page.screenshot({ path: `${shots}/fast-config-source-filter.png` })

  // 把必填的 order_no 那一行清掉 → 它成了「还没配好的必填字段」，「只看必填」就该给出 order_no。
  // （顺带把后面自动匹配那一段要用的空行准备出来。）
  const orderRow = await rowByTarget(page, 'order_no')
  await orderRow.getByRole('button', { name: '清除' }).click()
  await page.waitForTimeout(700)
  const clearedCounts = await filterCounts(page)
  check('清掉必填行之后「只看必填」这一档给出 1 个来源',
    clearedCounts['只看必填'] === 1, JSON.stringify(clearedCounts))
  await sourceFilterButton(page, '只看必填').click()
  await page.waitForTimeout(300)
  check('「只看必填」筛出来的正是 order_no 要用的那个来源',
    (await pickerItems(page)).map(i => i.name).join(',') === 'order_no',
    JSON.stringify((await pickerItems(page)).map(i => i.name)))
  await sourceFilterButton(page, '全部').click()
  await page.waitForTimeout(300)

  // ---------------------------------------------------------------- 4. 按目标字段搜并跳行
  //    搜索比的是**目标字段**的完整路径（来源字段有左边那个搜索框）；数组元素子行带上所属数组行。
  //    查询词从当前这些目标字段里现挑一个能命中两行的（写死一个词的话，换一份配置就红了）。
  const targetSearch = page.locator('.wb-target-search input')
  const targetValues = (await page.locator('.wb-row .wb-col-target input').evaluateAll(els => els.map(e => e.value)))
    .filter(v => v !== '')
  const query = pickSharedFragment(targetValues)
  check('这份配置里挑得出一个命中两行的搜索词', !!query, targetValues.join(','))
  const expectRows = targetValues.filter(v => v.includes(query))
  console.log(`    （搜索词 ${JSON.stringify(query)} → 期望命中 ${expectRows.join(' / ')}）`)

  await targetSearch.fill(query)
  await page.waitForTimeout(300)
  const note = await page.locator('.wb-match-note').innerText()
  check('搜索框旁边说命中 2 行', note.includes('2 行命中'), note)

  await targetSearch.press('Enter')
  await page.waitForTimeout(400)
  const selected = await page.locator('.wb-row.selected .wb-col-target input').inputValue()
  check('回车跳到命中的那一行', expectRows.includes(selected), selected)

  await targetSearch.press('Enter')
  await page.waitForTimeout(400)
  const selected2 = await page.locator('.wb-row.selected .wb-col-target input').inputValue()
  check('再回车跳到下一个命中（一路回车在命中项之间走）',
    selected2 !== selected && expectRows.includes(selected2), `${selected} → ${selected2}`)

  await targetSearch.fill('unit')
  await page.waitForTimeout(300)
  await targetSearch.press('Enter')
  await page.waitForTimeout(400)
  check('跳到 unit 那一行（清单里没配过的那个目标字段）',
    await (await rowByTarget(page, 'unit')).evaluate(el => el.classList.contains('selected')))
  await page.screenshot({ path: `${shots}/fast-config-target-search.png` })
  await targetSearch.fill('')
  await page.waitForTimeout(300)

  // ---------------------------------------------------------------- 5. 一键展开 / 收起数组行
  //    这份配置里没有数组行，先手工加一行数组映射（顺带验证：刚加的空行立刻铺得出来），
  //    再把它的子行加出来。
  const beforeAdd = await page.locator('.wb-row').count()
  await page.getByRole('button', { name: '+ 数组映射' }).click()
  await page.waitForTimeout(500)
  check('刚加的空行立刻铺出来了（不会被行过滤挡住）',
    await page.locator('.wb-row').count() === beforeAdd + 1,
    `${beforeAdd} → ${await page.locator('.wb-row').count()}`)

  // 这一行的行号就是它的目标字段，所以用 `data-row-id` 定位（`last()` 在列表重排后会指到别的行）。
  let arrayRow = page.locator('.wb-row').last()
  const arrayRowId = await arrayRow.getAttribute('data-row-id')
  await arrayRow.locator('.wb-col-target input').click()
  await arrayRow.locator('.wb-col-target input').fill('items')
  await page.keyboard.press('Escape')
  await page.waitForTimeout(400)
  arrayRow = page.locator(`.wb-row[data-row-id="${arrayRowId}"]`)
  check('数组映射行上给出了「+ 子字段」',
    await arrayRow.getByRole('button', { name: '+ 子字段' }).count() === 1,
    await arrayRow.innerText())
  await arrayRow.getByRole('button', { name: '+ 子字段' }).click()
  await page.waitForTimeout(400)
  arrayRow = page.locator(`.wb-row[data-row-id="${arrayRowId}"]`)
  await arrayRow.getByRole('button', { name: '+ 子字段' }).click()
  await page.waitForTimeout(500)
  const withChildren = await page.locator('.wb-row').count()
  check('数组行下面加了两个子行（收起前铺得出来）', withChildren === beforeAdd + 3, `rows=${withChildren}`)

  await page.getByRole('button', { name: '收起全部数组' }).click()
  await page.waitForTimeout(400)
  const collapsed = await page.locator('.wb-row').count()
  check('一键收起：两个子行不铺了', collapsed === beforeAdd + 1, `rows=${collapsed}`)
  check('按钮换成「展开全部数组」',
    await page.getByRole('button', { name: '展开全部数组' }).count() === 1)

  await page.getByRole('button', { name: '展开全部数组' }).click()
  await page.waitForTimeout(400)
  check('一键展开：子行都回来了', await page.locator('.wb-row').count() === beforeAdd + 3)
  await page.screenshot({ path: `${shots}/fast-config-arrays.png` })

  // ---------------------------------------------------------------- 6 + 7. 自动匹配同名：只填空行、不发请求
  //    已经配好的行长什么样：kind 是常量行（不取来源），unit 是没配过的空行；order_no 刚被清成空行。
  const kindBefore = await (await rowByTarget(page, 'kind')).locator('.wb-col-value').innerText()
  check('kind 那一行是常量行（自动匹配不该碰它）', kindBefore.includes('常量'), kindBefore)
  const unitBefore = await (await rowByTarget(page, 'unit')).locator('.wb-col-value').innerText()
  check('unit 那一行还没配（空行）', unitBefore.includes('还没选来源'), unitBefore)

  apiCalls.length = 0
  await page.getByRole('button', { name: '自动匹配同名' }).click()
  await page.waitForTimeout(900)
  const toast = await page.locator('.el-message').allInnerTexts()
  check('自动匹配没有任何后端请求', apiCalls.length === 0, JSON.stringify(apiCalls))
  check('给出了填了几行的说法', toast.some(t => t.includes('已按同名填上')), JSON.stringify(toast))

  const refilled = await orderRow.locator('.wb-col-value').innerText()
  check('清掉的空行按同名被填回来了', refilled.includes('order_no'), refilled)
  check('填上的行上标着「自动」',
    await orderRow.locator('.wb-chip.chip-auto').count() === 1, refilled)
  const kindAfter = await (await rowByTarget(page, 'kind')).locator('.wb-col-value').innerText()
  check('已经配好的 kind 行一个字都没动', kindAfter.includes('常量'), kindAfter)
  const unitAfter = await (await rowByTarget(page, 'unit')).locator('.wb-col-value').innerText()
  check('来源里没有同名字段的空行不会被硬填（unit 还是空着）',
    unitAfter.includes('还没选来源'), unitAfter)
  await page.screenshot({ path: `${shots}/fast-config-automatch.png` })

  // ---------------------------------------------------------------- 8. 校验时给一条提醒确认的提示
  const [response] = await Promise.all([
    page.waitForResponse(r => r.url().includes('/field-mapping-editor/validate'), { timeout: 30000 }),
    page.locator('.wb-head-actions').getByRole('button', { name: '校验配置' }).click(),
  ])
  const body = await response.json()
  check('后端那份草稿里没有「自动」这个字段（它是编辑器里的本地事实）',
    !JSON.stringify(body.data).includes('"auto"'), JSON.stringify(body.data).slice(0, 200))
  const issues = await page.locator('.wb-issue').allInnerTexts()
  check('问题列表里多了一条前端出的提醒',
    issues.some(t => t.includes('自动匹配同名') && t.includes('order_no')), JSON.stringify(issues))

  const autoIssue = page.locator('.wb-issue').filter({ hasText: '自动匹配同名' }).first()
  check('那条提醒是警告，不是错误',
    (await autoIssue.getAttribute('class')).includes('warning'), await autoIssue.getAttribute('class'))
  await autoIssue.click()
  await page.waitForTimeout(400)
  check('点那条提醒跳到自动填上的那一行',
    await orderRow.evaluate(el => el.classList.contains('selected')))
  check('那一行右侧显示「有警告」',
    (await orderRow.locator('.wb-state').innerText()).trim() === '有警告',
    await orderRow.locator('.wb-state').innerText())
  await page.screenshot({ path: `${shots}/fast-config-auto-notice.png` })

  // 手点一次来源之后「自动」标记要掉（提醒只对没碰过的那一行有用）
  await page.locator('.wb-source-item').filter({ hasText: 'order_no' }).first().click()
  await page.waitForTimeout(600)
  check('手点一次来源之后「自动」标记掉了',
    await orderRow.locator('.wb-chip.chip-auto').count() === 0,
    await orderRow.locator('.wb-col-value').innerText())

  check('页面上没有 JS 报错', pageErrors.length === 0, JSON.stringify(pageErrors))

  console.log(`\n${failures.length ? `FAILED: ${failures.join(' | ')}` : 'ALL OK'}`)
  await browser.close()
  process.exit(failures.length ? 1 : 0)
})().catch(async e => {
  console.error(e)
  if (browser) await browser.close()
  process.exit(1)
})
