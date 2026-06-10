export function CodexVersionToggleButton({
  active,
  hasUpdate,
  onClick
}: {
  active: boolean;
  hasUpdate: boolean;
  onClick: () => void;
}) {
  return (
    <button
      aria-label="Codex 版本管理"
      className={`cvm-toggle-btn ${active ? 'is-active' : ''}`}
      onClick={onClick}
      title="Codex 版本管理"
      type="button"
    >
      <svg
        fill="none"
        height="16"
        stroke="currentColor"
        strokeLinecap="round"
        strokeLinejoin="round"
        strokeWidth="1.8"
        viewBox="0 0 24 24"
        width="16"
      >
        <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z" />
        <path d="M3.27 6.96 12 12.01l8.73-5.05" />
        <path d="M12 22.08V12" />
      </svg>
      {hasUpdate && <span className="cvm-update-badge" />}
    </button>
  );
}
