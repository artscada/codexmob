import { useEffect, useMemo, useRef } from 'react';
import { ChatScreenContent } from '../components/ChatScreenContent';
import { ConfigurationScreen } from './ConfigurationScreen';
import { prefetchGlassSurface } from '../components/part/GlassSurface';
import { prefetchMarkdownRenderer } from '../components/part/MarkdownRenderer';
import { buildChatFontFaceCss, buildChatThemeStyle } from '../util/chatTheme';
import type { WebThemeSnapshot } from '../util/chatTypes';
import { useChatViewModel } from '../viewmodel/ChatViewModel';

function themeUsesGlass(theme: WebThemeSnapshot | null): boolean {
  if (!theme) {
    return false;
  }
  const input = theme.input;
  const bubble = theme.bubble;
  return Boolean(
    input?.liquid_glass ||
      input?.water_glass ||
      bubble?.cursor_user_liquid_glass ||
      bubble?.cursor_user_water_glass ||
      bubble?.user_liquid_glass ||
      bubble?.user_water_glass ||
      bubble?.assistant_liquid_glass ||
      bubble?.assistant_water_glass
  );
}

function schedulePrefetch(run: () => void): (() => void) | undefined {
  if (typeof window.requestIdleCallback === 'function') {
    const id = window.requestIdleCallback(run);
    return () => window.cancelIdleCallback?.(id);
  }
  const id = window.setTimeout(run, 200);
  return () => window.clearTimeout(id);
}

export function AIChatScreen() {
  const viewModel = useChatViewModel();
  const glassPrefetchedRef = useRef(false);
  const markdownPrefetchedRef = useRef(false);

  // Once the user has connected (overlay dismissed), warm the lazy glass chunk
  // during idle time — but only when the active theme actually uses glass — so
  // the first glass-themed message doesn't flash plain -> glass on a slow phone.
  // Deliberately gated on the overlay being dismissed so the chunk never re-enters
  // the connection-overlay critical path.
  useEffect(() => {
    if (typeof window === 'undefined') {
      return;
    }
    if (viewModel.showConnectionOverlay || glassPrefetchedRef.current) {
      return;
    }
    if (!themeUsesGlass(viewModel.theme)) {
      return;
    }
    glassPrefetchedRef.current = true;
    return schedulePrefetch(() => {
      void prefetchGlassSurface();
    });
  }, [viewModel.showConnectionOverlay, viewModel.theme]);

  // Also warm the lazy Markdown renderer once connected, so the first formatted
  // message renders immediately instead of waiting on the ~44 kB markdown chunk.
  // Unlike glass this is theme-independent (any message may contain markdown),
  // but it is likewise gated on the overlay being dismissed so the renderer
  // never re-enters the connection-overlay critical path.
  useEffect(() => {
    if (typeof window === 'undefined') {
      return;
    }
    if (viewModel.showConnectionOverlay || markdownPrefetchedRef.current) {
      return;
    }
    markdownPrefetchedRef.current = true;
    return schedulePrefetch(() => {
      void prefetchMarkdownRenderer();
    });
  }, [viewModel.showConnectionOverlay]);
  const fontFaceCss = buildChatFontFaceCss(viewModel.theme);
  const chatThemeStyle = useMemo(
    () => buildChatThemeStyle(viewModel.theme, viewModel.chatThemeId),
    [viewModel.theme, viewModel.chatThemeId]
  );
  const backdropBaseStyle = useMemo(
    () => ({
      background: String(chatThemeStyle['--chat-root-background'] ?? 'transparent')
    }),
    [chatThemeStyle]
  );
  const backdropImageStyle = useMemo(
    () => ({
      backgroundImage: String(chatThemeStyle['--chat-background-image'] ?? 'none'),
      opacity: String(chatThemeStyle['--chat-background-opacity'] ?? '0')
    }),
    [chatThemeStyle]
  );
  const backdropTintStyle = useMemo(
    () => ({
      background: String(chatThemeStyle['--chat-background-tint'] ?? 'transparent')
    }),
    [chatThemeStyle]
  );
  const suggestedUrl = useMemo(() => {
    if (typeof window === 'undefined') {
      return 'http://127.0.0.1:8094/';
    }

    const { protocol, hostname, port } = window.location;
    const resolvedPort = port || '8094';
    return `${protocol}//${hostname}:${resolvedPort}/`;
  }, []);

  return (
    <div
      className={[
        'ai-chat-screen',
        viewModel.activeChatStyle === 'bubble' ? 'chat-style-bubble' : 'chat-style-cursor',
        viewModel.theme?.theme_mode === 'light' ? 'theme-light' : 'theme-dark'
      ].join(' ')}
      data-theme={viewModel.chatThemeId}
      style={chatThemeStyle}
    >
      {fontFaceCss ? <style>{fontFaceCss}</style> : null}
      <div
        aria-hidden="true"
        className="chat-glass-backdrop-source"
        style={backdropBaseStyle}
      >
        <div className="chat-glass-backdrop-image" style={backdropImageStyle} />
        <div className="chat-glass-backdrop-tint" style={backdropTintStyle} />
      </div>

      <ChatScreenContent viewModel={viewModel} />

      {viewModel.showConnectionOverlay ? (
        <ConfigurationScreen
          error={viewModel.error}
          onCopyUrl={() => {
            if (typeof navigator !== 'undefined' && navigator.clipboard) {
              void navigator.clipboard.writeText(suggestedUrl);
            }
          }}
          onRememberConnectionChange={viewModel.setRememberConnection}
          onSubmit={viewModel.submitToken}
          onTokenDraftChange={viewModel.setTokenDraft}
          rememberConnection={viewModel.rememberConnection}
          suggestedUrl={suggestedUrl}
          tokenDraft={viewModel.tokenDraft}
        />
      ) : null}
    </div>
  );
}
