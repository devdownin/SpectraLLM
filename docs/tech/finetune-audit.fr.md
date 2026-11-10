# Étape 5 — audit du fine-tuning : go/no-go par version

Compare le modèle de base et chaque version fine-tunée (QLoRA, DPO…) sur le **même** benchmark qualité
tenu à l'écart, détecte les régressions — en particulier les hallucinations sur les questions **hors
corpus** — et vérifie que la quantification GGUF ne change pas le comportement. Livrable : `decision.md`,
une ligne GO ou NO-GO par version, avec ses motifs.

## Ce qui est mesuré

Le benchmark qualité de Spectra (`POST /api/quality-benchmark`, fichier `spectra.benchmark.quality-file`
ou le benchmark relu depuis le corpus) pose chaque question **sans RAG** au modèle évalué :

- questions **answerable** : score d'exactitude 1-10 face à la réponse de référence ;
- questions **hors corpus** (`answerable: false`) : le modèle doit s'abstenir ; `hallucinated` signale
  une réponse inventée.

La génération évaluée est désormais **gloutonne** (température 0, `top_p` 1) : elle suivait le défaut de
chat (0,7), si bien que deux passages d'un même modèle différaient, et un écart entre versions — ou entre
f16 et q8_0 — pouvait n'être qu'un tirage. Les notes viennent du juge configuré
(`spectra.evaluation.judge-model`). Un rapport **auto-jugé** (juge = modèle évalué) rend la décision
invalide : chaque version se noterait elle-même.

## Préparer les modèles

Pour chaque version, exporter deux GGUF depuis le même adaptateur et les enregistrer sous deux noms :

```bash
python3 scripts/export_gguf.py --adapter data/fine-tuning/qlora/adapter --output data/fine-tuning/qlora/merged \
  --base-model phi3                      # model.gguf, q8_0 : le format servi
python3 scripts/export_gguf.py --adapter data/fine-tuning/qlora/adapter --output data/fine-tuning/qlora/merged \
  --base-model phi3 --outtype f16        # model-f16.gguf : référence non quantifiée
# Enregistrer les deux (POST /api/fine-tuning/models/register), par exemple qlora-q8 et qlora-f16.
```

La base est le modèle de base du catalogue (`phi3` ici, celui de l'adaptateur), servi en GGUF q8_0 (même quantification que les versions) sans
fine-tuning et enregistré sous un nom propre (`phi3-base`). La référence f16 est une simple conversion des poids fusionnés (FP32 → f16, sans perte notable) : c'est
le « avant quantification » du contrôle de parité. Elle demande deux fois plus de mémoire que la q8_0.

## Exécution

```bash
OUT=data/finetune-audit-$(date +%F)
for pair in base=phi3-base qlora=qlora-q8 qlora-f16=qlora-f16 dpo=dpo-q8 dpo-f16=dpo-f16; do
  python3 scripts/finetune_audit.py collect --out "$OUT" --label "${pair%%=*}" --model "${pair#*=}"
done
python3 scripts/finetune_audit.py decide --out "$OUT" --base base \
  --candidate qlora --candidate dpo --parity qlora=qlora-f16 --parity dpo=dpo-f16
```

`collect` fige chaque rapport (`<label>/report.json`) et ne relance pas une étiquette déjà collectée.
Chaque passage bascule le modèle actif puis le restaure : réserver la pile à l'audit.

## Règles de décision

Une version est **GO** si aucun motif ne s'applique ; les seuils sont des options de `decide` et sont
consignés dans `decision.json`.

| Motif de NO-GO | Défaut |
|---|---|
| Rapports invalides : benchmarks différents, juges différents, ou auto-jugement | — |
| Items sans verdict du juge | > 10 % |
| Nouvelles hallucinations hors corpus (refusées par la base, inventées par la version) | > 0 |
| Score non garanti : borne basse de l'IC95 % de Δ score | < −0,5 point |
| Aucun gain : Δ score moyen | ≤ 0 |
| Régression d'une catégorie (au moins 3 items) | Δ < −1 point |
| Quantification : \|Δ score\| q8_0 vs f16 | > 0,5 point |
| Quantification : accord de refus hors corpus q8_0 vs f16 | < 95 % |
| Parité GGUF non vérifiée (pas de `--parity`) | — (`--no-parity-required` pour l'ignorer) |

`decision.json` liste aussi, par version, les hallucinations corrigées, les items dont le score baisse de
3 points ou plus, et pour la parité l'égalité exacte et la similarité moyenne des réponses q8_0/f16.

Limites : le benchmark est petit (quelques dizaines de questions) et l'IC95 % large, d'où une règle de
non-infériorité plutôt qu'une exigence de gain significatif ; il ne mesure pas la qualité en mode RAG
(voir les étapes 3 et 4, rejouables avec la version retenue comme modèle de chat).

## Résultats

> **Statut : à mesurer.** Aucun modèle n'a été évalué (pas de pile ni de GPU dans l'environnement de
> préparation). Coller ici `decision.md`.

| Version | Δ score [IC95 %] | Hallucination base → version | Nouvelles halluc. | Parité GGUF | Décision |
|---|---|---|---|---|---|
| — | — | — | — | — | — |
