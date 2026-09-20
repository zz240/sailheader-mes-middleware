// 采集桥（现场那层就是设备网关/DTU）：轮询 Modbus 从站 → 每个点位一条消息 → 推到网关。
//
// 为什么要有这一层：产品没有 Modbus/MQTT 连接器（只有 HTTP/MySQL/RabbitMQ），
// 现场也是这么落地的——设备侧协议由网关/DTU 收敛成 HTTP 或 MQ，边缘网关只对接收敛后的形态。
//
// 启动：node tools/shadow-site/modbus-bridge.cjs
//   DEVICE_HOST=127.0.0.1      Modbus 从站地址（换成 ESP32 的 IP 就是真机）
//   DEVICE_PORT=1502
//   DEVICE_UNITS=1,2           要采集的从站地址
//   POLL_MS=5000               采集周期
//   GATEWAY=http://localhost:8080
//   JOB_ID=91                  目标任务的 id（链首是 FIELD_MAP，请求体即链首输入）
//   API_KEY=<站点 API Key>
//   ADMIN_PORT=9202            自身的状态口（核对用）
//
// 断网续传：POST 失败就把这条消息塞进内存队列，下一轮先补发队列里的（先入先出，上限 5000 条）。
// 质量位：读失败 → STALE（沿用上一次的值）；状态字 bit1 → BAD；刚重启（bit2）→ 前几个周期标 STALE。
const net = require('net');
const http = require('http');

const DEVICE_HOST = process.env.DEVICE_HOST || '127.0.0.1';
const DEVICE_PORT = Number(process.env.DEVICE_PORT || 1502);
const UNITS = (process.env.DEVICE_UNITS || '1,2').split(',').map((s) => Number(s.trim())).filter((n) => n > 0);
const POLL_MS = Number(process.env.POLL_MS || 5000);
const GATEWAY = process.env.GATEWAY || 'http://localhost:8080';
const JOB_ID = process.env.JOB_ID || '';
const API_KEY = process.env.API_KEY || '';
const ADMIN_PORT = Number(process.env.ADMIN_PORT || 9202);
const QUEUE_CAP = 5000;
const READ_QTY = 20;

// 寄存器 → 点位名（与 modbus-device.cjs / esp32 固件同一张表）
const POINTS = [
  { reg: 3, name: 'temp_c', scale: 100, min: -40, max: 200 },
  { reg: 4, name: 'current_a', scale: 100, min: 0, max: 100 },
  { reg: 5, name: 'voltage_v', scale: 100, min: 100, max: 400 },
  { reg: 6, name: 'counter', scale: 1, min: 0, max: 65535 },
];

const queue = [];              // 待补发的消息（断网时攒着）
const last = new Map();        // device:point -> 上一次的值（读失败时沿用）
const stats = { cycles: 0, posted: 0, buffered: 0, failed: 0, lastError: '', lastCycleAt: '' };

function nowStamp() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/** 读一个从站的保持寄存器（功能码 3）。返回 20 个寄存器的数组，失败抛异常。 */
function readRegisters(unit) {
  return new Promise((resolve, reject) => {
    const socket = net.connect(DEVICE_PORT, DEVICE_HOST);
    const timer = setTimeout(() => { socket.destroy(); reject(new Error('modbus timeout')); }, 3000);
    const pdu = Buffer.alloc(5);
    pdu.writeUInt8(3, 0);
    pdu.writeUInt16BE(0, 1);
    pdu.writeUInt16BE(READ_QTY, 3);
    const head = Buffer.alloc(7);
    head.writeUInt16BE(1, 0);
    head.writeUInt16BE(0, 2);
    head.writeUInt16BE(pdu.length + 1, 4);
    head.writeUInt8(unit, 6);
    let acc = Buffer.alloc(0);
    socket.on('connect', () => socket.write(Buffer.concat([head, pdu])));
    socket.on('data', (chunk) => {
      acc = Buffer.concat([acc, chunk]);
      if (acc.length >= 9) {
        clearTimeout(timer);
        socket.end();
        const fc = acc.readUInt8(7);
        if (fc & 0x80) return reject(new Error(`modbus exception ${acc.readUInt8(8)}`));
        const byteCount = acc.readUInt8(8);
        const regs = [];
        for (let i = 0; i < byteCount / 2; i++) regs.push(acc.readUInt16BE(9 + i * 2));
        resolve(regs);
      }
    });
    socket.on('error', (e) => { clearTimeout(timer); reject(e); });
  });
}

function toSigned(v) {
  return v > 0x7fff ? v - 0x10000 : v;
}

function post(path, body) {
  return new Promise((resolve, reject) => {
    const data = Buffer.from(JSON.stringify(body), 'utf8');
    const url = new URL(GATEWAY + path);
    const req = http.request({
      hostname: url.hostname, port: url.port, path: url.pathname, method: 'POST',
      headers: { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': data.length, 'X-API-Key': API_KEY },
    }, (res) => {
      let text = '';
      res.on('data', (c) => (text += c));
      res.on('end', () => (res.statusCode >= 200 && res.statusCode < 300
        ? resolve(text)
        : reject(new Error(`http ${res.statusCode} ${text.slice(0, 120)}`))));
    });
    req.on('error', reject);
    req.write(data);
    req.end();
  });
}

async function flushQueue() {
  while (queue.length > 0) {
    const item = queue[0];
    try {
      await post(item.path, item.body);
      queue.shift();
      stats.posted += 1;
    } catch (e) {
      stats.failed += 1;
      stats.lastError = `queue flush: ${e.message}`;
      return;                                   // 还是不通，留着下一轮再试
    }
  }
}

async function cycle() {
  stats.cycles += 1;
  const stamp = nowStamp();
  const messages = [];

  for (const unit of UNITS) {
    let regs = null;
    try {
      regs = await readRegisters(unit);
    } catch (e) {
      stats.lastError = `read unit ${unit}: ${e.message}`;
    }
    const status = regs ? regs[2] : 0;
    const freshBoot = regs ? (status & 0b100) !== 0 : false;
    for (const p of POINTS) {
      const key = `${unit}:${p.name}`;
      let value = last.get(key);
      let quality = 'GOOD';
      if (regs) {
        value = toSigned(regs[p.reg]) / p.scale;
        last.set(key, value);
        if (status & 0b010) quality = 'BAD';           // 传感器故障
        else if (freshBoot) quality = 'STALE';         // 刚重启，寄存器还不可信
        else if (value < p.min || value > p.max) quality = 'BAD';   // 越界
      } else {
        quality = 'STALE';                             // 读不到：沿用上次的值
      }
      messages.push({
        device_no: `DEV-${unit}`,
        point: p.name,
        ts: stamp,
        value: value === undefined ? 0 : value,
        quality,
      });
    }
  }

  for (const m of messages) {
    if (queue.length > QUEUE_CAP) { stats.lastError = 'queue full, dropping oldest'; queue.shift(); }
    await flushQueue();
    if (queue.length > 0) { queue.push({ path: triggerPath(), body: m }); continue; }   // 还没通：攒着
    try {
      await post(triggerPath(), m);
      stats.posted += 1;
    } catch (e) {
      queue.push({ path: triggerPath(), body: m });
      stats.failed += 1;
      stats.lastError = e.message;
    }
  }
  stats.buffered = queue.length;
  stats.lastCycleAt = stamp;
  console.log(`[bridge] ${stamp} cycle ${stats.cycles}: ${messages.length} points, posted=${stats.posted}, buffered=${queue.length}${stats.lastError ? ' | ' + stats.lastError : ''}`);
}

function triggerPath() {
  return `/api/external/jobs/${JOB_ID}/trigger`;
}

if (!JOB_ID) {
  console.error('JOB_ID is required (the FIELD_MAP-headed chain to trigger)');
  process.exit(2);
}

console.log(`[bridge] polling modbus://${DEVICE_HOST}:${DEVICE_PORT} units=${UNITS.join(',')} every ${POLL_MS}ms -> ${GATEWAY}${triggerPath()}`);
http.createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(stats, null, 2));
}).listen(ADMIN_PORT, '0.0.0.0', () => console.log(`[bridge] admin: http://localhost:${ADMIN_PORT}/`));

cycle().catch((e) => console.error('[bridge] cycle failed:', e.message));
setInterval(() => { cycle().catch((e) => console.error('[bridge] cycle failed:', e.message)); }, POLL_MS);
