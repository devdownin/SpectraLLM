import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { sourcesApi } from '../../services/sources';
import type { Source } from '../../types/sources';
import { Badge, Button, DetailPanel, EmptyState } from '../ui';
import { displayDate, safeWebLink, sourceLocation, sourceStatus, statusTones } from './sourcePresentation';

export type SourceAction = 'sync' | 'test' | 'toggle' | 'cancel' | 'edit' | 'delete';
export function SourceDetails({ source, selectedDocumentId, onClose, onAction, busy }: {
  source: Source; selectedDocumentId?: string | null; onClose: () => void; onAction: (action: SourceAction, source: Source) => void; busy: boolean;
}) {
  const { t, i18n } = useTranslation();
  const [page, setPage] = useState(0);
  const [clickedDocument, setClickedDocument] = useState<string | null>(null);
  const activeDocument = clickedDocument ?? selectedDocumentId;
  const documents = useQuery({ queryKey: ['source-documents', source.id, page, source.lastRun?.id, source.lastRun?.completedAt], queryFn: async () => (await sourcesApi.documents(source.id, page)).data, refetchInterval: source.status === 'RUNNING' ? 4000 : false });
  const runs = useQuery({ queryKey: ['source-runs', source.id, source.status], queryFn: async () => (await sourcesApi.runs(source.id)).data, refetchInterval: source.status === 'RUNNING' ? 4000 : false });
  const cited = useQuery({ queryKey: ['source-document', source.id, activeDocument, source.lastRun?.id, source.lastRun?.completedAt], queryFn: async () => (await sourcesApi.document(source.id, activeDocument!)).data, enabled: !!activeDocument });
  const preview = useQuery({ queryKey: ['source-document-preview', source.id, activeDocument, source.lastRun?.id, source.lastRun?.completedAt], queryFn: async () => (await sourcesApi.preview(source.id, activeDocument!)).data, enabled: !!activeDocument });
  const status = sourceStatus(source);
  const running = source.status === 'RUNNING';
  const date = (value: string | null | undefined) => displayDate(value, i18n.language);
  return <DetailPanel title={source.name} onClose={onClose}
    summary={<div className="space-y-4">
      <div className="flex flex-wrap gap-2"><Badge tone={statusTones[status]} dot>{t(`sources.status.${status}`)}</Badge><Badge>{t(`sources.types.${source.type}`)}</Badge><Badge>{source.collection}</Badge></div>
      <p className="text-sm text-on-surface-variant break-all">{sourceLocation(source)}</p>
      <dl className="grid grid-cols-2 lg:grid-cols-4 gap-4 rounded-xl border border-outline-variant/40 p-4">{[
        ['documents', source.documentCount.toLocaleString()], ['lastSuccess', date(source.lastSuccessAt)],
        ['nextSync', source.enabled && source.nextSyncAt ? date(source.nextSyncAt) : t('sources.manual')], ['schedule', source.intervalMinutes ? t('sources.everyMinutes', { count: source.intervalMinutes }) : t('sources.manual')],
      ].map(([key, value]) => <div key={key}><dt className="text-xs text-on-surface-variant">{t(`sources.${key}`)}</dt><dd className="text-sm font-medium mt-1 break-words">{value}</dd></div>)}</dl>
      {running && <div role="status" className="rounded-xl bg-primary/5 border border-primary/20 p-4"><div className="flex items-center gap-2 text-sm text-primary"><span aria-hidden="true" className="material-symbols-outlined animate-spin text-base">sync</span>{t('sources.syncingHint')}</div><p className="text-xs text-on-surface-variant mt-2">{t('sources.publicationHint')}</p></div>}
      {source.lastError && <p className="rounded-xl p-4 text-sm bg-error/5 border border-error/20 text-error break-words">{source.lastError}</p>}
      {source.status === 'PARTIAL' && <p className="text-sm text-warning">{t('sources.partialHint')}</p>}
    </div>}
    details={<div className="space-y-4">
      {activeDocument && <div className="rounded-xl border border-primary/30 bg-primary/5 p-4 space-y-2"><h4 className="text-sm font-medium text-primary">{t(selectedDocumentId === activeDocument ? 'sources.citedDocument' : 'sources.documentPreview')}</h4>{cited.isPending ? <p role="status" className="text-xs">{t('sources.loading')}</p> : cited.isError ? <p role="alert" className="text-xs text-error">{t('sources.documentMissing')}</p> : <><p className="text-sm font-medium break-words">{cited.data.title || cited.data.externalId}</p>{safeWebLink(cited.data.sourceUrl) && <a className="text-xs text-primary hover:underline break-all" href={safeWebLink(cited.data.sourceUrl)} target="_blank" rel="noopener noreferrer">{cited.data.sourceUrl}</a>}<p className="text-xs text-on-surface-variant">{t('sources.indexedAt')}: {date(cited.data.indexedAt)}</p>{cited.data.error && <p className="text-xs text-error break-words">{cited.data.error}</p>}
        {preview.isPending ? <p role="status" className="text-xs text-on-surface-variant">{t('sources.loadingPreview')}</p> : preview.isError ? <div role="alert" className="text-xs text-error space-y-2"><p>{t('sources.previewFailed')}</p><Button variant="outline" size="sm" onClick={() => void preview.refetch()}>{t('sources.retry')}</Button></div> : !preview.data.chunks.length ? <p className="text-xs text-on-surface-variant">{t('sources.previewEmpty')}</p> : <div className="space-y-2"><div tabIndex={0} role="region" aria-label={t('sources.documentPreview')} className="max-h-80 overflow-y-auto overscroll-contain rounded-lg bg-surface-container-low p-4 space-y-4 border border-outline-variant/30 text-sm leading-relaxed">{preview.data.chunks.map((text, index) => <p className="whitespace-pre-wrap break-words" key={index}>{text}</p>)}</div>{preview.data.truncated && <p className="text-xs text-on-surface-variant">{t('sources.previewTruncated')}</p>}</div>}
        </>}</div>}
      <div className="flex flex-wrap items-center justify-between gap-2"><h4 className="font-medium text-sm">{t('sources.indexedDocuments')}</h4><span className="text-xs text-on-surface-variant">{t('sources.documentTotal', { count: documents.data?.totalElements ?? source.documentCount })}</span></div>
      {documents.isPending ? <p role="status" className="text-sm text-on-surface-variant">{t('sources.loading')}</p> : documents.isError ? <div role="alert" className="text-sm text-error space-y-2"><p>{t('sources.documentsFailed')}</p><Button variant="outline" onClick={() => void documents.refetch()}>{t('sources.retry')}</Button></div> : !documents.data?.content.length ? <EmptyState icon="description" title={t('sources.noDocuments')} description={t('sources.noDocumentsHint')} /> : <>
        <div className="overflow-x-auto rounded-xl border border-outline-variant/40"><table className="w-full text-left text-xs"><thead className="bg-surface-container"><tr>{['document', 'state', 'indexedAt'].map(key => <th scope="col" key={key} className="p-3 text-on-surface-variant font-medium">{t(`sources.${key}`)}</th>)}</tr></thead><tbody className="divide-y divide-outline-variant/30">
          {documents.data.content.map(doc => <tr key={doc.id} className={doc.id === activeDocument ? 'bg-primary/5' : undefined}><td className="p-3 min-w-40 max-w-md"><button type="button" onClick={() => setClickedDocument(doc.id)} className="font-medium break-words text-left hover:text-primary hover:underline">{doc.title || doc.externalId}</button>{safeWebLink(doc.sourceUrl) ? <a className="block text-primary truncate mt-1 hover:underline" href={safeWebLink(doc.sourceUrl)} target="_blank" rel="noopener noreferrer">{doc.sourceUrl}</a> : <p className="text-on-surface-variant break-all mt-1">{doc.sourceUrl || doc.externalId}</p>}{doc.error && <p className="text-error mt-1 break-words">{doc.error}</p>}</td><td className="p-3"><Badge tone={doc.error ? 'error' : 'neutral'}>{t(`sources.documentStatus.${doc.status}`, { defaultValue: doc.status })}</Badge></td><td className="p-3 whitespace-nowrap text-on-surface-variant">{date(doc.indexedAt)}</td></tr>)}
        </tbody></table></div>
        {documents.data.totalPages > 1 && <div className="flex items-center justify-end gap-3"><Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}>{t('sources.previous')}</Button><span className="text-xs text-on-surface-variant">{t('sources.pageOf', { page: page + 1, total: documents.data.totalPages })}</span><Button variant="outline" size="sm" disabled={page + 1 >= documents.data.totalPages} onClick={() => setPage(page + 1)}>{t('sources.next')}</Button></div>}
      </>}
      <details className="rounded-lg border border-outline-variant/30 p-3"><summary className="text-sm cursor-pointer">{t('sources.scope')}</summary><dl className="mt-3 grid sm:grid-cols-2 gap-3 text-xs">{[
        ['fields.extensions', source.config.extensions.join(', ') || t('sources.allFormats')],
        ['fields.excludePatterns', source.config.excludePatterns.join(', ') || '—'],
        ['fields.maxDocuments', source.config.maxDocuments.toLocaleString()],
        ['fields.maxFileSizeMb', `${source.config.maxFileSizeMb} MB`],
      ].map(([key, value]) => <div key={key}><dt className="text-on-surface-variant">{t(`sources.${key}`)}</dt><dd className="mt-1 break-words">{value}</dd></div>)}</dl></details>
    </div>}
    history={<div className="space-y-3">
      {runs.isPending ? <p role="status" className="text-sm text-on-surface-variant">{t('sources.loading')}</p> : runs.isError ? <div role="alert" className="space-y-2 text-sm text-error"><p>{t('sources.runsFailed')}</p><Button variant="outline" onClick={() => void runs.refetch()}>{t('sources.retry')}</Button></div> : !runs.data?.length ? <EmptyState icon="history" title={t('sources.noRuns')} description={t('sources.noRunsHint')} /> : runs.data.map(run => <article key={run.id} className="rounded-xl border border-outline-variant/40 p-4 space-y-3">
        <div className="flex flex-wrap gap-3 items-center justify-between"><p className="text-sm font-medium">{date(run.startedAt)}</p><Badge dot tone={statusTones[run.status]}>{t(`sources.status.${run.status}`)}</Badge></div>
        <dl className="grid grid-cols-3 lg:grid-cols-6 gap-3">{(['scanned', 'indexed', 'unchanged', 'deleted', 'failed'] as const).map(key => <div key={key}><dt className="text-[11px] text-on-surface-variant">{t(`sources.counts.${key}`)}</dt><dd className={`text-sm mt-1 tabular-nums ${key === 'failed' && run.failed ? 'text-error' : ''}`}>{run[key].toLocaleString()}</dd></div>)}<div><dt className="text-[11px] text-on-surface-variant">{t('sources.duration')}</dt><dd className="text-sm mt-1">{run.completedAt ? t('sources.seconds', { count: Math.max(0, Math.round((Date.parse(run.completedAt) - Date.parse(run.startedAt)) / 1000)) }) : '—'}</dd></div></dl>
        {run.error && <p className="text-xs text-error break-words">{run.error}</p>}
      </article>)}
    </div>}
    actions={<div className="space-y-4"><div className="flex flex-wrap gap-2">
      <Button icon="sync" disabled={running || busy || !source.enabled} onClick={() => onAction('sync', source)}>{t('sources.syncNow')}</Button>
      {running ? <Button variant="outline" icon="stop_circle" disabled={busy} onClick={() => onAction('cancel', source)}>{t('sources.cancelSync')}</Button> : <>
        <Button variant="outline" icon="network_check" disabled={busy} onClick={() => onAction('test', source)}>{t('sources.testConnection')}</Button>
        <Button variant="outline" icon={source.enabled ? 'pause' : 'play_arrow'} disabled={busy} onClick={() => onAction('toggle', source)}>{t(source.enabled ? 'sources.pause' : 'sources.resume')}</Button>
        <Button variant="outline" icon="edit" disabled={busy} onClick={() => onAction('edit', source)}>{t('sources.configure')}</Button>
      </>}
    </div><p className="text-xs text-on-surface-variant">{t(running ? 'sources.stopBeforeEdit' : 'sources.pauseHint')}</p><div className="border-t border-outline-variant/30 pt-4"><Button variant="danger" icon="delete" disabled={running || busy} onClick={() => onAction('delete', source)}>{t('sources.deleteSource')}</Button><p className="text-xs text-on-surface-variant mt-2">{t('sources.deleteHint')}</p></div></div>}
  />;
}
