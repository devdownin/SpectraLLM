import { useDeferredValue, useEffect, useMemo, useState } from 'react';
import type { FC } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useQuery, useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import Skeleton from '../components/Skeleton';
import Tooltip from '../components/Tooltip';
import ConfirmDialog from '../components/ConfirmDialog';
import { gedApi, commentApi } from '../services/api';
import { useFocusTrap } from '../hooks/useFocusTrap';
import type { IngestedFile, IngestedFileSheet, DocumentLifecycle, ArticleComment } from '../types/api';

type DocumentTypeKey = 'pdf' | 'json' | 'xml' | 'docx' | 'doc' | 'txt' | 'avro' | 'other';
type SortMode = 'recent' | 'name' | 'chunks' | 'quality';
type GroupBy = 'none' | 'type' | 'lifecycle' | 'collection';

interface DocumentTypeMeta {
  key: DocumentTypeKey;
  label: string;
  icon: string;
  accentClass: string;
}

const DOCUMENT_TYPES: Record<DocumentTypeKey, DocumentTypeMeta> = {
  pdf:   { key: 'pdf',   label: 'PDF',   icon: 'picture_as_pdf', accentClass: 'text-error border-error/20 bg-error/5' },
  json:  { key: 'json',  label: 'JSON',  icon: 'data_object',    accentClass: 'text-primary border-primary/20 bg-primary/5' },
  xml:   { key: 'xml',   label: 'XML',   icon: 'code_blocks',    accentClass: 'text-secondary border-secondary/20 bg-secondary/5' },
  docx:  { key: 'docx',  label: 'DOCX',  icon: 'description',    accentClass: 'text-primary border-primary/20 bg-primary/5' },
  doc:   { key: 'doc',   label: 'DOC',   icon: 'article',        accentClass: 'text-primary border-primary/20 bg-primary/5' },
  txt:   { key: 'txt',   label: 'TXT',   icon: 'notes',          accentClass: 'text-on-surface-variant border-outline-variant/20 bg-surface-container-high' },
  avro:  { key: 'avro',  label: 'AVRO',  icon: 'schema',         accentClass: 'text-secondary border-secondary/20 bg-secondary/5' },
  other: { key: 'other', label: 'OTHER', icon: 'draft',          accentClass: 'text-on-surface-variant border-outline-variant/20 bg-surface-container-high' },
};

const LIFECYCLE_COLORS: Record<DocumentLifecycle, string> = {
  INGESTED: 'border-outline-variant/30 text-outline',
  QUALIFIED: 'border-secondary/40 text-secondary bg-secondary/5',
  TRAINED: 'border-primary/40 text-primary bg-primary/5',
  ARCHIVED: 'border-on-surface-variant/20 text-on-surface-variant bg-surface-container-low',
};

const LIFECYCLE_BAR_COLORS: Record<string, string> = {
  INGESTED: 'bg-outline-variant',
  QUALIFIED: 'bg-secondary',
  TRAINED: 'bg-primary',
  ARCHIVED: 'bg-on-surface-variant/30',
};

const QUALITY_THRESHOLDS = [
  { label: null,    value: 0 },
  { label: '≥ 25%', value: 0.25 },
  { label: '≥ 50%', value: 0.50 },
  { label: '≥ 75%', value: 0.75 },
];

const PAGE_SIZE = 50;
/** Taille d'un lot chargé depuis le serveur (pagination incrémentale « Load more »). */
const FETCH_SIZE = 200;

function getDocumentType(file: IngestedFile): DocumentTypeMeta {
  const format = file.format.toLowerCase();
  const name = file.fileName.toLowerCase();
  if (format.includes('json') || name.endsWith('.json')) return DOCUMENT_TYPES.json;
  if (format.includes('xml') || name.endsWith('.xml')) return DOCUMENT_TYPES.xml;
  if (format.includes('pdf') || name.endsWith('.pdf')) return DOCUMENT_TYPES.pdf;
  if (format.includes('avro') || name.endsWith('.avro')) return DOCUMENT_TYPES.avro;
  if (name.endsWith('.docx') || format.includes('officedocument.wordprocessingml')) return DOCUMENT_TYPES.docx;
  if (name.endsWith('.doc') || format.includes('msword')) return DOCUMENT_TYPES.doc;
  if (format.includes('text/plain') || name.endsWith('.txt')) return DOCUMENT_TYPES.txt;
  return DOCUMENT_TYPES.other;
}

function getGroupKey(doc: IngestedFile, groupBy: GroupBy): string {
  if (groupBy === 'type') return getDocumentType(doc).key;
  if (groupBy === 'lifecycle') return doc.lifecycle;
  if (groupBy === 'collection') return doc.collectionName ?? '—';
  return '';
}

function getGroupLabel(key: string, groupBy: GroupBy): string {
  if (groupBy === 'type') return DOCUMENT_TYPES[key as DocumentTypeKey]?.label ?? key.toUpperCase();
  return key;
}

// ─────────────────────────────────────────────────────────────────────────────

/** Suppression en attente de confirmation (ligne, fiche ou sélection multiple). */
type PendingDelete =
  | { kind: 'single'; sha: string; name: string; chunks: number }
  | { kind: 'bulk'; shaList: string[] };

const Documents: FC = () => {
  const queryClient = useQueryClient();
  const { t, i18n } = useTranslation();

  // Format de date unique, branché sur la langue de l'interface.
  const formatDate = (value: string): string =>
    new Date(value).toLocaleString(i18n.language, { dateStyle: 'medium', timeStyle: 'short' });

  const [search, setSearch] = useState('');
  const [pendingDelete, setPendingDelete] = useState<PendingDelete | null>(null);
  const [selectedLifecycle, setSelectedLifecycle] = useState<string>('all');
  const [sortMode, setSortMode] = useState<SortMode>('recent');
  const [selectedSha, setSelectedSha] = useState<string | null>(null);
  const [groupBy, setGroupBy] = useState<GroupBy>('none');
  const [selectedFormats, setSelectedFormats] = useState<Set<DocumentTypeKey>>(new Set());
  const [qualityMin, setQualityMin] = useState(0);
  const [bulkSelected, setBulkSelected] = useState<Set<string>>(new Set());
  const [collapsedGroups, setCollapsedGroups] = useState<Set<string>>(new Set());
  const [page, setPage] = useState(0);
  const [newTagInput, setNewTagInput] = useState('');
  const [commentInput, setCommentInput] = useState('');
  const [focusInput, setFocusInput] = useState('');
  const [commentTab, setCommentTab] = useState<'list' | 'add' | 'generate'>('list');
  const deferredSearch = useDeferredValue(search);

  useEffect(() => { setPage(0); }, [deferredSearch, selectedLifecycle, selectedFormats, qualityMin, groupBy, sortMode]);

  // Deep-link : ?doc=<sha256> (ex. depuis une source du Playground) ouvre la fiche.
  const [searchParams, setSearchParams] = useSearchParams();
  useEffect(() => {
    const doc = searchParams.get('doc');
    if (doc) {
      setSelectedSha(doc);
      searchParams.delete('doc');
      setSearchParams(searchParams, { replace: true });
    }
  }, [searchParams, setSearchParams]);

  // Piège de focus + fermeture Échap + restauration du focus sur la fiche document.
  const sheetRef = useFocusTrap<HTMLDivElement>(Boolean(selectedSha), () => setSelectedSha(null));

  // ── Queries ────────────────────────────────────────────────────────────────

  const { data: stats } = useQuery({
    queryKey: ['ged-stats'],
    queryFn: () => gedApi.getStats().then(r => r.data),
  });

  // Pagination serveur incrémentale. On charge des pages successives (lifecycle + recherche
  // appliqués côté serveur) et on les accumule ; le filtrage riche (format/qualité/tri/
  // groupement) reste client-side sur l'ensemble chargé. Remplace l'ancien plafond muet
  // `size:1000` : au-delà, un bouton « Load more » charge la suite et un indicateur montre
  // « N chargés / M au total » plutôt que de tronquer silencieusement.
  const {
    data: docPages,
    isLoading,
    isFetching,
    refetch,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
  } = useInfiniteQuery({
    queryKey: ['ged-documents', selectedLifecycle, deferredSearch],
    initialPageParam: 0,
    queryFn: async ({ pageParam }) => {
      const params: Record<string, unknown> = { page: pageParam, size: FETCH_SIZE };
      if (selectedLifecycle !== 'all') params.lifecycle = selectedLifecycle;
      if (deferredSearch.trim()) params.q = deferredSearch.trim();
      const res = await gedApi.listDocuments(params);
      return res.data as {
        content: IngestedFile[];
        page: number;
        totalPages: number;
        totalElements: number;
      };
    },
    getNextPageParam: (last) => (last.page + 1 < last.totalPages ? last.page + 1 : undefined),
  });

  const documents = useMemo(
    () => docPages?.pages.flatMap((p) => p.content) ?? [],
    [docPages],
  );
  const totalDocuments = docPages?.pages[0]?.totalElements ?? documents.length;

  const { data: sheet, isLoading: isLoadingSheet } = useQuery<IngestedFileSheet>({
    queryKey: ['ged-document', selectedSha],
    queryFn: () => gedApi.getDocument(selectedSha!).then(r => r.data),
    enabled: !!selectedSha,
  });

  // ── Mutations ──────────────────────────────────────────────────────────────

  // Mutations optimistes : le cache React Query (listes paginées + fiche document) est
  // patché immédiatement, l'appel réseau part en arrière-plan. En cas d'échec, l'instantané
  // pris dans onMutate est restauré ; onSettled réconcilie toujours avec le serveur
  // (indispensable pour les listes filtrées par lifecycle, où un document patché doit
  // en réalité changer de liste).

  /** Applique un patch à un ensemble de documents dans TOUS les caches (listes + fiches). */
  const patchDocsInCaches = (shaList: string[], patch: (d: IngestedFile) => IngestedFile) => {
    queryClient.setQueriesData({ queryKey: ['ged-documents'] }, (data: any) => {
      if (!data?.pages) return data;
      return {
        ...data,
        pages: data.pages.map((p: any) => ({
          ...p,
          content: p.content.map((d: IngestedFile) => (shaList.includes(d.sha256) ? patch(d) : d)),
        })),
      };
    });
    shaList.forEach((sha) => {
      queryClient.setQueryData(['ged-document', sha], (s: any) => (s ? patch(s) : s));
    });
  };

  /** Instantané des caches documents, pour rollback si la mutation échoue. */
  const snapshotDocCaches = async () => {
    await queryClient.cancelQueries({ queryKey: ['ged-documents'] });
    await queryClient.cancelQueries({ queryKey: ['ged-document'] });
    return {
      lists: queryClient.getQueriesData({ queryKey: ['ged-documents'] }),
      sheets: queryClient.getQueriesData({ queryKey: ['ged-document'] }),
    };
  };

  type DocCachesSnapshot = Awaited<ReturnType<typeof snapshotDocCaches>>;

  const restoreDocCaches = (snapshot?: DocCachesSnapshot) => {
    snapshot?.lists.forEach(([key, data]) => queryClient.setQueryData(key, data));
    snapshot?.sheets.forEach(([key, data]) => queryClient.setQueryData(key, data));
  };

  const reconcileDocCaches = () => {
    queryClient.invalidateQueries({ queryKey: ['ged-documents'] });
    queryClient.invalidateQueries({ queryKey: ['ged-document'] });
    queryClient.invalidateQueries({ queryKey: ['ged-stats'] });
  };

  const transitionMutation = useMutation({
    mutationFn: ({ sha, lc }: { sha: string; lc: string }) => gedApi.updateLifecycle(sha, lc),
    onMutate: async ({ sha, lc }) => {
      const snapshot = await snapshotDocCaches();
      patchDocsInCaches([sha], (d) => ({ ...d, lifecycle: lc as DocumentLifecycle }));
      return snapshot;
    },
    onSuccess: () => toast.success(t('documents.lifecycleUpdated')),
    onError: (err: any, _vars, snapshot) => {
      restoreDocCaches(snapshot);
      toast.error(t('documents.transitionFailed'), { description: err.response?.data?.error });
    },
    onSettled: reconcileDocCaches,
  });

  const deleteMutation = useMutation({
    mutationFn: (sha: string) => gedApi.deleteDocument(sha),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['ged-documents'] });
      queryClient.invalidateQueries({ queryKey: ['ged-stats'] });
      setSelectedSha(null);
      toast.success(t('documents.docDeleted'));
    },
    onError: (err: any) => toast.error(t('documents.deleteFailed'), { description: err.response?.data?.error }),
  });

  const bulkLifecycleMutation = useMutation({
    mutationFn: ({ sha256List, lifecycle }: { sha256List: string[]; lifecycle: string }) =>
      gedApi.bulkLifecycle(sha256List, lifecycle),
    onMutate: async ({ sha256List, lifecycle }) => {
      const snapshot = await snapshotDocCaches();
      patchDocsInCaches(sha256List, (d) => ({ ...d, lifecycle: lifecycle as DocumentLifecycle }));
      setBulkSelected(new Set());
      return snapshot;
    },
    onSuccess: (_, { sha256List }) => toast.success(t('documents.bulkUpdated', { count: sha256List.length })),
    onError: (_err, _vars, snapshot) => {
      restoreDocCaches(snapshot);
      toast.error(t('documents.bulkUpdateFailed'));
    },
    onSettled: reconcileDocCaches,
  });

  const bulkDeleteMutation = useMutation({
    mutationFn: (sha256List: string[]) => Promise.all(sha256List.map(sha => gedApi.deleteDocument(sha))),
    onSuccess: (_, sha256List) => {
      queryClient.invalidateQueries({ queryKey: ['ged-documents'] });
      queryClient.invalidateQueries({ queryKey: ['ged-stats'] });
      setBulkSelected(new Set());
      if (selectedSha && sha256List.includes(selectedSha)) setSelectedSha(null);
      toast.success(t('documents.bulkDeleted', { count: sha256List.length }));
    },
    onError: () => toast.error(t('documents.bulkDeleteFailed')),
  });

  const addTagMutation = useMutation({
    mutationFn: ({ sha, tags }: { sha: string; tags: string[] }) => gedApi.addTags(sha, tags),
    onMutate: async ({ sha, tags }) => {
      const snapshot = await snapshotDocCaches();
      patchDocsInCaches([sha], (d) => ({ ...d, tags: [...new Set([...(d.tags ?? []), ...tags])] }));
      setNewTagInput('');
      return snapshot;
    },
    onSuccess: () => toast.success(t('documents.tagAdded')),
    onError: (_err, _vars, snapshot) => {
      restoreDocCaches(snapshot);
      toast.error(t('documents.tagAddFailed'));
    },
    onSettled: reconcileDocCaches,
  });

  const removeTagMutation = useMutation({
    mutationFn: ({ sha, tags }: { sha: string; tags: string[] }) => gedApi.removeTags(sha, tags),
    onMutate: async ({ sha, tags }) => {
      const snapshot = await snapshotDocCaches();
      patchDocsInCaches([sha], (d) => ({ ...d, tags: (d.tags ?? []).filter((tag) => !tags.includes(tag)) }));
      return snapshot;
    },
    onSuccess: () => toast.success(t('documents.tagRemoved')),
    onError: (_err, _vars, snapshot) => {
      restoreDocCaches(snapshot);
      toast.error(t('documents.tagRemoveFailed'));
    },
    onSettled: reconcileDocCaches,
  });

  // ── Comments ───────────────────────────────────────────────────────────────

  const { data: comments, isLoading: isLoadingComments } = useQuery<ArticleComment[]>({
    queryKey: ['comments', selectedSha],
    queryFn: () => commentApi.list(selectedSha!).then(r => r.data),
    enabled: !!selectedSha,
  });

  const addCommentMutation = useMutation({
    mutationFn: ({ sha, content }: { sha: string; content: string }) =>
      commentApi.addHuman(sha, content),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['comments', selectedSha] });
      setCommentInput('');
      setCommentTab('list');
      toast.success(t('documents.commentAdded'));
    },
    onError: () => toast.error(t('documents.commentAddFailed')),
  });

  const generateCommentMutation = useMutation({
    mutationFn: ({ sha, focus }: { sha: string; focus: string }) =>
      commentApi.generate(sha, focus),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['comments', selectedSha] });
      setFocusInput('');
      setCommentTab('list');
      toast.success(t('documents.aiCommentGenerated'));
    },
    onError: (err: any) => toast.error(t('documents.aiCommentFailed'),
      { description: err.response?.data?.error ?? t('documents.llmUnavailable') }),
  });

  const rateCommentMutation = useMutation({
    mutationFn: ({ sha, id, rating }: { sha: string; id: number; rating: 'APPROVED' | 'REJECTED' | 'NONE' }) =>
      commentApi.rate(sha, id, rating),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['comments', selectedSha] }),
    onError: () => toast.error(t('documents.ratingFailed')),
  });

  const deleteCommentMutation = useMutation({
    mutationFn: ({ sha, id }: { sha: string; id: number }) => commentApi.delete(sha, id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['comments', selectedSha] });
      toast.success(t('documents.commentDeleted'));
    },
  });

  const exportDpoMutation = useMutation({
    mutationFn: () => commentApi.exportDpo(),
    onSuccess: (res) => toast.success(t('documents.dpoExported', { count: res.data.pairs })),
    onError: () => toast.error(t('documents.dpoExportFailed')),
  });

  // ── Filtering & Sorting ────────────────────────────────────────────────────

  const availableFormats = useMemo(() => {
    const seen = new Set<DocumentTypeKey>();
    (documents ?? []).forEach(doc => seen.add(getDocumentType(doc).key));
    return Array.from(seen).sort();
  }, [documents]);

  const filtered = useMemo(() => {
    return (documents ?? [])
      .filter(doc => {
        if (deferredSearch) {
          const q = deferredSearch.toLowerCase();
          if (!doc.fileName.toLowerCase().includes(q) &&
              !doc.sha256.toLowerCase().includes(q) &&
              !doc.tags.some(t => t.toLowerCase().includes(q)) &&
              !(doc.collectionName ?? '').toLowerCase().includes(q)) return false;
        }
        if (selectedFormats.size > 0 && !selectedFormats.has(getDocumentType(doc).key)) return false;
        if (qualityMin > 0 && (doc.qualityScore ?? 0) < qualityMin) return false;
        return true;
      })
      .sort((a, b) => {
        if (sortMode === 'name') return a.fileName.localeCompare(b.fileName);
        if (sortMode === 'chunks') return b.chunksCreated - a.chunksCreated;
        if (sortMode === 'quality') return (b.qualityScore ?? 0) - (a.qualityScore ?? 0);
        return Date.parse(b.ingestedAt) - Date.parse(a.ingestedAt);
      });
  }, [documents, deferredSearch, selectedFormats, qualityMin, sortMode]);

  const groups = useMemo((): Record<string, IngestedFile[]> => {
    if (groupBy === 'none') return {};
    return filtered.reduce((acc, doc) => {
      const key = getGroupKey(doc, groupBy);
      if (!acc[key]) acc[key] = [];
      acc[key].push(doc);
      return acc;
    }, {} as Record<string, IngestedFile[]>);
  }, [filtered, groupBy]);

  const totalPages = Math.ceil(filtered.length / PAGE_SIZE);
  const paginatedItems = groupBy === 'none' ? filtered.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE) : [];

  // ── Selection helpers ──────────────────────────────────────────────────────

  const allFilteredSha = filtered.map(d => d.sha256);
  const allSelected = allFilteredSha.length > 0 && allFilteredSha.every(sha => bulkSelected.has(sha));
  const someSelected = bulkSelected.size > 0;

  const toggleSelectAll = () => {
    if (allSelected) setBulkSelected(new Set());
    else setBulkSelected(new Set(allFilteredSha));
  };

  const toggleSelect = (sha: string, e: React.MouseEvent) => {
    e.stopPropagation();
    setBulkSelected(prev => {
      const next = new Set(prev);
      if (next.has(sha)) next.delete(sha);
      else next.add(sha);
      return next;
    });
  };

  const toggleGroup = (key: string) => {
    setCollapsedGroups(prev => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const toggleFormat = (fmt: DocumentTypeKey) => {
    setSelectedFormats(prev => {
      const next = new Set(prev);
      if (next.has(fmt)) next.delete(fmt);
      else next.add(fmt);
      return next;
    });
  };

  // ── Row renderer ───────────────────────────────────────────────────────────

  const renderRow = (doc: IngestedFile) => {
    const type = getDocumentType(doc);
    const score = doc.qualityScore ?? 0;
    const isChecked = bulkSelected.has(doc.sha256);
    const isActive = selectedSha === doc.sha256;
    return (
      <div
        key={doc.sha256}
        onClick={() => setSelectedSha(doc.sha256)}
        className={`cv-auto group grid grid-cols-1 lg:grid-cols-[32px_minmax(0,1.5fr)_100px_140px_120px_100px_80px] gap-4 items-center px-4 py-4 bg-surface-container-low hover:bg-surface-container-high transition-all cursor-pointer border-l-2 ${isActive ? 'border-primary bg-surface-container-high' : 'border-transparent'}`}
      >
        <button
          type="button"
          role="checkbox"
          aria-checked={isChecked}
          aria-label={t('documents.selectDoc', { name: doc.fileName })}
          onClick={e => toggleSelect(doc.sha256, e)}
          className="flex justify-center"
        >
          <span className={`w-4 h-4 border flex items-center justify-center transition-all shrink-0 ${isChecked ? 'bg-primary border-primary' : 'border-outline-variant/40 hover:border-primary/50'}`}>
            {isChecked && <span aria-hidden="true" className="material-symbols-outlined text-white text-[11px]">check</span>}
          </span>
        </button>

        <div className="flex items-center gap-4 min-w-0">
          <div className={`w-10 h-10 flex items-center justify-center border shrink-0 ${type.accentClass}`}>
            <span className="material-symbols-outlined text-base">{type.icon}</span>
          </div>
          <div className="min-w-0">
            <p className="font-headline text-sm font-bold tracking-tight truncate">{doc.fileName}</p>
            <div className="flex items-center gap-2 mt-1 flex-wrap">
              <span className="text-[10px] font-mono text-outline">{doc.sha256.slice(0, 8)}</span>
              {doc.collectionName && (
                <span className="text-[10px] border border-primary/20 px-1 text-primary/60 uppercase truncate max-w-[100px]">{doc.collectionName}</span>
              )}
              {doc.tags.slice(0, 2).map(t => (
                <span key={t} className="text-[10px] border border-outline-variant/30 px-1 text-outline uppercase">#{t}</span>
              ))}
              {doc.tags.length > 2 && (
                <span className="text-[10px] text-outline">+{doc.tags.length - 2}</span>
              )}
            </div>
          </div>
        </div>

        <div className="flex justify-center">
          <span className={`text-[10px] font-bold px-2 py-0.5 border uppercase tracking-wider ${LIFECYCLE_COLORS[doc.lifecycle]}`}>
            {doc.lifecycle}
          </span>
        </div>

        <div className="flex items-center gap-2">
          <div
            role="progressbar"
            aria-valuenow={Math.round(score * 100)}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-label={t('documents.qualityAria', {
              pct: (score * 100).toFixed(0),
              level: score > 0.7 ? t('documents.qualityGood') : score > 0.4 ? t('documents.qualityMedium') : t('documents.qualityLow'),
            })}
            className="flex-1 h-1 bg-outline-variant/20 rounded-full overflow-hidden"
          >
            <div
              className={`h-full ${score > 0.7 ? 'bg-primary' : score > 0.4 ? 'bg-secondary' : 'bg-error'}`}
              style={{ width: `${score * 100}%` }}
            />
          </div>
          <span className="text-[11px] font-mono text-on-surface-variant w-8 text-right">{(score * 100).toFixed(0)}%</span>
        </div>

        <div className="text-[11px] text-on-surface-variant font-label uppercase">{formatDate(doc.ingestedAt)}</div>
        <div className="text-right font-headline font-bold text-lg">{doc.chunksCreated}</div>

        <div className="flex justify-end">
          <button
            onClick={e => {
              e.stopPropagation();
              setPendingDelete({ kind: 'single', sha: doc.sha256, name: doc.fileName, chunks: doc.chunksCreated });
            }}
            aria-label={t('documents.deleteDoc', { name: doc.fileName })}
            className="w-8 h-8 flex items-center justify-center text-outline hover:text-error transition-colors"
          >
            <span aria-hidden="true" className="material-symbols-outlined text-sm">delete</span>
          </button>
        </div>
      </div>
    );
  };

  if (isLoading) return <div className="p-8 space-y-4"><Skeleton className="h-10 w-1/4" /><Skeleton className="h-64 w-full" /></div>;

  const total = stats?.total ?? 0;

  return (
    <div className="space-y-6 animate-in fade-in duration-700 pb-32">

      {/* Header */}
      <header className="flex flex-col xl:flex-row xl:items-end justify-between gap-6">
        <div>
          <p className="font-label text-[11px] uppercase tracking-[0.1em] text-on-surface-variant mb-1">{t('documents.kicker')}</p>
          <h2 className="font-headline text-3xl font-bold tracking-tighter uppercase">{t('documents.title')}</h2>
          <p className="text-sm text-on-surface-variant mt-3 max-w-3xl leading-relaxed">
            {t('documents.subtitle')}
          </p>
        </div>
        <div className="flex items-center gap-3">
          <span className="text-[11px] font-label text-on-surface-variant uppercase tracking-widest">
            {t('documents.shownCount', { shown: filtered.length, loaded: documents.length })}
            {totalDocuments > documents.length ? t('documents.ofTotal', { total: totalDocuments }) : ''}
          </span>
          <button
            onClick={() => refetch()}
            className="flex items-center gap-2 border border-outline-variant/20 px-4 py-3 text-[11px] font-label uppercase tracking-widest text-on-surface-variant hover:text-primary transition-colors"
          >
            <span className={`material-symbols-outlined text-sm ${isFetching ? 'animate-spin' : ''}`}>refresh</span>
            {t('documents.sync')}
          </button>
        </div>
      </header>

      {/* Stats Cards */}
      <section className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <div className="bg-surface-container p-5 border-t-2 border-primary">
          <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-2">{t('documents.totalDocs')}</p>
          <p className="font-headline font-bold text-3xl">{total || '—'}</p>
        </div>
        <div className="bg-surface-container p-5 border-t-2 border-secondary">
          <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-2">{t('documents.avgQuality')}</p>
          <p className="font-headline font-bold text-3xl">{stats?.avgQualityScore ? (stats.avgQualityScore * 100).toFixed(0) + '%' : '—'}</p>
        </div>
        <div className="bg-surface-container p-5 border-t-2 border-outline-variant">
          <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-2">{t('documents.totalChunks')}</p>
          <p className="font-headline font-bold text-3xl">{stats?.totalChunks ?? '—'}</p>
        </div>
        <div className="bg-surface-container p-5 border-t-2 border-outline-variant">
          <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-2">{t('documents.lifecycle')}</p>
          <div className="flex gap-0.5 mt-3 overflow-hidden">
            {['INGESTED', 'QUALIFIED', 'TRAINED', 'ARCHIVED'].map(lc => {
              const count = stats?.byLifecycle?.[lc] ?? 0;
              const pct = total > 0 ? (count / total) * 100 : 0;
              return pct > 0 ? (
                <Tooltip key={lc} content={`${lc}: ${count}`}>
                  <div style={{ width: `${pct}%` }} className={`h-2 min-w-[4px] ${LIFECYCLE_BAR_COLORS[lc]}`} />
                </Tooltip>
              ) : null;
            })}
          </div>
          <div className="flex justify-between mt-1">
            {['INGESTED', 'QUALIFIED', 'TRAINED', 'ARCHIVED'].map(lc => (
              <span key={lc} className="text-[10px] text-outline">{stats?.byLifecycle?.[lc] ?? 0}</span>
            ))}
          </div>
        </div>
      </section>

      {/* Filters */}
      <section className="bg-surface-container p-5 space-y-5 border border-outline-variant/10">
        {/* Row 1 */}
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          <div className="space-y-2">
            <label className="text-[10px] font-label uppercase tracking-widest text-on-surface-variant">{t('documents.search')}</label>
            <div className="flex items-center gap-3 border border-outline-variant/20 bg-surface-container-lowest px-4 py-2.5">
              <span className="material-symbols-outlined text-base text-outline">search</span>
              <input
                type="text"
                value={search}
                onChange={e => setSearch(e.target.value)}
                placeholder={t('documents.searchPlaceholder')}
                className="w-full bg-transparent outline-none text-sm font-body placeholder:text-outline"
              />
              {search && (
                <button onClick={() => setSearch('')} className="text-outline hover:text-on-surface">
                  <span className="material-symbols-outlined text-sm">close</span>
                </button>
              )}
            </div>
          </div>
          <div className="space-y-2">
            <label className="text-[10px] font-label uppercase tracking-widest text-on-surface-variant">{t('documents.lifecycle')}</label>
            <div className="flex gap-2">
              {['all', 'INGESTED', 'QUALIFIED', 'TRAINED', 'ARCHIVED'].map(lc => (
                <button
                  key={lc}
                  onClick={() => setSelectedLifecycle(lc)}
                  className={`flex-1 py-2 border text-[10px] font-label uppercase tracking-widest transition-all ${selectedLifecycle === lc ? 'border-primary bg-primary/10 text-primary' : 'border-outline-variant/20 text-on-surface-variant hover:border-primary/30'}`}
                >
                  {lc === 'all' ? t('documents.qualityAll') : lc.slice(0, 1)}
                </button>
              ))}
            </div>
          </div>
          <div className="space-y-2">
            <label className="text-[10px] font-label uppercase tracking-widest text-on-surface-variant">{t('documents.sort')}</label>
            <div className="flex gap-2">
              {(['recent', 'name', 'chunks', 'quality'] as SortMode[]).map(m => (
                <button
                  key={m}
                  onClick={() => setSortMode(m)}
                  className={`flex-1 py-2 border text-[10px] font-label uppercase tracking-widest transition-all ${sortMode === m ? 'border-primary bg-primary/10 text-primary' : 'border-outline-variant/20 text-on-surface-variant hover:border-primary/30'}`}
                >
                  {m === 'recent' ? t('documents.sortDate') : m === 'chunks' ? t('documents.sortChunks') : m === 'quality' ? t('documents.sortQuality') : t('documents.sortName')}
                </button>
              ))}
            </div>
          </div>
        </div>

        {/* Row 2 */}
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          <div className="space-y-2">
            <label className="text-[10px] font-label uppercase tracking-widest text-on-surface-variant">
              {t('documents.docType')} {selectedFormats.size > 0 && <span className="text-primary">{t('documents.activeFilters', { count: selectedFormats.size })}</span>}
            </label>
            <div className="flex flex-wrap gap-2">
              {availableFormats.map(fmt => {
                const meta = DOCUMENT_TYPES[fmt];
                const active = selectedFormats.has(fmt);
                return (
                  <button
                    key={fmt}
                    onClick={() => toggleFormat(fmt)}
                    className={`flex items-center gap-1.5 px-2.5 py-1.5 border text-[10px] font-label uppercase tracking-widest transition-all ${active ? 'border-primary bg-primary/10 text-primary' : 'border-outline-variant/20 text-on-surface-variant hover:border-primary/30'}`}
                  >
                    <span className="material-symbols-outlined text-[12px]">{meta.icon}</span>
                    {meta.label}
                  </button>
                );
              })}
              {selectedFormats.size > 0 && (
                <button onClick={() => setSelectedFormats(new Set())} className="text-[10px] text-outline hover:text-error uppercase tracking-widest px-2">
                  {t('documents.reset')}
                </button>
              )}
            </div>
          </div>

          <div className="space-y-2">
            <label className="text-[10px] font-label uppercase tracking-widest text-on-surface-variant">{t('documents.minQuality')}</label>
            <div className="flex gap-2">
              {QUALITY_THRESHOLDS.map(({ label, value }) => (
                <button
                  key={value}
                  onClick={() => setQualityMin(value)}
                  className={`flex-1 py-2 border text-[10px] font-label uppercase tracking-widest transition-all ${qualityMin === value ? 'border-secondary bg-secondary/10 text-secondary' : 'border-outline-variant/20 text-on-surface-variant hover:border-secondary/30'}`}
                >
                  {label ?? t('documents.qualityAll')}
                </button>
              ))}
            </div>
          </div>

          <div className="space-y-2">
            <label className="text-[10px] font-label uppercase tracking-widest text-on-surface-variant">{t('documents.grouping')}</label>
            <div className="flex gap-2">
              {([
                { key: 'none', label: t('documents.groupNone') },
                { key: 'type', label: t('documents.groupType') },
                { key: 'lifecycle', label: t('documents.groupLifecycle') },
                { key: 'collection', label: t('documents.groupCollection') },
              ] as { key: GroupBy; label: string }[]).map(({ key, label }) => (
                <button
                  key={key}
                  onClick={() => { setGroupBy(key); setCollapsedGroups(new Set()); }}
                  className={`flex-1 py-2 border text-[10px] font-label uppercase tracking-widest transition-all ${groupBy === key ? 'border-primary bg-primary/10 text-primary' : 'border-outline-variant/20 text-on-surface-variant hover:border-primary/30'}`}
                >
                  {label}
                </button>
              ))}
            </div>
          </div>
        </div>
      </section>

      {/* Document List */}
      <section className="space-y-1">
        {/* Column headers */}
        <div className="hidden lg:grid sticky top-0 z-10 bg-background lg:grid-cols-[32px_minmax(0,1.5fr)_100px_140px_120px_100px_80px] gap-4 px-4 py-3 border-b border-outline-variant/10 text-[10px] font-label uppercase tracking-widest text-outline">
          <div className="flex justify-center">
            <button
              type="button"
              role="checkbox"
              aria-checked={allSelected ? true : someSelected ? 'mixed' : false}
              aria-label={t('documents.selectAll')}
              onClick={toggleSelectAll}
              className={`w-4 h-4 border flex items-center justify-center cursor-pointer transition-all ${allSelected ? 'bg-primary border-primary' : 'border-outline-variant/40 hover:border-primary/50'}`}
            >
              {allSelected && <span aria-hidden="true" className="material-symbols-outlined text-white text-[11px]">check</span>}
              {!allSelected && someSelected && <span aria-hidden="true" className="material-symbols-outlined text-primary text-[11px]">remove</span>}
            </button>
          </div>
          <span>{t('documents.colDocument')}</span>
          <span className="text-center">{t('documents.colLifecycle')}</span>
          <span>{t('documents.colQuality')}</span>
          <span>{t('documents.colIngestedOn')}</span>
          <span className="text-right">{t('documents.colChunks')}</span>
          <span className="text-right">{t('documents.colActions')}</span>
        </div>

        <div className="max-h-[70vh] overflow-y-auto custom-scrollbar space-y-1 pr-1">
          {groupBy === 'none' ? (
            <>
              {paginatedItems.map(renderRow)}
              {filtered.length === 0 && (
                <div className="py-20 text-center text-on-surface-variant">
                  <span className="material-symbols-outlined text-4xl block mb-3 opacity-30">search_off</span>
                  <p className="text-sm">{t('documents.noMatch')}</p>
                </div>
              )}
            </>
          ) : (
            Object.entries(groups)
              .sort(([a], [b]) => a.localeCompare(b))
              .map(([key, docs]) => {
                const isCollapsed = collapsedGroups.has(key);
                const label = getGroupLabel(key, groupBy);
                const groupSha = docs.map(d => d.sha256);
                const groupSelected = groupSha.filter(sha => bulkSelected.has(sha)).length;
                const allGroupSelected = groupSelected === docs.length;
                const toggleGroupSelect = (e: React.MouseEvent) => {
                  e.stopPropagation();
                  setBulkSelected(prev => {
                    const next = new Set(prev);
                    if (allGroupSelected) groupSha.forEach(sha => next.delete(sha));
                    else groupSha.forEach(sha => next.add(sha));
                    return next;
                  });
                };
                return (
                  <div key={key}>
                    <div
                      className="flex items-center gap-3 px-4 py-2.5 bg-surface-container border border-outline-variant/10 cursor-pointer hover:bg-surface-container-high transition-colors select-none"
                      onClick={() => toggleGroup(key)}
                    >
                      <button
                        type="button"
                        role="checkbox"
                        aria-checked={allGroupSelected ? true : groupSelected > 0 ? 'mixed' : false}
                        aria-label={t('documents.selectGroup', { name: label })}
                        onClick={toggleGroupSelect}
                        className="flex justify-center"
                        style={{ width: 32 }}
                      >
                        <span className={`w-4 h-4 border flex items-center justify-center transition-all ${allGroupSelected ? 'bg-primary border-primary' : groupSelected > 0 ? 'border-primary bg-primary/20' : 'border-outline-variant/40 hover:border-primary/50'}`}>
                          {allGroupSelected && <span aria-hidden="true" className="material-symbols-outlined text-white text-[11px]">check</span>}
                          {!allGroupSelected && groupSelected > 0 && <span aria-hidden="true" className="material-symbols-outlined text-primary text-[11px]">remove</span>}
                        </span>
                      </button>
                      <span className={`material-symbols-outlined text-base text-on-surface-variant transition-transform ${isCollapsed ? '-rotate-90' : ''}`}>expand_more</span>
                      <p className="font-headline font-bold text-sm uppercase tracking-tight flex-1">{label}</p>
                      <span className="text-[11px] font-label text-on-surface-variant uppercase tracking-widest">{t('documents.docsCount', { count: docs.length })}</span>
                      {groupSelected > 0 && (
                        <span className="text-[10px] font-label text-primary uppercase tracking-widest">{t('documents.selectedCount', { count: groupSelected })}</span>
                      )}
                    </div>
                    {!isCollapsed && (
                      <div className="space-y-px ml-0">
                        {docs.map(renderRow)}
                      </div>
                    )}
                  </div>
                );
              })
          )}
        </div>
      </section>

      {/* Pagination (flat list only) */}
      {groupBy === 'none' && totalPages > 1 && (
        <div className="flex items-center justify-between border border-outline-variant/10 p-4 bg-surface-container">
          <span className="text-[11px] font-label uppercase tracking-widest text-on-surface-variant">
            {t('documents.pageOf', { page: page + 1, total: totalPages, count: filtered.length })}
          </span>
          <div className="flex gap-2">
            <button
              onClick={() => setPage(p => Math.max(0, p - 1))}
              disabled={page === 0}
              className="px-3 py-2 border border-outline-variant/20 text-[10px] font-label uppercase tracking-widest text-on-surface-variant hover:text-primary hover:border-primary/30 transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
            >
              {t('documents.prev')}
            </button>
            {Array.from({ length: Math.min(totalPages, 7) }, (_, i) => {
              const p = totalPages <= 7 ? i : (page < 4 ? i : page + i - 3);
              if (p >= totalPages) return null;
              return (
                <button
                  key={p}
                  onClick={() => setPage(p)}
                  className={`w-8 py-2 border text-[10px] font-label uppercase tracking-widest transition-all ${p === page ? 'border-primary bg-primary/10 text-primary' : 'border-outline-variant/20 text-on-surface-variant hover:border-primary/30'}`}
                >
                  {p + 1}
                </button>
              );
            })}
            <button
              onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}
              disabled={page >= totalPages - 1}
              className="px-3 py-2 border border-outline-variant/20 text-[10px] font-label uppercase tracking-widest text-on-surface-variant hover:text-primary hover:border-primary/30 transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
            >
              {t('documents.next')}
            </button>
          </div>
        </div>
      )}

      {/* Chargement incrémental depuis le serveur : plus de troncature muette à 1000. */}
      {hasNextPage && (
        <div className="flex items-center justify-center gap-3 border border-outline-variant/10 p-4 bg-surface-container">
          <span className="text-[11px] font-label uppercase tracking-widest text-on-surface-variant">
            {t('documents.loadedOf', { loaded: documents.length, total: totalDocuments })}
          </span>
          <button
            onClick={() => fetchNextPage()}
            disabled={isFetchingNextPage}
            className="px-4 py-2 border border-primary/40 text-[10px] font-label uppercase tracking-widest text-primary hover:bg-primary/10 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
          >
            {isFetchingNextPage
              ? t('documents.loadingMore')
              : t('documents.loadMore', { count: Math.min(FETCH_SIZE, totalDocuments - documents.length) })}
          </button>
        </div>
      )}

      {/* Bulk Action Bar */}
      {someSelected && (
        <div className="fixed bottom-6 left-1/2 -translate-x-1/2 z-50 animate-in slide-in-from-bottom duration-300">
          <div className="flex items-center gap-4 bg-surface-container-high border border-primary/30 px-6 py-4 shadow-2xl">
            <span className="text-[11px] font-label uppercase tracking-widest text-primary font-bold">
              {t('documents.selectedCount', { count: bulkSelected.size })}
            </span>
            <div className="w-px h-6 bg-outline-variant/20" />
            <span className="text-[10px] font-label uppercase tracking-widest text-on-surface-variant">{t('documents.moveTo')}</span>
            {(['INGESTED', 'QUALIFIED', 'TRAINED', 'ARCHIVED'] as DocumentLifecycle[]).map(lc => (
              <button
                key={lc}
                onClick={() => bulkLifecycleMutation.mutate({ sha256List: Array.from(bulkSelected), lifecycle: lc })}
                disabled={bulkLifecycleMutation.isPending}
                className={`px-3 py-2 border text-[10px] font-bold tracking-widest uppercase transition-all disabled:opacity-50 ${LIFECYCLE_COLORS[lc]} hover:bg-primary/10`}
              >
                {lc}
              </button>
            ))}
            <div className="w-px h-6 bg-outline-variant/20" />
            <button
              onClick={() => setPendingDelete({ kind: 'bulk', shaList: Array.from(bulkSelected) })}
              disabled={bulkDeleteMutation.isPending}
              className="px-3 py-2 border border-error/30 text-error text-[10px] font-bold tracking-widest uppercase hover:bg-error hover:text-white transition-all disabled:opacity-50"
            >
              {t('documents.delete')}
            </button>
            <button onClick={() => setBulkSelected(new Set())} className="w-8 h-8 flex items-center justify-center text-outline hover:text-on-surface transition-colors">
              <span className="material-symbols-outlined text-sm">close</span>
            </button>
          </div>
        </div>
      )}

      {/* Document Detail Sheet */}
      {selectedSha && (
        <div
          ref={sheetRef}
          tabIndex={-1}
          role="dialog"
          aria-modal="true"
          aria-label={t('documents.sheetAria')}
          className="fixed inset-y-0 right-0 w-full lg:w-[520px] bg-surface-container-high shadow-[-20px_0_40px_rgba(0,0,0,0.5)] z-50 animate-in slide-in-from-right duration-300 border-l border-outline-variant/20 flex flex-col outline-none">
          <header className="p-6 border-b border-outline-variant/20 flex justify-between items-center">
            <div className="min-w-0">
              <p className="text-[10px] font-label uppercase tracking-widest text-outline">{t('documents.sheetTitle')}</p>
              <h3 className="font-headline text-lg font-bold truncate max-w-[380px]">{sheet?.fileName ?? '—'}</h3>
            </div>
            <button onClick={() => setSelectedSha(null)} aria-label={t('documents.closeSheet')} className="w-10 h-10 flex items-center justify-center hover:bg-surface-variant transition-colors shrink-0">
              <span aria-hidden="true" className="material-symbols-outlined">close</span>
            </button>
          </header>

          {isLoadingSheet ? (
            <div className="p-8 space-y-6">
              <Skeleton className="h-20" /><Skeleton className="h-40" /><Skeleton className="h-40" />
            </div>
          ) : sheet && (
            <div className="flex-1 overflow-y-auto p-6 space-y-8 custom-scrollbar">

              {/* Metadata grid */}
              <div className="grid grid-cols-2 gap-3">
                <div className="p-4 bg-surface-container-lowest border-l-2 border-primary">
                  <p className="text-[10px] uppercase tracking-widest text-outline mb-1">{t('documents.status')}</p>
                  <p className="font-headline font-bold text-sm text-primary uppercase">{sheet.lifecycle}</p>
                </div>
                <div className="p-4 bg-surface-container-lowest border-l-2 border-secondary">
                  <p className="text-[10px] uppercase tracking-widest text-outline mb-1">{t('documents.quality')}</p>
                  <p className="font-headline font-bold text-sm text-secondary uppercase">{((sheet.qualityScore ?? 0) * 100).toFixed(0)}%</p>
                </div>
                <div className="p-4 bg-surface-container-lowest border-l-2 border-outline-variant">
                  <p className="text-[10px] uppercase tracking-widest text-outline mb-1">{t('documents.format')}</p>
                  <p className="font-headline font-bold text-sm uppercase truncate">{sheet.format}</p>
                </div>
                <div className="p-4 bg-surface-container-lowest border-l-2 border-outline-variant">
                  <p className="text-[10px] uppercase tracking-widest text-outline mb-1">{t('documents.chunks')}</p>
                  <p className="font-headline font-bold text-sm">{sheet.chunksCreated}</p>
                </div>
                <div className="p-4 bg-surface-container-lowest border-l-2 border-outline-variant">
                  <p className="text-[10px] uppercase tracking-widest text-outline mb-1">{t('documents.version')}</p>
                  <p className="font-headline font-bold text-sm">v{sheet.version ?? 1}</p>
                </div>
                <div className="p-4 bg-surface-container-lowest border-l-2 border-outline-variant">
                  <p className="text-[10px] uppercase tracking-widest text-outline mb-1">{t('documents.ingestedOn')}</p>
                  <p className="font-headline font-bold text-sm">{formatDate(sheet.ingestedAt)}</p>
                </div>
                {/* Date d'archivage : posée à la transition vers ARCHIVED, base de la purge
                    de rétention — l'utilisateur voit depuis quand le document est archivé. */}
                {sheet.archivedAt && (
                  <div className="col-span-2 p-4 bg-surface-container-lowest border-l-2 border-error/40">
                    <p className="text-[10px] uppercase tracking-widest text-outline mb-1">{t('documents.archivedOn')}</p>
                    <p className="font-headline font-bold text-sm text-error/80">{formatDate(sheet.archivedAt)}</p>
                  </div>
                )}
                {sheet.collectionName && (
                  <div className="col-span-2 p-4 bg-surface-container-lowest border-l-2 border-primary/40">
                    <p className="text-[10px] uppercase tracking-widest text-outline mb-1">{t('documents.collection')}</p>
                    <p className="font-headline font-bold text-sm text-primary/80 truncate">{sheet.collectionName}</p>
                  </div>
                )}
                <div className="col-span-2 p-4 bg-surface-container-lowest border-l-2 border-outline-variant/40">
                  <p className="text-[10px] uppercase tracking-widest text-outline mb-1">SHA-256</p>
                  <p className="font-mono text-[11px] text-on-surface-variant break-all">{sheet.sha256}</p>
                </div>
              </div>

              {/* Lifecycle transitions */}
              <div className="space-y-3">
                <h4 className="text-[11px] font-bold uppercase tracking-widest text-outline">{t('documents.transitions')}</h4>
                <div className="flex flex-wrap gap-2">
                  {(['INGESTED', 'QUALIFIED', 'TRAINED', 'ARCHIVED'] as DocumentLifecycle[]).map(lc => (
                    <button
                      key={lc}
                      disabled={lc === sheet.lifecycle || transitionMutation.isPending}
                      onClick={() => transitionMutation.mutate({ sha: sheet.sha256, lc })}
                      className={`px-3 py-2 border text-[10px] font-bold tracking-widest uppercase transition-all ${lc === sheet.lifecycle ? 'opacity-30 cursor-not-allowed border-outline' : 'border-primary/30 text-primary hover:bg-primary/10'}`}
                    >
                      {lc === sheet.lifecycle ? '✓ ' : ''}{lc}
                    </button>
                  ))}
                </div>
              </div>

              {/* Tag management */}
              <div className="space-y-3">
                <h4 className="text-[11px] font-bold uppercase tracking-widest text-outline">{t('documents.tags')}</h4>
                <div className="flex flex-wrap gap-2 min-h-[2rem]">
                  {sheet.tags.length === 0 && (
                    <p className="text-xs italic text-outline">{t('documents.noTags')}</p>
                  )}
                  {sheet.tags.map(tag => (
                    <span key={tag} className="flex items-center gap-1 text-[10px] border border-outline-variant/30 px-2 py-1 text-outline uppercase">
                      #{tag}
                      <button
                        onClick={() => removeTagMutation.mutate({ sha: sheet.sha256, tags: [tag] })}
                        disabled={removeTagMutation.isPending}
                        className="hover:text-error transition-colors ml-1"
                      >
                        <span className="material-symbols-outlined text-[11px]">close</span>
                      </button>
                    </span>
                  ))}
                </div>
                <div className="flex gap-2">
                  <input
                    type="text"
                    value={newTagInput}
                    onChange={e => setNewTagInput(e.target.value)}
                    onKeyDown={e => {
                      if (e.key === 'Enter' && newTagInput.trim()) {
                        addTagMutation.mutate({ sha: sheet.sha256, tags: [newTagInput.trim().toLowerCase()] });
                      }
                    }}
                    placeholder={t('documents.newTag')}
                    className="flex-1 bg-surface-container-lowest border border-outline-variant/20 px-3 py-2 text-sm outline-none focus:border-primary/50 font-body placeholder:text-outline"
                  />
                  <button
                    onClick={() => {
                      if (newTagInput.trim()) addTagMutation.mutate({ sha: sheet.sha256, tags: [newTagInput.trim().toLowerCase()] });
                    }}
                    disabled={!newTagInput.trim() || addTagMutation.isPending}
                    className="px-4 py-2 bg-primary/10 border border-primary/30 text-primary text-[10px] font-bold tracking-widest uppercase hover:bg-primary/20 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                  >
                    {t('documents.addTag')}
                  </button>
                </div>
              </div>

              {/* Model links */}
              <div className="space-y-3">
                <h4 className="text-[11px] font-bold uppercase tracking-widest text-outline">{t('documents.modelLinks')}</h4>
                {sheet.modelLinks.length === 0 ? (
                  <p className="text-xs italic text-outline">{t('documents.noModelLinks')}</p>
                ) : (
                  <div className="space-y-2">
                    {sheet.modelLinks.map((l, i) => (
                      <div key={i} className="flex justify-between items-center p-3 bg-surface-container-lowest border border-outline-variant/10">
                        <div className="flex items-center gap-3">
                          <span className="material-symbols-outlined text-sm text-primary">hub</span>
                          <span className="text-xs font-bold truncate max-w-[240px]">{l.model}</span>
                        </div>
                        <span className="text-[10px] font-bold uppercase text-outline shrink-0">{l.type.replace('_', ' ')}</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              {/* Comments — RAG generation + DPO rating */}
              <div className="space-y-3">
                <div className="flex items-center justify-between">
                  <h4 className="text-[11px] font-bold uppercase tracking-widest text-outline">{t('documents.comments')}</h4>
                  <div className="flex gap-1">
                    {(['list', 'add', 'generate'] as const).map(tab => (
                      <button
                        key={tab}
                        onClick={() => setCommentTab(tab)}
                        className={`px-2 py-1 text-[10px] font-bold uppercase tracking-widest border transition-all ${
                          commentTab === tab
                            ? 'border-primary/60 text-primary bg-primary/10'
                            : 'border-outline-variant/20 text-outline hover:border-primary/30 hover:text-primary'
                        }`}
                      >
                        {tab === 'list' ? t('documents.tabList') : tab === 'add' ? t('documents.tabManual') : t('documents.tabAi')}
                      </button>
                    ))}
                    <Tooltip content={t('documents.exportDpoTooltip')}>
                      <button
                        onClick={() => exportDpoMutation.mutate()}
                        disabled={exportDpoMutation.isPending}
                        className="px-2 py-1 text-[10px] font-bold uppercase border border-secondary/30 text-secondary hover:bg-secondary/10 transition-all disabled:opacity-40"
                      >
                        DPO↓
                      </button>
                    </Tooltip>
                  </div>
                </div>

                {commentTab === 'list' && (
                  <div className="space-y-2">
                    {isLoadingComments ? (
                      <Skeleton className="h-16" />
                    ) : !comments?.length ? (
                      <p className="text-xs italic text-outline">{t('documents.noComments')}</p>
                    ) : (
                      comments.map(c => (
                        <div key={c.id} className={`p-3 border text-xs space-y-2 ${
                          c.rating === 'APPROVED' ? 'border-primary/30 bg-primary/5' :
                          c.rating === 'REJECTED' ? 'border-error/20 bg-error/5' :
                          'border-outline-variant/15 bg-surface-container-lowest'
                        }`}>
                          <div className="flex items-start justify-between gap-2">
                            <div className="flex items-center gap-2 shrink-0">
                              <span className={`text-[10px] font-bold uppercase px-1.5 py-0.5 border ${
                                c.type === 'AI_GENERATED'
                                  ? 'border-secondary/40 text-secondary bg-secondary/10'
                                  : 'border-outline-variant/30 text-outline'
                              }`}>
                                {c.type === 'AI_GENERATED' ? '✦ AI' : '👤'}
                              </span>
                              <span className="text-[10px] text-outline">{c.author}</span>
                            </div>
                            <span className="text-[10px] font-mono text-outline shrink-0">{formatDate(c.createdAt)}</span>
                          </div>
                          {c.focus && (
                            <p className="text-[10px] italic text-on-surface-variant border-l-2 border-secondary/30 pl-2">
                              {t('documents.focusLabel', { focus: c.focus })}
                            </p>
                          )}
                          <p className="text-[11px] text-on-surface leading-relaxed whitespace-pre-line">{c.content}</p>
                          {c.type === 'AI_GENERATED' && (
                            <div className="flex items-center gap-2 pt-1">
                              <span className="text-[10px] uppercase text-outline tracking-widest">{t('documents.dpoRating')}</span>
                              {(['APPROVED', 'NONE', 'REJECTED'] as const).map(r => (
                                <button
                                  key={r}
                                  onClick={() => rateCommentMutation.mutate({ sha: sheet!.sha256, id: c.id, rating: r })}
                                  disabled={rateCommentMutation.isPending}
                                  aria-pressed={c.rating === r}
                                  aria-label={r === 'APPROVED' ? t('documents.approveAria') : r === 'REJECTED' ? t('documents.rejectAria') : t('documents.noRatingAria')}
                                  className={`px-2 py-0.5 text-[10px] font-bold uppercase border transition-all disabled:opacity-40 flex items-center ${
                                    c.rating === r
                                      ? r === 'APPROVED' ? 'border-primary bg-primary/20 text-primary'
                                        : r === 'REJECTED' ? 'border-error bg-error/20 text-error'
                                        : 'border-outline bg-outline/10 text-outline'
                                      : 'border-outline-variant/20 text-outline hover:border-outline'
                                  }`}
                                >
                                  {r === 'APPROVED'
                                    ? <span aria-hidden="true" className="material-symbols-outlined text-[13px]">thumb_up</span>
                                    : r === 'REJECTED'
                                      ? <span aria-hidden="true" className="material-symbols-outlined text-[13px]">thumb_down</span>
                                      : '—'}
                                </button>
                              ))}
                              <button
                                onClick={() => deleteCommentMutation.mutate({ sha: sheet!.sha256, id: c.id })}
                                disabled={deleteCommentMutation.isPending}
                                className="ml-auto text-[10px] text-outline hover:text-error transition-colors"
                              >
                                <span className="material-symbols-outlined text-[12px]">delete</span>
                              </button>
                            </div>
                          )}
                          {c.type === 'HUMAN' && (
                            <div className="flex justify-end">
                              <button
                                onClick={() => deleteCommentMutation.mutate({ sha: sheet!.sha256, id: c.id })}
                                disabled={deleteCommentMutation.isPending}
                                className="text-[10px] text-outline hover:text-error transition-colors"
                              >
                                <span className="material-symbols-outlined text-[12px]">delete</span>
                              </button>
                            </div>
                          )}
                        </div>
                      ))
                    )}
                  </div>
                )}

                {commentTab === 'add' && (
                  <div className="space-y-2">
                    <textarea
                      value={commentInput}
                      onChange={e => setCommentInput(e.target.value)}
                      placeholder={t('documents.writeComment')}
                      rows={4}
                      className="w-full bg-surface-container-lowest border border-outline-variant/20 px-3 py-2 text-sm outline-none focus:border-primary/50 font-body placeholder:text-outline resize-none"
                    />
                    <button
                      onClick={() => {
                        if (commentInput.trim() && sheet) {
                          addCommentMutation.mutate({ sha: sheet.sha256, content: commentInput.trim() });
                        }
                      }}
                      disabled={!commentInput.trim() || addCommentMutation.isPending}
                      className="w-full py-2 bg-primary/10 border border-primary/30 text-primary text-[10px] font-bold uppercase tracking-widest hover:bg-primary/20 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                    >
                      {addCommentMutation.isPending ? t('documents.saving') : t('documents.addComment')}
                    </button>
                  </div>
                )}

                {commentTab === 'generate' && (
                  <div className="space-y-2">
                    <p className="text-[10px] text-on-surface-variant">
                      {t('documents.generateHint')}
                    </p>
                    <input
                      type="text"
                      value={focusInput}
                      onChange={e => setFocusInput(e.target.value)}
                      placeholder={t('documents.focusPlaceholder')}
                      className="w-full bg-surface-container-lowest border border-outline-variant/20 px-3 py-2 text-sm outline-none focus:border-secondary/50 font-body placeholder:text-outline"
                    />
                    <button
                      onClick={() => {
                        if (sheet) {
                          generateCommentMutation.mutate({ sha: sheet.sha256, focus: focusInput.trim() });
                        }
                      }}
                      disabled={generateCommentMutation.isPending}
                      className="w-full py-2 bg-secondary/10 border border-secondary/30 text-secondary text-[10px] font-bold uppercase tracking-widest hover:bg-secondary/20 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                    >
                      {generateCommentMutation.isPending
                        ? t('documents.generating')
                        : t('documents.generateBtn')}
                    </button>
                    <p className="text-[10px] text-outline">
                      {t('documents.dpoExportHint')}
                    </p>
                  </div>
                )}
              </div>

              {/* Audit Trail */}
              <div className="space-y-3">
                <h4 className="text-[11px] font-bold uppercase tracking-widest text-outline">{t('documents.auditTrail')}</h4>
                <div className="space-y-1 relative before:absolute before:left-[11px] before:top-2 before:bottom-2 before:w-px before:bg-outline-variant/20">
                  {sheet.auditTrail.map((a, i) => (
                    <div key={i} className="pl-8 relative py-3 group">
                      <div className="absolute left-[8px] top-5 w-2 h-2 rounded-full bg-outline-variant group-first:bg-primary" />
                      <div className="flex justify-between items-start mb-1">
                        <p className="text-[11px] font-bold uppercase tracking-tighter">{a.action.replace(/_/g, ' ')}</p>
                        <p className="text-[10px] text-outline font-mono shrink-0 ml-2">{formatDate(a.timestamp)}</p>
                      </div>
                      <p className="text-[11px] text-on-surface-variant italic">{t('documents.byActor', { actor: a.actor })}</p>
                      {a.details && <p className="text-[10px] text-outline mt-1 font-mono break-all">{a.details}</p>}
                    </div>
                  ))}
                </div>
              </div>
            </div>
          )}

          <footer className="p-6 border-t border-outline-variant/20">
            <button
              onClick={() => {
                if (sheet) setPendingDelete({ kind: 'single', sha: sheet.sha256, name: sheet.fileName, chunks: sheet.chunksCreated });
              }}
              disabled={deleteMutation.isPending || !sheet}
              className="w-full py-3 bg-error/10 border border-error/30 text-error font-bold text-[11px] tracking-widest uppercase hover:bg-error hover:text-white transition-all disabled:opacity-50"
            >
              {t('documents.deletePermanently')}
            </button>
          </footer>
        </div>
      )}

      {/* Backdrop */}
      {selectedSha && (
        <div
          className="fixed inset-0 bg-black/60 backdrop-blur-sm z-40 animate-in fade-in duration-300"
          onClick={() => setSelectedSha(null)}
        />
      )}

      {/* Confirmation de suppression (ligne, fiche ou sélection multiple) */}
      <ConfirmDialog
        open={pendingDelete !== null}
        title={pendingDelete?.kind === 'bulk'
          ? t('confirm.deleteBulkTitle', { count: pendingDelete.shaList.length })
          : t('confirm.deleteDocTitle')}
        message={pendingDelete?.kind === 'bulk'
          ? t('confirm.deleteBulkMessage')
          : pendingDelete
            ? t('confirm.deleteDocMessage', { name: pendingDelete.name, chunks: pendingDelete.chunks })
            : ''}
        confirmLabel={t('confirm.delete')}
        busy={deleteMutation.isPending || bulkDeleteMutation.isPending}
        onCancel={() => setPendingDelete(null)}
        onConfirm={() => {
          if (!pendingDelete) return;
          if (pendingDelete.kind === 'bulk') bulkDeleteMutation.mutate(pendingDelete.shaList);
          else deleteMutation.mutate(pendingDelete.sha);
          setPendingDelete(null);
        }}
      />
    </div>
  );
};

export default Documents;
