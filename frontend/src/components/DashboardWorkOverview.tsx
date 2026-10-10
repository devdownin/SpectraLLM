import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { configApi } from '../services/api';
import { useGlobalTasks } from '../hooks/useGlobalTasks';
import type { GlobalTask } from '../hooks/useGlobalTasks';
import { Card } from './ui';

interface Service {
  available?: boolean;
  details?: { activeModelLoaded?: boolean };
}

interface Props {
  chunks: number | null;
  loading: boolean;
  unavailable: boolean;
  updatedAt: number;
  chat?: Service;
  embedding?: Service;
  store?: Service;
  unqualifiedDocuments: number | null;
  children: ReactNode;
}

const actionClass = 'inline-flex items-center justify-center min-h-9 px-4 rounded-lg bg-primary text-on-primary font-medium text-sm hover:bg-primary-fixed';

function TaskList({ tasks }: { tasks: GlobalTask[] }) {
  const { t, i18n } = useTranslation();
  return (
    <ul className="divide-y divide-outline-variant/40">
      {tasks.map(task => (
        <li key={task.id}>
          <Link to={task.path} className="block py-3 rounded-lg hover:bg-surface-container-high transition-colors">
            <div className="flex items-start gap-3">
              <span aria-hidden="true" className="material-symbols-outlined text-primary text-xl mt-0.5">{task.icon}</span>
              <div className="min-w-0 flex-1 space-y-1">
                <p className="text-sm font-medium break-words">{task.label}</p>
                <p className="text-xs text-on-surface-variant">{t(`taskCenter.kinds.${task.kind}`)} · {t(`dashboard.work.status.${task.status}`)}</p>
                {task.detail && <p className="text-xs text-on-surface-variant">{task.detail}</p>}
                {task.error && <p className="text-xs text-error break-words">{task.error}</p>}
                {task.timestamp && Number.isFinite(Date.parse(task.timestamp)) && (
                  <time className="block text-xs text-on-surface-variant" dateTime={task.timestamp}>
                    {new Date(task.timestamp).toLocaleString(i18n.resolvedLanguage)}
                  </time>
                )}
                {(task.status === 'running' || task.status === 'pending') && task.progress !== null && (
                  <progress className="w-full h-2 accent-primary" value={task.progress} max={1}
                    aria-label={t('dashboard.work.progress', { name: task.label })} />
                )}
              </div>
              <span aria-hidden="true" className="material-symbols-outlined text-on-surface-variant text-lg">arrow_forward</span>
            </div>
          </Link>
        </li>
      ))}
    </ul>
  );
}

/** Operational overview; uses the same queries and task normalization as the global task center. */
export default function DashboardWorkOverview({ chunks, loading, unavailable, updatedAt, chat, embedding, store, unqualifiedDocuments, children }: Props) {
  const { t, i18n } = useTranslation();
  const { tasks, activeTasks, isLoading: tasksLoading, liveStatus } = useGlobalTasks();
  const { data: consistency, isError: consistencyError } = useQuery({
    queryKey: ['embedding-consistency'],
    queryFn: () => configApi.getEmbeddingConsistency().then(res => res.data),
    refetchInterval: 60_000,
  });
  const { data: reindexStatuses, isError: reindexError } = useQuery({
    queryKey: ['embedding-reindex'],
    queryFn: () => configApi.getReindexStatuses().then(res => res.data),
    refetchInterval: query => query.state.data?.some((s: { status: string }) => s.status === 'RUNNING') ? 2_000 : 30_000,
  });

  const blocked = (consistency?.mismatches ?? 0) > 0
    || (reindexStatuses ?? []).some((s: { status: string }) => s.status === 'RUNNING' || s.status === 'FAILED');
  const unknown = loading || unavailable || chunks === null || !chat || !embedding || !store
    || !consistency || !reindexStatuses || consistencyError || reindexError
    || (chunks !== null && chunks > 0 && !(consistency.collections?.length > 0))
    || consistency?.collections?.some((c: { status: string }) => c.status === 'UNSTAMPED');
  const servicesReady = chat?.available && chat.details?.activeModelLoaded === true
    && embedding?.available && embedding.details?.activeModelLoaded === true && store?.available;
  const state = unknown ? 'unknown' : blocked ? 'blocked' : !servicesReady ? 'services' : chunks === 0 ? 'empty' : 'ready';
  const action = state === 'ready' ? 'test' : state === 'empty' ? 'import' : state === 'blocked' ? 'reviewIndex' : 'models';
  const actionPath = state === 'ready' ? '/playground' : state === 'empty' ? '/ingestion'
    : state === 'blocked' ? '#dashboard-attention' : '/model-hub';
  const recent = [...tasks].sort((a, b) => (b.timestamp ?? '').localeCompare(a.timestamp ?? ''));
  const failed = recent.filter(task => task.status === 'failed' || task.error);
  const completed = recent.filter(task => task.status === 'completed' && !task.error);

  return (
    <div className="space-y-6">
      <section aria-labelledby="dashboard-summary-title" className="bg-surface-container border border-primary/20 rounded-xl p-5 md:p-6">
        <p className="text-xs font-medium text-primary mb-2">{t('dashboard.work.kicker')}</p>
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="max-w-2xl space-y-2">
            <h2 id="dashboard-summary-title" className="text-xl font-semibold">{t(`dashboard.work.summary.${state}`)}</h2>
            <p className="text-sm text-on-surface-variant">{t(`dashboard.work.hint.${state}`)}</p>
            <p className="text-xs text-on-surface-variant">
              {updatedAt > 0 ? t('dashboard.work.updated', { time: new Date(updatedAt).toLocaleString(i18n.resolvedLanguage) }) : t('dashboard.work.noUpdate')}
              {unavailable && ` · ${t('dashboard.work.partial')}`}
            </p>
          </div>
          {state !== 'unknown' && (actionPath.startsWith('#')
            ? <a href={actionPath} className={actionClass}>{t(`dashboard.work.action.${action}`)}</a>
            : <Link to={actionPath} className={actionClass}>{t(`dashboard.work.action.${action}`)}</Link>)}
        </div>
      </section>

      <section id="dashboard-attention" aria-labelledby="dashboard-attention-title" className="scroll-mt-20 space-y-4">
        <h2 id="dashboard-attention-title" className="text-lg font-semibold">{t('dashboard.work.attention')}</h2>
        {children}
        {unqualifiedDocuments !== null && unqualifiedDocuments > 0 && (
          <Card>
            <div className="flex flex-wrap items-center justify-between gap-3">
              <p className="text-sm">{t('dashboard.work.unqualified', { count: unqualifiedDocuments })}</p>
              <Link to="/documents" className="text-sm font-medium text-primary hover:underline">{t('dashboard.work.action.qualify')}</Link>
            </div>
          </Card>
        )}
        <Card>
          {unavailable || consistencyError || reindexError ? <p className="text-sm text-warning">{t('dashboard.work.partial')}</p> : null}
          {failed.length > 0 ? <TaskList tasks={failed.slice(0, 6)} />
            : <p className="text-sm text-on-surface-variant">{t(tasksLoading ? 'dashboard.work.loading' : 'dashboard.work.noFailures')}</p>}
        </Card>
      </section>

      <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">
        <section aria-labelledby="dashboard-running-title">
          <Card className="h-full">
            <h2 id="dashboard-running-title" className="text-lg font-semibold">{t('dashboard.work.running')} <span className="text-sm text-on-surface-variant">({activeTasks.length})</span></h2>
            <p className="text-xs text-on-surface-variant mt-1">{t(liveStatus === 'open' ? 'dashboard.work.live' : 'dashboard.work.polling')}</p>
            {activeTasks.length > 0 ? <TaskList tasks={activeTasks} />
              : <p className="text-sm text-on-surface-variant mt-4">{t(tasksLoading ? 'dashboard.work.loading' : 'dashboard.work.noActive')}</p>}
          </Card>
        </section>
        <section aria-labelledby="dashboard-results-title">
          <Card className="h-full">
            <h2 id="dashboard-results-title" className="text-lg font-semibold">{t('dashboard.work.results')}</h2>
            <p className="text-xs text-on-surface-variant mt-1">{t('dashboard.work.resultsHint')}</p>
            {completed.length > 0 ? <TaskList tasks={completed.slice(0, 6)} />
              : <p className="text-sm text-on-surface-variant mt-4">{t(tasksLoading ? 'dashboard.work.loading' : 'dashboard.work.noResults')}</p>}
          </Card>
        </section>
      </div>
    </div>
  );
}
