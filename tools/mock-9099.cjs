// 本地 mock：监听 9099，接收 POST 请求并把请求行/请求体追加到日志文件。
// 用于联调 BOM对接 等任务的 HTTP_CALL 动作（数据源 e2e-http → http://localhost:9099）。
// 启动：node tools/mock-9099.cjs
// 日志：%TEMP%\edge-mock\bom-mock.log
const http = require('http');
const fs = require('fs');
const path = require('path');

const logDir = path.join(process.env.TEMP || '.', 'edge-mock');
fs.mkdirSync(logDir, { recursive: true });
const log = path.join(logDir, 'bom-mock.log');

const server = http.createServer((req, res) => {
  let data = '';
  req.on('data', (c) => (data += c));
  req.on('end', () => {
    const line = `${new Date().toISOString()} ${req.method} ${req.url} ${data}\n`;
    fs.appendFileSync(log, line);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ code: '0', message: 'ok' }));
  });
});

server.listen(9099, '0.0.0.0', () => console.log(`mock listening on 9099, log=${log}`));
