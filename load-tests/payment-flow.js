// k6 load test of the payment path (ADR-029): create -> confirm (UPI intent) -> signed PSP webhook -> read.
// Needs a non-production deployment with mock PSPs (the prod profile refuses them): bootTestRun, compose, or a
// dedicated load-test environment. Never point it at production or at real PSP sandboxes.
//
//   k6 run -e PROFILE=smoke load-tests/payment-flow.js
//   k6 run -e PROFILE=steady -e RATE=50 -e DURATION=2m -e METRICS_URL=http://localhost:8080/actuator/prometheus ...
import http from 'k6/http';
import { check, fail } from 'k6';
import exec from 'k6/execution';
import { Gauge, Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ADMIN_TOKEN = __ENV.ADMIN_TOKEN || 'local-admin-token';
// The application's own histograms (management port): server-side NFR-2 checks the client cannot see.
const METRICS_URL = __ENV.METRICS_URL || '';

// Rates are payments per second. NFR-1: about 12/s at launch, 1,000/s peak. Each payment is two merchant writes, one
// read and one simulated PSP completion, which makes the PSP webhook call into the gateway.
const PROFILES = {
  smoke: {
    merchants: 2,
    maxDropped: 0,
    scenario: { executor: 'constant-arrival-rate', rate: 2, timeUnit: '1s', duration: '30s', preAllocatedVUs: 5, maxVUs: 20 },
  },
  steady: {
    merchants: 5,
    maxDropped: 0,
    scenario: { executor: 'constant-arrival-rate', rate: 100, timeUnit: '1s', duration: '10m', preAllocatedVUs: 100, maxVUs: 400 },
  },
  peak: {
    merchants: 20,
    maxDropped: 100,
    scenario: {
      executor: 'ramping-arrival-rate', startRate: 100, timeUnit: '1s', preAllocatedVUs: 500, maxVUs: 3000,
      stages: [{ target: 1000, duration: '5m' }, { target: 1000, duration: '10m' }, { target: 0, duration: '1m' }],
    },
  },
  spike: {
    merchants: 10,
    maxDropped: 100,
    scenario: {
      executor: 'ramping-arrival-rate', startRate: 100, timeUnit: '1s', preAllocatedVUs: 300, maxVUs: 2000,
      stages: [
        { target: 100, duration: '2m' }, { target: 500, duration: '10s' }, { target: 500, duration: '2m' },
        { target: 100, duration: '10s' }, { target: 100, duration: '3m' },
      ],
    },
  },
};

const profile = PROFILES[__ENV.PROFILE || 'smoke'];
if (!profile) {
  throw new Error(`Unknown PROFILE ${__ENV.PROFILE}; use one of ${Object.keys(PROFILES).join(', ')}`);
}
if (profile.scenario.executor === 'constant-arrival-rate') {
  if (__ENV.RATE) profile.scenario.rate = Number(__ENV.RATE);
  if (__ENV.DURATION) profile.scenario.duration = __ENV.DURATION;
}

const paymentSucceeded = new Rate('payment_succeeded');
const paymentEndToEnd = new Trend('payment_end_to_end', true);
const serverCreateWithin150ms = new Gauge('server_create_within_150ms');
const serverWebhookAckWithin200ms = new Gauge('server_webhook_ack_within_200ms');

const thresholds = {
  // NFR-2: API overhead p99 <= 150 ms. Create and read call no PSP; confirm includes the (mock) PSP call.
  'http_req_duration{name:create}': ['p(99)<150'],
  'http_req_duration{name:read}': ['p(99)<150'],
  'http_req_duration{name:confirm}': ['p(99)<500'],
  // NFR-3: 99.95 % availability; a load test tolerates no more than 0.1 % failed requests.
  'http_req_failed{flow:payment}': ['rate<0.001'],
  payment_succeeded: ['rate>0.999'],
  dropped_iterations: [`count<=${profile.maxDropped}`],
};
if (METRICS_URL) {
  thresholds.server_create_within_150ms = ['value>=0.99'];
  thresholds.server_webhook_ack_within_200ms = ['value>=0.99'];
}

export const options = {
  scenarios: { payments: profile.scenario },
  thresholds,
  setupTimeout: '2m',
  summaryTrendStats: ['avg', 'p(50)', 'p(90)', 'p(99)', 'max'],
};

function json(body) {
  return JSON.stringify(body);
}

export function setup() {
  const admin = {
    headers: { Authorization: `Bearer ${ADMIN_TOKEN}`, 'Content-Type': 'application/json' },
    tags: { flow: 'setup' },
  };
  const merchants = [];
  for (let i = 0; i < profile.merchants; i++) {
    const created = http.post(`${BASE_URL}/admin/v1/merchants`,
      json({ name: `Load test ${i + 1}`, providers: ['MOCK_ALPHA', 'MOCK_BETA'] }), admin);
    if (created.status !== 201) fail(`creating a merchant returned ${created.status}: ${created.body}`);
    const id = created.json('id');

    // Headroom so the test measures the gateway rather than the per-merchant rate limit (ADR-020).
    const limits = http.put(`${BASE_URL}/admin/v1/merchants/${id}/rate-limits`,
      json({ read: { per_second: 2000, burst: 4000 }, write: { per_second: 2000, burst: 4000 } }), admin);
    if (limits.status !== 200) fail(`raising rate limits returned ${limits.status}: ${limits.body}`);

    const key = http.post(`${BASE_URL}/admin/v1/merchants/${id}/api-keys`, null, admin);
    if (key.status !== 201) fail(`issuing an API key returned ${key.status}: ${key.body}`);
    merchants.push({ id, key: key.json('api_key') });
  }
  return { merchants };
}

export default function (data) {
  const merchant = data.merchants[exec.scenario.iterationInTest % data.merchants.length];
  const auth = { Authorization: `Bearer ${merchant.key}`, 'Content-Type': 'application/json' };
  const started = Date.now();
  // Whole rupees end in 00, so no amount hits a mock failure scenario (suffixes 01-09).
  const amount = 100 * (100 + Math.floor(Math.random() * 4900));

  const created = http.post(`${BASE_URL}/v1/payments`, json({
    amount,
    currency: 'INR',
    merchant_order_id: `lt_${crypto.randomUUID()}`,
    capture_method: 'automatic',
    // A fresh customer per payment: reusing one per VU trips the risk velocity rule, as it should.
    customer: { reference: `cust_${crypto.randomUUID()}`, email: 'buyer@example.com' },
  }), { headers: { ...auth, 'Idempotency-Key': crypto.randomUUID() }, tags: { flow: 'payment', name: 'create' } });
  if (!check(created, { 'payment created': (r) => r.status === 201 })) {
    paymentSucceeded.add(false);
    return;
  }
  const paymentId = created.json('id');

  const confirmed = http.post(`${BASE_URL}/v1/payments/${paymentId}/confirm`, json({
    payment_method: { type: 'upi', upi: { flow: 'intent' } },
    return_url: 'https://merchant.example/return',
  }), { headers: { ...auth, 'Idempotency-Key': crypto.randomUUID() }, tags: { flow: 'payment', name: 'confirm' } });
  // 200 with no attempt means the payment was refused before any PSP call (risk, routing).
  if (!check(confirmed, { 'payment confirmed at a PSP': (r) => r.status === 200 && !!r.json('latest_attempt.provider_reference') })) {
    paymentSucceeded.add(false);
    return;
  }

  // The customer approves in their UPI app: the mock PSP posts a signed webhook to the gateway.
  const provider = confirmed.json('latest_attempt.provider');
  const reference = confirmed.json('latest_attempt.provider_reference');
  const approved = http.post(`${BASE_URL}/simulator/${provider}/payments/${reference}/complete`,
    json({ outcome: 'success' }),
    { headers: { 'Content-Type': 'application/json' }, tags: { flow: 'payment', name: 'psp_webhook' } });
  check(approved, { 'PSP webhook delivered': (r) => r.status === 200 });

  const read = http.get(`${BASE_URL}/v1/payments/${paymentId}`, { headers: auth, tags: { flow: 'payment', name: 'read' } });
  const succeeded = check(read, { 'payment succeeded': (r) => r.status === 200 && r.json('status') === 'succeeded' });
  paymentSucceeded.add(succeeded);
  if (succeeded) paymentEndToEnd.add(Date.now() - started);
}

// Fraction of requests the server answered within `le` seconds, from its cumulative Prometheus histogram.
function withinServerSide(text, matches, le) {
  let within = 0;
  let total = 0;
  for (const line of text.split('\n')) {
    // Greedy: URI templates put braces inside label values ({provider}).
    const sample = /^http_server_requests_seconds_(bucket|count)\{(.*)\} ([0-9.eE+-]+)$/.exec(line);
    if (!sample) continue;
    const labels = Object.fromEntries([...sample[2].matchAll(/(\w+)="([^"]*)"/g)].map((m) => [m[1], m[2]]));
    if (!matches(labels)) continue;
    if (sample[1] === 'count') total += Number(sample[3]);
    else if (labels.le === le) within += Number(sample[3]);
  }
  return total === 0 ? 0 : within / total;
}

export function teardown() {
  if (!METRICS_URL) return;
  const scraped = http.get(METRICS_URL, { tags: { flow: 'setup' } });
  if (scraped.status !== 200) fail(`scraping ${METRICS_URL} returned ${scraped.status}`);
  serverCreateWithin150ms.add(withinServerSide(scraped.body,
    (l) => l.method === 'POST' && l.uri === '/v1/payments', '0.15'));
  serverWebhookAckWithin200ms.add(withinServerSide(scraped.body,
    (l) => l.uri.startsWith('/v1/webhooks/providers/'), '0.2'));
}
