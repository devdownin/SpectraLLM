import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { toast } from 'sonner';
import { useSearchParams } from 'react-router-dom';
import { apiErrorMessage } from '../lib/apiError';
import { sourcesApi } from '../services/sources';
import type { Source, SourceType } from '../types/sources';
import ConfirmDialog from '../components/ConfirmDialog';
import { Badge, Button, Card, EmptyState, Input, PageHeader, Select, Stat } from '../components/ui';
import { SourceDetails } from '../components/sources/SourceDetails';
import type { SourceAction } from '../components/sources/SourceDetails';
import { SourceWizard } from '../components/sources/SourceWizard';
import { displayDate, sourceIcons, sourceLocation, sourceRequest, sourceStatus, statusTones, SOURCE_TYPES } from '../components/sources/sourcePresentation';

export default function Sources() {
  const { t, i18n } = useTranslation();
  const client = useQueryClient();
  const [params, setParams] = useSearchParams();
  const sources = useQuery({ queryKey: ['sources'], queryFn: async () => (await sourcesApi.list()).data, refetchInterval: 5000 });
  const catalog = useQuery({ queryKey: ['source-catalog'], queryFn: async () => (await sourcesApi.catalog()).data });
  const [search, setSearch] = useState('');
  const [filter, setFilter] = useState('ALL');
  const [selected, setSelected] = useState<string | null>(null);
  const [wizard, setWizard] = useState<{ source?: Source; type?: SourceType } | null>(null);
  const [removing, setRemoving] = useState<Source | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [actionError, setActionError] = useState('');
  const items = sources.data ?? [];
  const selectedId = selected ?? params.get('source');
  const selectedDocumentId = selectedId === params.get('source') ? params.get('document') : null;
  const chosen = items.find(source => source.id === selectedId);
  const closeDetails = () => { setSelected(null); if (params.has('source') || params.has('document')) { const next = new URLSearchParams(params); next.delete('source'); next.delete('document'); setParams(next, { replace: true }); } };
  const filtered = items.filter(source => `${source.name} ${sourceLocation(source)} ${t(`sources.types.${source.type}`)}`.toLocaleLowerCase().includes(search.toLocaleLowerCase()) && (filter === 'ALL' || filter === 'ATTENTION' && ['FAILED', 'PARTIAL', 'INTERRUPTED'].includes(source.status) || sourceStatus(source) === filter));
  const invalidate = async () => { await Promise.all([client.invalidateQueries({ queryKey: ['sources'] }), client.invalidateQueries({ queryKey: ['source-documents'] }), client.invalidateQueries({ queryKey: ['source-runs'] })]); };
  const action = async (name: SourceAction, source: Source) => {
    if (name === 'edit') { closeDetails(); setWizard({ source }); return; }
    if (name === 'delete') { setRemoving(source); return; }
    setBusy(source.id); setActionError('');
    try {
      if (name === 'sync') { await sourcesApi.sync(source.id); toast.success(t('sources.syncStarted')); }
      if (name === 'cancel') { await sourcesApi.cancel(source.id); toast.success(t('sources.cancelRequested')); }
      if (name === 'toggle') { await sourcesApi.update(source.id, { ...sourceRequest(source), enabled: !source.enabled }); toast.success(t(source.enabled ? 'sources.paused' : 'sources.resumed')); }
      if (name === 'test') { const result = (await sourcesApi.testSaved(source.id)).data; if (result.success) toast.success(t('sources.connectionSuccess'), { description: result.message }); else { setActionError(result.message); toast.error(t('sources.connectionFailed'), { description: result.message }); } }
      await invalidate();
    } catch (e) { const message = apiErrorMessage(e) ?? t('sources.actionFailed'); setActionError(message); toast.error(t('sources.actionFailed'), { description: message }); }
    finally { setBusy(null); }
  };
  const remove = async () => {
    if (!removing) return;
    setBusy(removing.id); setActionError('');
    try { await sourcesApi.remove(removing.id); if (selectedId === removing.id) closeDetails(); setRemoving(null); await invalidate(); toast.success(t('sources.deleted')); }
    catch (e) { const message = apiErrorMessage(e) ?? t('sources.actionFailed'); setActionError(message); toast.error(t('sources.actionFailed'), { description: message }); }
    finally { setBusy(null); }
  };
  return <div className="space-y-7 pb-8">
    <PageHeader kicker={t('sources.kicker')} title={t('sources.title')} description={t('sources.subtitle')} primaryAction={<Button icon="add" onClick={() => setWizard({})} disabled={catalog.isPending || catalog.isError}>{t('sources.addSource')}</Button>} />
    <div className="grid grid-cols-2 xl:grid-cols-4 gap-3 sm:gap-4">
      <Stat label={t('sources.connectedSources')} value={sources.isError ? '—' : items.length} loading={sources.isPending} hint={t('sources.sourceTypes', { count: SOURCE_TYPES.length })} tone="primary" />
      <Stat label={t('sources.documents')} value={sources.isError ? '—' : items.reduce((total, source) => total + source.documentCount, 0).toLocaleString()} loading={sources.isPending} hint={t('sources.documentsHint')} />
      <Stat label={t('sources.syncing')} value={sources.isError ? '—' : items.filter(source => source.status === 'RUNNING').length} loading={sources.isPending} hint={t('sources.liveHint')} />
      <Stat label={t('sources.attention')} value={sources.isError ? '—' : items.filter(source => ['PARTIAL', 'FAILED', 'INTERRUPTED'].includes(source.status)).length} loading={sources.isPending} hint={t('sources.attentionHint')} />
    </div>
    <section aria-labelledby="source-catalog-heading" className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2"><h3 id="source-catalog-heading" className="font-semibold text-base tracking-tight">{t('sources.catalog')}</h3><span className="text-xs text-on-surface-variant">{t('sources.catalogHint')}</span></div>
      {catalog.isError ? <div role="alert" className="rounded-xl border border-error/20 bg-error/5 p-4 flex items-center justify-between gap-3 text-sm"><p>{t('sources.catalogFailed')}</p><Button variant="outline" onClick={() => void catalog.refetch()}>{t('sources.retry')}</Button></div> : <div className="grid md:grid-cols-3 gap-4">
        {SOURCE_TYPES.map(type => {
          const entry = catalog.data?.find(item => item.type === type);
          const available = entry?.available === true;
          return <Card key={type} className="relative overflow-hidden flex flex-col gap-5 border border-outline-variant/30 hover:border-primary/30 transition-colors">
            <div className="flex items-center justify-between"><span aria-hidden="true" className="material-symbols-outlined text-[25px] rounded-xl bg-primary/10 border border-primary/10 p-3 text-primary">{sourceIcons[type]}</span><Badge tone={available ? 'neutral' : 'warning'}>{t(available ? `sources.tags.${type}` : 'sources.unavailable')}</Badge></div>
            <div className="flex-1"><h4 className="font-semibold text-[15px]">{t(`sources.types.${type}`)}</h4><p className="mt-1.5 text-[13px] leading-relaxed text-on-surface-variant">{t(`sources.descriptions.${type}`)}</p></div>
            <div className="flex items-center justify-between border-t border-outline-variant/30 pt-3 gap-2"><span className="text-[11px] text-on-surface-variant">{t(`sources.benefits.${type}`)}</span><Button variant="ghost" size="sm" icon="add" disabled={catalog.isPending || !available} onClick={() => setWizard({ type })} aria-label={t('sources.addType', { type: t(`sources.types.${type}`) })}>{t('sources.connect')}</Button></div>
          </Card>;
        })}
      </div>}
    </section>
    <section aria-labelledby="sources-heading" className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3"><div className="flex items-center gap-2"><h3 id="sources-heading" className="text-base font-semibold tracking-tight">{t('sources.yourSources')}</h3><Badge>{items.length}</Badge></div><Button variant="ghost" icon="refresh" onClick={() => void sources.refetch()} loading={sources.isFetching && !sources.isPending}>{t('sources.refresh')}</Button></div>
      <div className="flex flex-col sm:flex-row gap-3"><div className="relative flex-1"><span aria-hidden="true" className="material-symbols-outlined absolute left-3 top-2.5 text-base text-outline">search</span><Input type="search" aria-label={t('sources.search')} placeholder={t('sources.search')} value={search} onChange={e => setSearch(e.target.value)} className="pl-9" /></div><Select aria-label={t('sources.filterStatus')} value={filter} onChange={e => setFilter(e.target.value)} className="sm:max-w-52">{['ALL', 'RUNNING', 'SUCCESS', 'ATTENTION', 'PAUSED', 'IDLE'].map(value => <option value={value} key={value}>{t(`sources.filters.${value}`)}</option>)}</Select></div>
      {actionError && <p role="alert" className="rounded-lg bg-error/10 p-3 text-sm text-error break-words">{actionError}</p>}
      {sources.isPending ? <div role="status" aria-label={t('sources.loading')} className="space-y-3">{[0, 1, 2].map(key => <div key={key} className="h-28 rounded-xl skeleton-shimmer" />)}</div> : sources.isError ? <Card><EmptyState icon="cloud_off" title={t('sources.loadFailed')} description={t('sources.loadFailedHint')} action={<Button variant="outline" onClick={() => void sources.refetch()}>{t('sources.retry')}</Button>} /></Card> : !items.length ? <Card className="border border-dashed border-outline-variant/50"><EmptyState icon="hub" title={t('sources.emptyTitle')} description={t('sources.emptyHint')} action={<Button icon="add" disabled={catalog.isPending || catalog.isError} onClick={() => setWizard({})}>{t('sources.addSource')}</Button>} /></Card> : !filtered.length ? <EmptyState icon="search_off" title={t('sources.noMatches')} description={t('sources.noMatchesHint')} action={<Button variant="ghost" onClick={() => { setSearch(''); setFilter('ALL'); }}>{t('sources.clearFilters')}</Button>} /> : <div className="space-y-3">
        {filtered.map(source => {
          const status = sourceStatus(source);
          const running = source.status === 'RUNNING';
          return <Card key={source.id} padding="none" className={`border overflow-hidden ${running ? 'border-primary/30' : 'border-outline-variant/30'}`}>
            <div className="flex flex-col xl:flex-row xl:items-center gap-4 p-4 sm:p-5">
              <div className="flex gap-3 min-w-0 flex-1"><span aria-hidden="true" className="material-symbols-outlined text-primary bg-primary/5 rounded-xl w-11 h-11 shrink-0 flex items-center justify-center">{sourceIcons[source.type]}</span><div className="min-w-0"><div className="flex items-center flex-wrap gap-2"><button type="button" onClick={() => setSelected(source.id)} className="text-sm font-semibold text-left break-words hover:text-primary hover:underline">{source.name}</button><Badge tone={statusTones[status]} dot>{t(`sources.status.${status}`)}</Badge></div><p className="text-xs text-on-surface-variant truncate mt-1 max-w-xl">{sourceLocation(source)}</p><div className="flex gap-x-3 gap-y-1 flex-wrap text-[11px] text-on-surface-variant mt-2"><span>{t('sources.documentTotal', { count: source.documentCount })}</span><span>{t('sources.lastSync')}: {displayDate(source.lastSuccessAt, i18n.language)}</span></div></div></div>
              <div className="flex flex-wrap items-center gap-2 xl:shrink-0">
                <Button variant="ghost" size="sm" onClick={() => setSelected(source.id)}>{t('sources.viewDetails')}</Button>
                {running ? <Button variant="outline" icon="stop_circle" size="sm" disabled={busy !== null} onClick={() => void action('cancel', source)}>{t('sources.cancelSync')}</Button> : <><Button variant="outline" icon={source.enabled ? 'pause' : 'play_arrow'} size="sm" disabled={busy !== null} onClick={() => void action('toggle', source)} aria-label={t(source.enabled ? 'sources.pauseNamed' : 'sources.resumeNamed', { name: source.name })}>{t(source.enabled ? 'sources.pause' : 'sources.resume')}</Button><Button variant="secondary" icon="sync" size="sm" disabled={busy !== null || !source.enabled} loading={busy === source.id} onClick={() => void action('sync', source)}>{t('sources.syncNow')}</Button></>}
              </div>
            </div>
            {running && <div className="h-0.5 bg-primary/10 overflow-hidden"><div className="h-full w-full bg-primary/50 animate-pulse" /></div>}
            {source.lastError && <div className="border-t border-error/15 bg-error/5 px-5 py-2.5 flex items-start gap-2"><span aria-hidden="true" className="material-symbols-outlined text-sm text-error mt-0.5">error</span><p className="text-xs text-error break-words">{source.lastError}</p></div>}
          </Card>;
        })}
      </div>}
    </section>
    <div className="flex gap-3 rounded-xl border border-outline-variant/30 px-4 py-3"><span aria-hidden="true" className="material-symbols-outlined text-base text-outline mt-0.5">info</span><p className="text-xs text-on-surface-variant leading-relaxed">{t('sources.sharedCorpusHint')}</p></div>
    {params.has('source') && !chosen && !sources.isPending && !sources.isError && <p role="alert" className="rounded-lg border border-warning/25 bg-warning/5 p-4 text-sm text-warning">{t('sources.sourceMissing')}</p>}
    {chosen && <SourceDetails key={`${chosen.id}:${selectedDocumentId ?? ''}`} source={chosen} selectedDocumentId={selectedDocumentId} onClose={closeDetails} onAction={(name, source) => void action(name, source)} busy={busy !== null} />}
    {wizard && <SourceWizard source={wizard.source} initialType={wizard.type} catalog={catalog.data ?? []} onClose={() => setWizard(null)} onSaved={source => { setWizard(null); setSelected(source.id); void invalidate(); toast.success(t('sources.saved')); }} />}
    <ConfirmDialog open={!!removing} title={t('sources.deleteSource')} message={<div className="space-y-2"><p>{t('sources.deleteConfirm', { name: removing?.name, count: removing?.documentCount ?? 0 })}</p>{actionError && <p role="alert" className="text-error">{actionError}</p>}</div>} confirmLabel={t('sources.deleteSource')} busy={busy !== null} onCancel={() => { if (!busy) setRemoving(null); }} onConfirm={() => void remove()} />
  </div>;
}
