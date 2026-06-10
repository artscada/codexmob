// Fast, build-free unit tests for the first-screen per-chunk diff helpers.
// Run with: node --test scripts/first-screen-diff.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { chunkKey, diffFirstScreenChunks, formatGzipDelta } from './first-screen-diff.mjs';

test('chunkKey strips the trailing content hash but keeps the extension', () => {
  assert.equal(chunkKey('react-DYrC2osf.js'), 'react.js');
  assert.equal(chunkKey('index-CE_bup-9.js'), 'index.js'); // hash itself contains a hyphen
  assert.equal(chunkKey('index-CDuGIP0A.css'), 'index.css');
});

test('chunkKey keeps js and css with the same base name distinct', () => {
  // The JS entry and the CSS bundle are both named `index`; they must not
  // collide on the same key or the diff matches a chunk against the wrong type.
  assert.notEqual(chunkKey('index-Bh3iVu3S.js'), chunkKey('index-CDuGIP0A.css'));
});

test('chunkKey preserves inner hyphens in the logical name', () => {
  assert.equal(chunkKey('html-sanitize-GoFsRstw.js'), 'html-sanitize.js');
  assert.equal(chunkKey('CodexVersionManager-Dv9v5kAK.js'), 'CodexVersionManager.js');
});

test('diff matches chunks across a hash change (no false new/removed)', () => {
  // Same logical chunk, different content hash + size between builds.
  const current = [{ name: 'react-AAAAAAAA.js', sizes: { gzip: 60000 } }];
  const baseline = [{ name: 'react-BBBBBBBB.js', sizes: { gzip: 59977 } }];
  const { rows, removed } = diffFirstScreenChunks(current, baseline);
  assert.equal(removed.length, 0, 'a hash change must not look like a removal');
  assert.equal(rows.length, 1);
  assert.equal(rows[0].status, 'changed');
  assert.equal(rows[0].baseGzip, 59977);
  assert.equal(rows[0].deltaGzip, 23);
});

test('diff flags a brand-new chunk with null delta', () => {
  const current = [{ name: 'newthing-AAAAAAAA.js', sizes: { gzip: 1000 } }];
  const { rows, removed } = diffFirstScreenChunks(current, []);
  assert.equal(rows[0].status, 'new');
  assert.equal(rows[0].deltaGzip, null);
  assert.equal(rows[0].baseGzip, null);
  assert.equal(removed.length, 0);
});

test('diff reports a removed baseline chunk with a negative delta', () => {
  const baseline = [{ name: 'legacy-BBBBBBBB.js', sizes: { gzip: 5000 } }];
  const { rows, removed } = diffFirstScreenChunks([], baseline);
  assert.equal(rows.length, 0);
  assert.equal(removed.length, 1);
  assert.equal(removed[0].key, 'legacy.js');
  assert.equal(removed[0].deltaGzip, -5000);
  assert.equal(removed[0].status, 'removed');
});

test('diff preserves current chunk input order', () => {
  const current = [
    { name: 'react-AAAAAAAA.js', sizes: { gzip: 60000 } },
    { name: 'index-BBBBBBBB.js', sizes: { gzip: 47000 } }
  ];
  const { rows } = diffFirstScreenChunks(current, []);
  assert.deepEqual(rows.map((r) => r.key), ['react.js', 'index.js']);
});

test('diff tolerates null/undefined chunk lists', () => {
  const { rows, removed } = diffFirstScreenChunks(undefined, null);
  assert.deepEqual(rows, []);
  assert.deepEqual(removed, []);
});

test('formatGzipDelta signs and rounds to kB, and renders null as new', () => {
  assert.equal(formatGzipDelta(0), '+0.00 kB');
  assert.equal(formatGzipDelta(245), '+0.24 kB');
  assert.equal(formatGzipDelta(-1126), '-1.10 kB');
  assert.equal(formatGzipDelta(null), 'new');
});
