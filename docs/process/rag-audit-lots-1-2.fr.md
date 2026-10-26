# Corrections de l’audit RAG — lots 1 et 2

Périmètre : intégrité des filtres, cycle de vie du corpus et disponibilité. Les corrections de citations, du nettoyage PDF et des métriques d’ablation restent dans le lot 3.

## Comportements corrigés

- Les résultats vectoriels sont contrôlés par empreinte documentaire après le préfiltre Chroma compatible avec les anciennes métadonnées. Un homonyme exclu ne peut pas entrer dans le contexte ; la recherche hybride et la recherche agentique utilisent la même règle. La sur-extraction est bornée à 4096 candidats, ce qui peut rendre moins de passages sur un corpus extrême.
- Kafka indexe une nouvelle génération dans Chroma et BM25 avant de publier son pointeur SQL. Les générations préparées et obsolètes sont masquées au retrieval. Une suppression persiste son intention avant la purge ; les erreurs remontent au listener et empêchent son acquittement. La colonne `versioned_publication`, ajoutée par `schema.sql`, maintient la compatibilité des données historiques jusqu’à leur première publication versionnée.
- BM25 reconstruit un snapshot depuis Chroma puis rejoue les seules mutations survenues pendant sa construction. Une suppression pendant un parcours multipage invalide le snapshot pour éviter un décalage d’offset ; une réconciliation ultérieure reprend le travail. Le journal est borné et l’index en service est conservé si cette borne est dépassée.
- La fraîcheur des snapshots et la réconciliation comparent une empreinte du contenu indexé : identifiant, texte, source, identité documentaire et termes d’en-tête. Les remplacements à nombre de passages constant et les changements de texte sous le même identifiant sont détectés. Le calcul est paginé, indépendant de l’ordre des résultats et utilise une mémoire constante pour l’empreinte. Il nécessite un scan du corpus ; ce contrôle ne constitue pas un snapshot transactionnel du serveur pendant des écritures externes.
- `neighborWindow` est limité à `0..5` dans l’API et plafonné dans le service. Les calculs d’indices utilisent une arithmétique évitant les dépassements d’entier.
- Le voisinage d’une archive ZIP inclut `zipEntry`. Les archives historiques sans chemin d’entrée fiable ne sont pas élargies. L’empreinte racine reste disponible pour la suppression de l’archive entière.

## Ré-indexation et reprise

La migration en place commence par attendre les pipelines de lecture/ingestion déjà admis, puis écrit un marqueur durable dans `${spectra.chromadb.maintenance-dir:./data/collection-maintenance}`. Les nouvelles lectures et écritures de la collection sont refusées jusqu’à la réussite complète. Cela couvre les migrations de préfixes ou d’en-têtes sans changement du nom du modèle.

Les appels de maintenance lisent les pages et réécrivent les vecteurs ; le marqueur n’est retiré qu’après vérification du nombre de passages traités et ré-estampillage final. Une erreur ou un redémarrage laisse la collection bloquée. Pour reprendre, relancer la ré-indexation complète via `POST /api/config/embedding-consistency/reindex` avec le même nom de collection. Ne pas effacer les marqueurs pour forcer la remise en service d’un index partiellement migré.

Le répertoire des marqueurs doit résider sur le volume persistant de l’application. Les verrous de pipelines et d’écrivains Kafka sont locaux à une JVM : un déploiement à plusieurs instances nécessite une coordination distribuée avant d’offrir les mêmes garanties. Les lecteurs auxiliaires d’une recherche hybride peuvent terminer pendant qu’une migration attend ; sous trafic continu, l’admission des lecteurs peut retarder le début de la migration.

Une migration entre modèles de dimensions différentes doit être qualifiée séparément : Chroma peut refuser une réécriture en place avec une dimension différente. L’échec laisse le marqueur et ne remet pas en service l’index partiellement traité.

## Validation

Des tests JUnit couvrent les homonymes, les suppressions et publications Kafka, les mutations pendant reconstruction, les snapshots à cardinalité constante, le blocage persistant après redémarrage, les bornes API et l’isolation des entrées ZIP.

Les harnais locaux compilent les classes ciblées avec des doubles aux frontières externes. Ils vérifient les invariants de filtrage, de reconstruction, de visibilité, de fraîcheur et de blocage/concurrence. Ils ne remplacent pas les tests Spring/PostgreSQL/Chroma/Kafka. Exécuter `mvn -B package -f backend/pom.xml` avec Java 25, puis la pile E2E du dépôt. Les résultats de la CI attachés à la PR font foi pour le commit considéré.
