import { Suspense, lazy } from 'react';

const MarkdownRendererImpl = lazy(() => import('./MarkdownRendererImpl'));

// Warm-prefetch the lazy markdown renderer chunk. Uses the exact same import
// specifier as the `lazy()` above so it resolves to the same chunk and the
// first formatted message renders without waiting on a separate download. Must
// only be called AFTER the connection overlay is dismissed (never on the
// overlay's critical path), mirroring prefetchGlassSurface in GlassSurface.tsx.
export function prefetchMarkdownRenderer(): Promise<unknown> {
  return import('./MarkdownRendererImpl');
}

export function MarkdownRenderer({
  content,
  className
}: {
  content: string;
  className?: string;
}) {
  if (!content.trim()) {
    return null;
  }

  return (
    <Suspense
      fallback={
        <div className={className ?? 'markdown-block'}>
          <p className="markdown-paragraph">{content}</p>
        </div>
      }
    >
      <MarkdownRendererImpl content={content} className={className} />
    </Suspense>
  );
}
