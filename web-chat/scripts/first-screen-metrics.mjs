// Pure, browser-free first-screen metric math used by
// `measure-first-screen-browser.mjs`. Kept in its own module so the subtle
// TTI/TBT logic can be unit-tested without spinning up headless Chromium.

// Quiet window (ms, page time) with no long tasks that marks the page "interactive".
export const TTI_QUIET_WINDOW_MS = 2_000;

// Long tasks (Long Tasks API) only fire for main-thread work >= this many ms.
// Everything above the threshold is "blocking" time the user can't interact through.
export const LONG_TASK_THRESHOLD_MS = 50;

// Compute a "ready to use" time-to-interactive proxy.
//
// The naive "first quiet window strictly after FCP" definition collapses to FCP
// on throttled phones, because the heavy parse/exec long tasks finish *just
// before* the contentful paint, leaving nothing for a post-FCP search to find.
// That hides the fact that the interactive control (the token input) isn't even
// in the DOM yet at FCP.
//
// Instead we anchor at the moment the overlay is genuinely usable -- the later
// of FCP and overlayReady (when `#web-chat-token` exists) -- then, Lighthouse
// style, walk forward through long tasks and extend the candidate past any task
// that starts before a full TTI_QUIET_WINDOW_MS of main-thread quiet has
// elapsed. So the metric reflects when the control exists *and* the main thread
// has settled, and it still captures trailing long tasks (e.g. late hydration).
export function computeTti(fcp, overlayReady, longTasks, lastEventTime) {
  if (fcp == null) return null;
  // Can't be "ready to use" before content paints or before the control exists.
  const anchor = Math.max(fcp, overlayReady ?? fcp);
  const sorted = [...longTasks].sort((a, b) => a.start - b.start);
  let candidate = anchor;
  for (const task of sorted) {
    const end = task.start + task.dur;
    if (end <= candidate) continue; // already covered by the anchor/earlier task
    if (task.start - candidate >= TTI_QUIET_WINDOW_MS) break; // quiet window found
    candidate = Math.max(candidate, end);
  }
  // Don't claim interactivity past the point we actually observed the page.
  return Math.min(candidate, Math.max(lastEventTime, anchor));
}

// Total Blocking Time: the summed main-thread blocking (each long task's time
// over the 50 ms threshold) across the whole observed first-screen load. Unlike
// the FCP-anchored TTI this *counts pre-FCP long tasks*, so it directly surfaces
// the JS parse/exec cost that fires before the overlay paints -- exactly the
// busyness the old TTI proxy threw away.
export function computeTbt(longTasks) {
  return longTasks.reduce((sum, t) => sum + Math.max(0, t.dur - LONG_TASK_THRESHOLD_MS), 0);
}
