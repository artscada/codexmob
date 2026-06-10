---
name: Web-chat interface themes
description: How the user-selectable web-chat themes (modern/developer/glass) coexist with the server-driven theme.
---

The web-chat frontend has TWO theming layers:
1. Server-driven theme (`WebThemeSnapshot`) → `buildChatThemeStyle(theme, themeId)` builds a `base` object of `--chat-*` CSS vars applied INLINE on `.ai-chat-screen`.
2. A locally-selected interface theme (`ChatThemeId` = modern | developer | glass) persisted in localStorage (key `web_chat_interface_theme`, see `ThemeStateHolder.ts`).

**Rule:** `buildChatThemeStyle` returns `{ ...base, ...preset }`. The preset (in `CHAT_THEME_PRESETS`) intentionally overrides the server palette colors, but deliberately OMITS `--chat-background-image`, `--chat-background-opacity`, `--chat-background-tint`, and `--chat-font-scale`, so those server-driven values survive theme selection.

**Why:** Inline CSS vars on the root element win over any stylesheet rule, so color identity must be done in TS (the preset merge), not in CSS. `chat-themes.css` (imported from `chat-screen.css`) is reserved for STRUCTURAL differences only (flat bubbles, mono left-border, glass backdrop-filter) and the settings picker cards.

**How to apply:** To add/adjust a theme, edit `CHAT_THEME_OPTIONS` + `CHAT_THEME_PRESETS` in `chatTheme.ts` and the `[data-theme="..."]` structural rules in `chat-themes.css`. Do NOT move palette colors into CSS — they will be beaten by the inline root vars. Keep any var you want server-controlled OUT of the preset.
