/*
 * #107 真机 UI 验收：撤掉旧编辑器（动作表上只剩一个「编辑映射」入口，打开的是新版编辑器）。
 *
 * 验收项 → 怎么验：
 *   1. 动作表上没有「编辑映射（新版）」这个临时入口；FIELD_MAP 行上那个「编辑映射」打开的是
 *      新版全屏编辑器（`.mapping-workbench-drawer`）。
 *   2. 「编辑」打开的弹窗里没有旧编辑器：没有「结构化规则 / 手写表达式」那两个单选、
 *      没有「来源样例 / 对接样例 / 获取推荐 / 一键自动填充」、没有映射关系表格（`.mapping-editor`）。
 *   3. 旧编辑器配过的配置（`{rules:[...]}`，就是库里现在这份）在新编辑器里正确回填；原样保存时
 *      不丢字段（比对 PUT 出去的 addition 与数据库里那份）。
 *   4. 一份都没配置过的动作（`addition = {}`）打开不报错。
 *   5. 弹窗里改名称 / 序号照样存得下去（旧编辑器删掉之后，这份 PUT 必须把原有映射带上，
 *      否则会被后端「至少需要一条映射规则」拒掉）。已有动作的弹窗里不该再有「去配字段映射」。
 *   6. 新建 FIELD_MAP：弹窗里「去配字段映射」开同一个编辑器，配一行之后点保存才建出动作
 *      （POST /jobs/{id}/actions，带名字与那份映射）。
 *
 * 跑法（playwright 是本目录自带的，先 `npm i` 一次就行）：
 *   cd tools/ui && npm i && node remove-old-editor-107.cjs
 * 跑之前前端 dev server（5173）与后端（8080）都要在。动作 8 的配置、脚本造的临时动作，
 * 跑完（或中途出错）都会还原。
 */
const fs = require('fs')
const path = require('path')
const { execFileSync } = require('child_process')
const { chromium } = require('playwright')

const APP = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080/api'
const MYSQL = 'C:\\Program Files\\MySQL\\MySQL Workbench 8.0\\mysql.exe'
const JOB_ID = 3
const ACTION_ID = 8
const shots = path.join(__dirname, 'shots')
const failures = []
let browser = null
/** 动作 8 原来的配置：跑到一半出错也要放回去（下面 catch 里用）。 */
let original = null
/** 「一份都没配过」那个临时动作的 id（空配置只能直接造：接口会把空规则拒掉）。 */
let tempActionId = null

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

/** 直接改库：老格式配置的回填、以及「一份都没配过」的空动作，这两条只能这么造出来。 */
function sql(statement) {
  const out = execFileSync(MYSQL, [
    '-h', '127.0.0.1', '-P', '3306', '-u', 'gateway', '-pgateway',
    '-D', 'edge_gateway', '--default-character-set=utf8mb4', '-N', '-B', '-e', statement,
  ], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
  return out.split(/\r?\n/).map(line => line.trim()).filter(Boolean)
}

const action = async id => (await api('GET', `/jobs/${JOB_ID}/actions`)).data.find(a => a.id === id)

/** 键序无关的 JSON 比较（后端 toMap 会重排键，值才是契约）。 */
function stable(value) {
  if (Array.isArray(value)) return value.map(stable)
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().map(k => [k, stable(value[k])]))
  }
  return value
}

const same = (a, b) => JSON.stringify(stable(a)) === JSON.stringify(stable(b))

/** 打开任务 3 的动作链（按 ID 认任务行，免得被名称编码坑到）。 */
async function openJob3(page) {
  await page.goto(`${APP}/jobs`, { waitUntil: 'networkidle' })
  const rows = page.locator('tbody tr')
  await rows.first().waitFor({ timeout: 20000 })
  const count = await rows.count()
  for (let i = 0; i < count; i++) {
    if ((await rows.nth(i).locator('td').first().innerText()).trim() === String(JOB_ID)) {
      await rows.nth(i).getByRole('button', { name: '动作' }).click()
      await page.waitForSelector('text=动作链', { timeout: 20000 })
      await page.waitForTimeout(400)
      return
    }
  }
  throw new Error(`任务列表里没有 id=${JOB_ID} 的行`)
}

const mapRow = page => page.locator('tbody tr', { hasText: 'FIELD_MAP' }).first()
const workbench = page => page.locator('.mapping-workbench-drawer')

/** 按动作 id 认动作表里的那一行。 */
async function actionRow(page, id) {
  const rows = page.locator('tbody tr').filter({ has: page.locator('td', { hasText: /^FIELD_MAP$/ }) })
  const count = await rows.count()
  for (let i = 0; i < count; i++) {
    const row = rows.nth(i)
    if ((await row.locator('td').first().innerText()).trim() === String(id)) return row
  }
  throw new Error(`动作表里没有 id=${id} 的行`)
}

/** 删掉本脚本造的临时动作（跑完 / 中途出错都要删）。 */
function dropTempActions() {
  sql(`delete from job_action where job_id=${JOB_ID} and name in ('T107-EMPTY', 'T107-UPSERT')`)
  tempActionId = null
}

;(async () => {
  fs.mkdirSync(shots, { recursive: true })
  browser = await chromium.launch()
  const page = await browser.newPage({ viewport: { width: 1680, height: 1000 } })
  page.setDefaultTimeout(20000)
  const pageErrors = []
  page.on('pageerror', e => pageErrors.push(e.message))

  original = (await action(ACTION_ID)).addition
  // 上一次跑崩了可能留下临时动作（接口不给空规则，所以这类动作只能直接造）；先清干净
  dropTempActions()
  sql(`delete from job_action where job_id=${JOB_ID} and name = 'T107-NEW'`)

  // ---------------------------------------------------------------- 1. 入口只剩一个
  await openJob3(page)
  check('动作表上没有「编辑映射（新版）」这个临时入口',
    (await mapRow(page).getByRole('button', { name: '编辑映射（新版）' }).count()) === 0)
  const mapButtons = await mapRow(page).getByRole('button', { name: '编辑映射', exact: true }).count()
  check('FIELD_MAP 行上有且只有一个「编辑映射」入口', mapButtons === 1, `count=${mapButtons}`)
  check('其余动作行上没有「编辑映射」入口',
    (await page.locator('tbody tr', { hasText: 'READ_SQL' }).first()
      .getByRole('button', { name: '编辑映射', exact: true }).count()) === 0)

  // ---------------------------------------------------------------- 2. 弹窗里没有旧编辑器
  await mapRow(page).getByRole('button', { name: '编辑', exact: true }).click()
  const dialog = page.locator('.el-dialog').filter({ hasText: '编辑动作' })
  await dialog.waitFor({ timeout: 10000 })
  await page.waitForTimeout(300)
  check('「编辑」弹窗里没有旧编辑器的模式单选',
    (await dialog.locator('.el-radio-button').count()) === 0)
  check('「编辑」弹窗里没有旧的样例框 / 推荐按钮 / 映射表格',
    (await dialog.getByText('来源样例', { exact: false }).count()) === 0
    && (await dialog.getByRole('button', { name: '获取推荐' }).count()) === 0
    && (await dialog.getByRole('button', { name: '一键自动填充' }).count()) === 0
    && (await dialog.locator('.mapping-editor, .rows-table, .tree-panel').count()) === 0)
  check('FIELD_MAP 的弹窗里写明了映射与名称是同一次保存',
    (await dialog.innerText()).includes('改名称 / 序号顺便保存映射'),
    (await dialog.innerText()).slice(0, 120))
  check('已有动作的弹窗里没有「去配字段映射」（不然会拿空草稿盖掉它）',
    (await dialog.getByRole('button', { name: '去配字段映射' }).count()) === 0)

  // ---------------------------------------------------------------- 3. 改名称 / 序号也存得下去
  // 旧编辑器在弹窗里，改名与改映射是同一次保存；旧编辑器删掉之后，弹窗里没有可编辑的映射，
  // 一份不带映射的 PUT 会被后端「至少需要一条映射规则」拒掉——所以这次 PUT 要把映射带上。
  await dialog.getByText('改名称 / 序号顺便保存映射').waitFor({ timeout: 5000 })
  await dialog.locator('.el-form-item', { hasText: '名称' }).locator('input').first().fill('map-T107')
  const [renamePut] = await Promise.all([
    page.waitForResponse(r => r.request().method() === 'PUT' && /\/api\/actions\/\d+$/.test(r.url()),
      { timeout: 30000 }),
    dialog.getByRole('button', { name: '保存', exact: true }).click(),
  ])
  const renameBody = JSON.parse(renamePut.request().postData() ?? '{}')
  check('改名称保存没被拦下（PUT 出去了）', renamePut.status() < 300, `status=${renamePut.status()}`)
  check('这次 PUT 把原有的映射原样带上了', same(renameBody.addition, original),
    JSON.stringify(renameBody.addition).slice(0, 160))
  await page.waitForTimeout(600)
  const renamed = await action(ACTION_ID)
  check('动作名称真的改了，映射没被清掉',
    renamed.name === 'map-T107' && same(renamed.addition, original), JSON.stringify(renamed).slice(0, 160))
  await api('PUT', `/actions/${ACTION_ID}`, { name: 'map', type: 'FIELD_MAP', index: 2, addition: original })

  // ---------------------------------------------------------------- 4. 动作表那一个入口开的是新版编辑器
  await openJob3(page)
  await mapRow(page).getByRole('button', { name: '编辑映射', exact: true }).click()
  await workbench(page).waitFor({ timeout: 25000 })
  await page.waitForSelector('.wb-row, .wb-empty', { timeout: 25000 })
  await page.waitForTimeout(800)
  check('打开的是新版编辑器（全屏抽屉）', (await workbench(page).count()) === 1)
  // 目标表 dbg_receipt 有 10 列，其中 unit 没配过——所以行数比规则数多一行（那行是留给它填的）
  const rowCount = await page.locator('.wb-row').count()
  check('新版编辑器把已存的规则铺成了行（外加清单里没配的那一行）',
    rowCount === original.rules.length + 1, `rows=${rowCount} rules=${original.rules.length}`)
  const rowTargets = await page.evaluate(() => [...document.querySelectorAll('.wb-row .wb-col-target input')]
    .map(i => i.value))
  check('已存的 9 个目标字段一个不少地回填了',
    original.rules.every(r => rowTargets.includes(r.target)), JSON.stringify(rowTargets))
  await page.screenshot({ path: `${shots}/remove-old-editor-entry.png` })

  // ---------------------------------------------------------------- 5. 旧格式原样保存不丢字段
  const [savedResponse] = await Promise.all([
    page.waitForResponse(r => r.request().method() === 'PUT' && /\/api\/actions\/\d+$/.test(r.url()),
      { timeout: 30000 }),
    (async () => {
      await workbench(page).locator('.wb-head-actions').getByRole('button', { name: '保存', exact: true }).click()
      // 清单里那一列（unit）还没配来源，照旧先问一次「这些行不会被保存」；这一行本来就不该进规则
      const box = page.locator('.el-message-box').filter({ hasText: '不会被保存' })
      await box.waitFor({ timeout: 5000 })
      check('没配好的那一行说清了「不会被保存」',
        (await box.innerText()).includes('1 行没配好'), (await box.innerText()).slice(0, 120))
      await box.getByRole('button', { name: '只保存配好的' }).click()
    })(),
  ])
  const sent = JSON.parse(savedResponse.request().postData() ?? '{}')
  check('保存发的是这份规则树（没有 mode）',
    !!sent.addition && Array.isArray(sent.addition.rules) && sent.addition.mode === undefined,
    JSON.stringify(sent.addition).slice(0, 160))
  check('旧编辑器配的规则原样回填、原样存回', same(sent.addition.rules, original.rules),
    `sent=${JSON.stringify(sent.addition.rules).slice(0, 200)}`)
  await page.waitForTimeout(600)

  // 再打开一次：存回去的还是那些行（往返稳定）
  await openJob3(page)
  await mapRow(page).getByRole('button', { name: '编辑映射', exact: true }).click()
  await page.waitForSelector('.wb-row', { timeout: 25000 })
  await page.waitForTimeout(800)
  check('重开编辑器还是那些行', (await page.locator('.wb-row').count()) === rowCount,
    `rows=${await page.locator('.wb-row').count()}`)
  await workbench(page).locator('.wb-head-actions').getByRole('button', { name: '取消' }).click()
  await page.waitForTimeout(600)

  // ---------------------------------------------------------------- 6. 一份都没配过的动作
  // 放在序号 4（所有动作之后），所以它自己没有写目标——这正是「探不出目标结构」那条退化路
  sql(`insert into job_action (job_id, name, type, action_index, addition)
       values (${JOB_ID}, 'T107-EMPTY', 'FIELD_MAP', 4, json_object())`)
  tempActionId = Number(sql(`select id from job_action where job_id=${JOB_ID} and name='T107-EMPTY'`)[0])
  await openJob3(page)
  const emptyActionRow = await actionRow(page, tempActionId)
  await emptyActionRow.getByRole('button', { name: '编辑映射', exact: true }).click()
  await page.waitForSelector('.wb-row, .wb-empty', { timeout: 25000 })
  await page.waitForTimeout(1200)
  check('一份都没配过的动作打开不报错', (await workbench(page).count()) === 1)
  const emptyText = await page.locator('.wb-empty').first().innerText()
  check('说清了为什么没有目标清单（这个动作后面没有写库动作）',
    emptyText.includes('还没有目标字段清单'), emptyText)
  check('空动作也留着「自己加行」那条出路',
    (await page.locator('.wb-rows-head').getByRole('button', { name: '+ 字段映射' }).count()) === 1)
  check('空动作打开时没有页面报错', pageErrors.length === 0, pageErrors.slice(0, 2).join(' | '))
  await page.screenshot({ path: `${shots}/remove-old-editor-empty-action.png` })
  await workbench(page).locator('.wb-head-actions').getByRole('button', { name: '取消' }).click()
  await page.waitForTimeout(600)

  // ---------------------------------------------------------------- 7. 新建 FIELD_MAP 走同一个入口
  // 临时动作先删掉（它占着序号 4），再补一个临时写目标动作在序号 5：新动作放 4、写目标跟在它后面，
  // 编辑器才探得出目标字段清单（探不出来就只有「+ 字段映射」那条路，配不出「选个来源」这一步）
  dropTempActions()
  sql(`insert into job_action (job_id, name, type, action_index, addition)
       values (${JOB_ID}, 'T107-UPSERT', 'WRITE_UPSERT', 5,
               json_object('tableName', 'dbg_receipt', 'dataSourceId', 1, 'idempotencyKey', 'order_no'))`)
  await openJob3(page)
  await page.getByRole('button', { name: '添加动作' }).click()
  const createDialog = page.locator('.el-dialog').filter({ hasText: '添加动作' })
  await createDialog.waitFor({ timeout: 10000 })
  check('新建弹窗里没有旧编辑器',
    (await createDialog.locator('.mapping-editor, .rows-table, .tree-panel').count()) === 0
    && (await createDialog.locator('.el-radio-button').count()) === 0)
  check('新建弹窗默认类型下没有「去配字段映射」这个按钮',
    (await createDialog.getByRole('button', { name: '去配字段映射' }).count()) === 0)
  await createDialog.locator('.el-select').first().click()
  await page.waitForTimeout(400)
  await page.locator('.el-select-dropdown__item').filter({ hasText: 'FIELD_MAP' }).first().click()
  await page.waitForTimeout(400)
  check('选了 FIELD_MAP 之后才有那一个入口',
    (await createDialog.getByRole('button', { name: '去配字段映射' }).count()) === 1,
    (await createDialog.innerText()).slice(0, 200))
  // 「去配字段映射」要一个动作名；序号确认落在 4（缺省就是「动作数 + 1」）
  await createDialog.locator('.el-form-item', { hasText: '名称' }).locator('input').first().fill('T107-NEW')
  const indexItem = createDialog.locator('.el-form-item', { hasText: '序号' })
  const indexStart = Number(await indexItem.locator('input').first().inputValue())
  for (let i = indexStart; i > 4; i--) await indexItem.locator('.el-input-number__decrease').click()
  for (let i = indexStart; i < 4; i++) await indexItem.locator('.el-input-number__increase').click()
  check('新建弹窗里的序号是「动作数 + 1」',
    Number(await indexItem.locator('input').first().inputValue()) === 4,
    await indexItem.locator('input').first().inputValue())
  await createDialog.getByRole('button', { name: '去配字段映射' }).click()
  await workbench(page).waitFor({ timeout: 25000 })
  await page.waitForSelector('.wb-row, .wb-empty', { timeout: 25000 })
  await page.waitForTimeout(1000)
  check('「去配字段映射」直接开新版编辑器', (await workbench(page).count()) === 1)
  check('新动作的编辑器按写目标那张表铺出了行', (await page.locator('.wb-row').count()) > 0,
    await page.locator('.wb-empty').first().innerText().catch(() => ''))
  await page.screenshot({ path: `${shots}/remove-old-editor-new-action.png` })

  // 配一行（选个来源），再保存：建动作发生在这一刻
  await page.locator('.wb-row').first().locator('.wb-col-value .wb-chip.chip-src').first().click()
  await page.waitForTimeout(400)
  await page.locator('.wb-source-list').first().waitFor({ timeout: 10000 })
  await page.locator('.wb-source-item').first().click()
  await page.waitForTimeout(400)
  const [createdResponse] = await Promise.all([
    page.waitForResponse(r => r.request().method() === 'POST' && /\/api\/jobs\/\d+\/actions$/.test(r.url()),
      { timeout: 30000 }),
    (async () => {
      await workbench(page).locator('.wb-head-actions').getByRole('button', { name: '保存', exact: true }).click()
      const keep = page.locator('.el-message-box').filter({ hasText: '不会被保存' })
      if (await keep.count()) await keep.getByRole('button', { name: '只保存配好的' }).click()
    })(),
  ])
  const createdBody = JSON.parse(createdResponse.request().postData() ?? '{}')
  check('新建这条路点保存才建动作（POST 带上了名字与那份映射）',
    createdResponse.status() < 300 && createdBody.name === 'T107-NEW'
    && createdBody.type === 'FIELD_MAP' && (createdBody.addition?.rules ?? []).length === 1,
    `${createdResponse.status()} ${JSON.stringify(createdBody).slice(0, 200)}`)
  await page.waitForTimeout(800)
  const created = (await api('GET', `/jobs/${JOB_ID}/actions`)).data.find(a => a.name === 'T107-NEW')
  check('动作真的建出来了，而且带着刚配的那条规则',
    !!created && (created.addition?.rules ?? []).length === 1, JSON.stringify(created ?? {}).slice(0, 160))
  const createdRow = created ? await actionRow(page, created.id) : null
  check('建出来的动作在动作表上也有「编辑映射」入口',
    !!createdRow && (await createdRow.getByRole('button', { name: '编辑映射', exact: true }).count()) === 1)
  if (created) {
    await api('DELETE', `/actions/${created.id}`)
    await page.waitForTimeout(300)
  }

  // ---------------------------------------------------------------- 还原
  dropTempActions()
  await api('PUT', `/actions/${ACTION_ID}`, { name: 'map', type: 'FIELD_MAP', index: 2, addition: original })
  check('跑完把动作 8 原来的配置放回去了', same((await action(ACTION_ID)).addition, original))
  check('临时动作都删掉了', (await api('GET', `/jobs/${JOB_ID}/actions`)).data.length === 3,
    `actions=${(await api('GET', `/jobs/${JOB_ID}/actions`)).data.length}`)
  check('全程没有页面报错', pageErrors.length === 0, pageErrors.slice(0, 3).join(' | '))

  console.log(`\n# ${failures.length ? `${failures.length} 项没过` : '全部通过'}`)
  await browser.close()
  browser = null
  process.exit(failures.length ? 1 : 0)
})().catch(async e => {
  console.error('脚本自己出错了：', e)
  try {
    dropTempActions()
  } catch (e2) {
    console.error('临时动作没删掉：', e2.message)
  }
  try {
    if (original) {
      await api('PUT', `/actions/${ACTION_ID}`, { name: 'map', type: 'FIELD_MAP', index: 2, addition: original })
      console.log('（已把动作 8 原来的配置放回去）')
    }
  } catch (e2) {
    console.error('还原也失败了：', e2.message)
  }
  if (browser) await browser.close()
  process.exit(1)
})
