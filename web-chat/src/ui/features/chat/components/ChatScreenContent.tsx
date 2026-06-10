import { Suspense, lazy, useEffect, useMemo, useRef, useState } from 'react';
import { ChatArea } from './ChatArea';
import { CharacterSelectorPanel } from './CharacterSelectorPanel';
import { ChatHistorySelector } from './ChatHistorySelector';
import { ChatScreenHeader } from './ChatScreenHeader';
import { AgentChatInputSection } from './style/input/agent/AgentChatInputSection';
import { ClassicChatInputSection } from './style/input/classic/ClassicChatInputSection';
import { ClassicChatSettingsBar } from './style/input/classic/ClassicChatSettingsBar';
import type { ChatViewModel } from '../viewmodel/ChatViewModel';
import type { WebChatMessage, WebMessageContentBlock } from '../util/chatTypes';
import {
  getIsFloatingMode,
  toggleFloatingWindow
} from '../viewmodel/FloatingWindowDelegate';

const AgentActivityPanel = lazy(() =>
  import('./AgentActivityPanel').then((m) => ({ default: m.AgentActivityPanel }))
);
const CodexVersionManager = lazy(() =>
  import('./CodexVersionManager').then((m) => ({ default: m.CodexVersionManager }))
);

function countToolCalls(messages: WebChatMessage[]): { total: number; running: number } {
  let total = 0;
  let running = 0;

  for (const msg of messages) {
    const blocks = msg.content_blocks;
    if (!blocks?.length) continue;

    function scan(bs: WebMessageContentBlock[]) {
      for (const b of bs) {
        if (b.kind === 'xml' && b.tag_name === 'tool' && b.attrs?.name?.trim()) {
          total++;
          if (msg.streaming && b.closed === false) running++;
        }
        if (b.children?.length) scan(b.children);
      }
    }
    scan(blocks);
  }

  return { total, running };
}

export function ChatScreenContent({
  viewModel
}: {
  viewModel: ChatViewModel;
}) {
  const headerRef = useRef<HTMLDivElement | null>(null);
  const composerHostRef = useRef<HTMLDivElement | null>(null);
  const measureRafRef = useRef<number | null>(null);
  const [sizes, setSizes] = useState({ header: 0, composer: 0, bottomBar: 0 });
  const headerHeight = sizes.header;
  const composerHeight = sizes.composer;
  const bottomBarHeight = sizes.bottomBar;
  const [isFloatingMode, setIsFloatingMode] = useState(getIsFloatingMode());
  const [classicSettingsOpen, setClassicSettingsOpen] = useState(false);
  const [activityPanelOpen, setActivityPanelOpen] = useState(false);
  const [versionPanelOpen, setVersionPanelOpen] = useState(false);
  const overlayMode = Boolean(viewModel.theme?.header.overlay);
  const showInputProcessingStatus =
    viewModel.theme?.show_input_processing_status ?? viewModel.boot?.show_input_processing_status ?? true;
  const classicSettingsBottomOffset = bottomBarHeight > 36 ? bottomBarHeight - 6 : 18;

  const activityStats = useMemo(() => countToolCalls(viewModel.messages), [viewModel.messages]);

  useEffect(() => {
    const headerEl = headerRef.current;
    const composerEl = composerHostRef.current;
    const bottomBarEl =
      composerEl?.querySelector<HTMLElement>(
        '.classic-chat-input-section, .agent-chat-input-section'
      ) ?? null;

    const measure = () => {
      measureRafRef.current = null;
      setSizes((prev) => {
        const next = {
          header: headerEl?.getBoundingClientRect().height ?? prev.header,
          composer: composerEl?.getBoundingClientRect().height ?? prev.composer,
          bottomBar: bottomBarEl?.getBoundingClientRect().height ?? prev.bottomBar
        };
        // Skip sub-pixel changes that would otherwise trigger layout-thrash
        // re-renders and visible jitter during streaming.
        if (
          Math.abs(next.header - prev.header) < 0.5 &&
          Math.abs(next.composer - prev.composer) < 0.5 &&
          Math.abs(next.bottomBar - prev.bottomBar) < 0.5
        ) {
          return prev;
        }
        return next;
      });
    };

    // Coalesce all observer callbacks within a frame into one measurement so
    // header/composer/bottom-bar updates land in a single render.
    const scheduleMeasure = () => {
      if (measureRafRef.current != null) return;
      measureRafRef.current = requestAnimationFrame(measure);
    };

    if (typeof ResizeObserver === 'undefined') {
      measure();
      return;
    }

    const observer = new ResizeObserver(scheduleMeasure);
    if (headerEl) observer.observe(headerEl);
    if (composerEl) observer.observe(composerEl);
    if (bottomBarEl) observer.observe(bottomBarEl);
    measure();

    return () => {
      observer.disconnect();
      if (measureRafRef.current != null) {
        cancelAnimationFrame(measureRafRef.current);
        measureRafRef.current = null;
      }
    };
  }, [
    overlayMode,
    viewModel.activeCharacterName,
    viewModel.historyOpen,
    viewModel.selectedChatId,
    viewModel.activeInputStyle,
    viewModel.attachmentPanelOpen,
    viewModel.error,
    viewModel.inputProcessingStage,
    viewModel.isPendingQueueExpanded,
    viewModel.messageInput,
    viewModel.pendingQueueMessages.length,
    viewModel.pendingUploads.length
  ]);

  useEffect(() => {
    if (viewModel.activeInputStyle !== 'classic') {
      setClassicSettingsOpen(false);
    }
  }, [viewModel.activeInputStyle]);

  useEffect(() => {
    if (viewModel.attachmentPanelOpen) {
      setClassicSettingsOpen(false);
    }
  }, [viewModel.attachmentPanelOpen]);

  useEffect(() => {
    function handleFullscreenChange() {
      setIsFloatingMode(Boolean(document.fullscreenElement));
    }

    document.addEventListener('fullscreenchange', handleFullscreenChange);
    return () => document.removeEventListener('fullscreenchange', handleFullscreenChange);
  }, []);

  const composer = viewModel.activeInputStyle === 'agent' ? (
    <AgentChatInputSection
      attachmentPanelOpen={viewModel.attachmentPanelOpen}
      contextPercent={viewModel.contextStats.percent}
      contextCurrentValue={viewModel.contextStats.currentValue}
      contextMaxValue={viewModel.contextStats.maxValue}
      inputSettings={viewModel.inputSettings}
      inputProcessingStage={viewModel.inputProcessingStage}
      isLoading={viewModel.isStreaming || viewModel.isConnecting}
      isPendingQueueExpanded={viewModel.isPendingQueueExpanded}
      memorySelector={viewModel.memorySelector}
      messageInput={viewModel.messageInput}
      modelSelector={viewModel.modelSelector}
      modelSelectorLoading={viewModel.modelSelectorLoading}
      onAttachmentPanelChange={viewModel.setAttachmentPanelOpen}
      onCancelMessage={viewModel.cancelCurrentMessage}
      onDeletePendingQueueMessage={viewModel.deletePendingQueueMessage}
      onEditPendingQueueMessage={viewModel.editPendingQueueMessage}
      onMessageInputChange={viewModel.setMessageInput}
      onPendingQueueExpandedChange={viewModel.setPendingQueueExpanded}
      onQueueMessage={viewModel.queueDraftMessage}
      onRemovePendingUpload={viewModel.removePendingUpload}
      onRunManualMemoryUpdate={viewModel.runManualMemoryUpdate}
      onSelectModelConfig={viewModel.selectModelConfig}
      onSelectMemoryProfile={viewModel.selectMemoryProfile}
      onSendMessage={viewModel.sendMessage}
      onSendPendingQueueMessage={viewModel.sendPendingQueueMessage}
      onUpdateInputSettings={viewModel.updateInputSettings}
      onUploadFiles={viewModel.uploadFiles}
      pendingQueueMessages={viewModel.pendingQueueMessages}
      pendingUploads={viewModel.pendingUploads}
      showInputProcessingStatus={showInputProcessingStatus}
      theme={viewModel.theme}
    />
  ) : (
    <ClassicChatInputSection
      attachmentPanelOpen={viewModel.attachmentPanelOpen}
      inputProcessingStage={viewModel.inputProcessingStage}
      isLoading={viewModel.isStreaming || viewModel.isConnecting}
      isPendingQueueExpanded={viewModel.isPendingQueueExpanded}
      messageInput={viewModel.messageInput}
      onAttachmentPanelChange={(value) => {
        setClassicSettingsOpen(false);
        viewModel.setAttachmentPanelOpen(value);
      }}
      onCancelMessage={viewModel.cancelCurrentMessage}
      onDeletePendingQueueMessage={viewModel.deletePendingQueueMessage}
      onEditPendingQueueMessage={viewModel.editPendingQueueMessage}
      onMessageInputChange={viewModel.setMessageInput}
      onPendingQueueExpandedChange={viewModel.setPendingQueueExpanded}
      onQueueMessage={viewModel.queueDraftMessage}
      onRemovePendingUpload={viewModel.removePendingUpload}
      onSendMessage={viewModel.sendMessage}
      onSendPendingQueueMessage={viewModel.sendPendingQueueMessage}
      onUploadFiles={viewModel.uploadFiles}
      pendingQueueMessages={viewModel.pendingQueueMessages}
      pendingUploads={viewModel.pendingUploads}
      showInputProcessingStatus={showInputProcessingStatus}
      theme={viewModel.theme}
    />
  );

  return (
    <div className="chat-screen-content">
      <CharacterSelectorPanel
        activePrompt={viewModel.characterSelector?.active_prompt ?? null}
        loading={viewModel.characterSelectorLoading}
        onClose={() => viewModel.setCharacterSelectorOpen(false)}
        onSelectTarget={(target) => {
          void viewModel.switchActivePrompt(target);
        }}
        open={viewModel.characterSelectorOpen}
        selector={viewModel.characterSelector}
      />

      <ChatHistorySelector
        busy={viewModel.isBusy || viewModel.historyLoading}
        chats={viewModel.chats}
        characterSelector={viewModel.characterSelector}
        historyDisplayMode={viewModel.historyDisplayMode}
        autoSwitchCharacterCard={viewModel.autoSwitchCharacterCard}
        autoSwitchChatOnCharacterSelect={viewModel.autoSwitchChatOnCharacterSelect}
        onClose={() => viewModel.setHistoryOpen(false)}
        onCreateChat={viewModel.createConversation}
        onDeleteChat={viewModel.deleteConversation}
        onDeleteGroup={viewModel.deleteGroup}
        onRenameChat={viewModel.renameConversation}
        onRenameGroup={viewModel.renameGroup}
        onReorderChats={viewModel.reorderConversations}
        onSearchChange={viewModel.setSearch}
        onSelectChat={viewModel.selectChat}
        onAutoSwitchCharacterCardChange={viewModel.setAutoSwitchCharacterCard}
        onAutoSwitchChatOnCharacterSelectChange={viewModel.setAutoSwitchChatOnCharacterSelect}
        onHistoryDisplayModeChange={viewModel.setHistoryDisplayMode}
        onUpdateChat={viewModel.updateConversation}
        open={viewModel.historyOpen}
        search={viewModel.search}
        selectedChatId={viewModel.selectedChatId}
        streaming={viewModel.isStreaming}
      />

      <div className={`chat-screen-with-panel ${activityPanelOpen ? 'has-activity-panel' : ''} ${versionPanelOpen ? 'has-version-panel' : ''}`}>
        <div className={`chat-screen-frame ${overlayMode ? 'is-overlay-mode' : ''}`}>
          <div className={`chat-screen-main ${overlayMode ? 'is-overlay-mode' : ''}`}>
            <div className={`chat-screen-header-layer ${overlayMode ? 'is-overlay-mode' : ''}`} ref={headerRef}>
              <ChatScreenHeader
                activeCharacterAvatarUrl={viewModel.activeCharacterAvatarUrl}
                activeCharacterName={viewModel.activeCharacterName}
                contextCurrentValue={viewModel.contextStats.currentValue}
                contextLabel={`上下文 ${viewModel.contextStats.percent}%`}
                contextMaxValue={viewModel.contextStats.maxValue}
                contextPercent={viewModel.contextStats.percent}
                isConnecting={viewModel.isConnecting}
                isFloatingMode={isFloatingMode}
                isStreaming={viewModel.isStreaming}
                onCharacterSwitcherClick={() =>
                  viewModel.setCharacterSelectorOpen(!viewModel.characterSelectorOpen)
                }
                onLaunchFloatingWindow={() => {
                  void toggleFloatingWindow();
                }}
                onToggleChatHistorySelector={() => viewModel.setHistoryOpen(!viewModel.historyOpen)}
                runningTaskCount={viewModel.activeStreamingCount}
                showChatHistorySelector={viewModel.historyOpen}
                activityPanelOpen={activityPanelOpen}
                activityToolCount={activityStats.total}
                activityHasRunning={activityStats.running > 0}
                onToggleActivityPanel={() => {
                  setActivityPanelOpen((v) => !v);
                  setVersionPanelOpen(false);
                }}
                versionPanelOpen={versionPanelOpen}
                onToggleVersionPanel={() => {
                  setVersionPanelOpen((v) => !v);
                  setActivityPanelOpen(false);
                }}
              />
            </div>
            <ChatArea
              autoScrollToBottom={viewModel.autoScrollToBottom}
              bottomPadding={composerHeight}
              chatHistory={viewModel.messages}
              chatStyle={viewModel.activeChatStyle}
              currentChatId={viewModel.selectedChatId}
              hasMoreHistoryBefore={viewModel.hasMoreHistoryBefore}
              hasMoreHistoryAfter={viewModel.hasMoreHistoryAfter}
              isLoading={viewModel.isStreaming}
              isConversationLoading={viewModel.isConnecting}
              isLoadingHistoryBefore={viewModel.isLoadingHistoryBefore}
              isLoadingHistoryAfter={viewModel.isLoadingHistoryAfter}
              onJumpToLatest={viewModel.showLatestMessages}
              onLoadNewer={viewModel.loadNewerMessages}
              onLoadOlder={viewModel.loadOlderMessages}
              onLoadMessageLocatorEntries={viewModel.loadMessageLocatorEntries}
              onAutoScrollToBottomChange={viewModel.setAutoScrollToBottom}
              onRevealMessageForLocator={viewModel.revealMessageForCurrentChat}
              onToggleFavoriteMessage={viewModel.toggleMessageFavorite}
              theme={viewModel.theme}
              topPadding={overlayMode ? headerHeight + 4 : 0}
            />
          </div>

          {viewModel.activeInputStyle === 'classic' ? (
            <div
              className="classic-chat-settings-layer"
              style={{ bottom: `${classicSettingsBottomOffset}px` }}
            >
              <ClassicChatSettingsBar
                chatThemeId={viewModel.chatThemeId}
                contextPercent={viewModel.contextStats.percent}
                contextCurrentValue={viewModel.contextStats.currentValue}
                contextMaxValue={viewModel.contextStats.maxValue}
                inputSettings={viewModel.inputSettings}
                onSelectChatTheme={viewModel.setChatThemeId}
                memorySelector={viewModel.memorySelector}
                modelSelector={viewModel.modelSelector}
                modelSelectorLoading={viewModel.modelSelectorLoading}
                onRunManualConversationSummary={viewModel.runManualConversationSummary}
                onRunManualMemoryUpdate={viewModel.runManualMemoryUpdate}
                onSelectModelConfig={viewModel.selectModelConfig}
                onSelectMemoryProfile={viewModel.selectMemoryProfile}
                onToggleSettings={() => {
                  viewModel.setAttachmentPanelOpen(false);
                  setClassicSettingsOpen(!classicSettingsOpen);
                }}
                onUpdateInputSettings={viewModel.updateInputSettings}
                settingsOpen={classicSettingsOpen}
              />
            </div>
          ) : null}

          <div
            className={[
              'chat-composer-host',
              viewModel.theme?.input.floating ? 'is-floating' : '',
              viewModel.theme?.input.transparent ? 'is-transparent' : '',
              viewModel.theme?.input.liquid_glass ? 'is-liquid-glass' : '',
              viewModel.theme?.input.water_glass ? 'is-water-glass' : '',
              viewModel.activeInputStyle === 'agent' ? 'is-agent' : 'is-classic'
            ]
              .filter(Boolean)
              .join(' ')}
            ref={composerHostRef}
          >
            {composer}
            {viewModel.error ? <div className="chat-inline-error">{viewModel.error}</div> : null}
          </div>
        </div>

        {activityPanelOpen ? (
          <Suspense fallback={null}>
            <AgentActivityPanel
              messages={viewModel.messages}
              onClose={() => setActivityPanelOpen(false)}
            />
          </Suspense>
        ) : null}

        {versionPanelOpen ? (
          <Suspense fallback={null}>
            <CodexVersionManager
              isConnected={!viewModel.showConnectionOverlay}
              onClose={() => setVersionPanelOpen(false)}
              token={viewModel.token || null}
            />
          </Suspense>
        ) : null}
      </div>
    </div>
  );
}
