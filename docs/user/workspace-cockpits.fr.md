# Contexte et cockpits de travail

Le bandeau du Playground, de Comparaison et d’Optimisation affiche le modèle
réellement chargé, d’après l’état du service. Il peut différer temporairement du
modèle demandé pendant un rechargement. La collection choisie est conservée dans
le navigateur et envoyée avec les prochaines requêtes RAG du Playground. Le
choix vide utilise le défaut du serveur. Une comparaison de module conserve la
collection et le mode RAG de sa réponse de référence.

Les évaluations conservent leur propre jeu de test et les modèles indiqués dans
leurs rapports : sélectionner une collection ne modifie pas leur corpus.
L’action « Ouvrir les évaluations » ne note pas automatiquement la réponse.

La fiche Documents affiche à gauche un échantillon des textes indexés et à droite
les métadonnées, la qualification, les annotations et l’historique. Sur mobile,
les deux panneaux se suivent. L’aperçu contient au plus douze extraits de 2 000
caractères et ne reproduit pas le fichier original. Il utilise l’identité SHA256
du document ; les anciens chunks sans cette identité ne sont pas affichés. Les
comptages de la fiche décrivent la dernière ingestion, pas une vérification
exhaustive de l’index actuel. L’endpoint de lecture est
`GET /api/ged/documents/{sha256}/preview` (`chunks`, `truncated`) ; un document
inconnu donne 404 et une panne d’index reste une erreur, pas un résultat vide.

Activité regroupe les tâches accessibles, avec filtres par état, type et texte.
La progression inconnue est indiquée explicitement. Une ingestion terminée avec
des erreurs par fichier apparaît en succès partiel. Une tâche annulée apparaît
comme annulée. Les liens ouvrent la page de gestion du type de tâche, où sont
consultables les résultats et les actions ; ils ne sélectionnent pas un rapport
précis. En cas de coupure du flux temps réel, les sources REST accessibles sont
interrogées périodiquement ; cette vue ne garantit pas un historique exhaustif.
