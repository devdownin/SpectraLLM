import React, { useState } from 'react';

const Documentation: React.FC = () => {
  const [activeTab, setActiveTab] = useState('overview');

  const sections = [
    { id: 'overview',        title: 'Vue d\'ensemble' },
    { id: 'prerequisites',   title: 'Prérequis' },
    { id: 'pipeline',        title: 'Pipeline' },
    { id: 'commenting',      title: 'Commentaires IA' },
    { id: 'interface',       title: 'Interface' },
    { id: 'benchmark',       title: 'Benchmark' },
    { id: 'tips',            title: 'Conseils' },
    { id: 'troubleshooting', title: 'Dépannage' },
  ];

  return (
    <div className="space-y-8 animate-in fade-in slide-in-from-bottom-4 duration-700">
      {/* Header */}
      <div className="flex flex-col md:flex-row md:items-end justify-between border-b border-border/40 pb-6 gap-4">
        <div>
          <h1 className="text-4xl font-headline font-bold tracking-tight text-foreground">Documentation</h1>
          <p className="text-muted-foreground mt-2 max-w-2xl text-lg">
            Maîtrisez Spectra — de l'ingestion de documents à la boucle de rétroaction humaine et au fine-tuning par préférence.
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
          title: 'Boucle de Rétroaction Humaine',
          body: 'Commentaires analytiques générés par RAG sur chaque document. Notez 👍/👎 pour constituer des paires DPO et améliorer le modèle à chaque cycle.',
          badge: 'RLHF / DPO',
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

    {/* Architecture flow */}
    <div className="bg-card/50 border border-border/40 rounded-xl p-8 space-y-6">
      <h3 className="text-lg font-headline font-bold text-foreground">Flux de données complet</h3>
      <div className="grid grid-cols-1 md:grid-cols-5 gap-2 items-center text-center">
        {[
          { num: '1', label: 'Ingest',      sub: 'PDF · DOCX · URL',       color: 'primary' },
          { num: '2', label: 'Generate',    sub: 'Q/A · DPO · résumés',    color: 'primary' },
          { num: '2c', label: 'Annotate',   sub: 'RAG → commentaires',      color: 'secondary' },
          { num: '3', label: 'Fine-Tune',   sub: 'QLoRA · GGUF',           color: 'primary' },
          { num: '4', label: 'Query',       sub: 'RAG Playground',         color: 'secondary' },
        ].map((s, i, arr) => (
          <React.Fragment key={s.num}>
            <div className="flex flex-col items-center gap-2">
              <div className={`w-12 h-12 rounded-full flex items-center justify-center font-headline font-bold text-sm shadow-lg ${
                s.color === 'primary'
                  ? 'bg-primary text-primary-foreground shadow-primary/20'
                  : 'bg-secondary/60 text-foreground shadow-secondary/10'
              }`}>{s.num}</div>
              <span className={`text-[10px] font-headline uppercase tracking-widest ${s.color === 'primary' ? 'text-primary' : 'text-muted-foreground'}`}>{s.label}</span>
              <span className="text-[8px] text-muted-foreground/60">{s.sub}</span>
            </div>
            {i < arr.length - 1 && <div className="hidden md:block h-px bg-border/40 w-full" />}
          </React.Fragment>
        ))}
      </div>
      <p className="text-xs text-muted-foreground italic text-center">
        L'étape 2c (Annotate) est nouvelle — elle ferme la boucle entre RAG et fine-tuning via des préférences humaines.
      </p>
    </div>

    {/* Stats highlight */}
    <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
      {[
        { label: '10+',  sub: 'stratégies RAG',      color: 'text-primary' },
        { label: '270+', sub: 'tests unitaires',      color: 'text-secondary' },
        { label: '100%', sub: 'local & open-source',  color: 'text-primary' },
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
    {/* Progress visual */}
    <div className="flex items-center justify-center py-4 overflow-x-auto no-scrollbar">
      <div className="flex items-center space-x-3 min-w-max">
        {[
          { n: '1', label: 'Ingest',    active: true },
          { n: '2', label: 'Generate',  active: true },
          { n: '2c', label: 'Annotate', active: true, accent: 'secondary' },
          { n: '3', label: 'Fine-Tune', active: false },
          { n: '4', label: 'Query',     active: false },
        ].map((s, i, arr) => (
          <React.Fragment key={s.n}>
            <div className="flex flex-col items-center">
              <div className={`w-10 h-10 rounded-full flex items-center justify-center font-headline font-bold shadow-lg ${
                s.active
                  ? s.accent === 'secondary'
                    ? 'bg-secondary/80 text-foreground shadow-secondary/20'
                    : 'bg-primary text-primary-foreground shadow-primary/20'
                  : 'bg-secondary/40 text-muted-foreground opacity-60'
              }`}>{s.n}</div>
              <span className={`text-[10px] font-headline uppercase mt-2 tracking-widest ${
                s.active
                  ? s.accent === 'secondary' ? 'text-secondary' : 'text-primary'
                  : 'text-muted-foreground opacity-60'
              }`}>{s.label}</span>
            </div>
            {i < arr.length - 1 && <div className="w-8 h-px bg-border/40" />}
          </React.Fragment>
        ))}
      </div>
    </div>

    <div className="grid grid-cols-1 md:grid-cols-2 gap-6">

      <div className="bg-card/50 border border-border/40 rounded-xl p-6">
        <div className="flex items-center gap-2 mb-4">
          <div className="w-7 h-7 rounded-full bg-primary flex items-center justify-center font-headline font-bold text-primary-foreground text-xs">1</div>
          <h3 className="text-xl font-headline font-bold text-foreground">Ingestion</h3>
        </div>
        <p className="text-sm text-muted-foreground mb-4 leading-relaxed">
          Transformez vos PDF, DOCX et JSON en vecteurs stockés dans ChromaDB. Spectra extrait le texte, le normalise et le découpe en segments intelligents.
        </p>
        <ul className="space-y-2 text-xs text-foreground/80">
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-primary shrink-0" /> PDF natifs et fichiers DOCX structurés</li>
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-primary shrink-0" /> Découpage en chunks de ~512 mots</li>
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-primary shrink-0" /> Calcul des embeddings avec Nomic</li>
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-primary shrink-0" /> Gestion du cycle de vie GED (INGESTED → TRAINED)</li>
        </ul>
      </div>

      <div className="bg-card/50 border border-border/40 rounded-xl p-6">
        <div className="flex items-center gap-2 mb-4">
          <div className="w-7 h-7 rounded-full bg-primary flex items-center justify-center font-headline font-bold text-primary-foreground text-xs">2</div>
          <h3 className="text-xl font-headline font-bold text-foreground">Génération</h3>
        </div>
        <p className="text-sm text-muted-foreground mb-4 leading-relaxed">
          Créez un dataset d'entraînement Q/A à partir de vos documents. Spectra utilise le LLM pour générer des questions techniques, des résumés et des cas négatifs.
        </p>
        <ul className="space-y-2 text-xs text-foreground/80">
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-primary shrink-0" /> Auto-correction par un second passage LLM</li>
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-primary shrink-0" /> Questions "pièges" pour limiter les hallucinations</li>
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-primary shrink-0" /> Classification automatique des contenus</li>
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-primary shrink-0" /> Paires DPO (chosen / rejected) pour l'alignement</li>
        </ul>
      </div>

      <div className="bg-card/50 border border-secondary/30 rounded-xl p-6">
        <div className="flex items-center gap-2 mb-4">
          <div className="w-7 h-7 rounded-full bg-secondary/80 flex items-center justify-center font-headline font-bold text-foreground text-xs">2c</div>
          <h3 className="text-xl font-headline font-bold text-foreground">Annotations IA</h3>
          <span className="text-[8px] font-bold uppercase tracking-widest border border-secondary/40 text-secondary px-2 py-0.5 rounded-full">Nouveau</span>
        </div>
        <p className="text-sm text-muted-foreground mb-4 leading-relaxed">
          Générez des commentaires analytiques sur chaque document via RAG. Évaluez-les pour constituer des paires DPO utilisées lors du prochain cycle de fine-tuning.
        </p>
        <ul className="space-y-2 text-xs text-foreground/80">
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-secondary shrink-0" /> RAG récupère 6 chunks pertinents du document</li>
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-secondary shrink-0" /> LLM génère un commentaire factuel (temp. 0.4)</li>
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-secondary shrink-0" /> Évaluation 👍/👎 → paires DPO exportables</li>
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-secondary shrink-0" /> Commentaires humains aussi supportés</li>
        </ul>
      </div>

      <div className="bg-card/50 border border-border/40 rounded-xl p-6">
        <div className="flex items-center gap-2 mb-4">
          <div className="w-7 h-7 rounded-full bg-secondary/40 flex items-center justify-center font-headline font-bold text-foreground text-xs">3</div>
          <h3 className="text-xl font-headline font-bold text-foreground">Fine-Tuning</h3>
        </div>
        <p className="text-sm text-muted-foreground mb-4 leading-relaxed">
          Entraînez votre propre modèle spécialisé. Si vous n'avez pas de GPU, Spectra crée un modèle optimisé par prompt système s'appuyant sur vos données.
        </p>
        <ul className="space-y-2 text-xs text-foreground/80">
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-primary shrink-0" /> Optimisation LoRA / QLoRA 4-bit</li>
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-primary shrink-0" /> Filtrage par score de confiance</li>
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-primary shrink-0" /> Enregistrement automatique dans llama-server</li>
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-primary shrink-0" /> Alignement DPO compatible comments_dpo.jsonl</li>
        </ul>
      </div>

      <div className="bg-card/50 border border-border/40 rounded-xl p-6 md:col-span-2">
        <div className="flex items-center gap-2 mb-4">
          <div className="w-7 h-7 rounded-full bg-secondary/40 flex items-center justify-center font-headline font-bold text-foreground text-xs">4</div>
          <h3 className="text-xl font-headline font-bold text-foreground">Interrogation RAG</h3>
        </div>
        <p className="text-sm text-muted-foreground mb-4 leading-relaxed">
          Posez vos questions techniques dans le Playground. Spectra sélectionne la stratégie RAG optimale selon le type de requête.
        </p>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          {[
            { label: 'Hybrid Search',  desc: 'BM25 + vecteurs RRF' },
            { label: 'Re-ranking',     desc: 'Cross-Encoder 2 étapes' },
            { label: 'Agentic ReAct',  desc: 'Boucle multi-hop' },
            { label: 'Corrective RAG', desc: 'Grading LLM des chunks' },
          ].map(r => (
            <div key={r.label} className="bg-secondary/20 rounded-lg p-3">
              <p className="text-[9px] font-bold uppercase tracking-widest text-primary">{r.label}</p>
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
        paires DPO qui alimentent le prochain cycle de fine-tuning — <strong>le modèle apprend vos préférences</strong>.
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
            accent: 'border-outline-variant/40 text-outline',
          },
          {
            icon: 'model_training',
            title: 'Fine-tuning seul',
            body: 'Le modèle apprend le style de votre domaine. Mais sans contexte RAG, il peut inventer des détails absents du document.',
            badge: 'Style adapté mais fragile',
            accent: 'border-outline-variant/40 text-outline',
          },
          {
            icon: 'sync',
            title: 'RAG + DPO (optimal)',
            body: 'RAG ancre chaque commentaire dans les chunks réels. DPO aligne le modèle sur ce que vous considérez comme un bon commentaire. La qualité s\'améliore à chaque cycle.',
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

    {/* Cycle de vie */}
    <div className="bg-card/50 border border-border/40 rounded-xl p-8 space-y-6">
      <h3 className="text-lg font-headline font-bold text-foreground">Cycle de vie d'un commentaire IA</h3>
      <div className="space-y-3">
        {[
          { n: '1', icon: 'chat_bubble', label: 'Focus utilisateur', desc: 'Vous fournissez un angle d\'analyse — ou laissez vide pour un résumé général.', color: 'text-primary border-primary/30 bg-primary/5' },
          { n: '2', icon: 'search', label: 'Retrieval RAG', desc: 'Spectra récupère les 6 passages les plus pertinents du document dans ChromaDB.', color: 'text-primary border-primary/30 bg-primary/5' },
          { n: '3', icon: 'memory', label: 'Génération LLM', desc: 'Le LLM rédige un commentaire analytique factuel (température 0.4) ancré dans ces passages.', color: 'text-secondary border-secondary/30 bg-secondary/5' },
          { n: '4', icon: 'thumb_up', label: 'Évaluation', desc: 'Vous notez le commentaire 👍 APPROVED ou 👎 REJECTED. Chaque note enregistre une préférence.', color: 'text-secondary border-secondary/30 bg-secondary/5' },
          { n: '5', icon: 'download', label: 'Export DPO', desc: 'Cliquez DPO↓ — Spectra exporte les paires (chosen, rejected) en JSONL prêt pour trl.DPOTrainer.', color: 'text-primary border-primary/30 bg-primary/5' },
          { n: '↺', icon: 'sync', label: 'Nouveau fine-tuning', desc: 'Lancez un job Fine-Tuning avec "Alignement DPO" — le modèle intègre vos préférences.', color: 'text-primary border-primary/30 bg-primary/5' },
        ].map(step => (
          <div key={step.n} className={`flex items-start gap-4 p-4 rounded-lg border ${step.color}`}>
            <div className={`w-7 h-7 rounded-full flex items-center justify-center font-headline font-bold text-xs shrink-0 ${step.color}`}>
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

    {/* Interface guide */}
    <div className="bg-card/50 border border-border/40 rounded-xl p-8 space-y-5">
      <h3 className="text-lg font-headline font-bold text-foreground">Utilisation dans l'interface</h3>
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
          <span>Lisez le commentaire dans l'onglet <strong>Liste</strong> et cliquez <strong>👍</strong> ou <strong>👎</strong>.</span>
        </li>
        <li className="flex gap-3">
          <span className="w-6 h-6 rounded-full bg-secondary/30 text-secondary flex items-center justify-center font-bold text-xs shrink-0 mt-0.5">5</span>
          <span>Répétez sur plusieurs documents, puis cliquez <strong>DPO↓</strong> pour exporter.</span>
        </li>
        <li className="flex gap-3">
          <span className="w-6 h-6 rounded-full bg-primary/20 text-primary flex items-center justify-center font-bold text-xs shrink-0 mt-0.5">6</span>
          <span>Dans <strong>Fine-Tuning</strong>, cochez <strong>Alignement DPO</strong> et lancez un nouveau job.</span>
        </li>
      </ol>
    </div>

    {/* API reference */}
    <div className="bg-card/50 border border-border/40 rounded-xl p-8">
      <h3 className="text-lg font-headline font-bold text-foreground mb-4">Référence API</h3>
      <div className="bg-black/40 p-5 rounded-lg font-mono text-sm border border-border/20 space-y-1 overflow-x-auto">
        <p className="text-green-400"># Lister les commentaires d'un document</p>
        <p className="text-foreground mb-3">GET /api/ged/documents/{'{sha256}'}/comments</p>

        <p className="text-green-400"># Ajouter un commentaire humain</p>
        <p className="text-foreground">POST /api/ged/documents/{'{sha256}'}/comments</p>
        <p className="text-muted-foreground mb-3">{'{ "content": "Mon annotation.", "generate": false }'}</p>

        <p className="text-green-400"># Générer un commentaire IA via RAG</p>
        <p className="text-foreground">POST /api/ged/documents/{'{sha256}'}/comments</p>
        <p className="text-muted-foreground mb-3">{'{ "content": "focus retrieval", "generate": true }'}</p>

        <p className="text-green-400"># Évaluer un commentaire (APPROVED / REJECTED / NONE)</p>
        <p className="text-foreground mb-3">PATCH /api/ged/documents/{'{sha256}'}/comments/{'{id}'}/rating?rating=APPROVED</p>

        <p className="text-green-400"># Supprimer un commentaire</p>
        <p className="text-foreground mb-3">DELETE /api/ged/documents/{'{sha256}'}/comments/{'{id}'}</p>

        <p className="text-green-400"># Exporter les paires DPO (comments_dpo.jsonl)</p>
        <p className="text-foreground">POST /api/ged/documents/export/comments-dpo</p>
        <p className="text-muted-foreground">{'→ { "pairs": 18, "file": "...", "exportedAt": "..." }'}</p>
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
            body: 'État de santé des services (LLM, ChromaDB), statistiques de la base de connaissances, métriques de commentaires IA et DPO, actions pipeline rapides.',
            icon: 'dashboard',
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
            body: 'Fiche complète de chaque document : cycle de vie, tags, associations modèles, audit trail. Section Commentaires avec 3 onglets : Liste / Manuel / ✦ IA — génération RAG, évaluation DPO et export.',
            icon: 'analytics',
            badge: 'Commentaires IA',
          },
          {
            key: 'Fine-Tuning',
            title: 'Entraînement & Logs',
            body: 'Lancez de nouveaux jobs d\'entraînement (recettes CPU/GPU/DPO). Visualisez la télémétrie en direct (loss, epoch). Utilisez les paires DPO issues des commentaires annotés.',
            icon: 'history',
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
            body: 'Comparez côte-à-côte deux modèles sur une même question. Mesurez le gain obtenu après fine-tuning DPO.',
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
          body: 'Le RAG est idéal pour trouver des faits précis. Le Fine-Tuning fait adopter le style et le raisonnement de votre domaine même sur des questions générales.',
        },
        {
          title: 'Commentaires IA — Volume',
          body: 'Annotez 10–20 commentaires minimum avant d\'exporter en DPO. La cohérence de vos évaluations compte plus que le volume.',
        },
        {
          title: 'Commentaires IA — Focus',
          body: 'Des angles précis ("procédures d\'urgence") produisent de meilleurs commentaires qu\'un focus vide. Variez les angles sur un même document pour obtenir des perspectives complémentaires.',
        },
        {
          title: 'Combiner les datasets DPO',
          body: 'Concaténez dpo_pairs.jsonl et comments_dpo.jsonl avant le fine-tuning. Les deux sources sont au même format et se complètent.',
        },
        {
          title: 'Cycles de Fine-Tuning',
          body: 'Après chaque fine-tuning DPO, générez de nouveaux commentaires avec le modèle amélioré. La qualité s\'améliore à chaque itération.',
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
            a: "Vérifiez que le texte de votre PDF est sélectionnable. S'il s'agit d'une image, Spectra ne pourra pas extraire le contenu sans OCR préalable.",
          },
          {
            q: 'Génération de dataset bloquée ?',
            a: "Sur CPU, la génération est lente (2–5 min par chunk). Vérifiez les logs via docker compose logs spectra-api pour s'assurer que le modèle Phi-3 répond.",
            code: 'docker compose logs spectra-api',
          },
          {
            q: "Erreur 400 lors du Fine-Tuning ?",
            a: 'Vérifiez que le fichier GGUF de base est présent dans data/fine-tuning/merged/model.gguf et que le conteneur llama-cpp-chat est démarré et healthy.',
            code: 'data/fine-tuning/merged/model.gguf',
          },
          {
            q: "Le commentaire IA génère une erreur \"LLM indisponible\" ?",
            a: "Le service de chat doit être Online (Dashboard). La génération via RAG nécessite que llama-cpp-chat soit démarré et que ChromaDB contienne des chunks du document.",
          },
          {
            q: "Export DPO retourne 0 paire ?",
            a: "Vous devez avoir au moins un commentaire IA noté APPROVED. Rendez-vous dans la page Database, ouvrez un document, et évaluez un commentaire avec 👍.",
          },
          {
            q: "Réinitialisation d'urgence",
            a: "Si la base vectorielle est corrompue : docker compose down -v puis docker compose up -d.",
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
