import { useTranslation } from 'react-i18next';
import { Button } from './Button';
import type { ReactNode } from 'react';
import { describeStatus } from '../../lib/uiStatus';
import type { StatusDomain } from '../../lib/uiStatus';

export function ListTools({ search, onSearch, status, onStatus, statuses, sort, onSort, onReset, phaseLabels, statusDomain = 'task', resultCount, extraControls, sortChoices = ['recent', 'name'] }: {
  search: string; onSearch: (value: string) => void; status: string; onStatus: (value: string) => void; statuses: string[];
  sort: string; onSort: (value: string) => void; onReset: () => void;
  phaseLabels?: Record<string, string>; statusDomain?: StatusDomain; resultCount?: number; extraControls?: ReactNode; sortChoices?: string[];
}) {
  const { t } = useTranslation();
  const control = 'h-9 rounded-lg bg-surface-container-high border border-outline-variant/40 px-3 text-sm max-w-full';
  return <div data-list-tools className="sticky top-14 z-20 rounded-xl border border-outline-variant/30 bg-background/95 backdrop-blur-md p-3 space-y-2">
    {resultCount !== undefined && <p role="status" className="text-xs text-on-surface-variant">{t('workList.resultCount', { count: resultCount })}</p>}
    <div className="flex flex-wrap gap-3 items-end">
    <label className="flex flex-col gap-1 text-xs flex-1 basis-full sm:basis-auto min-w-0">{t('workList.search')}<input type="search" className={control} value={search} onChange={e => onSearch(e.target.value)} /></label>
    <label className="flex flex-col gap-1 text-xs flex-1 sm:flex-none min-w-0">{t('workList.status')}<select aria-label={t('workList.status')} className={control} value={status} onChange={e => onStatus(e.target.value)}><option value="all">{t('activity.all')}</option>{statuses.map(s => { const key = describeStatus(s, statusDomain).labelKey; return <option key={s} value={s}>{key ? t(key) : s}{phaseLabels?.[s] ? ` · ${phaseLabels[s]}` : ''}</option>; })}</select></label>
    <label className="flex flex-col gap-1 text-xs flex-1 sm:flex-none min-w-0">{t('workList.sortLabel')}<select aria-label={t('workList.sortLabel')} className={control} value={sort} onChange={e => onSort(e.target.value)}>{sortChoices.map(s => <option key={s} value={s}>{t(`workList.sort.${s}`)}</option>)}</select></label>
    <Button variant="ghost" onClick={onReset} icon="filter_alt_off">{t('workList.reset')}</Button>
    {extraControls}
    </div>
  </div>;
}
