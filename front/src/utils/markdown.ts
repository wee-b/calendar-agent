import DOMPurify from 'dompurify';
import { marked } from 'marked';

marked.setOptions({
  breaks: true,
  gfm: true,
});

export const renderMarkdown = (content: string) => {
  if (!content) return '';
  return DOMPurify.sanitize(marked.parse(content, { async: false }) as string);
};
