/**
 * #146 验收用的「慢速目标端点」：每次请求故意等 DELAY_MS 才回 STATUS，把一次运行拉到几秒，
 * 好让第二次触发稳稳落在第一次运行的时间窗内。
 *
 * 用法：node tools/shadow-site/slow-http.cjs [port] [delayMs] [status]
 *   port    默认 18080
 *   delayMs 默认 3000
 *   status  默认 200；传 500 可让下游动作失败（#146 重投验收要据此造出真实的失败消息步）
 *
 * 每一行 stdout 是一次请求记录（seq/时刻/方法/路径/体），验收脚本据此断言「被跳过的触发没有真发出去」。
 */
const http = require('http')

const port = Number(process.argv[2] || 18080)
const delayMs = Number(process.argv[3] || 3000)
const status = Number(process.argv[4] || 200)
let hits = 0

const server = http.createServer((req, res) => {
  let body = ''
  req.on('data', (chunk) => { body += chunk })
  req.on('end', () => {
    hits++
    const seq = hits
    console.log(JSON.stringify({ seq, at: new Date().toISOString(), method: req.method, url: req.url, status, body }))
    setTimeout(() => {
      res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' })
      res.end(JSON.stringify({ ok: status < 400, seq, delayMs, status }))
    }, delayMs)
  })
})

server.listen(port, () => console.log(JSON.stringify({ listening: port, delayMs, status })))
