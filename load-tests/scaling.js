import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const byInstance = new Counter('requests_by_instance');
const errors = new Rate('business_errors');
const identities = (__ENV.INSTANCE_IDS || '').split(',').filter(Boolean);
const thresholds = { http_reqs: ['count>0'] };
for (const instance of identities) {
  thresholds[`requests_by_instance{instance:${instance}}`] = ['count>=0'];
}
if (__ENV.REQUIRE_NO_ERRORS === 'true') thresholds.business_errors = ['rate==0'];

export const options = {
  vus: Number(__ENV.VUS || 20),
  duration: __ENV.DURATION || '30s',
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
  thresholds,
};

export default function () {
  const headers = __ENV.SLOW_INSTANCE ? { 'X-Lab-Slow-Instance': __ENV.SLOW_INSTANCE } : {};
  const response = http.get(`${__ENV.BASE_URL || 'http://localhost:8080'}/api/products?page=0&size=20`, {
    headers, timeout: '20s',
  });
  const identity = response.headers['X-Instance-Id'] || response.headers['X-Instance-ID'] || 'unavailable';
  const valid = check(response, {
    'HTTP 200 and backend identity': r => r.status === 200 && identity !== 'unavailable',
  });
  errors.add(!valid);
  byInstance.add(1, { instance: identity });
}

export function handleSummary(data) {
  const latency = data.metrics.http_req_duration?.values || {};
  const distribution = {};
  for (const identity of identities) {
    distribution[identity] = data.metrics[`requests_by_instance{instance:${identity}}`]?.values.count || 0;
  }
  const report = {
    run: __ENV.RUN_NAME || 'manual', vus: options.vus, duration: options.duration,
    slowInstance: __ENV.SLOW_INSTANCE || null,
    requests: data.metrics.http_reqs?.values.count || 0,
    rps: data.metrics.http_reqs?.values.rate || 0,
    avgMs: latency.avg, p95Ms: latency['p(95)'], p99Ms: latency['p(99)'],
    errorRate: data.metrics.business_errors?.values.rate || 0,
    distribution,
  };
  const name = (__ENV.RUN_NAME || 'manual').replace(/[^a-zA-Z0-9_-]/g, '_');
  return {
    stdout: `${JSON.stringify(report, null, 2)}\n`,
    [`/results/${name}.json`]: JSON.stringify({ report, metrics: data.metrics }, null, 2),
  };
}
