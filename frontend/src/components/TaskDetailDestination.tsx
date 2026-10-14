import { useQuery } from '@tanstack/react-query';
import { useContextSearchParams } from '../hooks/useContextSearchParams';
import { useTranslation } from 'react-i18next';
import { DetailPanel } from './ui/DetailPanel';
import { StatusBadge, Button } from './ui';
import { ingestApi, datasetApi, dpoApi, modelsHubApi, qualityBenchmarkApi } from '../services/api';

const readers = {
  ingestion: ingestApi.getTaskStatus,
  dataset: datasetApi.getGenerationStatus,
  dpo: dpoApi.getTask,
  benchmark: qualityBenchmarkApi.getCompareJob,
  install: async (id: string) => {
    const response = await modelsHubApi.getInstallations();
    const data = (response.data ?? []).find((job: { jobId: string }) => job.jobId === id);
    if (!data) throw new Error('missing');
    return { data };
  },
};
/** Targeted task reader for workspaces whose history does not have a selectable report. */
export default function TaskDetailDestination() {
  const [params] = useContextSearchParams();
  const target = params.get('task');
  return target ? <TaskReader key={target} target={target} /> : null;
}
function TaskReader({ target }: { target: string }) {
  const [params, setParams] = useContextSearchParams();
  const { t, i18n } = useTranslation();
  const separator = target.indexOf(':');
  const kind = target.slice(0, separator) as keyof typeof readers;
  const id = target.slice(separator + 1);
  const reader = Object.hasOwn(readers, kind) ? readers[kind] : undefined;
  const query = useQuery({ queryKey: ['task-detail', target], queryFn: async () => (await reader!(id)).data, enabled: Boolean(target && reader && id), refetchInterval: q => ['PENDING','PROCESSING','RUNNING','DOWNLOADING','REGISTERING'].includes(q.state.data?.status) ? 4000 : false });
  const data = query.data;
  const status = kind === 'ingestion' && data?.status === 'COMPLETED' && data.fileErrors?.length ? 'partial' : data?.status;
  const close = () => { const next = new URLSearchParams(params); next.delete('task'); setParams(next, { replace: true }); };
  const date = (value?: string) => value && Number.isFinite(Date.parse(value)) ? new Date(value).toLocaleString(i18n.language) : '—';
  const unavailable = query.isError || !reader || !id || (!query.isLoading && !data);
  const exportResult = () => {
    const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }));
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = `${kind}-${id.replace(/[^a-z0-9_-]/gi, '_')}.json`; anchor.click(); URL.revokeObjectURL(url);
  };
  return <DetailPanel title={t('detail.taskTitle')} onClose={close}
    summary={<div className="space-y-2"><p className="text-sm break-all">{target}</p>{data && <StatusBadge status={status} />}{query.isLoading && <p role="status">{t('activity.loading')}</p>}{unavailable && <p role="alert">{t('detail.missingTarget')}</p>}</div>}
    details={data && <div className="space-y-3 text-sm">
      {data.modelName && <p>{data.modelName}{data.quant && ` · ${data.quant}`}</p>}
      {Array.isArray(data.files) && <ul>{data.files.map((file: string, index: number) => <li key={index} className="break-all">{file}</li>)}</ul>}
      {data.currentStep && <p>{data.currentStep}</p>}
      {kind === 'install' && typeof data.progress === 'number' && <p>{t('activity.progress')} : {Math.min(100, Math.max(0, data.progress))}%</p>}
      {['chunksCreated', 'pairsGenerated', 'pairsAccepted', 'processed', 'total'].filter(key => typeof data[key] === 'number').map(key => <p key={key}>{t(`detail.${key}`)} : {data[key]}</p>)}
      {data.outputPath && <p className="break-all">{data.outputPath}</p>}
      {data.error && <p className="text-error whitespace-pre-wrap">{data.error}</p>}
      {Array.isArray(data.fileErrors) && data.fileErrors.map((error: string, index: number) => <p key={index} className="text-error">{error}</p>)}
      {kind === 'benchmark' && [data.baselineReport, data.candidateReport].filter(Boolean).map((report, index) => <div key={index} className="rounded-lg border border-outline-variant/30 p-3 space-y-2">
        <p className="font-semibold">{report.model}</p><p>{t('detail.quality')} : {report.avgScore}/10</p>
        {report.items?.map((item: { question: string; modelAnswer?: string; score?: number }, i: number) => <div key={i} className="border-t border-outline-variant/20 pt-2"><p>{item.question}</p><p className="text-on-surface-variant whitespace-pre-wrap">{item.modelAnswer}</p>{typeof item.score === 'number' && <p>{item.score}/10</p>}</div>)}
      </div>)}
    </div>}
    history={data && <dl className="text-sm space-y-2"><dt>{t('detail.started')}</dt><dd>{date(data.startedAt ?? data.createdAt)}</dd><dt>{t('detail.finished')}</dt><dd>{date(data.completedAt)}</dd></dl>}
    actions={<div className="flex flex-wrap gap-2">{data && <Button variant="outline" onClick={exportResult} icon="download">{t('detail.export')}</Button>}<Button variant="outline" onClick={close}>{t('detail.close')}</Button></div>} />;
}
