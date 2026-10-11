# Cohérence des écrans de travail

Chaque écran utilise la même largeur de contenu, portée par le layout. Les sections
principales sont séparées par le même espacement. L’en-tête commun place le titre
et sa description à gauche, le contexte et les actions à droite ; les éléments
se replient sur plusieurs lignes sur mobile. Le Playground dispose aussi de cet
en-tête : « Poser une question » place le focus dans la saisie.

Les actions principales restent visibles. Les actions secondaires, comme
l’actualisation, l’export ou les modes de comparaison, sont accessibles dans
« Autres actions ». Le menu s’utilise avec les flèches, Début/Fin, Entrée et
Échap ; Tab le ferme et poursuit la navigation. Les actions d’une réponse du
Playground sont visibles sur mobile et au clavier, sans nécessiter de survol.
Les confirmations de suppression et d’effacement restent en place.

Un badge utilise le même vocabulaire et la même couleur sur toutes les surfaces :
attente neutre, activité en cours en accent secondaire, réussite en vert, succès
partiel en avertissement, échec en rouge et annulation neutre. Les états de document
restent distincts des états de tâche. Les phases détaillées d’entraînement restent
lisibles sous l’état synthétique ; un état backend inconnu reste visible et neutre.

« Configuré » indique le modèle demandé dans la configuration. « Chargé » nécessite
une confirmation positive du service ainsi que le nom du modèle chargé. Les deux
noms peuvent différer pendant une bascule. « Chargement », « Indisponible » et
« Non vérifié » ne sont pas présentés comme un modèle prêt. Le bandeau de contexte
et le Model Hub affichent ces deux informations séparément, actualisées périodiquement.

Pour les contributeurs : `PageHeader` expose `context`, `primaryAction` et
`secondaryActions`. `ActionMenu` conserve le même comportement clavier partout.
`StatusBadge` utilise les définitions de `lib/uiStatus.ts` pour les domaines
`task`, `document` et `model` ; les composants ne doivent pas réinventer leur palette.

## Navigation contextuelle et panneaux de détail

Activité ouvre le rapport ou le job identifié par la tâche, et conserve les filtres
pour le retour. Les liens d’évaluation (`?eval=`), A/B (`?ab=`), d’entraînement
et d’ablation (`?job=`), ainsi que les autres tâches (`?task=type:identifiant`),
restent rechargeables. Une cible indisponible est signalée ; aucun autre résultat
n’est choisi à sa place. Un entraînement évalué ouvre son rapport d’évaluation précis.

Une source du Playground ouvre le document dont le nom et la collection correspondent
exactement. La recherche parcourt ses pages et refuse une correspondance ambiguë,
au lieu d’ouvrir arbitrairement le premier résultat. Les anciennes réponses sans
collection ne permettent l’ouverture que si le nom désigne un document unique.
« Retour vers Playground » restaure la conversation conservée localement et replace
le focus sur la source consultée. Le retour contextuel utilise l’historique du navigateur ;
un lien ouvert directement ne prétend pas avoir un écran d’origine.

Documents, traces RAG et rapports d’évaluation ou d’ablation partagent `DetailPanel` : Résumé,
Détails, Historique et Actions. La chronologie d’exécution sert d’historique à une
trace ; les rapports affichent leurs dates ; les documents conservent leur audit.
L’aperçu, la qualification, les annotations, les scores et les actions restent accessibles.
Les boutons de section acceptent les flèches gauche/droite et Début/Fin. Leur activation
déplace le focus et fait défiler la section sans modifier l’URL du document.

Le panneau occupe la hauteur disponible, avec une seule surface de défilement principale
et la même disposition sur mobile. Le bouton de fermeture, Échap et le clic sur le fond
ferment le panneau ; Tab reste dans le dialogue et le focus revient à l’élément d’ouverture.
Une confirmation de suppression se ferme avant le panneau situé dessous.
