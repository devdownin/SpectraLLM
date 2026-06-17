import React, { useState } from 'react';

const Documentation: React.FC = () => {
  const [activeTab, setActiveTab] = useState('overview');

  const sections = [
    { id: 'overview',          title: 'Overview' },
    { id: 'prerequisites',     title: 'Prerequisites' },
    { id: 'pipeline',          title: 'Pipeline' },
    { id: 'commenting',        title: 'AI Comments' },
    { id: 'personalisation',   title: 'Personalization' },
    { id: 'interface',         title: 'Interface' },
    { id: 'benchmark',         title: 'Benchmark' },
    { id: 'tips',              title: 'Tips' },
    { id: 'troubleshooting',   title: 'Troubleshooting' },
  ];

  return (
    <div className="space-y-8 animate-in fade-in slide-in-from-bottom-4 duration-700">
      {/* Header */}
      <div className="flex flex-col md:flex-row md:items-end justify-between border-b border-border/40 pb-6 gap-4">
        <div>
          <h1 className="text-4xl font-headline font-bold tracking-tight text-foreground">Documentation</h1>
          <p className="text-muted-foreground mt-2 max-w-2xl text-lg">
            Master Spectra — from document ingestion to the continuous personalization loop and preference-based fine-tuning.
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
      <h2 className="text-2xl font-headline font-bold text-primary mb-4">Welcome to Spectra</h2>
      <p className="text-lg leading-relaxed text-foreground/80">
        Spectra lets you build your own artificial intelligence assistant specialized in{' '}
        <strong>your business domain</strong>, from your own documents.
        The assistant runs <strong>entirely on-premises</strong> — no data ever leaves your infrastructure.
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
          title: 'Total Privacy',
          body: 'Your documents never leave your infrastructure. Ingestion, RAG, fine-tuning — everything is 100% local, with no cloud dependency.',
          badge: 'Privacy',
        },
        {
          icon: (
            <svg className="w-6 h-6 text-primary" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 10V3L4 14h7v7l9-11h-7z" />
            </svg>
          ),
          title: 'Multi-Strategy RAG',
          body: 'Hybrid BM25 + vector search, Cross-Encoder re-ranking, agentic ReAct loop, Multi-Query, Corrective RAG — 10+ configurable modules.',
          badge: 'Retrieval',
        },
        {
          icon: (
            <svg className="w-6 h-6 text-primary" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10" />
            </svg>
          ),
          title: 'Built-in Fine-Tuning',
          body: "QLoRA 4-bit (Unsloth), automatic SFT + DPO dataset generation, real-time streaming of loss/epoch metrics, GGUF export.",
          badge: 'Training',
        },
        {
          icon: (
            <svg className="w-6 h-6 text-primary" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z" />
            </svg>
          ),
          title: 'Continuous Personalization',
          body: 'Analytical comments generated via RAG on each document. Rate 👍/👎 to build DPO pairs. Once the threshold is reached, re-training triggers automatically.',
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
      <h3 className="text-lg font-headline font-bold text-foreground">Complete personalization cycle</h3>
      <div className="overflow-x-auto">
        <div className="flex items-stretch gap-0 min-w-max">
          {[
            { num: '1', label: 'Ingest',      sub: 'PDF · DOCX · URL',     color: 'bg-primary', text: 'text-primary' },
            { arrow: true },
            { num: '2', label: 'Generate',    sub: 'Q/A · summaries',      color: 'bg-primary', text: 'text-primary' },
            { arrow: true },
            { num: '3', label: 'Annotate',    sub: 'RAG → comments',       color: 'bg-secondary/80', text: 'text-secondary' },
            { arrow: true },
            { num: '4', label: 'Fine-Tune',   sub: 'QLoRA · GGUF',        color: 'bg-primary', text: 'text-primary' },
            { arrow: true },
            { num: '5', label: 'Evaluation',  sub: 'LLM-as-judge',        color: 'bg-secondary/80', text: 'text-secondary' },
            { arrow: true },
            { num: '↺', label: 'Auto-trigger',sub: 'N approvals → FT',    color: 'bg-primary', text: 'text-primary' },
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
          The <strong>↺ Auto-trigger</strong> step closes the loop: when the number of approved comments reaches
          the configured threshold (<code className="font-mono bg-black/30 px-1">spectra.ged.auto-retrain-threshold</code>, default = 5),
          a DPO fine-tuning job is submitted <em>automatically</em> — with no human intervention.
        </p>
      </div>
    </div>

    {/* Stats highlight */}
    <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
      {[
        { label: '10+',  sub: 'RAG strategies',        color: 'text-primary' },
        { label: 'Auto', sub: 'built-in DPO re-train', color: 'text-secondary' },
        { label: '100%', sub: 'local & open-source',   color: 'text-primary' },
        { label: 'DPO',  sub: 'active human loop',     color: 'text-secondary' },
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
      <h2 className="text-2xl font-headline font-bold text-primary mb-6">System Prerequisites</h2>
      <div className="space-y-4">
        <p className="text-foreground/80">Before getting started, make sure you have installed the following components:</p>
        <ul className="list-disc list-inside space-y-2 text-foreground/80 ml-4">
          <li><span className="text-foreground font-bold">Docker Desktop</span> (v4.x+) — Started and running.</li>
          <li><span className="text-foreground font-bold">llama-server</span> — Provided automatically via Docker (llama-cpp-turboquant).</li>
        </ul>

        <div className="mt-8">
          <h3 className="text-lg font-headline font-bold text-foreground mb-3 uppercase tracking-wider text-[10px]">Required GGUF Models</h3>
          <div className="bg-black/40 p-4 rounded-lg font-mono text-sm border border-border/20">
            <p className="text-green-400"># Embeddings — place in data/models/embed.gguf</p>
            <p className="text-foreground mb-3">nomic-embed-text-v1.5.Q4_K_M.gguf</p>
            <p className="text-green-400"># Chat — place in data/fine-tuning/merged/model.gguf</p>
            <p className="text-foreground">phi-3-mini-4k-instruct-q4.gguf  (or any compatible GGUF)</p>
          </div>
        </div>

        <div className="mt-6 p-4 bg-primary/5 border border-primary/20 rounded-lg flex gap-4">
          <div className="text-primary mt-1">
            <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" /></svg>
          </div>
          <p className="text-sm text-foreground/80 italic">
            No GPU required to get started. Spectra uses CPU inference by default.
            Fine-tuning with real weights is optional and requires an NVIDIA card + CUDA.
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
          Every uploaded file goes through a 4-step processing chain before being indexed:
        </p>
        {/* Mini pipeline diagram */}
        <div className="bg-black/30 rounded-lg p-3 font-mono text-xs space-y-1 mb-4">
          <p className="text-green-400">File (PDF/DOCX/JSON/URL)</p>
          <p className="text-muted-foreground">  ↓ Raw text extraction</p>
          <p className="text-muted-foreground">  ↓ Cleaning (8 passes): dedup · Unicode · stops</p>
          <p className="text-muted-foreground">  ↓ Chunking: 512 tokens, 64 overlap</p>
          <p className="text-foreground">  ↓ Embedding → ChromaDB (768-dim vectors)</p>
        </div>
        <ul className="space-y-1.5 text-xs text-foreground/80">
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-primary shrink-0" /> Formats: PDF, DOCX, DOC, JSON, XML, TXT, HTML, Avro, ZIP, URL</li>
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-primary shrink-0" /> Layout-aware parsing (tables preserved) via optional docparser</li>
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-primary shrink-0" /> Document lifecycle management: INGESTED → REVIEWED → TRAINED</li>
        </ul>
      </div>

      {/* Step 2 */}
      <div className="bg-card/50 border border-border/40 rounded-xl p-6">
        <div className="flex items-center gap-2 mb-4">
          <div className="w-7 h-7 rounded-full bg-primary flex items-center justify-center font-headline font-bold text-primary-foreground text-xs">2</div>
          <h3 className="text-xl font-headline font-bold text-foreground">Dataset Generation</h3>
        </div>
        <p className="text-sm text-muted-foreground mb-4 leading-relaxed">
          For each chunk, Spectra generates 3–4 training pairs covering different types:
        </p>
        <div className="grid grid-cols-2 gap-2 mb-4 text-xs">
          {[
            { type: 'Q&A', desc: 'Technical question + extracted answer', color: 'border-primary/30 text-primary' },
            { type: 'Summary', desc: 'Condensed synthesis of the chunk', color: 'border-secondary/30 text-secondary' },
            { type: 'Negative', desc: 'Off-topic question → factual refusal', color: 'border-border/40 text-muted-foreground' },
            { type: 'DPO pair', desc: 'Chosen (correct) + Rejected (LLM erroneous)', color: 'border-border/40 text-muted-foreground' },
          ].map(t => (
            <div key={t.type} className={`rounded p-2 border ${t.color}`}>
              <p className={`font-bold text-[9px] uppercase tracking-widest ${t.color}`}>{t.type}</p>
              <p className="text-muted-foreground mt-0.5 text-[8px]">{t.desc}</p>
            </div>
          ))}
        </div>
        <p className="text-xs text-muted-foreground">A confidence score 0–1 is assigned to each pair. Filtered at the ≥ 0.8 threshold before fine-tuning.</p>
      </div>

      {/* Step 2c */}
      <div className="bg-card/50 border border-secondary/30 rounded-xl p-6">
        <div className="flex items-center gap-2 mb-4">
          <div className="w-7 h-7 rounded-full bg-secondary/80 flex items-center justify-center font-headline font-bold text-foreground text-xs">2c</div>
          <h3 className="text-xl font-headline font-bold text-foreground">AI Annotations</h3>
          <span className="text-[8px] font-bold uppercase tracking-widest border border-secondary/40 text-secondary px-2 py-0.5 rounded-full">DPO Loop</span>
        </div>
        <p className="text-sm text-muted-foreground mb-4 leading-relaxed">
          The LLM generates an analytical comment grounded in the document's actual chunks (RAG). Your 👍/👎 ratings form DPO pairs.
        </p>
        <div className="bg-black/30 rounded-lg p-3 font-mono text-xs space-y-1 mb-3">
          <p className="text-secondary">RAG (6 chunks) + user focus</p>
          <p className="text-muted-foreground">  ↓ LLM generates comment (temp=0.4)</p>
          <p className="text-muted-foreground">  ↓ User: 👍 APPROVED / 👎 REJECTED</p>
          <p className="text-foreground">  ↓ DPO export: prompt / chosen / rejected</p>
        </div>
        <ul className="space-y-1.5 text-xs text-foreground/80">
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-secondary shrink-0" /> Jaccard guard: overly similar pairs automatically rejected</li>
          <li className="flex items-center gap-2"><div className="w-1.5 h-1.5 rounded-full bg-secondary shrink-0" /> Auto-trigger: N approvals → re-training launched automatically</li>
        </ul>
      </div>

      {/* Step 3 */}
      <div className="bg-card/50 border border-border/40 rounded-xl p-6">
        <div className="flex items-center gap-2 mb-4">
          <div className="w-7 h-7 rounded-full bg-primary flex items-center justify-center font-headline font-bold text-primary-foreground text-xs">3</div>
          <h3 className="text-xl font-headline font-bold text-foreground">Fine-Tuning</h3>
        </div>
        <p className="text-sm text-muted-foreground mb-4 leading-relaxed">
          Trains the base model with 4-bit QLoRA via Unsloth. Three preconfigured recipes:
        </p>
        <div className="space-y-2 text-xs mb-4">
          {[
            { name: 'cpu-rapide',      desc: 'rank 8, 1 epoch — quick test on CPU',              color: 'text-muted-foreground' },
            { name: 'gpu-qualite',     desc: 'rank 64, 3 epochs — best quality (GPU)',            color: 'text-primary' },
            { name: 'dpo-alignement',  desc: 'rank 32, DPO enabled — integrates your preferences', color: 'text-secondary' },
          ].map(r => (
            <div key={r.name} className="flex items-start gap-2">
              <code className={`font-mono font-bold text-[9px] ${r.color} shrink-0`}>{r.name}</code>
              <span className="text-muted-foreground">{r.desc}</span>
            </div>
          ))}
        </div>
        <p className="text-xs text-muted-foreground">Output: <code className="font-mono bg-black/30 px-1">adapter.gguf</code> in the working directory.</p>
      </div>

      {/* Step 4 */}
      <div className="bg-card/50 border border-secondary/30 rounded-xl p-6">
        <div className="flex items-center gap-2 mb-4">
          <div className="w-7 h-7 rounded-full bg-secondary/80 flex items-center justify-center font-headline font-bold text-foreground text-xs">4</div>
          <h3 className="text-xl font-headline font-bold text-foreground">LLM-as-Judge Evaluation</h3>
        </div>
        <p className="text-sm text-muted-foreground mb-4 leading-relaxed">
          After fine-tuning, run an automatic evaluation. The same LLM acts as judge and scores responses from 1 to 10:
        </p>
        <div className="bg-black/30 rounded-lg p-3 font-mono text-xs space-y-0.5 mb-3">
          <p className="text-muted-foreground">5% of the dataset (min 5, max 50 pairs)</p>
          <p className="text-muted-foreground">  ↓ Model answers the test questions</p>
          <p className="text-muted-foreground">  ↓ LLM judge compares against the reference answer</p>
          <p className="text-foreground">  ↓ Score 1–10 per criterion: accuracy, completeness, clarity</p>
        </div>
        <p className="text-xs text-muted-foreground">Per-category scores (qa, summary, classification) appear in Dashboard &gt; Personalization Cycle.</p>
      </div>

      {/* Step RAG */}
      <div className="bg-card/50 border border-border/40 rounded-xl p-6 md:col-span-2">
        <div className="flex items-center gap-2 mb-4">
          <div className="w-7 h-7 rounded-full bg-secondary/40 flex items-center justify-center font-headline font-bold text-foreground text-xs">5</div>
          <h3 className="text-xl font-headline font-bold text-foreground">RAG Querying — Playground</h3>
        </div>
        <p className="text-sm text-muted-foreground mb-4 leading-relaxed">
          Ask your technical questions in the Playground. Spectra automatically selects the optimal RAG strategy based on the detected complexity.
        </p>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          {[
            { label: 'Hybrid Search',   desc: 'BM25 + vectors → RRF fusion', icon: 'merge' },
            { label: 'Re-ranking',      desc: '2-stage Cross-Encoder', icon: 'sort' },
            { label: 'Agentic ReAct',   desc: 'Iterative multi-hop loop', icon: 'psychology' },
            { label: 'Corrective RAG',  desc: 'LLM grading of chunks', icon: 'fact_check' },
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
          <h2 className="text-2xl font-headline font-bold text-foreground">AI Comments — RAG + DPO Loop</h2>
          <p className="text-sm text-muted-foreground">Pipeline step 2c — available on the Database page</p>
        </div>
      </div>
      <p className="text-sm text-foreground/80 leading-relaxed">
        Every GED document can receive analytical comments generated automatically by the LLM,
        grounded in the document's actual content via RAG. Your ratings (👍/👎) form
        DPO pairs that feed the next fine-tuning cycle. Once the configured approval threshold
        is reached, <strong>re-training triggers automatically</strong>.
      </p>
    </div>

    {/* Why it's optimal */}
    <div className="bg-card/50 border border-border/40 rounded-xl p-8 space-y-6">
      <h3 className="text-lg font-headline font-bold text-foreground">Why RAG + DPO Fine-tuning is optimal</h3>
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {[
          {
            icon: 'search',
            title: 'RAG alone',
            body: 'The comment is grounded in the actual document — no hallucination. But the style and terminology remain generic.',
            badge: 'Factual but generic',
            accent: 'border-outline-variant/40 text-muted-foreground',
          },
          {
            icon: 'model_training',
            title: 'Fine-tuning alone',
            body: 'The model learns your domain\'s style. But without RAG context, it may invent details absent from the document.',
            badge: 'Adapted style but fragile',
            accent: 'border-outline-variant/40 text-muted-foreground',
          },
          {
            icon: 'sync',
            title: 'RAG + DPO (optimal)',
            body: 'RAG grounds each comment in the actual chunks. DPO aligns the model with your quality preferences. Each cycle improves relevance.',
            badge: 'Factual AND aligned ✓',
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

    {/* Full lifecycle */}
    <div className="bg-card/50 border border-border/40 rounded-xl p-8 space-y-6">
      <h3 className="text-lg font-headline font-bold text-foreground">Full lifecycle of an AI comment</h3>
      <div className="space-y-3">
        {[
          { n: '1', icon: 'chat_bubble',     label: 'User focus',                desc: 'Provide a specific analysis angle ("emergency procedures") or leave blank for a general summary.', color: 'text-primary border-primary/30 bg-primary/5' },
          { n: '2', icon: 'search',          label: 'RAG retrieval',             desc: 'Spectra retrieves the 6 most relevant passages of the document from ChromaDB via hybrid search.', color: 'text-primary border-primary/30 bg-primary/5' },
          { n: '3', icon: 'memory',          label: 'LLM generation (temp 0.4)', desc: 'The LLM writes a factual analytical comment grounded in those passages. Low temperature = fewer fabrications.', color: 'text-secondary border-secondary/30 bg-secondary/5' },
          { n: '4', icon: 'thumb_up',        label: 'Rating 👍/👎',             desc: 'You rate APPROVED or REJECTED. Each rating creates a preference. The Jaccard guard filters out overly similar pairs.', color: 'text-secondary border-secondary/30 bg-secondary/5' },
          { n: '5', icon: 'download',        label: 'DPO export',                desc: 'Click "DPO↓" — Spectra exports the (chosen, rejected) pairs as JSONL. Pairs with Jaccard > 0.85 are excluded.', color: 'text-primary border-primary/30 bg-primary/5' },
          { n: '↺', icon: 'auto_mode',       label: 'Auto re-training',          desc: 'At the Nth approved comment (configurable threshold), a DPO fine-tuning job is submitted automatically.', color: 'text-primary border-primary/30 bg-primary/5' },
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

    {/* How to use */}
    <div className="bg-card/50 border border-border/40 rounded-xl p-8 space-y-5">
      <h3 className="text-lg font-headline font-bold text-foreground">How to use it in the interface</h3>
      <ol className="space-y-4 text-sm text-foreground/80">
        <li className="flex gap-3">
          <span className="w-6 h-6 rounded-full bg-primary/20 text-primary flex items-center justify-center font-bold text-xs shrink-0 mt-0.5">1</span>
          <span>Click <strong>Database</strong> in the side menu, then a document to open its record.</span>
        </li>
        <li className="flex gap-3">
          <span className="w-6 h-6 rounded-full bg-primary/20 text-primary flex items-center justify-center font-bold text-xs shrink-0 mt-0.5">2</span>
          <span>In the <strong>Comments</strong> section, choose the <strong>✦ AI</strong> tab.</span>
        </li>
        <li className="flex gap-3">
          <span className="w-6 h-6 rounded-full bg-primary/20 text-primary flex items-center justify-center font-bold text-xs shrink-0 mt-0.5">3</span>
          <span>Enter an analysis angle (<em>e.g. "safety procedures"</em>) and click <strong>✦ Generate via RAG</strong>.</span>
        </li>
        <li className="flex gap-3">
          <span className="w-6 h-6 rounded-full bg-secondary/30 text-secondary flex items-center justify-center font-bold text-xs shrink-0 mt-0.5">4</span>
          <span>Read the comment in the <strong>List</strong> tab and click <strong>👍</strong> or <strong>👎</strong>. As soon as the threshold is reached, the progress bar in the Dashboard fills up.</span>
        </li>
        <li className="flex gap-3">
          <span className="w-6 h-6 rounded-full bg-secondary/30 text-secondary flex items-center justify-center font-bold text-xs shrink-0 mt-0.5">5</span>
          <span>Repeat across several documents. DPO re-training triggers <strong>automatically</strong> at the threshold (default: 5 approvals).</span>
        </li>
        <li className="flex gap-3">
          <span className="w-6 h-6 rounded-full bg-primary/20 text-primary flex items-center justify-center font-bold text-xs shrink-0 mt-0.5">6</span>
          <span>Track progress in <strong>Fine-Tuning</strong>, then run an <strong>Evaluation</strong> to measure the gain.</span>
        </li>
      </ol>
    </div>

    {/* API reference */}
    <div className="bg-card/50 border border-border/40 rounded-xl p-8">
      <h3 className="text-lg font-headline font-bold text-foreground mb-4">API Reference</h3>
      <div className="bg-black/40 p-5 rounded-lg font-mono text-sm border border-border/20 space-y-1 overflow-x-auto">
        <p className="text-green-400"># List a document's comments</p>
        <p className="text-foreground mb-3">GET /api/ged/documents/{'{sha256}'}/comments</p>

        <p className="text-green-400"># Generate an AI comment via RAG</p>
        <p className="text-foreground">POST /api/ged/documents/{'{sha256}'}/comments</p>
        <p className="text-muted-foreground mb-3">{'{ "content": "safety procedures", "generate": true }'}</p>

        <p className="text-green-400"># Rate (APPROVED / REJECTED / NONE)</p>
        <p className="text-foreground mb-3">PATCH /api/ged/documents/{'{sha256}'}/comments/{'{id}'}/rating?rating=APPROVED</p>

        <p className="text-green-400"># Export DPO pairs (Jaccard-filtered)</p>
        <p className="text-foreground">POST /api/ged/documents/export/comments-dpo</p>
        <p className="text-muted-foreground mb-3">{'→ { "pairs": 12, "file": "data/dataset/comments_dpo.jsonl" }'}</p>

        <p className="text-green-400"># Personalization cycle metrics</p>
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
        <h2 className="text-2xl font-headline font-bold text-foreground">Continuous Personalization</h2>
        <span className="text-[8px] font-bold uppercase tracking-widest border border-primary/40 text-primary px-2 py-0.5 rounded-full">v1.1</span>
      </div>
      <p className="text-sm text-foreground/80 leading-relaxed">
        Spectra implements an <strong>automatic personalization loop</strong> built from 4 complementary mechanisms.
        Together, they ensure your human annotations genuinely improve the model on every cycle.
      </p>
    </div>

    {/* ── Feature 1 : Auto-trigger ── */}
    <div className="bg-card/50 border border-border/40 rounded-xl p-8 space-y-6">
      <div className="flex items-start gap-3">
        <div className="w-8 h-8 rounded-full bg-primary flex items-center justify-center font-headline font-bold text-primary-foreground text-sm shrink-0">1</div>
        <div>
          <h3 className="text-lg font-headline font-bold text-foreground">Automatic re-training trigger</h3>
          <p className="text-xs text-muted-foreground mt-1 uppercase tracking-widest">ArticleCommentService → FineTuningService</p>
        </div>
      </div>

      <p className="text-sm text-foreground/80 leading-relaxed">
        On every comment approval (👍), Spectra counts the total number of approved AI comments.
        When that total reaches a <strong>multiple of the configured threshold</strong>, a DPO fine-tuning job
        is submitted automatically — with no manual intervention.
      </p>

      {/* Diagram */}
      <div className="bg-black/40 rounded-xl p-6 border border-border/20">
        <p className="text-[9px] font-headline uppercase tracking-widest text-muted-foreground mb-4">Trigger diagram</p>
        <div className="font-mono text-xs space-y-1">
          <div className="flex items-center gap-2">
            <span className="text-muted-foreground w-28">Approval 1</span>
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
            <span className="text-muted-foreground w-28">Approval 3</span>
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
            <span className="text-foreground w-28 font-bold">Approval 5</span>
            <div className="flex gap-0.5">
              <div className="w-6 h-3 bg-primary rounded-sm" />
              <div className="w-6 h-3 bg-primary rounded-sm" />
              <div className="w-6 h-3 bg-primary rounded-sm" />
              <div className="w-6 h-3 bg-primary rounded-sm" />
              <div className="w-6 h-3 bg-primary rounded-sm" />
            </div>
            <span className="text-primary font-bold text-[9px] flex items-center gap-1">
              5 / 5 → <span className="material-symbols-outlined text-[11px]">rocket_launch</span> DPO FT launched!
            </span>
          </div>
          <div className="flex items-center gap-2 mt-1">
            <span className="text-muted-foreground w-28">Approval 10</span>
            <div className="flex gap-0.5">
              <div className="w-6 h-3 bg-primary rounded-sm" />
              <div className="w-6 h-3 bg-primary rounded-sm" />
              <div className="w-6 h-3 bg-primary rounded-sm" />
              <div className="w-6 h-3 bg-primary rounded-sm" />
              <div className="w-6 h-3 bg-primary rounded-sm" />
            </div>
            <span className="text-primary text-[9px] flex items-center gap-1">
              10 / 5 → <span className="material-symbols-outlined text-[11px]">rocket_launch</span> 2nd cycle
            </span>
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <div className="bg-black/30 rounded-lg p-4">
          <p className="text-[9px] font-headline uppercase tracking-widest text-primary mb-2">Configuration</p>
          <div className="font-mono text-xs space-y-1 text-foreground/80">
            <p><span className="text-muted-foreground">Key:</span> spectra.ged.auto-retrain-threshold</p>
            <p><span className="text-muted-foreground">Default:</span> 5</p>
            <p><span className="text-muted-foreground">Env var:</span> SPECTRA_GED_AUTO_RETRAIN_THRESHOLD</p>
          </div>
        </div>
        <div className="bg-black/30 rounded-lg p-4">
          <p className="text-[9px] font-headline uppercase tracking-widest text-secondary mb-2">What happens</p>
          <div className="text-xs space-y-1 text-foreground/80">
            <p>1. <code className="font-mono">exportDpoPairs()</code> → <code className="font-mono">comments_dpo.jsonl</code></p>
            <p>2. <code className="font-mono">FineTuningService.submit()</code> with <code className="font-mono">dpoEnabled=true</code></p>
            <p>3. Asynchronous execution (off the HTTP thread)</p>
          </div>
        </div>
      </div>
    </div>

    {/* ── Feature 2 : Jaccard ── */}
    <div className="bg-card/50 border border-border/40 rounded-xl p-8 space-y-6">
      <div className="flex items-start gap-3">
        <div className="w-8 h-8 rounded-full bg-secondary/80 flex items-center justify-center font-headline font-bold text-foreground text-sm shrink-0">2</div>
        <div>
          <h3 className="text-lg font-headline font-bold text-foreground">DPO quality guard — Jaccard similarity</h3>
          <p className="text-xs text-muted-foreground mt-1 uppercase tracking-widest">DpoGenerationService · ArticleCommentService</p>
        </div>
      </div>

      <p className="text-sm text-foreground/80 leading-relaxed">
        A DPO pair is only valuable if <code className="font-mono bg-black/30 px-1">chosen</code> and <code className="font-mono bg-black/30 px-1">rejected</code> are <strong>genuinely different</strong>.
        If the LLM produces an "incorrect" answer that is nearly identical to the correct one,
        DPO training cannot learn the distinction — and may even degrade the model.
      </p>

      {/* Formula */}
      <div className="bg-black/40 rounded-xl p-6 border border-border/20 space-y-4">
        <p className="text-[9px] font-headline uppercase tracking-widest text-muted-foreground">Jaccard formula over word sets</p>
        <div className="flex items-center justify-center">
          <div className="font-mono text-sm text-center space-y-1">
            <p className="text-foreground/60 text-xs">Let A = words of <span className="text-primary">chosen</span>, B = words of <span className="text-secondary">rejected</span></p>
            <div className="bg-secondary/20 px-6 py-3 rounded-lg inline-block mt-2">
              <p className="text-foreground text-base">
                J(A, B) = <span className="text-primary">|A ∩ B|</span> / <span className="text-secondary">|A ∪ B|</span>
              </p>
            </div>
            <p className="text-muted-foreground text-xs mt-2">
              Result ∈ [0, 1] · If J &gt; <span className="text-primary font-bold">0.85</span> → pair rejected
            </p>
          </div>
        </div>
      </div>

      {/* Worked example */}
      <div className="space-y-3">
        <p className="text-[9px] font-headline uppercase tracking-widest text-muted-foreground">Concrete example</p>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
          <div className="bg-green-500/5 border border-green-500/20 rounded-lg p-4">
            <p className="text-[9px] font-bold uppercase tracking-widest text-green-400 mb-2">ACCEPTED pair ✓</p>
            <div className="font-mono text-xs space-y-2">
              <div>
                <p className="text-primary text-[9px] uppercase">chosen</p>
                <p className="text-foreground/80">"The document describes 5 steps: alert, containment, evacuation, response, return-to-normal"</p>
              </div>
              <div>
                <p className="text-secondary text-[9px] uppercase">rejected</p>
                <p className="text-foreground/60">"This report summarizes the legal safety obligations in 3 main points"</p>
              </div>
              <div className="border-t border-border/20 pt-2">
                <p className="text-muted-foreground">A ∩ B = {'{'}the{'}'} = 2 words</p>
                <p className="text-muted-foreground">A ∪ B ≈ 22 words</p>
                <p className="text-green-400 font-bold">J = 2/22 = <strong>0.09</strong> → ACCEPTED</p>
              </div>
            </div>
          </div>

          <div className="bg-red-500/5 border border-red-500/20 rounded-lg p-4">
            <p className="text-[9px] font-bold uppercase tracking-widest text-red-400 mb-2">REJECTED pair ✗</p>
            <div className="font-mono text-xs space-y-2">
              <div>
                <p className="text-primary text-[9px] uppercase">chosen</p>
                <p className="text-foreground/80">"The document describes 5 steps: alert, containment, evacuation, response, return-to-normal"</p>
              </div>
              <div>
                <p className="text-secondary text-[9px] uppercase">rejected</p>
                <p className="text-foreground/60">"The document describes 5 steps: alert, containment, evacuation, response, normalization"</p>
              </div>
              <div className="border-t border-border/20 pt-2">
                <p className="text-muted-foreground">A ∩ B = 13 common words</p>
                <p className="text-muted-foreground">A ∪ B = 15 words</p>
                <p className="text-red-400 font-bold">J = 13/15 = <strong>0.87</strong> → REJECTED (› 0.85)</p>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div className="flex items-start gap-2 p-3 bg-secondary/5 border border-secondary/20 rounded-lg">
        <span className="material-symbols-outlined text-sm text-secondary shrink-0 mt-0.5">info</span>
        <p className="text-xs text-muted-foreground">
          The 0.85 threshold is hard-coded as the <code className="font-mono bg-black/30 px-1">SIMILARITY_THRESHOLD</code> constant in both services.
          A lower threshold (e.g. 0.70) is stricter — it also filters out legitimately close pairs.
          0.85 is a good compromise for technical text.
        </p>
      </div>
    </div>

    {/* ── Feature 3 : Model sync ── */}
    <div className="bg-card/50 border border-border/40 rounded-xl p-8 space-y-6">
      <div className="flex items-start gap-3">
        <div className="w-8 h-8 rounded-full bg-primary flex items-center justify-center font-headline font-bold text-primary-foreground text-sm shrink-0">3</div>
        <div>
          <h3 className="text-lg font-headline font-bold text-foreground">Registry ↔ llama-server consistency check</h3>
          <p className="text-xs text-muted-foreground mt-1 uppercase tracking-widest">LlamaCppChatClient · ModelRegistryService</p>
        </div>
      </div>

      <p className="text-sm text-foreground/80 leading-relaxed">
        Spectra maintains a local JSON model registry (<code className="font-mono bg-black/30 px-1">data/models/registry.json</code>)
        independently of llama-server. In the event of a mismatch — the registry points to a model the
        server does not know about — all requests will fail silently.
      </p>

      {/* Problem/Solution diagram */}
      <div className="bg-black/40 rounded-xl p-6 border border-border/20 space-y-4">
        <p className="text-[9px] font-headline uppercase tracking-widest text-muted-foreground">Mismatch problem</p>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 text-xs font-mono">
          <div className="bg-red-500/10 border border-red-500/20 rounded p-3">
            <p className="text-red-400 font-bold text-[9px] uppercase mb-2">Registry (registry.json)</p>
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
          <p className="text-xs text-red-400">Result: every request to "phi-4-mini-finetuned" fails (model not found)</p>
        </div>
      </div>

      {/* Solution */}
      <div className="bg-black/40 rounded-xl p-6 border border-border/20 space-y-3">
        <p className="text-[9px] font-headline uppercase tracking-widest text-muted-foreground">Implemented solution</p>
        <div className="font-mono text-xs space-y-1 text-foreground/80">
          <p><span className="text-primary">setActiveModel(</span><span className="text-secondary">"phi-4-mini-finetuned"</span><span className="text-primary">)</span></p>
          <p className="text-muted-foreground">  ↓ 1. Update the registry</p>
          <p className="text-muted-foreground">  ↓ 2. runtimeOrchestrator.ensureChatModelServed()</p>
          <p className="text-foreground">  ↓ 3. <span className="text-secondary">CompletableFuture.runAsync</span> → checkHealth()</p>
          <p className="text-muted-foreground pl-8">If status ≠ "ok" → WARN in the logs</p>
          <p className="text-muted-foreground pl-8">"REGISTRY/SERVER ALERT: model 'X' active</p>
          <p className="text-muted-foreground pl-8"> but not served by llama-server"</p>
        </div>
        <div className="flex items-start gap-2 p-2 bg-green-500/5 border border-green-500/20 rounded">
          <span className="material-symbols-outlined text-sm text-green-400 shrink-0">check_circle</span>
          <p className="text-xs text-green-400">The check is asynchronous: it does not block the model switch.</p>
        </div>
      </div>

      <div className="bg-black/30 rounded-lg p-4">
        <p className="text-[9px] font-headline uppercase tracking-widest text-muted-foreground mb-2">Monitor mismatches</p>
        <div className="font-mono text-xs text-foreground/70 space-y-1">
          <p className="text-green-400"># Search the logs</p>
          <p>docker compose logs spectra-api | grep "ALERTE REGISTRE"</p>
          <p className="text-green-400 mt-2"># Check the active model on the server side</p>
          <p>curl http://localhost:8081/v1/models | jq '.data[].id'</p>
        </div>
      </div>
    </div>

    {/* ── Feature 4 : Metrics Dashboard ── */}
    <div className="bg-card/50 border border-border/40 rounded-xl p-8 space-y-6">
      <div className="flex items-start gap-3">
        <div className="w-8 h-8 rounded-full bg-secondary/80 flex items-center justify-center font-headline font-bold text-foreground text-sm shrink-0">4</div>
        <div>
          <h3 className="text-lg font-headline font-bold text-foreground">Personalization metrics dashboard</h3>
          <p className="text-xs text-muted-foreground mt-1 uppercase tracking-widest">PersonalizationMetricsService · GET /api/metrics/personalization</p>
        </div>
      </div>

      <p className="text-sm text-foreground/80 leading-relaxed">
        A new endpoint aggregates all personalization-loop metrics in real time.
        The Dashboard displays this data in the <strong>"Personalization Cycle"</strong> section.
      </p>

      {/* Metrics map */}
      <div className="bg-black/40 rounded-xl p-6 border border-border/20">
        <p className="text-[9px] font-headline uppercase tracking-widest text-muted-foreground mb-4">Response structure</p>
        <div className="font-mono text-xs space-y-0.5 overflow-x-auto">
          <p className="text-foreground">{`GET /api/metrics/personalization`}</p>
          <p className="text-muted-foreground">{`{`}</p>
          <p className="text-muted-foreground pl-4"><span className="text-primary">"approvedComments"</span>{`: 12,`}<span className="text-muted-foreground/50 ml-3">//  AI comments rated 👍</span></p>
          <p className="text-muted-foreground pl-4"><span className="text-primary">"rejectedComments"</span>{`: 4,`} <span className="text-muted-foreground/50 ml-3">//  AI comments rated 👎</span></p>
          <p className="text-muted-foreground pl-4"><span className="text-primary">"totalAiComments"</span>{`: 20,`}<span className="text-muted-foreground/50 ml-3">//  all AI comments</span></p>
          <p className="text-muted-foreground pl-4"><span className="text-secondary">"dpoPairs"</span>{`: 9,`}        <span className="text-muted-foreground/50 ml-3">//  valid pairs in memory</span></p>
          <p className="text-muted-foreground pl-4"><span className="text-secondary">"completedCycles"</span>{`: 2,`}  <span className="text-muted-foreground/50 ml-3">//  auto-triggered cycles</span></p>
          <p className="text-muted-foreground pl-4"><span className="text-secondary">"nextTriggerIn"</span>{`: 3,`}   <span className="text-muted-foreground/50 ml-3">//  approvals before next trigger</span></p>
          <p className="text-muted-foreground pl-4"><span className="text-primary">"autoRetrainThreshold"</span>{`: 5,`}</p>
          <p className="text-muted-foreground pl-4"><span className="text-primary">"completedFineTuningJobs"</span>{`: 2,`}</p>
          <p className="text-muted-foreground pl-4"><span className="text-secondary">"latestEvalScore"</span>{`: 7.3,`}<span className="text-muted-foreground/50 ml-3">//  avg score /10 for last cycle</span></p>
          <p className="text-muted-foreground pl-4"><span className="text-muted-foreground">"fineTuningJobs"</span>{`: [...],`}</p>
          <p className="text-muted-foreground pl-4"><span className="text-muted-foreground">"evaluations"</span>{`: [...]`}</p>
          <p className="text-muted-foreground">{`}`}</p>
        </div>
      </div>

      {/* Dashboard visual */}
      <div className="space-y-3">
        <p className="text-[9px] font-headline uppercase tracking-widest text-muted-foreground">What you see in the Dashboard</p>
        <div className="grid grid-cols-4 gap-3">
          {[
            { label: 'Approved', value: '12', color: 'border-primary text-primary' },
            { label: 'DPO Pairs', value: '9', color: 'border-secondary text-secondary' },
            { label: 'Fine-Tunings', value: '2', color: 'border-border/40 text-foreground' },
            { label: 'Eval Score', value: '7.3/10', color: 'border-border/40 text-foreground' },
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
            <p className="text-[9px] uppercase tracking-widest text-muted-foreground">Next auto re-training</p>
            <p className="text-[9px] font-mono text-muted-foreground">threshold: 5</p>
          </div>
          <div className="w-full bg-border/20 h-2 rounded-full">
            <div className="h-2 bg-primary rounded-full" style={{ width: '80%' }} />
          </div>
          <div className="flex justify-between mt-1">
            <p className="text-[8px] text-muted-foreground">12 / 15 (2nd cycle + 2 into 3rd)</p>
            <p className="text-[8px] text-muted-foreground">3 more approvals</p>
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
