const fs = require('node:fs');
const path = require('node:path');
const { performance } = require('node:perf_hooks');

const API_URL = process.env.API_URL || 'http://localhost:5001';
const CONCURRENCY = Number(process.env.CONCURRENCY || 100);
const SETUP_CONCURRENCY = Number(process.env.SETUP_CONCURRENCY || 5);
const REQUEST_TIMEOUT_MS = Number(process.env.REQUEST_TIMEOUT_MS || 10000);
const IMAGE_PATH = process.env.PROFILE_IMAGE || path.resolve(__dirname, '../fixtures/images/profile.jpg');
const IMAGE_CONTENT_TYPE = process.env.PROFILE_IMAGE_CONTENT_TYPE
  || (path.extname(IMAGE_PATH).toLowerCase() === '.webp' ? 'image/webp' : 'image/jpeg');
const samples = new Map();

function record(name, ms, status, error = null) {
  if (!samples.has(name)) samples.set(name, []);
  samples.get(name).push({ ms, status, error });
}

async function measured(name, route, options = {}) {
  const started = performance.now();
  try {
    const response = await fetch(`${API_URL}${route}`, {
      ...options,
      redirect: 'follow',
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
    const body = await response.text();
    const elapsed = performance.now() - started;
    if (!response.ok) {
      record(name, elapsed, response.status, body.slice(0, 200));
      const error = new Error(`${name} HTTP ${response.status}: ${body.slice(0, 200)}`);
      error.recorded = true;
      throw error;
    }
    record(name, elapsed, response.status);
    const contentType = response.headers.get('content-type') || '';
    return {
      data: body && contentType.includes('application/json') ? JSON.parse(body) : body,
      finalUrl: response.url,
    };
  } catch (error) {
    if (!error.recorded) record(name, performance.now() - started, 0, error.message);
    throw error;
  }
}

async function prepareUser(index, runId) {
  const credentials = {
    email: `profile_api_${runId}_${index}@example.com`,
    password: 'Password123!',
    passwordConfirm: 'Password123!',
    name: `Profile API ${index}`,
  };
  await measured('setup.register', '/api/auth/register', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(credentials),
  });
  const login = await measured('setup.login', '/api/auth/login', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ email: credentials.email, password: credentials.password }),
  });
  return { index, token: login.data.token, sessionId: login.data.sessionId };
}

async function prepareUsers(runId) {
  const users = new Array(CONCURRENCY);
  let next = 0;
  async function worker() {
    while (next < CONCURRENCY) {
      const index = next++;
      users[index] = await prepareUser(index, runId);
    }
  }
  await Promise.all(Array.from({ length: Math.min(SETUP_CONCURRENCY, CONCURRENCY) }, worker));
  return users;
}

async function profileFlow(user, imageBytes) {
  const authHeaders = {
    'x-auth-token': user.token,
    'x-session-id': user.sessionId,
  };
  await measured('profile.get', '/api/users/profile', { headers: authHeaders });

  const form = new FormData();
  form.append('profileImage', new Blob([imageBytes], { type: IMAGE_CONTENT_TYPE }), path.basename(IMAGE_PATH));
  const upload = await measured('profile.upload', '/api/users/profile-image', {
    method: 'POST',
    headers: authHeaders,
    body: form,
  });

  await measured('profile.update', '/api/users/profile', {
    method: 'PUT',
    headers: { ...authHeaders, 'content-type': 'application/json' },
    body: JSON.stringify({ name: `Updated ${user.index}` }),
  });

  await measured('profile.image_get', upload.data.imageUrl);
}

function percentile(values, percentileValue) {
  const sorted = [...values].sort((a, b) => a - b);
  const index = Math.min(sorted.length - 1, Math.ceil((percentileValue / 100) * sorted.length) - 1);
  return sorted[index];
}

function buildReport(results, durationMs, imageSize) {
  const report = {};
  for (const [name, rows] of samples) {
    const times = rows.map(({ ms }) => ms);
    report[name] = {
      requests: rows.length,
      success: rows.filter(({ status }) => status >= 200 && status < 400).length,
      errors: rows.filter(({ status }) => status < 200 || status >= 400).length,
      avg_ms: +(times.reduce((sum, value) => sum + value, 0) / times.length).toFixed(1),
      p50_ms: +percentile(times, 50).toFixed(1),
      p95_ms: +percentile(times, 95).toFixed(1),
      p99_ms: +percentile(times, 99).toFixed(1),
      max_ms: +Math.max(...times).toFixed(1),
      statuses: Object.fromEntries(
        [...new Set(rows.map(({ status }) => status))]
          .map((status) => [status, rows.filter((row) => row.status === status).length])
      ),
    };
  }
  return {
    target: API_URL,
    concurrency: CONCURRENCY,
    setup_concurrency: SETUP_CONCURRENCY,
    fixture_bytes: imageSize,
    profile_duration_ms: +durationMs.toFixed(1),
    completed: results.filter(({ status }) => status === 'fulfilled').length,
    failed: results.filter(({ status }) => status === 'rejected').length,
    report,
  };
}

(async () => {
  const imageBytes = fs.readFileSync(IMAGE_PATH);
  const runId = Date.now();

  // 회원가입·로그인은 낮은 동시성으로 먼저 끝내고 Profile Spike 측정에서 제외한다.
  const users = await prepareUsers(runId);
  const started = performance.now();
  const results = await Promise.allSettled(users.map((user) => profileFlow(user, imageBytes)));
  const output = buildReport(results, performance.now() - started, imageBytes.length);

  console.log(JSON.stringify(output, null, 2));
  if (output.failed > 0) process.exitCode = 1;
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
