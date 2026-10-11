import { useTranslation } from 'react-i18next';
import { Badge } from './Badge';
import { describeStatus } from '../../lib/uiStatus';
import type { StatusDomain } from '../../lib/uiStatus';

export function StatusBadge({ status, domain = 'task', className }: { status?: string | null; domain?: StatusDomain; className?: string }) {
  const { t } = useTranslation();
  const presentation = describeStatus(status, domain);
  return <Badge tone={presentation.tone} dot className={className}>{presentation.labelKey ? t(presentation.labelKey) : status || t('ui.unknown')}</Badge>;
}
