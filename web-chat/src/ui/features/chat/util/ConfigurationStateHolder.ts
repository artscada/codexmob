const TOKEN_KEY = 'operit-web-chat-token';
const REMEMBER_KEY = 'operit-web-chat-remember';

function encodeToken(token: string) {
  try {
    return btoa(unescape(encodeURIComponent(token)));
  } catch {
    return token;
  }
}

function decodeToken(stored: string) {
  try {
    return decodeURIComponent(escape(atob(stored)));
  } catch {
    return stored;
  }
}

export function readRememberConnection() {
  if (typeof window === 'undefined') {
    return false;
  }
  return window.localStorage.getItem(REMEMBER_KEY) === 'true';
}

export function readStoredToken() {
  if (typeof window === 'undefined') {
    return '';
  }

  const sessionValue = window.sessionStorage.getItem(TOKEN_KEY);
  if (sessionValue) {
    return decodeToken(sessionValue);
  }

  if (readRememberConnection()) {
    const persisted = window.localStorage.getItem(TOKEN_KEY);
    if (persisted) {
      return decodeToken(persisted);
    }
  }

  return '';
}

export function writeStoredToken(token: string, remember = false) {
  if (typeof window === 'undefined') {
    return;
  }

  const trimmed = token.trim();
  if (!trimmed) {
    clearStoredToken();
    return;
  }

  const encoded = encodeToken(trimmed);
  // Token 默认仅存活当前会话（sessionStorage），关闭标签页即清除。
  window.sessionStorage.setItem(TOKEN_KEY, encoded);

  if (remember) {
    window.localStorage.setItem(REMEMBER_KEY, 'true');
    window.localStorage.setItem(TOKEN_KEY, encoded);
  } else {
    window.localStorage.removeItem(REMEMBER_KEY);
    window.localStorage.removeItem(TOKEN_KEY);
  }
}

export function clearStoredToken() {
  if (typeof window === 'undefined') {
    return;
  }
  window.sessionStorage.removeItem(TOKEN_KEY);
  window.localStorage.removeItem(TOKEN_KEY);
  window.localStorage.removeItem(REMEMBER_KEY);
}
