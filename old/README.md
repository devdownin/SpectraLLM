# old/ — fichiers archivés

Ce répertoire regroupe des fichiers **non nécessaires au build ni à l'exécution**
du projet, conservés ici pour référence (historique Git préservé via `git mv`).

| Fichier | Raison de l'archivage |
|---|---|
| `Attente`, `Image` | Fichiers vides (0 octet), sans usage. |
| `backend.log` | Log d'exécution accidentellement versionné (désormais ignoré via `.gitignore`). |
| `root-package-lock.json` | `package-lock.json` racine vide/factice — le vrai lockfile npm est `frontend/package-lock.json`. |
| `application.yml.root-stale` | Copie obsolète à la racine ; la configuration effective est `src/main/resources/application.yml`. Le Dockerfile ne la copie pas. |
| `CHECKPOINT.md`, `IMPROVEMENTS.md` | Notes de travail internes (suivi d'avancement / journal d'audit), non destinées aux utilisateurs. |
| `MARKET_ANALYSIS.md` | Document d'analyse de marché (hors code). |
| `stitch_spectra_llm_playground/` | Maquettes de design (Google Stitch) remplacées par le frontend React (`frontend/`). |

Ces fichiers peuvent être supprimés définitivement si l'historique Git suffit.
