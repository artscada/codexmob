// Pure, dependency-free helpers for diffing first-screen chunk sizes against a
// saved baseline. Kept in its own module so the (subtle) hash-stripping match
// logic can be unit-tested without a build, and reused by the Markdown summary
// in `measure-first-screen.mjs`.

// Strip Vite's content hash from an asset filename to get a stable logical key.
// Vite names eager/vendor chunks `<name>-<hash>.<ext>` where <hash> is an
// 8-char content hash that changes on every build. Matching on the raw filename
// would make every chunk look removed + re-added each build, so we drop the
// trailing `-<hash>` and compare by the logical name + extension instead. The
// extension is kept so the JS entry and the CSS bundle (both named `index`)
// don't collide on the same key.
//   react-DYrC2osf.js          -> react.js
//   index-CE_bup-9.js          -> index.js
//   html-sanitize-GoFsRstw.js  -> html-sanitize.js  (inner hyphens preserved)
//   index-CDuGIP0A.css         -> index.css
export function chunkKey(name) {
  const m = name.match(/^(.*)-[\w-]{8}\.(js|css)$/);
  return m ? `${m[1]}.${m[2]}` : name;
}

// Diff the current first-screen chunks against the baseline ones, matching by
// logical key so hash churn isn't reported as a change.
//
// `currentChunks` / `baselineChunks` are arrays of `{ name, sizes: { gzip, ... } }`.
// Returns `{ rows, removed }`:
//   rows    – one entry per current chunk, in input order, each with the gzip
//             delta vs its baseline match (`deltaGzip`/`baseGzip` are null and
//             `status` is 'new' when the chunk has no baseline counterpart).
//   removed – baseline chunks with no current match (renamed beyond the key
//             match, or genuinely dropped), so a size drop is explained rather
//             than silently missing from the table.
export function diffFirstScreenChunks(currentChunks, baselineChunks) {
  const baseByKey = new Map();
  for (const item of baselineChunks ?? []) {
    if (!item) continue;
    baseByKey.set(chunkKey(item.name), item.sizes);
  }

  const seen = new Set();
  const rows = (currentChunks ?? []).filter(Boolean).map((item) => {
    const key = chunkKey(item.name);
    seen.add(key);
    const base = baseByKey.get(key);
    return {
      name: item.name,
      key,
      sizes: item.sizes,
      baseGzip: base ? base.gzip : null,
      deltaGzip: base ? item.sizes.gzip - base.gzip : null,
      status: base ? 'changed' : 'new'
    };
  });

  const removed = [];
  for (const [key, sizes] of baseByKey) {
    if (seen.has(key)) continue;
    removed.push({ key, baseGzip: sizes.gzip, deltaGzip: -sizes.gzip, status: 'removed' });
  }

  return { rows, removed };
}

// Format a signed gzip byte delta as a kB cell, e.g. `+0.24 kB`, `-1.10 kB`,
// `+0.00 kB`. `null` (no baseline counterpart) renders as `new`.
export function formatGzipDelta(bytes) {
  if (bytes == null) return 'new';
  const sign = bytes >= 0 ? '+' : '-';
  return `${sign}${(Math.abs(bytes) / 1024).toFixed(2)} kB`;
}
