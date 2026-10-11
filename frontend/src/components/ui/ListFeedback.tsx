import { useTranslation } from 'react-i18next';
import type { ReactNode } from 'react';
import Skeleton from '../Skeleton';
import { EmptyState } from './EmptyState';
import { Button } from './Button';

export function ListFeedback({ loading, error, empty, retry, action, children }: {
  loading?: boolean; error?: boolean; empty?: boolean; retry?: () => void; action?: ReactNode; children: ReactNode;
}) {
  const { t } = useTranslation();
  if (loading) return <div role="status" aria-label={t('workList.loading')} className="space-y-3 p-4"><span className="sr-only">{t('workList.loading')}</span>{[0, 1, 2].map(i => <Skeleton key={i} className="h-20" />)}</div>;
  if (error) return <div role="alert" className="p-6 rounded-xl border border-error/30 bg-error/5 space-y-3"><p>{t('workList.loadFailed')}</p><p className="text-sm text-on-surface-variant">{t('workList.loadFailedHint')}</p>{retry && <Button variant="outline" onClick={retry} icon="refresh">{t('workList.retry')}</Button>}{children}</div>;
  if (empty) return <EmptyState title={t('workList.empty')} description={t('workList.emptyHint')} action={action} />;
  return children;
}
