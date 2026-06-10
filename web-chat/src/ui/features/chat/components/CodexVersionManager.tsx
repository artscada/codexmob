import { useCallback, useEffect, useRef, useState } from 'react';
import { StructuredIcon } from './part/XmlCanvasSummaryComponents';
import {
  ApiError,
  CodexInstallStatus,
  getCodexInstallStatus,
  getCodexVersion,
  triggerCodexInstall
} from '../util/chatApi';
import type { CodexVersionInfo } from '../util/chatApi';

const CODEX_REPO = 'openai/codex';
const GITHUB_RELEASES_URL = `https://api.github.com/repos/${CODEX_REPO}/releases?per_page=10`;
const CODEX_RELEASES_PAGE = `https://github.com/${CODEX_REPO}/releases`;
const POLL_INTERVAL_MS = 2000;
const POLL_TIMEOUT_MS = 360_000;

interface GitHubAsset {
  name: string;
  size: number;
  browser_download_url: string;
}

interface GitHubRelease {
  id: number;
  tag_name: string;
  name: string | null;
  published_at: string;
  prerelease: boolean;
  draft: boolean;
  body: string | null;
  html_url: string;
  assets: GitHubAsset[];
}

type FetchState = 'idle' | 'loading' | 'done' | 'error';
type DeviceVersionState = 'idle' | 'loading' | 'done' | 'unsupported' | 'error';
type DownloadState = 'idle' | 'pending' | 'done' | 'error';

interface ArchSupport {
  arm64: boolean;
  x86_64: boolean;
  nodeOnly: boolean;
}

function detectArchSupport(assets: GitHubAsset[]): ArchSupport {
  const names = assets.map((a) => a.name.toLowerCase());
  return {
    arm64: names.some((n) => n.includes('arm64') || n.includes('aarch64')),
    x86_64: names.some((n) => n.includes('x86_64') || n.includes('amd64') || n.includes('x64')),
    nodeOnly: assets.length === 0
  };
}

function getBestArm64Asset(assets: GitHubAsset[]): GitHubAsset | null {
  return (
    assets.find((a) => {
      const n = a.name.toLowerCase();
      return (n.includes('arm64') || n.includes('aarch64')) && !n.endsWith('.sha256');
    }) ?? null
  );
}

function formatDate(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleDateString('zh-CN', { year: 'numeric', month: 'short', day: 'numeric' });
}

function trimChangelog(body: string | null, maxLen = 280): string {
  if (!body) return '';
  const cleaned = body
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\r\n/g, '\n')
    .trim();
  if (cleaned.length <= maxLen) return cleaned;
  const cut = cleaned.lastIndexOf('\n', maxLen);
  return (cut > 80 ? cleaned.slice(0, cut) : cleaned.slice(0, maxLen)) + '\n…';
}

function getStatusLabel(status: CodexInstallStatus['status']): string {
  switch (status) {
    case 'pending':     return '准备中…';
    case 'downloading': return '下载中…';
    case 'installing':  return '安装中…';
    case 'done':        return '安装完成';
    case 'error':       return '安装失败';
    case 'timeout':     return '安装超时';
    default:            return '处理中…';
  }
}

function ArchBadge({ supported, label }: { supported: boolean; label: string }) {
  return (
    <span className={`cvm-arch-badge ${supported ? 'is-ok' : 'is-miss'}`}>
      {supported ? (
        <StructuredIcon name="check" size={10} />
      ) : (
        <StructuredIcon name="close" size={10} />
      )}
      {label}
    </span>
  );
}

function ReleaseCard({
  release,
  isLatest,
  isInstalled,
  downloadState,
  installStatus,
  isConnected,
  onDownload
}: {
  release: GitHubRelease;
  isLatest: boolean;
  isInstalled: boolean;
  downloadState: DownloadState;
  installStatus: CodexInstallStatus | null;
  isConnected: boolean;
  onDownload: (release: GitHubRelease) => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const [logExpanded, setLogExpanded] = useState(false);
  const logScrollRef = useRef<HTMLPreElement | null>(null);
  const arch = detectArchSupport(release.assets);
  const changelog = trimChangelog(release.body);
  const displayName = release.name?.trim() || release.tag_name;
  const arm64Asset = getBestArm64Asset(release.assets);

  const isInstalling = downloadState === 'pending';
  const statusLabel = installStatus ? getStatusLabel(installStatus.status) : null;
  const logLines = installStatus?.log ? installStatus.log.split('\n').filter(Boolean) : [];
  const lastLogLine = logLines.length ? logLines[logLines.length - 1] : null;
  const hasError = installStatus?.status === 'error' || installStatus?.status === 'timeout';

  useEffect(() => {
    const el = logScrollRef.current;
    if (el) {
      el.scrollTop = el.scrollHeight;
    }
  }, [installStatus?.log, logExpanded]);

  const btnLabel = isConnected
    ? arm64Asset
      ? '推送到设备'
      : '在 GitHub 查看'
    : '在 GitHub 查看';

  return (
    <div
      className={`cvm-release-card ${isInstalled ? 'is-installed' : ''} ${isLatest && !isInstalled ? 'is-latest' : ''}`}
    >
      <div className="cvm-release-header">
        <div className="cvm-release-meta">
          <div className="cvm-release-title-row">
            <span className="cvm-release-tag">{displayName}</span>
            <div className="cvm-release-badges">
              {isInstalled && <span className="cvm-badge cvm-badge-installed">已安装</span>}
              {isLatest && !isInstalled && <span className="cvm-badge cvm-badge-latest">最新</span>}
              {release.prerelease && <span className="cvm-badge cvm-badge-pre">预览版</span>}
            </div>
          </div>
          <span className="cvm-release-date">{formatDate(release.published_at)}</span>
        </div>

        <div className="cvm-release-actions">
          {isInstalled ? (
            <span className="cvm-btn-installed">
              <StructuredIcon name="check" size={14} />
              当前版本
            </span>
          ) : downloadState === 'done' ? (
            <span className="cvm-btn-installed">
              <StructuredIcon name="check" size={14} />
              已触发
            </span>
          ) : hasError ? (
            <button
              className="cvm-btn-download"
              onClick={() => onDownload(release)}
              type="button"
            >
              <StructuredIcon name="close" size={14} />
              重试
            </button>
          ) : (
            <button
              className={`cvm-btn-download ${isInstalling ? 'is-loading' : ''}`}
              disabled={isInstalling}
              onClick={() => onDownload(release)}
              type="button"
            >
              {isInstalling ? (
                <>
                  <span className="cvm-spinner" />
                  {statusLabel ?? (isConnected && arm64Asset ? '推送中…' : '打开中…')}
                </>
              ) : (
                <>
                  <StructuredIcon name="arrow" size={14} />
                  {btnLabel}
                </>
              )}
            </button>
          )}
        </div>
      </div>

      {installStatus && (isInstalling || hasError) && (
        <div className={`cvm-install-progress ${hasError ? 'is-error' : ''}`}>
          <div className="cvm-install-progress-bar-wrap">
            <div
              className={`cvm-install-progress-bar ${installStatus.status === 'downloading' ? 'is-indeterminate' : ''} ${installStatus.status === 'installing' ? 'is-indeterminate' : ''}`}
            />
          </div>
          {lastLogLine && (
            <div className="cvm-install-log-line">
              <span className="cvm-install-log-icon">
                {hasError ? (
                  <StructuredIcon name="close" size={11} />
                ) : (
                  <span className="cvm-spinner-xs" />
                )}
              </span>
              <span className="cvm-install-log-text">{lastLogLine}</span>
            </div>
          )}
          {logLines.length > 0 && (
            <pre
              ref={logScrollRef}
              className={`cvm-install-log-scroll ${logExpanded ? 'is-expanded' : ''}`}
            >
              {installStatus.log}
            </pre>
          )}
          {logLines.length > 1 && (
            <button
              className="cvm-install-log-toggle"
              onClick={() => setLogExpanded((v) => !v)}
              type="button"
            >
              {logExpanded ? '收起日志' : '展开完整日志'}
            </button>
          )}
        </div>
      )}

      <div className="cvm-arch-row">
        {arch.nodeOnly ? (
          <span className="cvm-arch-note">源码包（需 Node.js 环境）</span>
        ) : (
          <>
            <ArchBadge label="ARM64" supported={arch.arm64} />
            <ArchBadge label="x86_64" supported={arch.x86_64} />
          </>
        )}
        {release.assets.length > 0 && (
          <span className="cvm-asset-count">{release.assets.length} 个文件</span>
        )}
      </div>

      {changelog ? (
        <div className="cvm-changelog-section">
          <button
            className={`cvm-changelog-toggle ${expanded ? 'is-open' : ''}`}
            onClick={() => setExpanded((v) => !v)}
            type="button"
          >
            <StructuredIcon name="subdirectory-arrow-right" size={13} />
            {expanded ? '收起更新日志' : '查看更新日志'}
          </button>
          {expanded && <pre className="cvm-changelog-body">{changelog}</pre>}
        </div>
      ) : null}
    </div>
  );
}

function InstalledVersionCard({
  version,
  arch,
  path,
  isUpToDate,
  latestVersion,
  isConnected,
  deviceState
}: {
  version: string | null;
  arch: string | null;
  path: string | null;
  isUpToDate: boolean;
  latestVersion: string | null;
  isConnected: boolean;
  deviceState: DeviceVersionState;
}) {
  let cardClass = 'is-not-installed';
  let iconName: 'check' | 'arrow' | 'code' | 'terminal' = 'code';
  let title = '未安装 Codex CLI';
  let subtitle = '从下方选择版本进行安装';

  if (!isConnected) {
    cardClass = 'is-not-installed';
    iconName = 'terminal';
    title = '未连接设备';
    subtitle = '连接安卓设备后可检测已安装版本';
  } else if (deviceState === 'loading') {
    cardClass = 'is-not-installed';
    iconName = 'code';
    title = '检测中…';
    subtitle = '正在查询设备已安装版本';
  } else if (deviceState === 'unsupported') {
    cardClass = 'is-not-installed';
    iconName = 'code';
    title = '版本检测不可用';
    subtitle = '设备端 API 尚未实现，手动查看下方版本列表';
  } else if (deviceState === 'error') {
    cardClass = 'is-not-installed';
    iconName = 'code';
    title = '检测失败';
    subtitle = '无法读取设备版本信息，请刷新重试';
  } else if (version) {
    if (isUpToDate) {
      cardClass = 'is-ok';
      iconName = 'check';
      title = `Codex CLI ${version}`;
      subtitle = '已是最新版本';
    } else {
      cardClass = 'is-outdated';
      iconName = 'arrow';
      title = `Codex CLI ${version}`;
      subtitle = `有新版本可用：${latestVersion ?? ''}`;
    }
  } else if (deviceState === 'done') {
    cardClass = 'is-not-installed';
    iconName = 'code';
    title = '未安装 Codex CLI';
    subtitle = '从下方选择版本推送到设备安装';
  }

  return (
    <div className={`cvm-installed-card ${cardClass}`}>
      <div className="cvm-installed-icon">
        {deviceState === 'loading' && isConnected ? (
          <span className="cvm-spinner-sm" />
        ) : (
          <StructuredIcon name={iconName} size={20} />
        )}
      </div>
      <div className="cvm-installed-info">
        <strong>{title}</strong>
        <span>{subtitle}</span>
        {arch && (
          <span className="cvm-installed-arch">
            架构：{arch}
            {path ? ` · ${path}` : ''}
          </span>
        )}
      </div>
      {version && !isUpToDate && latestVersion && <span className="cvm-update-dot" />}
    </div>
  );
}

export function CodexVersionManager({
  onClose,
  token,
  isConnected
}: {
  onClose: () => void;
  token: string | null;
  isConnected: boolean;
}) {
  const [fetchState, setFetchState] = useState<FetchState>('idle');
  const [releases, setReleases] = useState<GitHubRelease[]>([]);
  const [githubError, setGithubError] = useState<string | null>(null);

  const [deviceState, setDeviceState] = useState<DeviceVersionState>('idle');
  const [deviceInfo, setDeviceInfo] = useState<CodexVersionInfo | null>(null);

  const [downloadStates, setDownloadStates] = useState<Record<number, DownloadState>>({});
  const [installStatuses, setInstallStatuses] = useState<Record<number, CodexInstallStatus>>({});

  const pollTimers = useRef<Record<number, ReturnType<typeof setInterval>>>({});
  const pollStartTimes = useRef<Record<number, number>>({});

  const stableReleases = releases.filter((r) => !r.draft);
  const latestStable = stableReleases.find((r) => !r.prerelease) ?? stableReleases[0] ?? null;

  const installedVersion = deviceInfo?.version ?? null;
  const isUpToDate =
    installedVersion !== null &&
    latestStable !== null &&
    installedVersion === latestStable.tag_name;

  const hasUpdate = installedVersion !== null && !isUpToDate && latestStable !== null;

  const stopPolling = useCallback((releaseId: number) => {
    if (pollTimers.current[releaseId]) {
      clearInterval(pollTimers.current[releaseId]);
      delete pollTimers.current[releaseId];
      delete pollStartTimes.current[releaseId];
    }
  }, []);

  useEffect(() => {
    return () => {
      Object.keys(pollTimers.current).forEach((id) => {
        clearInterval(pollTimers.current[Number(id)]);
      });
    };
  }, []);

  const fetchReleases = useCallback(async () => {
    setFetchState('loading');
    setGithubError(null);
    try {
      const res = await fetch(GITHUB_RELEASES_URL, {
        headers: { Accept: 'application/vnd.github+json' }
      });
      if (!res.ok) {
        throw new Error(
          `GitHub API 返回 ${res.status}${res.status === 403 ? '（请求频率限制，请稍后重试）' : ''}`
        );
      }
      const data = (await res.json()) as GitHubRelease[];
      setReleases(data);
      setFetchState('done');
    } catch (err) {
      setGithubError(err instanceof Error ? err.message : '网络请求失败');
      setFetchState('error');
    }
  }, []);

  const fetchDeviceVersion = useCallback(async () => {
    if (!token || !isConnected) return;
    setDeviceState('loading');
    try {
      const info = await getCodexVersion(token);
      setDeviceInfo(info);
      setDeviceState('done');
    } catch (err) {
      if (err instanceof ApiError && (err.status === 404 || err.status === 501)) {
        setDeviceState('unsupported');
      } else {
        setDeviceState('error');
      }
    }
  }, [token, isConnected]);

  useEffect(() => {
    void fetchReleases();
  }, [fetchReleases]);

  useEffect(() => {
    if (isConnected && token) {
      void fetchDeviceVersion();
    } else {
      setDeviceState('idle');
      setDeviceInfo(null);
    }
  }, [isConnected, token, fetchDeviceVersion]);

  const handleRefresh = useCallback(() => {
    void fetchReleases();
    void fetchDeviceVersion();
  }, [fetchReleases, fetchDeviceVersion]);

  const startPolling = useCallback(
    (releaseId: number, taskId: string, tok: string) => {
      stopPolling(releaseId);
      pollStartTimes.current[releaseId] = Date.now();

      pollTimers.current[releaseId] = setInterval(() => {
        const elapsed = Date.now() - (pollStartTimes.current[releaseId] ?? 0);
        if (elapsed > POLL_TIMEOUT_MS) {
          stopPolling(releaseId);
          setDownloadStates((prev) => ({ ...prev, [releaseId]: 'error' }));
          return;
        }

        void getCodexInstallStatus(tok, taskId)
          .then((status) => {
            setInstallStatuses((prev) => ({ ...prev, [releaseId]: status }));

            if (status.status === 'done') {
              setDownloadStates((prev) => ({ ...prev, [releaseId]: 'done' }));
              stopPolling(releaseId);
              setTimeout(() => void fetchDeviceVersion(), 1500);
            } else if (status.status === 'error' || status.status === 'timeout') {
              setDownloadStates((prev) => ({ ...prev, [releaseId]: 'error' }));
              stopPolling(releaseId);
            }
          })
          .catch(() => {
          });
      }, POLL_INTERVAL_MS);
    },
    [stopPolling, fetchDeviceVersion]
  );

  const handleDownload = useCallback(
    async (release: GitHubRelease) => {
      setDownloadStates((prev) => ({ ...prev, [release.id]: 'pending' }));
      setInstallStatuses((prev) => {
        const next = { ...prev };
        delete next[release.id];
        return next;
      });

      const arm64Asset = getBestArm64Asset(release.assets);

      if (isConnected && token && arm64Asset) {
        try {
          const { task_id: taskId } = await triggerCodexInstall(token, {
            version: release.tag_name,
            download_url: arm64Asset.browser_download_url,
            arch: 'arm64'
          });
          startPolling(release.id, taskId, token);
        } catch {
          setDownloadStates((prev) => ({ ...prev, [release.id]: 'error' }));
        }
      } else {
        window.open(release.html_url, '_blank', 'noopener');
        setTimeout(() => {
          setDownloadStates((prev) => ({ ...prev, [release.id]: 'done' }));
        }, 1200);
      }
    },
    [isConnected, token, startPolling]
  );

  const isRefreshing = fetchState === 'loading' || deviceState === 'loading';

  return (
    <div className="cvm-panel">
      <div className="cvm-panel-header">
        <div className="cvm-panel-title-row">
          <span className="cvm-panel-icon">
            <StructuredIcon name="code" size={16} />
          </span>
          <strong className="cvm-panel-title">Codex 版本管理</strong>
        </div>
        <div className="cvm-panel-actions">
          {isConnected && <span className="cvm-conn-dot" title="已连接设备" />}
          <button
            className="cvm-icon-btn"
            disabled={isRefreshing}
            onClick={handleRefresh}
            title="刷新"
            type="button"
          >
            <svg
              className={isRefreshing ? 'cvm-spin' : ''}
              fill="none"
              height="15"
              stroke="currentColor"
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth="1.8"
              viewBox="0 0 24 24"
              width="15"
            >
              <path d="M3 12a9 9 0 0 1 9-9 9 9 0 0 1 6.4 2.6L21 9" />
              <path d="M21 3v6h-6" />
              <path d="M21 12a9 9 0 0 1-9 9 9 9 0 0 1-6.4-2.6L3 15" />
              <path d="M3 21v-6h6" />
            </svg>
          </button>
          <a
            className="cvm-icon-btn"
            href={CODEX_RELEASES_PAGE}
            rel="noopener noreferrer"
            target="_blank"
            title="在 GitHub 上查看"
          >
            <svg fill="currentColor" height="15" viewBox="0 0 24 24" width="15">
              <path d="M12 2A10 10 0 0 0 2 12c0 4.42 2.87 8.17 6.84 9.5.5.08.66-.23.66-.5v-1.69c-2.77.6-3.36-1.34-3.36-1.34-.46-1.16-1.11-1.47-1.11-1.47-.91-.62.07-.6.07-.6 1 .07 1.53 1.03 1.53 1.03.87 1.52 2.34 1.07 2.91.83.09-.65.35-1.09.63-1.34-2.22-.25-4.55-1.11-4.55-4.92 0-1.11.38-2 1.03-2.71-.1-.25-.45-1.29.1-2.64 0 0 .84-.27 2.75 1.02.79-.22 1.65-.33 2.5-.33.85 0 1.71.11 2.5.33 1.91-1.29 2.75-1.02 2.75-1.02.55 1.35.2 2.39.1 2.64.65.71 1.03 1.6 1.03 2.71 0 3.82-2.34 4.66-4.57 4.91.36.31.69.92.69 1.85V21c0 .27.16.59.67.5C19.14 20.16 22 16.42 22 12A10 10 0 0 0 12 2Z" />
            </svg>
          </a>
          <button
            className="cvm-icon-btn"
            onClick={onClose}
            title="关闭"
            type="button"
          >
            <StructuredIcon name="close" size={15} />
          </button>
        </div>
      </div>

      <div className="cvm-panel-body">
        <InstalledVersionCard
          arch={deviceInfo?.arch ?? null}
          deviceState={deviceState}
          isConnected={isConnected}
          isUpToDate={isUpToDate}
          latestVersion={latestStable?.tag_name ?? null}
          path={deviceInfo?.path ?? null}
          version={installedVersion}
        />

        <div className="cvm-section-label">
          <span>可用版本</span>
          <span className="cvm-section-sub">来自 openai/codex</span>
        </div>

        {fetchState === 'loading' && (
          <div className="cvm-loading">
            <span className="cvm-spinner-lg" />
            <span>从 GitHub 获取版本列表…</span>
          </div>
        )}

        {fetchState === 'error' && (
          <div className="cvm-error-card">
            <StructuredIcon name="close" size={16} />
            <div className="cvm-error-text">
              <strong>获取失败</strong>
              <span>{githubError}</span>
            </div>
            <button
              className="cvm-retry-btn"
              onClick={() => void fetchReleases()}
              type="button"
            >
              重试
            </button>
          </div>
        )}

        {fetchState === 'done' && stableReleases.length === 0 && (
          <div className="cvm-empty">
            <StructuredIcon name="search" size={24} />
            <span>未找到发布版本</span>
          </div>
        )}

        {fetchState === 'done' && stableReleases.length > 0 && (
          <div className="cvm-release-list">
            {stableReleases.map((release, index) => (
              <ReleaseCard
                downloadState={downloadStates[release.id] ?? 'idle'}
                installStatus={installStatuses[release.id] ?? null}
                isConnected={isConnected}
                isInstalled={installedVersion !== null && installedVersion === release.tag_name}
                isLatest={index === 0 && !release.prerelease}
                key={release.id}
                onDownload={(r) => void handleDownload(r)}
                release={release}
              />
            ))}
          </div>
        )}

        <div className="cvm-footer-note">
          <StructuredIcon name="arrow" size={12} />
          <span>
            {isConnected
              ? '已连接设备：点击「推送到设备」可触发安卓端自动下载安装。ARM64 预编译包需等待官方支持，当前可通过 Node.js 源码运行。'
              : '连接安卓设备后可直接推送安装。ARM64 预编译包需等待官方支持，当前可通过 Node.js 源码运行。'}
          </span>
        </div>
      </div>
    </div>
  );
}
