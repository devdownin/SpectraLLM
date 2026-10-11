import { useMemo } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useGlobalTasks, isActiveTask } from '../hooks/useGlobalTasks';
import type { GlobalTaskStatus, GlobalTaskKind } from '../hooks/useGlobalTasks';
import { PageHeader, Button, WorkList, WorkListRow, ListFeedback, ListTools, StatusBadge } from '../components/ui';

import { useViewState } from '../hooks/useViewState';
import { taskDestination } from '../lib/contextualNavigation';

const statuses: GlobalTaskStatus[] = ['pending', 'running', 'completed', 'partial', 'failed', 'cancelled'];
const kinds: GlobalTaskKind[] = ['ingestion', 'dataset', 'dpo', 'training', 'evaluation', 'ab', 'install', 'benchmark', 'ablation'];
export default function Activity() {
  const { t, i18n } = useTranslation();
  const { tasks, isLoading, liveStatus, isError, unavailable, refetch } = useGlobalTasks();
  const location = useLocation();
  const restored = location.state?.activityFilters;
  const [status, setStatus] = useViewState('activity.status', restored?.status ?? 'all');
  const [kind, setKind] = useViewState('activity.kind', restored?.kind ?? 'all');
  const [search, setSearch] = useViewState('activity.search', restored?.search ?? '');
  const [sort, setSort] = useViewState('activity.sort', 'recent');
  const reset = () => { setSearch(''); setStatus('all'); setKind('all'); setSort('recent'); };
  const filtered = useMemo(() => tasks.filter(task =>
    (status === 'all' || task.status === status) && (kind === 'all' || task.kind === kind)
    && `${task.label} ${task.id} ${task.error ?? ''}`.toLocaleLowerCase().includes(search.toLocaleLowerCase())
  ).sort((a, b) => sort === 'name' ? a.label.localeCompare(b.label) : Number(isActiveTask(b)) - Number(isActiveTask(a)) || (b.timestamp ?? '').localeCompare(a.timestamp ?? '')), [tasks, status, kind, search, sort]);
  const control = 'p-2 rounded-lg border border-outline-variant/40 bg-surface-container-high text-sm max-w-full';
  return <div className="space-y-6">
    <PageHeader title={t('activity.title')} description={t('activity.description')} />
    <p className="text-xs text-on-surface-variant" role="status">{t(liveStatus === 'open' ? 'activity.live' : 'activity.polling')}</p>
    <ListTools resultCount={filtered.length} search={search} onSearch={setSearch} status={status} onStatus={setStatus} statuses={statuses} sort={sort} onSort={setSort} onReset={reset} extraControls={<label className="flex flex-col gap-1 text-xs">{t('activity.kind')}<select aria-label={t('activity.kind')} className={control} value={kind} onChange={e => setKind(e.target.value)}><option value="all">{t('activity.all')}</option>{kinds.map(k => <option key={k} value={k}>{t(`taskCenter.kinds.${k}`)}</option>)}</select></label>} />
    {!!unavailable && <p role="alert" className="text-sm text-secondary">{t('workList.partial')} <Button variant="ghost" onClick={() => { void refetch?.(); }}>{t('workList.retry')}</Button></p>}
    <ListFeedback loading={isLoading} error={isError} empty={!filtered.length} retry={() => { void refetch?.(); }} action={<Button onClick={reset} variant="outline">{t('workList.reset')}</Button>}>
      {!!filtered.length && <WorkList viewKey="activity" filters={JSON.stringify([search, status, kind, sort])} label={t('activity.title')}>{filtered.map(task => <WorkListRow key={task.id}
        identity={<><p className="font-medium break-all">{task.label}</p><p className="text-xs text-on-surface-variant break-all">{task.id}</p></>}
        status={<StatusBadge status={task.status} />}
        context={<><p className="text-xs text-on-surface-variant">{t(`taskCenter.kinds.${task.kind}`)}</p>{task.detail && <p>{task.detail}</p>}
          {isActiveTask(task) && (task.progress !== null ? <div><progress aria-label={t('activity.progress')} value={task.progress} max={1} className="w-full h-2" /><p className="text-xs">{Math.round(task.progress * 100)}%</p></div> : <p className="text-xs">{t('activity.indeterminate')}</p>)}
          {task.error && <details open={task.status === 'failed' || task.status === 'partial'}><summary className="text-error cursor-pointer">{t('activity.error')}</summary><p className="text-xs whitespace-pre-wrap break-all text-error">{task.error}</p></details>}</>}
        updated={task.timestamp && Number.isFinite(Date.parse(task.timestamp)) ? <time dateTime={task.timestamp}>{new Date(task.timestamp).toLocaleString(i18n.language)}</time> : '—'}
        actions={<Link className="inline-flex rounded-lg border border-primary/30 px-3 py-2 text-primary hover:bg-primary/10" to={taskDestination(task)} state={{ origin: { path: location.pathname + location.search, label: t('activity.title'), state: { activityFilters: { status, kind, search } } } }}>{t(isActiveTask(task) ? 'workList.follow' : task.status === 'completed' || task.status === 'partial' ? 'workList.result' : task.status === 'failed' ? 'workList.inspect' : 'workList.open')}</Link>}
      />)}</WorkList>}
    </ListFeedback>
  </div>;
}
