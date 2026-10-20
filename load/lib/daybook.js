// Small client for the Daybook API, shared by the k6 scripts.
import http from 'k6/http';

export const BASE_URL = __ENV.BASE_URL || 'http://api:8080';
export const ADMIN_KEY = __ENV.ADMIN_KEY || 'dev-admin-key';

export function headers(apiKey, idempotencyKey) {
  const h = { 'Content-Type': 'application/json', Authorization: `Bearer ${apiKey}` };
  if (idempotencyKey) {
    h['Idempotency-Key'] = idempotencyKey;
  }
  return h;
}

export function post(path, apiKey, idempotencyKey, body, tags) {
  return http.post(`${BASE_URL}${path}`, body === undefined ? null : JSON.stringify(body), {
    headers: headers(apiKey, idempotencyKey),
    tags,
  });
}

export function get(path, apiKey, tags) {
  return http.get(`${BASE_URL}${path}`, { headers: headers(apiKey), tags });
}

function expect(res, status, what) {
  if (res.status !== status) {
    throw new Error(`${what}: expected ${status}, got ${res.status} ${res.body}`);
  }
  return res.json();
}

/** A fresh tenant with `count` accounts, each funded with `fundingMinor` from the treasury. */
export function createFundedTenant(name, count, fundingMinor) {
  const tenant = expect(post('/v1/admin/tenants', ADMIN_KEY, null, { name }), 201, 'create tenant');
  const accounts = [];
  for (let i = 0; i < count; i++) {
    const account = expect(post('/v1/accounts', tenant.apiKey, `${name}-acct-${i}`), 201, 'create account');
    expect(
      post(`/v1/admin/tenants/${tenant.tenantId}/fundings`, ADMIN_KEY, `${name}-fund-${i}`, {
        accountId: account.id,
        amountMinor: fundingMinor,
      }),
      201,
      'fund account',
    );
    accounts.push(account.id);
  }
  return { tenantId: tenant.tenantId, apiKey: tenant.apiKey, accounts };
}

/** Sum of the given accounts' balances, read from the ledger. */
export function totalBalance(apiKey, accounts) {
  let total = 0;
  for (const id of accounts) {
    total += expect(get(`/v1/accounts/${id}`, apiKey), 200, 'read account').balanceMinor;
  }
  return total;
}

/** Compact end-of-test report: printed to the console and saved as JSON for the README. */
export function summarize(data, title, extra) {
  const m = data.metrics;
  const ms = (v) => (v === undefined ? 'n/a' : `${v.toFixed(1)} ms`);
  const lines = [`\n=== ${title} ===`];
  for (const name of Object.keys(m).sort()) {
    const metric = m[name];
    if (name.startsWith('http_req_duration{')) {
      const v = metric.values;
      lines.push(`${name.padEnd(48)} p50 ${ms(v.med)}  p95 ${ms(v['p(95)'])}  p99 ${ms(v['p(99)'])}  max ${ms(v.max)}`);
    } else if (metric.type === 'counter') {
      lines.push(`${name.padEnd(48)} ${metric.values.count}  (${metric.values.rate.toFixed(1)}/s)`);
    } else if (metric.type === 'rate') {
      lines.push(`${name.padEnd(48)} ${(metric.values.rate * 100).toFixed(2)} %`);
    }
  }
  const failed = Object.entries(m)
    .filter(([, metric]) => metric.thresholds && Object.values(metric.thresholds).some((t) => !t.ok))
    .map(([name]) => name);
  lines.push(failed.length ? `THRESHOLDS FAILED: ${failed.join(', ')}` : 'All thresholds passed.');
  return lines.join('\n') + '\n';
}
