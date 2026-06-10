import type { ChatThemeId } from './chatTypes';

const CHAT_THEME_KEY = 'web_chat_interface_theme';
const VALID_THEME_IDS: ChatThemeId[] = ['modern', 'developer', 'glass'];

export const DEFAULT_CHAT_THEME_ID: ChatThemeId = 'modern';

export function readStoredChatThemeId(): ChatThemeId {
  if (typeof window === 'undefined') {
    return DEFAULT_CHAT_THEME_ID;
  }
  try {
    const stored = window.localStorage.getItem(CHAT_THEME_KEY);
    return VALID_THEME_IDS.includes(stored as ChatThemeId)
      ? (stored as ChatThemeId)
      : DEFAULT_CHAT_THEME_ID;
  } catch {
    return DEFAULT_CHAT_THEME_ID;
  }
}

export function writeStoredChatThemeId(themeId: ChatThemeId) {
  if (typeof window === 'undefined') {
    return;
  }
  try {
    window.localStorage.setItem(CHAT_THEME_KEY, themeId);
  } catch {
    /* storage unavailable (private mode / quota) — selection stays in memory */
  }
}
