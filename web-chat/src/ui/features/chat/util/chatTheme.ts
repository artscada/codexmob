import type { CSSProperties } from 'react';
import type { ChatThemeId, WebThemeSnapshot } from './chatTypes';
import { DEFAULT_CHAT_THEME_ID } from './ThemeStateHolder';

type ThemeStyle = CSSProperties & Record<string, string | number>;

export interface ChatThemeOption {
  id: ChatThemeId;
  label: string;
  description: string;
}

export const CHAT_THEME_OPTIONS: ChatThemeOption[] = [
  { id: 'modern', label: '现代', description: '极简纯黑 · 无气泡 · 大留白' },
  { id: 'developer', label: '开发者', description: '等宽字体 · 终端绿 · 代码感' },
  { id: 'glass', label: '玻璃', description: '毛玻璃 · 半透明 · 渐变光晕' }
];

const CHAT_THEME_PRESETS: Record<ChatThemeId, ThemeStyle> = {
  modern: {
    '--chat-root-background': '#0a0a0a',
    '--chat-surface-color': '#141416',
    '--chat-surface-variant-color': '#1c1c1f',
    '--chat-surface-container-color': '#151517',
    '--chat-surface-container-high-color': '#1d1d20',
    '--chat-surface-strong': '#141416',
    '--chat-surface-soft': 'rgba(28, 28, 31, 0.94)',
    '--chat-surface-faint': 'rgba(255, 255, 255, 0.04)',
    '--chat-card-soft': 'rgba(255, 255, 255, 0.04)',
    '--chat-card-strong': 'rgba(255, 255, 255, 0.08)',
    '--chat-history-panel-bg': 'rgba(15, 15, 17, 0.96)',
    '--chat-history-item-bg': 'rgba(255, 255, 255, 0.04)',
    '--chat-history-item-active-bg': 'rgba(255, 255, 255, 0.1)',
    '--chat-border': 'rgba(255, 255, 255, 0.1)',
    '--chat-field-border': 'rgba(255, 255, 255, 0.16)',
    '--chat-field-border-strong': 'rgba(255, 255, 255, 0.28)',
    '--chat-text-main': '#fafafa',
    '--chat-text-soft': '#a1a1aa',
    '--chat-text-muted': 'rgba(250, 250, 250, 0.6)',
    '--chat-primary': '#fafafa',
    '--chat-secondary': '#a1a1aa',
    '--chat-primary-container': '#27272a',
    '--chat-on-primary-container': '#fafafa',
    '--chat-on-primary': '#0a0a0a',
    '--chat-user-bubble': 'transparent',
    '--chat-assistant-bubble': 'transparent',
    '--chat-user-text': '#fafafa',
    '--chat-assistant-text': '#ededed',
    '--chat-user-radius': '6px',
    '--chat-assistant-radius': '6px',
    '--chat-font-family':
      '"Inter", "Geist", -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", sans-serif',
    '--chat-composer-bg': '#141416',
    '--chat-composer-bg-transparent': 'rgba(20, 20, 22, 0.72)',
    '--chat-composer-bg-strong': 'rgba(20, 20, 22, 0.92)',
    '--chat-field-bg': '#141416',
    '--chat-panel-bg': '#141416',
    '--chat-button-filled-bg': '#fafafa',
    '--chat-button-filled-fg': '#0a0a0a',
    '--chat-action-primary-bg': '#fafafa',
    '--chat-action-primary-fg': '#0a0a0a',
    '--chat-header-bg': 'transparent',
    '--chat-code-block-bg': 'rgba(255, 255, 255, 0.05)'
  },
  developer: {
    '--chat-root-background': '#0d1117',
    '--chat-surface-color': '#161b22',
    '--chat-surface-variant-color': '#1c2128',
    '--chat-surface-container-color': '#161b22',
    '--chat-surface-container-high-color': '#21262d',
    '--chat-surface-strong': '#161b22',
    '--chat-surface-soft': 'rgba(33, 38, 45, 0.94)',
    '--chat-surface-faint': 'rgba(110, 118, 129, 0.1)',
    '--chat-card-soft': 'rgba(110, 118, 129, 0.12)',
    '--chat-card-strong': 'rgba(110, 118, 129, 0.2)',
    '--chat-history-panel-bg': 'rgba(13, 17, 23, 0.96)',
    '--chat-history-item-bg': 'rgba(110, 118, 129, 0.1)',
    '--chat-history-item-active-bg': 'rgba(57, 211, 83, 0.18)',
    '--chat-border': 'rgba(48, 54, 61, 0.9)',
    '--chat-field-border': '#30363d',
    '--chat-field-border-strong': '#39d353',
    '--chat-text-main': '#c9d1d9',
    '--chat-text-soft': '#8b949e',
    '--chat-text-muted': 'rgba(201, 209, 217, 0.7)',
    '--chat-primary': '#39d353',
    '--chat-secondary': '#56d4dd',
    '--chat-primary-container': 'rgba(57, 211, 83, 0.16)',
    '--chat-on-primary-container': '#7ee787',
    '--chat-on-primary': '#0d1117',
    '--chat-user-bubble': 'rgba(57, 211, 83, 0.14)',
    '--chat-assistant-bubble': '#161b22',
    '--chat-user-text': '#e6fbe9',
    '--chat-assistant-text': '#c9d1d9',
    '--chat-user-radius': '6px',
    '--chat-assistant-radius': '6px',
    '--chat-font-family':
      '"JetBrains Mono", "Fira Code", "SFMono-Regular", "Consolas", monospace',
    '--chat-composer-bg': '#161b22',
    '--chat-composer-bg-transparent': 'rgba(22, 27, 34, 0.78)',
    '--chat-composer-bg-strong': 'rgba(22, 27, 34, 0.92)',
    '--chat-field-bg': '#0d1117',
    '--chat-panel-bg': '#161b22',
    '--chat-button-filled-bg': '#238636',
    '--chat-button-filled-fg': '#ffffff',
    '--chat-action-primary-bg': '#238636',
    '--chat-action-primary-fg': '#ffffff',
    '--chat-header-bg': 'rgba(13, 17, 23, 0.6)',
    '--chat-code-block-bg': 'rgba(1, 4, 9, 0.7)'
  },
  glass: {
    '--chat-root-background':
      'radial-gradient(circle at 18% 12%, rgba(139, 92, 246, 0.35), transparent 42%), radial-gradient(circle at 82% 78%, rgba(56, 189, 248, 0.3), transparent 45%), linear-gradient(135deg, #161433 0%, #251a45 50%, #10243f 100%)',
    '--chat-surface-color': 'rgba(255, 255, 255, 0.1)',
    '--chat-surface-variant-color': 'rgba(255, 255, 255, 0.14)',
    '--chat-surface-container-color': 'rgba(255, 255, 255, 0.08)',
    '--chat-surface-container-high-color': 'rgba(255, 255, 255, 0.16)',
    '--chat-surface-strong': 'rgba(255, 255, 255, 0.12)',
    '--chat-surface-soft': 'rgba(255, 255, 255, 0.1)',
    '--chat-surface-faint': 'rgba(255, 255, 255, 0.06)',
    '--chat-card-soft': 'rgba(255, 255, 255, 0.08)',
    '--chat-card-strong': 'rgba(255, 255, 255, 0.16)',
    '--chat-history-panel-bg': 'rgba(30, 27, 60, 0.55)',
    '--chat-history-item-bg': 'rgba(255, 255, 255, 0.08)',
    '--chat-history-item-active-bg': 'rgba(167, 139, 250, 0.32)',
    '--chat-border': 'rgba(255, 255, 255, 0.22)',
    '--chat-field-border': 'rgba(255, 255, 255, 0.28)',
    '--chat-field-border-strong': 'rgba(255, 255, 255, 0.45)',
    '--chat-text-main': '#f5f7ff',
    '--chat-text-soft': 'rgba(245, 247, 255, 0.72)',
    '--chat-text-muted': 'rgba(245, 247, 255, 0.6)',
    '--chat-primary': '#c4b5fd',
    '--chat-secondary': '#7dd3fc',
    '--chat-primary-container': 'rgba(167, 139, 250, 0.3)',
    '--chat-on-primary-container': '#f5f3ff',
    '--chat-on-primary': '#1e1b3a',
    '--chat-user-bubble': 'rgba(167, 139, 250, 0.28)',
    '--chat-assistant-bubble': 'rgba(255, 255, 255, 0.12)',
    '--chat-user-text': '#f8f7ff',
    '--chat-assistant-text': '#eef1ff',
    '--chat-composer-bg': 'rgba(255, 255, 255, 0.12)',
    '--chat-composer-bg-transparent': 'rgba(255, 255, 255, 0.1)',
    '--chat-composer-bg-strong': 'rgba(255, 255, 255, 0.18)',
    '--chat-field-bg': 'rgba(255, 255, 255, 0.1)',
    '--chat-panel-bg': 'rgba(30, 27, 60, 0.6)',
    '--chat-button-filled-bg': 'rgba(167, 139, 250, 0.85)',
    '--chat-button-filled-fg': '#1e1b3a',
    '--chat-action-primary-bg': 'rgba(167, 139, 250, 0.9)',
    '--chat-action-primary-fg': '#1e1b3a',
    '--chat-header-bg': 'transparent',
    '--chat-code-block-bg': 'rgba(0, 0, 0, 0.28)'
  }
};

function fallbackColor(value: string | null | undefined, fallback: string) {
  return value ?? fallback;
}

function resolveFontFamily(theme: WebThemeSnapshot) {
  if (theme.font.custom_font_asset_url) {
    return '"OperitThemeFont", "PingFang SC", "Microsoft YaHei", sans-serif';
  }
  if (theme.font.system_font_name?.trim()) {
    return `"${theme.font.system_font_name}", "PingFang SC", "Microsoft YaHei", sans-serif`;
  }
  return '"PingFang SC", "Microsoft YaHei", sans-serif';
}

function clampOpacity(value: number | undefined) {
  if (typeof value !== 'number' || Number.isNaN(value)) {
    return 0.2;
  }
  return Math.max(0, Math.min(1, value));
}

function toRgba(color: string | null | undefined, alpha?: number) {
  if (!color) {
    return null;
  }

  const normalized = color.trim();
  if (normalized.startsWith('#')) {
    const hex = normalized.slice(1);
    const size = hex.length === 3 ? 1 : 2;
    if (hex.length !== 3 && hex.length !== 6) {
      return normalized;
    }

    const read = (index: number) => {
      const chunk = hex.slice(index * size, index * size + size);
      const expanded = size === 1 ? chunk + chunk : chunk;
      return Number.parseInt(expanded, 16);
    };

    const red = read(0);
    const green = read(1);
    const blue = read(2);
    const resolvedAlpha = alpha ?? 1;
    return `rgba(${red}, ${green}, ${blue}, ${resolvedAlpha.toFixed(3)})`;
  }

  const rgbaMatch = normalized.match(
    /^rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)(?:\s*,\s*([\d.]+))?\s*\)$/i
  );
  if (!rgbaMatch) {
    return normalized;
  }

  const red = Number.parseFloat(rgbaMatch[1]);
  const green = Number.parseFloat(rgbaMatch[2]);
  const blue = Number.parseFloat(rgbaMatch[3]);
  const baseAlpha = rgbaMatch[4] ? Number.parseFloat(rgbaMatch[4]) : 1;
  const resolvedAlpha = alpha === undefined ? baseAlpha : alpha;
  return `rgba(${red}, ${green}, ${blue}, ${resolvedAlpha.toFixed(3)})`;
}

function parseRgb(color: string | null | undefined) {
  if (!color) {
    return null;
  }

  const normalized = color.trim();
  if (normalized.startsWith('#')) {
    const hex = normalized.slice(1);
    const size = hex.length === 3 ? 1 : 2;
    if (hex.length !== 3 && hex.length !== 6) {
      return null;
    }

    const read = (index: number) => {
      const chunk = hex.slice(index * size, index * size + size);
      const expanded = size === 1 ? chunk + chunk : chunk;
      return Number.parseInt(expanded, 16);
    };

    return {
      red: read(0),
      green: read(1),
      blue: read(2)
    };
  }

  const rgbaMatch = normalized.match(
    /^rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)(?:\s*,\s*([\d.]+))?\s*\)$/i
  );
  if (!rgbaMatch) {
    return null;
  }

  return {
    red: Number.parseFloat(rgbaMatch[1]),
    green: Number.parseFloat(rgbaMatch[2]),
    blue: Number.parseFloat(rgbaMatch[3])
  };
}

function resolveReadableTextColor(
  color: string | null | undefined,
  dark = '#08111d',
  light = '#f7fbff'
) {
  const rgb = parseRgb(color);
  if (!rgb) {
    return light;
  }

  const luminance = (0.299 * rgb.red + 0.587 * rgb.green + 0.114 * rgb.blue) / 255;
  return luminance > 0.58 ? dark : light;
}

export function buildChatThemeStyle(
  theme: WebThemeSnapshot | null,
  themeId: ChatThemeId = DEFAULT_CHAT_THEME_ID
): ThemeStyle {
  const preset = CHAT_THEME_PRESETS[themeId] ?? CHAT_THEME_PRESETS[DEFAULT_CHAT_THEME_ID];
  if (!theme) {
    return { ...preset };
  }

  const backgroundOpacity = clampOpacity(theme.background.opacity);
  const isLight = theme.theme_mode === 'light';
  const palette = theme.palette;
  const hasBackgroundAsset = Boolean(theme.background.asset_url);
  const primary = theme.primary_color || palette.primary_color || '#8ca9ff';
  const secondary = theme.secondary_color || palette.secondary_color || '#67d4c8';
  const backgroundColor = palette.background_color || (isLight ? '#faf8ff' : '#101520');
  const surfaceColor = palette.surface_color || (isLight ? '#ffffff' : '#1b202b');
  const surfaceVariantColor =
    palette.surface_variant_color || (isLight ? '#ece8f1' : '#45464f');
  const surfaceContainerColor =
    palette.surface_container_color || surfaceColor;
  const surfaceContainerHighColor =
    palette.surface_container_high_color || surfaceContainerColor;
  const outlineColor = palette.outline_color || (isLight ? '#7a7a85' : '#8f909a');
  const outlineVariantColor =
    palette.outline_variant_color || (isLight ? '#c8c4cf' : '#45464f');
  const primaryContainerColor =
    palette.primary_container_color || (toRgba(primary, isLight ? 0.24 : 0.28) ?? primary);
  const onPrimaryContainerColor =
    palette.on_primary_container_color || resolveReadableTextColor(primaryContainerColor);
  const onPrimaryColor = resolveReadableTextColor(primary);
  const onSecondaryColor = resolveReadableTextColor(secondary);
  const cardSoft = toRgba(surfaceVariantColor, 0.3) ?? surfaceVariantColor;
  const cardStrong = toRgba(surfaceVariantColor, 0.5) ?? surfaceVariantColor;
  const cardSubtle = toRgba(surfaceVariantColor, 0.2) ?? surfaceVariantColor;
  const historyPanelBackground =
    (hasBackgroundAsset ? toRgba(surfaceContainerColor, 0.96) : null) ?? surfaceContainerColor;
  const panelSurface =
    (hasBackgroundAsset ? toRgba(surfaceColor, 0.85) : null) ?? surfaceColor;
  const panelSurfaceStrong =
    (hasBackgroundAsset ? toRgba(surfaceColor, 0.92) : null) ?? surfaceColor;
  const transparentPanelSurface = toRgba(surfaceColor, 0.72) ?? surfaceColor;
  const transparentPanelSurfaceStrong = toRgba(surfaceColor, 0.9) ?? surfaceColor;
  const shadowColor = isLight ? 'rgba(44, 58, 90, 0.14)' : 'rgba(0, 0, 0, 0.24)';
  const assistantBubble = fallbackColor(
    theme.bubble.assistant_bubble_color,
    surfaceColor
  );
  const userBubble = fallbackColor(
    theme.chat_style === 'cursor' && !theme.bubble.cursor_user_follow_theme
      ? theme.bubble.cursor_user_color
      : theme.bubble.user_bubble_color,
    primaryContainerColor
  );
  const backgroundTint =
    hasBackgroundAsset && !theme.header.transparent
      ? toRgba(backgroundColor, isLight ? 0.04 : 0.08) ?? 'transparent'
      : 'transparent';

  const base: ThemeStyle = {
    '--chat-primary': primary,
    '--chat-secondary': secondary,
    '--chat-primary-container': primaryContainerColor,
    '--chat-on-primary-container': onPrimaryContainerColor,
    '--chat-on-primary': onPrimaryColor,
    '--chat-on-secondary': onSecondaryColor,
    '--chat-root-background': backgroundColor,
    '--chat-background-image': theme.background.asset_url ? `url(${theme.background.asset_url})` : 'none',
    '--chat-background-opacity': String(theme.background.asset_url ? backgroundOpacity : 0),
    '--chat-background-tint': backgroundTint,
    '--chat-surface-color': surfaceColor,
    '--chat-surface-variant-color': surfaceVariantColor,
    '--chat-surface-container-color': surfaceContainerColor,
    '--chat-surface-container-high-color': surfaceContainerHighColor,
    '--chat-surface-strong': surfaceColor,
    '--chat-surface-soft': toRgba(surfaceContainerHighColor, 0.94) ?? surfaceContainerHighColor,
    '--chat-surface-faint': cardSubtle,
    '--chat-card-soft': cardSoft,
    '--chat-card-strong': cardStrong,
    '--chat-history-panel-bg': historyPanelBackground,
    '--chat-history-item-bg': cardSoft,
    '--chat-history-item-active-bg': toRgba(primaryContainerColor, 0.3) ?? primaryContainerColor,
    '--chat-border': toRgba(outlineVariantColor, 0.68) ?? outlineVariantColor,
    '--chat-field-border': toRgba(outlineColor, 0.72) ?? outlineColor,
    '--chat-field-border-strong': outlineColor,
    '--chat-shadow': shadowColor,
    '--chat-text-main': palette.on_surface_color,
    '--chat-text-soft': palette.on_surface_variant_color,
    '--chat-text-muted': toRgba(palette.on_surface_color, 0.7) ?? palette.on_surface_color,
    '--chat-user-bubble': userBubble,
    '--chat-assistant-bubble': assistantBubble,
    '--chat-user-text': fallbackColor(
      theme.bubble.user_text_color,
      onPrimaryContainerColor
    ),
    '--chat-assistant-text': fallbackColor(
      theme.bubble.assistant_text_color,
      palette.on_surface_color
    ),
    '--chat-font-scale': String(theme.font.scale || 1),
    '--chat-font-family': resolveFontFamily(theme),
    '--chat-user-radius': theme.bubble.user_rounded ? '20px' : '8px',
    '--chat-assistant-radius': theme.bubble.assistant_rounded ? '20px' : '8px',
    '--chat-avatar-radius':
      theme.avatars.shape === 'square'
        ? `${theme.avatars.corner_radius ?? 10}px`
        : '999px',
    '--chat-user-padding-left': `${theme.bubble.user_padding_left || 12}px`,
    '--chat-user-padding-right': `${theme.bubble.user_padding_right || 12}px`,
    '--chat-assistant-padding-left': `${theme.bubble.assistant_padding_left || 12}px`,
    '--chat-assistant-padding-right': `${theme.bubble.assistant_padding_right || 12}px`,
    '--chat-header-bg': theme.header.transparent
      ? 'transparent'
      : toRgba(surfaceVariantColor, 0.2) ?? surfaceVariantColor,
    '--chat-header-icon-active-bg': toRgba(primary, 0.15) ?? primary,
    '--chat-header-icon-muted': toRgba(palette.on_surface_color, 0.7) ?? palette.on_surface_color,
    '--chat-header-track': toRgba(surfaceVariantColor, 0.3) ?? surfaceVariantColor,
    '--chat-button-filled-bg': primaryContainerColor,
    '--chat-button-filled-fg': onPrimaryContainerColor,
    '--chat-composer-bg': theme.input.transparent ? 'transparent' : panelSurface,
    '--chat-composer-bg-transparent': transparentPanelSurface,
    '--chat-composer-bg-strong': transparentPanelSurfaceStrong,
    '--chat-queue-bg': theme.input.transparent ? transparentPanelSurface : panelSurface,
    '--chat-queue-item-bg':
      theme.input.transparent ? transparentPanelSurfaceStrong : panelSurfaceStrong,
    '--chat-field-bg': surfaceColor,
    '--chat-scroll-button-bg': toRgba(surfaceContainerHighColor, 0.62) ?? surfaceContainerHighColor,
    '--chat-panel-bg': panelSurfaceStrong,
    '--chat-code-block-bg': toRgba(surfaceVariantColor, isLight ? 0.54 : 0.42) ?? surfaceVariantColor,
    '--chat-scrim': toRgba(palette.on_surface_color, isLight ? 0.14 : 0.38) ?? palette.on_surface_color,
    '--chat-dialog-scrim':
      toRgba(palette.on_surface_color, isLight ? 0.22 : 0.5) ?? palette.on_surface_color,
    '--chat-action-primary-bg': primary,
    '--chat-action-primary-fg': onPrimaryColor,
    '--chat-action-queued-bg': secondary,
    '--chat-action-queued-fg': onSecondaryColor,
    '--chat-action-danger-bg': isLight ? '#d35d6d' : '#e06e7f',
    '--chat-action-danger-fg': '#fef8f9'
  };

  return { ...base, ...preset };
}

export function buildChatFontFaceCss(theme: WebThemeSnapshot | null) {
  if (!theme?.font.custom_font_asset_url) {
    return '';
  }

  return `@font-face {
    font-family: "OperitThemeFont";
    src: url("${theme.font.custom_font_asset_url}");
  }`;
}
