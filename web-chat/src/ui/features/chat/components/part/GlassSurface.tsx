import type { CSSProperties, ReactNode } from 'react';
import { Suspense, lazy } from 'react';

type GlassVariant = 'water' | 'liquid';

const GlassSurfaceLiquid = lazy(() => import('./GlassSurfaceLiquid'));

// Warm-prefetch the lazy liquid-glass chunk. Uses the exact same import
// specifier as the `lazy()` above so it resolves to the same chunk and the
// first glass message renders without a plain -> glass flash. Must only be
// called AFTER the connection overlay is dismissed (never on the overlay's
// critical path) and only for themes that actually use a glass variant.
export function prefetchGlassSurface(): Promise<unknown> {
  return import('./GlassSurfaceLiquid');
}

export function GlassSurface({
  variant,
  className,
  style,
  children,
  radius,
  baseColor,
  borderColor,
  themeMode,
  widthMode
}: {
  variant?: GlassVariant | null;
  className?: string;
  style?: CSSProperties;
  children: ReactNode;
  radius?: number;
  baseColor?: string | null;
  borderColor?: string | null;
  themeMode?: string;
  widthMode?: 'fill' | 'intrinsic';
}) {
  const plain = (
    <div className={className} style={style}>
      {children}
    </div>
  );

  if (!variant) {
    return plain;
  }

  return (
    <Suspense fallback={plain}>
      <GlassSurfaceLiquid
        baseColor={baseColor}
        borderColor={borderColor}
        className={className}
        radius={radius}
        style={style}
        themeMode={themeMode}
        variant={variant}
        widthMode={widthMode}
      >
        {children}
      </GlassSurfaceLiquid>
    </Suspense>
  );
}
