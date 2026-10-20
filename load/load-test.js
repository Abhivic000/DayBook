// Load test (NFR-1, NFR-2): throughput and latency of the money path.
//
//   docker compose --profile tools run --rm k6 run /scripts/load-test.js
//   ... -e RATE=200 -e DURATION=3m         (override the defaults below)
//
// Three scenarios run at the same time, at a fixed arrival rate (requests are started on schedule
// whether or not earlier ones finished, so a slow server shows up as latency, not as less load):
//   spread — transfers between random accounts: little lock contention, measures raw throughput
//   hot    — every transfer touches one "hot" account: requests queue on its row lock, which is
//            the price of pessimistic locking (CLAUDE.md: correctness over latency)
//   reads  — balance reads
// At the end, every account is read back: transfers move money, so the total must be unchanged.
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';
import { createFundedTenant, get, post, summarize, totalBalance } from './lib/daybook.js';

const RATE = Number(__ENV.RATE || 100);
const HOT_RATE = Number(__ENV.HOT_RATE || 20);
const READ_RATE = Number(__ENV.READ_RATE || 50);
const DURATION = __ENV.DURATION || '2m';
const ACCOUNTS = 50;
const FUNDING = 100_000_000; // ₹10,00,000 per account, in paise: transfers never run out

const transfersSettled = new Counter('transfers_settled');
const transfersRejected = new Counter('transfers_rejected');
const moneyConserved = new Counter('money_conserved'); // 1 if the end-of-test total matches

const arrival = (rate, fn) => ({
  executor: 'constant-arrival-rate',
  rate,
  timeUnit: '1s',
  duration: DURATION,
  preAllocatedVUs: Math.max(20, rate),
  maxVUs: rate * 5,
  exec: fn,
});

export const options = {
  scenarios: {
    spread: arrival(RATE, 'spread'),
    hot: arrival(HOT_RATE, 'hot'),
    reads: arrival(READ_RATE, 'reads'),
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  // Pass/fail gates. Generous on purpose: the README reports the measured numbers.
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{scenario:spread}': ['p(95)<500'],
    'http_req_duration{scenario:hot}': ['p(95)<1000'],
    'http_req_duration{scenario:reads}': ['p(95)<300'],
    money_conserved: ['count==1'],
  },
};

export function setup() {
  const run = `load-${Date.now()}`;
  const tenant = createFundedTenant(run, ACCOUNTS + 1, FUNDING); // the last one is the hot account
  return { ...tenant, run };
}

function transfer(data, from, to, tag) {
  const amount = 100 + Math.floor(Math.random() * 10_000);
  const key = `${data.run}-${tag}-${exec.scenario.iterationInTest}`;
  const res = post('/v1/transfers', data.apiKey, key, { fromAccountId: from, toAccountId: to, amountMinor: amount });
  check(res, { 'transfer 201 or 422': (r) => r.status === 201 || r.status === 422 });
  if (res.status === 201) transfersSettled.add(1);
  if (res.status === 422) transfersRejected.add(1);
}

function randomAccount(data, except) {
  let id;
  do {
    id = data.accounts[Math.floor(Math.random() * ACCOUNTS)];
  } while (id === except);
  return id;
}

export function spread(data) {
  const from = randomAccount(data);
  transfer(data, from, randomAccount(data, from), 'spread');
}

export function hot(data) {
  // Alternate direction so the hot account neither drains nor only fills.
  const hotAccount = data.accounts[ACCOUNTS];
  const other = randomAccount(data);
  if (exec.scenario.iterationInTest % 2 === 0) transfer(data, hotAccount, other, 'hot');
  else transfer(data, other, hotAccount, 'hot');
}

export function reads(data) {
  const res = get(`/v1/accounts/${randomAccount(data)}`, data.apiKey);
  check(res, { 'read 200': (r) => r.status === 200 });
}

export function teardown(data) {
  const expected = (ACCOUNTS + 1) * FUNDING;
  const actual = totalBalance(data.apiKey, data.accounts);
  console.log(`Money check: expected ${expected}, found ${actual}`);
  if (actual === expected) moneyConserved.add(1);
}

export function handleSummary(data) {
  const text = summarize(data, `Load test: ${RATE}/s spread + ${HOT_RATE}/s hot + ${READ_RATE}/s reads for ${DURATION}`);
  return {
    stdout: text,
    '/scripts/results/load-test.json': JSON.stringify(data, null, 2),
    '/scripts/results/load-test.txt': text,
  };
}
