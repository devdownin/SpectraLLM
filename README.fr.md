<div align="center">

# ⚡ Spectra

### Transformez vos documents en assistant IA privé et fine-tuné — en une commande.

**100 % local · Sans cloud · Sans clé API · Sans abonnement**

[![Java CI with Maven](https://github.com/devdownin/SpectraLLM/actions/workflows/ci.yml/badge.svg)](https://github.com/devdownin/SpectraLLM/actions/workflows/ci.yml)
[![Code Coverage](https://codecov.io/gh/devdownin/SpectraLLM/branch/main/graph/badge.svg)](https://codecov.io/gh/devdownin/SpectraLLM)
[![OpenSSF Scorecard](https://api.securityscorecards.dev/projects/github.com/devdownin/SpectraLLM/badge)](https://securityscorecards.dev/viewer/?uri=github.com/devdownin/SpectraLLM)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)

[Démarrage rapide](#-démarrage-rapide) · [Pourquoi Spectra](#-pourquoi-spectra) · [Comment ça marche](#-comment-ça-marche) · [Documentation](#-documentation) · [English](./README.md)

</div>

---

Le savoir de votre organisation est enfermé dans des PDF, des documents Word, des wikis et des exports de données. Les LLM généralistes n'en connaissent rien — et envoyer vos documents internes à une API cloud est souvent exclu.

**Spectra ingère vos documents, répond à vos questions à partir de leur contenu (RAG), puis va plus loin : il fine-tune un modèle local qui connaît définitivement votre domaine** — et l'exporte en un fichier GGUF unique, déployable partout, même hors ligne.

La plupart des outils vous font choisir entre RAG *ou* fine-tuning, et vous laissent l'intégration. Spectra fait les deux, en séquence, automatiquement, sur votre matériel :

```
 vos documents ─► 📥 ingestion ─► 🔍 recherche & questions (RAG) ─► 🧪 dataset
                                                                        │
   déployez partout ◄─ 📦 export GGUF ◄─ 🎓 fine-tuning ◄──────────────┘
```

Une seule stack Docker. Une interface web pour tout le parcours. Vos données ne quittent jamais votre machine.

## 🚀 Démarrage rapide

```bash
git clone https://github.com/devdownin/SpectraLLM.git && cd SpectraLLM
./start.sh --first-run        # Windows : start.bat --first-run
```

C'est tout. Spectra télécharge les modèles par défaut (~1,2 Go), démarre la stack et ouvre l'interface sur **http://localhost**. Déposez un PDF sur la page Ingestion et posez vos questions.

> **Prérequis :** Docker (Compose v2) et 16 Go de RAM. GPU optionnel — NVIDIA, AMD/ROCm et Vulkan supportés, détection automatique. Vous préférez le pas-à-pas ? Voir **[Getting Started](docs/GETTING_STARTED.md)**.

## 🏆 Pourquoi Spectra

| Fonctionnalité | Spectra | LangChain | Haystack | Open WebUI |
|---------|:--------:|:---------:|:---------:|:---------:|
| Plateforme de bout en bout | ✅ | ❌ | ❌ | ❌ |
| RAG hybride avancé | ✅ | ⚠️ | ✅ | ❌ |
| RAG agentique | ✅ | ⚠️ | ⚠️ | ❌ |
| Génération de dataset synthétique | ✅ | ❌ | ❌ | ❌ |
| Fine-tuning QLoRA | ✅ | ❌ | ❌ | ❌ |
| Entraînement DPO | ✅ | ❌ | ❌ | ❌ |
| Apprentissage continu | ✅ | ❌ | ❌ | ❌ |
| Évaluation de modèles | ✅ | ❌ | ❌ | ❌ |
| Déploiement GGUF | ✅ | ❌ | ❌ | ⚠️ |
| Prêt pour Kubernetes | ✅ | ⚠️ | ⚠️ | ⚠️ |
| 100 % local | ✅ | ✅ | ✅ | ✅ |

> ✅ Intégré &nbsp;&nbsp; ⚠️ Intégration à développer &nbsp;&nbsp; ❌ Indisponible

Construire cela vous-même signifie assembler un framework d'orchestration, une base vectorielle, un chunker, un serveur d'embeddings, un pipeline de fine-tuning, un harnais d'évaluation et un frontend — chacun avec sa configuration et ses modes de panne. Spectra livre le tout dans un seul `docker compose up`.

## ⚙️ Comment ça marche

1. **📥 Ingestion** — PDF, DOCX, HTML, JSON, XML, TXT, ZIP, URLs, et même des flux Kafka en continu. Le parsing PDF préserve tableaux et titres ; nettoyage en 8 étapes et chunking sémantique.
2. **🔍 Recherche** — Recherche hybride : BM25 (mots-clés) + similarité vectorielle, fusionnées par Reciprocal Rank Fusion, puis re-ranking cross-encoder, déduplication sémantique et compression de contexte.
3. **💬 Réponses** — Six stratégies RAG choisies adaptativement par question : Standard, Hybride, Multi-Query, Corrective, Self-RAG, et une boucle agentique ReAct pour les questions multi-étapes. Réponses en streaming avec sources citées.
4. **🧪 Synthèse** — Spectra génère un dataset d'entraînement Q/R et DPO à partir de votre propre corpus, noté par un LLM-juge.
5. **🎓 Fine-tuning** — Des recettes QLoRA/DPO pour CPU ou GPU gravent le savoir dans les poids du modèle. Les réponses approuvées alimentent une boucle d'apprentissage continu qui ré-entraîne automatiquement.
6. **📦 Export & mesure** — Un fichier GGUF en sortie, déployable partout (llama.cpp, Ollama, LM Studio…). Évaluation intégrée, comparaison A/B et benchmarks d'ablation prouvent le gain à chaque étape.

Le tout piloté par une interface web guidée (FR/EN) — tableau de bord, ingestion, gestion documentaire, fine-tuning, playground, évaluation — avec détection automatique du matériel.

## 📚 Documentation

| Guide | Contenu |
|---|---|
| **[Getting Started](docs/GETTING_STARTED.md)** | Installation pas-à-pas, téléchargement des modèles, profils Docker, déploiement Kubernetes/GKE |
| **[Architecture & Services](docs/ARCHITECTURE.md)** | Chaque conteneur et service en détail : internals RAG, ingestion, GED, évaluation, stack technique |
| **[Configuration](docs/CONFIGURATION.md)** | Toutes les variables d'environnement, endpoints de santé, métriques Prometheus |
| **[Manuel utilisateur](USER_MANUAL.md)** | Parcours complet de l'interface web |
| **[Doc technique](TECHNICAL_DOC.md)** | Référence au niveau implémentation |
| **[Mini-livre pédagogique (FR)](DOCUMENTATION_PEDAGOGIQUE.fr.md)** | Les idées derrière Spectra : embeddings, HNSW, BM25 + RRF, les six stratégies RAG, DPO/QLoRA — chacune avec un exemple concret |
| **[Guide llama.cpp](llama.cpp.md)** | Détails et réglages du moteur d'inférence |
| **[Fiabilité](RELIABILITY.md)** · **[Sécurité](SECURITY.md)** | Garanties opérationnelles et politique de sécurité |

**Stack :** Java 25 / Spring Boot 4 · React 19 · llama.cpp · ChromaDB · Python (fine-tuning, parsing, re-ranking) — détaillée dans [Architecture](docs/ARCHITECTURE.md#technology-stack).

## 🤝 Contribuer

Issues et pull requests bienvenues — voir [CONTRIBUTING.md](CONTRIBUTING.md). Si Spectra vous est utile, une ⭐ aide les autres à le découvrir.

## 📄 Licence

**GNU AGPL-3.0** — utilisez, modifiez et auto-hébergez librement, en production, sur site ou hors ligne. L'AGPL est un copyleft fort : si vous exploitez une version modifiée comme service réseau, vous devez rendre les sources correspondantes accessibles à ses utilisateurs. Texte complet dans [LICENSE](LICENSE).

---

<div align="center">

*De vos documents bruts à l'expertise métier — le tout sur votre matériel.*

</div>
