import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const MARKDOWN_PACKAGE_HINTS = [
  'react-markdown',
  'remark',
  'micromark',
  'mdast',
  'hast',
  'unist',
  'unified',
  'vfile',
  'property-information',
  'space-separated-tokens',
  'comma-separated-tokens',
  'decode-named-character-reference',
  'character-entities',
  'html-url-attributes',
  'trim-lines',
  'longest-streak',
  'markdown-table',
  'zwitch',
  'ccount',
  'escape-string-regexp',
  'devlop',
  'bail',
  'trough',
  'is-plain-obj'
];

function resolveVendorChunk(id: string): string | undefined {
  const segments = id.split('node_modules/');
  const packagePath = segments[segments.length - 1] ?? '';

  if (packagePath.startsWith('dompurify')) {
    return 'html-sanitize';
  }

  if (MARKDOWN_PACKAGE_HINTS.some((hint) => packagePath.startsWith(hint))) {
    return 'markdown';
  }

  if (packagePath.startsWith('liquid-glass-react')) {
    return 'glass';
  }

  if (
    packagePath.startsWith('react-dom') ||
    packagePath.startsWith('react/') ||
    packagePath.startsWith('react-reconciler') ||
    packagePath.startsWith('scheduler')
  ) {
    return 'react';
  }

  return 'vendor';
}

export default defineConfig({
  plugins: [react()],
  server: {
    host: '0.0.0.0',
    port: 5000,
    allowedHosts: true,
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules')) {
            return resolveVendorChunk(id);
          }
          return undefined;
        }
      }
    }
  }
});
