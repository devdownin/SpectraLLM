<!-- short: Spectra LLM web interface — React UI for the local RAG and fine-tuning stack -->
<!--
  Page de présentation Docker Hub de compagnonsdudev/spectrallm-frontend.
  Poussée par .github/workflows/docker-publish.yml (job « describe »).
  Éditez CE fichier, jamais la description dans l'interface Docker Hub.

  Volontairement plus courte que celle de l'image principale : c'est un composant, pas
  un produit. Elle dit ce qu'il est, ce qu'il exige, et renvoie à la page qui présente
  le projet — plutôt que d'en dupliquer le contenu, qui divergerait.
-->

<img src="https://raw.githubusercontent.com/devdownin/SpectraLLM/main/docs/assets/logo.png" alt="Spectra LLM" width="280">

### The web interface of Spectra LLM — React 19, served by Nginx.

[GitHub](https://github.com/devdownin/SpectraLLM) · [**compagnonsdudev/spectrallm** (the backend, and the project's main page)](https://hub.docker.com/r/compagnonsdudev/spectrallm) · AGPL-3.0

![Spectra dashboard](https://raw.githubusercontent.com/devdownin/SpectraLLM/main/docs/assets/dashboard.png)

---

## What this image is

The guided FR/EN interface for the whole journey — ingestion, playground with cited sources, fine-tuning, evaluation. Static assets built with Vite and served by Nginx on **port 80**, which also reverse-proxies `/api` to the backend.

**This image does not run alone.** It needs `compagnonsdudev/spectrallm` (the backend), a ChromaDB vector store and two llama.cpp servers. Everything is wired by the project's Compose file:

```bash
git clone https://github.com/devdownin/SpectraLLM.git && cd SpectraLLM
./scripts/setup.sh --download-embed --download-chat

docker compose --project-directory . \
  -f deploy/docker/docker-compose.yml -f deploy/docker/docker-compose.hub.yml pull

docker compose --project-directory . \
  -f deploy/docker/docker-compose.yml -f deploy/docker/docker-compose.hub.yml \
  up -d --wait --no-build
```

Then open **http://localhost**. The full walkthrough lives on the [backend image's page](https://hub.docker.com/r/compagnonsdudev/spectrallm).

---

## Tags

| Tag | Meaning |
|---|---|
| `latest` | The most recent release. |
| `0.7` | The 0.7 series. |
| `0.7.1` | An exact version. **Pin this in production.** |

**Architectures:** `linux/amd64` and `linux/arm64`.

Keep this image and `compagnonsdudev/spectrallm` on the **same tag**: they are built from the same commit by the same workflow, and are only ever tested together.

---

## Documentation

- [Getting Started](https://github.com/devdownin/SpectraLLM/blob/main/docs/getting-started.en.md)
- [User Manual](https://github.com/devdownin/SpectraLLM/blob/main/docs/user/user-manual.en.md) · [FR](https://github.com/devdownin/SpectraLLM/blob/main/docs/user/user-manual.fr.md)
- [Publier / consommer les images (FR)](https://github.com/devdownin/SpectraLLM/blob/main/docs/tech/docker-hub.fr.md)

**License:** GNU AGPL-3.0 — [full text](https://github.com/devdownin/SpectraLLM/blob/main/LICENSE).
