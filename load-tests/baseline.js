import http from 'k6/http';
import crypto from 'k6/crypto';
import { Counter, Rate, Trend } from 'k6/metrics';

const scenario = __ENV.BUSINESS_SCENARIO || 'read';
const mode = __ENV.LOAD_MODE || 'vus';
const level = Number(__ENV.LEVEL || 10);
const seconds = Number(__ENV.SECONDS || 30);
const phase = __ENV.PHASE || 'measure';
const base = __ENV.BASE_URL || 'http://lb:8080';
const customerId = '00000000-0000-0000-0000-000000000001';
const attempts = new Counter('request_attempts');
const completed = new Counter('request_completed');
const errors = new Counter('request_errors');
const latency = new Trend('request_latency', true);
const workflowLatency = new Trend('workflow_latency', true);
const workflowErrors = new Rate('workflow_errors');
const workflowCompleted = new Counter('workflow_completed');
const workflowStarted = new Counter('workflow_started');
const hits = new Counter('cache_hits');
const misses = new Counter('cache_misses');
const bypasses = new Counter('cache_bypasses');
const identities = (__ENV.INSTANCE_IDS || '').split(',').filter(Boolean);
const nodeRequests = new Counter('node_requests');

if (!['read', 'write', 'workflow'].includes(scenario)) throw new Error('Unknown BUSINESS_SCENARIO');
if (!['vus', 'arrival'].includes(mode)) throw new Error('LOAD_MODE must be vus or arrival');
if (!(level > 0 && seconds >= 5)) throw new Error('LEVEL > 0 and SECONDS >= 5 required');

const executor = mode === 'arrival' ? {
  executor: 'constant-arrival-rate', rate: level, timeUnit: '1s', duration: `${seconds}s`,
  preAllocatedVUs: Number(__ENV.PREALLOCATED_VUS || 50), maxVUs: Number(__ENV.MAX_VUS || 500),
} : { executor: 'constant-vus', vus: level, duration: `${seconds}s` };
const thresholds = { request_completed: ['count>0'] };
for (const id of identities) thresholds[`node_requests{instance:${id}}`] = ['count>=0'];
export const options = {
  scenarios: { baseline: { ...executor, gracefulStop: '0s' } },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
  thresholds, setupTimeout: '30s',
  systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
};

function productId(n) {
  const hash = crypto.md5(`baseline-product-${n}`, 'hex');
  return `${hash.slice(0, 8)}-${hash.slice(8, 12)}-${hash.slice(12, 16)}-${hash.slice(16, 20)}-${hash.slice(20)}`;
}

export function setup() {
  // Preparation is excluded from the custom measurement metrics below.
  const response = http.get(`${base}/api/products/${productId(1)}`, { timeout: '10s' });
  if (response.status !== 200 || Number(response.json('price')) !== 10) {
    throw new Error('Baseline seed is missing or modified; use the isolated runner');
  }
}

function request(method, path, body, expected, validate = () => true) {
  attempts.add(1);
  const response = http.request(method, `${base}${path}`, body == null ? null : JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json' }, timeout: '10s',
    tags: { name: method + ' ' + path.split('?')[0].replace(/[0-9a-f]{8}-[0-9a-f-]{27}/gi, ':id') },
  });
  completed.add(1);
  latency.add(response.timings.duration);
  let value = null;
  let valid = response.status === expected;
  try { value = response.json(); valid = valid && validate(value); } catch (_) { valid = false; }
  errors.add(valid ? 0 : 1);
  const identity = response.headers['X-Instance-Id'] || response.headers['X-Instance-ID'] || 'unavailable';
  nodeRequests.add(1, { instance: identity });
  const cache = response.headers['X-Cache'];
  if (cache === 'HIT') hits.add(1);
  if (cache === 'MISS') misses.add(1);
  if (cache === 'BYPASS') bypasses.add(1);
  return { valid, value };
}

export default function () {
  workflowStarted.add(1);
  const started = Date.now();
  let valid = false;
  const id = productId(((__VU + __ITER) % 100) + 1);
  if (scenario === 'read') {
    const page = __ITER % 5;
    valid = request('GET', `/api/products?page=${page}&size=20`, null, 200,
      data => data.page === page && data.items.length === 20).valid;
  } else if (scenario === 'write') {
    valid = request('POST', '/api/orders', { customerId, items: [{ productId: id, quantity: 1 }] }, 201,
      data => Boolean(data.id) && Number(data.total) === 10).valid;
  } else {
    const product = request('GET', `/api/products/${id}`, null, 200, data => Number(data.price) === 10);
    if (product.valid) {
      const total = Number(product.value.price) * 2;
      const order = request('POST', '/api/orders', { customerId, items: [{ productId: id, quantity: 2 }] }, 201,
        data => Boolean(data.id) && Number(data.total) === total);
      if (order.valid) {
        const saved = request('GET', `/api/orders/${order.value.id}`, null, 200,
          data => data.status === 'PLACED' && Number(data.total) === total);
        const cancelled = request('POST', `/api/orders/${order.value.id}/cancel`, null, 200,
          data => data.status === 'CANCELLED');
        valid = saved.valid && cancelled.valid;
      }
    }
  }
  workflowCompleted.add(1);
  workflowErrors.add(!valid);
  workflowLatency.add(Date.now() - started);
}

export function handleSummary(data) {
  const count = name => data.metrics[name]?.values.count || 0;
  const unfinished = Math.max(0, count('request_attempts') - count('request_completed'));
  const unfinishedWorkflows = Math.max(0, count('workflow_started') - count('workflow_completed'));
  const durations = data.metrics.request_latency?.values || {};
  const distribution = {};
  for (const id of identities) distribution[id] = data.metrics[`node_requests{instance:${id}}`]?.values.count || 0;
  const requestErrorRate = (count('request_errors') + unfinished) / Math.max(1, count('request_attempts'));
  const workflowFailed = Math.round((data.metrics.workflow_errors?.values.rate || 0)
    * count('workflow_completed')) + unfinishedWorkflows;
  const report = {
    scenario, mode, level, phase, durationSeconds: seconds,
    instances: Number(__ENV.INSTANCES || 1), cacheMode: __ENV.CACHE_MODE || 'disabled',
    repeat: Number(__ENV.REPEAT || 1), runName: __ENV.RUN_NAME,
    requests: count('request_completed'), rps: count('request_completed') / seconds,
    workflowsPerSecond: count('workflow_completed') / seconds,
    avgMs: durations.avg ?? null, p50Ms: durations['p(50)'] ?? null,
    p95Ms: durations['p(95)'] ?? null, p99Ms: durations['p(99)'] ?? null,
    workflowP95Ms: data.metrics.workflow_latency?.values['p(95)'] ?? null,
    requestErrorRate, workflowErrorRate: workflowFailed / Math.max(1, count('workflow_started')),
    unfinishedRequests: unfinished, unfinishedWorkflows,
    droppedIterations: count('dropped_iterations'),
    cacheHits: count('cache_hits'), cacheMisses: count('cache_misses'), cacheBypasses: count('cache_bypasses'),
    distribution,
    slo: { p95Ms: Number(__ENV.SLO_P95_MS || 500), p99Ms: Number(__ENV.SLO_P99_MS || 1000),
      errorRate: Number(__ENV.SLO_ERROR_RATE || 0.01) },
  };
  report.acceptable = report.requests > 0 && report.p95Ms != null && report.p99Ms != null
    && report.p95Ms <= report.slo.p95Ms && report.p99Ms <= report.slo.p99Ms
    && report.requestErrorRate <= report.slo.errorRate && report.workflowErrorRate <= report.slo.errorRate
    && report.droppedIterations === 0;
  const name = (__ENV.RUN_NAME || 'baseline').replace(/[^a-zA-Z0-9_-]/g, '_');
  return { stdout: JSON.stringify(report, null, 2) + '\n',
    [`${__ENV.OUTPUT_DIR || '/results'}/${name}.json`]: JSON.stringify({ report, metrics: data.metrics }, null, 2) };
}
