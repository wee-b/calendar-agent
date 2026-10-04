import DOMPurify from 'dompurify';
import { marked } from 'marked';

marked.setOptions({
  breaks: true,
  gfm: true,
});

export const resolveAiAssetUrl = (path: string) => {
  const aiBaseUrl = (import.meta.env.VITE_AI_API_BASE_URL || '').replace(/\/$/, '');
  return aiBaseUrl && path.startsWith('/images/') ? aiBaseUrl + path : path;
};

export const renderMarkdown = (content: string) => {
  if (!content) return '';
  const html = DOMPurify.sanitize(marked.parse(content, { async: false }) as string);
  const aiBaseUrl = import.meta.env.VITE_AI_API_BASE_URL;
  if (!aiBaseUrl || !html.includes('/images/')) return html;

  const template = document.createElement('template');
  template.innerHTML = html;
  template.content.querySelectorAll<HTMLImageElement>('img[src^="/images/"]').forEach(image => {
    image.src = resolveAiAssetUrl(image.getAttribute('src') || '');
  });
  template.content.querySelectorAll<HTMLAnchorElement>('a[href^="/images/"]').forEach(link => {
    link.href = resolveAiAssetUrl(link.getAttribute('href') || '');
  });
  return template.innerHTML;
};
