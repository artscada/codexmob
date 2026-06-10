import DOMPurify from 'dompurify';

export default function HtmlBlockImpl({ content }: { content: string }) {
  const safeHtml = DOMPurify.sanitize(content, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['script', 'style', 'iframe', 'object', 'embed', 'form'],
    FORBID_ATTR: ['onerror', 'onload', 'onclick', 'onmouseover', 'onfocus', 'onanimationstart'],
  });
  return <div className="structured-html-card" dangerouslySetInnerHTML={{ __html: safeHtml }} />;
}
