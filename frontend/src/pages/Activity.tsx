import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useGlobalTasks, isActiveTask } from '../hooks/useGlobalTasks';
import type { GlobalTaskStatus, GlobalTaskKind } from '../hooks/useGlobalTasks';
import { PageHeader, Card } from '../components/ui';

const statuses: GlobalTaskStatus[] = ['pending', 'running', 'completed', 'partial', 'failed', 'cancelled'];
const kinds: GlobalTaskKind[] = ['ingestion', 'dataset', 'dpo', 'training', 'evaluation', 'ab', 'install', 'benchmark', 'ablation'];
export default function Activity() {
  const { t, i18n } = useTranslation();
  const { tasks, isLoading, liveStatus } = useGlobalTasks();
  const [status, setStatus] = useState('all');
  const [kind, setKind] = useState('all');
  const [search, setSearch] = useState('');
  const filtered = useMemo(() => tasks.filter(task =>
    (status === 'all' || task.status === status) && (kind === 'all' || task.kind === kind)
    && `${task.label} ${task.id} ${task.error ?? ''}`.toLocaleLowerCase().includes(search.toLocaleLowerCase())
  ).sort((a, b) => Number(isActiveTask(b)) - Number(isActiveTask(a)) || (b.timestamp ?? '').localeCompare(a.timestamp ?? '')), [tasks, status, kind, search]);
  const control = 'p-2 rounded-lg border border-outline-variant/40 bg-surface-container-high text-sm max-w-full';
  return <div className="space-y-6">
    <PageHeader title={t('activity.title')} description={t('activity.description')} />
    <p className="text-xs text-on-surface-variant" role="status">{t(liveStatus === 'open' ? 'activity.live' : 'activity.polling')}</p>
    <div className="flex flex-wrap gap-3">
      <label className="flex flex-col gap-1 text-xs">{t('activity.search')}<input className={control} value={search} onChange={e => setSearch(e.target.value)} /></label>
      <label className="flex flex-col gap-1 text-xs">{t('activity.status')}<select className={control} value={status} onChange={e => setStatus(e.target.value)}><option value="all">{t('activity.all')}</option>{statuses.map(s => <option key={s} value={s}>{t(`dashboard.work.status.${s}`)}</option>)}</select></label>
      <label className="flex flex-col gap-1 text-xs">{t('activity.kind')}<select className={control} value={kind} onChange={e => setKind(e.target.value)}><option value="all">{t('activity.all')}</option>{kinds.map(k => <option key={k} value={k}>{t(`taskCenter.kinds.${k}`)}</option>)}</select></label>
    </div>
    {isLoading ? <p role="status">{t('activity.loading')}</p> : <>
      <p className="text-sm text-on-surface-variant">{t('activity.count', { count: filtered.length })}</p>
      {!filtered.length && <p>{t('activity.empty')}</p>}
      <ul className="space-y-3">{filtered.map(task => <li key={task.id}><Card className="p-4 space-y-3">
        <div className="flex flex-wrap justify-between gap-3">
          <div className="min-w-0"><p className="text-xs text-on-surface-variant">{t(`taskCenter.kinds.${task.kind}`)}</p><h3 className="text-sm font-medium break-all">{task.label}</h3></div>
          <span className={`text-xs ${task.status === 'failed' ? 'text-error' : task.status === 'partial' ? 'text-secondary' : 'text-primary'}`}>{t(`dashboard.work.status.${task.status}`)}</span>
        </div>
        {task.detail && <p className="text-sm">{task.detail}</p>}
        {isActiveTask(task) && (task.progress !== null ? <div><progress aria-label={t('activity.progress')} value={task.progress} max={1} className="w-full h-2 overflow-hidden rounded-full bg-surface-container-high [&::-webkit-progress-bar]:bg-surface-container-high [&::-webkit-progress-value]:bg-primary [&::-moz-progress-bar]:bg-primary" /><p className="text-xs">{Math.round(task.progress * 100)}%</p></div> : <p className="text-xs text-on-surface-variant">{t('activity.indeterminate')}</p>)}
        {task.error && <details open={task.status === 'failed' || task.status === 'partial'}><summary className="text-sm text-error cursor-pointer">{t('activity.error')}</summary><p className="mt-2 text-xs whitespace-pre-wrap break-all text-error">{task.error}</p></details>}
        <div className="flex flex-wrap items-center justify-between gap-3 text-xs">
          <span className="text-on-surface-variant break-all">{task.id}{task.timestamp && Number.isFinite(Date.parse(task.timestamp)) && <> · <time dateTime={task.timestamp}>{new Date(task.timestamp).toLocaleString(i18n.language)}</time></>}</span>
          <Link className="rounded-lg border border-primary/30 px-3 py-2 text-primary hover:bg-primary/10" to={task.path}>{t(task.status === 'completed' || task.status === 'partial' ? 'activity.result' : 'activity.manage')}</Link>
        </div>
      </Card></li>)}</ul>
    </>}
  </div>;
}
