import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { gedApi } from '../services/api';

export default function DocumentPreview({ sha }: { sha: string }) {
  const { t } = useTranslation();
  const query = useQuery({ queryKey: ['ged-preview', sha], queryFn: () => gedApi.getPreview(sha).then(r => r.data), retry: false });
  return <section aria-label={t('cockpit.preview')} className="min-w-0 p-6 space-y-4 bg-surface-container-lowest lg:overflow-y-auto">
    <h4 className="font-semibold text-sm">{t('cockpit.preview')}</h4>
    <p className="text-xs text-on-surface-variant">{t('cockpit.previewHint')}</p>
    {query.isPending && <p role="status">{t('cockpit.loading')}</p>}
    {query.isError && <div role="alert"><p className="text-sm text-error">{t('cockpit.unavailable')}</p><button className="mt-2 text-sm text-primary" onClick={() => void query.refetch()}>{t('cockpit.retry')}</button></div>}
    {query.data && <>
      {!query.data.chunks.length && <p className="text-sm text-on-surface-variant">{t('cockpit.empty')}</p>}
      {query.data.truncated && <p className="text-xs text-secondary">{t('cockpit.truncated')}</p>}
      {query.data.chunks.map((text, i) => <article className="rounded-lg border border-outline-variant/30 p-4 space-y-2" key={i}><h5 className="text-xs text-primary">{t('cockpit.extract', { number: i + 1 })}</h5><p className="text-sm leading-relaxed whitespace-pre-wrap break-words">{text}</p></article>)}
    </>}
  </section>;
}
