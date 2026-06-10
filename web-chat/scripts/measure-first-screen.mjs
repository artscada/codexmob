#!/usr/bin/env node
// Measure the LAN-phone first-screen (connection overlay) download cost of the
// built web-chat app. Works purely against `web-chat/dist`, so it is fast,
// repeatable, and dependency-free (no headless browser required).
//
// "First screen" = everything the browser must download before it can paint and
// hydrate the connection overlay: the entry module plus every chunk Vite marks
// for eager loading in index.html (<script type=module> + <link rel=modulepreload>)
// and the initial stylesheet. Lazy chunks (markdown, agent panel, codex manager)
// are reported separately because they are NOT on the first-screen critical path.
//
// Usage:
//   node scripts/measure-first-screen.mjs                 # measure + compare to baseline
//   node scripts/measure-first-screen.mjs --update-baseline  # (re)write the saved baseline
//   node scripts/measure-first-screen.mjs --check         # exit 1 if first-screen JS regressed
//   node scripts/measure-first-screen.mjs --json          # print machine-readable JSON only
//   node scripts/measure-first-screen.mjs --summary       # print a Markdown summary (for $GITHUB_STEP_SUMMARY)

import { gzipSync, brotliCompressSync } from 'node:zlib';
import {
  existsSync,
  readFileSync,
  readdirSync,
  writeFileSync,
  statSync
} from 'node:fs';
import { dirname, resolve, basename } from 'node:path';
import { fileURLToPath } from 'node:url';
import { diffFirstScreenChunks, formatGzipDelta } from './first-screen-diff.mjs';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(scriptDir, '..');
const distDir = resolve(projectRoot, 'dist');
const indexHtml = resolve(distDir, 'index.html');
const baselinePath = resolve(projectRoot, 'perf', 'first-screen-baseline.json');
const reportPath = resolve(projectRoot, 'perf', 'first-screen-report.md');

const args = new Set(process.argv.slice(2));
const updateBaseline = args.has('--update-baseline');
const checkMode = args.has('--check');
const jsonOnly = args.has('--json');
const summaryMode = args.has('--summary');

// Simulated network profiles. Throughput is the realised download speed; latency
// is per-request round-trip overhead. These mirror Chrome DevTools / Lighthouse
// presets plus a LAN profile representing a phone on the same Wi-Fi as the PC.
// The estimate is network transfer only (it deliberately excludes JS parse/exec),
// so treat it as a lower bound on time-to-interactive.
const NETWORK_PROFILES = [
  { name: 'LAN Wi-Fi (phone↔PC)', kbps: 50_000, rttMs: 10, parallel: 6 },
  { name: 'Fast 3G / Slow 4G', kbps: 1_600, rttMs: 150, parallel: 6 },
  { name: 'Slow 3G', kbps: 400, rttMs: 400, parallel: 6 }
];

const REGRESSION_TOLERANCE = 0.05; // 5% growth in first-screen gzip JS fails --check

function fail(message) {
  console.error(`\n[measure-first-screen] ${message}\n`);
  process.exit(1);
}

if (!existsSync(distDir) || !existsSync(indexHtml)) {
  fail('Missing web-chat/dist. Run `npm --prefix web-chat run build` first.');
}

function sizesFor(filePath) {
  const buf = readFileSync(filePath);
  return {
    raw: buf.length,
    gzip: gzipSync(buf, { level: 9 }).length,
    brotli: brotliCompressSync(buf).length
  };
}

function extractEagerAssets(html) {
  const js = new Set();
  const css = new Set();
  let entry = null;

  // Entry module(s): <script type="module" ... src="/assets/xxx.js">
  for (const m of html.matchAll(/<script\b[^>]*\bsrc=["']([^"']+)["'][^>]*>/g)) {
    if (/type=["']module["']/.test(m[0])) {
      const name = basename(m[1]);
      js.add(name);
      if (!entry) entry = name;
    }
  }
  // Eagerly preloaded static-import chunks: <link rel="modulepreload" href=...>
  for (const m of html.matchAll(/<link\b[^>]*>/g)) {
    const tag = m[0];
    const href = tag.match(/href=["']([^"']+)["']/)?.[1];
    if (!href) continue;
    if (/rel=["']modulepreload["']/.test(tag)) js.add(basename(href));
    if (/rel=["']stylesheet["']/.test(tag)) css.add(basename(href));
  }
  return { js: [...js], css: [...css], entry };
}

function listAssetJs() {
  const assetsDir = resolve(distDir, 'assets');
  if (!existsSync(assetsDir)) return [];
  return readdirSync(assetsDir).filter((f) => f.endsWith('.js'));
}

function sumSizes(list) {
  return list.reduce(
    (acc, item) => ({
      raw: acc.raw + item.sizes.raw,
      gzip: acc.gzip + item.sizes.gzip,
      brotli: acc.brotli + item.sizes.brotli
    }),
    { raw: 0, gzip: 0, brotli: 0 }
  );
}

// Estimate transfer time for a bundle of files over a profile. Files are fetched
// `parallel` at a time; each request pays one RTT then streams at the shared link
// speed. This is a coarse model, accurate enough to compare runs and rank profiles.
function estimateTransferMs(files, transferKey, profile) {
  const bytesPerMs = (profile.kbps * 1000) / 8 / 1000; // kbps -> bytes per ms
  const sorted = [...files].sort((a, b) => b.sizes[transferKey] - a.sizes[transferKey]);
  const lanes = new Array(profile.parallel).fill(0);
  for (const file of sorted) {
    const lane = lanes.indexOf(Math.min(...lanes));
    const downloadMs = file.sizes[transferKey] / bytesPerMs;
    lanes[lane] += profile.rttMs + downloadMs;
  }
  return Math.round(Math.max(...lanes));
}

const html = readFileSync(indexHtml, 'utf8');
const eager = extractEagerAssets(html);
const allJs = listAssetJs();
const cssFile = eager.css[0]
  ? { name: eager.css[0], sizes: sizesFor(resolve(distDir, 'assets', eager.css[0])) }
  : null;

function describe(name) {
  return { name, sizes: sizesFor(resolve(distDir, 'assets', name)) };
}

const firstScreenJs = eager.js.map(describe).sort((a, b) => b.sizes.raw - a.sizes.raw);
const eagerNames = new Set(eager.js);
const deferredJs = allJs
  .filter((f) => !eagerNames.has(f))
  .map(describe)
  .sort((a, b) => b.sizes.raw - a.sizes.raw);

const firstScreenJsTotal = sumSizes(firstScreenJs);
const deferredJsTotal = sumSizes(deferredJs);
// What the browser actually pulls before first paint/interactive: eager JS + CSS.
const firstScreenCritical = {
  raw: firstScreenJsTotal.raw + (cssFile?.sizes.raw ?? 0),
  gzip: firstScreenJsTotal.gzip + (cssFile?.sizes.gzip ?? 0),
  brotli: firstScreenJsTotal.brotli + (cssFile?.sizes.brotli ?? 0)
};

const transferFiles = [...firstScreenJs, ...(cssFile ? [cssFile] : [])];
const network = NETWORK_PROFILES.map((profile) => ({
  profile: profile.name,
  estGzipMs: estimateTransferMs(transferFiles, 'gzip', profile),
  estBrotliMs: estimateTransferMs(transferFiles, 'brotli', profile)
}));

const result = {
  measuredAt: new Date().toISOString(),
  buildId: eager.entry ?? firstScreenJs[0]?.name ?? 'unknown',
  firstScreen: {
    js: firstScreenJs,
    css: cssFile,
    jsTotal: firstScreenJsTotal,
    criticalTotal: firstScreenCritical
  },
  deferred: {
    js: deferredJs,
    jsTotal: deferredJsTotal
  },
  network
};

if (jsonOnly) {
  console.log(JSON.stringify(result, null, 2));
  process.exit(0);
}

// Markdown summary for GitHub Actions job summary / PR surfacing. Prints the
// first-screen gzip JS delta vs baseline plus the per-chunk breakdown. Never
// fails (the separate --check run owns the pass/fail gate), so this can run on
// both passing and failing budget checks.
if (summaryMode) {
  const sBaseline = loadBaseline();
  const lines = [];
  if (!sBaseline) {
    // No baseline yet: there is no pass/fail verdict to show, so keep a neutral
    // heading and explain how to record one.
    lines.push('### web-chat 首屏体积 (first-screen budget)');
    lines.push('');
    lines.push(`Build entry chunk: \`${result.buildId}\``);
    lines.push('');
    lines.push(
      '> 尚未记录基线 (no baseline saved). 运行 `npm run measure:baseline` 生成基线后即可显示对比。'
    );
  } else {
    const sBase = sBaseline.firstScreen?.jsTotal?.gzip ?? 0;
    const sCur = firstScreenJsTotal.gzip;
    const sDelta = sCur - sBase;
    const sPct = sBase ? (sDelta / sBase) * 100 : 0;
    const sSign = sDelta >= 0 ? '+' : '';
    const tol = REGRESSION_TOLERANCE * 100;
    // Verdict uses the SAME tolerance as the `--check` gate, so the PASS/FAIL
    // shown here always matches the actual build outcome.
    const overBudget = sPct > tol;
    const headingTag = overBudget ? '🚨 FAIL' : '✅ PASS';
    const banner = overBudget
      ? `**🚨 超出预算 (over budget): ${sSign}${sPct.toFixed(1)}% > ${tol.toFixed(0)}% 容忍度 — 判定回归。**`
      : sDelta > 0
        ? `**✅ 在预算内 (within budget): ${sSign}${sPct.toFixed(1)}%（体积增加但未超预算）。**`
        : `**✅ 在预算内 (within budget): ${sSign}${sPct.toFixed(1)}%（体积未增加）。**`;

    // Leading PASS/FAIL tag + bolded verdict banner so reviewers see the outcome
    // at a glance without reading the table below.
    lines.push(`### web-chat 首屏体积 (first-screen budget) — ${headingTag}`);
    lines.push('');
    lines.push(banner);
    lines.push('');
    lines.push(`Build entry chunk: \`${result.buildId}\``);
    lines.push('');
    lines.push(
      `**首屏 JS (gzip): ${fmtKB(sBase)} → ${fmtKB(sCur)} ` +
        `(${sSign}${fmtKB(sDelta)}, ${sSign}${sPct.toFixed(1)}%)**`
    );
    lines.push('');
    lines.push(
      `基线 build: \`${sBaseline.buildId}\` · 预算容忍度：首屏 gzip JS 增长 > ${tol.toFixed(
        0
      )}% 即判定回归。`
    );
    lines.push('');
    // Per-chunk gzip delta vs baseline so a reviewer can see *which* chunk
    // moved, not just that the total did. Match by logical key (hash stripped)
    // so per-build hash churn isn't mistaken for chunks being removed/added.
    const baseFirstScreenChunks = [
      ...(sBaseline.firstScreen?.js ?? []),
      ...(sBaseline.firstScreen?.css ? [sBaseline.firstScreen.css] : [])
    ];
    const curFirstScreenChunks = [...firstScreenJs, ...(cssFile ? [cssFile] : [])];
    const { rows: chunkRows, removed: removedChunks } = diffFirstScreenChunks(
      curFirstScreenChunks,
      baseFirstScreenChunks
    );

    lines.push('| chunk | raw | gzip | brotli | Δ gzip vs base |');
    lines.push('| --- | ---: | ---: | ---: | ---: |');
    for (const row of chunkRows) {
      const tag = row.status === 'new' ? ' 🆕' : '';
      lines.push(
        `| ${row.name}${tag} | ${fmtKB(row.sizes.raw)} | ${fmtKB(row.sizes.gzip)} | ${fmtKB(
          row.sizes.brotli
        )} | ${formatGzipDelta(row.deltaGzip)} |`
      );
    }
    // Surface baseline chunks that vanished so a size drop is explained.
    for (const row of removedChunks) {
      lines.push(`| ~~${row.key}~~ (removed) | — | — | — | ${formatGzipDelta(row.deltaGzip)} |`);
    }
    const baseCriticalGzip = sBaseline.firstScreen?.criticalTotal?.gzip ?? 0;
    const criticalDelta = firstScreenCritical.gzip - baseCriticalGzip;
    lines.push(
      `| **critical total** | **${fmtKB(firstScreenCritical.raw)}** | **${fmtKB(
        firstScreenCritical.gzip
      )}** | **${fmtKB(firstScreenCritical.brotli)}** | **${formatGzipDelta(criticalDelta)}** |`
    );
    lines.push('');
    lines.push('估算首屏传输时间（仅网络传输，不含解析/执行）：');
    lines.push('');
    lines.push('| profile | gzip | brotli |');
    lines.push('| --- | ---: | ---: |');
    for (const row of network) {
      lines.push(`| ${row.profile} | ${row.estGzipMs} ms | ${row.estBrotliMs} ms |`);
    }
  }
  lines.push('');
  console.log(lines.join('\n'));
  process.exit(0);
}

function fmtKB(bytes) {
  return `${(bytes / 1024).toFixed(2)} kB`;
}

function pad(str, width) {
  return String(str).padEnd(width);
}

function printTable(title, items, totalLabel, total) {
  console.log(`\n${title}`);
  console.log(
    `  ${pad('chunk', 34)}${pad('raw', 12)}${pad('gzip', 12)}${pad('brotli', 12)}`
  );
  for (const item of items) {
    console.log(
      `  ${pad(item.name, 34)}${pad(fmtKB(item.sizes.raw), 12)}${pad(
        fmtKB(item.sizes.gzip),
        12
      )}${pad(fmtKB(item.sizes.brotli), 12)}`
    );
  }
  console.log(
    `  ${pad(totalLabel, 34)}${pad(fmtKB(total.raw), 12)}${pad(
      fmtKB(total.gzip),
      12
    )}${pad(fmtKB(total.brotli), 12)}`
  );
}

console.log('\n=== web-chat first-screen load measurement ===');
console.log(`build: ${result.buildId}   measured: ${result.measuredAt}`);

printTable(
  'First-screen JS (eager: entry + modulepreload)',
  firstScreenJs,
  'first-screen JS total',
  firstScreenJsTotal
);
if (cssFile) {
  console.log(
    `\nFirst-screen CSS\n  ${pad(cssFile.name, 34)}${pad(
      fmtKB(cssFile.sizes.raw),
      12
    )}${pad(fmtKB(cssFile.sizes.gzip), 12)}${pad(fmtKB(cssFile.sizes.brotli), 12)}`
  );
}
console.log(
  `\nFirst-screen critical total (eager JS + CSS): ` +
    `raw ${fmtKB(firstScreenCritical.raw)}, gzip ${fmtKB(
      firstScreenCritical.gzip
    )}, brotli ${fmtKB(firstScreenCritical.brotli)}`
);

printTable(
  'Deferred JS (lazy-loaded, NOT on first screen)',
  deferredJs,
  'deferred JS total',
  deferredJsTotal
);

console.log('\nEstimated first-screen transfer time (network only, excludes parse/exec)');
console.log(`  ${pad('profile', 28)}${pad('gzip', 14)}brotli`);
for (const row of network) {
  console.log(
    `  ${pad(row.profile, 28)}${pad(`${row.estGzipMs} ms`, 14)}${row.estBrotliMs} ms`
  );
}

// Baseline comparison / persistence.
function loadBaseline() {
  if (!existsSync(baselinePath)) return null;
  try {
    return JSON.parse(readFileSync(baselinePath, 'utf8'));
  } catch {
    return null;
  }
}

function writeReportMarkdown() {
  const lines = [];
  lines.push('# web-chat first-screen load baseline');
  lines.push('');
  lines.push(
    '_Auto-generated by `npm run measure:baseline`. Do not edit by hand; rerun the script after a build to refresh._'
  );
  lines.push('');
  lines.push(`- Build entry chunk: \`${result.buildId}\``);
  lines.push(`- Measured: ${result.measuredAt}`);
  lines.push('');
  lines.push('## First-screen critical download (connection overlay)');
  lines.push('');
  lines.push('These are downloaded before the overlay can paint/hydrate.');
  lines.push('');
  lines.push('| chunk | raw | gzip | brotli |');
  lines.push('| --- | ---: | ---: | ---: |');
  for (const item of firstScreenJs) {
    lines.push(
      `| ${item.name} | ${fmtKB(item.sizes.raw)} | ${fmtKB(item.sizes.gzip)} | ${fmtKB(
        item.sizes.brotli
      )} |`
    );
  }
  if (cssFile) {
    lines.push(
      `| ${cssFile.name} | ${fmtKB(cssFile.sizes.raw)} | ${fmtKB(
        cssFile.sizes.gzip
      )} | ${fmtKB(cssFile.sizes.brotli)} |`
    );
  }
  lines.push(
    `| **critical total** | **${fmtKB(firstScreenCritical.raw)}** | **${fmtKB(
      firstScreenCritical.gzip
    )}** | **${fmtKB(firstScreenCritical.brotli)}** |`
  );
  lines.push('');
  lines.push(`First-screen **JS only** total: gzip **${fmtKB(firstScreenJsTotal.gzip)}**, brotli **${fmtKB(firstScreenJsTotal.brotli)}**.`);
  lines.push('');
  lines.push('## Deferred chunks (lazy, off the first-screen path)');
  lines.push('');
  lines.push('| chunk | raw | gzip | brotli |');
  lines.push('| --- | ---: | ---: | ---: |');
  for (const item of deferredJs) {
    lines.push(
      `| ${item.name} | ${fmtKB(item.sizes.raw)} | ${fmtKB(item.sizes.gzip)} | ${fmtKB(
        item.sizes.brotli
      )} |`
    );
  }
  lines.push(
    `| **deferred total** | **${fmtKB(deferredJsTotal.raw)}** | **${fmtKB(
      deferredJsTotal.gzip
    )}** | **${fmtKB(deferredJsTotal.brotli)}** |`
  );
  lines.push('');
  lines.push('## Estimated first-screen transfer time');
  lines.push('');
  lines.push('Network transfer only (excludes JS parse/exec); lower bound on TTI.');
  lines.push('');
  lines.push('| profile | gzip | brotli |');
  lines.push('| --- | ---: | ---: |');
  for (const row of network) {
    lines.push(`| ${row.profile} | ${row.estGzipMs} ms | ${row.estBrotliMs} ms |`);
  }
  lines.push('');
  writeFileSync(reportPath, lines.join('\n'));
}

if (updateBaseline) {
  const perfDir = dirname(baselinePath);
  if (!existsSync(perfDir)) {
    // perf/ should exist in the repo; create defensively.
    readdirSync(projectRoot); // no-op touch
  }
  writeFileSync(baselinePath, JSON.stringify(result, null, 2));
  writeReportMarkdown();
  console.log(`\n✔ Baseline updated: ${baselinePath}`);
  console.log(`✔ Report written:  ${reportPath}`);
  process.exit(0);
}

const baseline = loadBaseline();
if (!baseline) {
  console.log(
    '\nNo baseline saved yet. Run `npm run measure:baseline` to record one for future comparisons.'
  );
  process.exit(0);
}

const baseGzip = baseline.firstScreen?.jsTotal?.gzip ?? 0;
const curGzip = firstScreenJsTotal.gzip;
const deltaBytes = curGzip - baseGzip;
const deltaPct = baseGzip ? (deltaBytes / baseGzip) * 100 : 0;
const sign = deltaBytes >= 0 ? '+' : '';
console.log('\n=== Comparison vs baseline ===');
console.log(`baseline build: ${baseline.buildId} (${baseline.measuredAt})`);
console.log(
  `first-screen JS gzip: ${fmtKB(baseGzip)} -> ${fmtKB(curGzip)} ` +
    `(${sign}${fmtKB(deltaBytes)}, ${sign}${deltaPct.toFixed(1)}%)`
);

if (checkMode && deltaPct > REGRESSION_TOLERANCE * 100) {
  fail(
    `First-screen JS grew ${deltaPct.toFixed(1)}% (> ${(
      REGRESSION_TOLERANCE * 100
    ).toFixed(0)}% tolerance). Investigate before shipping.`
  );
}
console.log('');
