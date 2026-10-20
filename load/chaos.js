// Traffic for the chaos test (scripts/chaos.ps1 runs this and breaks things meanwhile).
//
// Behaves like a well-written client: every request carries an Idempotency-Key, and when the
// answer is lost (connection refused while the API is down, 5xx, 409 "still in progress") it
// retries with the SAME key. Some requests are also sent twice on purpose.
//
// The exactly-once check: transfer number i always uses key "<run>-xfer-<i>" and a body computed
// from i alone. In teardown every key 0..N-1 is replayed once more. Every replay must answer 201
// or 422, and the database must hold exactly as many transfers as there were 201 answers — no
// duplicates from retries, nothing acknowledged and then lost.
import { check, sleep } from 'k6';
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';
import { createFundedTenant, post, summarize } from './lib/daybook.js';

const RATE = Number(__ENV.RATE || 30);
const SECONDS = Number(__ENV.SECONDS || 150);
const TRANSFERS = RATE * SECONDS; // the key space replayed in teardown
const ACCOUNTS = 20;
const FUNDING = 10_000_000;
const RETRY_FOR_MS = 60_000;

const retries = new Counter('client_retries');
const gaveUp = new Counter('client_gave_up');
const duplicateMismatch = new Counter('duplicate_mismatch');
const finalSettled = new Counter('final_transfers_settled');
const finalRejected = new Counter('final_transfers_rejected');
const finalOther = new Counter('final_transfers_unexpected');

export const options = {
  scenarios: {
    transfers: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: `${SECONDS}s`,
      // Retrying requests hold their VU, so allow plenty while the API is down.
      preAllocatedVUs: RATE * 2,
      maxVUs: RATE * 40,
      exec: 'transfers',
    },
    payments: {
      executor: 'constant-arrival-rate',
      rate: 4,
      timeUnit: '1s',
      duration: `${SECONDS}s`,
      preAllocatedVUs: 10,
      maxVUs: 150,
      exec: 'payments',
    },
  },
  teardownTimeout: '5m',
  summaryTrendStats: ['med', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    duplicate_mismatch: ['count==0'],
    final_transfers_unexpected: ['count==0'],
  },
};

export function setup() {
  const run = __ENV.RUN_ID || `chaos-${Date.now()}`;
  return { ...createFundedTenant(run, ACCOUNTS, FUNDING), run };
}

/** Transfer i, always the same request for the same i. */
function transferRequest(data, i) {
  const from = data.accounts[i % ACCOUNTS];
  const to = data.accounts[(i * 7 + 1 + (i % (ACCOUNTS - 1))) % ACCOUNTS];
  return {
    key: `${data.run}-xfer-${i}`,
    body: { fromAccountId: from, toAccountId: to === from ? data.accounts[(i + 1) % ACCOUNTS] : to, amountMinor: 100 + ((i * 7919) % 5_000) },
  };
}

/** Sends until a final answer arrives, always with the same key. */
function sendUntilAnswered(path, apiKey, key, body, finalStatuses) {
  const deadline = Date.now() + RETRY_FOR_MS;
  let res = post(path, apiKey, key, body);
  while (!finalStatuses.includes(res.status) && Date.now() < deadline) {
    retries.add(1);
    sleep(0.5 + Math.random());
    res = post(path, apiKey, key, body);
  }
  if (!finalStatuses.includes(res.status)) gaveUp.add(1);
  return res;
}

export function transfers(data) {
  const { key, body } = transferRequest(data, exec.scenario.iterationInTest);
  const res = sendUntilAnswered('/v1/transfers', data.apiKey, key, body, [201, 422]);
  // Every tenth request is sent again at once, like a client that never saw the first answer.
  if (exec.scenario.iterationInTest % 10 === 0 && (res.status === 201 || res.status === 422)) {
    const again = post('/v1/transfers', data.apiKey, key, body);
    if (again.status !== 0 && again.status < 500 && again.body !== res.body) duplicateMismatch.add(1);
  }
}

/** Top-ups and withdrawals through the PSP, which the chaos script switches off and on. */
export function payments(data) {
  const i = exec.scenario.iterationInTest;
  const account = data.accounts[i % ACCOUNTS];
  const withdraw = i % 3 === 2;
  const res = sendUntilAnswered(
    withdraw ? '/v1/withdrawals' : '/v1/topups',
    data.apiKey,
    `${data.run}-${withdraw ? 'wd' : 'top'}-${i}`,
    { accountId: account, amountMinor: 500 + (i % 50) * 10 },
    // 201 settled, 202 pending (outcome unknown, resolved later by the sweeper), 422 declined.
    [201, 202, 422],
  );
  check(res, { 'payment answered': (r) => [201, 202, 422].includes(r.status) });
}

export function teardown(data) {
  // Replay every key in batches. The system is healthy again by now, so each must have an answer.
  for (let start = 0; start < TRANSFERS; start += 50) {
    const batch = [];
    for (let i = start; i < Math.min(start + 50, TRANSFERS); i++) {
      const { key, body } = transferRequest(data, i);
      batch.push([
        'POST',
        `${__ENV.BASE_URL || 'http://api:8080'}/v1/transfers`,
        JSON.stringify(body),
        { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.apiKey}`, 'Idempotency-Key': key } },
      ]);
    }
    for (const res of http.batch(batch)) {
      if (res.status === 201) finalSettled.add(1);
      else if (res.status === 422) finalRejected.add(1);
      else finalOther.add(1);
    }
  }
}

export function handleSummary(data) {
  const text = summarize(data, `Chaos traffic: ${RATE}/s transfers + 4/s PSP payments for ${SECONDS}s`);
  const result = {
    tenantId: data.setup_data.tenantId,
    run: data.setup_data.run,
    transferKeys: TRANSFERS,
    finalSettled: data.metrics.final_transfers_settled?.values.count ?? 0,
    finalRejected: data.metrics.final_transfers_rejected?.values.count ?? 0,
    finalUnexpected: data.metrics.final_transfers_unexpected?.values.count ?? 0,
    duplicateMismatch: data.metrics.duplicate_mismatch?.values.count ?? 0,
    clientRetries: data.metrics.client_retries?.values.count ?? 0,
    clientGaveUp: data.metrics.client_gave_up?.values.count ?? 0,
  };
  return {
    stdout: text,
    [`/scripts/results/${data.setup_data.run}.json`]: JSON.stringify(result, null, 2),
  };
}
