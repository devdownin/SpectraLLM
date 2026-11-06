# Sources de données synchronisées

La page **Données → Connecteurs** (`/sources`) alimente le RAG depuis un dossier du serveur, un bucket S3/MinIO ou un site web. L'ingestion ponctuelle de fichiers et d'URL reste disponible dans **Ingestion**.

## Connecter une source

1. Choisir **Dossier local**, **S3 / MinIO** ou **Site web / sitemap**.
2. Nommer la source, choisir la collection cible et son périmètre. Une collection doit avoir 3 à 63 caractères alphanumériques, tirets ou underscores.
3. Choisir la fréquence (manuelle, ou au moins 5 minutes), les extensions, les exclusions et les limites.
4. Tester la connexion, vérifier le récapitulatif puis enregistrer. Le premier lancement manuel se fait avec **Synchroniser** ; une source planifiée démarrera aussi à sa prochaine échéance.

La fiche d'une source affiche les documents, leur dernière indexation, les erreurs et les vingt dernières exécutions. Une citation `source://…` du Playground ouvre cette fiche. Le nom d'une source et sa configuration sont modifiables ; changer son type ou sa collection demande une nouvelle instance. Annuler une exécution avant de modifier, suspendre ou supprimer sa source.

## Dossier local

Le chemin est celui du **serveur Spectra**, pas celui du navigateur ou du téléphone. En conteneur, monter les documents en lecture seule. L'administrateur autorise les racines via `SPECTRA_SOURCES_LOCAL_ROOTS`, séparées par des virgules (défaut `/app/sources`). Les chemins réels sont vérifiés et les liens symboliques ne sont pas parcourus.

Exemple depuis la racine du dépôt, avec le projet construit localement :

```bash
mkdir -p data/sources
docker compose --project-directory . -f deploy/docker/docker-compose.yml \
  -f deploy/docker/docker-compose.sources.yml up -d --build
```

Déposer les fichiers dans `data/sources`, puis configurer `/app/sources` dans l'assistant. `SPECTRA_SOURCES_HOST_DIR` permet de choisir un autre dossier hôte. Sous Docker Desktop, autoriser le partage de ce dossier avec Docker. Les droits du dossier doivent permettre sa lecture par l'utilisateur du conteneur.

La profondeur 0 lit les fichiers directement dans le dossier ; 2 inclut deux niveaux de sous-dossiers. Un déplacement change l'identifiant relatif : le nouveau document est indexé et l'ancien est retiré après une découverte complète.

## S3 compatible et MinIO

Renseigner le bucket, le préfixe facultatif et la région (`us-east-1` par défaut). L'endpoint vide utilise AWS ; un endpoint MinIO comme `http://minio:9000` utilise l'adressage par chemin. Il doit contenir seulement l'origine, sans chemin ni paramètres.

Les champs d'authentification contiennent les **noms** des variables du processus serveur, jamais leurs valeurs. L'overlay Docker transmet `SPECTRA_S3_ACCESS_KEY`, `SPECTRA_S3_SECRET_KEY` et le jeton de session facultatif `SPECTRA_S3_SESSION_TOKEN`. Utiliser ces noms dans le formulaire. Laisser les deux références vides utilise la chaîne de credentials AWS standard. D'autres noms sont possibles si les variables sont injectées dans le conteneur.

Le compte doit pouvoir lister le préfixe et lire les objets. Le test vérifie le listing ; les lectures et leurs éventuelles erreurs sont contrôlées pendant la synchronisation. Les listes sont paginées ; les fichiers sont lus avec une limite de taille. Une condition ETag protège contre un objet modifié entre le listing et son téléchargement.

Pour un endpoint sur réseau privé, l'administrateur doit explicitement autoriser son **nom d'hôte exact**, par exemple `SPECTRA_SOURCES_ALLOWED_HOSTS=minio`. Plusieurs noms sont séparés par des virgules. Cette autorisation permet l'accès réseau à cet hôte ; ne pas y inscrire de domaines non maîtrisés.

## Site web et sitemap

Renseigner une URL de départ et, si disponible, l'URL d'un sitemap XML du même site. Le crawl reste sur la même origine et sous le chemin de départ : `/docs` inclut `/docs/guide`, mais pas `/admin` ni `/docs-autres`. Pour découvrir tout un site, partir de sa racine.

Les liens HTML et les index de sitemaps sont suivis dans les limites configurées. Le connecteur respecte les règles `Allow`/`Disallow` de `robots.txt` pour `SpectraBot`, ainsi que les directives HTML `noindex` et `nofollow`. Un `robots.txt` indisponible par erreur réseau, refus ou erreur serveur diffère le crawl. Les redirections ne sont pas suivies : utiliser l'URL finale. Les sites nécessitant une connexion ou un rendu JavaScript complet ne sont pas pris en charge par ce connecteur ; l'ingestion ponctuelle d'URL avec Browserless reste distincte.

Les hôtes privés sont bloqués sauf autorisation explicite via `SPECTRA_SOURCES_ALLOWED_HOSTS`. Le contrôle réseau est appliqué aussi à l'adresse utilisée pour établir la connexion. Aucun cookie ni identifiant de session du navigateur n'est transmis.

## Garanties de synchronisation

| Situation | Comportement |
|---|---|
| Document inchangé | Le checkpoint évite de recalculer ses embeddings. Le contenu est relu pour vérifier son empreinte. |
| Contenu ou provenance modifiés | Une nouvelle version est préparée dans les index vectoriel et lexical, puis publiée ; la version précédente est ensuite retirée. |
| Redémarrage | L'exécution active devient « Interrompue ». Les checkpoints des documents terminés persistent ; relancer reprend par une nouvelle découverte. |
| Échec d'un document | Son ancienne version publiée reste disponible ; la fiche signale l'échec. |
| Découverte incomplète, limite atteinte ou erreur | Résultat partiel/échoué. L'absence d'un document dans cette exécution ne déclenche aucune suppression par réconciliation. |
| Découverte complète et sans erreur | Les documents disparus ou exclus du périmètre sont retirés des deux index. Un listing vide réussi peut donc vider la source. |
| HTTP 404/410 sur un document web connu | Suppression confirmée de ce document. Un 403, 429 ou 5xx n'est jamais traité comme une suppression. |
| Suspension | Les documents restent consultables, les prochaines synchronisations sont désactivées. |
| Suppression d'une source | Après confirmation, suppression de ses documents indexés, de son historique et de sa configuration. Les fichiers originaux ne sont jamais supprimés. |

Limites par défaut : 1 000 documents, 25 Mio par fichier, profondeur 2 ; plafonds : 10 000 documents, 50 Mio, profondeur 8. Les formats documentaires pris en charge sont PDF, DOC/DOCX, HTML, Markdown, TXT, CSV, JSON, XML et Avro. Les archives ZIP relèvent de l'ingestion ponctuelle. Exclusions : motifs glob relatifs, par exemple `**/archive/**` ou `*.tmp`.

Les checkpoints sont **par document** : un redémarrage reliste le périmètre et ignore l'indexation déjà terminée. Ce premier lot ne conserve pas de curseur de listing du fournisseur entre deux exécutions et ne reçoit pas de webhooks. Les changements de provenance déclenchent actuellement une republication avec recalcul des embeddings. L'ordonnanceur utilise au plus deux exécutions concurrentes (`SPECTRA_SOURCES_MAX_CONCURRENT`) dans l'instance serveur ; le stockage H2 du déploiement actuel reste mono-instance.

## Accès au corpus

Les trois connecteurs alimentent le **corpus partagé de l'instance Spectra**. Ils ne synchronisent pas les ACL des fichiers, buckets ou sites par utilisateur. Le compte de collecte et le périmètre choisi doivent correspondre aux lecteurs de cette instance. La clé API existante protège les nouveaux endpoints comme le reste de l'API lorsqu'elle est configurée.

## API

| Endpoint | Usage |
|---|---|
| `GET /api/sources/catalog` | Catalogue des trois types |
| `GET/POST /api/sources` | Lister/créer les instances |
| `GET/PUT/DELETE /api/sources/{id}` | Lire/modifier/supprimer une instance |
| `POST /api/sources/test` | Tester une configuration avant enregistrement |
| `POST /api/sources/{id}/test` | Tester une instance |
| `POST /api/sources/{id}/sync` | Démarrer une exécution, réponse 202 |
| `POST /api/sources/{id}/cancel` | Demander l'annulation |
| `GET /api/sources/{id}/runs` | Vingt dernières exécutions |
| `GET /api/sources/{id}/documents?page=0&size=25` | Documents paginés |
| `GET /api/sources/{id}/documents/{documentId}` | Provenance d'un document |
| `GET /api/sources/{id}/documents/{documentId}/preview` | Aperçu borné de sa version publiée |

Exemple de création :

```json
{
  "name": "Documentation produit",
  "type": "LOCAL_FOLDER",
  "collection": "spectra_documents",
  "enabled": true,
  "intervalMinutes": 60,
  "config": {
    "rootPath": "/app/sources",
    "extensions": ["pdf", "md", "docx"],
    "excludePatterns": ["**/archive/**"],
    "maxDocuments": 1000,
    "maxDepth": 2,
    "maxFileSizeMb": 25
  }
}
```
