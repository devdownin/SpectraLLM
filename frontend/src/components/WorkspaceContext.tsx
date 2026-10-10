import { useLocation } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { configApi } from '../services/api';
import { useStatus } from '../hooks/useStatus';
import { useWorkspaceCollection } from '../hooks/useWorkspaceCollection';

export default function WorkspaceContext() {
  const { pathname } = useLocation();
  const visible = ['/playground', '/comparison', '/optimization'].includes(pathname);
  return visible ? <ContextBar evaluation={pathname !== '/playground'} /> : null;
}

function ContextBar({ evaluation }: { evaluation: boolean }) {
  const { t } = useTranslation();
  const { status, error } = useStatus();
  const [collection, setCollection] = useWorkspaceCollection();
  const report = useQuery({ queryKey: ['embedding-consistency'], queryFn: () => configApi.getEmbeddingConsistency().then(r => r.data), refetchInterval: 30000 });
  const collections: { name: string; status: string }[] = report.data?.collections ?? [];
  const selected = collections.find(c => c.name === collection);
  const chat = status?.services?.find((s: { name: string }) => s.name === 'llama-cpp');
  const model = chat?.available && chat.details?.activeModelLoaded === true ? chat.details.activeModel : null;
  return <section aria-label={t('workspace.title')} className="mx-4 md:mx-8 mt-4 p-4 rounded-xl border border-outline-variant/30 bg-surface-container-low flex flex-wrap items-center gap-x-6 gap-y-3">
    <div className="min-w-0"><p className="text-xs text-on-surface-variant">{t('workspace.model')}</p><p className="text-sm font-medium break-all">{error ? t('workspace.unavailable') : model ?? t('workspace.unloaded')}</p></div>
    <label className="flex flex-col gap-1 text-xs text-on-surface-variant">{t('workspace.collection')}
      <select value={collection} onChange={e => setCollection(e.target.value)} className="max-w-full bg-surface-container-high border border-outline-variant/40 rounded-lg p-2 text-sm text-on-surface">
        <option value="">{t('workspace.default')}</option>
        {collection && !selected && <option value={collection}>{collection}</option>}
        {collections.map(c => <option key={c.name} value={c.name}>{c.name}</option>)}
      </select>
    </label>
    <p className="text-xs text-on-surface-variant flex-1 basis-64">{evaluation ? t('workspace.evaluationHint') : t('workspace.playgroundHint')}
      {report.isError && <span className="block text-error">{t('workspace.collectionsUnavailable')}</span>}
      {collection && !report.isPending && !report.isError && !selected && <span className="block text-error">{t('workspace.missingCollection')}</span>}
      {selected?.status === 'MISMATCH' && <span className="block text-error">{t('workspace.mismatch')}</span>}
    </p>
  </section>;
}
