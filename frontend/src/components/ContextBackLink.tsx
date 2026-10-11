import { Link, useLocation, useInRouterContext } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { validOrigin } from '../lib/contextualNavigation';
export default function ContextBackLink() {
  return useInRouterContext() ? <OriginLink /> : null;
}
function OriginLink() {
  const location = useLocation();
  const { t } = useTranslation();
  const origin = validOrigin(location.state?.origin);
  return origin ? <Link to={origin.path} state={origin.state} className="inline-flex items-center gap-2 mb-4 text-sm text-primary rounded-lg border border-primary/30 px-3 py-2"><span aria-hidden="true" className="material-symbols-outlined text-base">arrow_back</span>{t('detail.returnTo', { screen: origin.label })}</Link> : null;
}
