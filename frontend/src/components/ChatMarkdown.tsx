import { useRef, useState } from 'react';
import type { FC, ReactNode } from 'react';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type { Components } from 'react-markdown';

/** Bloc de code avec bouton « copier » (lit le textContent réel du <pre>). */
const CodeBlock: FC<{ children: ReactNode }> = ({ children }) => {
  const ref = useRef<HTMLPreElement>(null);
  const [copied, setCopied] = useState(false);
  const copy = () => {
    const text = ref.current?.textContent ?? '';
    navigator.clipboard.writeText(text).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    }).catch(() => { /* ignore */ });
  };
  return (
    <div className="relative group/code my-3">
      <button
        type="button"
        onClick={copy}
        aria-label="Copy code"
        className="absolute right-2 top-2 opacity-0 group-hover/code:opacity-100 focus-visible:opacity-100 transition-opacity bg-surface-container-high border border-outline-variant/30 text-on-surface-variant hover:text-primary px-1.5 py-0.5 text-[10px] uppercase tracking-widest flex items-center gap-1"
      >
        <span aria-hidden="true" className="material-symbols-outlined text-[12px]">{copied ? 'check' : 'content_copy'}</span>
        {copied ? 'Copied' : 'Copy'}
      </button>
      <pre ref={ref} className="bg-surface-container-lowest border border-outline-variant/20 p-3 overflow-x-auto text-[12px] font-mono leading-relaxed">
        {children}
      </pre>
    </div>
  );
};

const components: Components = {
  pre: ({ children }) => <CodeBlock>{children}</CodeBlock>,
  code: ({ className, children, ...props }) => {
    const text = String(children ?? '');
    const isBlock = /language-/.test(className ?? '') || text.includes('\n');
    if (isBlock) {
      return <code className={className} {...props}>{children}</code>;
    }
    return <code className="font-mono text-[0.85em] bg-surface-container-high px-1 py-0.5 text-primary">{children}</code>;
  },
  a: ({ href, children }) => (
    <a href={href} target="_blank" rel="noopener noreferrer" className="text-primary underline underline-offset-2 hover:text-primary/80">{children}</a>
  ),
  p:  ({ children }) => <p className="mb-2 last:mb-0 leading-relaxed">{children}</p>,
  ul: ({ children }) => <ul className="list-disc list-inside space-y-1 my-2">{children}</ul>,
  ol: ({ children }) => <ol className="list-decimal list-inside space-y-1 my-2">{children}</ol>,
  li: ({ children }) => <li className="leading-relaxed">{children}</li>,
  h1: ({ children }) => <h1 className="text-base font-bold font-headline mt-3 mb-1">{children}</h1>,
  h2: ({ children }) => <h2 className="text-sm font-bold font-headline mt-3 mb-1">{children}</h2>,
  h3: ({ children }) => <h3 className="text-sm font-bold font-headline mt-2 mb-1">{children}</h3>,
  blockquote: ({ children }) => <blockquote className="border-l-2 border-primary/40 pl-3 italic text-on-surface-variant my-2">{children}</blockquote>,
  table: ({ children }) => <div className="overflow-x-auto my-2"><table className="text-xs border-collapse w-full">{children}</table></div>,
  th: ({ children }) => <th className="border border-outline-variant/30 px-2 py-1 text-left bg-surface-container-high font-bold">{children}</th>,
  td: ({ children }) => <td className="border border-outline-variant/20 px-2 py-1">{children}</td>,
  hr: () => <hr className="border-outline-variant/20 my-3" />,
};

/** Rendu Markdown (GFM) stylé selon le thème, pour les réponses de l'assistant. */
const ChatMarkdown: FC<{ content: string }> = ({ content }) => (
  <div className="text-sm font-body break-words">
    <Markdown remarkPlugins={[remarkGfm]} components={components}>{content}</Markdown>
  </div>
);

export default ChatMarkdown;
