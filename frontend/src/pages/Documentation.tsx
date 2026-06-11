import React, { useState } from 'react';

const Documentation: React.FC = () => {
  const [activeTab, setActiveTab] = useState('overview');

  const sections = [
    { id: 'overview',          title: 'Vue d\'ensemble' },
    { id: 'prerequisites',     title: 'Prérequis' },
    { id: 'pipeline',          title: 'Pipeline' },
    { id: 'commenting',        title: 'Commentaires IA' },
    { id: 'personalisation',   title: 'Personnalisation' },
    { id: 'interface',         title: 'Interface' },
    { id: 'benchmark',         title: 'Benchmark' },
    { id: 'tips',              title: 'Conseils' },
    { id: 'troubleshooting',   title: 'Dépannage' },
  ];

  return (
    <div className="space-y-8 animate-in fade-in slide-in-from-bottom-4 duration-700">
      {/* Header */}
      <div className="flex flex-col md:flex-row md:items-end justify-between border-b border-border/40 pb-6 gap-4">
        <div>
          <h1 className="text-4xl font-headline font-bold tracking-tight text-foreground">Documentation</h1>
          <p className="text-muted-foreground mt-2 max-w-2xl text-lg">
            Maîtrisez Spectra — de l'ingestion de documents à la boucle de personnalisation continue et au fine-tuning par préférence.
          </p>
        </div>
        <div className="flex bg-secondary/30 p-1 rounded-lg border border-border/40 overflow-x-auto no-scrollbar flex-wrap gap-0.5">
          {sections.map((section) => (
            <button
              key={section.id}
              onClick={() => setActiveTab(section.id)}
              className={`px-4 py-1.5 text-xs font-headline uppercase tracking-widest rounded-md transition-all whitespace-nowrap ${
                activeTab === section.id
                  ? 'bg-primary text-primary-foreground shadow-lg shadow-primary/20'
                  : 'text-muted-foreground hover:text-foreground hover:bg-secondary/50'
              }`}
            >
              {section.title}
            </button>
          ))}
        </div>
      </div>

      <div className="grid grid-cols-1 gap-8">
        {activeTab === 'overview'        && sectionOverview()}
        {activeTab === 'prerequisites'   && sectionPrerequisites()}
        {activeTab === 'pipeline'        && sectionPipeline()}
        {activeTab === 'commenting'      && sectionCommenting()}
        {activeTab === 'personalisation' && sectionPersonalisation()}
        {activeTab === 'interface'       && sectionInterface()}
        {activeTab === 'benchmark'       && sectionBenchmark()}
        {activeTab === 'tips'            && sectionTips()}
        {activeTab === 'troubleshooting' && sectionTroubleshooting()}
      </div>
    </div>
  );
};

// ─────────────────────────────────────────────────────────────────────────────

const sectionOverview = () => (
  <div className="space-y-10 animate-in fade-in slide-in-from-right-4 duration-500">
    <div className="prose prose-invert max-w-none">
      <h2 className="text-2xl font-headline font-bold text-primary mb-4">Bienvenue sur Spectra</h2>
      <p className="text-lg leading-relaxed text-foreground/80">
        Spectra vous permet de créer votre propre assistant d'intelligence artificielle spécialisé dans{' '}
        <strong>votre domaine métier</strong>, à partir de vos propres documents.
        L'assistant fonctionne <strong>entièrement en local</strong> — aucune donnée ne quitte votre infrastructure.
      </p>
    </div>

    {/* 4 pillars */}
    <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
      {[
        {
          icon: (
            <svg className="w-6 h-6 text-primary" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
            </svg>
          ),
          title: 'Confidentialité Totale',
          body: 'Vos documents ne quittent jamais votre infrastructure. Ingestion, RAG, fine-tuning — tout est 100 % local, sans dépendance cloud.',
          badge: 'Privacy',
        },
        {
          icon: (
            <svg className="w-6 h-6 text-primary" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 10V3L4 14h7v7l9-11h-7z" />
            </svg>
          ),
          title: 'RAG Multi-Stratégie',
          body: 'Recherche hybride BM25 + vecteurs, re-ranking Cross-Encoder, boucle ReAct agentique, Multi-Query, Corrective RAG — 10+ modules configurables.',
          badge: 'Retrieval',
        },
        {
          icon: (
            <svg className="w-6 h-6 text-primary" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10" />
            </svg>
          ),
          title: 'Fine-Tuning Intégré',
          body: "QLoRA 4-bit (Unsloth), génération automatique de dataset SFT + DPO, streaming temps réel des métriques loss/epoch, export GGUF.",
          badge: 'Training',
        },
        {
          icon: (
            <svg className="w-6 h-6 text-primary" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z" />
            </svg>
          ),
          title: 'Personnalisation Continue',
          body: 'Commentaires analytiques générés par RAG sur chaque document. Notez 👍/👎 pour constituer des paires DPO. Quand le seuil est atteint, le re-entraînement se déclenche automatiquement.',
          badge: 'Auto-DPO',
        },
      ].map(p => (
        <div key={p.title} className="bg-secondary/20 p-6 rounded-xl border border-border/40 hover:border-primary/40 transition-colors group">
          <div className="flex items-start justify-between mb-4">
            <div className="w-12 h-12 rounded-lg bg-primary/10 flex items-center justify-center group-hover:scale-110 transition-transform shrink-0">
              {p.icon}
            </div>
            <span className="text-[8px] font-bold uppercase tracking-widest border border-primary/30 text-primary px-2 py-0.5 rounded-full">
              {p.badge}
            </span>
          </div>
          <h3 className="text-lg font-headline font-bold mb-2">{p.title}</h3>
          <p className="text-sm text-muted-foreground leading-relaxed">{p.body}</p>
        </div>
      ))}
    </div>

    {/* Architecture flow — full cycle */}
    <div className="bg-card/50 border border-border/40 rounded-xl p-8 space-y-6">
      <h3 className="text-lg font-headline font-bold text-foreground">Cycle complet de personnalisation</h3>
      <div className="overflow-x-auto">
        <div className="flex items-stretch gap-0 min-w-max">
          {[
            { num: '1', label: 'Ingest',      sub: 'PDF · DOCX · URL',     color: 'bg-primary', text: 'text-primary' },
            { arrow: true },
            { num: '2', label: 'Generate',    sub: 'Q/A · résumés',        color: 'bg-primary', text: 'text-primary' },
            { arrow: true },
            { num: '3', label: 'Annotate',    sub: 'RAG → commentaires',   color: 'bg-secondary/80', text: 'text-secondary' },
            { arrow: true },
            { num: '4', label: 'Fine-Tune',   sub: 'QLoRA · GGUF',        color: 'bg-primary', text: 'text-primary' },
            { arrow: true },
            { num: '5', label: 'Évaluation',  sub: 'LLM-as-judge',        color: 'bg-secondary/80', text: 'text-secondary' },
            { arrow: true },
            { num: '↺', label: 'Auto-trigger',sub: 'N approbations → FT', color: 'bg-primary', text: 'text-primary' },
          ].map((s: any, i) => s.arrow ? (
            <div key={i} className="flex items-center px-1 text-muted-foreground/40 font-mono text-lg">→</div>
          ) : (
            <div key={i} className="flex flex-col items-center gap-2 px-3">
              <div className={`w-10 h-10 rounded-full flex items-center justify-center font-headline font-bold text-xs shadow-lg ${s.color} text-primary-foreground`}>{s.num}</div>
              <span className={`text-[10px] font-headline uppercase tracking-widest ${s.text}`}>{s.label}</span>
              <span className="text-[8px] text-muted-foreground/60 text-center max-w-[70px]">{s.sub}</span>
            </div>
          ))}
        </div>
      </div>
      <div className="flex items-center gap-2 p-3 bg-primary/5 border border-primary/20 rounded-lg">
        <span className="material-symbols-outlined text-sm text-primary shrink-0">sync</span>
        <p className="text-xs text-foreground/70">
          L'étape <strong>↺ Auto-trigger</strong> ferme la boucle : quand le nombre de commentaires approuvés atteint
          le seuil configuré (<code className="font-mono bg-black/30 px-1">spectra.ged.auto-retrain-threshold</code>, défaut = 5),
          un job de fine-tuning DPO est soumis <em>automatiquement</em> — sans intervention humaine.
        </p>
      </div>
    </div>

    {/* Stats highlight */}
    <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
      {[
        { label: '10+',  sub: 'stratégies RAG',        color: 'text-primary' },
        { label: 'Auto', sub: 're-train DPO intégré',  color: 'text-secondary' },
        { label: '100%', sub: 'local & open-source',   color: 'text-primary' },
        { label: 'DPO',  sub: 'boucle humaine active', color: 'text-secondary' },
      ].map(s => (
        <div key={s.label} className="bg-secondary/20 rounded-xl p-5 text-center border border-border/40">
          <p className={`text-3xl font-headline font-bold ${s.color}`}>{s.label}</p>
          <p className="text-[9px] text-muted-foreground uppercase tracking-widest mt-1">{s.sub}</p>
        </div>
      ))}
    </div>
  </div>
);

// ─────────────────────────────────────────────────────────────────────────────

const sectionPrerequisites = () => (
  <div className="space-y-6 animate-in fade-in slide-in-from-right-4 duration-500">
    <div className="bg-card/50 border border-border/40 rounded-xl p-8">
      <h2 className="text-2xl font-headline font-bold text-primary mb-6">Prérequis Système</h2>
      <div className="space-y-4">
        <p className="text-foreground/80">Avant de commencer, assurez-vous d'avoir installé les composants suivants :</p>
        <ul className="list-disc list-inside space-y-2 text-foreground/80 ml-4">
          <li><span className="text-foreground font-bold">Docker Desktop</span> (v4.x+) — Démarré et opérationnel.</li>
          <li><span className="text-foreground font-bold">llama-server</span> — Fourni automatiquement via Docker (llama-cpp-turboquant).</li>
        </ul>

        <div className="mt-8">
          <h3 className="text-lg font-headline font-bold text-foreground mb-3 uppercase tracking-wider text-[10px]">Modèles GGUF Requis</h3>
          <div className="bg-black/40 p-4 rounded-lg font-mono text-sm border border-border/20">
            <p className="text-green-400"># Embeddings — placez dans data/models/embed.gguf</p>
            <p className="text-foreground mb-3">nomic-embed-text-v1.5.Q4_K_M.gguf</p>
            <p className="text-green-400"># Chat — placez dans data/fine-tuning/merged/model.gguf</p>
            <p className="text-foreground">phi-3-mini-4k-instruct-q4.gguf  (ou tout GGUF compatible)</p>
          </div>
        </div>

        <div className="mt-6 p-4 bg-primary/5 border border-primary/20 rounded-lg flex gap-4">
          <div className="text-primary mt-1">
            <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" /></svg>
          </div>
          <p className="text-sm text-foreground/80 italic">
            Pas de GPU requis pour démarrer. Spectra utilise l'inférence CPU par défaut.
            Le fine-tuning avec poids réels est optionnel et nécessite une carte NVIDIA + CUDA.
          </p>
        </div>
      </div>
    </div>
  </div>
);

// ─────────────────────────────────────────────────────────────────────────────

const sectionPipeline = () => (
  <div className="space-y-8 animate-in fade-in slide-in-from-right-4 duration-500">

    {/* Stepper */}
    <div className="flex items-center justify-center py-4 overflow-x-auto no-scrollbar">
      <div className="flex items-center space-x-2 min-w-max">
        {[
          { n: '1',  label: 'Ingest',     accent: 'primary' },
          { n: '2',  label: 'Generate',   accent: 'primary' },
          { n: '2c', label: 'Annotate',   accent: 'secondary' },
          { n: '3',  label: 'Fine-Tune',  accent: 'primary' },
          { n: '4',  label: 'Evaluate',   accent: 'secondary' },
          { n: '↺',  label: 'Auto-DPO',   accent: 'primary' },
        ].map((s, i, arr) => (
          <React.Fragment key={s.n}>
            <div className="flex flex-col items-center">
              <div className={`w-9 h-9 rounded-full flex items-center justify-center font-headline font-bold text-xs shadow-lg ${
                s.accent === 'primary'
                  ? 'bg-primary text-primary-foreground shadow-primary/20'
                  : 'bg-secondary/80 text-foreground shadow-secondary/10'
              }`}>{s.n}</div>
              <span className={`text-[9px] font-headline uppercase mt-1.5 tracking-widest ${
                s.accent === 'primary' ? 'text-primary' : 'text-secondary'
              }`}>{s.label}</span>
            </div>
            {i < arr.length - 1 && <div className="w-6 h-px bg-border/40" />}
          </React.Fragment>
        ))}
      </div>
    </div>

    <div className="grid grid-cols-1 md:grid-cols-2 gap-6">

      {/* Step 1 */}
      <div className="bg-card/50 border border-border/40 rounded-xl p-6">
        <div className="flex items-center gap-2 mb-4">
          <div className="w-7 h-7 rounded-full bg-primary flex items-center justify-center font-headline font-bold text-primary-foreground text-xs">1</div>
          <h3 className="text-xl font-headline font-bold text-foreground">Ingestion</h3>
        </div>
        <p className="text-sm text-muted-foreground mb-4 leading-relaxed">
          Chaque fichier déposé passe par une chaîne de traitement en 4 étapes avant d'être indexé :
        </p>
        {/* Mini pipeline diagram */}
        <div className="bg-black/30 rounded-lg p-3 font-mono text-xs space-y-1 mb-4">
          <p className="text-green-400">Fichier (PDF/DOCX/JSON/URL)</p>
          <p className="text-muted-foreground">  ↓ Extraction du texte brut</p>
          <p className="text-muted-foreground">  ↓ Nettoyage (8 passes) : dédup · Unicode · stops</p>
          <p className="text-muted-foreground">  ↓ Découpage : 512 tokens, 64 de recouvrement</p>
          <p className="text-foreground">  ↓ Embedding → ChromaDB (vecteurs 768 dims)</p>
        </div>
        <ul className="space-y-1.5 text-xs text-foreground/80">
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-primary shrink-0" /> Formats : PDF, DOCX, DOC, JSON, XML, TXT, HTML, Avro, ZIP, URL</li>
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-primary shrink-0" /> Parsing layout-aware (tableaux préservés) via docparser optionnel</li>
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-primary shrink-0" /> Gestion de cycle de vie GED : INGESTED → REVIEWED → TRAINED</li>
        </ul>
      </div>

      {/* Step 2 */}
      <div className="bg-card/50 border border-border/40 rounded-xl p-6">
        <div className="flex items-center gap-2 mb-4">
          <div className="w-7 h-7 rounded-full bg-primary flex items-center justify-center font-headline font-bold text-primary-foreground text-xs">2</div>
          <h3 className="text-xl font-headline font-bold text-foreground">Génération Dataset</h3>
        </div>
        <p className="text-sm text-muted-foreground mb-4 leading-relaxed">
          Pour chaque chunk, Spectra génère 3–4 paires d'entraînement couvrant différents types :
        </p>
        <div className="grid grid-cols-2 gap-2 mb-4 text-xs">
          {[
            { type: 'Q&A', desc: 'Question technique + réponse extraite', color: 'border-primary/30 text-primary' },
            { type: 'Résumé', desc: 'Synthèse condensée du chunk', color: 'border-secondary/30 text-secondary' },
            { type: 'Négatif', desc: 'Question hors-sujet → refus factuel', color: 'border-border/40 text-muted-foreground' },
            { type: 'DPO pair', desc: 'Chosen (correct) + Rejected (LLM erroné)', color: 'border-border/40 text-muted-foreground' },
          ].map(t => (
            <div key={t.type} className={`rounded p-2 border ${t.color}`}>
              <p className={`font-bold text-[9px] uppercase tracking-widest ${t.color}`}>{t.type}</p>
              <p className="text-muted-foreground mt-0.5 text-[8px]">{t.desc}</p>
            </div>
          ))}
        </div>
        <p className="text-xs text-muted-foreground">Score de confiance 0–1 assigné à chaque paire. Filtrage au seuil ≥ 0.8 avant fine-tuning.</p>
      </div>

      {/* Step 2c */}
      <div className="bg-card/50 border border-secondary/30 rounded-xl p-6">
        <div className="flex items-center gap-2 mb-4">
          <div className="w-7 h-7 rounded-full bg-secondary/80 flex items-center justify-center font-headline font-bold text-foreground text-xs">2c</div>
          <h3 className="text-xl font-headline font-bold text-foreground">Annotations IA</h3>
          <span className="text-[8px] font-bold uppercase tracking-widest border border-secondary/40 text-secondary px-2 py-0.5 rounded-full">Boucle DPO</span>
        </div>
        <p className="text-sm text-muted-foreground mb-4 leading-relaxed">
          Le LLM génère un commentaire analytique ancré dans les chunks réels du document (RAG). Vos notes 👍/👎 constituent des paires DPO.
        </p>
        <div className="bg-black/30 rounded-lg p-3 font-mono text-xs space-y-1 mb-3">
          <p className="text-secondary">RAG (6 chunks) + focus utilisateur</p>
          <p className="text-muted-foreground">  ↓ LLM génère commentaire (temp=0.4)</p>
          <p className="text-muted-foreground">  ↓ Utilisateur : 👍 APPROVED / 👎 REJECTED</p>
          <p className="text-foreground">  ↓ Export DPO : prompt / chosen / rejected</p>
        </div>
        <ul className="space-y-1.5 text-xs text-foreground/80">
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-secondary shrink-0" /> Garde Jaccard : paires trop similaires automatiquement rejetées</li>
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-secondary shrink-0" /> Auto-trigger : N approbations → re-entraînement lancé automatiquement</li>
        </ul>
      </div>

      {/* Step 3 */}
      <div className="bg-card/50 border border-border/40 rounded-xl p-6">
        <div className="flex items-center gap-2 mb-4">
          <div className="w-7 h-7 rounded-full bg-primary flex items-center justify-center font-headline font-bold text-primary-foreground text-xs">3</div>
          <h3 className="text-xl font-headline font-bold text-foreground">Fine-Tuning</h3>
        </div>
        <p className="text-sm text-muted-foreground mb-4 leading-relaxed">
          Entraîne le modèle de base avec QLoRA 4-bit via Unsloth. Trois recettes préconfigurées :
        </p>
        <div className="space-y-2 text-xs mb-4">
          {[
            { name: 'cpu-rapide',      desc: 'rank 8, 1 époque — test rapide sur CPU',           color: 'text-muted-foreground' },
            { name: 'gpu-qualite',     desc: 'rank 64, 3 époques — meilleure qualité (GPU)',      color: 'text-primary' },
            { name: 'dpo-alignement',  desc: 'rank 32, DPO activé — intègre vos préférences',    color: 'text-secondary' },
          ].map(r => (
            <div key={r.name} className="flex items-start gap-2">
              <code className={`font-mono font-bold text-[9px] ${r.color} shrink-0`}>{r.name}</code>
              <span className="text-muted-foreground">{r.desc}</span>
            </div>
          ))}
        </div>
        <p className="text-xs text-muted-foreground">Sortie : <code className="font-mono bg-black/30 px-1">adapter.gguf</code> dans le répertoire de travail.</p>
      </div>

      {/* Step 4 */}
      <div className="bg-card/50 border border-secondary/30 rounded-xl p-6">
        <div className="flex items-center gap-2 mb-4">
          <div className="w-7 h-7 rounded-full bg-secondary/80 flex items-center justify-center font-headline font-bold text-foreground text-xs">4</div>
          <h3 className="text-xl font-headline font-bold text-foreground">Évaluation LLM-as-Judge</h3>
        </div>
        <p className="text-sm text-muted-foreground mb-4 leading-relaxed">
          Après fine-tuning, lancez une évaluation automatique. Le même LLM joue le rôle de juge et note les réponses de 1 à 10 :
        </p>
        <div className="bg-black/30 rounded-lg p-3 font-mono text-xs space-y-0.5 mb-3">
          <p className="text-muted-foreground">5 % du dataset (min 5, max 50 paires)</p>
          <p className="text-muted-foreground">  ↓ Modèle répond aux questions de test</p>
          <p className="text-muted-foreground">  ↓ LLM-juge compare à la réponse de référence</p>
          <p className="text-foreground">  ↓ Score 1–10 par critère : exactitude, complétude, clarté</p>
        </div>
        <p className="text-xs text-muted-foreground">Les scores par catégorie (qa, summary, classification) apparaissent dans le Dashboard &gt; Cycle de Personnalisation.</p>
      </div>

      {/* Step RAG */}
      <div className="bg-card/50 border border-border/40 rounded-xl p-6 md:col-span-2">
        <div className="flex items-center gap-2 mb-4">
          <div className="w-7 h-7 rounded-full bg-secondary/40 flex items-center justify-center font-headline font-bold text-foreground text-xs">5</div>
          <h3 className="text-xl font-headline font-bold text-foreground">Interrogation RAG — Playground</h3>
        </div>
        <p className="text-sm text-muted-foreground mb-4 leading-relaxed">
          Posez vos questions techniques dans le Playground. Spectra sélectionne automatiquement la stratégie RAG optimale selon la complexité détectée.
        </p>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          {[
            { label: 'Hybrid Search',   desc: 'BM25 + vecteurs → RRF fusion', icon: 'merge' },
            { label: 'Re-ranking',      desc: 'Cross-Encoder 2 étapes', icon: 'sort' },
            { label: 'Agentic ReAct',   desc: 'Boucle itérative multi-hop', icon: 'psychology' },
            { label: 'Corrective RAG',  desc: 'Grading LLM des chunks', icon: 'fact_check' },
          ].map(r => (
            <div key={r.label} className="bg-secondary/20 rounded-lg p-3">
              <span className="material-symbols-outlined text-sm text-primary">{r.icon}</span>
              <p className="text-[9px] font-bold uppercase tracking-widest text-primary mt-1">{r.label}</p>
              <p className="text-[9px] text-muted-foreground mt-0.5">{r.desc}</p>
            </div>
          ))}
        </div>
      </div>

    </div>
  </div>
);

// ─────────────────────────────────────────────────────────────────────────────

const sectionCommenting = () => (
  <div className="space-y-8 animate-in fade-in slide-in-from-right-4 duration-500">

    {/* Hero */}
    <div className="bg-secondary/10 border border-secondary/30 rounded-xl p-8 space-y-4">
      <div className="flex items-center gap-3">
        <div className="w-10 h-10 rounded-lg bg-secondary/20 flex items-center justify-center">
          <svg className="w-5 h-5 text-secondary" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z" />
          </svg>
        </div>
        <div>
          <h2 className="text-2xl font-headline font-bold text-foreground">Commentaires IA — Boucle RAG + DPO</h2>
          <p className="text-sm text-muted-foreground">Étape 2c du pipeline — disponible dans la page Database</p>
        </div>
      </div>
      <p className="text-sm text-foreground/80 leading-relaxed">
        Chaque document GED peut recevoir des commentaires analytiques générés automatiquement par le LLM,
        ancrés dans le contenu réel du document via RAG. Vos évaluations (👍/👎) constituent des
        paires DPO qui alimentent le prochain cycle de fine-tuning. Quand le seuil d'approbations configuré
        est atteint, <strong>le re-entraînement se déclenche automatiquement</strong>.
      </p>
    </div>

    {/* Pourquoi c'est optimal */}
    <div className="bg-card/50 border border-border/40 rounded-xl p-8 space-y-6">
      <h3 className="text-lg font-headline font-bold text-foreground">Pourquoi RAG + Fine-tuning DPO est optimal</h3>
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {[
          {
            icon: 'search',
            title: 'RAG seul',
            body: 'Le commentaire est ancré dans le document réel — pas d\'hallucination. Mais le style et la terminologie restent génériques.',
            badge: 'Factuel mais générique',
            accent: 'border-outline-variant/40 text-muted-foreground',
          },
          {
            icon: 'model_training',
            title: 'Fine-tuning seul',
            body: 'Le modèle apprend le style de votre domaine. Mais sans contexte RAG, il peut inventer des détails absents du document.',
            badge: 'Style adapté mais fragile',
            accent: 'border-outline-variant/40 text-muted-foreground',
          },
          {
            icon: 'sync',
            title: 'RAG + DPO (optimal)',
            body: 'RAG ancre chaque commentaire dans les chunks réels. DPO aligne le modèle sur vos préférences qualité. Chaque cycle améliore la pertinence.',
            badge: 'Factuel ET aligné ✓',
            accent: 'border-primary/40 text-primary bg-primary/5',
          },
        ].map(c => (
          <div key={c.title} className={`rounded-xl p-5 border ${c.accent}`}>
            <div className="flex items-center gap-2 mb-3">
              <span className="material-symbols-outlined text-base">{c.icon}</span>
              <span className="font-headline font-bold text-sm">{c.title}</span>
            </div>
            <p className="text-xs text-muted-foreground leading-relaxed mb-3">{c.body}</p>
            <span className={`text-[8px] font-bold uppercase tracking-widest border px-2 py-0.5 rounded-full ${c.accent}`}>
              {c.badge}
            </span>
          </div>
        ))}
      </div>
    </div>

    {/* Cycle complet */}
    <div className="bg-card/50 border border-border/40 rounded-xl p-8 space-y-6">
      <h3 className="text-lg font-headline font-bold text-foreground">Cycle de vie complet d'un commentaire IA</h3>
      <div className="space-y-3">
        {[
          { n: '1', icon: 'chat_bubble',     label: 'Focus utilisateur',        desc: 'Fournissez un angle d\'analyse précis ("procédures d\'urgence") ou laissez vide pour un résumé général.', color: 'text-primary border-primary/30 bg-primary/5' },
          { n: '2', icon: 'search',          label: 'Retrieval RAG',             desc: 'Spectra récupère les 6 passages les plus pertinents du document dans ChromaDB via recherche hybride.', color: 'text-primary border-primary/30 bg-primary/5' },
          { n: '3', icon: 'memory',          label: 'Génération LLM (temp 0.4)', desc: 'Le LLM rédige un commentaire analytique factuel ancré dans ces passages. Température basse = moins d\'inventions.', color: 'text-secondary border-secondary/30 bg-secondary/5' },
          { n: '4', icon: 'thumb_up',        label: 'Évaluation 👍/👎',         desc: 'Vous notez APPROVED ou REJECTED. Chaque note crée une préférence. La garde Jaccard filtre les paires trop similaires.', color: 'text-secondary border-secondary/30 bg-secondary/5' },
          { n: '5', icon: 'download',        label: 'Export DPO',                desc: 'Cliquez "DPO↓" — Spectra exporte les paires (chosen, rejected) en JSONL. Les paires Jaccard > 0.85 sont exclues.', color: 'text-primary border-primary/30 bg-primary/5' },
          { n: '↺', icon: 'auto_mode',       label: 'Re-entraînement auto',      desc: 'Au N-ième commentaire approuvé (seuil configurable), un job fine-tuning DPO se soumet automatiquement.', color: 'text-primary border-primary/30 bg-primary/5' },
        ].map(step => (
          <div key={step.n} className={`flex items-start gap-4 p-4 rounded-lg border ${step.color}`}>
            <div className={`w-7 h-7 rounded-full flex items-center justify-center font-headline font-bold text-xs shrink-0 border ${step.color}`}>
              {step.n}
            </div>
            <div className="flex items-start gap-3 flex-1 min-w-0">
              <span className={`material-symbols-outlined text-sm mt-0.5 shrink-0 ${step.color}`}>{step.icon}</span>
              <div>
                <p className="font-bold text-sm text-foreground">{step.label}</p>
                <p className="text-xs text-muted-foreground mt-0.5 leading-relaxed">{step.desc}</p>
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>

    {/* Mode d'emploi */}
    <div className="bg-card/50 border border-border/40 rounded-xl p-8 space-y-5">
      <h3 className="text-lg font-headline font-bold text-foreground">Mode d'emploi dans l'interface</h3>
      <ol className="space-y-4 text-sm text-foreground/80">
        <li className="flex gap-3">
          <span className="w-6 h-6 rounded-full bg-primary/20 text-primary flex items-center justify-center font-bold text-xs shrink-0 mt-0.5">1</span>
          <span>Cliquez sur <strong>Database</strong> dans le menu latéral, puis sur un document pour ouvrir sa fiche.</span>
        </li>
        <li className="flex gap-3">
          <span className="w-6 h-6 rounded-full bg-primary/20 text-primary flex items-center justify-center font-bold text-xs shrink-0 mt-0.5">2</span>
          <span>Dans la section <strong>Commentaires</strong>, choisissez l'onglet <strong>✦ IA</strong>.</span>
        </li>
        <li className="flex gap-3">
          <span className="w-6 h-6 rounded-full bg-primary/20 text-primary flex items-center justify-center font-bold text-xs shrink-0 mt-0.5">3</span>
          <span>Entrez un angle d'analyse (<em>ex. "procédures de sécurité"</em>) et cliquez <strong>✦ Générer via RAG</strong>.</span>
        </li>
        <li className="flex gap-3">
          <span className="w-6 h-6 rounded-full bg-secondary/30 text-secondary flex items-center justify-center font-bold text-xs shrink-0 mt-0.5">4</span>
          <span>Lisez le commentaire dans l'onglet <strong>Liste</strong> et cliquez <strong>👍</strong> ou <strong>👎</strong>. Dès que le seuil est atteint, la barre de progression dans le Dashboard se remplit.</span>
        </li>
        <li className="flex gap-3">
          <span className="w-6 h-6 rounded-full bg-secondary/30 text-secondary flex items-center justify-center font-bold text-xs shrink-0 mt-0.5">5</span>
          <span>Répétez sur plusieurs documents. Le re-entraînement DPO se déclenche <strong>automatiquement</strong> au seuil (défaut : 5 approbations).</span>
        </li>
        <li className="flex gap-3">
          <span className="w-6 h-6 rounded-full bg-primary/20 text-primary flex items-center justify-center font-bold text-xs shrink-0 mt-0.5">6</span>
          <span>Suivez la progression dans <strong>Fine-Tuning</strong>, puis lancez une <strong>Évaluation</strong> pour mesurer le gain.</span>
        </li>
      </ol>
    </div>

    {/* API reference */}
    <div className="bg-card/50 border border-border/40 rounded-xl p-8">
      <h3 className="text-lg font-headline font-bold text-foreground mb-4">Référence API</h3>
      <div className="bg-black/40 p-5 rounded-lg font-mono text-sm border border-border/20 space-y-1 overflow-x-auto">
        <p className="text-green-400"># Lister les commentaires d'un document</p>
        <p className="text-foreground mb-3">GET /api/ged/documents/{'{sha256}'}/comments</p>

        <p className="text-green-400"># Générer un commentaire IA via RAG</p>
        <p className="text-foreground">POST /api/ged/documents/{'{sha256}'}/comments</p>
        <p className="text-muted-foreground mb-3">{'{ "content": "procédures de sécurité", "generate": true }'}</p>

        <p className="text-green-400"># Évaluer (APPROVED / REJECTED / NONE)</p>
        <p className="text-foreground mb-3">PATCH /api/ged/documents/{'{sha256}'}/comments/{'{id}'}/rating?rating=APPROVED</p>

        <p className="text-green-400"># Exporter les paires DPO (filtrées Jaccard)</p>
        <p className="text-foreground">POST /api/ged/documents/export/comments-dpo</p>
        <p className="text-muted-foreground mb-3">{'→ { "pairs": 12, "file": "data/dataset/comments_dpo.jsonl" }'}</p>

        <p className="text-green-400"># Métriques du cycle de personnalisation</p>
        <p className="text-foreground">GET /api/metrics/personalization</p>
        <p className="text-muted-foreground">{'→ { "approvedComments": 8, "dpoPairs": 6, "completedCycles": 1, "nextTriggerIn": 2, ... }'}</p>
      </div>
    </div>

  </div>
);

// ─────────────────────────────────────────────────────────────────────────────

const sectionPersonalisation = () => (
  <div className="space-y-8 animate-in fade-in slide-in-from-right-4 duration-500">

    {/* Hero */}
    <div className="bg-primary/10 border border-primary/30 rounded-xl p-8">
      <div className="flex items-center gap-3 mb-4">
        <span className="material-symbols-outlined text-2xl text-primary">auto_mode</span>
        <h2 className="text-2xl font-headline font-bold text-foreground">Personnalisation Continue</h2>
        <span className="text-[8px] font-bold uppercase tracking-widest border border-primary/40 text-primary px-2 py-0.5 rounded-full">v1.1</span>
      </div>
      <p className="text-sm text-foreground/80 leading-relaxed">
        Spectra implémente une <strong>boucle de personnalisation automatique</strong> en 4 mécanismes complémentaires.
        Ensemble, ils garantissent que vos annotations humaines améliorent réellement le modèle à chaque cycle.
      </p>
    </div>

    {/* ── Feature 1 : Auto-trigger ── */}
    <div className="bg-card/50 border border-border/40 rounded-xl p-8 space-y-6">
      <div className="flex items-start gap-3">
        <div className="w-8 h-8 rounded-full bg-primary flex items-center justify-center font-headline font-bold text-primary-foreground text-sm shrink-0">1</div>
        <div>
          <h3 className="text-lg font-headline font-bold text-foreground">Déclencheur automatique de re-entraînement</h3>
          <p className="text-xs text-muted-foreground mt-1 uppercase tracking-widest">ArticleCommentService → FineTuningService</p>
        </div>
      </div>

      <p className="text-sm text-foreground/80 leading-relaxed">
        À chaque approbation de commentaire (👍), Spectra compte le total de commentaires IA approuvés.
        Quand ce total atteint un <strong>multiple du seuil configuré</strong>, un job de fine-tuning DPO
        est soumis automatiquement — sans intervention.
      </p>

      {/* Diagram */}
      <div className="bg-black/40 rounded-xl p-6 border border-border/20">
        <p className="text-[9px] font-headline uppercase tracking-widest text-muted-foreground mb-4">Schéma de déclenchement</p>
        <div className="font-mono text-xs space-y-1">
          <div className="flex items-center gap-2">
            <span className="text-muted-foreground w-28">Approbation 1</span>
            <div className="flex gap-0.5">
              <div className="w-6 h-3 bg-primary/40 rounded-sm" />
              <div className="w-6 h-3 bg-border/20 rounded-sm" />
              <div className="w-6 h-3 bg-border/20 rounded-sm" />
              <div className="w-6 h-3 bg-border/20 rounded-sm" />
              <div className="w-6 h-3 bg-border/20 rounded-sm" />
            </div>
            <span className="text-muted-foreground text-[9px]">1 / 5</span>
          </div>
          <div className="flex items-center gap-2">
            <span className="text-muted-foreground w-28">Approbation 3</span>
            <div className="flex gap-0.5">
              <div className="w-6 h-3 bg-primary/40 rounded-sm" />
              <div className="w-6 h-3 bg-primary/40 rounded-sm" />
              <div className="w-6 h-3 bg-primary/40 rounded-sm" />
              <div className="w-6 h-3 bg-border/20 rounded-sm" />
              <div className="w-6 h-3 bg-border/20 rounded-sm" />
            </div>
            <span className="text-muted-foreground text-[9px]">3 / 5</span>
          </div>
          <div className="flex items-center gap-2">
            <span className="text-foreground w-28 font-bold">Approbation 5</span>
            <div className="flex gap-0.5">
              <div className="w-6 h-3 bg-primary rounded-sm" />
              <div className="w-6 h-3 bg-primary rounded-sm" />
              <div className="w-6 h-3 bg-primary rounded-sm" />
              <div className="w-6 h-3 bg-primary rounded-sm" />
              <div className="w-6 h-3 bg-primary rounded-sm" />
            </div>
            <span className="text-primary font-bold text-[9px] flex items-center gap-1">
              5 / 5 → <span className="material-symbols-outlined text-[11px]">rocket_launch</span> DPO FT lancé !
            </span>
          </div>
          <div className="flex items-center gap-2 mt-1">
            <span className="text-muted-foreground w-28">Approbation 10</span>
            <div className="flex gap-0.5">
              <div className="w-6 h-3 bg-primary rounded-sm" />
              <div className="w-6 h-3 bg-primary rounded-sm" />
              <div className="w-6 h-3 bg-primary rounded-sm" />
              <div className="w-6 h-3 bg-primary rounded-sm" />
              <div className="w-6 h-3 bg-primary rounded-sm" />
            </div>
            <span className="text-primary text-[9px] flex items-center gap-1">
              10 / 5 → <span className="material-symbols-outlined text-[11px]">rocket_launch</span> 2ème cycle
            </span>
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <div className="bg-black/30 rounded-lg p-4">
          <p className="text-[9px] font-headline uppercase tracking-widest text-primary mb-2">Configuration</p>
          <div className="font-mono text-xs space-y-1 text-foreground/80">
            <p><span className="text-muted-foreground">Clé :</span> spectra.ged.auto-retrain-threshold</p>
            <p><span className="text-muted-foreground">Défaut :</span> 5</p>
            <p><span className="text-muted-foreground">Env var :</span> SPECTRA_GED_AUTO_RETRAIN_THRESHOLD</p>
          </div>
        </div>
        <div className="bg-black/30 rounded-lg p-4">
          <p className="text-[9px] font-headline uppercase tracking-widest text-secondary mb-2">Ce qui se passe</p>
          <div className="text-xs space-y-1 text-foreground/80">
            <p>1. <code className="font-mono">exportDpoPairs()</code> → <code className="font-mono">comments_dpo.jsonl</code></p>
            <p>2. <code className="font-mono">FineTuningService.submit()</code> avec <code className="font-mono">dpoEnabled=true</code></p>
            <p>3. Exécution asynchrone (hors thread HTTP)</p>
          </div>
        </div>
      </div>
    </div>

    {/* ── Feature 2 : Jaccard ── */}
    <div className="bg-card/50 border border-border/40 rounded-xl p-8 space-y-6">
      <div className="flex items-start gap-3">
        <div className="w-8 h-8 rounded-full bg-secondary/80 flex items-center justify-center font-headline font-bold text-foreground text-sm shrink-0">2</div>
        <div>
          <h3 className="text-lg font-headline font-bold text-foreground">Garde de qualité DPO — Similarité de Jaccard</h3>
          <p className="text-xs text-muted-foreground mt-1 uppercase tracking-widest">DpoGenerationService · ArticleCommentService</p>
        </div>
      </div>

      <p className="text-sm text-foreground/80 leading-relaxed">
        Une paire DPO n'a de valeur que si <code className="font-mono bg-black/30 px-1">chosen</code> et <code className="font-mono bg-black/30 px-1">rejected</code> sont <strong>vraiment différents</strong>.
        Si le LLM génère une réponse "incorrecte" quasi-identique à la réponse correcte,
        l'entraînement DPO ne peut pas apprendre la distinction — et peut même dégrader le modèle.
      </p>

      {/* Formula */}
      <div className="bg-black/40 rounded-xl p-6 border border-border/20 space-y-4">
        <p className="text-[9px] font-headline uppercase tracking-widest text-muted-foreground">Formule de Jaccard sur ensembles de mots</p>
        <div className="flex items-center justify-center">
          <div className="font-mono text-sm text-center space-y-1">
            <p className="text-foreground/60 text-xs">Soient A = mots de <span className="text-primary">chosen</span>, B = mots de <span className="text-secondary">rejected</span></p>
            <div className="bg-secondary/20 px-6 py-3 rounded-lg inline-block mt-2">
              <p className="text-foreground text-base">
                J(A, B) = <span className="text-primary">|A ∩ B|</span> / <span className="text-secondary">|A ∪ B|</span>
              </p>
            </div>
            <p className="text-muted-foreground text-xs mt-2">
              Résultat ∈ [0, 1] · Si J &gt; <span className="text-primary font-bold">0.85</span> → paire rejetée
            </p>
          </div>
        </div>
      </div>

      {/* Worked example */}
      <div className="space-y-3">
        <p className="text-[9px] font-headline uppercase tracking-widest text-muted-foreground">Exemple concret</p>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
          <div className="bg-green-500/5 border border-green-500/20 rounded-lg p-4">
            <p className="text-[9px] font-bold uppercase tracking-widest text-green-400 mb-2">Paire ACCEPTÉE ✓</p>
            <div className="font-mono text-xs space-y-2">
              <div>
                <p className="text-primary text-[9px] uppercase">chosen</p>
                <p className="text-foreground/80">"Le document décrit 5 étapes : alerte, confinement, évacuation, intervention, retour-à-la-normale"</p>
              </div>
              <div>
                <p className="text-secondary text-[9px] uppercase">rejected</p>
                <p className="text-foreground/60">"Ce rapport résume les obligations légales de sécurité en 3 points principaux"</p>
              </div>
              <div className="border-t border-border/20 pt-2">
                <p className="text-muted-foreground">A ∩ B = {'{'}le, de{'}'} = 2 mots</p>
                <p className="text-muted-foreground">A ∪ B ≈ 22 mots</p>
                <p className="text-green-400 font-bold">J = 2/22 = <strong>0.09</strong> → ACCEPTÉE</p>
              </div>
            </div>
          </div>

          <div className="bg-red-500/5 border border-red-500/20 rounded-lg p-4">
            <p className="text-[9px] font-bold uppercase tracking-widest text-red-400 mb-2">Paire REJETÉE ✗</p>
            <div className="font-mono text-xs space-y-2">
              <div>
                <p className="text-primary text-[9px] uppercase">chosen</p>
                <p className="text-foreground/80">"Le document décrit 5 étapes : alerte, confinement, évacuation, intervention, retour-à-la-normale"</p>
              </div>
              <div>
                <p className="text-secondary text-[9px] uppercase">rejected</p>
                <p className="text-foreground/60">"Le document décrit 5 étapes : alerte, confinement, évacuation, intervention, normalisation"</p>
              </div>
              <div className="border-t border-border/20 pt-2">
                <p className="text-muted-foreground">A ∩ B = 13 mots communs</p>
                <p className="text-muted-foreground">A ∪ B = 15 mots</p>
                <p className="text-red-400 font-bold">J = 13/15 = <strong>0.87</strong> → REJETÉE (› 0.85)</p>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div className="flex items-start gap-2 p-3 bg-secondary/5 border border-secondary/20 rounded-lg">
        <span className="material-symbols-outlined text-sm text-secondary shrink-0 mt-0.5">info</span>
        <p className="text-xs text-muted-foreground">
          Le seuil 0.85 est codé en constante <code className="font-mono bg-black/30 px-1">SIMILARITY_THRESHOLD</code> dans les deux services.
          Un seuil plus bas (ex. 0.70) est plus strict — il filtre aussi des paires légitimement proches.
          0.85 est un bon compromis pour des textes techniques.
        </p>
      </div>
    </div>

    {/* ── Feature 3 : Model sync ── */}
    <div className="bg-card/50 border border-border/40 rounded-xl p-8 space-y-6">
      <div className="flex items-start gap-3">
        <div className="w-8 h-8 rounded-full bg-primary flex items-center justify-center font-headline font-bold text-primary-foreground text-sm shrink-0">3</div>
        <div>
          <h3 className="text-lg font-headline font-bold text-foreground">Vérification registre ↔ serveur llama</h3>
          <p className="text-xs text-muted-foreground mt-1 uppercase tracking-widest">LlamaCppChatClient · ModelRegistryService</p>
        </div>
      </div>

      <p className="text-sm text-foreground/80 leading-relaxed">
        Spectra maintient un registre JSON local des modèles (<code className="font-mono bg-black/30 px-1">data/models/registry.json</code>)
        indépendamment de llama-server. En cas de désynchronisation — le registre désigne un modèle que le
        serveur ne connaît pas — toutes les requêtes échoueront silencieusement.
      </p>

      {/* Problem/Solution diagram */}
      <div className="bg-black/40 rounded-xl p-6 border border-border/20 space-y-4">
        <p className="text-[9px] font-headline uppercase tracking-widest text-muted-foreground">Problème de désynchronisation</p>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 text-xs font-mono">
          <div className="bg-red-500/10 border border-red-500/20 rounded p-3">
            <p className="text-red-400 font-bold text-[9px] uppercase mb-2">Registre (registry.json)</p>
            <p className="text-foreground">activeChatModel:</p>
            <p className="text-primary font-bold">"phi-4-mini-finetuned"</p>
          </div>
          <div className="flex items-center justify-center text-muted-foreground/40 text-2xl">≠</div>
          <div className="bg-red-500/10 border border-red-500/20 rounded p-3">
            <p className="text-red-400 font-bold text-[9px] uppercase mb-2">llama-server (GET /v1/models)</p>
            <p className="text-foreground">models[0].id:</p>
            <p className="text-secondary font-bold">"phi-3-mini"</p>
          </div>
        </div>
        <div className="flex items-center gap-2 p-2 bg-red-500/5 rounded">
          <span className="material-symbols-outlined text-sm text-red-400">error</span>
          <p className="text-xs text-red-400">Résultat : toutes les requêtes vers "phi-4-mini-finetuned" échouent (modèle introuvable)</p>
        </div>
      </div>

      {/* Solution */}
      <div className="bg-black/40 rounded-xl p-6 border border-border/20 space-y-3">
        <p className="text-[9px] font-headline uppercase tracking-widest text-muted-foreground">Solution implémentée</p>
        <div className="font-mono text-xs space-y-1 text-foreground/80">
          <p><span className="text-primary">setActiveModel(</span><span className="text-secondary">"phi-4-mini-finetuned"</span><span className="text-primary">)</span></p>
          <p className="text-muted-foreground">  ↓ 1. Mise à jour du registre</p>
          <p className="text-muted-foreground">  ↓ 2. runtimeOrchestrator.ensureChatModelServed()</p>
          <p className="text-foreground">  ↓ 3. <span className="text-secondary">CompletableFuture.runAsync</span> → checkHealth()</p>
          <p className="text-muted-foreground pl-8">Si status ≠ "ok" → WARN dans les logs</p>
          <p className="text-muted-foreground pl-8">"ALERTE REGISTRE/SERVEUR : modèle 'X' actif</p>
          <p className="text-muted-foreground pl-8"> mais non servi par llama-server"</p>
        </div>
        <div className="flex items-start gap-2 p-2 bg-green-500/5 border border-green-500/20 rounded">
          <span className="material-symbols-outlined text-sm text-green-400 shrink-0">check_circle</span>
          <p className="text-xs text-green-400">La vérification est asynchrone : elle ne bloque pas le changement de modèle.</p>
        </div>
      </div>

      <div className="bg-black/30 rounded-lg p-4">
        <p className="text-[9px] font-headline uppercase tracking-widest text-muted-foreground mb-2">Surveiller les désynchronisations</p>
        <div className="font-mono text-xs text-foreground/70 space-y-1">
          <p className="text-green-400"># Chercher dans les logs</p>
          <p>docker compose logs spectra-api | grep "ALERTE REGISTRE"</p>
          <p className="text-green-400 mt-2"># Vérifier le modèle actif côté serveur</p>
          <p>curl http://localhost:8081/v1/models | jq '.data[].id'</p>
        </div>
      </div>
    </div>

    {/* ── Feature 4 : Metrics Dashboard ── */}
    <div className="bg-card/50 border border-border/40 rounded-xl p-8 space-y-6">
      <div className="flex items-start gap-3">
        <div className="w-8 h-8 rounded-full bg-secondary/80 flex items-center justify-center font-headline font-bold text-foreground text-sm shrink-0">4</div>
        <div>
          <h3 className="text-lg font-headline font-bold text-foreground">Dashboard de métriques de personnalisation</h3>
          <p className="text-xs text-muted-foreground mt-1 uppercase tracking-widest">PersonalizationMetricsService · GET /api/metrics/personalization</p>
        </div>
      </div>

      <p className="text-sm text-foreground/80 leading-relaxed">
        Un nouvel endpoint agrège en temps réel toutes les métriques de la boucle de personnalisation.
        Le Dashboard affiche ces données dans la section <strong>"Cycle de Personnalisation"</strong>.
      </p>

      {/* Metrics map */}
      <div className="bg-black/40 rounded-xl p-6 border border-border/20">
        <p className="text-[9px] font-headline uppercase tracking-widest text-muted-foreground mb-4">Structure de la réponse</p>
        <div className="font-mono text-xs space-y-0.5 overflow-x-auto">
          <p className="text-foreground">{`GET /api/metrics/personalization`}</p>
          <p className="text-muted-foreground">{`{`}</p>
          <p className="text-muted-foreground pl-4"><span className="text-primary">"approvedComments"</span>{`: 12,`}<span className="text-muted-foreground/50 ml-3">//  commentaires IA notés 👍</span></p>
          <p className="text-muted-foreground pl-4"><span className="text-primary">"rejectedComments"</span>{`: 4,`} <span className="text-muted-foreground/50 ml-3">//  commentaires IA notés 👎</span></p>
          <p className="text-muted-foreground pl-4"><span className="text-primary">"totalAiComments"</span>{`: 20,`}<span className="text-muted-foreground/50 ml-3">//  tous les commentaires IA</span></p>
          <p className="text-muted-foreground pl-4"><span className="text-secondary">"dpoPairs"</span>{`: 9,`}        <span className="text-muted-foreground/50 ml-3">//  paires valides en mémoire</span></p>
          <p className="text-muted-foreground pl-4"><span className="text-secondary">"completedCycles"</span>{`: 2,`}  <span className="text-muted-foreground/50 ml-3">//  cycles auto déclenchés</span></p>
          <p className="text-muted-foreground pl-4"><span className="text-secondary">"nextTriggerIn"</span>{`: 3,`}   <span className="text-muted-foreground/50 ml-3">//  approbations avant prochain trigger</span></p>
          <p className="text-muted-foreground pl-4"><span className="text-primary">"autoRetrainThreshold"</span>{`: 5,`}</p>
          <p className="text-muted-foreground pl-4"><span className="text-primary">"completedFineTuningJobs"</span>{`: 2,`}</p>
          <p className="text-muted-foreground pl-4"><span className="text-secondary">"latestEvalScore"</span>{`: 7.3,`}<span className="text-muted-foreground/50 ml-3">//  score moyen /10 dernier cycle</span></p>
          <p className="text-muted-foreground pl-4"><span className="text-muted-foreground">"fineTuningJobs"</span>{`: [...],`}</p>
          <p className="text-muted-foreground pl-4"><span className="text-muted-foreground">"evaluations"</span>{`: [...]`}</p>
          <p className="text-muted-foreground">{`}`}</p>
        </div>
      </div>

      {/* Dashboard visual */}
      <div className="space-y-3">
        <p className="text-[9px] font-headline uppercase tracking-widest text-muted-foreground">Ce que vous voyez dans le Dashboard</p>
        <div className="grid grid-cols-4 gap-3">
          {[
            { label: 'Approuvés', value: '12', color: 'border-primary text-primary' },
            { label: 'Paires DPO', value: '9', color: 'border-secondary text-secondary' },
            { label: 'Fine-Tunings', value: '2', color: 'border-border/40 text-foreground' },
            { label: 'Score Éval.', value: '7.3/10', color: 'border-border/40 text-foreground' },
          ].map(m => (
            <div key={m.label} className={`bg-black/30 rounded p-3 border-t-2 ${m.color}`}>
              <p className="text-[8px] uppercase tracking-widest text-muted-foreground">{m.label}</p>
              <p className={`font-headline font-bold text-xl mt-1 ${m.color}`}>{m.value}</p>
            </div>
          ))}
        </div>

        {/* Progress bar */}
        <div className="bg-black/30 rounded-lg p-4">
          <div className="flex justify-between mb-2">
            <p className="text-[9px] uppercase tracking-widest text-muted-foreground">Prochain re-entraînement auto</p>
            <p className="text-[9px] font-mono text-muted-foreground">seuil : 5</p>
          </div>
          <div className="w-full bg-border/20 h-2 rounded-full">
            <div className="h-2 bg-primary rounded-full" style={{ width: '80%' }} />
          </div>
          <div className="flex justify-between mt-1">
            <p className="text-[8px] text-muted-foreground">12 / 15 (2ème cycle + 2 sur 3ème)</p>
            <p className="text-[8px] text-muted-foreground">encore 3 approbations</p>
          </div>
        </div>
      </div>
    </div>

  </div>
);

// ─────────────────────────────────────────────────────────────────────────────

const sectionInterface = () => (
  <div className="space-y-6 animate-in fade-in slide-in-from-right-4 duration-500">
    <div className="bg-card/50 border border-border/40 rounded-xl p-8">
      <h2 className="text-2xl font-headline font-bold text-foreground mb-8">Navigation & Fonctionnalités</h2>

      <div className="space-y-8">
        {[
          {
            key: 'Dashboard',
            title: 'Surveillance en temps réel',
            body: 'État de santé des services (LLM, ChromaDB), statistiques de la base de connaissances, section "Cycle de Personnalisation" (approuvés, DPO, FT, score d\'évaluation + barre de progression vers le prochain déclenchement auto).',
            icon: 'dashboard',
            badge: 'Métriques auto',
          },
          {
            key: 'Datasets',
            title: 'Ingestion & Génération',
            body: 'Zone de drop pour vos documents (PDF, DOCX, TXT, URL). Suivez l\'ingestion chunk par chunk. Lancez la génération du dataset Q/A avec le curseur Max Chunks.',
            icon: 'cloud_upload',
          },
          {
            key: 'Database',
            title: 'GED + Commentaires IA',
            body: 'Fiche complète de chaque document : cycle de vie, tags, audit trail. Section Commentaires avec 3 onglets : Liste / Manuel / ✦ IA — génération RAG, évaluation DPO, export. Le compteur d\'approbations alimente la barre de progression du Dashboard.',
            icon: 'analytics',
            badge: 'Commentaires IA',
          },
          {
            key: 'Fine-Tuning',
            title: 'Entraînement & Logs',
            body: 'Lancez des jobs manuels (recettes CPU/GPU/DPO) ou consultez les jobs auto-déclenchés. Visualisez la télémétrie en direct (loss, epoch). Les jobs auto-DPO apparaissent avec le préfixe "auto-dpo-".',
            icon: 'history',
            badge: 'Jobs auto-DPO',
          },
          {
            key: 'Playground',
            title: 'Laboratoire de Tests',
            body: 'Interrogez vos modèles avec le RAG complet. Activez/désactivez la Knowledge Base pour comparer avec et sans contexte documentaire. Historique de conversation multi-tour.',
            icon: 'chat_bubble',
          },
          {
            key: 'Comparison',
            title: 'Benchmark de Modèles',
            body: 'Comparez côte-à-côte deux modèles sur une même question. Utilisez cette page pour mesurer le gain de qualité après un cycle auto-DPO (score LLM-as-judge avant/après).',
            icon: 'compare_arrows',
          },
        ].map(item => (
          <div key={item.key} className="flex gap-6">
            <div className="w-28 shrink-0 flex flex-col items-start gap-1 pt-1">
              <span className="material-symbols-outlined text-sm text-primary">{item.icon}</span>
              <span className="font-headline uppercase tracking-widest text-[10px] text-primary">{item.key}</span>
              {item.badge && (
                <span className="text-[7px] font-bold uppercase border border-secondary/40 text-secondary px-1.5 py-0.5 rounded-full tracking-widest">
                  {item.badge}
                </span>
              )}
            </div>
            <div className="space-y-1.5 border-l border-border/20 pl-6">
              <h4 className="font-bold text-foreground">{item.title}</h4>
              <p className="text-sm text-muted-foreground leading-relaxed">{item.body}</p>
            </div>
          </div>
        ))}
      </div>
    </div>
  </div>
);

// ─────────────────────────────────────────────────────────────────────────────

const sectionBenchmark = () => (
  <div className="space-y-8 animate-in fade-in slide-in-from-right-4 duration-500">
    <div className="bg-card/50 border border-border/40 rounded-xl p-8">
      <h2 className="text-2xl font-headline font-bold text-primary mb-2">Benchmark turboquant</h2>
      <p className="text-sm text-muted-foreground mb-1">
        Résultats mesurés le <strong>2 avril 2026</strong> — matériel : CPU 4 threads (conteneur Docker, WSL2), pas de GPU.
      </p>
      <p className="text-sm text-muted-foreground">
        Fork : <code className="bg-black/40 px-1.5 py-0.5 rounded text-primary">TheTom/llama-cpp-turboquant</code> build <code className="bg-black/40 px-1.5 py-0.5 rounded text-primary">9c600bc</code>
      </p>
    </div>

    <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
      <div className="bg-secondary/20 border border-border/40 rounded-xl p-6">
        <h3 className="text-sm font-headline font-bold uppercase tracking-widest text-primary mb-3">Modèle Chat</h3>
        <p className="text-foreground font-bold">Phi-3.5-mini-instruct Q4_K_M</p>
        <p className="text-xs text-muted-foreground mt-1">2.23 GiB · 3.82 B paramètres · KV cache q8_0</p>
      </div>
      <div className="bg-secondary/20 border border-border/40 rounded-xl p-6">
        <h3 className="text-sm font-headline font-bold uppercase tracking-widest text-primary mb-3">Modèle Embedding</h3>
        <p className="text-foreground font-bold">nomic-embed-text-v1.5 Q4_K_M</p>
        <p className="text-xs text-muted-foreground mt-1">79.5 MiB · 136.7 M paramètres · 768 dimensions</p>
      </div>
    </div>

    <div className="bg-card/50 border border-border/40 rounded-xl p-8">
      <h3 className="text-lg font-headline font-bold text-foreground mb-1">1. Débit natif (llama-bench)</h3>
      <p className="text-xs text-muted-foreground mb-6">3 répétitions par test · PP = prompt prefill · TG = text generation</p>
      <div className="space-y-6">
        <div>
          <p className="text-xs font-headline uppercase tracking-widest text-muted-foreground mb-3">Modèle chat — 4 threads CPU</p>
          <div className="overflow-x-auto">
            <table className="w-full text-sm font-mono border-collapse">
              <thead>
                <tr className="text-xs text-muted-foreground border-b border-border/40">
                  <th className="text-left py-2 pr-6">Test</th>
                  <th className="text-right py-2 pr-6">tokens/s</th>
                  <th className="text-right py-2">±</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border/20">
                {[
                  { test: 'pp128',  val: '34.24', err: '0.97' },
                  { test: 'pp512',  val: '33.96', err: '0.60' },
                  { test: 'pp1024', val: '32.52', err: '0.45' },
                  { test: 'tg64',   val: '12.46', err: '0.18' },
                  { test: 'tg128',  val: '12.15', err: '0.11' },
                ].map(row => (
                  <tr key={row.test} className="hover:bg-white/5 transition-colors">
                    <td className="py-2 pr-6 text-primary">{row.test}</td>
                    <td className="py-2 pr-6 text-right text-foreground font-bold">{row.val}</td>
                    <td className="py-2 text-right text-muted-foreground">{row.err}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
        <div>
          <p className="text-xs font-headline uppercase tracking-widest text-muted-foreground mb-3">Modèle embedding — 2 threads CPU</p>
          <div className="overflow-x-auto">
            <table className="w-full text-sm font-mono border-collapse">
              <thead>
                <tr className="text-xs text-muted-foreground border-b border-border/40">
                  <th className="text-left py-2 pr-6">Test</th>
                  <th className="text-right py-2 pr-6">tokens/s</th>
                  <th className="text-right py-2">±</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border/20">
                {[
                  { test: 'pp128 (embed)', val: '418.44', err: '51.44' },
                  { test: 'pp512 (embed)', val: '370.08', err: '35.59' },
                ].map(row => (
                  <tr key={row.test} className="hover:bg-white/5 transition-colors">
                    <td className="py-2 pr-6 text-primary">{row.test}</td>
                    <td className="py-2 pr-6 text-right text-foreground font-bold">{row.val}</td>
                    <td className="py-2 text-right text-muted-foreground">{row.err}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>

    <div className="bg-card/50 border border-border/40 rounded-xl p-8">
      <h3 className="text-lg font-headline font-bold text-foreground mb-1">2. Latence API Spectra</h3>
      <p className="text-xs text-muted-foreground mb-6">Mesures bout-en-bout via HTTP · inclut sérialisation, tokenisation et overhead Docker réseau</p>
      <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
        <div className="bg-secondary/20 rounded-xl p-5">
          <h4 className="text-sm font-headline font-bold text-foreground mb-3">Embedding (10 × ~864 tokens)</h4>
          <div className="space-y-2 text-sm font-mono">
            {[{ label: 'P50 latence', value: '4 239 ms' }, { label: 'P95 latence', value: '4 379 ms' }, { label: 'Succès', value: '10 / 10' }].map(r => (
              <div key={r.label} className="flex justify-between">
                <span className="text-muted-foreground">{r.label}</span>
                <span className="text-foreground font-bold">{r.value}</span>
              </div>
            ))}
          </div>
        </div>
        <div className="bg-secondary/20 rounded-xl p-5">
          <h4 className="text-sm font-headline font-bold text-foreground mb-3">LLM pure (3 générations)</h4>
          <div className="space-y-2 text-sm font-mono">
            {[{ label: 'P50 latence', value: '10 153 ms' }, { label: 'P95 latence', value: '23 561 ms' }, { label: 'Succès', value: '3 / 3' }].map(r => (
              <div key={r.label} className="flex justify-between">
                <span className="text-muted-foreground">{r.label}</span>
                <span className="text-foreground font-bold">{r.value}</span>
              </div>
            ))}
          </div>
          <p className="text-xs text-muted-foreground mt-3 italic">Variabilité P50→P95 élevée : augmenter à 10 itérations pour une mesure stable.</p>
        </div>
        <div className="bg-secondary/20 rounded-xl p-5 md:col-span-2">
          <h4 className="text-sm font-headline font-bold text-foreground mb-3">RAG bout-en-bout (estimé)</h4>
          <p className="text-xs text-muted-foreground mb-3">Corpus vide lors du run — estimation par composant</p>
          <div className="flex items-center gap-3 text-sm font-mono flex-wrap">
            <span className="bg-primary/10 px-3 py-1.5 rounded-lg text-primary">~4.2 s</span>
            <span className="text-muted-foreground">embed</span>
            <span className="text-border/60">+</span>
            <span className="bg-primary/10 px-3 py-1.5 rounded-lg text-primary">&lt; 0.1 s</span>
            <span className="text-muted-foreground">search</span>
            <span className="text-border/60">+</span>
            <span className="bg-primary/10 px-3 py-1.5 rounded-lg text-primary">~10 s</span>
            <span className="text-muted-foreground">LLM</span>
            <span className="text-border/60">=</span>
            <span className="bg-green-500/10 px-3 py-1.5 rounded-lg text-green-400 font-bold">~14–15 s P50</span>
          </div>
        </div>
      </div>
    </div>

    <div className="bg-card/50 border border-border/40 rounded-xl p-8">
      <h3 className="text-lg font-headline font-bold text-foreground mb-6">3. Résumé & apport de turboquant</h3>
      <div className="overflow-x-auto mb-6">
        <table className="w-full text-sm border-collapse">
          <thead>
            <tr className="text-xs text-muted-foreground border-b border-border/40">
              <th className="text-left py-2 pr-4">Métrique</th>
              <th className="text-right py-2 pr-4">turboquant</th>
              <th className="text-right py-2 pr-4">llama.cpp std</th>
              <th className="text-right py-2">Gain</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border/20">
            {[
              { metric: 'TG chat (t/s)',         tq: '12.3',       std: '7–9',       gain: '+37–76 %', ok: true },
              { metric: 'PP chat (t/s)',          tq: '33–34',      std: '25–30',     gain: '+10–36 %', ok: true },
              { metric: 'PP embed pp128 (t/s)',   tq: '418',        std: '300–360',   gain: '+16–39 %', ok: true },
              { metric: 'Embed latence P50',      tq: '4 239 ms',   std: '—',         gain: '(baseline)', ok: null },
              { metric: 'LLM latence P50',        tq: '10 153 ms',  std: '—',         gain: '(baseline)', ok: null },
              { metric: 'RAG latence P50 (est)',  tq: '~14 000 ms', std: '—',         gain: '✅ ≤ 60 000 ms', ok: true },
            ].map(r => (
              <tr key={r.metric} className="hover:bg-white/5 transition-colors">
                <td className="py-2.5 pr-4 text-foreground/80">{r.metric}</td>
                <td className="py-2.5 pr-4 text-right font-bold text-foreground font-mono">{r.tq}</td>
                <td className="py-2.5 pr-4 text-right text-muted-foreground font-mono">{r.std}</td>
                <td className={`py-2.5 text-right font-mono text-xs ${r.ok === true ? 'text-green-400' : 'text-muted-foreground'}`}>{r.gain}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="p-4 bg-yellow-500/5 border border-yellow-500/20 rounded-lg text-xs text-muted-foreground">
        <strong className="text-yellow-400/80">Note méthodologique :</strong> les baselines llama.cpp standard sont issues de benchmarks communautaires (même modèle, CPU similaire, 4 threads).
      </div>
    </div>

    <div className="bg-card/50 border border-border/40 rounded-xl p-8">
      <h3 className="text-lg font-headline font-bold text-foreground mb-4">4. Relancer les benchmarks</h3>
      <div className="bg-black/40 p-4 rounded-lg font-mono text-sm border border-border/20 space-y-1">
        <p className="text-green-400"># Campagne complète</p>
        <p className="text-foreground mb-3">./scripts/benchmark.sh</p>
        <p className="text-green-400"># Endpoints disponibles</p>
        <p className="text-foreground">GET /api/benchmark/embedding?iterations=10</p>
        <p className="text-foreground">GET /api/benchmark/llm?iterations=3</p>
        <p className="text-foreground">GET /api/benchmark/rag?iterations=5&maxChunks=2</p>
      </div>
    </div>
  </div>
);

// ─────────────────────────────────────────────────────────────────────────────

const sectionTips = () => (
  <div className="space-y-6 animate-in fade-in slide-in-from-right-4 duration-500">
    <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
      {[
        {
          title: 'Qualité des Documents',
          body: 'Privilégiez les PDF générés numériquement (non scannés). Les documents structurés avec des titres clairs produisent de meilleurs chunks sémantiques. Incluez vos lexiques métier.',
        },
        {
          title: 'Volume Recommandé',
          body: 'Visez 200–300 pages minimum pour un dataset de fine-tuning robuste. Base plus petite ? Baissez le seuil de confiance à 0.7.',
        },
        {
          title: 'Tests Rapides',
          body: 'Utilisez "Max Chunks = 10" lors de votre première génération. Vous validez la qualité des paires Q/A en quelques minutes avant de lancer le traitement complet.',
        },
        {
          title: 'RAG vs Fine-Tuning',
          body: 'Le RAG est idéal pour trouver des faits précis dans vos documents. Le Fine-Tuning fait adopter le style et le raisonnement de votre domaine même sur des questions générales.',
        },
        {
          title: 'Seuil auto-retraining',
          body: 'Le défaut (5 approbations) est volontairement bas pour valider la boucle rapidement. En production, augmentez à 20–50 pour accumuler davantage de signal avant chaque cycle.',
        },
        {
          title: 'Commentaires IA — Focus',
          body: 'Des angles précis ("procédures d\'urgence", "obligations contractuelles") produisent de meilleurs commentaires. Variez les angles sur un même document pour obtenir des perspectives complémentaires.',
        },
        {
          title: 'Combiner les datasets DPO',
          body: 'Concaténez dpo_pairs.jsonl (généré auto) et comments_dpo.jsonl (commentaires notés) avant le fine-tuning. Les deux sources sont au même format JSONL et se complètent.',
        },
        {
          title: 'Cycles d\'amélioration',
          body: 'Après chaque fine-tuning DPO, lancez une Évaluation (LLM-as-judge) pour quantifier le gain. Comparez les scores avant/après dans la page Comparison et le Dashboard.',
        },
      ].map(tip => (
        <div key={tip.title} className="space-y-3">
          <h3 className="text-[10px] font-headline font-bold text-primary uppercase tracking-widest">{tip.title}</h3>
          <p className="text-sm text-muted-foreground leading-relaxed">{tip.body}</p>
        </div>
      ))}
    </div>
  </div>
);

// ─────────────────────────────────────────────────────────────────────────────

const sectionTroubleshooting = () => (
  <div className="space-y-6 animate-in fade-in slide-in-from-right-4 duration-500">
    <div className="bg-card/50 border border-border/40 rounded-xl overflow-hidden">
      <div className="divide-y divide-border/40">
        {[
          {
            q: "L'ingestion produit 0 chunks ?",
            a: "Vérifiez que le texte de votre PDF est sélectionnable. S'il s'agit d'une image scannée, Spectra ne peut pas extraire le contenu sans OCR préalable.",
          },
          {
            q: 'Génération de dataset bloquée ?',
            a: "Sur CPU, la génération est lente (2–5 min par chunk). Vérifiez les logs pour s'assurer que le modèle répond.",
            code: 'docker compose logs spectra-api',
          },
          {
            q: "Erreur 400 lors du Fine-Tuning ?",
            a: 'Vérifiez que le fichier GGUF de base est présent et que llama-cpp-chat est démarré et healthy.',
            code: 'data/fine-tuning/merged/model.gguf',
          },
          {
            q: "Le job auto-DPO ne se déclenche pas ?",
            a: "Vérifiez que vous avez bien noté des commentaires IA (pas humains) avec 👍 APPROVED. Le seuil par défaut est 5 approbations. Consultez les logs pour voir si le déclenchement a eu lieu.",
            code: 'docker compose logs spectra-api | grep "re-entraînement"',
          },
          {
            q: "Avertissement 'ALERTE REGISTRE/SERVEUR' dans les logs ?",
            a: "Le modèle activé dans le registre (registry.json) ne correspond pas à ce que llama-server sert. Relancez llama-server avec le bon fichier GGUF, ou activez le modèle correct dans Model Hub.",
            code: 'curl http://localhost:8081/v1/models | jq \'.data[].id\'',
          },
          {
            q: "Export DPO retourne 0 paire ?",
            a: "Vous devez avoir au moins un commentaire IA noté APPROVED. Si vous en avez mais que l'export retourne 0, c'est que la garde Jaccard a filtré toutes les paires (chosen ≈ rejected). Générez de nouveaux commentaires avec des angles différents.",
          },
          {
            q: "Score d'évaluation faible après DPO ?",
            a: "Vérifiez la cohérence de vos approbations (approuver des commentaires vagues peut dégrader le modèle). Augmentez le volume de paires DPO (20+ paires) avant de relancer.",
          },
          {
            q: "Réinitialisation d'urgence",
            a: "Si la base vectorielle est corrompue : supprimez les volumes Docker puis relancez.",
            code: 'docker compose down -v && docker compose up -d',
            warning: 'Attention : cela efface toutes les données ingérées.',
          },
        ].map((item, i) => (
          <div key={i} className={`p-6 ${item.warning ? 'bg-primary/5' : ''}`}>
            <h4 className={`font-bold mb-2 ${item.warning ? 'text-primary italic' : 'text-foreground'}`}>{item.q}</h4>
            <p className="text-sm text-muted-foreground leading-relaxed">{item.a}</p>
            {item.code && (
              <code className="mt-2 block bg-black/40 px-2 py-1 rounded text-primary text-xs font-mono w-fit">{item.code}</code>
            )}
            {item.warning && (
              <p className="text-primary/80 font-bold text-xs mt-2">{item.warning}</p>
            )}
          </div>
        ))}
      </div>
    </div>
  </div>
);

export default Documentation;
