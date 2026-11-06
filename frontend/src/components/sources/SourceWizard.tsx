import { useEffect, useId, useState } from 'react';
import { createPortal } from 'react-dom';
import { useTranslation } from 'react-i18next';
import { useFocusTrap } from '../../hooks/useFocusTrap';
import { apiErrorMessage } from '../../lib/apiError';
import { sourcesApi } from '../../services/sources';
import type { ConnectionResult, Source, SourceCatalogEntry, SourceConfig, SourceRequest, SourceType } from '../../types/sources';
import { Badge, Button, Field, Input, Select, Textarea } from '../ui';
import { newSource, sourceIcons, sourceLocation, sourceRequest, SOURCE_TYPES } from './sourcePresentation';

export function SourceWizard({ source, initialType, catalog, onClose, onSaved }: {
  source?: Source; initialType?: SourceType; catalog: SourceCatalogEntry[]; onClose: () => void; onSaved: (source: Source) => void;
}) {
  const { t } = useTranslation();
  const [step, setStep] = useState(source ? 1 : 0);
  const [request, setRequest] = useState<SourceRequest>(() => source ? sourceRequest(source) : newSource(initialType));
  const [extensions, setExtensions] = useState(source?.config.extensions.join(', ') ?? '');
  const [exclusions, setExclusions] = useState(source?.config.excludePatterns.join('\n') ?? '');
  const [test, setTest] = useState<ConnectionResult | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState<'test' | 'save' | null>(null);
  const titleId = useId();
  const close = () => { if (!busy) onClose(); };
  const ref = useFocusTrap<HTMLDivElement>(true, close);
  useEffect(() => {
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = previous; };
  }, []);
  const change = (value: Partial<SourceRequest>) => { setRequest(old => ({ ...old, ...value })); setTest(null); setError(''); };
  const config = (value: Partial<SourceConfig>) => change({ config: { ...request.config, ...value } });
  const payload = (): SourceRequest => ({ ...request, name: request.name.trim(), collection: request.collection.trim(), config: {
    ...request.config, extensions: extensions.split(',').map(v => v.trim().toLowerCase().replace(/^\./, '')).filter(Boolean),
    excludePatterns: exclusions.split('\n').map(v => v.trim()).filter(Boolean),
  } });
  const testConnection = async () => {
    setBusy('test'); setError(''); setTest(null);
    try { setTest((await sourcesApi.test(payload())).data); }
    catch (e) { setError(apiErrorMessage(e) ?? t('sources.actionFailed')); }
    finally { setBusy(null); }
  };
  const save = async () => {
    if (!test?.success || busy) return;
    setBusy('save'); setError('');
    try {
      const result = source ? await sourcesApi.update(source.id, payload()) : await sourcesApi.create(payload());
      onSaved(result.data);
    } catch (e) { setError(apiErrorMessage(e) ?? t('sources.actionFailed')); setBusy(null); }
  };
  const textField = (key: keyof SourceConfig, label: string, placeholder = '', required = false, description?: string) => (
    <Field label={t(`sources.fields.${label}`)} description={description && t(`sources.${description}`)} required={required}>
      {props => <Input {...props} required={required} value={String(request.config[key] ?? '')} placeholder={placeholder} onChange={e => config({ [key]: e.target.value.trimStart() })} autoComplete="off" spellCheck={false} />}
    </Field>
  );
  const steps = ['choose', 'configure', 'review'];
  return createPortal(<div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm p-0 sm:p-5" onClick={e => { if (e.target === e.currentTarget) close(); }}>
    <div ref={ref} tabIndex={-1} role="dialog" aria-modal="true" aria-labelledby={titleId} className="flex max-h-full h-full sm:h-auto w-full max-w-4xl flex-col overflow-hidden bg-surface-container-low sm:rounded-2xl border border-outline-variant/50 shadow-2xl outline-none">
      <header className="shrink-0 flex items-start justify-between gap-3 p-5 sm:px-7 border-b border-outline-variant/30">
        <div><p className="text-[11px] uppercase tracking-wider text-primary mb-1">{t('sources.knowledgeFlow')}</p><h2 id={titleId} className="text-xl font-semibold tracking-tight">{t(source ? 'sources.editSource' : 'sources.addSource')}</h2></div>
        <Button type="button" variant="ghost" onClick={close} disabled={!!busy} aria-label={t('sources.close')} icon="close" />
      </header>
      <ol aria-label={t('sources.setupSteps')} className="shrink-0 grid grid-cols-3 border-b border-outline-variant/30 px-3 sm:px-7 py-4 gap-2">
        {steps.map((item, index) => <li key={item} aria-current={step === index ? 'step' : undefined} className={`flex items-center gap-2 text-xs ${step === index ? 'text-primary' : 'text-on-surface-variant'}`}>
          <span className={`flex w-7 h-7 shrink-0 items-center justify-center rounded-full text-xs font-semibold ${step === index ? 'bg-primary text-on-primary' : 'border border-outline-variant'}`}>{step > index ? <span aria-hidden="true" className="material-symbols-outlined text-base">check</span> : index + 1}</span>
          <span>{t(`sources.steps.${item}`)}</span>
        </li>)}
      </ol>
      <form className="flex flex-col min-h-0 flex-1" onSubmit={e => { e.preventDefault(); if (step < 2) { setError(''); setStep(step + 1); } else void save(); }}>
        <div className="overflow-y-auto overscroll-contain p-5 sm:p-7 space-y-6 min-h-0">
          {step === 0 && <>
            <div><h3 className="font-semibold">{t('sources.chooseTitle')}</h3><p className="text-sm text-on-surface-variant mt-1">{t('sources.chooseHint')}</p></div>
            <div className="grid sm:grid-cols-3 gap-3">
              {SOURCE_TYPES.map(type => {
                const available = catalog.find(entry => entry.type === type)?.available === true;
                return <button key={type} type="button" aria-pressed={request.type === type} disabled={!available} onClick={() => { change(newSource(type)); setExtensions(''); setExclusions(''); }} className={`text-left p-5 rounded-xl border transition-colors disabled:opacity-50 ${request.type === type ? 'border-primary bg-primary/5 ring-1 ring-primary/30' : 'border-outline-variant/50 hover:border-primary/40 bg-surface-container'}`}>
                  <div className="flex items-center justify-between mb-5"><span aria-hidden="true" className="material-symbols-outlined text-3xl text-primary">{sourceIcons[type]}</span>{request.type === type && <span aria-hidden="true" className="material-symbols-outlined text-primary text-lg">check_circle</span>}</div>
                  <span className="font-semibold text-sm block">{t(`sources.types.${type}`)}</span><span className="text-xs text-on-surface-variant mt-2 block leading-relaxed">{t(`sources.descriptions.${type}`)}</span>
                  {!available && <span className="text-xs text-warning block mt-3">{t('sources.unavailable')}</span>}
                </button>;
              })}
            </div>
          </>}
          {step === 1 && <fieldset disabled={!!busy} className="space-y-6">
            <div className="flex items-center gap-3"><span aria-hidden="true" className="material-symbols-outlined rounded-xl bg-primary/10 p-3 text-primary">{sourceIcons[request.type]}</span><div><h3 className="font-semibold">{t(`sources.types.${request.type}`)}</h3><p className="text-xs text-on-surface-variant">{t(`sources.descriptions.${request.type}`)}</p></div></div>
            <div className="grid sm:grid-cols-2 gap-4">
              <Field label={t('sources.fields.name')} required>{props => <Input {...props} required maxLength={120} value={request.name} placeholder={t(`sources.examples.${request.type}`)} onChange={e => change({ name: e.target.value })} />}</Field>
              <Field label={t('sources.fields.collection')} description={t('sources.collectionHint')} required>{props => <Input {...props} required maxLength={63} disabled={!!source} value={request.collection} onChange={e => change({ collection: e.target.value })} />}</Field>
            </div>
            {request.type === 'LOCAL_FOLDER' && <div className="space-y-3">{textField('rootPath', 'rootPath', '/app/sources/documents', true)}<p className="rounded-lg border border-primary/20 bg-primary/5 p-3 text-xs leading-relaxed text-on-surface-variant">{t('sources.localHint')}</p></div>}
            {request.type === 'WEB' && <div className="grid sm:grid-cols-2 gap-4">{textField('url', 'url', 'https://docs.example.com/', true)}{textField('sitemapUrl', 'sitemapUrl', 'https://docs.example.com/sitemap.xml', false, 'sitemapHint')}</div>}
            {request.type === 'S3' && <div className="space-y-4">
              <div className="grid sm:grid-cols-2 gap-4">{textField('bucket', 'bucket', 'knowledge-base', true)}{textField('prefix', 'prefix', 'documents/')}{textField('endpoint', 'endpoint', 'https://minio.example.com', false, 'endpointHint')}{textField('region', 'region', 'us-east-1', true)}</div>
              <div className="rounded-xl bg-surface-container border border-outline-variant/40 p-4 space-y-4"><div><h4 className="text-sm font-medium">{t('sources.credentialsTitle')}</h4><p className="text-xs text-on-surface-variant mt-1 leading-relaxed">{t('sources.credentialsHint')}</p></div><div className="grid sm:grid-cols-2 gap-4">{textField('accessKeyEnv', 'accessKeyEnv', 'SPECTRA_S3_ACCESS_KEY')}{textField('secretKeyEnv', 'secretKeyEnv', 'SPECTRA_S3_SECRET_KEY')}{textField('sessionTokenEnv', 'sessionTokenEnv', 'SPECTRA_S3_SESSION_TOKEN')}</div></div>
            </div>}
            <div className="grid sm:grid-cols-2 gap-4 border-t border-outline-variant/30 pt-5">
              <Field label={t('sources.fields.intervalMinutes')} description={t('sources.scheduleHint')}>{props => <Select {...props} value={request.intervalMinutes} onChange={e => change({ intervalMinutes: Number(e.target.value) })}>{[0, 5, 15, 30, 60, 360, 1440, 10080].map(value => <option value={value} key={value}>{value === 0 ? t('sources.manual') : value < 60 ? t('sources.everyMinutes', { count: value }) : value < 1440 ? t('sources.everyHours', { count: value / 60 }) : t('sources.everyDays', { count: value / 1440 })}</option>)}</Select>}</Field>
              <Field label={t('sources.fields.extensions')} description={t('sources.extensionsHint')}>{props => <Input {...props} value={extensions} placeholder="pdf, docx, md, txt" onChange={e => { setExtensions(e.target.value); setTest(null); }} />}</Field>
            </div>
            <details className="rounded-xl border border-outline-variant/40 p-4"><summary className="cursor-pointer text-sm font-medium">{t('sources.advanced')}</summary><div className="mt-4 space-y-4">
              <Field label={t('sources.fields.excludePatterns')} description={t('sources.exclusionsHint')}>{props => <Textarea {...props} value={exclusions} placeholder={'**/archive/**\n**/drafts/**'} onChange={e => { setExclusions(e.target.value); setTest(null); }} />}</Field>
              <div className="grid sm:grid-cols-3 gap-4">{(['maxDocuments', 'maxFileSizeMb', ...(request.type !== 'S3' ? ['maxDepth'] : [])] as const).map(key => <Field key={key} label={t(`sources.fields.${key}`)}>{props => <Input {...props} type="number" required min={key === 'maxDepth' ? 0 : 1} max={key === 'maxDepth' ? 8 : key === 'maxDocuments' ? 10000 : 50} value={String(request.config[key as keyof SourceConfig] ?? '')} onChange={e => config({ [key]: e.target.value === '' ? undefined : Number(e.target.value) })} />}</Field>)}</div>
              <p className="text-xs text-on-surface-variant">{t('sources.limitHint')}</p>
            </div></details>
          </fieldset>}
          {step === 2 && <>
            <div className="rounded-xl border border-outline-variant/40 bg-surface-container p-5 space-y-4">
              <div className="flex gap-3 items-center"><span aria-hidden="true" className="material-symbols-outlined rounded-lg p-2 bg-primary/10 text-primary">{sourceIcons[request.type]}</span><div className="min-w-0"><h3 className="font-semibold break-words">{request.name}</h3><p className="text-xs text-on-surface-variant">{t(`sources.types.${request.type}`)}</p></div></div>
              <dl className="grid sm:grid-cols-2 gap-4 text-sm"><div className="sm:col-span-2 min-w-0"><dt className="text-xs text-on-surface-variant">{t('sources.location')}</dt><dd className="break-all mt-1">{sourceLocation(request)}</dd></div><div><dt className="text-xs text-on-surface-variant">{t('sources.fields.collection')}</dt><dd className="mt-1 break-all">{request.collection}</dd></div><div><dt className="text-xs text-on-surface-variant">{t('sources.fields.intervalMinutes')}</dt><dd className="mt-1">{request.intervalMinutes ? t('sources.everyMinutes', { count: request.intervalMinutes }) : t('sources.manual')}</dd></div><div><dt className="text-xs text-on-surface-variant">{t('sources.fields.extensions')}</dt><dd className="mt-1">{extensions || t('sources.allFormats')}</dd></div><div><dt className="text-xs text-on-surface-variant">{t('sources.fields.maxDocuments')}</dt><dd className="mt-1">{request.config.maxDocuments.toLocaleString()}</dd></div></dl>
            </div>
            <div className="rounded-xl border border-outline-variant/40 p-5 space-y-3"><h3 className="font-semibold text-sm">{t('sources.connectionCheck')}</h3><p className="text-sm text-on-surface-variant">{t('sources.testHint')}</p><Button type="button" variant="outline" icon="network_check" onClick={() => void testConnection()} loading={busy === 'test'} disabled={!!busy}>{t('sources.testConnection')}</Button>
              {test && <div role="status" className={`rounded-lg p-3 text-sm ${test.success ? 'bg-success/10 text-success' : 'bg-error/10 text-error'}`}><Badge tone={test.success ? 'success' : 'error'}>{t(test.success ? 'sources.connectionSuccess' : 'sources.connectionFailed')}</Badge><p className="mt-2 break-words">{test.message}</p>{test.success && <p className="text-xs mt-1">{t('sources.sampleCount', { count: test.sampleCount })}</p>}</div>}
            </div>
            <p className="text-xs text-on-surface-variant leading-relaxed">{t('sources.sharedCorpusHint')}</p>
          </>}
          {error && <p role="alert" className="rounded-lg bg-error/10 border border-error/25 p-3 text-sm text-error break-words">{error}</p>}
        </div>
        <footer className="shrink-0 border-t border-outline-variant/30 px-5 sm:px-7 py-4 flex flex-wrap justify-between gap-3 bg-surface-container">
          <Button type="button" variant="ghost" onClick={step === 0 || (source && step === 1) ? close : () => setStep(step - 1)} disabled={!!busy} icon="arrow_back">{t(step === 0 || (source && step === 1) ? 'sources.cancel' : 'sources.back')}</Button>
          {step < 2 ? <Button type="submit" icon="arrow_forward" disabled={step === 0 && catalog.find(entry => entry.type === request.type)?.available !== true}>{t('sources.continue')}</Button> : <Button type="submit" loading={busy === 'save'} disabled={!test?.success || !!busy} icon="check">{t(source ? 'sources.saveChanges' : 'sources.saveSource')}</Button>}
        </footer>
      </form>
    </div>
  </div>, document.body);
}
