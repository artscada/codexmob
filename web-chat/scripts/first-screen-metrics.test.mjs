// Fast, browser-free unit tests for the first-screen TTI/TBT math.
// Run with: node --test scripts/first-screen-metrics.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  TTI_QUIET_WINDOW_MS,
  LONG_TASK_THRESHOLD_MS,
  computeTti,
  computeTbt
} from './first-screen-metrics.mjs';

test('constants have the documented values', () => {
  assert.equal(TTI_QUIET_WINDOW_MS, 2_000);
  assert.equal(LONG_TASK_THRESHOLD_MS, 50);
});

test('computeTti returns null when FCP is missing', () => {
  assert.equal(computeTti(null, 500, [], 5_000), null);
});

test('computeTti anchors at FCP when no long tasks and no overlay', () => {
  // overlayReady absent -> falls back to FCP.
  assert.equal(computeTti(1_000, null, [], 5_000), 1_000);
});

test('computeTti falls back to FCP when overlayReady is null', () => {
  // A long task entirely inside the post-FCP quiet window must not push TTI
  // past FCP here because it ends before the anchor.
  const longTasks = [{ start: 200, dur: 100 }]; // ends at 300, before FCP 1000
  assert.equal(computeTti(1_000, null, longTasks, 5_000), 1_000);
});

test('computeTti anchors at the later of FCP and overlayReady', () => {
  // overlayReady (1500) is later than FCP (1000): the control isn't usable
  // until 1500 even though content painted at 1000.
  assert.equal(computeTti(1_000, 1_500, [], 5_000), 1_500);
  // FCP later than overlayReady: anchor at FCP.
  assert.equal(computeTti(1_800, 1_200, [], 5_000), 1_800);
});

test('computeTti does NOT collapse to FCP when long tasks all end before FCP', () => {
  // Regression guard: heavy parse/exec long tasks finishing *just before* FCP
  // used to leave a post-FCP search with nothing to find, collapsing TTI to FCP.
  // With the anchor walk they are simply skipped (end <= anchor), so TTI is the
  // anchor (overlayReady here), never something earlier than FCP.
  const longTasks = [
    { start: 0, dur: 400 }, // ends 400
    { start: 450, dur: 500 } // ends 950, still before FCP 1000
  ];
  const fcp = 1_000;
  const overlayReady = 1_200;
  const tti = computeTti(fcp, overlayReady, longTasks, 5_000);
  assert.equal(tti, 1_200); // anchor, not collapsed below FCP/overlay
  assert.ok(tti >= fcp, 'TTI must never be earlier than FCP');
});

test('computeTti extends past a trailing long task after overlay-ready', () => {
  // A late long task (e.g. hydration) starts within the quiet window after the
  // anchor, so TTI must extend to that task's end.
  const fcp = 1_000;
  const overlayReady = 1_000;
  const longTasks = [{ start: 1_500, dur: 300 }]; // starts 500ms after anchor (< 2000 quiet)
  // candidate walks from 1000 -> task starts at 1500 (gap 500 < 2000) -> end 1800
  assert.equal(computeTti(fcp, overlayReady, longTasks, 5_000), 1_800);
});

test('computeTti stops at the first full quiet window', () => {
  const fcp = 1_000;
  const overlayReady = 1_000;
  const longTasks = [
    { start: 1_200, dur: 100 }, // ends 1300, within quiet window of anchor
    { start: 4_000, dur: 500 } // starts 2700ms after 1300 -> beyond quiet window, ignored
  ];
  // candidate: 1000 -> 1300 (first task). Second task gap 4000-1300=2700 >= 2000 => break.
  assert.equal(computeTti(fcp, overlayReady, longTasks, 6_000), 1_300);
});

test('computeTti is clamped to the last observed event time', () => {
  const fcp = 1_000;
  const overlayReady = 1_000;
  const longTasks = [{ start: 1_500, dur: 5_000 }]; // would end at 6500
  // lastEventTime caps it at 3000 (still >= anchor).
  assert.equal(computeTti(fcp, overlayReady, longTasks, 3_000), 3_000);
});

test('computeTti clamp never drops below the anchor', () => {
  // Even if lastEventTime is implausibly early, the result stays at the anchor.
  const fcp = 1_000;
  const overlayReady = 1_500;
  assert.equal(computeTti(fcp, overlayReady, [], 100), 1_500);
});

test('computeTbt sums only the portion of each task over the 50ms threshold', () => {
  const longTasks = [
    { start: 0, dur: 60 }, // 10 over
    { start: 100, dur: 250 }, // 200 over
    { start: 500, dur: 50 }, // exactly threshold -> 0
    { start: 600, dur: 40 } // below threshold -> 0
  ];
  assert.equal(computeTbt(longTasks), 210);
});

test('computeTbt counts pre-FCP long tasks (no anchoring)', () => {
  // Tasks entirely before FCP still contribute to TBT, unlike TTI.
  const longTasks = [{ start: 0, dur: 300 }]; // 250 over threshold
  assert.equal(computeTbt(longTasks), 250);
});

test('computeTbt is zero for an empty task list', () => {
  assert.equal(computeTbt([]), 0);
});
