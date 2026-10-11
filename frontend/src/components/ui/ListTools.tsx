import { useTranslation } from 'react-i18next';
import { Button } from './Button';

export function ListTools({ search, onSearch, status, onStatus, statuses, sort, onSort, onReset }: {
  search: string; onSearch: (value: string) => void; status: string; onStatus: (value: string) => void; statuses: string[];
  sort: string; onSort: (value: string) => void; onReset: () => void;
}) {
  const { t } = useTranslation();
  const control = 'h-9 rounded-lg bg-surface-container-high border border-outline-variant/40 px-3 text-sm max-w-full';
  return <div className="flex flex-wrap gap-3 items-end">
    <label className="flex flex-col gap-1 text-xs flex-1 min-w-[140px]">{t('workList.search')}<input type="search" className={control} value={search} onChange={e => onSearch(e.target.value)} /></label>
    <label className="flex flex-col gap-1 text-xs">{t('workList.status')}<select aria-label={t('workList.status')} className={control} value={status} onChange={e => onStatus(e.target.value)}><option value="all">{t('activity.all')}</option>{statuses.map(s => <option key={s} value={s}>{t(`ui.status.task.${s.toLowerCase()}`, { defaultValue: s })}</option>)}</select></label>
    <label className="flex flex-col gap-1 text-xs">{t('workList.sortLabel')}<select aria-label={t('workList.sortLabel')} className={control} value={sort} onChange={e => onSort(e.target.value)}>{['recent', 'name'].map(s => <option key={s} value={s}>{t(`workList.sort.${s}`)}</option>)}</select></label>
    <Button variant="ghost" onClick={onReset} icon="filter_alt_off">{t('workList.reset')}</Button>
  </div>;
}
