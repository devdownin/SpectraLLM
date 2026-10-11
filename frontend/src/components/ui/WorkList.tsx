import { Children, useEffect, useRef } from 'react';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useViewState } from '../../hooks/useViewState';
import { Button } from './Button';
import { useScrollMemory } from '../../hooks/useScrollMemory';

export const workRowClass = 'grid grid-cols-1 lg:grid-cols-[minmax(0,2fr)_minmax(0,1fr)_minmax(0,2fr)_minmax(0,1fr)_auto] gap-3 lg:gap-4 items-center p-4 border-b border-outline-variant/30 last:border-0 hover:bg-surface-container-high/40';
const fields = ['identity', 'status', 'context', 'updated', 'actions'];

export function WorkList({ children, label, viewKey, filters = '' }: { children: ReactNode; label: string; viewKey: string; filters?: string }) {
  const { t } = useTranslation();
  const [page, setPage] = useViewState(`${viewKey}.page`, 0, n => Number.isInteger(n) && n >= 0);
  const [rememberedFilters, setRememberedFilters] = useViewState(`${viewKey}.filters`, filters);
  const previousFilters = useRef(rememberedFilters);
  useEffect(() => { if (previousFilters.current !== filters) { setPage(0); previousFilters.current = filters; setRememberedFilters(filters); } }, [filters, setPage, setRememberedFilters]);
  const rows = Children.toArray(children);
  const pages = Math.max(1, Math.ceil(rows.length / 20));
  const currentPage = Math.min(page, pages - 1);
  const scrollRef = useRef<HTMLDivElement>(null);
  useScrollMemory(`${viewKey}.list`, scrollRef);
  return <section aria-label={label} className="rounded-xl bg-surface-container ring-1 ring-white/[0.045] overflow-hidden">
    <div ref={scrollRef} data-work-list-scroll role="region" tabIndex={0} aria-label={label} className="max-h-[65vh] overflow-y-auto custom-scrollbar">
    <div data-work-list-heading aria-hidden="true" className={`${workRowClass} sticky top-0 z-10 hidden lg:grid bg-surface-container-high text-[11px] uppercase tracking-wide text-on-surface-variant`}>{fields.map(field => <span key={field}>{t(`workList.${field}`)}</span>)}</div>
    <ul className="list-none m-0 p-0">{rows.slice(currentPage * 20, (currentPage + 1) * 20)}</ul>
    </div>
    {pages > 1 && <nav aria-label={t('workList.pagination')} className="flex flex-wrap items-center justify-between gap-3 p-4 border-t border-outline-variant/30"><span className="text-xs">{t('documents.pageOf', { page: currentPage + 1, total: pages, count: rows.length })}</span><div className="flex gap-2"><Button variant="outline" size="sm" disabled={currentPage === 0} onClick={() => setPage(currentPage - 1)}>{t('workList.previous')}</Button><Button variant="outline" size="sm" disabled={currentPage >= pages - 1} onClick={() => setPage(currentPage + 1)}>{t('workList.next')}</Button></div></nav>}
  </section>;
}

export function WorkListRow({ identity, status, context, updated, actions, selected }: { identity: ReactNode; status: ReactNode; context: ReactNode; updated: ReactNode; actions: ReactNode; selected?: boolean }) {
  const { t } = useTranslation();
  return <li className={`${workRowClass} ${selected ? 'bg-primary/5 ring-1 ring-inset ring-primary/30' : ''}`}>
    {[identity, status, context, updated, actions].map((content, i) => <div key={fields[i]} className="min-w-0 break-words text-sm"><p className="lg:hidden text-[10px] uppercase text-on-surface-variant mb-1">{t(`workList.${fields[i]}`)}</p>{content}</div>)}
  </li>;
}
