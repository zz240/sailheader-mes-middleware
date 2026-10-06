// MES 侧替身（云上 MES）：收单 + 标识注册/解析 + 故障注入 + 收到什么可查。
//
// 启动：node tools/shadow-site/mes-mock.cjs          （默认监听 9100）
// 环境：MES_MOCK_PORT=9200 node tools/shadow-site/mes-mock.cjs
//
// 收单端点（对应 docs/design/docking-scenarios-spec.md §2.2）：
//   POST /mes/api/purchase-order   A5
//   POST /mes/api/report           A4（单条或数组）
//   POST /mes/api/qc-report        A7（头 + 明细数组）
//   POST /mes/api/id-register      B6（{codes:[...]}，同一 FCode 重复注册返回同一 handle）
//   GET  /mes/api/id/resolve/:handle   B6 回读
//
// 核对与运维端点（不模拟业务，只给测试用）：
//   GET    /__health                    存活 + 计数
//   GET    /__received?path=&limit=     收到的原始请求（含时间戳、头、体）
//   DELETE /__received                  清空收到的东西
//   GET    /__handles                   已注册的 handle 账本（旁证）
//   DELETE /__handles                   清空 handle 账本（#143：1000 条那一段验收跑完还原）
//   POST   /__fault  {"mode":"500|503|429|timeout|slow","count":3,"delayMs":2000}
//                                        注入故障：接下来 count 次请求按 mode 处理（count 省略=一直）
//   GET    /__fault                     当前故障设置
//   DELETE /__fault                     取消故障
const http = require('http');

const PORT = Number(process.env.MES_MOCK_PORT || 9100);
const received = [];
const handles = new Map();          // FCode -> handle（幂等注册）
let seq = 0;
let fault = null;                   // {mode, count, delayMs}

function record(req, path, body) {
    received.push({
        at: new Date().toISOString(),
        method: req.method,
        path,
        contentType: req.headers['content-type'] || '',
        authorization: req.headers['authorization'] || '',
        apiKey: req.headers['x-api-key'] || '',
        body,
    });
    if (received.length > 2000) received.shift();
}

function nextFault() {
    if (!fault) return null;
    if (typeof fault.count === 'number') {
        if (fault.count <= 0) { fault = null; return null; }
        fault.count -= 1;
    }
    return fault;
}

function send(res, status, obj) {
    const text = JSON.stringify(obj);
    res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(text);
}

function handleFault(res, f) {
    if (f.mode === 'timeout') return true;                       // 不响应，让调用方超时
    if (f.mode === 'slow') return false;                         // 延迟后正常返回，交给调用方 setTimeout
    const code = Number(f.mode);
    send(res, code, { code: String(code), message: `injected fault ${f.mode}` });
    return true;
}

const server = http.createServer((req, res) => {
    const url = req.url || '/';
    const path = url.split('?')[0];
    let data = '';
    req.on('data', (c) => (data += c));
    req.on('end', () => {
        let body = data;
        try { body = JSON.parse(data); } catch { /* 保留原文 */ }

        // ---- 核对与运维 ----
        if (path === '/__health') {
            return send(res, 200, { ok: true, port: PORT, received: received.length, handles: handles.size, fault });
        }
        if (path === '/__received') {
            if (req.method === 'DELETE') { received.length = 0; return send(res, 200, { cleared: true }); }
            const q = new URLSearchParams(url.split('?')[1] || '');
            const filter = q.get('path');
            const limit = Number(q.get('limit') || 50);
            const list = received.filter((r) => !filter || r.path.includes(filter)).slice(-limit);
            return send(res, 200, { total: received.length, returned: list.length, records: list });
        }
        if (path === '/__handles') {
            // 已注册的 handle 清单：**旁证**用（替身自己的账本）。回写是否真的发生，看链路——#123 之后
            // HTTP_CALL 的响应进 context[本动作序号]，B6 链的 write-handle 步快照里有 handle，库里
            // MDM_Code.FHandle 也能直接查（verify-scenario.ps1 就是这么断言的）。这里用来交叉核对。
            // DELETE 清账本（#143）：1000 条那一段验收会注册 1000 个新码，不清掉的话「同一批 20 条码
            // 的 B6 段」再跑一次就会看到 1020 个 handle、断言不再成立（脚本必须连跑两次结果一致）。
            if (req.method === 'DELETE') { handles.clear(); return send(res, 200, { total: 0, handles: [] }); }
            return send(res, 200, {
                total: handles.size,
                handles: [...handles.entries()].map(([fCode, handle]) => ({ fCode, handle })),
            });
        }
        if (path === '/__fault') {
            if (req.method === 'DELETE') { fault = null; return send(res, 200, { fault }); }
            if (req.method === 'GET') return send(res, 200, { fault });
            fault = {
                mode: String((body && body.mode) || '500'),
                count: body && typeof body.count === 'number' ? body.count : undefined,
                delayMs: body && typeof body.delayMs === 'number' ? body.delayMs : 0,
            };
            return send(res, 200, { fault });
        }

        // ---- 故障注入 ----
        const f = nextFault();
        if (f) {
            if (f.mode === 'slow' && f.delayMs > 0) {
                return setTimeout(() => {
                    record(req, path, body);
                    send(res, 200, { code: '0', message: 'ok(slow)' });
                }, f.delayMs);
            }
            record(req, path, body);
            if (handleFault(res, f)) return;
        }

        // ---- 业务端点 ----
        if (path === '/mes/api/id-resolve' || path.startsWith('/mes/api/id/resolve/')) {
            const handle = decodeURIComponent(path.replace('/mes/api/id/resolve/', ''));
            const hit = [...handles.entries()].find(([, h]) => h === handle);
            record(req, path, body);
            if (!hit) return send(res, 404, { code: 'NOT_FOUND', message: `unknown handle ${handle}` });
            return send(res, 200, { code: '0', handle, fCode: hit[0] });
        }

        if (req.method !== 'POST') {
            record(req, path, body);
            return send(res, 404, { code: 'NOT_FOUND', message: `no route ${req.method} ${path}` });
        }

        record(req, path, body);

        if (path === '/mes/api/id-register') {
            const codes = Array.isArray(body) ? body : (body && body.codes) || [];
            const results = codes.map((c) => {
                const fCode = c.FCode || c.fCode || c.code;
                if (!handles.has(fCode)) handles.set(fCode, `88.1.${++seq}${String(fCode).replace(/\D/g, '')}`);
                return { FCode: fCode, handle: handles.get(fCode) };
            });
            return send(res, 200, { code: '0', count: results.length, results });
        }

        const accepted = {
            '/mes/api/purchase-order': 'purchase-order',
            '/mes/api/device-metric': 'device-metric',
            '/mes/api/report': 'report',
            '/mes/api/qc-report': 'qc-report',
        }[path];

        if (accepted) {
            const n = Array.isArray(body) ? body.length : 1;
            console.log(`[mes-mock] ${accepted} 收到 ${n} 条，累计 ${received.length}`);
            return send(res, 200, { code: '0', message: 'ok', accepted: n });
        }

        return send(res, 404, { code: 'NOT_FOUND', message: `no route POST ${path}` });
    });
});

server.listen(PORT, '0.0.0.0', () => {
    console.log(`MES mock listening on ${PORT}`);
    console.log(`  核对：GET  http://localhost:${PORT}/__received?limit=10`);
    console.log(`  注入：POST http://localhost:${PORT}/__fault  {"mode":"500","count":3}`);
});
