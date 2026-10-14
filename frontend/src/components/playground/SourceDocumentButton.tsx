import { useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { toast } from 'sonner';
import { resolveSourceDocument } from '../../lib/sourceDocument';
export default function SourceDocumentButton({ fileName, collection, focusId }: { fileName: string; collection?: string; focusId?: string }) {
  const { t } = useTranslation();
  const location = useLocation();
  const navigate = useNavigate();
  const [opening, setOpening] = useState(false);
  return <button type="button" disabled={opening} className="text-xs text-primary border border-primary/30 rounded-lg px-3 py-2 disabled:opacity-50" onClick={async () => {
    setOpening(true);
    try {
      const sha = await resolveSourceDocument(fileName, collection);
      navigate(`/documents?doc=${encodeURIComponent(sha)}`, { state: { origin: { path: location.pathname + location.search, label: t('nav.playground'), state: { sourceFocusId: focusId } } } });
    } catch (error) {
      toast.error(t(error instanceof Error && error.message === 'ambiguous' ? 'detail.ambiguousSource' : error instanceof Error && error.message === 'missing' ? 'playground.docNotFound' : 'playground.docOpenFailed'));
    } finally { setOpening(false); }
  }}>{t(opening ? 'detail.opening' : 'detail.openDocument')}</button>;
}
