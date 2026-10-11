import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { configApi } from '../services/api';
import { useStatus } from '../hooks/useStatus';
import { modelPhase } from '../lib/uiStatus';
import { StatusBadge } from './ui';

export default function RuntimeModelSummary() {
  const { t } = useTranslation();
  const { status, error } = useStatus();
  const config = useQuery({ queryKey: ['configured-model'], queryFn: () => configApi.getModelConfig().then(r => r.data), refetchInterval: 30000 });
  const chat = status?.services?.find((s: { name: string }) => s.name === 'llama-cpp');
  const phase = error ? 'unavailable' : modelPhase(chat);
  return <section aria-label={t('ui.modelContext')} className="min-w-0 space-y-2">
    <div className="flex flex-wrap items-center gap-2"><StatusBadge domain="model" status="configured" /><span className="text-sm break-all">{config.isError ? t('workspace.unavailable') : config.data?.model || t('ui.unknown')}</span></div>
    <div className="flex flex-wrap items-center gap-2"><StatusBadge domain="model" status={phase} /><span className="text-sm break-all">{phase === 'loaded' ? chat.details.activeModel : t('workspace.unloaded')}</span></div>
  </section>;
}
