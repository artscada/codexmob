---
name: web-chat first-screen chunk budget
description: How to add a dependency to web-chat without tripping the first-screen size guard
---

# Adding deps to web-chat without bloating the first screen

web-chat has a CI guard (`npm --prefix web-chat run measure:check`, also the
`webchat-first-screen` validation) that fails when first-screen gzip JS grows
>5% vs `web-chat/perf/first-screen-baseline.json`.

`vite.config.ts` routes node_modules into named chunks via `manualChunks`
(`markdown`, `glass`, `react`, `vendor`). The measure script counts a chunk as
"first-screen" only if it's reachable through the **static** import graph from
the entry. Lazy (`React.lazy`/dynamic `import()`) chunks are excluded.

**Rule:** A dependency only needed on a rare render path (e.g. DOMPurify for an
occasional `<html>` block) must be reached only through a lazy boundary. Put it
in its own module and `lazy(() => import('./Thing'))` it.

**Why:** Two wrong approaches were tried first:
- Static `import DOMPurify` in a first-screen component → its chunk (`vendor`)
  becomes first-screen, +9% (over budget).
- Adding the dep to the existing deferred `markdown` chunk → because a
  first-screen component statically imported it, the WHOLE `markdown` chunk
  (react-markdown, ~54kB gzip) got dragged onto the first screen, +40%.

**How to apply:** Two things together: (1) Mirror the existing `MarkdownRenderer`
pattern — a thin wrapper with `Suspense` + `lazy()` around an `*Impl` module that
holds the heavy dep. (2) Give the dep its own `manualChunks` rule (e.g.
`dompurify` -> `html-sanitize`) so it can't ride along inside the shared `vendor`
chunk that first-screen code also pulls. Verify with `measure:check` AND by
grepping `dist/index.html` for the chunk name (it must NOT appear in preloads).
Result for DOMPurify: own 9.89kB-gzip chunk, first-screen +0.0%. Do NOT just bump
the baseline to get past the guard when lazy-loading is available.
