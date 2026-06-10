import { useMemo, useState } from 'react';
import { StructuredIcon } from './part/XmlCanvasSummaryComponents';
import { resolveToolIconName } from './part/XmlCanvasSummaryComponents';
import type { WebChatMessage, WebMessageContentBlock } from '../util/chatTypes';

interface ToolCallRecord {
  id: string;
  toolName: string;
  params: string;
  result: string | null;
  isSuccess: boolean | null;
  isStreaming: boolean;
}

function unescapeXml(input: string) {
  return input
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/<!\[CDATA\[([\s\S]*?)]]>/g, '$1');
}

function buildParamPreview(params: string, max = 80): string {
  const firstParam = params.match(/<param[^>]*>([\s\S]*?)<\/param>/i);
  const raw = firstParam?.[1]?.trim() || params.trim();
  const cleaned = unescapeXml(raw).replace(/\s+/g, ' ').trim();
  return cleaned.length > max ? cleaned.slice(0, max) + '…' : cleaned;
}

function buildResultPreview(result: string, max = 100): string {
  const cleaned = result.replace(/\s+/g, ' ').trim();
  return cleaned.length > max ? cleaned.slice(0, max) + '…' : cleaned;
}

interface FlatEntry {
  block: WebMessageContentBlock;
  message: WebChatMessage;
}

function extractToolCalls(messages: WebChatMessage[]): ToolCallRecord[] {
  const flat: FlatEntry[] = [];

  function flattenBlocks(bs: WebMessageContentBlock[], message: WebChatMessage) {
    for (const b of bs) {
      flat.push({ block: b, message });
      if (b.children?.length) flattenBlocks(b.children, message);
    }
  }

  for (const message of messages) {
    const blocks = message.content_blocks;
    if (!blocks?.length) continue;
    flattenBlocks(blocks, message);
  }

  const records: ToolCallRecord[] = [];

  for (let i = 0; i < flat.length; i++) {
    const { block, message } = flat[i];
    if (block.kind !== 'xml' || block.tag_name !== 'tool') continue;
    if (!block.attrs?.name?.trim()) continue;

    const toolName = block.attrs.name.trim();
    const params = block.content ?? '';
    const isClosed = block.closed ?? true;

    let result: string | null = null;
    let isSuccess: boolean | null = null;

    // Scan forward across the whole flattened stream for the matching
    // tool_result, skipping intermediate text/thinking blocks. Stop at the
    // next tool call so a result is never paired with the wrong tool.
    for (let j = i + 1; j < flat.length; j++) {
      const candidate = flat[j].block;
      if (
        candidate.kind === 'xml' &&
        candidate.tag_name === 'tool' &&
        candidate.attrs?.name?.trim()
      ) {
        break;
      }
      if (candidate.kind === 'xml' && candidate.tag_name === 'tool_result') {
        const rawResult = candidate.content ?? '';
        const successAttr = candidate.attrs?.success ?? candidate.attrs?.status ?? '';
        isSuccess =
          successAttr.toLowerCase() !== 'false' && successAttr.toLowerCase() !== 'error';
        result = rawResult.trim();
        break;
      }
    }

    records.push({
      id: `${message.id}-${i}`,
      toolName,
      params,
      result,
      isSuccess,
      isStreaming: message.streaming === true && !isClosed && result === null
    });
  }

  return records;
}

function ToolCallItem({ record }: { record: ToolCallRecord }) {
  const [expanded, setExpanded] = useState(false);
  const icon = resolveToolIconName(record.toolName);
  const paramPreview = useMemo(() => buildParamPreview(record.params), [record.params]);
  const resultPreview = useMemo(
    () => (record.result ? buildResultPreview(record.result) : null),
    [record.result]
  );
  const status =
    record.isStreaming ? 'running'
    : record.result === null ? 'pending'
    : record.isSuccess === false ? 'error'
    : 'done';

  return (
    <div className={`aa-item aa-item-${status}`}>
      <button
        className="aa-item-header"
        onClick={() => setExpanded((v) => !v)}
        type="button"
      >
        <span className="aa-item-icon-wrap">
          <StructuredIcon name={icon} size={14} />
        </span>
        <span className="aa-item-name">{record.toolName}</span>
        <span className="aa-item-preview">{paramPreview}</span>
        <span className={`aa-item-badge aa-badge-${status}`}>
          {status === 'running' ? (
            <span className="aa-spinner" />
          ) : status === 'done' ? (
            <StructuredIcon name="check" size={12} />
          ) : status === 'error' ? (
            <StructuredIcon name="close" size={12} />
          ) : (
            <span className="aa-dot" />
          )}
        </span>
        <span className={`aa-item-chevron ${expanded ? 'is-open' : ''}`}>
          <StructuredIcon name="subdirectory-arrow-right" size={14} />
        </span>
      </button>

      {expanded ? (
        <div className="aa-item-body">
          {record.params.trim() ? (
            <div className="aa-detail-block">
              <span className="aa-detail-label">参数</span>
              <pre className="aa-detail-pre">{unescapeXml(record.params)}</pre>
            </div>
          ) : null}
          {record.result !== null ? (
            <div className={`aa-detail-block aa-result-block aa-result-${record.isSuccess === false ? 'error' : 'ok'}`}>
              <span className="aa-detail-label">{record.isSuccess === false ? '错误' : '结果'}</span>
              <pre className="aa-detail-pre">{record.result.slice(0, 600)}{record.result.length > 600 ? '\n…(已截断)' : ''}</pre>
            </div>
          ) : record.isStreaming ? (
            <div className="aa-detail-block">
              <span className="aa-detail-label aa-label-running">执行中…</span>
            </div>
          ) : null}
        </div>
      ) : null}

      {!expanded && resultPreview && status !== 'pending' ? (
        <div className={`aa-item-result-row aa-result-${record.isSuccess === false ? 'error' : 'ok'}`}>
          <StructuredIcon name="subdirectory-arrow-right" size={14} />
          <span className="aa-item-result-text">{resultPreview}</span>
        </div>
      ) : null}
    </div>
  );
}

export function AgentActivityPanel({
  messages,
  onClose
}: {
  messages: WebChatMessage[];
  onClose: () => void;
}) {
  const toolCalls = useMemo(() => extractToolCalls(messages), [messages]);
  const runningCount = toolCalls.filter((r) => r.isStreaming).length;
  const doneCount = toolCalls.filter((r) => !r.isStreaming && r.result !== null).length;
  const errorCount = toolCalls.filter((r) => r.isSuccess === false).length;

  return (
    <div className="aa-panel">
      <div className="aa-panel-header">
        <div className="aa-panel-title-row">
          <span className="aa-panel-title-icon">
            <StructuredIcon name="terminal" size={16} />
          </span>
          <strong className="aa-panel-title">Agent 活动</strong>
          {runningCount > 0 ? (
            <span className="aa-panel-running-badge">
              <span className="aa-spinner-small" />
              {runningCount} 执行中
            </span>
          ) : null}
        </div>
        <div className="aa-panel-stats">
          <span className="aa-stat aa-stat-done">{doneCount} 完成</span>
          {errorCount > 0 ? <span className="aa-stat aa-stat-error">{errorCount} 错误</span> : null}
          <span className="aa-stat aa-stat-total">{toolCalls.length} 总计</span>
        </div>
        <button className="aa-panel-close" onClick={onClose} type="button" aria-label="关闭面板">
          <StructuredIcon name="close" size={16} />
        </button>
      </div>

      <div className="aa-panel-body">
        {toolCalls.length === 0 ? (
          <div className="aa-empty">
            <StructuredIcon name="terminal" size={28} />
            <p>暂无工具调用</p>
            <span>AI 使用工具时，调用记录将在此显示</span>
          </div>
        ) : (
          <div className="aa-list">
            {toolCalls.map((record) => (
              <ToolCallItem key={record.id} record={record} />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
