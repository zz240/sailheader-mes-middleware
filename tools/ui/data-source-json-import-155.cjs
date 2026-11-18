/*
 * #155 真机 UI 验收：数据源 addition 只从表单输入，「从 JSON 粘贴」是一次性导入。
 *
 * 验什么：
 *   1. 新建弹窗里不再有「高级：原始 addition JSON」，也没有原始 JSON 文本框；
 *   2. 粘一份整份 JSON → 填满当前类型的格子（HTTP token / MQ 四个带默认值 / MySQL 账号密码），导入框关掉；
 *   3. 非法 JSON、非对象、未知键、非法 port 各给一句中文错因；导入框不关、表单保持原值；
 *   4. 导入只填表单：保存请求体里的 addition 由表单拼出，没有第二份 JSON；
 *   5. 编辑已有数据源时导入同样可用，点「取消」不发保存请求。
 * 全程不写库：保存那一下把 POST /api/data-sources 拦下来（只读请求照走）。
 *
 * 跑法：cd tools/ui && node data-source-json-import-155.cjs
 */
const fs = require('fs')
const path = require('path')
const { chromium } = require('playwright')

const APP = 'http://127.0.0.1:5173'
const shots = path.join(__dirname, 'shots')
const failures = []
let browser = null

function check(name, cond, detail = '') {
  console.log(`${cond ? 'ok  ' : 'NOT '} - ${name}${cond ? '' : ` :: ${detail}`}`)
  if (!cond) failures.push(name)
}

/** 弹窗里某一格：按 label 找 form-item，再取第一个 input。 */
function field(form, label) {
  return form.locator('.el-form-item').filter({ hasText: label }).locator('input').first()
}

async function openCreate(page) {
  await page.getByRole('button', { name: '新建数据源' }).click()
  const form = page.locator('.el-dialog').first()
  await form.waitFor()
  return form
}

async function pickType(page, form, label) {
  await form.locator('.el-select').first().click()
  await page.locator('.el-select-dropdown__item').filter({ hasText: label }).last().click()
  await page.waitForTimeout(300)
}

async function openImport(page, form) {
  await form.getByRole('button', { name: '从 JSON 粘贴' }).click()
  await page.getByRole('button', { name: '导入' }).waitFor()
}

async function doImport(page, text) {
  await page.locator('body > .el-overlay textarea').fill(text)
  await page.getByRole('button', { name: '导入' }).click()
  await page.waitForTimeout(400)
}

async function toastText(page) {
  const toast = page.locator('.el-message').last()
  await toast.waitFor({ timeout: 5000 })
  return (await toast.innerText()).trim()
}

async function closeImport(page) {
  await page.locator('body > .el-overlay .el-dialog__footer button').filter({ hasText: '取消' }).click()
  await page.waitForTimeout(400)
}

;(async () => {
  fs.mkdirSync(shots, { recursive: true })
  browser = await chromium.launch()
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  page.setDefaultTimeout(20000)
  const pageErrors = []
  page.on('pageerror', (e) => pageErrors.push(e.message))

  await page.goto(`${APP}/data-sources`, { waitUntil: 'networkidle' })

  // ---- 1. 原来的原始 JSON 框没了，换成一次性导入入口
  const form = await openCreate(page)
  check('新建弹窗里没有「高级：原始 addition JSON」',
    (await form.getByText('高级：原始 addition JSON').count()) === 0)
  check('新建弹窗里没有原始 JSON 文本框', (await form.locator('textarea').count()) === 0)
  check('「从 JSON 粘贴」入口在界面上',
    (await form.getByRole('button', { name: '从 JSON 粘贴' }).count()) === 1)

  // ---- 2. HTTP：整份导入填进 Token 格
  await openImport(page, form)
  check('导入框打开时原文是空的',
    (await page.locator('body > .el-overlay textarea').inputValue()) === '')
  await doImport(page, '{"token":"abc"}')
  check('导入后 Token 格子被填上', (await field(form, 'Token').inputValue()) === 'abc',
    await field(form, 'Token').inputValue())
  check('导入成功后导入框关掉',
    !(await page.locator('body > .el-overlay textarea').isVisible()))
  check('提示写明「保存才写库」', (await toastText(page)).includes('点「保存」才写库'),
    await toastText(page))
  await page.screenshot({ path: `${shots}/155-import-token.png` })

  // ---- 3. 四种错因：导入框不关、表单不被改
  await openImport(page, form)
  await doImport(page, '{"token":"x","timeout":1}')
  const unknownToast = await toastText(page)
  check('未知键点名报错', unknownToast.includes('timeout') && unknownToast.includes('只接受 token'),
    unknownToast)
  check('未知键时导入框不关',
    await page.locator('body > .el-overlay textarea').isVisible())
  check('未知键时表单保持原值', (await field(form, 'Token').inputValue()) === 'abc',
    await field(form, 'Token').inputValue())

  await doImport(page, '{oops')
  check('非法 JSON 有错因', (await toastText(page)).includes('不是合法 JSON'), await toastText(page))

  await doImport(page, '[1,2]')
  check('数组不算 JSON 对象', (await toastText(page)).includes('JSON 对象'), await toastText(page))
  await page.screenshot({ path: `${shots}/155-import-unknown-key.png` })
  await closeImport(page)

  // ---- 4. RabbitMQ：没写的键回到后端默认值
  await pickType(page, form, 'RabbitMQ')
  await openImport(page, form)
  await doImport(page, '{"host":"mq1"}')
  check('MQ 只写 host 时其余三格显示默认值',
    (await field(form, 'Host').inputValue()) === 'mq1'
      && (await field(form, 'Port').inputValue()) === '5672'
      && (await field(form, '用户名').inputValue()) === 'guest'
      && (await field(form, '密码').inputValue()) === 'guest',
    [
      await field(form, 'Host').inputValue(),
      await field(form, 'Port').inputValue(),
      await field(form, '用户名').inputValue(),
      await field(form, '密码').inputValue(),
    ].join('/'))

  await openImport(page, form)
  await doImport(page, '{"port":"abc"}')
  check('非法 port 有错因', (await toastText(page)).includes('1 到 65535'), await toastText(page))
  check('非法 port 时表单保持原值', (await field(form, 'Host').inputValue()) === 'mq1')
  await closeImport(page)

  // ---- 5. MySQL：老形状的 url 会被当成未知键；正常形状只填表单
  await pickType(page, form, 'MySQL')
  await openImport(page, form)
  await doImport(page, '{"url":"jdbc:mysql://x","username":"u","password":"p"}')
  check('旧写法里的 url 被点名为未知键', (await toastText(page)).includes('url'), await toastText(page))
  await doImport(page, '{"username":"u","password":"p"}')
  check('MySQL 账号密码被填上',
    (await field(form, '用户名').inputValue()) === 'u'
      && (await field(form, '密码').inputValue()) === 'p')

  // ---- 6. 保存发出去的 addition 就是表单那两格
  await field(form, '名称').fill('t155-import')
  await field(form, '地址').fill('jdbc:mysql://127.0.0.1:3306/x')
  let postBody = null
  let posts = 0
  await page.route('**/api/data-sources', async (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    posts++
    postBody = JSON.parse(route.request().postData() || '{}')
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'OK', message: 'ok', data: { id: 999 } }),
    })
  })
  await form.getByRole('button', { name: '保存' }).click()
  await page.waitForTimeout(700)
  check('保存只发了一次 POST', posts === 1, `posts=${posts}`)
  check('请求体里的 addition 由表单拼出（没有 timeout / url 这类多余键）',
    postBody && JSON.stringify(postBody.addition) === '{"username":"u","password":"p"}',
    JSON.stringify(postBody?.addition))
  await page.unroute('**/api/data-sources')

  // ---- 7. 编辑已有数据源：导入同样可用，取消不发保存请求
  let puts = 0
  page.on('request', (req) => {
    if (req.method() === 'PUT' && /\/api\/data-sources\/\d+$/.test(new URL(req.url()).pathname)) puts++
  })
  await page.locator('tbody tr').first().getByRole('button', { name: '编辑' }).click()
  const editForm = page.locator('.el-dialog').first()
  await editForm.waitFor()
  check('编辑弹窗里也没有原始 JSON 文本框', (await editForm.locator('textarea').count()) === 0)
  await openImport(page, editForm)
  await doImport(page, '{"username":"edited","password":"pw"}')
  check('编辑时导入也能填表单', (await field(editForm, '用户名').inputValue()) === 'edited',
    await field(editForm, '用户名').inputValue())
  await editForm.getByRole('button', { name: '取消' }).click()
  await page.waitForTimeout(400)
  check('点「取消」不发保存请求', puts === 0, `puts=${puts}`)

  check('页面上没有 JS 报错', pageErrors.length === 0, JSON.stringify(pageErrors))

  console.log(`\n${failures.length ? `FAILED: ${failures.join(' | ')}` : 'ALL OK'}`)
  await browser.close()
  process.exit(failures.length ? 1 : 0)
})().catch(async (e) => {
  console.error(e)
  if (browser) await browser.close()
  process.exit(1)
})
