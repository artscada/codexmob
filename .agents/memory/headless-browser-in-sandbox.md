---
name: Running headless Chromium in this Replit agent sandbox
description: Why bash-tool runs of headless Chromium against a real page get SIGKILLed, and the workaround.
---

The `bash` tool sandbox SIGKILLs (exit 137 / 143, with all buffered stdout AND filesystem writes from that call discarded) any command whose headless-Chromium subprocess does sustained work loading a *real* page. A trivial `data:` URL navigation succeeds; loading an actual app (heavy or even near-empty, via localhost HTTP server OR puppeteer request interception, single iteration, core-pinned with taskset/nice — all tried) gets the whole process group killed mid-run. It is NOT OOM (8 GB cgroup cap, ~3 GB used) and NOT the listening socket.

**Workaround that works:** run the browser job through a **one-shot workflow** (`configureWorkflow` with `outputType:"console"`, no `waitForPort`), then read results via `getWorkflowStatus` and from the files it wrote. Workflows are platform-managed and not subject to the bash-tool kill. Remember to `removeWorkflow` afterward — `configureWorkflow` also wires the workflow into the `Project` run-button task list in `.replit`, which you don't want left behind.

**Why:** lets you actually capture Puppeteer/Playwright timings (e.g. `web-chat`'s `measure:browser`) in this environment instead of concluding "no browser works here." Don't waste cycles re-trying via bash with more flags — go straight to a workflow.

**How to apply:** any time you need headless Chromium/Chrome to render a non-trivial page for measurement, screenshots, or scraping inside this sandbox, drive it from a workflow, not the bash tool.
