import { useState } from 'react';
import type { FC } from 'react';
import { useStatus } from '../hooks/useStatus';

/**
 * Bandeau global (toutes pages) affiché quand un service critique est
 * indisponible — cas typique du premier lancement (modèle non téléchargé,
 * conteneur arrêté). Explique la conséquence en langage clair et donne la
 * commande de remédiation, au lieu de laisser les requêtes échouer en toasts.
 */

interface ServiceWithDetails {
  name: string;
  available: boolean;
  details?: { activeModel?: string; activeModelLoaded?: boolean };
}

interface HealthIssue {
  key: string;
  title: string;
  description: string;
  command?: string;
}

const DISMISS_KEY = 'spectra-health-banner-dismissed';

function computeIssues(status: { services?: ServiceWithDetails[] } | undefined, error: unknown): HealthIssue[] {
  if (error || !status) {
    return [{
      key: 'api',
      title: 'Spectra API unreachable',
      description: 'The backend is not responding — no feature will work until it is up.',
      command: './start.sh   (Windows: start.bat)',
    }];
  }

  const services = status.services ?? [];
  const chat = services.find((s) => s.name === 'llama-cpp');
  const embed = services.find((s) => s.name === 'llama-cpp-embed');
  const chroma = services.find((s) => s.name === 'chromadb');

  const issues: HealthIssue[] = [];
  if (chat && !chat.available) {
    issues.push({
      key: 'chat',
      title: 'Chat LLM server offline',
      description: 'Answers, dataset generation and evaluation need it. Download a model, then restart the stack.',
      command: './setup.sh --download-chat   (Windows: setup.bat)',
    });
  } else if (chat?.details?.activeModelLoaded === false) {
    issues.push({
      key: 'chat-model',
      title: 'LLM server is up but no model is loaded',
      description: 'Place a .gguf file in data/models/ or download the default model.',
      command: './setup.sh --download-chat   (Windows: setup.bat)',
    });
  }
  if (embed && !embed.available) {
    issues.push({
      key: 'embed',
      title: 'Embedding server offline',
      description: 'Document ingestion and semantic search need it.',
      command: './setup.sh --download-embed   (Windows: setup.bat)',
    });
  }
  if (chroma && !chroma.available) {
    issues.push({
      key: 'chromadb',
      title: 'ChromaDB offline',
      description: 'Vector storage is unavailable — ingestion and retrieval will fail.',
      command: 'docker compose ps   (check the chromadb container)',
    });
  }
  return issues;
}

const ServiceHealthBanner: FC = () => {
  const { status, loading, error } = useStatus();
  const [dismissed, setDismissed] = useState<string>(
    () => sessionStorage.getItem(DISMISS_KEY) ?? '',
  );

  if (loading) return null;

  const issues = computeIssues(status, error);
  if (issues.length === 0) return null;

  // La signature change si la liste des problèmes évolue : un bandeau masqué
  // réapparaît quand un nouveau problème survient (ou disparaît si tout va bien).
  const signature = issues.map((i) => i.key).sort().join(',');
  if (dismissed === signature) return null;

  const dismiss = () => {
    sessionStorage.setItem(DISMISS_KEY, signature);
    setDismissed(signature);
  };

  return (
    <div role="alert" className="w-full bg-error/5 border-b border-error/30 px-4 md:px-8 py-3">
      <div className="max-w-4xl mx-auto flex items-start gap-3">
        <span aria-hidden="true" className="material-symbols-outlined text-[18px] text-error mt-0.5 shrink-0">warning</span>
        <div className="flex-1 space-y-2 min-w-0">
          {issues.map((issue) => (
            <div key={issue.key} className="flex flex-col md:flex-row md:items-baseline gap-x-3 gap-y-0.5">
              <span className="font-headline font-bold text-[11px] uppercase tracking-widest text-error shrink-0">
                {issue.title}
              </span>
              <span className="text-[11px] text-on-surface-variant leading-relaxed">
                {issue.description}
                {issue.command && (
                  <>
                    {' '}
                    <code className="font-mono text-[11px] bg-surface-container-high px-1.5 py-0.5 text-on-surface whitespace-nowrap">
                      {issue.command}
                    </code>
                  </>
                )}
              </span>
            </div>
          ))}
        </div>
        <button
          type="button"
          onClick={dismiss}
          aria-label="Dismiss service health warning"
          className="p-1 text-outline hover:text-on-surface transition-colors shrink-0"
        >
          <span aria-hidden="true" className="material-symbols-outlined text-[16px]">close</span>
        </button>
      </div>
    </div>
  );
};

export default ServiceHealthBanner;
