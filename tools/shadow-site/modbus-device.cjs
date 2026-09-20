// 设备替身：Modbus TCP 从站（功能码 3 = 读保持寄存器），零依赖、手写 MBAP。
//
// 用途：在没有 ESP32 手边时，把「车间设备」这一层先立起来；寄存器映射与
// tools/shadow-site/esp32/modbus_device.ino 完全对齐，所以现场只要把采集桥的
// DEVICE_HOST 指向 ESP32 的 IP，其余一个字不用改。
//
// 启动：node tools/shadow-site/modbus-device.cjs
//   MODBUS_PORT=1502      监听端口（1502 避开标准 502，需要管理员权限的机器上更稳）
//   MODBUS_UNITS=2        从站地址 1..N（一台设备一个 unit id）
//   RESTART_EVERY=0       每 N 秒模拟一次「断电重启」（uptime 归零 + 状态位 bit2 置位）；0 = 不模拟
//   SENSOR_FAULT_AFTER=0  运行 N 秒后把状态位 bit1（传感器故障）置起来；0 = 不模拟
//
// 寄存器映射（保持寄存器，地址从 0 起）：
//   0..1  uptime 秒（高 16 位 / 低 16 位，寄存器是 16 位）
//   2     状态字：bit0 运行、bit1 传感器故障、bit2 刚重启
//   3     温度  DS18B20  ×100（有符号）
//   4     电流  INA219   ×100
//   5     电压  INA219   ×100
//   6     计数（每次被轮询自增，用来证明「读到的是新值」）
//   7..9  保留
//
// 说明：这是替身，不是产品代码。它只实现 FC3，其余功能码一律回异常码 01（非法功能）。
const net = require('net');
const http = require('http');

const PORT = Number(process.env.MODBUS_PORT || 1502);
const UNITS = Number(process.env.MODBUS_UNITS || 2);
const RESTART_EVERY = Number(process.env.RESTART_EVERY || 0);
const SENSOR_FAULT_AFTER = Number(process.env.SENSOR_FAULT_AFTER || 0);
const ADMIN_PORT = Number(process.env.DEVICE_ADMIN_PORT || 9201);

const startedAt = Date.now();
const polls = new Array(UNITS + 1).fill(0);

/** 每台设备的寄存器映像（读的时候现算，保证曲线在动）。 */
function registers(unit) {
  const now = Date.now();
  const phase = unit * 1.7;
  let uptime = Math.floor((now - startedAt) / 1000);

  let justRestarted = false;
  if (RESTART_EVERY > 0) {
    // 每 RESTART_EVERY 秒「重启」一次：uptime 归零，重启后前 5 秒状态位 bit2 亮着
    const since = uptime % RESTART_EVERY;
    if (since < 5) justRestarted = true;
    uptime = since;
  }

  let status = 0b001;                                   // bit0 运行
  if (SENSOR_FAULT_AFTER > 0 && (now - startedAt) / 1000 > SENSOR_FAULT_AFTER) status |= 0b010;
  if (justRestarted) status |= 0b100;

  const t = now / 1000;
  const temp = 45 + 5 * Math.sin(t / 20 + phase);        // 温度曲线
  const current = 8 + 2 * Math.sin(t / 7 + phase);       // 电流曲线
  const voltage = 220 + (Math.random() - 0.5);           // 电压带噪声
  const counter = polls[unit] & 0xffff;

  const r = new Array(20).fill(0);
  r[0] = (uptime >>> 16) & 0xffff;
  r[1] = uptime & 0xffff;
  r[2] = status;
  r[3] = toInt16(Math.round(temp * 100));
  r[4] = toInt16(Math.round(current * 100));
  r[5] = toInt16(Math.round(voltage * 100));
  r[6] = counter;
  return r;
}

function toInt16(v) {
  return v < 0 ? (v + 0x10000) & 0xffff : v & 0xffff;
}

const server = net.createServer((socket) => {
  socket.on('error', () => { /* 客户端断了就算了 */ });
  socket.on('data', (buf) => {
    // MBAP(7) + PDU；一帧总长 = 6 + MBAP 长度字段（该字段含 unit id）。
    let offset = 0;
    while (offset + 6 <= buf.length) {
      const tid = buf.readUInt16BE(offset);
      const mbapLen = buf.readUInt16BE(offset + 4);
      const total = 6 + mbapLen;
      if (offset + total > buf.length) break;              // 半包：等下一批数据
      const unit = buf.readUInt8(offset + 6);
      const fc = buf.readUInt8(offset + 7);

      if (fc === 3 && unit >= 1 && unit <= UNITS) {
        const addr = buf.readUInt16BE(offset + 8);
        const qty = buf.readUInt16BE(offset + 10);
        polls[unit] += 1;
        const regs = registers(unit);
        if (addr + qty > regs.length) {
          respond(socket, tid, unit, Buffer.from([fc | 0x80, 0x02]));   // 非法数据地址
        } else {
          const data = Buffer.alloc(2 + qty * 2);
          data.writeUInt8(fc, 0);
          data.writeUInt8(qty * 2, 1);
          for (let i = 0; i < qty; i++) data.writeUInt16BE(regs[addr + i], 2 + i * 2);
          respond(socket, tid, unit, data);
        }
      } else {
        respond(socket, tid, unit, Buffer.from([fc | 0x80, 0x01]));     // 非法功能
      }
      offset += total;
    }
  });
});

function respond(socket, tid, unit, pdu) {
  const head = Buffer.alloc(7);
  head.writeUInt16BE(tid, 0);
  head.writeUInt16BE(0, 2);
  head.writeUInt16BE(pdu.length + 1, 4);
  head.writeUInt8(unit, 6);
  socket.write(Buffer.concat([head, pdu]));
}

server.listen(PORT, '0.0.0.0', () => {
  console.log(`modbus device simulator on ${PORT} (units 1..${UNITS})`);
  if (RESTART_EVERY > 0) console.log(`  simulating a power cycle every ${RESTART_EVERY}s`);
  if (SENSOR_FAULT_AFTER > 0) console.log(`  raising the sensor-fault bit after ${SENSOR_FAULT_AFTER}s`);
});

// 一个小管理口，方便核对替身自己在想什么（不参与 Modbus）。
http.createServer((req, res) => {
  const regs = {};
  for (let u = 1; u <= UNITS; u++) regs[`unit${u}`] = registers(u);
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ uptimeSec: Math.floor((Date.now() - startedAt) / 1000), polls: polls.slice(1), regs }, null, 2));
}).listen(ADMIN_PORT, '0.0.0.0', () => console.log(`  admin: http://localhost:${ADMIN_PORT}/`));
