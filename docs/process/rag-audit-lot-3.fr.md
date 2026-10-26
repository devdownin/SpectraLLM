# Corrections RAG — lot 3

Ce lot complète les [lots 1 et 2](rag-audit-lots-1-2.fr.md).

- Les réponses agentiques sont vérifiées contre les passages complets effectivement sélectionnés pour l’appel final, avec leur numérotation. Le rapport de citations est renvoyé en JSON et dans l’événement SSE `done`. La réponse agentique étant calculée avant émission, le mode strict s’applique avant sa diffusion. Le streaming standard conserve son contrôle après émission, tel que documenté précédemment.
- Le nettoyage PDF conserve les lignes numériques isolées par défaut. L’ancienne heuristique reste disponible avec `DOCPARSER_STRIP_PAGE_NUMBERS=true`. Un numéro de page explicite tel que `Page 3 / 47` reste supprimé. Les PDF dont les chiffres ont déjà été retirés doivent être ré-ingérés pour restaurer ces faits dans le corpus.
- `QueryResponse.contextChars` décrit la somme des longueurs des passages complets servis après sélection et adaptation du contexte. Ce champ ne comprend ni instructions, ni question, ni habillage des passages. Le mode direct expose zéro ; une réponse provenant d’un ancien producteur peut laisser la mesure inconnue (`null`). Les rapports d’ablation utilisent cette mesure et non les aperçus tronqués de `Source.text`. Une moyenne dont une mesure manque reste inconnue, et l’interface affiche cette absence.

Les tokens du contexte restent une estimation basée sur les caractères, pas une mesure du tokenizer du modèle ni de la consommation totale du prompt. Le score de citations du cross-encoder mesure la pertinence ; il ne prouve pas une implication logique.

Validation : tests de régression pour les citations agentiques (dont numéro invalide), les rapports SSE, les faits numériques et l’activation explicite de l’heuristique, les contextes de même préfixe mais de longueurs différentes, les copies de réponse et les valeurs inconnues. La CI de la PR fournit la validation complète du commit considéré.
