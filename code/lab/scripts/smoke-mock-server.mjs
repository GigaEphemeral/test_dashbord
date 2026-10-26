// Zero-dependency mock of the java-learn-lab public HTTP surface, used by
// test-smoke.sh as a regression harness for code/lab/scripts/smoke.sh.
// It never talks to MySQL/Redis/RabbitMQ and binds a caller-chosen port only.
//
// Usage: node smoke-mock-server.mjs <port> <mode>
//   mode: async     POST /api/seckill/orders -> 202 ACCEPTED + requestId,
//                   first poll ACCEPTED, second poll CREATED + orderId.
//         degraded  202 but body requestId=null, status=CREATED, orderId set
//                   (seckillBackend circuit open -> synchronous DB fallback).
//         syncoff   200 CREATED + orderId (mq toggle off).
//         conflict  409 sold out envelope.
//         limited   429 rate limited envelope.
import { createServer } from 'node:http';

const [, , portArg, mode] = process.argv;
if (!portArg || !mode) {
  console.error('usage: node smoke-mock-server.mjs <port> <mode>');
  process.exit(2);
}

const ok = (data) => JSON.stringify({ code: '0', message: 'ok', data });
const envelope = (res, httpStatus, body) => {
  res.writeHead(httpStatus, { 'Content-Type': 'application/json' });
  res.end(body);
};

let nextOrderId = 100;
let seckillPolls = 0;
const ordersByKey = new Map();

const server = createServer((req, res) => {
  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('end', () => {
    const body = Buffer.concat(chunks).toString('utf8');
    const url = req.url.split('?')[0];
    const key = req.headers['idempotency-key'] || null;

    if (req.method === 'POST' && url === '/api/login') {
      envelope(res, 200, ok({ token: 'mock-token', expiresInSeconds: 1800 }));
      return;
    }
    const productMatch = url.match(/^\/api\/products\/(\d+)$/);
    if (req.method === 'GET' && productMatch) {
      envelope(res, 200, ok({ id: Number(productMatch[1]), name: 'mock', stock: 10 }));
      return;
    }
    if (req.method === 'POST' && url === '/api/orders') {
      if (!key) {
        envelope(res, 400, JSON.stringify({ code: '40001', message: 'Idempotency-Key header is required', data: null }));
        return;
      }
      let id = ordersByKey.get(key);
      if (id === undefined) {
        id = nextOrderId++;
        ordersByKey.set(key, id);
      }
      envelope(res, 200, ok({ id, orderNo: `NO-${id}`, status: 'CREATED' }));
      return;
    }
    const orderMatch = url.match(/^\/api\/orders\/(\d+)$/);
    if (req.method === 'GET' && orderMatch) {
      envelope(res, 200, ok({ id: Number(orderMatch[1]), orderNo: `NO-${orderMatch[1]}`, status: 'CREATED' }));
      return;
    }
    if (req.method === 'POST' && url === '/api/seckill/orders') {
      if (mode === 'conflict') {
        envelope(res, 409, JSON.stringify({ code: '40901', message: 'Sold out', data: null }));
        return;
      }
      if (mode === 'limited') {
        envelope(res, 429, JSON.stringify({ code: '42901', message: 'Seckill rate limited', data: null }));
        return;
      }
      if (mode === 'degraded') {
        envelope(res, 202, ok({ requestId: null, status: 'CREATED', orderId: nextOrderId++ }));
        return;
      }
      if (mode === 'syncoff') {
        envelope(res, 200, ok({ requestId: null, status: 'CREATED', orderId: nextOrderId++ }));
        return;
      }
      envelope(res, 202, ok({ requestId: 'mock-request-1', status: 'ACCEPTED', orderId: null }));
      return;
    }
    const seckillMatch = url.match(/^\/api\/seckill\/orders\/([^/]+)$/);
    if (req.method === 'GET' && seckillMatch) {
      seckillPolls += 1;
      if (seckillPolls < 2) {
        envelope(res, 200, ok({ requestId: seckillMatch[1], status: 'ACCEPTED', orderId: null }));
      } else {
        envelope(res, 200, ok({ requestId: seckillMatch[1], status: 'CREATED', orderId: 4242 }));
      }
      return;
    }
    envelope(res, 404, JSON.stringify({ code: '40401', message: 'not found', data: null }));
  });
});

server.listen(Number(portArg), '127.0.0.1', () => {
  // Port 0 asks the OS for an ephemeral port; the runner parses this line.
  console.log(`mock ready mode=${mode} port=${server.address().port}`);
});
