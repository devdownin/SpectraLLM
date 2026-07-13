import { useEffect, useRef, useState } from 'react';
import type { FC } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { toast } from 'sonner';
import { useGlobalTasks } from '../hooks/useGlobalTasks';
import type { GlobalTask, GlobalTaskStatus } from '../hooks/useGlobalTasks';

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

const TaskRow: FC<{ task: GlobalTask; onNavigate: () => void }> = ({ task, onNavigate }) => {
  const { t } = useTranslation();
  const active = task.status === 'running' || task.status === 'pending';

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
            {task.detail && (
              <span className={`text-[10px] tabular-nums shrink-0 ${STATUS_COLOR[task.status]}`}>
                {task.detail}
              </span>
            )}
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
      )}
    </Link>
  );
};

const TaskCenter: FC = () => {
  const { t } = useTranslation();
  const location = useLocation();
  const { tasks, activeTasks, activeCount } = useGlobalTasks();
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

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

  // Toast de fin de tâche, seulement pour les transitions observées EN SESSION
  // (pas l'historique du premier chargement) et hors de la page concernée
  // (elle notifie déjà elle-même).
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
      if (!wasActive || pathnameRef.current === task.path) continue;
      if (task.status === 'completed') {
        toast.success(t('taskCenter.taskCompleted'), {
          id: `task-${task.id}`,
          description: `${t(`taskCenter.kinds.${task.kind}`)} — ${task.label}`,
        });
      } else if (task.status === 'failed') {
        toast.error(t('taskCenter.taskFailed'), {
          id: `task-${task.id}`,
          description: task.error ?? `${t(`taskCenter.kinds.${task.kind}`)} — ${task.label}`,
        });
      }
    }
  }, [tasks, t]);

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
            <span className="font-headline text-[11px] font-bold uppercase tracking-widest">
              {t('taskCenter.title')}
            </span>
            {activeCount > 0 && (
              <span className="flex items-center gap-1.5 text-[10px] font-bold text-secondary uppercase tracking-widest">
                <span className="w-1.5 h-1.5 bg-secondary animate-pulse" aria-hidden="true" />
                {t('taskCenter.activeCount', { count: activeCount })}
              </span>
            )}
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
                        <TaskRow key={task.id} task={task} onNavigate={() => setOpen(false)} />
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
                        <TaskRow key={task.id} task={task} onNavigate={() => setOpen(false)} />
                      ))}
                    </div>
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      )}
    </div>
  );
};

export default TaskCenter;
