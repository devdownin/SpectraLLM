# Scan des dépendances et base NVD

Les PR analysent les dépendances avec une base NVD déjà alimentée. Elles ne reçoivent
pas la clé NVD et ne publient aucun cache. `depcheck` reste le job de scan ; les erreurs
du scanner ne sont pas ignorées et le rapport HTML est conservé même en cas d'échec.

## Alimentation de la base

Sur la branche par défaut, le workflow `Dependency Check` met la base à jour avant le
scan. Il s'exécute chaque jour à 03:00 UTC, après un push et sur déclenchement manuel.
Le job `update-nvd` utilise le secret `NVD_API_KEY` et le goal `update-only`, puis publie
un cache uniquement si la commande réussit et que la base existe. Une mise à jour partielle
ou interrompue ne remplace jamais le cache complet précédent.

La clé inclut la version du plugin OWASP lue dans le pom et l'identifiant de l'exécution.
Les nouvelles clés évitent de conserver indéfiniment une base ancienne dans un cache GitHub
immuable. Les mises à jour sont sérialisées et bornées à 210 minutes ; ce budget plus long
couvre l'alimentation initiale, sans mobiliser un runner pour chaque PR.

Cette organisation suit le modèle [un écrivain, plusieurs lecteurs recommandé par OWASP](https://dependency-check.github.io/DependencyCheck/data/cacheh2.html).
Elle évite aussi que plusieurs scans partagent simultanément la même clé NVD et son quota.
Le délai de 210 minutes reste une borne, pas une garantie de disponibilité du service NVD.

## Premier démarrage et incidents

Le nouveau cache doit être initialisé une fois sur la branche par défaut après intégration
du workflow. Depuis Actions, déclencher `Dependency Check` sur cette branche si le push
n'a pas déjà lancé le job, puis attendre la réussite de `update-nvd` et de `depcheck`.
Avant cette première alimentation, le scan des PR échoue explicitement avec « Base NVD
absente ou vide ». C'est aussi le comportement attendu après éviction du cache GitHub.

Une base vieille de plus de 48 heures, une date incohérente, une version de plugin différente
ou l'absence du marqueur de mise à jour complète provoquent un échec avant l'analyse.
La base datant de moins de 48 heures permet aux PR de continuer pendant un incident NVD
bref ; au-delà de cette limite, une erreur ne vaut pas un certificat d'absence de CVE.

En cas d'échec : consulter le job `update-nvd`, vérifier le secret `NVD_API_KEY`, relancer
le workflow sur la branche par défaut, puis relancer les checks des PR concernées. Ne pas
désactiver `autoUpdate=false` côté PR ni créer manuellement le marqueur de fraîcheur.

Les limites et suppressions de vulnérabilités du profil Maven `dep-check` sont conservées.
La fraîcheur du cache ne remplace pas le résultat du scanner : seule sa réussite confirme
que l'analyse a pu se terminer selon cette configuration.
