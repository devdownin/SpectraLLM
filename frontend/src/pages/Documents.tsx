import { useDeferredValue, useEffect, useMemo, useState } from 'react';
import type { FC } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useQuery, useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import Skeleton from '../components/Skeleton';
import Tooltip from '../components/Tooltip';
import ConfirmDialog from '../components/ConfirmDialog';
import { Badge, EmptyState, PageHeader, Button } from '../components/ui';
import { gedApi, commentApi } from '../services/api';
import { useFocusTrap } from '../hooks/useFocusTrap';
import {
  DOCUMENT_TYPES, LIFECYCLE_BAR_COLORS, LIFECYCLE_COLORS, LIFECYCLE_TONES,
  QUALITY_THRESHOLDS, getDocumentType, getGroupKey, getGroupLabel,
} from '../lib/documentTaxonomy';
import type { DocumentTypeKey, GroupBy, SortMode } from '../lib/documentTaxonomy';
import type {
  IngestedFile, IngestedFileSheet, DocumentLifecycle, ArticleComment,
  ClassificationConfig, ClassificationTask,
} from '../types/api';

const PAGE_SIZE = 50;
/** Taille d'un lot chargé depuis le serveur (pagination incrémentale « Load more »). */
const FETCH_SIZE = 200;

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
  // R8 — classification : filtre par catégorie et suivi du lot en cours.
  const [selectedCategory, setSelectedCategory] = useState<string>('all');
  const [classifyTaskId, setClassifyTaskId] = useState<string | null>(null);
  const [commentInput, setCommentInput] = useState('');
  const [focusInput, setFocusInput] = useState('');
  const [commentTab, setCommentTab] = useState<'list' | 'add' | 'generate'>('list');
  const deferredSearch = useDeferredValue(search);

  useEffect(() => { setPage(0); }, [deferredSearch, selectedLifecycle, selectedFormats, qualityMin, groupBy, sortMode, selectedCategory]);

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

  // ── Classification automatique (R8) ────────────────────────────────────────

  const { data: classificationConfig } = useQuery<ClassificationConfig>({
    queryKey: ['ged-classification-config'],
    queryFn: () => gedApi.getClassificationConfig().then(r => r.data),
    staleTime: 5 * 60 * 1000, // configuration serveur : inutile de la resonder en continu
  });

  const classifyMutation = useMutation({
    mutationFn: ({ sha, force }: { sha: string; force: boolean }) => gedApi.classify(sha, force),
    onSuccess: (res) => {
      const { categories, reused } = res.data as { categories: string[]; reused: boolean };
      // `reused` : le serveur a renvoyé la classification existante sans rappeler le LLM.
      toast.success(
        reused ? t('documents.alreadyClassified') : t('documents.classified'),
        { description: categories.join(' · ') },
      );
      reconcileDocCaches();
    },
    onError: (err: any) => toast.error(t('documents.classifyFailed'),
      { description: err.response?.data?.error ?? t('documents.llmUnavailable') }),
  });

  const bulkClassifyMutation = useMutation({
    mutationFn: ({ sha256List, force }: { sha256List: string[] | null; force: boolean }) =>
      gedApi.bulkClassify(sha256List, force),
    onSuccess: (res) => {
      setClassifyTaskId(res.data.taskId);
      toast.success(t('documents.bulkClassifyStarted'));
    },
    onError: (err: any) => toast.error(t('documents.bulkClassifyFailed'),
      { description: err.response?.data?.error }),
  });

  // Suivi du lot : on interroge la progression tant que la tâche tourne, puis on s'arrête.
  const { data: classifyTask } = useQuery<ClassificationTask>({
    queryKey: ['ged-classification-task', classifyTaskId],
    queryFn: () => gedApi.getClassificationTask(classifyTaskId!).then(r => r.data),
    enabled: !!classifyTaskId,
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return status === 'PENDING' || status === 'PROCESSING' ? 2000 : false;
    },
  });

  // Le lot terminé rafraîchit la liste (les catégories viennent d'arriver) puis se retire
  // de l'écran ; sans cela la barre de progression resterait figée à 100 %.
  useEffect(() => {
    if (!classifyTask) return;
    if (classifyTask.status === 'PENDING' || classifyTask.status === 'PROCESSING') return;
    reconcileDocCaches();
    if (classifyTask.status === 'COMPLETED') {
      toast.success(t('documents.bulkClassifyDone', {
        succeeded: classifyTask.succeeded, failed: classifyTask.failed,
      }));
    }
    const timer = setTimeout(() => setClassifyTaskId(null), 4000);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [classifyTask?.status]);

  const cancelClassifyMutation = useMutation({
    mutationFn: (taskId: string) => gedApi.cancelClassificationTask(taskId),
    onSuccess: () => toast.success(t('documents.bulkClassifyCancelled')),
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

  /**
   * Catégories proposées au filtre : la taxonomie configurée, complétée par celles
   * réellement rencontrées dans le corpus (taxonomie ouverte, ou taxonomie modifiée
   * après une première classification).
   */
  const availableCategories = useMemo(() => {
    const seen = new Set<string>(classificationConfig?.taxonomy ?? []);
    (documents ?? []).forEach(doc => (doc.categories ?? []).forEach(c => seen.add(c)));
    return Array.from(seen).sort();
  }, [documents, classificationConfig]);

  const filtered = useMemo(() => {
    return (documents ?? [])
      .filter(doc => {
        if (deferredSearch) {
          const q = deferredSearch.toLowerCase();
          if (!doc.fileName.toLowerCase().includes(q) &&
              !doc.sha256.toLowerCase().includes(q) &&
              !doc.tags.some(t => t.toLowerCase().includes(q)) &&
              !(doc.categories ?? []).some(c => c.toLowerCase().includes(q)) &&
              !(doc.collectionName ?? '').toLowerCase().includes(q)) return false;
        }
        if (selectedFormats.size > 0 && !selectedFormats.has(getDocumentType(doc).key)) return false;
        if (qualityMin > 0 && (doc.qualityScore ?? 0) < qualityMin) return false;
        if (selectedCategory === 'unclassified') {
          if ((doc.categories ?? []).length > 0) return false;
        } else if (selectedCategory !== 'all' && !(doc.categories ?? []).includes(selectedCategory)) {
          return false;
        }
        return true;
      })
      .sort((a, b) => {
        if (sortMode === 'name') return a.fileName.localeCompare(b.fileName);
        if (sortMode === 'chunks') return b.chunksCreated - a.chunksCreated;
        if (sortMode === 'quality') return (b.qualityScore ?? 0) - (a.qualityScore ?? 0);
        return Date.parse(b.ingestedAt) - Date.parse(a.ingestedAt);
      });
  }, [documents, deferredSearch, selectedFormats, qualityMin, sortMode, selectedCategory]);

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
        className={`cv-auto group grid grid-cols-1 lg:grid-cols-[32px_minmax(0,1.5fr)_100px_140px_120px_100px_80px] gap-4 items-center px-4 py-4 transition-colors cursor-pointer border-l-2 ${isActive ? 'border-primary bg-surface-container-high/70' : 'border-transparent hover:bg-surface-container-high/40'}`}
      >
        <button
          type="button"
          role="checkbox"
          aria-checked={isChecked}
          aria-label={t('documents.selectDoc', { name: doc.fileName })}
          onClick={e => toggleSelect(doc.sha256, e)}
          className="flex justify-center"
        >
          <span className={`w-4 h-4 rounded border flex items-center justify-center transition-all shrink-0 ${isChecked ? 'bg-primary border-primary' : 'border-outline-variant/40 hover:border-primary/50'}`}>
            {isChecked && <span aria-hidden="true" className="material-symbols-outlined text-white text-[11px]">check</span>}
          </span>
        </button>

        <div className="flex items-center gap-4 min-w-0">
          <div className={`w-10 h-10 rounded-lg flex items-center justify-center border shrink-0 ${type.accentClass}`}>
            <span className="material-symbols-outlined text-base">{type.icon}</span>
          </div>
          <div className="min-w-0">
            <p className="font-headline text-sm font-bold tracking-tight truncate">{doc.fileName}</p>
            <div className="flex items-center gap-2 mt-1 flex-wrap">
              <span className="text-[10px] font-mono text-outline">{doc.sha256.slice(0, 8)}</span>
              {doc.collectionName && (
                <span className="text-[10px] border border-primary/20 px-1 text-primary/60 uppercase truncate max-w-[100px]">{doc.collectionName}</span>
              )}
              {/* Catégories LLM (R8) — visuellement distinctes des tags manuels (#) pour
                  qu'on voie d'un coup d'œil ce qui a été étiqueté par la machine. */}
              {(doc.categories ?? []).slice(0, 2).map(c => (
                <span key={c} className="text-[10px] border border-secondary/40 bg-secondary/5 px-1 text-secondary uppercase">{c}</span>
              ))}
              {(doc.categories ?? []).length > 2 && (
                <span className="text-[10px] text-secondary/70">+{(doc.categories ?? []).length - 2}</span>
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
          <Badge tone={LIFECYCLE_TONES[doc.lifecycle]} className={doc.lifecycle === 'ARCHIVED' ? 'opacity-60' : undefined}>
            {doc.lifecycle}
          </Badge>
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
  // R8 — documents restant à classifier, tirés des stats serveur (et non de la page
  // chargée) : le bouton d'en-tête doit refléter tout le fonds, pas l'échantillon visible.
  const pendingClassification = stats?.classification?.unclassified ?? 0;

  return (
    <div className="space-y-6 animate-in fade-in duration-700 pb-32">

      {/* Header */}
      <PageHeader
        kicker={t('documents.kicker')}
        title={t('documents.title')}
        description={t('documents.subtitle')}
        actions={
          <>
            <span className="text-[12px] text-on-surface-variant">
              {t('documents.shownCount', { shown: filtered.length, loaded: documents.length })}
              {totalDocuments > documents.length ? t('documents.ofTotal', { total: totalDocuments }) : ''}
            </span>
            {/* R8 — action principale de l'écran : lancer la classification du fonds. Elle vit
                dans l'en-tête et non dans le panneau de filtres, où elle était invisible.
                Le libellé porte le reste à faire : « Classifier (23) » dit à la fois quoi
                et combien, et disparaît quand tout est déjà classifié. */}
            {classificationConfig?.enabled && (
              <Button
                variant={pendingClassification > 0 ? 'primary' : 'outline'}
                size="sm"
                disabled={bulkClassifyMutation.isPending || !!classifyTaskId || pendingClassification === 0}
                title={t('documents.classifyHint', { model: classificationConfig.model })}
                onClick={() => bulkClassifyMutation.mutate({ sha256List: null, force: false })}
              >
                <span aria-hidden="true" className={`material-symbols-outlined text-[16px] ${classifyTaskId ? 'animate-spin' : ''}`}>
                  {classifyTaskId ? 'progress_activity' : 'auto_awesome'}
                </span>
                {pendingClassification > 0
                  ? t('documents.classifyPending', { count: pendingClassification })
                  : t('documents.allClassified')}
              </Button>
            )}
            <Button variant="outline" size="sm" onClick={() => refetch()}>
              <span aria-hidden="true" className={`material-symbols-outlined text-[16px] ${isFetching ? 'animate-spin' : ''}`}>refresh</span>
              {t('documents.sync')}
            </Button>
          </>
        }
      />

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

        {/* Row 3 — Classification automatique (R8) */}
        {classificationConfig?.enabled && (
          <div className="space-y-3 pt-4 border-t border-outline-variant/10">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div className="flex items-baseline gap-3">
                <label className="text-[10px] font-label uppercase tracking-widest text-on-surface-variant">
                  {t('documents.category')}
                </label>
                {stats?.classification && (
                  <span className="text-[10px] text-outline">
                    {t('documents.classificationCoverage', {
                      classified: stats.classification.classified,
                      total: (stats.classification.classified ?? 0) + (stats.classification.unclassified ?? 0),
                    })}
                  </span>
                )}
              </div>
              {/* Le déclencheur global vit désormais dans l'en-tête ; il ne reste ici que
                  le contexte (modèle utilisé), pour ne pas offrir deux boutons identiques. */}
              <span className="text-[10px] text-outline uppercase tracking-widest">
                {t('documents.classifierModel', { model: classificationConfig.model })}
              </span>
            </div>

            <div className="flex flex-wrap gap-2">
              <button
                onClick={() => setSelectedCategory('all')}
                className={`px-2.5 py-1.5 border text-[10px] font-label uppercase tracking-widest transition-all ${selectedCategory === 'all' ? 'border-secondary bg-secondary/10 text-secondary' : 'border-outline-variant/20 text-on-surface-variant hover:border-secondary/30'}`}
              >
                {t('documents.qualityAll')}
              </button>
              <button
                onClick={() => setSelectedCategory('unclassified')}
                className={`px-2.5 py-1.5 border text-[10px] font-label uppercase tracking-widest transition-all ${selectedCategory === 'unclassified' ? 'border-error bg-error/10 text-error' : 'border-outline-variant/20 text-on-surface-variant hover:border-error/30'}`}
              >
                {t('documents.unclassified')}
              </button>
              {availableCategories.map(cat => (
                <button
                  key={cat}
                  onClick={() => setSelectedCategory(cat)}
                  className={`px-2.5 py-1.5 border text-[10px] font-label uppercase tracking-widest transition-all ${selectedCategory === cat ? 'border-secondary bg-secondary/10 text-secondary' : 'border-outline-variant/20 text-on-surface-variant hover:border-secondary/30'}`}
                >
                  {cat}
                </button>
              ))}
            </div>

            {/* Progression du lot en cours */}
            {classifyTask && (
              <div className="flex items-center gap-3 bg-surface-container-lowest border border-outline-variant/20 px-4 py-2.5">
                <span className={`material-symbols-outlined text-[16px] text-secondary ${classifyTask.status === 'PROCESSING' ? 'animate-spin' : ''}`}>
                  {classifyTask.status === 'COMPLETED' ? 'check_circle' : classifyTask.status === 'FAILED' ? 'error' : 'progress_activity'}
                </span>
                <div className="flex-1 min-w-0">
                  <p className="text-[11px] font-label uppercase tracking-widest text-on-surface-variant">
                    {t('documents.classifyProgress', {
                      processed: classifyTask.processed,
                      total: classifyTask.total,
                      failed: classifyTask.failed,
                    })}
                  </p>
                  <div className="h-1 bg-outline-variant/20 rounded-full overflow-hidden mt-1.5">
                    <div
                      className="h-full bg-secondary transition-all"
                      style={{ width: `${classifyTask.total > 0 ? (classifyTask.processed / classifyTask.total) * 100 : 0}%` }}
                    />
                  </div>
                </div>
                {(classifyTask.status === 'PENDING' || classifyTask.status === 'PROCESSING') && (
                  <button
                    onClick={() => cancelClassifyMutation.mutate(classifyTask.taskId)}
                    className="text-[10px] text-outline hover:text-error uppercase tracking-widest"
                  >
                    {t('documents.cancel')}
                  </button>
                )}
              </div>
            )}
          </div>
        )}
      </section>

      {/* Document List — aligné sur le style ui/Table (carte + en-tête + lignes divisées) */}
      <section className="bg-surface-container rounded-xl ring-1 ring-white/[0.045] overflow-hidden">
        {/* Column headers */}
        <div className="hidden lg:grid lg:grid-cols-[32px_minmax(0,1.5fr)_100px_140px_120px_100px_80px] gap-4 px-4 py-2.5 bg-surface-container-high/60 border-b border-outline-variant/60 text-[11px] font-medium uppercase tracking-[0.05em] text-on-surface-variant">
          <div className="flex justify-center">
            <button
              type="button"
              role="checkbox"
              aria-checked={allSelected ? true : someSelected ? 'mixed' : false}
              aria-label={t('documents.selectAll')}
              onClick={toggleSelectAll}
              className={`w-4 h-4 rounded border flex items-center justify-center cursor-pointer transition-all ${allSelected ? 'bg-primary border-primary' : 'border-outline-variant/40 hover:border-primary/50'}`}
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

        <div className="max-h-[70vh] overflow-y-auto custom-scrollbar divide-y divide-outline-variant/40">
          {groupBy === 'none' ? (
            <>
              {paginatedItems.map(renderRow)}
              {filtered.length === 0 && (
                <EmptyState icon="search_off" title={t('documents.noMatch')} className="py-16" />
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
                      className="flex items-center gap-3 px-4 py-2.5 bg-surface-container-high/60 cursor-pointer hover:bg-surface-container-high transition-colors select-none"
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
                        <span className={`w-4 h-4 rounded border flex items-center justify-center transition-all ${allGroupSelected ? 'bg-primary border-primary' : groupSelected > 0 ? 'border-primary bg-primary/20' : 'border-outline-variant/40 hover:border-primary/50'}`}>
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
                      <div className="divide-y divide-outline-variant/40">
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
        <div className="flex items-center justify-between p-4 bg-surface-container rounded-xl ring-1 ring-white/[0.045]">
          <span className="text-[12px] text-on-surface-variant">
            {t('documents.pageOf', { page: page + 1, total: totalPages, count: filtered.length })}
          </span>
          <div className="flex gap-1.5">
            <Button variant="outline" size="sm" onClick={() => setPage(p => Math.max(0, p - 1))} disabled={page === 0}>
              {t('documents.prev')}
            </Button>
            {Array.from({ length: Math.min(totalPages, 7) }, (_, i) => {
              const p = totalPages <= 7 ? i : (page < 4 ? i : page + i - 3);
              if (p >= totalPages) return null;
              return (
                <button
                  key={p}
                  onClick={() => setPage(p)}
                  aria-current={p === page ? 'page' : undefined}
                  className={`w-8 h-8 rounded-lg border text-[12px] font-medium transition-colors ${p === page ? 'border-primary/50 bg-primary/10 text-primary' : 'border-outline-variant text-on-surface-variant hover:bg-surface-container-high'}`}
                >
                  {p + 1}
                </button>
              );
            })}
            <Button variant="outline" size="sm" onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))} disabled={page >= totalPages - 1}>
              {t('documents.next')}
            </Button>
          </div>
        </div>
      )}

      {/* Chargement incrémental depuis le serveur : plus de troncature muette à 1000. */}
      {hasNextPage && (
        <div className="flex items-center justify-center gap-3 p-4 bg-surface-container rounded-xl ring-1 ring-white/[0.045]">
          <span className="text-[12px] text-on-surface-variant">
            {t('documents.loadedOf', { loaded: documents.length, total: totalDocuments })}
          </span>
          <Button variant="outline" size="sm" onClick={() => fetchNextPage()} loading={isFetchingNextPage}>
            {isFetchingNextPage
              ? t('documents.loadingMore')
              : t('documents.loadMore', { count: Math.min(FETCH_SIZE, totalDocuments - documents.length) })}
          </Button>
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
            {classificationConfig?.enabled && (
              <>
                <div className="w-px h-6 bg-outline-variant/20" />
                <button
                  onClick={() => bulkClassifyMutation.mutate({
                    sha256List: Array.from(bulkSelected),
                    // Sélection explicite : l'utilisateur veut un verdict frais sur ces
                    // documents-là, y compris ceux déjà étiquetés.
                    force: true,
                  })}
                  disabled={bulkClassifyMutation.isPending || !!classifyTaskId}
                  className="flex items-center gap-1.5 px-3 py-2 border border-secondary/40 text-secondary text-[10px] font-bold tracking-widest uppercase hover:bg-secondary/10 transition-all disabled:opacity-50"
                >
                  <span aria-hidden="true" className="material-symbols-outlined text-[13px]">auto_awesome</span>
                  {t('documents.classify')}
                </button>
              </>
            )}
            <div className="w-px h-6 bg-outline-variant/20" />
            <Button
              variant="danger"
              size="sm"
              icon="delete"
              onClick={() => setPendingDelete({ kind: 'bulk', shaList: Array.from(bulkSelected) })}
              disabled={bulkDeleteMutation.isPending}
            >
              {t('documents.delete')}
            </Button>
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

              {/* Classification automatique (R8) */}
              {classificationConfig?.enabled && (
                <div className="space-y-3">
                  <div className="flex items-center justify-between gap-3">
                    <h4 className="text-[11px] font-bold uppercase tracking-widest text-outline">
                      {t('documents.classification')}
                    </h4>
                    <button
                      onClick={() => classifyMutation.mutate({
                        sha: sheet.sha256,
                        // Un document déjà classifié : le bouton relance explicitement le
                        // modèle, sinon l'API renverrait simplement l'ancien verdict.
                        force: (sheet.categories ?? []).length > 0,
                      })}
                      disabled={classifyMutation.isPending}
                      className="flex items-center gap-1.5 px-3 py-1.5 border border-secondary/30 text-secondary text-[10px] font-bold tracking-widest uppercase hover:bg-secondary/10 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                    >
                      <span aria-hidden="true" className={`material-symbols-outlined text-[13px] ${classifyMutation.isPending ? 'animate-spin' : ''}`}>
                        {classifyMutation.isPending ? 'progress_activity' : 'auto_awesome'}
                      </span>
                      {(sheet.categories ?? []).length > 0 ? t('documents.reclassify') : t('documents.classify')}
                    </button>
                  </div>

                  {(sheet.categories ?? []).length === 0 ? (
                    <p className="text-xs italic text-outline">{t('documents.notClassified')}</p>
                  ) : (
                    <>
                      <div className="flex flex-wrap gap-2">
                        {(sheet.categories ?? []).map(cat => {
                          const score = sheet.categoryScores?.[cat];
                          return (
                            <Badge key={cat} tone="secondary">
                              {cat}
                              {score != null && (
                                <span className="font-mono opacity-70">{(score * 100).toFixed(0)}%</span>
                              )}
                            </Badge>
                          );
                        })}
                      </div>
                      {sheet.classificationSummary && (
                        <p className="text-xs text-on-surface-variant italic border-l-2 border-secondary/40 pl-3">
                          {sheet.classificationSummary}
                        </p>
                      )}
                      <p className="text-[10px] text-outline uppercase tracking-widest">
                        {t('documents.classifiedBy', {
                          model: sheet.classifierModel ?? '—',
                          date: sheet.classifiedAt ? formatDate(sheet.classifiedAt) : '—',
                        })}
                      </p>
                    </>
                  )}
                </div>
              )}

              {/* Tag management */}
              <div className="space-y-3">
                <h4 className="text-[11px] font-bold uppercase tracking-widest text-outline">{t('documents.tags')}</h4>
                <div className="flex flex-wrap gap-2 min-h-[2rem]">
                  {sheet.tags.length === 0 && (
                    <p className="text-xs italic text-outline">{t('documents.noTags')}</p>
                  )}
                  {sheet.tags.map(tag => (
                    <Badge key={tag} tone="neutral">
                      #{tag}
                      <button
                        onClick={() => removeTagMutation.mutate({ sha: sheet.sha256, tags: [tag] })}
                        disabled={removeTagMutation.isPending}
                        aria-label={`Remove tag ${tag}`}
                        className="hover:text-error transition-colors"
                      >
                        <span aria-hidden="true" className="material-symbols-outlined text-[11px]">close</span>
                      </button>
                    </Badge>
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
                              <Badge tone={c.type === 'AI_GENERATED' ? 'secondary' : 'neutral'}>
                                {c.type === 'AI_GENERATED' ? '✦ AI' : '👤'}
                              </Badge>
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
