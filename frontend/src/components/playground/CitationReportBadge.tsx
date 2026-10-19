import { useId, useState } from 'react';
import type { FC } from 'react';
import { useTranslation } from 'react-i18next';
import type { CitationReport } from '../../services/api';
import { summarizeCitations } from '../../lib/citationReport';
import type { CitationLevel } from '../../lib/citationReport';

const LEVEL_STYLE: Record<CitationLevel, { box: string; icon: string }> = {
  good: { box: 'border-primary/40 text-primary bg-primary/5', icon: 'verified' },
  warn: { box: 'border-secondary/50 text-secondary bg-secondary/10', icon: 'rule' },
  bad:  { box: 'border-error/50 text-error bg-error/10', icon: 'report' },
};

const STATUS_ICON: Record<CitationReport['checks'][number]['status'], { icon: string; tone: string }> = {
  SUPPORTED:        { icon: 'check_circle', tone: 'text-primary' },
  UNSUPPORTED:      { icon: 'help',         tone: 'text-secondary' },
  INVALID_CITATION: { icon: 'link_off',     tone: 'text-error' },
};

/**
 * Fidélité des citations d'une réponse : « 3/4 phrases étayées », dépliable phrase par phrase.
 *
 * Le rapport vient du cross-encoder (pertinence de la phrase pour le passage cité), pas d'une
 * preuve logique — le panneau le dit en toutes lettres, pour qu'un badge vert ne passe pas pour
 * une garantie. Rien n'est rendu sans phrase citée (cf. {@link summarizeCitations}).
 */
const CitationReportBadge: FC<{
  report: CitationReport | null | undefined;
  /** Clic sur un numéro de source dans le détail : déplie la source correspondante. */
  onJumpToSource?: (n: number) => void;
  /** Nombre de sources servies : au-delà, un numéro n'a pas de cible à ouvrir. */
  sourceCount?: number;
}> = ({ report, onJumpToSource, sourceCount = Infinity }) => {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const panelId = useId();
  const summary = summarizeCitations(report);
  if (!report || !summary) return null;
  const style = LEVEL_STYLE[summary.level];

  return (
    <div className="mt-3" data-testid="citation-report">
      <button
        type="button"
        onClick={() => setOpen(o => !o)}
        aria-expanded={open}
        aria-controls={panelId}
        title={t('playground.citations.badgeHint')}
        className={`inline-flex items-center gap-1.5 border px-2 py-1 text-[11px] font-bold ${style.box}`}
      >
        <span aria-hidden="true" className="material-symbols-outlined text-[14px]">{style.icon}</span>
        {t('playground.citations.badge', { supported: summary.supported, cited: summary.cited })}
        <span aria-hidden="true" className={`material-symbols-outlined text-[14px] transition-transform ${open ? 'rotate-180' : ''}`}>expand_more</span>
      </button>

      {open && (
        <div id={panelId} className="mt-2 border border-outline-variant/20 bg-surface-container-lowest p-3 space-y-2">
          <p className="font-label text-[10px] uppercase tracking-widest text-outline">{t('playground.citations.title')}</p>
          <ul className="space-y-2">
            {report.checks.map((c, i) => {
              const st = STATUS_ICON[c.status];
              return (
                <li key={i} className="flex gap-2 text-[12px] leading-relaxed">
                  <span
                    aria-label={t(`playground.citations.status.${c.status}`)}
                    role="img"
                    className={`material-symbols-outlined text-[16px] shrink-0 ${st.tone}`}
                  >{st.icon}</span>
                  <div className="min-w-0">
                    <p className="text-on-surface break-words">{c.sentence}</p>
                    <p className="mt-0.5 flex flex-wrap items-center gap-1.5 text-[10px] text-outline">
                      {c.citations.map(n => {
                        const reachable = n >= 1 && n <= sourceCount;
                        return reachable && onJumpToSource ? (
                          <button
                            key={n}
                            type="button"
                            onClick={() => onJumpToSource(n)}
                            aria-label={t('playground.citations.openSource', { n })}
                            className="font-mono font-bold text-primary bg-primary/10 hover:bg-primary/20 border border-primary/30 rounded px-1"
                          >[{n}]</button>
                        ) : (
                          <span key={n} className="font-mono font-bold">[{n}]</span>
                        );
                      })}
                      {c.status === 'INVALID_CITATION'
                        ? <span className="text-error">{t('playground.citations.invalidCitation')}</span>
                        : typeof c.bestScore === 'number' && (
                          <span>{t('playground.citations.score', { score: c.bestScore.toFixed(2), min: report.minScore.toFixed(2) })}</span>
                        )}
                    </p>
                  </div>
                </li>
              );
            })}
          </ul>
          <p className="text-[11px] text-on-surface-variant border-t border-outline-variant/20 pt-2">{t('playground.citations.caveat')}</p>
        </div>
      )}
    </div>
  );
};

export default CitationReportBadge;
