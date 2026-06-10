import { StructuredIcon } from './part/XmlCanvasSummaryComponents';

export function AgentActivityToggleButton({
  active,
  toolCallCount,
  hasRunning,
  onClick
}: {
  active: boolean;
  toolCallCount: number;
  hasRunning: boolean;
  onClick: () => void;
}) {
  return (
    <button
      className={`aa-toggle-btn ${active ? 'is-active' : ''} ${hasRunning ? 'is-running' : ''}`}
      onClick={onClick}
      type="button"
      aria-label="Agent 工具调用面板"
      title="Agent 活动面板"
    >
      <StructuredIcon name="terminal" size={16} />
      {toolCallCount > 0 ? (
        <span className={`aa-toggle-badge ${hasRunning ? 'is-running' : ''}`}>
          {toolCallCount > 99 ? '99+' : toolCallCount}
        </span>
      ) : null}
    </button>
  );
}
