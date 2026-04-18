import React, { useState } from 'react';

const Documentation: React.FC = () => {
  const [activeTab, setActiveTab] = useState('overview');

  const sections = [
    { id: 'overview', title: 'Vue d\'ensemble' },
    { id: 'prerequisites', title: 'Prérequis' },
    { id: 'pipeline', title: 'Le Pipeline en 4 Étapes' },
    { id: 'interface', title: 'Guide de l\'Interface' },
    { id: 'benchmark', title: 'Benchmark turboquant' },
    { id: 'tips', title: 'Conseils & Optimisation' },
    { id: 'troubleshooting', title: 'Dépannage' },
  ];

  return (
    <div className="space-y-8 animate-in fade-in slide-in-from-bottom-4 duration-700">
      {/* Header */}
      <div className="flex flex-col md:flex-row md:items-end justify-between border-b border-border/40 pb-6 gap-4">
        <div>
          <h1 className="text-4xl font-headline font-bold tracking-tight text-foreground">Documentation</h1>
          <p className="text-muted-foreground mt-2 max-w-2xl text-lg">
            Apprenez à maîtriser Spectra pour transformer vos documents en intelligence artificielle locale spécialisée.
          </p>
        </div>
        <div className="flex bg-secondary/30 p-1 rounded-lg border border-border/40 overflow-x-auto no-scrollbar">
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
        {/* Section: Overview */}
        {activeTab === 'overview' && (
          <div className="space-y-6 animate-in fade-in slide-in-from-right-4 duration-500">
            <div className="prose prose-invert max-w-none">
              <h2 className="text-2xl font-headline font-bold text-primary mb-4">Bienvenue sur Spectra</h2>
              <p className="text-lg leading-relaxed text-foreground/80">
                Spectra vous permet de créer votre propre assistant d'intelligence artificielle spécialisé dans <strong>votre domaine métier</strong>, à partir de vos propres documents. 
                L'assistant fonctionne <strong>entièrement en local</strong> — aucune donnée ne quitte votre infrastructure.
              </p>
              
              <div className="grid grid-cols-1 md:grid-cols-3 gap-6 my-10">
                <div className="bg-secondary/20 p-6 rounded-xl border border-border/40 hover:border-primary/40 transition-colors group">
                  <div className="w-12 h-12 rounded-lg bg-primary/10 flex items-center justify-center mb-4 group-hover:scale-110 transition-transform">
                    <svg className="w-6 h-6 text-primary" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                    </svg>
                  </div>
                  <h3 className="text-lg font-headline font-bold mb-2">Confidentialité Totale</h3>
                  <p className="text-sm text-muted-foreground">Vos documents ne sont jamais envoyés dans le cloud. Tout le traitement est local.</p>
                </div>
                <div className="bg-secondary/20 p-6 rounded-xl border border-border/40 hover:border-primary/40 transition-colors group">
                  <div className="w-12 h-12 rounded-lg bg-primary/10 flex items-center justify-center mb-4 group-hover:scale-110 transition-transform">
                    <svg className="w-6 h-6 text-primary" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 10V3L4 14h7v7l9-11h-7z" />
                    </svg>
                  </div>
                  <h3 className="text-lg font-headline font-bold mb-2">Spécialisation Métier</h3>
                  <p className="text-sm text-muted-foreground">L'IA apprend votre terminologie, vos procédures et vos concepts spécifiques.</p>
                </div>
                <div className="bg-secondary/20 p-6 rounded-xl border border-border/40 hover:border-primary/40 transition-colors group">
                  <div className="w-12 h-12 rounded-lg bg-primary/10 flex items-center justify-center mb-4 group-hover:scale-110 transition-transform">
                    <svg className="w-6 h-6 text-primary" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10" />
                    </svg>
                  </div>
                  <h3 className="text-lg font-headline font-bold mb-2">Multi-Services</h3>
                  <p className="text-sm text-muted-foreground">Une stack complète incluant base vectorielle, modèles LLM et interface web.</p>
                </div>
              </div>
            </div>
          </div>
        )}

        {/* Section: Prerequisites */}
        {activeTab === 'prerequisites' && (sectionPrerequisites())}

        {/* Section: Pipeline */}
        {activeTab === 'pipeline' && (sectionPipeline())}

        {/* Section: Interface */}
        {activeTab === 'interface' && (sectionInterface())}

        {/* Section: Benchmark */}
        {activeTab === 'benchmark' && (sectionBenchmark())}

        {/* Section: Tips */}
        {activeTab === 'tips' && (sectionTips())}

        {/* Section: Troubleshooting */}
        {activeTab === 'troubleshooting' && (sectionTroubleshooting())}
      </div>
    </div>
  );
};

// --- Sub-components for readability ---

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

const sectionPipeline = () => (
  <div className="space-y-8 animate-in fade-in slide-in-from-right-4 duration-500">
    <div className="flex items-center justify-center py-4 overflow-x-auto no-scrollbar">
      <div className="flex items-center space-x-4 min-w-max">
        <div className="flex flex-col items-center">
          <div className="w-10 h-10 rounded-full bg-primary flex items-center justify-center font-headline font-bold text-primary-foreground shadow-lg shadow-primary/20">1</div>
          <span className="text-[10px] font-headline uppercase mt-2 tracking-widest text-primary">Ingest</span>
        </div>
        <div className="w-12 h-px bg-border/40"></div>
        <div className="flex flex-col items-center">
          <div className="w-10 h-10 rounded-full bg-primary flex items-center justify-center font-headline font-bold text-primary-foreground shadow-lg shadow-primary/20">2</div>
          <span className="text-[10px] font-headline uppercase mt-2 tracking-widest text-primary">Generate</span>
        </div>
        <div className="w-12 h-px bg-border/40"></div>
        <div className="flex flex-col items-center opacity-60">
          <div className="w-10 h-10 rounded-full bg-secondary flex items-center justify-center font-headline font-bold">3</div>
          <span className="text-[10px] font-headline uppercase mt-2 tracking-widest text-muted-foreground">Fine-Tune</span>
        </div>
        <div className="w-12 h-px bg-border/40"></div>
        <div className="flex flex-col items-center opacity-60">
          <div className="w-10 h-10 rounded-full bg-secondary flex items-center justify-center font-headline font-bold">4</div>
          <span className="text-[10px] font-headline uppercase mt-2 tracking-widest text-muted-foreground">Query</span>
        </div>
      </div>
    </div>

    <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
      <div className="bg-card/50 border border-border/40 rounded-xl p-6">
        <h3 className="text-xl font-headline font-bold text-foreground mb-4">Étape 1 — Ingestion</h3>
        <p className="text-sm text-muted-foreground mb-4 leading-relaxed">
          Transformez vos PDF, DOCX et JSON en vecteurs stockés dans ChromaDB. Spectra extrait le texte, le normalise et le découpe en segments intelligents.
        </p>
        <ul className="space-y-2 text-xs text-foreground/80">
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-primary"></div> PDF natifs et fichiers DOCX structurés</li>
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-primary"></div> Découpage en chunks de ~512 mots</li>
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-primary"></div> Calcul des embeddings avec Nomic</li>
        </ul>
      </div>

      <div className="bg-card/50 border border-border/40 rounded-xl p-6">
        <h3 className="text-xl font-headline font-bold text-foreground mb-4">Étape 2 — Génération</h3>
        <p className="text-sm text-muted-foreground mb-4 leading-relaxed">
          Créez un dataset d'entraînement Q/A à partir de vos documents. Spectra utilise le LLM pour générer des questions techniques, des résumés et des cas négatifs.
        </p>
        <ul className="space-y-2 text-xs text-foreground/80">
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-primary"></div> Auto-correction par un second passage LLM</li>
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-primary"></div> Questions "pièges" pour limiter les hallucinations</li>
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-primary"></div> Classification automatique des contenus</li>
        </ul>
      </div>

      <div className="bg-card/50 border border-border/40 rounded-xl p-6">
        <h3 className="text-xl font-headline font-bold text-foreground mb-4">Étape 3 — Fine-Tuning</h3>
        <p className="text-sm text-muted-foreground mb-4 leading-relaxed">
          Entraînez votre propre modèle spécialisé. Si vous n'avez pas de GPU, Spectra crée un modèle optimisé par prompt système s'appuyant sur vos données.
        </p>
        <ul className="space-y-2 text-xs text-foreground/80">
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-primary"></div> Optimisation LoRA / QLoRA 4-bit</li>
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-primary"></div> Filtrage par score de confiance</li>
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-primary"></div> Enregistrement automatique dans llama-server</li>
        </ul>
      </div>

      <div className="bg-card/50 border border-border/40 rounded-xl p-6">
        <h3 className="text-xl font-headline font-bold text-foreground mb-4">Étape 4 — Interrogation</h3>
        <p className="text-sm text-muted-foreground mb-4 leading-relaxed">
          Posez vos questions techniques dans le Playground. Spectra utilise le RAG (Retrieval Augmented Generation) pour fournir des réponses sourcées.
        </p>
        <ul className="space-y-2 text-xs text-foreground/80">
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-primary"></div> Réponses basées sur le contexte documentaire</li>
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-primary"></div> Citations exactes des sources</li>
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-primary"></div> Comparaison des modèles en temps réel</li>
        </ul>
      </div>
    </div>
  </div>
);

const sectionInterface = () => (
  <div className="space-y-6 animate-in fade-in slide-in-from-right-4 duration-500">
    <div className="bg-card/50 border border-border/40 rounded-xl p-8">
      <h2 className="text-2xl font-headline font-bold text-foreground mb-8">Navigation & Fonctionnalités</h2>
      
      <div className="space-y-10">
        <div className="flex gap-6">
          <div className="w-24 shrink-0 font-headline uppercase tracking-widest text-[10px] text-primary pt-1">Dashboard</div>
          <div className="space-y-2">
            <h4 className="font-bold text-foreground">Surveillance en temps réel</h4>
            <p className="text-sm text-muted-foreground leading-relaxed">
              Consultez l'état de santé des services critiques (LLM, ChromaDB) et les statistiques globales de votre base de connaissances.
            </p>
          </div>
        </div>

        <div className="flex gap-6">
          <div className="w-24 shrink-0 font-headline uppercase tracking-widest text-[10px] text-primary pt-1">Pipelines</div>
          <div className="space-y-2">
            <h4 className="font-bold text-foreground">Gestion des données</h4>
            <p className="text-sm text-muted-foreground leading-relaxed">
              La zone de drop pour vos documents. Suivez l'ingestion chunk par chunk et lancez la génération du dataset avec le curseur "Max Chunks".
            </p>
          </div>
        </div>

        <div className="flex gap-6">
          <div className="w-24 shrink-0 font-headline uppercase tracking-widest text-[10px] text-primary pt-1">Fine-Tuning</div>
          <div className="space-y-2">
            <h4 className="font-bold text-foreground">Entraînement & Logs</h4>
            <p className="text-sm text-muted-foreground leading-relaxed">
              Lancez de nouveaux jobs d'entraînement et visualisez la télémétrie en direct. L'historique vous permet de retrouver vos configurations passées.
            </p>
          </div>
        </div>

        <div className="flex gap-6">
          <div className="w-24 shrink-0 font-headline uppercase tracking-widest text-[10px] text-primary pt-1">Playground</div>
          <div className="space-y-2">
            <h4 className="font-bold text-foreground">Laboratoire de Tests</h4>
            <p className="text-sm text-muted-foreground leading-relaxed">
              Interrogez vos modèles. Activez/désactivez la Knowledge Base pour comparer les performances de l'IA avec et sans vos documents.
            </p>
          </div>
        </div>
      </div>
    </div>
  </div>
);

const sectionBenchmark = () => (
  <div className="space-y-8 animate-in fade-in slide-in-from-right-4 duration-500">
    {/* Header */}
    <div className="bg-card/50 border border-border/40 rounded-xl p-8">
      <h2 className="text-2xl font-headline font-bold text-primary mb-2">Benchmark turboquant</h2>
      <p className="text-sm text-muted-foreground mb-1">
        Résultats mesurés le <strong>2 avril 2026</strong> — matériel : CPU 4 threads (conteneur Docker, WSL2), pas de GPU.
      </p>
      <p className="text-sm text-muted-foreground">
        Fork : <code className="bg-black/40 px-1.5 py-0.5 rounded text-primary">TheTom/llama-cpp-turboquant</code> build <code className="bg-black/40 px-1.5 py-0.5 rounded text-primary">9c600bc</code>
      </p>
    </div>

    {/* Models */}
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

    {/* llama-bench native */}
    <div className="bg-card/50 border border-border/40 rounded-xl p-8">
      <h3 className="text-lg font-headline font-bold text-foreground mb-1">1. Débit natif (llama-bench)</h3>
      <p className="text-xs text-muted-foreground mb-6">3 répétitions par test · PP = prompt prefill · TG = text generation</p>

      <div className="space-y-6">
        {/* Chat model table */}
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

        {/* Embed model table */}
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

    {/* API latency */}
    <div className="bg-card/50 border border-border/40 rounded-xl p-8">
      <h3 className="text-lg font-headline font-bold text-foreground mb-1">2. Latence API Spectra</h3>
      <p className="text-xs text-muted-foreground mb-6">Mesures bout-en-bout via HTTP · inclut sérialisation, tokenisation et overhead Docker réseau</p>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
        {/* Embedding */}
        <div className="bg-secondary/20 rounded-xl p-5">
          <h4 className="text-sm font-headline font-bold text-foreground mb-3">Embedding (10 × ~864 tokens)</h4>
          <div className="space-y-2 text-sm font-mono">
            {[
              { label: 'P50 latence', value: '4 239 ms' },
              { label: 'P95 latence', value: '4 379 ms' },
              { label: 'Succès', value: '10 / 10' },
            ].map(r => (
              <div key={r.label} className="flex justify-between">
                <span className="text-muted-foreground">{r.label}</span>
                <span className="text-foreground font-bold">{r.value}</span>
              </div>
            ))}
          </div>
        </div>

        {/* LLM */}
        <div className="bg-secondary/20 rounded-xl p-5">
          <h4 className="text-sm font-headline font-bold text-foreground mb-3">LLM pure (3 générations)</h4>
          <div className="space-y-2 text-sm font-mono">
            {[
              { label: 'P50 latence', value: '10 153 ms' },
              { label: 'P95 latence', value: '23 561 ms' },
              { label: 'Succès', value: '3 / 3' },
            ].map(r => (
              <div key={r.label} className="flex justify-between">
                <span className="text-muted-foreground">{r.label}</span>
                <span className="text-foreground font-bold">{r.value}</span>
              </div>
            ))}
          </div>
          <p className="text-xs text-muted-foreground mt-3 italic">
            Variabilité P50→P95 élevée : augmenter à 10 itérations pour une mesure stable.
          </p>
        </div>

        {/* RAG estimate */}
        <div className="bg-secondary/20 rounded-xl p-5 md:col-span-2">
          <h4 className="text-sm font-headline font-bold text-foreground mb-3">RAG bout-en-bout (estimé)</h4>
          <p className="text-xs text-muted-foreground mb-3">Corpus vide lors du run — estimation par composant : embed(question) + ChromaDB search + LLM</p>
          <div className="flex items-center gap-3 text-sm font-mono">
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

    {/* Summary table */}
    <div className="bg-card/50 border border-border/40 rounded-xl p-8">
      <h3 className="text-lg font-headline font-bold text-foreground mb-6">3. Résumé & apport de turboquant</h3>

      <div className="overflow-x-auto mb-6">
        <table className="w-full text-sm border-collapse">
          <thead>
            <tr className="text-xs text-muted-foreground border-b border-border/40">
              <th className="text-left py-2 pr-4">Métrique</th>
              <th className="text-right py-2 pr-4">turboquant (mesuré)</th>
              <th className="text-right py-2 pr-4">llama.cpp std (estimé)</th>
              <th className="text-right py-2">Gain</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border/20">
            {[
              { metric: 'TG chat (t/s)',        tq: '12.3',   std: '7–9',     gain: '+37–76 %', ok: true },
              { metric: 'PP chat (t/s)',         tq: '33–34',  std: '25–30',   gain: '+10–36 %', ok: true },
              { metric: 'PP embed pp128 (t/s)',  tq: '418',    std: '300–360', gain: '+16–39 %', ok: true },
              { metric: 'Embed latence P50',     tq: '4 239 ms', std: '—',     gain: '(baseline)', ok: null },
              { metric: 'LLM latence P50',       tq: '10 153 ms', std: '—',    gain: '(baseline)', ok: null },
              { metric: 'RAG latence P50 (est)', tq: '~14 000 ms', std: '—',   gain: '✅ ≤ 60 000 ms', ok: true },
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
        Pour une comparaison rigoureuse sur votre machine, compiler llama.cpp standard et relancer <code className="bg-black/40 px-1 rounded">llama-bench</code> sur les mêmes fichiers GGUF.
      </div>
    </div>

    {/* Turboquant advantages */}
    <div className="bg-card/50 border border-border/40 rounded-xl p-8">
      <h3 className="text-lg font-headline font-bold text-foreground mb-6">4. Où turboquant apporte le plus de valeur</h3>
      <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
        {[
          {
            title: 'Quantizations extrêmes',
            body: 'Pour IQ2_S, IQ2_XXS et IQ3_M, turboquant améliore la qualité de quantization : perplexité équivalente à Q4 standard à iso-taille de fichier.',
          },
          {
            title: 'CPU sans AVX-512',
            body: 'Le fork intègre des optimisations SIMD spécifiques aux processeurs sans AVX-512 (Ryzen, anciens Intel). Le gain est plus marqué sur ces architectures.',
          },
          {
            title: 'KV cache quantizé',
            body: 'q8_0 réduit l\'empreinte mémoire du cache KV de ~50 % vs f16, permettant des contextes plus longs sur machines limitées en RAM.',
          },
        ].map(item => (
          <div key={item.title} className="bg-secondary/20 rounded-xl p-5">
            <h4 className="text-sm font-headline font-bold text-foreground mb-2">{item.title}</h4>
            <p className="text-xs text-muted-foreground leading-relaxed">{item.body}</p>
          </div>
        ))}
      </div>
    </div>

    {/* How to run */}
    <div className="bg-card/50 border border-border/40 rounded-xl p-8">
      <h3 className="text-lg font-headline font-bold text-foreground mb-4">5. Relancer les benchmarks</h3>
      <div className="bg-black/40 p-4 rounded-lg font-mono text-sm border border-border/20 space-y-1">
        <p className="text-green-400"># Campagne complète (llama-bench + API)</p>
        <p className="text-foreground mb-3">./scripts/benchmark.sh</p>
        <p className="text-green-400"># API uniquement (rapide)</p>
        <p className="text-foreground mb-3">./scripts/benchmark.sh --api-only</p>
        <p className="text-green-400"># llama-bench uniquement</p>
        <p className="text-foreground mb-3">./scripts/benchmark.sh --llama-only</p>
        <p className="text-green-400"># Endpoints disponibles</p>
        <p className="text-foreground">GET /api/benchmark/embedding?iterations=10</p>
        <p className="text-foreground">GET /api/benchmark/llm?iterations=3</p>
        <p className="text-foreground">GET /api/benchmark/rag?iterations=5&maxChunks=2</p>
        <p className="text-foreground">GET /api/benchmark  (suite complète)</p>
      </div>
      <p className="text-xs text-muted-foreground mt-3 italic">
        Prérequis pour le benchmark RAG : ingérer des documents via l'onglet Pipelines avant de lancer le test.
      </p>
    </div>
  </div>
);

const sectionTips = () => (
  <div className="space-y-6 animate-in fade-in slide-in-from-right-4 duration-500">
    <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
      <div className="space-y-4">
        <h3 className="text-lg font-headline font-bold text-primary uppercase tracking-widest text-[10px]">Qualité des Documents</h3>
        <p className="text-sm text-muted-foreground leading-relaxed">
          Privilégiez les PDF générés numériquement (non scannés). Les documents structurés avec des titres clairs produisent de meilleurs chunks sémantiques. 
          Incluez vos lexiques métier pour que Spectra maîtrise votre vocabulaire spécifique.
        </p>
      </div>
      <div className="space-y-4">
        <h3 className="text-lg font-headline font-bold text-primary uppercase tracking-widest text-[10px]">Volume Recommandé</h3>
        <p className="text-sm text-muted-foreground leading-relaxed">
          Visez un minimum de 200 à 300 pages pour obtenir un dataset de fine-tuning robuste. 
          Si votre base est plus petite, baissez le seuil de confiance à 0.7 dans les paramètres de job.
        </p>
      </div>
      <div className="space-y-4">
        <h3 className="text-lg font-headline font-bold text-primary uppercase tracking-widest text-[10px]">Tests Rapides</h3>
        <p className="text-sm text-muted-foreground leading-relaxed">
          Utilisez "Max Chunks = 10" lors de votre première génération de dataset. Cela vous permet de valider la qualité des paires Q/A en quelques minutes avant de lancer le traitement complet.
        </p>
      </div>
      <div className="space-y-4">
        <h3 className="text-lg font-headline font-bold text-primary uppercase tracking-widest text-[10px]">RAG vs Fine-Tuning</h3>
        <p className="text-sm text-muted-foreground leading-relaxed">
          Le RAG (Playground) est idéal pour trouver des faits précis. Le Fine-Tuning permet au modèle d'adopter le style et le raisonnement de votre domaine même sur des questions générales.
        </p>
      </div>
    </div>
  </div>
);

const sectionTroubleshooting = () => (
  <div className="space-y-6 animate-in fade-in slide-in-from-right-4 duration-500">
    <div className="bg-card/50 border border-border/40 rounded-xl overflow-hidden">
      <div className="divide-y divide-border/40">
        <div className="p-6">
          <h4 className="font-bold text-foreground mb-2">L'ingestion produit 0 chunks ?</h4>
          <p className="text-sm text-muted-foreground">
            Vérifiez que le texte de votre PDF est sélectionnable. S'il s'agit d'une image, Spectra ne pourra pas en extraire le contenu sans OCR préalable.
          </p>
        </div>
        <div className="p-6">
          <h4 className="font-bold text-foreground mb-2">Génération de dataset bloquée ?</h4>
          <p className="text-sm text-muted-foreground">
            Sur CPU, la génération est lente (2-5 min par chunk). Vérifiez les logs via <code className="bg-black/40 px-1.5 py-0.5 rounded text-primary">docker compose logs spectra-api</code> pour s'assurer que le modèle Phi-3 répond correctement.
          </p>
        </div>
        <div className="p-6">
          <h4 className="font-bold text-foreground mb-2">Erreur 400 lors du Fine-Tuning ?</h4>
          <p className="text-sm text-muted-foreground">
            Vérifiez que le fichier GGUF de base est présent dans <code className="bg-black/40 px-1.5 py-0.5 rounded text-primary">data/fine-tuning/merged/model.gguf</code> et que le conteneur <code className="bg-black/40 px-1.5 py-0.5 rounded text-primary">llama-cpp-chat</code> est démarré et healthy.
          </p>
        </div>
        <div className="p-6 bg-primary/5">
          <h4 className="font-bold text-primary mb-2 italic">Réinitialisation d'urgence</h4>
          <p className="text-sm text-muted-foreground">
            Si la base vectorielle est corrompue : <code className="bg-black/40 px-1.5 py-0.5 rounded">docker compose down -v</code> puis <code className="bg-black/40 px-1.5 py-0.5 rounded">docker compose up -d</code>. 
            <span className="text-primary/80 font-bold ml-1">Attention: cela efface toutes les données ingérées.</span>
          </p>
        </div>
      </div>
    </div>
  </div>
);

export default Documentation;
