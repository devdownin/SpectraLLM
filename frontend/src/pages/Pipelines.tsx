import { useDeferredValue, useEffect, useMemo, useState } from 'react';
import type { FC } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import Skeleton from '../components/Skeleton';
import Tooltip from '../components/Tooltip';
import { gedApi } from '../services/api';
import type { IngestedFile, IngestedFileSheet, DocumentLifecycle } from '../types/api';

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
  { label: 'All',  value: 0 },
  { label: '≥ 25%', value: 0.25 },
  { label: '≥ 50%', value: 0.50 },
  { label: '≥ 75%', value: 0.75 },
];

const PAGE_SIZE = 50;

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

function formatDate(value: string): string {
  return new Date(value).toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' });
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

const Pipelines: FC = () => {
  const queryClient = useQueryClient();

  const [search, setSearch] = useState('');
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
  const deferredSearch = useDeferredValue(search);

  useEffect(() => { setPage(0); }, [deferredSearch, selectedLifecycle, selectedFormats, qualityMin, groupBy, sortMode]);

  // ── Queries ────────────────────────────────────────────────────────────────

  const { data: stats } = useQuery({
    queryKey: ['ged-stats'],
    queryFn: () => gedApi.getStats().then(r => r.data),
  });

  const { data: documents, isLoading, isFetching, refetch } = useQuery<IngestedFile[]>({
    queryKey: ['ged-documents', selectedLifecycle],
    queryFn: async () => {
      const params: Record<string, unknown> = { size: 500 };
      if (selectedLifecycle !== 'all') params.lifecycle = selectedLifecycle;
      const res = await gedApi.listDocuments(params);
      return res.data.content;
    },
  });

  const { data: sheet, isLoading: isLoadingSheet } = useQuery<IngestedFileSheet>({
    queryKey: ['ged-document', selectedSha],
    queryFn: () => gedApi.getDocument(selectedSha!).then(r => r.data),
    enabled: !!selectedSha,
  });

  // ── Mutations ──────────────────────────────────────────────────────────────

  const transitionMutation = useMutation({
    mutationFn: ({ sha, lc }: { sha: string; lc: string }) => gedApi.updateLifecycle(sha, lc),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['ged-documents'] });
      queryClient.invalidateQueries({ queryKey: ['ged-document'] });
      queryClient.invalidateQueries({ queryKey: ['ged-stats'] });
      toast.success('Cycle de vie mis à jour');
    },
    onError: (err: any) => toast.error('Échec transition', { description: err.response?.data?.error }),
  });

  const deleteMutation = useMutation({
    mutationFn: (sha: string) => gedApi.deleteDocument(sha),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['ged-documents'] });
      queryClient.invalidateQueries({ queryKey: ['ged-stats'] });
      setSelectedSha(null);
      toast.success('Document supprimé');
    },
  });

  const bulkLifecycleMutation = useMutation({
    mutationFn: ({ sha256List, lifecycle }: { sha256List: string[]; lifecycle: string }) =>
      gedApi.bulkLifecycle(sha256List, lifecycle),
    onSuccess: (_, { sha256List }) => {
      queryClient.invalidateQueries({ queryKey: ['ged-documents'] });
      queryClient.invalidateQueries({ queryKey: ['ged-stats'] });
      setBulkSelected(new Set());
      toast.success(`${sha256List.length} document(s) mis à jour`);
    },
    onError: () => toast.error('Échec de la mise à jour en lot'),
  });

  const bulkDeleteMutation = useMutation({
    mutationFn: (sha256List: string[]) => Promise.all(sha256List.map(sha => gedApi.deleteDocument(sha))),
    onSuccess: (_, sha256List) => {
      queryClient.invalidateQueries({ queryKey: ['ged-documents'] });
      queryClient.invalidateQueries({ queryKey: ['ged-stats'] });
      setBulkSelected(new Set());
      if (selectedSha && sha256List.includes(selectedSha)) setSelectedSha(null);
      toast.success(`${sha256List.length} document(s) supprimé(s)`);
    },
    onError: () => toast.error('Échec de la suppression en lot'),
  });

  const addTagMutation = useMutation({
    mutationFn: ({ sha, tags }: { sha: string; tags: string[] }) => gedApi.addTags(sha, tags),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['ged-document', selectedSha] });
      queryClient.invalidateQueries({ queryKey: ['ged-documents'] });
      setNewTagInput('');
      toast.success('Tag ajouté');
    },
  });

  const removeTagMutation = useMutation({
    mutationFn: ({ sha, tags }: { sha: string; tags: string[] }) => gedApi.removeTags(sha, tags),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['ged-document', selectedSha] });
      queryClient.invalidateQueries({ queryKey: ['ged-documents'] });
      toast.success('Tag supprimé');
    },
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
        className={`group grid grid-cols-1 lg:grid-cols-[32px_minmax(0,1.5fr)_100px_140px_120px_100px_80px] gap-4 items-center px-4 py-4 bg-surface-container-low hover:bg-surface-container-high transition-all cursor-pointer border-l-2 ${isActive ? 'border-primary bg-surface-container-high' : 'border-transparent'}`}
      >
        <div className="flex justify-center" onClick={e => toggleSelect(doc.sha256, e)}>
          <div className={`w-4 h-4 border flex items-center justify-center transition-all shrink-0 ${isChecked ? 'bg-primary border-primary' : 'border-outline-variant/40 hover:border-primary/50'}`}>
            {isChecked && <span className="material-symbols-outlined text-white text-[10px]">check</span>}
          </div>
        </div>

        <div className="flex items-center gap-4 min-w-0">
          <div className={`w-10 h-10 flex items-center justify-center border shrink-0 ${type.accentClass}`}>
            <span className="material-symbols-outlined text-base">{type.icon}</span>
          </div>
          <div className="min-w-0">
            <p className="font-headline text-sm font-bold tracking-tight truncate">{doc.fileName}</p>
            <div className="flex items-center gap-2 mt-1 flex-wrap">
              <span className="text-[9px] font-mono text-outline">{doc.sha256.slice(0, 8)}</span>
              {doc.collectionName && (
                <span className="text-[8px] border border-primary/20 px-1 text-primary/60 uppercase truncate max-w-[100px]">{doc.collectionName}</span>
              )}
              {doc.tags.slice(0, 2).map(t => (
                <span key={t} className="text-[8px] border border-outline-variant/30 px-1 text-outline-variant uppercase">#{t}</span>
              ))}
              {doc.tags.length > 2 && (
                <span className="text-[8px] text-outline-variant">+{doc.tags.length - 2}</span>
              )}
            </div>
          </div>
        </div>

        <div className="flex justify-center">
          <span className={`text-[8px] font-bold px-2 py-0.5 border uppercase tracking-wider ${LIFECYCLE_COLORS[doc.lifecycle]}`}>
            {doc.lifecycle}
          </span>
        </div>

        <div className="flex items-center gap-2">
          <div className="flex-1 h-1 bg-outline-variant/20 rounded-full overflow-hidden">
            <div
              className={`h-full ${score > 0.7 ? 'bg-primary' : score > 0.4 ? 'bg-secondary' : 'bg-error'}`}
              style={{ width: `${score * 100}%` }}
            />
          </div>
          <span className="text-[10px] font-mono text-on-surface-variant w-8 text-right">{(score * 100).toFixed(0)}%</span>
        </div>

        <div className="text-[10px] text-on-surface-variant font-label uppercase">{formatDate(doc.ingestedAt)}</div>
        <div className="text-right font-headline font-bold text-lg">{doc.chunksCreated}</div>

        <div className="flex justify-end">
          <button
            onClick={e => { e.stopPropagation(); deleteMutation.mutate(doc.sha256); }}
            className="w-8 h-8 flex items-center justify-center text-outline-variant hover:text-error transition-colors"
          >
            <span className="material-symbols-outlined text-sm">delete</span>
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
          <p className="font-label text-[11px] uppercase tracking-[0.1em] text-on-surface-variant mb-1">Knowledge & Records</p>
          <h2 className="font-headline text-3xl font-bold tracking-tighter uppercase">GED / DATABASE</h2>
          <p className="text-sm text-on-surface-variant mt-3 max-w-3xl leading-relaxed">
            Gestion Électronique de Documents : suivez le cycle de vie, auditez les modifications
            et gérez la qualité des sources alimentant vos modèles.
          </p>
        </div>
        <div className="flex items-center gap-3">
          <span className="text-[10px] font-label text-on-surface-variant uppercase tracking-widest">
            {filtered.length} / {documents?.length ?? 0} documents
          </span>
          <button
            onClick={() => refetch()}
            className="flex items-center gap-2 border border-outline-variant/20 px-4 py-3 text-[10px] font-label uppercase tracking-widest text-on-surface-variant hover:text-primary transition-colors"
          >
            <span className={`material-symbols-outlined text-sm ${isFetching ? 'animate-spin' : ''}`}>refresh</span>
            Sync
          </button>
        </div>
      </header>

      {/* Stats Cards */}
      <section className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <div className="bg-surface-container p-5 border-t-2 border-primary">
          <p className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant mb-2">Total Documents</p>
          <p className="font-headline font-bold text-3xl">{total || '—'}</p>
        </div>
        <div className="bg-surface-container p-5 border-t-2 border-secondary">
          <p className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant mb-2">Avg Quality</p>
          <p className="font-headline font-bold text-3xl">{stats?.avgQualityScore ? (stats.avgQualityScore * 100).toFixed(0) + '%' : '—'}</p>
        </div>
        <div className="bg-surface-container p-5 border-t-2 border-outline-variant">
          <p className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant mb-2">Total Chunks</p>
          <p className="font-headline font-bold text-3xl">{stats?.totalChunks ?? '—'}</p>
        </div>
        <div className="bg-surface-container p-5 border-t-2 border-outline-variant">
          <p className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant mb-2">Lifecycle</p>
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
              <span key={lc} className="text-[8px] text-outline-variant">{stats?.byLifecycle?.[lc] ?? 0}</span>
            ))}
          </div>
        </div>
      </section>

      {/* Filters */}
      <section className="bg-surface-container p-5 space-y-5 border border-outline-variant/10">
        {/* Row 1 */}
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          <div className="space-y-2">
            <label className="text-[9px] font-label uppercase tracking-widest text-on-surface-variant">Recherche</label>
            <div className="flex items-center gap-3 border border-outline-variant/20 bg-surface-container-lowest px-4 py-2.5">
              <span className="material-symbols-outlined text-base text-outline">search</span>
              <input
                type="text"
                value={search}
                onChange={e => setSearch(e.target.value)}
                placeholder="Nom, hash, tag, collection…"
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
            <label className="text-[9px] font-label uppercase tracking-widest text-on-surface-variant">Cycle de vie</label>
            <div className="flex gap-2">
              {['all', 'INGESTED', 'QUALIFIED', 'TRAINED', 'ARCHIVED'].map(lc => (
                <button
                  key={lc}
                  onClick={() => setSelectedLifecycle(lc)}
                  className={`flex-1 py-2 border text-[9px] font-label uppercase tracking-widest transition-all ${selectedLifecycle === lc ? 'border-primary bg-primary/10 text-primary' : 'border-outline-variant/20 text-on-surface-variant hover:border-primary/30'}`}
                >
                  {lc === 'all' ? 'Tous' : lc.slice(0, 1)}
                </button>
              ))}
            </div>
          </div>
          <div className="space-y-2">
            <label className="text-[9px] font-label uppercase tracking-widest text-on-surface-variant">Tri</label>
            <div className="flex gap-2">
              {(['recent', 'name', 'chunks', 'quality'] as SortMode[]).map(m => (
                <button
                  key={m}
                  onClick={() => setSortMode(m)}
                  className={`flex-1 py-2 border text-[9px] font-label uppercase tracking-widest transition-all ${sortMode === m ? 'border-primary bg-primary/10 text-primary' : 'border-outline-variant/20 text-on-surface-variant hover:border-primary/30'}`}
                >
                  {m === 'recent' ? 'Date' : m === 'chunks' ? 'Chunks' : m === 'quality' ? 'Qualité' : 'Nom'}
                </button>
              ))}
            </div>
          </div>
        </div>

        {/* Row 2 */}
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          <div className="space-y-2">
            <label className="text-[9px] font-label uppercase tracking-widest text-on-surface-variant">
              Type de document {selectedFormats.size > 0 && <span className="text-primary">({selectedFormats.size} actif)</span>}
            </label>
            <div className="flex flex-wrap gap-2">
              {availableFormats.map(fmt => {
                const meta = DOCUMENT_TYPES[fmt];
                const active = selectedFormats.has(fmt);
                return (
                  <button
                    key={fmt}
                    onClick={() => toggleFormat(fmt)}
                    className={`flex items-center gap-1.5 px-2.5 py-1.5 border text-[9px] font-label uppercase tracking-widest transition-all ${active ? 'border-primary bg-primary/10 text-primary' : 'border-outline-variant/20 text-on-surface-variant hover:border-primary/30'}`}
                  >
                    <span className="material-symbols-outlined text-[12px]">{meta.icon}</span>
                    {meta.label}
                  </button>
                );
              })}
              {selectedFormats.size > 0 && (
                <button onClick={() => setSelectedFormats(new Set())} className="text-[9px] text-outline-variant hover:text-error uppercase tracking-widest px-2">
                  Réinitialiser
                </button>
              )}
            </div>
          </div>

          <div className="space-y-2">
            <label className="text-[9px] font-label uppercase tracking-widest text-on-surface-variant">Score qualité minimum</label>
            <div className="flex gap-2">
              {QUALITY_THRESHOLDS.map(({ label, value }) => (
                <button
                  key={value}
                  onClick={() => setQualityMin(value)}
                  className={`flex-1 py-2 border text-[9px] font-label uppercase tracking-widest transition-all ${qualityMin === value ? 'border-secondary bg-secondary/10 text-secondary' : 'border-outline-variant/20 text-on-surface-variant hover:border-secondary/30'}`}
                >
                  {label}
                </button>
              ))}
            </div>
          </div>

          <div className="space-y-2">
            <label className="text-[9px] font-label uppercase tracking-widest text-on-surface-variant">Regroupement</label>
            <div className="flex gap-2">
              {([
                { key: 'none', label: 'Aucun' },
                { key: 'type', label: 'Type' },
                { key: 'lifecycle', label: 'État' },
                { key: 'collection', label: 'Collection' },
              ] as { key: GroupBy; label: string }[]).map(({ key, label }) => (
                <button
                  key={key}
                  onClick={() => { setGroupBy(key); setCollapsedGroups(new Set()); }}
                  className={`flex-1 py-2 border text-[9px] font-label uppercase tracking-widest transition-all ${groupBy === key ? 'border-primary bg-primary/10 text-primary' : 'border-outline-variant/20 text-on-surface-variant hover:border-primary/30'}`}
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
        <div className="hidden lg:grid lg:grid-cols-[32px_minmax(0,1.5fr)_100px_140px_120px_100px_80px] gap-4 px-4 py-3 border-b border-outline-variant/10 text-[9px] font-label uppercase tracking-widest text-outline">
          <div className="flex justify-center">
            <div
              onClick={toggleSelectAll}
              className={`w-4 h-4 border flex items-center justify-center cursor-pointer transition-all ${allSelected ? 'bg-primary border-primary' : 'border-outline-variant/40 hover:border-primary/50'}`}
            >
              {allSelected && <span className="material-symbols-outlined text-white text-[10px]">check</span>}
              {!allSelected && someSelected && <span className="material-symbols-outlined text-primary text-[10px]">remove</span>}
            </div>
          </div>
          <span>Document</span>
          <span className="text-center">Lifecycle</span>
          <span>Qualité</span>
          <span>Ingéré le</span>
          <span className="text-right">Chunks</span>
          <span className="text-right">Actions</span>
        </div>

        <div className="space-y-1">
          {groupBy === 'none' ? (
            <>
              {paginatedItems.map(renderRow)}
              {filtered.length === 0 && (
                <div className="py-20 text-center text-on-surface-variant">
                  <span className="material-symbols-outlined text-4xl block mb-3 opacity-30">search_off</span>
                  <p className="text-sm">Aucun document ne correspond aux filtres actifs.</p>
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
                      <div onClick={toggleGroupSelect} className="flex justify-center" style={{ width: 32 }}>
                        <div className={`w-4 h-4 border flex items-center justify-center transition-all ${allGroupSelected ? 'bg-primary border-primary' : groupSelected > 0 ? 'border-primary bg-primary/20' : 'border-outline-variant/40 hover:border-primary/50'}`}>
                          {allGroupSelected && <span className="material-symbols-outlined text-white text-[10px]">check</span>}
                          {!allGroupSelected && groupSelected > 0 && <span className="material-symbols-outlined text-primary text-[10px]">remove</span>}
                        </div>
                      </div>
                      <span className={`material-symbols-outlined text-base text-on-surface-variant transition-transform ${isCollapsed ? '-rotate-90' : ''}`}>expand_more</span>
                      <p className="font-headline font-bold text-sm uppercase tracking-tight flex-1">{label}</p>
                      <span className="text-[10px] font-label text-on-surface-variant uppercase tracking-widest">{docs.length} doc{docs.length > 1 ? 's' : ''}</span>
                      {groupSelected > 0 && (
                        <span className="text-[9px] font-label text-primary uppercase tracking-widest">{groupSelected} sélectionné{groupSelected > 1 ? 's' : ''}</span>
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
          <span className="text-[10px] font-label uppercase tracking-widest text-on-surface-variant">
            Page {page + 1} / {totalPages} — {filtered.length} résultats
          </span>
          <div className="flex gap-2">
            <button
              onClick={() => setPage(p => Math.max(0, p - 1))}
              disabled={page === 0}
              className="px-3 py-2 border border-outline-variant/20 text-[9px] font-label uppercase tracking-widest text-on-surface-variant hover:text-primary hover:border-primary/30 transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
            >
              ← Précédent
            </button>
            {Array.from({ length: Math.min(totalPages, 7) }, (_, i) => {
              const p = totalPages <= 7 ? i : (page < 4 ? i : page + i - 3);
              if (p >= totalPages) return null;
              return (
                <button
                  key={p}
                  onClick={() => setPage(p)}
                  className={`w-8 py-2 border text-[9px] font-label uppercase tracking-widest transition-all ${p === page ? 'border-primary bg-primary/10 text-primary' : 'border-outline-variant/20 text-on-surface-variant hover:border-primary/30'}`}
                >
                  {p + 1}
                </button>
              );
            })}
            <button
              onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}
              disabled={page >= totalPages - 1}
              className="px-3 py-2 border border-outline-variant/20 text-[9px] font-label uppercase tracking-widest text-on-surface-variant hover:text-primary hover:border-primary/30 transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
            >
              Suivant →
            </button>
          </div>
        </div>
      )}

      {/* Bulk Action Bar */}
      {someSelected && (
        <div className="fixed bottom-6 left-1/2 -translate-x-1/2 z-50 animate-in slide-in-from-bottom duration-300">
          <div className="flex items-center gap-4 bg-surface-container-high border border-primary/30 px-6 py-4 shadow-2xl">
            <span className="text-[10px] font-label uppercase tracking-widest text-primary font-bold">
              {bulkSelected.size} sélectionné{bulkSelected.size > 1 ? 's' : ''}
            </span>
            <div className="w-px h-6 bg-outline-variant/20" />
            <span className="text-[9px] font-label uppercase tracking-widest text-on-surface-variant">Passer en :</span>
            {(['INGESTED', 'QUALIFIED', 'TRAINED', 'ARCHIVED'] as DocumentLifecycle[]).map(lc => (
              <button
                key={lc}
                onClick={() => bulkLifecycleMutation.mutate({ sha256List: Array.from(bulkSelected), lifecycle: lc })}
                disabled={bulkLifecycleMutation.isPending}
                className={`px-3 py-2 border text-[9px] font-bold tracking-widest uppercase transition-all disabled:opacity-50 ${LIFECYCLE_COLORS[lc]} hover:bg-primary/10`}
              >
                {lc}
              </button>
            ))}
            <div className="w-px h-6 bg-outline-variant/20" />
            <button
              onClick={() => bulkDeleteMutation.mutate(Array.from(bulkSelected))}
              disabled={bulkDeleteMutation.isPending}
              className="px-3 py-2 border border-error/30 text-error text-[9px] font-bold tracking-widest uppercase hover:bg-error hover:text-white transition-all disabled:opacity-50"
            >
              Supprimer
            </button>
            <button onClick={() => setBulkSelected(new Set())} className="w-8 h-8 flex items-center justify-center text-outline-variant hover:text-on-surface transition-colors">
              <span className="material-symbols-outlined text-sm">close</span>
            </button>
          </div>
        </div>
      )}

      {/* Document Detail Sheet */}
      {selectedSha && (
        <div className="fixed inset-y-0 right-0 w-full lg:w-[520px] bg-surface-container-high shadow-[-20px_0_40px_rgba(0,0,0,0.5)] z-50 animate-in slide-in-from-right duration-300 border-l border-outline-variant/20 flex flex-col">
          <header className="p-6 border-b border-outline-variant/20 flex justify-between items-center">
            <div className="min-w-0">
              <p className="text-[9px] font-label uppercase tracking-widest text-outline">Fiche Document</p>
              <h3 className="font-headline text-lg font-bold truncate max-w-[380px]">{sheet?.fileName ?? '—'}</h3>
            </div>
            <button onClick={() => setSelectedSha(null)} className="w-10 h-10 flex items-center justify-center hover:bg-surface-variant transition-colors shrink-0">
              <span className="material-symbols-outlined">close</span>
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
                  <p className="text-[8px] uppercase tracking-widest text-outline mb-1">Status</p>
                  <p className="font-headline font-bold text-sm text-primary uppercase">{sheet.lifecycle}</p>
                </div>
                <div className="p-4 bg-surface-container-lowest border-l-2 border-secondary">
                  <p className="text-[8px] uppercase tracking-widest text-outline mb-1">Qualité</p>
                  <p className="font-headline font-bold text-sm text-secondary uppercase">{((sheet.qualityScore ?? 0) * 100).toFixed(0)}%</p>
                </div>
                <div className="p-4 bg-surface-container-lowest border-l-2 border-outline-variant">
                  <p className="text-[8px] uppercase tracking-widest text-outline mb-1">Format</p>
                  <p className="font-headline font-bold text-sm uppercase truncate">{sheet.format}</p>
                </div>
                <div className="p-4 bg-surface-container-lowest border-l-2 border-outline-variant">
                  <p className="text-[8px] uppercase tracking-widest text-outline mb-1">Chunks</p>
                  <p className="font-headline font-bold text-sm">{sheet.chunksCreated}</p>
                </div>
                {sheet.collectionName && (
                  <div className="col-span-2 p-4 bg-surface-container-lowest border-l-2 border-primary/40">
                    <p className="text-[8px] uppercase tracking-widest text-outline mb-1">Collection</p>
                    <p className="font-headline font-bold text-sm text-primary/80 truncate">{sheet.collectionName}</p>
                  </div>
                )}
                <div className="col-span-2 p-4 bg-surface-container-lowest border-l-2 border-outline-variant/40">
                  <p className="text-[8px] uppercase tracking-widest text-outline mb-1">SHA-256</p>
                  <p className="font-mono text-[10px] text-on-surface-variant break-all">{sheet.sha256}</p>
                </div>
              </div>

              {/* Lifecycle transitions */}
              <div className="space-y-3">
                <h4 className="text-[10px] font-bold uppercase tracking-widest text-outline">Transitions</h4>
                <div className="flex flex-wrap gap-2">
                  {(['INGESTED', 'QUALIFIED', 'TRAINED', 'ARCHIVED'] as DocumentLifecycle[]).map(lc => (
                    <button
                      key={lc}
                      disabled={lc === sheet.lifecycle || transitionMutation.isPending}
                      onClick={() => transitionMutation.mutate({ sha: sheet.sha256, lc })}
                      className={`px-3 py-2 border text-[9px] font-bold tracking-widest uppercase transition-all ${lc === sheet.lifecycle ? 'opacity-30 cursor-not-allowed border-outline' : 'border-primary/30 text-primary hover:bg-primary/10'}`}
                    >
                      {lc === sheet.lifecycle ? '✓ ' : ''}{lc}
                    </button>
                  ))}
                </div>
              </div>

              {/* Tag management */}
              <div className="space-y-3">
                <h4 className="text-[10px] font-bold uppercase tracking-widest text-outline">Tags</h4>
                <div className="flex flex-wrap gap-2 min-h-[2rem]">
                  {sheet.tags.length === 0 && (
                    <p className="text-xs italic text-outline">Aucun tag.</p>
                  )}
                  {sheet.tags.map(tag => (
                    <span key={tag} className="flex items-center gap-1 text-[9px] border border-outline-variant/30 px-2 py-1 text-outline-variant uppercase">
                      #{tag}
                      <button
                        onClick={() => removeTagMutation.mutate({ sha: sheet.sha256, tags: [tag] })}
                        disabled={removeTagMutation.isPending}
                        className="hover:text-error transition-colors ml-1"
                      >
                        <span className="material-symbols-outlined text-[10px]">close</span>
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
                    placeholder="Nouveau tag…"
                    className="flex-1 bg-surface-container-lowest border border-outline-variant/20 px-3 py-2 text-sm outline-none focus:border-primary/50 font-body placeholder:text-outline"
                  />
                  <button
                    onClick={() => {
                      if (newTagInput.trim()) addTagMutation.mutate({ sha: sheet.sha256, tags: [newTagInput.trim().toLowerCase()] });
                    }}
                    disabled={!newTagInput.trim() || addTagMutation.isPending}
                    className="px-4 py-2 bg-primary/10 border border-primary/30 text-primary text-[9px] font-bold tracking-widest uppercase hover:bg-primary/20 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                  >
                    + Tag
                  </button>
                </div>
              </div>

              {/* Model links */}
              <div className="space-y-3">
                <h4 className="text-[10px] font-bold uppercase tracking-widest text-outline">Associations Modèles</h4>
                {sheet.modelLinks.length === 0 ? (
                  <p className="text-xs italic text-outline">Aucune association modèle.</p>
                ) : (
                  <div className="space-y-2">
                    {sheet.modelLinks.map((l, i) => (
                      <div key={i} className="flex justify-between items-center p-3 bg-surface-container-lowest border border-outline-variant/10">
                        <div className="flex items-center gap-3">
                          <span className="material-symbols-outlined text-sm text-primary">hub</span>
                          <span className="text-xs font-bold truncate max-w-[240px]">{l.model}</span>
                        </div>
                        <span className="text-[9px] font-bold uppercase text-outline shrink-0">{l.type.replace('_', ' ')}</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              {/* Audit Trail */}
              <div className="space-y-3">
                <h4 className="text-[10px] font-bold uppercase tracking-widest text-outline">Audit Trail</h4>
                <div className="space-y-1 relative before:absolute before:left-[11px] before:top-2 before:bottom-2 before:w-px before:bg-outline-variant/20">
                  {sheet.auditTrail.map((a, i) => (
                    <div key={i} className="pl-8 relative py-3 group">
                      <div className="absolute left-[8px] top-5 w-2 h-2 rounded-full bg-outline-variant group-first:bg-primary" />
                      <div className="flex justify-between items-start mb-1">
                        <p className="text-[10px] font-bold uppercase tracking-tighter">{a.action.replace(/_/g, ' ')}</p>
                        <p className="text-[8px] text-outline font-mono shrink-0 ml-2">{formatDate(a.timestamp)}</p>
                      </div>
                      <p className="text-[10px] text-on-surface-variant italic">by {a.actor}</p>
                      {a.details && <p className="text-[9px] text-outline mt-1 font-mono break-all">{a.details}</p>}
                    </div>
                  ))}
                </div>
              </div>
            </div>
          )}

          <footer className="p-6 border-t border-outline-variant/20">
            <button
              onClick={() => deleteMutation.mutate(sheet!.sha256)}
              disabled={deleteMutation.isPending}
              className="w-full py-3 bg-error/10 border border-error/30 text-error font-bold text-[10px] tracking-widest uppercase hover:bg-error hover:text-white transition-all disabled:opacity-50"
            >
              Supprimer définitivement
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
    </div>
  );
};

export default Pipelines;
