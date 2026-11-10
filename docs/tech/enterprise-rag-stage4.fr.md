# Étape 4 — audit de la génération, Claude Opus 5.5 comme juge

Mesure, par catégorie de question, l'exactitude et la complétude des réponses, leur fidélité aux sources,
la qualité de leurs citations et le refus des questions sans réponse. Le juge est **Claude Opus 5.5**
(`claude-opus-5-5`), distinct du modèle évalué (Qwen2.5-7B dans la [baseline v1](enterprise-rag-baseline.fr.md)) ;
les lanceurs refusent un juge dont le nom figure dans le modèle évalué du profil.

## Ce qui est noté, et par quel prompt

| Mesure | Définition | Prompt (publié par Onyx) | Outil |
|---|---|---|---|
| Exactitude | réponse alignée sur la réponse de référence | `ANSWER_WHOLISTIC_EVALUATION_PROMPT` | évaluateur Onyx (`judge`) |
| Complétude | part des faits de référence présents | `INDIVIDUAL_FACT_VALIDATOR_PROMPT` | évaluateur Onyx |
| Score | complétude si correcte, 0 sinon (classement) | — | évaluateur Onyx |
| Docs invalides | documents cités hors référence (sans correction du gold) | — | évaluateur Onyx |
| **Fidélité** | part des affirmations étayées par les documents récupérés | `INDIVIDUAL_FACT_VALIDATOR_PROMPT` (preuve = documents, énoncé = affirmation) | `enterprise_rag_generation_audit.py` |
| **Précision des citations** | part des affirmations citées `[n]` étayées par les documents cités | idem (preuve = documents cités) | idem |
| Couverture des citations | part des affirmations portant au moins une citation valide | — | idem |
| **Refus correct** | `info_not_found` : la réponse déclare l'information indisponible | idem (énoncé fixe de refus, preuve = réponse) | idem |
| **Refus abusif** | mêmes refus sur les questions qui ont une réponse | idem | idem |

Pour `info_not_found`, la réponse de référence d'Onyx exige déjà de dire que la question n'a pas de réponse :
l'exactitude d'Onyx et le « refus correct » se recoupent, le second isolant le seul refus.

Les affirmations sont les phrases d'au moins 4 mots, citations retirées, au plus 12 par réponse
(`claims_skipped` compte le reste). La preuve est le **texte intégral** des documents (`$WORK/documents`),
sans troncature : un passage cité est vérifié contre son document entier, ce qui peut accepter une affirmation
étayée ailleurs dans le document que dans le chunk montré au modèle. Le prompt d'Onyx est lu dans le clone figé
de l'évaluateur, et son empreinte est consignée dans `grounding.json`.

## Juge

- **Évaluateur Onyx** : `LLM_PROVIDER=anthropic` est pris en charge nativement (SDK `anthropic`, réponses en
  flux). Avec `claude-opus-5-5`, l'évaluateur n'envoie pas de paramètre `thinking` : le modèle raisonne en mode
  adaptatif à l'effort par défaut (`medium`). Le lanceur sonde le juge avec le SDK de l'évaluateur avant de
  commencer et consigne `provider` dans `judge.json`.
- **Audit de génération** : SDK officiel `anthropic`, `effort` réglable (`medium` par défaut), preuve mise en
  cache entre les affirmations d'une même réponse (`cache_control`).
- **Refus du juge** (`stop_reason: "refusal"`) : traité comme une panne, la question reste à noter. Aucun
  repli vers un autre modèle n'est activé : il changerait le juge en cours de mesure. Côté évaluateur Onyx, un
  refus pendant la validation d'un fait compte comme « no » sans avertissement ; vérifier le nombre de refus
  dans la console Anthropic si la complétude chute anormalement.

## Exécution

Sur un run `answer` déjà produit (par exemple `baseline-answer` de l'étape 1). Un run déjà jugé par un autre
modèle garde son `judge.json` : copier ses réponses dans un nouveau répertoire pour le juge Claude.

```bash
RUN=$WORK/baseline-answer
OPUS=$WORK/baseline-answer-opus
mkdir -p "$OPUS" && cp "$RUN"/{run.json,questions.jsonl,records.jsonl} "$OPUS"/

export LLM_PROVIDER=anthropic LLM_MODEL_NAME=claude-opus-5-5 LLM_API_KEY=…   # clé Anthropic
PY=../EnterpriseRAG-Bench/.venv/bin/python   # contient le SDK anthropic (requirements d'Onyx)

python3 scripts/enterprise_rag_experiment.py judge --out "$OPUS" \
  --evaluator-dir ../EnterpriseRAG-Bench --python "$PY"
"$PY" scripts/enterprise_rag_generation_audit.py ground --out "$OPUS" \
  --corpus-dir "$WORK/documents" --evaluator-dir ../EnterpriseRAG-Bench
"$PY" scripts/enterprise_rag_generation_audit.py report --out "$OPUS"
```

`ground` est reprenable (`grounding.jsonl`). `report` écrit `generation-audit.json` et
`generation-audit.md` : une ligne par catégorie (basic, semantic, intra_document_reasoning, project_related,
constrained, conflicting_info, completeness, miscellaneous, info_not_found, high_level) et une ligne d'ensemble.
Comparer deux variantes avec `enterprise_rag_experiment.py compare` (exactitude, complétude, score) ; les
mesures de fidélité se comparent catégorie par catégorie dans leurs rapports.

## Coût

Par réponse : 1 à 2 appels par affirmation (fidélité, et précision si elle cite) et 1 appel de refus, soit
jusqu'à 25 appels (12 affirmations × 2, plus le refus), en plus des appels de l'évaluateur Onyx (un par fait de référence et un
d'exactitude). La preuve, mise en cache, est relue au tarif réduit d'une affirmation à l'autre. Mesurer le coût
réel sur une dizaine de questions (`--parallelism 1`, puis lire l'usage dans la console) avant de lancer les 120.

## Résultats

> **Statut : à mesurer.** Aucun appel au juge n'a été fait (pas de Docker, de modèle local ni de clé Anthropic
> dans l'environnement de préparation). Coller ici `generation-audit.md`.

| Catégorie | n | Exactitude % | Complétude % | Score | Fidélité % | Précision citations % | Refus correct % | Refus abusif % |
|---|---|---|---|---|---|---|---|---|
| — | — | — | — | — | — | — | — | — |
