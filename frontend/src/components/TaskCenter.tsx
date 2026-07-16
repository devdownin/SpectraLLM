import { useEffect, useRef, useState } from 'react';
import type { FC } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { toast } from 'sonner';
import { useGlobalTasks, etaMs, formatEta } from '../hooks/useGlobalTasks';
import type { GlobalTask, GlobalTaskKind, GlobalTaskStatus } from '../hooks/useGlobalTasks';
import {
  ingestApi,
  datasetApi,
  dpoApi,
  fineTuningApi,
  evaluationApi,
  modelsHubApi,
  ablationApi,
  qualityBenchmarkApi,
} from '../services/api';
import ConfirmDialog from './ConfirmDialog';

/**
 * Centre d'activité global (header) : rend visibles TOUTES les tâches de fond
 * (ingestion, génération, entraînement, évaluations, téléchargements…) depuis
 * n'importe quelle page — pastille animée + panneau avec barres de progression.
 *
 * Complète le suivi par page sans le remplacer :
 *  - le badge et le titre d'onglet signalent qu'un travail est en cours ;
 *  - un toast notifie la fin d'une tâche quand on N'EST PAS sur sa page
 *    (la page concernée affiche déjà son propre toast, on évite le doublon).
 */

const BASE_TITLE = 'Spectra | AI Architect';
const NOTIFY_STORAGE_KEY = 'spectra-task-notifications';

const STATUS_ICON: Record<GlobalTaskStatus, string> = {
  pending: 'hourglass_empty',
  running: 'progress_activity',
  completed: 'check_circle',
  failed: 'error',
};

const STATUS_COLOR: Record<GlobalTaskStatus, string> = {
  pending: 'text-on-surface-variant',
  running: 'text-secondary',
  completed: 'text-primary',
  failed: 'text-error',
};

/**
 * Annulation par famille de tâche → endpoint DELETE correspondant. Toutes les familles
 * longues sont couvertes. L'id brut est extrait de `${kind}:${id}`.
 */
const CANCEL_BY_KIND: Partial<Record<GlobalTaskKind, (id: string) => Promise<unknown>>> = {
  ingestion:  (id) => ingestApi.cancelTask(id),
  dataset:    (id) => datasetApi.cancelGeneration(id),
  dpo:        (id) => dpoApi.cancelTask(id),
  training:   (id) => fineTuningApi.cancelJob(id),
  evaluation: (id) => evaluationApi.cancel(id),
  ab:         (id) => evaluationApi.cancelAb(id),
  install:    (id) => modelsHubApi.cancelInstallation(id),
  ablation:   (id) => ablationApi.cancelJob(id),
  benchmark:  (id) => qualityBenchmarkApi.cancelCompare(id),
};

const rawTaskId = (task: GlobalTask): string => task.id.slice(task.kind.length + 1);

const TaskRow: FC<{ task: GlobalTask; now: number; onNavigate: () => void; onCancel?: (task: GlobalTask) => void }> =
    ({ task, now, onNavigate, onCancel }) => {
  const { t } = useTranslation();
  const active = task.status === 'running' || task.status === 'pending';
  const eta = etaMs(task, now);
  const cancellable = active && onCancel && CANCEL_BY_KIND[task.kind] !== undefined;

  return (
    <Link
      to={task.path}
      onClick={onNavigate}
      className="block px-4 py-2.5 hover:bg-surface-container-highest transition-colors group"
    >
      <div className="flex items-center gap-3">
        <span
          className={`material-symbols-outlined text-base shrink-0 ${STATUS_COLOR[task.status]} ${
            task.status === 'running' ? 'animate-spin' : ''
          }`}
        >
          {STATUS_ICON[task.status]}
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex items-center justify-between gap-2">
            <span className="text-[11px] font-bold truncate group-hover:text-primary transition-colors" title={task.label}>
              {task.label}
            </span>
            <span className="flex items-center gap-1.5 shrink-0">
              {task.detail && (
                <span className={`text-[10px] tabular-nums ${STATUS_COLOR[task.status]}`}>
                  {task.detail}
                </span>
              )}
              {cancellable && (
                <button
                  type="button"
                  onClick={(e) => { e.preventDefault(); e.stopPropagation(); onCancel!(task); }}
                  aria-label={t('taskCenter.cancel')}
                  title={t('taskCenter.cancel')}
                  className="p-0.5 text-outline hover:text-error transition-colors"
                >
                  <span aria-hidden="true" className="material-symbols-outlined text-[14px]">stop_circle</span>
                </button>
              )}
            </span>
          </div>
          <div className="flex items-center gap-1.5 text-outline">
            <span className="material-symbols-outlined text-[11px] shrink-0" aria-hidden="true">{task.icon}</span>
            <span className="font-label text-[9px] uppercase tracking-widest">
              {t(`taskCenter.kinds.${task.kind}`)}
            </span>
          </div>
          {task.error && (
            <p className="text-[10px] text-error truncate mt-0.5" title={task.error}>{task.error}</p>
          )}
        </div>
      </div>
      {/* Barre de progression : déterminée si un ratio existe, sinon balayage. */}
      {active && (
        <>
          <div className="relative w-full bg-outline-variant/20 h-0.5 mt-2 overflow-hidden">
            {task.progress !== null ? (
              <div
                className="absolute top-0 left-0 h-full bg-secondary transition-all duration-500"
                style={{ width: `${Math.round(task.progress * 100)}%` }}
              />
            ) : (
              task.status === 'running' && <div className="scan-beam" />
            )}
          </div>
          {/* Temps restant estimé (extrapolation linéaire depuis le début de la tâche). */}
          {eta !== null && (
            <p className="text-right text-[9px] text-outline tabular-nums mt-1">
              {t('taskCenter.etaLeft', { time: formatEta(eta) })}
            </p>
          )}
        </>
      )}
    </Link>
  );
};

const TaskCenter: FC = () => {
  const { t } = useTranslation();
  const location = useLocation();
  const { tasks, activeTasks, activeCount, liveStatus } = useGlobalTasks();
  const [open, setOpen] = useState(false);
  const [pendingCancel, setPendingCancel] = useState<GlobalTask | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  const confirmCancel = async () => {
    const task = pendingCancel;
    setPendingCancel(null);
    if (!task) return;
    const cancel = CANCEL_BY_KIND[task.kind];
    if (!cancel) return;
    try {
      await cancel(rawTaskId(task));
      toast.info(t('taskCenter.cancelRequested'), { description: task.label });
    } catch (err: any) {
      toast.error(t('taskCenter.cancelFailed'), {
        description: err?.response?.data?.detail ?? err?.response?.data?.error ?? err?.message,
      });
    }
  };

  // Horloge pour l'ETA : ne tourne que panneau ouvert (le flux SSE n'émet que sur
  // changement d'état, il ne suffit donc pas à rafraîchir un compte à rebours).
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!open) return;
    setNow(Date.now());
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [open]);

  // Titre d'onglet : signale l'activité même quand l'onglet est en arrière-plan.
  useEffect(() => {
    document.title = activeCount > 0 ? `(${activeCount}) ${BASE_TITLE}` : BASE_TITLE;
    return () => { document.title = BASE_TITLE; };
  }, [activeCount]);

  // Fermeture au clic extérieur / Échap.
  useEffect(() => {
    if (!open) return;
    const onPointerDown = (e: MouseEvent) => {
      if (!containerRef.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKeyDown = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  // ── Notifications navigateur (opt-in) ─────────────────────────────────────
  // Complément du titre d'onglet pour les tâches longues (fine-tuning CPU…) :
  // quand l'onglet n'a pas le focus, une notification système signale la fin.
  const notificationsSupported = typeof window !== 'undefined' && 'Notification' in window;
  const [notifyEnabled, setNotifyEnabled] = useState(
    () => notificationsSupported && localStorage.getItem(NOTIFY_STORAGE_KEY) === '1',
  );
  const notifyEnabledRef = useRef(notifyEnabled);
  notifyEnabledRef.current = notifyEnabled;

  const toggleNotifications = async () => {
    if (!notificationsSupported) return;
    if (notifyEnabled) {
      setNotifyEnabled(false);
      localStorage.removeItem(NOTIFY_STORAGE_KEY);
      return;
    }
    const permission = Notification.permission === 'granted'
      ? 'granted'
      : await Notification.requestPermission();
    if (permission !== 'granted') {
      toast.error(t('taskCenter.notifyDenied'));
      return;
    }
    setNotifyEnabled(true);
    localStorage.setItem(NOTIFY_STORAGE_KEY, '1');
    toast.success(t('taskCenter.notifyEnabled'));
  };

  // Toast de fin de tâche, seulement pour les transitions observées EN SESSION
  // (pas l'historique du premier chargement) et hors de la page concernée
  // (elle notifie déjà elle-même). Les notifications système, elles, partent
  // quelle que soit la page — mais uniquement onglet masqué (sinon le toast suffit).
  const prevStatuses = useRef<Map<string, GlobalTaskStatus> | null>(null);
  const pathnameRef = useRef(location.pathname);
  pathnameRef.current = location.pathname;
  useEffect(() => {
    const prev = prevStatuses.current;
    prevStatuses.current = new Map(tasks.map((task) => [task.id, task.status]));
    if (!prev) return;
    for (const task of tasks) {
      const before = prev.get(task.id);
      const wasActive = before === 'running' || before === 'pending';
      if (!wasActive || (task.status !== 'completed' && task.status !== 'failed')) continue;

      const description = task.status === 'failed'
        ? (task.error ?? `${t(`taskCenter.kinds.${task.kind}`)} — ${task.label}`)
        : `${t(`taskCenter.kinds.${task.kind}`)} — ${task.label}`;

      if (pathnameRef.current !== task.path) {
        if (task.status === 'completed') {
          toast.success(t('taskCenter.taskCompleted'), { id: `task-${task.id}`, description });
        } else {
          toast.error(t('taskCenter.taskFailed'), { id: `task-${task.id}`, description });
        }
      }

      if (notifyEnabledRef.current && notificationsSupported
          && Notification.permission === 'granted' && document.hidden) {
        try {
          new Notification(
            task.status === 'completed' ? t('taskCenter.taskCompleted') : t('taskCenter.taskFailed'),
            { body: description, tag: `spectra-${task.id}` }, // tag : pas de doublon si re-émis
          );
        } catch { /* notification refusée par l'OS : le toast reste */ }
      }
    }
  }, [tasks, t, notificationsSupported]);

  // Tâches terminées les plus récentes en tête de section (l'API renvoie
  // l'historique complet ; on n'en montre qu'un extrait, trié par date).
  const recent = tasks
    .filter((task) => task.status === 'completed' || task.status === 'failed')
    .sort((a, b) => (b.timestamp ?? '').localeCompare(a.timestamp ?? ''))
    .slice(0, 6);

  return (
    <div ref={containerRef} className="relative">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-label={t('taskCenter.open')}
        aria-expanded={open}
        aria-haspopup="true"
        title={t('taskCenter.open')}
        className={`relative p-1.5 hover:bg-surface-variant/60 transition-colors ${
          open ? 'text-primary bg-surface-variant/60' : activeCount > 0 ? 'text-secondary' : 'text-on-surface-variant hover:text-primary'
        }`}
      >
        <span aria-hidden="true" className="material-symbols-outlined text-[18px]">browse_activity</span>
        {activeCount > 0 && (
          <>
            {/* Pastille de comptage + halo pulsé : « ça travaille » visible en permanence. */}
            <span className="absolute -top-0.5 -right-0.5 min-w-[14px] h-[14px] bg-secondary animate-ping opacity-40" aria-hidden="true" />
            <span className="absolute -top-0.5 -right-0.5 min-w-[14px] h-[14px] px-0.5 bg-secondary text-on-secondary text-[9px] font-bold leading-[14px] text-center">
              {activeCount}
            </span>
          </>
        )}
      </button>

      {open && (
        <div className="absolute right-0 top-full mt-2 w-80 max-w-[calc(100vw-2rem)] bg-surface-container border border-outline-variant/20 shadow-xl shadow-black/30 z-50 animate-in fade-in slide-in-from-top-2 duration-200">
          <div className="px-4 py-3 border-b border-outline-variant/10 flex items-center justify-between">
            <div className="flex items-center gap-2">
              <span className="font-headline text-[11px] font-bold uppercase tracking-widest">
                {t('taskCenter.title')}
              </span>
              {/* Mode temps réel (SSE) ou repli polling — même sémantique que le Telemetry Stream. */}
              <span
                className={`w-1.5 h-1.5 rounded-full ${liveStatus === 'open' ? 'bg-primary animate-pulse' : 'bg-outline'}`}
                title={liveStatus === 'open' ? t('taskCenter.live') : t('taskCenter.pollingFallback')}
                aria-label={liveStatus === 'open' ? t('taskCenter.live') : t('taskCenter.pollingFallback')}
              />
            </div>
            <div className="flex items-center gap-3">
              {activeCount > 0 && (
                <span className="flex items-center gap-1.5 text-[10px] font-bold text-secondary uppercase tracking-widest">
                  <span className="w-1.5 h-1.5 bg-secondary animate-pulse" aria-hidden="true" />
                  {t('taskCenter.activeCount', { count: activeCount })}
                </span>
              )}
              {/* Opt-in notifications système : signale la fin d'une tâche onglet masqué. */}
              {notificationsSupported && (
                <button
                  type="button"
                  onClick={toggleNotifications}
                  aria-pressed={notifyEnabled}
                  aria-label={t('taskCenter.notifications')}
                  title={t('taskCenter.notifications')}
                  className={`p-0.5 transition-colors ${
                    notifyEnabled ? 'text-primary' : 'text-outline hover:text-on-surface-variant'
                  }`}
                >
                  <span aria-hidden="true" className="material-symbols-outlined text-[15px]">
                    {notifyEnabled ? 'notifications_active' : 'notifications_off'}
                  </span>
                </button>
              )}
            </div>
          </div>

          <div className="max-h-96 overflow-y-auto custom-scrollbar">
            {tasks.length === 0 ? (
              <div className="px-4 py-8 text-center space-y-2">
                <span className="material-symbols-outlined text-2xl text-outline">bedtime</span>
                <p className="text-[11px] text-outline uppercase tracking-widest">{t('taskCenter.empty')}</p>
                <p className="text-[10px] text-on-surface-variant leading-relaxed">{t('taskCenter.emptyHint')}</p>
              </div>
            ) : (
              <>
                {activeTasks.length > 0 && (
                  <div className="py-1">
                    <p className="px-4 pt-2 pb-1 font-label text-[9px] uppercase tracking-widest text-secondary font-bold">
                      {t('taskCenter.inProgress')}
                    </p>
                    <div className="divide-y divide-outline-variant/5">
                      {activeTasks.map((task) => (
                        <TaskRow key={task.id} task={task} now={now} onNavigate={() => setOpen(false)}
                          onCancel={setPendingCancel} />
                      ))}
                    </div>
                  </div>
                )}
                {recent.length > 0 && (
                  <div className="py-1 border-t border-outline-variant/10">
                    <p className="px-4 pt-2 pb-1 font-label text-[9px] uppercase tracking-widest text-outline font-bold">
                      {t('taskCenter.recent')}
                    </p>
                    <div className="divide-y divide-outline-variant/5">
                      {recent.map((task) => (
                        <TaskRow key={task.id} task={task} now={now} onNavigate={() => setOpen(false)} />
                      ))}
                    </div>
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      )}

      {/* Confirmation d'annulation — évite d'interrompre un long entraînement par mégarde. */}
      <ConfirmDialog
        open={pendingCancel !== null}
        title={t('taskCenter.cancelTitle')}
        message={pendingCancel
          ? t('taskCenter.cancelMessage', {
              kind: t(`taskCenter.kinds.${pendingCancel.kind}`),
              label: pendingCancel.label,
            })
          : ''}
        confirmLabel={t('taskCenter.cancelConfirm')}
        onCancel={() => setPendingCancel(null)}
        onConfirm={confirmCancel}
      />
    </div>
  );
};

export default TaskCenter;
