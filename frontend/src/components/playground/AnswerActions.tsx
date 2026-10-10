import { useTranslation } from 'react-i18next';
import type { Message } from './ragTypes';
import { appliedModules } from '../../lib/ragPipeline';

interface Props { message: Message; onSources: () => void; onTrace: () => void; onCompare: () => void; onEvaluate: () => void; }
export default function AnswerActions({ message, onSources, onTrace, onCompare, onEvaluate }: Props) {
  const { t } = useTranslation();
  const reasons = [message.stopped && 'stopped', !message.sources?.length && 'noSources', message.ragMeta?.agenticStopReason === 'MAX_ITERATIONS' && 'maxIterations'].filter(Boolean) as string[];
  const action = 'rounded-lg border border-outline-variant/40 px-3 py-2 text-xs hover:bg-surface-container-high disabled:opacity-40';
  return <section className="mt-4 pt-4 border-t border-outline-variant/20 space-y-3" aria-label={t('answer.limitations')}>
    <h4 className="text-xs font-semibold">{t('answer.limitations')}</h4>
    <ul className="text-xs text-on-surface-variant space-y-1 list-disc pl-4">
      {reasons.map(reason => <li key={reason}>{t(`answer.${reason}`)}</li>)}
      <li>{t('answer.verify')}</li>
    </ul>
    <div className="flex flex-wrap gap-2">
      <button className={action} onClick={onSources} disabled={!message.sources?.length}>{t('answer.sources')}</button>
      <button className={action} onClick={onTrace} disabled={!message.ragMeta}>{t('answer.trace')}</button>
      <button className={action} onClick={onCompare} disabled={!appliedModules(message.ragMeta).length || message.stopped}>{t('answer.compare')}</button>
      <button className={action} onClick={onEvaluate}>{t('answer.evaluate')}</button>
    </div>
    <p className="text-xs text-on-surface-variant">{t('answer.evaluationHint')}</p>
  </section>;
}
