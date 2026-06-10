#!/usr/bin/env node
// Real on-device first-screen measurement for the web-chat connection overlay.
//
// Unlike the lightweight, dependency-free `measure-first-screen.mjs` (which only
// inspects byte sizes in `dist` and *estimates* network transfer time), this
// script drives a REAL headless Chromium with phone-like CPU + network
// throttling. It loads the built `web-chat/dist` connection overlay and records
// actual first-paint, first-contentful-paint and a time-to-interactive proxy,
// so the byte-based estimates can be cross-checked against measured reality and
// JS parse/exec cost on lower-end phones becomes visible.
//
// The heavy browser dependency (puppeteer-core + a Chromium binary) is isolated
// here: it is dynamically imported and Chromium is auto-detected, so the static
// `measure` script keeps working in environments without a browser.
//
// Usage:
//   node scripts/measure-first-screen-browser.mjs                 # measure + compare to browser baseline
//   node scripts/measure-first-screen-browser.mjs --update-baseline  # (re)write the browser baseline + report
//   node scripts/measure-first-screen-browser.mjs --check         # exit 1 if on-device TTI/TBT regressed
//   node scripts/measure-first-screen-browser.mjs --json          # print machine-readable JSON only
//   node scripts/measure-first-screen-browser.mjs --iterations=5  # samples per profile (default 3)
//
// --check is the on-device counterpart of the static `measure --check` byte
// gate: it re-measures and fails (exit 1) when "ready to use" (TTI) or
// main-thread blocking (TBT) regress past tolerance vs the saved browser
// baseline. Where no Chromium / puppeteer-core is available it SKIPS (exit 0)
// instead of hard-failing, so environments without a browser don't get blocked.
//
// Environment:
//   CHROMIUM_BIN / PUPPETEER_EXECUTABLE_PATH   override the Chromium executable

import { createServer } from 'node:http';
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync, statSync } from 'node:fs';
import { dirname, resolve, extname, normalize, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { computeTti, computeTbt } from './first-screen-metrics.mjs';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(scriptDir, '..');
const distDir = resolve(projectRoot, 'dist');
const indexHtml = resolve(distDir, 'index.html');
const byteBaselinePath = resolve(projectRoot, 'perf', 'first-screen-baseline.json');
const baselinePath = resolve(projectRoot, 'perf', 'first-screen-browser-baseline.json');
const reportPath = resolve(projectRoot, 'perf', 'first-screen-browser-report.md');

const args = new Set(process.argv.slice(2));
const updateBaseline = args.has('--update-baseline');
const checkMode = args.has('--check');
const jsonOnly = args.has('--json');
const iterationsArg = [...args].find((a) => a.startsWith('--iterations='));
const ITERATIONS = Math.max(1, Number(iterationsArg?.split('=')[1]) || 3);

// Regression tolerances for the --check gate. On-device timings are noisier than
// byte sizes (CPU throttling + sampling jitter), so each metric allows the
// larger of a percentage of the baseline OR an absolute millisecond floor, so
// small absolute numbers aren't tripped by ordinary run-to-run noise.
const TTI_TOLERANCE = 0.2; // 20% slower "ready to use" fails --check
const TTI_MIN_SLACK_MS = 150;
const TBT_TOLERANCE = 0.3; // 30% more main-thread blocking fails --check
const TBT_MIN_SLACK_MS = 50;

// Profiles combine a network shape (kept in sync with the static script's
// NETWORK_PROFILES via `staticProfile`) with a CPU throttle representing the
// device class. cpuRate is the Chromium slowdown multiplier (1 = no throttle).
const PROFILES = [
  {
    name: 'LAN Wi-Fi + mid-tier phone',
    staticProfile: 'LAN Wi-Fi (phone↔PC)',
    kbps: 50_000,
    rttMs: 10,
    cpuRate: 4
  },
  {
    name: 'Fast 3G/Slow 4G + mid-tier phone',
    staticProfile: 'Fast 3G / Slow 4G',
    kbps: 1_600,
    rttMs: 150,
    cpuRate: 4
  },
  {
    name: 'Slow 3G + low-end phone',
    staticProfile: 'Slow 3G',
    kbps: 400,
    rttMs: 400,
    cpuRate: 6
  }
];

// Phone-like viewport (iPhone 12/13 logical resolution).
const VIEWPORT = { width: 390, height: 844, deviceScaleFactor: 3, isMobile: true, hasTouch: true };

// Real wall-clock settle time we allow after load before reading metrics.
const SETTLE_MS = 3_000;

function fail(message) {
  console.error(`\n[measure-first-screen-browser] ${message}\n`);
  process.exit(1);
}

// In --check mode a missing browser must not block the build: there is simply
// nothing to measure, so skip cleanly (exit 0). Outside --check it stays a hard
// failure so the manual measure/baseline commands tell you what's wrong.
function skipOrFail(message) {
  if (checkMode) {
    console.error(`\n[measure-first-screen-browser] SKIP (--check): ${message}\n`);
    process.exit(0);
  }
  fail(message);
}

function resolveChromium() {
  const fromEnv = process.env.CHROMIUM_BIN || process.env.PUPPETEER_EXECUTABLE_PATH;
  if (fromEnv && existsSync(fromEnv)) return fromEnv;
  for (const bin of ['chromium', 'chromium-browser', 'google-chrome', 'google-chrome-stable', 'chrome']) {
    try {
      const found = execFileSync('command', ['-v', bin], { shell: '/bin/bash', encoding: 'utf8' })
        .trim()
        .split('\n')[0];
      if (found && existsSync(found)) return found;
    } catch {
      // not on PATH, keep looking
    }
  }
  return null;
}

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf'
};

// Serve `dist` statically with an SPA fallback to index.html.
function startStaticServer() {
  return new Promise((resolveServer) => {
    const server = createServer((req, res) => {
      try {
        const urlPath = decodeURIComponent((req.url || '/').split('?')[0]);
        let filePath = resolve(distDir, `.${urlPath}`);
        const rel = normalize(filePath);
        if (!rel.startsWith(distDir + sep) && rel !== distDir) {
          res.writeHead(403);
          res.end('Forbidden');
          return;
        }
        if (existsSync(filePath) && statSync(filePath).isDirectory()) {
          filePath = resolve(filePath, 'index.html');
        }
        if (!existsSync(filePath)) {
          filePath = indexHtml; // SPA fallback
        }
        const body = readFileSync(filePath);
        res.writeHead(200, {
          'Content-Type': MIME[extname(filePath)] || 'application/octet-stream',
          'Cache-Control': 'no-store'
        });
        res.end(body);
      } catch (err) {
        res.writeHead(500);
        res.end(String(err));
      }
    });
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      resolveServer({ server, port });
    });
  });
}

function median(values) {
  if (!values.length) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
}

function round(value) {
  return value == null ? null : Math.round(value);
}

async function runProfile(puppeteer, executablePath, baseUrl, profile) {
  const samples = { fp: [], fcp: [], domContentLoaded: [], load: [], domInteractive: [], overlayReady: [], tti: [], tbt: [] };

  const browser = await puppeteer.launch({
    executablePath,
    headless: true,
    args: [
      '--no-sandbox',
      '--disable-setuid-sandbox',
      '--disable-gpu',
      '--disable-dev-shm-usage',
      '--no-zygote'
    ]
  });
  try {
    for (let i = 0; i < ITERATIONS; i += 1) {
      const context = await browser.createBrowserContext();
      const page = await context.newPage();
      await page.setViewport(VIEWPORT);

      const client = await page.target().createCDPSession();
      await client.send('Network.enable');
      await client.send('Network.setCacheDisabled', { cacheDisabled: true });
      await client.send('Network.emulateNetworkConditions', {
        offline: false,
        latency: profile.rttMs,
        downloadThroughput: (profile.kbps * 1000) / 8,
        uploadThroughput: (profile.kbps * 1000) / 8
      });
      await client.send('Emulation.setCPUThrottlingRate', { rate: profile.cpuRate });

      await page.evaluateOnNewDocument(() => {
        window.__longTasks = [];
        try {
          new PerformanceObserver((list) => {
            for (const entry of list.getEntries()) {
              window.__longTasks.push({ start: entry.startTime, dur: entry.duration });
            }
          }).observe({ type: 'longtask', buffered: true });
        } catch {
          // longtask unsupported; TTI proxy falls back to FCP
        }
      });

      // Use DOMContentLoaded, not 'load': with no backend the app keeps
      // requests pending, so the window 'load' event may never fire.
      await page.goto(baseUrl, { waitUntil: 'domcontentloaded', timeout: 30_000 });
      // Wait for the overlay's interactive control, then let the main thread settle.
      let overlayReady = null;
      try {
        await page.waitForSelector('#web-chat-token', { timeout: 30_000 });
        overlayReady = await page.evaluate(() => performance.now());
      } catch {
        // overlay never appeared; leave null
      }
      await new Promise((r) => setTimeout(r, SETTLE_MS));

      const metrics = await page.evaluate(() => {
        const paints = performance.getEntriesByType('paint');
        const fp = paints.find((p) => p.name === 'first-paint')?.startTime ?? null;
        const fcp = paints.find((p) => p.name === 'first-contentful-paint')?.startTime ?? null;
        const nav = performance.getEntriesByType('navigation')[0];
        // loadEventEnd is 0 when the window 'load' event has not fired (the app
        // keeps requests pending without a backend); report that as null.
        const loadEnd = nav && nav.loadEventEnd > 0 ? nav.loadEventEnd : null;
        return {
          fp,
          fcp,
          domContentLoaded: nav && nav.domContentLoadedEventEnd > 0 ? nav.domContentLoadedEventEnd : null,
          load: loadEnd,
          domInteractive: nav && nav.domInteractive > 0 ? nav.domInteractive : null,
          longTasks: window.__longTasks || [],
          now: performance.now()
        };
      });

      const tti = computeTti(metrics.fcp, overlayReady, metrics.longTasks, metrics.now);
      const tbt = computeTbt(metrics.longTasks);
      if (metrics.fp != null) samples.fp.push(metrics.fp);
      if (metrics.fcp != null) samples.fcp.push(metrics.fcp);
      if (metrics.domContentLoaded != null) samples.domContentLoaded.push(metrics.domContentLoaded);
      if (metrics.load != null) samples.load.push(metrics.load);
      if (metrics.domInteractive != null) samples.domInteractive.push(metrics.domInteractive);
      if (overlayReady != null) samples.overlayReady.push(overlayReady);
      if (tti != null) samples.tti.push(tti);
      if (tbt != null) samples.tbt.push(tbt);

      await context.close();
    }
  } finally {
    await browser.close();
  }

  return {
    profile: profile.name,
    staticProfile: profile.staticProfile,
    cpuRate: profile.cpuRate,
    kbps: profile.kbps,
    rttMs: profile.rttMs,
    samples: ITERATIONS,
    metrics: {
      firstPaintMs: round(median(samples.fp)),
      firstContentfulPaintMs: round(median(samples.fcp)),
      domContentLoadedMs: round(median(samples.domContentLoaded)),
      loadMs: round(median(samples.load)),
      domInteractiveMs: round(median(samples.domInteractive)),
      overlayReadyMs: round(median(samples.overlayReady)),
      timeToInteractiveMs: round(median(samples.tti)),
      totalBlockingTimeMs: round(median(samples.tbt))
    }
  };
}

function loadJson(path) {
  if (!existsSync(path)) return null;
  try {
    return JSON.parse(readFileSync(path, 'utf8'));
  } catch {
    return null;
  }
}

function estForStaticProfile(byteBaseline, staticProfile) {
  const row = byteBaseline?.network?.find((n) => n.profile === staticProfile);
  return row ? { gzip: row.estGzipMs, brotli: row.estBrotliMs } : null;
}

async function main() {
  if (!existsSync(distDir) || !existsSync(indexHtml)) {
    fail('Missing web-chat/dist. Run `npm --prefix web-chat run build` first.');
  }

  const executablePath = resolveChromium();
  if (!executablePath) {
    skipOrFail(
      'No Chromium/Chrome executable found. Install one (e.g. the `chromium` system package) ' +
        'or set CHROMIUM_BIN. The lightweight `npm run measure` script needs no browser.'
    );
  }

  let puppeteer;
  try {
    puppeteer = (await import('puppeteer-core')).default;
  } catch {
    skipOrFail(
      'puppeteer-core is not installed. Install it (root devDependency) to run the browser ' +
        'measurement. The lightweight `npm run measure` script does not need it.'
    );
  }

  const { server, port } = await startStaticServer();
  const baseUrl = `http://127.0.0.1:${port}/`;

  const profiles = [];
  try {
    for (const profile of PROFILES) {
      if (!jsonOnly) console.error(`measuring "${profile.name}" (${ITERATIONS} samples)...`);
      profiles.push(await runProfile(puppeteer, executablePath, baseUrl, profile));
    }
  } finally {
    server.close();
  }

  const byteBaseline = loadJson(byteBaselinePath);
  const result = {
    measuredAt: new Date().toISOString(),
    chromium: executablePath,
    iterations: ITERATIONS,
    viewport: VIEWPORT,
    byteBaselineBuildId: byteBaseline?.buildId ?? null,
    profiles: profiles.map((p) => ({
      ...p,
      estNetworkOnlyMs: estForStaticProfile(byteBaseline, p.staticProfile)
    }))
  };

  if (jsonOnly) {
    console.log(JSON.stringify(result, null, 2));
    process.exit(0);
  }

  printConsole(result);

  if (updateBaseline) {
    writeFileSync(baselinePath, JSON.stringify(result, null, 2));
    writeReportMarkdown(result);
    console.log(`\n✔ Browser baseline updated: ${baselinePath}`);
    console.log(`✔ Report written:          ${reportPath}`);
    process.exit(0);
  }

  if (checkMode) {
    checkAgainstBaseline(result);
    process.exit(0);
  }

  compareToBaseline(result);
}

function pad(str, width) {
  return String(str).padEnd(width);
}

function ms(value) {
  return value == null ? 'n/a' : `${value} ms`;
}

function printConsole(result) {
  console.log('\n=== web-chat first-screen REAL browser measurement ===');
  console.log(`chromium: ${result.chromium}`);
  console.log(`measured: ${result.measuredAt}   samples/profile: ${result.iterations}`);
  console.log(`viewport: ${result.viewport.width}x${result.viewport.height} @${result.viewport.deviceScaleFactor}x (mobile)`);
  console.log(
    `\n  ${pad('profile', 34)}${pad('FP', 10)}${pad('FCP', 10)}${pad('overlay', 11)}${pad('TTI', 10)}${pad('TBT', 10)}${pad('load', 10)}`
  );
  for (const p of result.profiles) {
    const m = p.metrics;
    console.log(
      `  ${pad(p.profile, 34)}${pad(ms(m.firstPaintMs), 10)}${pad(ms(m.firstContentfulPaintMs), 10)}${pad(
        ms(m.overlayReadyMs),
        11
      )}${pad(ms(m.timeToInteractiveMs), 10)}${pad(ms(m.totalBlockingTimeMs), 10)}${pad(ms(m.loadMs), 10)}`
    );
  }

  console.log('\nCross-check vs byte-based estimate (network-only lower bound)');
  console.log(`  ${pad('profile', 34)}${pad('est net (gzip)', 16)}${pad('measured FCP', 14)}parse/exec+RTT`);
  for (const p of result.profiles) {
    const est = p.estNetworkOnlyMs?.gzip ?? null;
    const fcp = p.metrics.firstContentfulPaintMs;
    const overhead = est != null && fcp != null ? fcp - est : null;
    console.log(
      `  ${pad(p.profile, 34)}${pad(ms(est), 16)}${pad(ms(fcp), 14)}${overhead == null ? 'n/a' : `${overhead >= 0 ? '+' : ''}${overhead} ms`}`
    );
  }
}

function writeReportMarkdown(result) {
  const lines = [];
  lines.push('# web-chat first-screen REAL browser measurement');
  lines.push('');
  lines.push(
    '_Auto-generated by `npm run measure:browser:baseline`. Do not edit by hand; rerun after a build to refresh._'
  );
  lines.push('');
  lines.push(`- Chromium: \`${result.chromium}\``);
  lines.push(`- Measured: ${result.measuredAt}`);
  lines.push(`- Samples per profile (median reported): ${result.iterations}`);
  lines.push(`- Viewport: ${result.viewport.width}x${result.viewport.height} @${result.viewport.deviceScaleFactor}x (mobile)`);
  if (result.byteBaselineBuildId) {
    lines.push(`- Cross-checked against byte baseline build: \`${result.byteBaselineBuildId}\``);
  }
  lines.push('');
  lines.push('## Measured first-screen timings (connection overlay)');
  lines.push('');
  lines.push('FP = first paint, FCP = first contentful paint, overlay = token input present.');
  lines.push('');
  lines.push(
    'TTI ("ready to use") is anchored at the later of FCP and overlay-ready, then extended through any long task that fires before a 2s quiet window — so it reflects when the control exists *and* the main thread has settled, not just FCP.'
  );
  lines.push('');
  lines.push(
    'TBT (total blocking time) sums every long task\'s time over 50 ms across the whole load, **including the parse/exec tasks that fire before FCP** — the main-thread busyness the FCP-anchored timings cannot show.'
  );
  lines.push('');
  lines.push('| profile | CPU | net | FP | FCP | overlay ready | TTI | TBT | load |');
  lines.push('| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |');
  for (const p of result.profiles) {
    const m = p.metrics;
    lines.push(
      `| ${p.profile} | ${p.cpuRate}x | ${p.kbps} kbps / ${p.rttMs} ms | ${ms(m.firstPaintMs)} | ${ms(
        m.firstContentfulPaintMs
      )} | ${ms(m.overlayReadyMs)} | ${ms(m.timeToInteractiveMs)} | ${ms(m.totalBlockingTimeMs)} | ${ms(m.loadMs)} |`
    );
  }
  lines.push('');
  lines.push('## Cross-check: byte-based estimate vs measured');
  lines.push('');
  lines.push(
    'The static `measure` script estimates **network transfer only** (a TTI lower bound). The gap below is real connection setup + JS parse/exec on a throttled phone CPU.'
  );
  lines.push('');
  lines.push('| profile | est network-only (gzip) | measured FCP | measured TTI | FCP − est |');
  lines.push('| --- | ---: | ---: | ---: | ---: |');
  for (const p of result.profiles) {
    const est = p.estNetworkOnlyMs?.gzip ?? null;
    const fcp = p.metrics.firstContentfulPaintMs;
    const overhead = est != null && fcp != null ? `${fcp - est >= 0 ? '+' : ''}${fcp - est} ms` : 'n/a';
    lines.push(`| ${p.profile} | ${ms(est)} | ${ms(fcp)} | ${ms(p.metrics.timeToInteractiveMs)} | ${overhead} |`);
  }
  lines.push('');
  writeFileSync(reportPath, lines.join('\n'));
}

// The --check gate: re-measured TTI/TBT must stay within tolerance of the saved
// browser baseline, or exit non-zero so a slow-to-interact regression is caught
// even when the download size stayed flat.
function checkAgainstBaseline(result) {
  const baseline = loadJson(baselinePath);
  if (!baseline) {
    console.log(
      '\nNo browser baseline saved yet — nothing to check against. ' +
        'Run `npm run measure:browser:baseline` to record one. Skipping (--check).'
    );
    process.exit(0);
  }

  console.log('\n=== On-device regression check vs browser baseline ===');
  console.log(`baseline measured: ${baseline.measuredAt}`);
  console.log(
    `tolerances: TTI +${(TTI_TOLERANCE * 100).toFixed(0)}% (min +${TTI_MIN_SLACK_MS} ms), ` +
      `TBT +${(TBT_TOLERANCE * 100).toFixed(0)}% (min +${TBT_MIN_SLACK_MS} ms)`
  );

  const regressions = [];
  const evalMetric = (label, profileName, cur, base, tol, minSlack) => {
    if (cur == null || base == null) {
      console.log(
        `  ${pad(profileName, 34)} ${label}: ` +
          `${cur == null ? 'n/a (not measured)' : `${ms(cur)} (no baseline)`}`
      );
      return;
    }
    const budget = Math.max(base * tol, minSlack);
    const d = cur - base;
    const over = d > budget;
    if (over) {
      regressions.push(
        `${profileName} — ${label} ${ms(base)} -> ${ms(cur)} (+${d} ms, budget +${Math.round(budget)} ms)`
      );
    }
    console.log(
      `  ${pad(profileName, 34)} ${label}: ${ms(base)} -> ${ms(cur)} ` +
        `(${d >= 0 ? '+' : ''}${d} ms, budget +${Math.round(budget)} ms) ${over ? 'FAIL' : 'ok'}`
    );
  };

  for (const p of result.profiles) {
    const base = baseline.profiles?.find((b) => b.profile === p.profile);
    evalMetric('TTI', p.profile, p.metrics.timeToInteractiveMs, base?.metrics?.timeToInteractiveMs, TTI_TOLERANCE, TTI_MIN_SLACK_MS);
    evalMetric('TBT', p.profile, p.metrics.totalBlockingTimeMs, base?.metrics?.totalBlockingTimeMs, TBT_TOLERANCE, TBT_MIN_SLACK_MS);
  }

  if (regressions.length) {
    fail(
      'On-device first-screen timings regressed beyond tolerance:\n' +
        regressions.map((r) => `  - ${r}`).join('\n') +
        '\n\nIf this slowdown is intentional, refresh the browser baseline with ' +
        '`npm --prefix web-chat run measure:browser:baseline` and commit the updated ' +
        'perf/first-screen-browser-baseline.json.'
    );
  }
  console.log('\n✔ On-device first-screen timings within tolerance.\n');
}

function compareToBaseline(result) {
  const baseline = loadJson(baselinePath);
  if (!baseline) {
    console.log(
      '\nNo browser baseline saved yet. Run `npm run measure:browser:baseline` to record one for future comparisons.'
    );
    return;
  }
  console.log('\n=== Comparison vs browser baseline ===');
  console.log(`baseline measured: ${baseline.measuredAt}`);
  const delta = (cur, base) => (cur == null || base == null ? null : cur - base);
  const fmt = (cur, base) => {
    const d = delta(cur, base);
    if (cur == null) return 'n/a (no baseline)';
    if (d == null) return `${ms(cur)} (no baseline)`;
    return `${ms(base)} -> ${ms(cur)} (${d >= 0 ? '+' : ''}${d} ms)`;
  };
  for (const p of result.profiles) {
    const base = baseline.profiles?.find((b) => b.profile === p.profile);
    console.log(`  ${pad(p.profile, 34)} TTI: ${fmt(p.metrics.timeToInteractiveMs, base?.metrics?.timeToInteractiveMs)}`);
    console.log(`  ${pad('', 34)} TBT: ${fmt(p.metrics.totalBlockingTimeMs, base?.metrics?.totalBlockingTimeMs)}`);
  }
  console.log('');
}

main().catch((err) => fail(err?.stack || String(err)));
