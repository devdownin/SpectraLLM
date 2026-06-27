"""
Spectra — Fine-tuning QLoRA sur l'hôte (Windows/Linux sans Docker)
Usage:
    python scripts/train_host.py \
        --dataset data/fine-tuning/export.jsonl \
        --output  data/fine-tuning/adapter \
        --base-model phi3 \
        --epochs 1 \
        --lora-rank 16
"""

import argparse
import sys
import os

# ── Arguments ─────────────────────────────────────────────────
parser = argparse.ArgumentParser(description="Spectra fine-tuning host script")
parser.add_argument("--dataset",        required=True, help="Chemin vers le fichier JSONL")
parser.add_argument("--output",         default="data/fine-tuning/adapter", help="Dossier de sortie")
parser.add_argument("--base-model",     default="phi3",  help="Modèle de base (phi3, mistral, llama3)")
parser.add_argument("--resume-adapter", default=None,    help="Adaptateur LoRA existant à continuer (entraînement incrémental)")
parser.add_argument("--epochs",         type=int,   default=1)
parser.add_argument("--lora-rank",      type=int,   default=16)
parser.add_argument("--lora-alpha",     type=int,   default=32)
parser.add_argument("--lr",             type=float, default=2e-4)
parser.add_argument("--packing",        action="store_true", default=False,
                    help="Active le multipacking : concatène les exemples courts pour remplir max_length tokens")
parser.add_argument("--dpo",            action="store_true", default=False,
                    help="Active l'entraînement DPO : dataset au format {prompt, chosen, rejected}")
parser.add_argument("--val-split",      type=float, default=0.0,
                    help="Fraction du dataset (0..1) tenue à l'écart pour mesurer l'eval_loss "
                         "par époque et détecter le sur-apprentissage (0 = désactivé, SFT uniquement)")
args = parser.parse_args()

# Alias honnêtes : chaque clé pointe vers le modèle réellement chargé.
# "tinyllama" est le défaut léger pour CPU ; "phi3" charge bien Phi-3 (les target_modules
# LoRA sont auto-détectés plus bas, donc l'attention fusionnée qkv_proj de Phi-3 est gérée).
# Ce mapping doit rester IDENTIQUE à celui de export_gguf.py (sinon la fusion LoRA échoue).
MODEL_MAP = {
    "tinyllama":"TinyLlama/TinyLlama-1.1B-Chat-v1.0",   # CPU léger (~1.1B)
    "phi3":     "microsoft/Phi-3-mini-4k-instruct",     # ~3.8B (GPU recommandé)
    "mistral":  "mistralai/Mistral-7B-Instruct-v0.3",
    "llama3":   "meta-llama/Meta-Llama-3-8B-Instruct",
}
hf_model = MODEL_MAP.get(args.base_model, args.base_model)

# ── Détection GPU ──────────────────────────────────────────────
import torch
has_gpu = torch.cuda.is_available()

USE_UNSLOTH = False
if has_gpu:
    try:
        from unsloth import FastLanguageModel
        USE_UNSLOTH = True
        print(f"Backend : Unsloth (GPU {torch.cuda.get_device_name(0)})")
    except Exception:
        print("Backend : HuggingFace PEFT (GPU disponible mais Unsloth non chargé)")
else:
    print("Backend : HuggingFace PEFT (CPU)")

from transformers import AutoTokenizer, AutoModelForCausalLM, TrainingArguments, Trainer, TrainerCallback
import json


def find_target_modules(model):
    """
    Détecte les projections d'attention à cibler par LoRA selon l'architecture réelle.

    Les modules cibles étaient codés en dur en q_proj/k_proj/v_proj/o_proj (style Llama),
    ce qui plantait sur des architectures à attention fusionnée comme Phi-3 (qkv_proj).
    On inspecte les modules présents et on retombe sur le style Llama si rien n'est trouvé.
    """
    candidates = {"q_proj", "k_proj", "v_proj", "o_proj", "qkv_proj"}
    found = set()
    for name, _ in model.named_modules():
        leaf = name.split(".")[-1]
        if leaf in candidates:
            found.add(leaf)
    return sorted(found) if found else ["q_proj", "k_proj", "v_proj", "o_proj"]
import torch
from torch.utils.data import Dataset as TorchDataset

# ── Dataset PyTorch natif (sans lib datasets — incompatible Python 3.14) ──────
class ConversationDataset(TorchDataset):
    """
    SFT au format conversation avec **masquage du prompt** : seuls les tokens de la
    réponse de l'assistant (EOS compris) contribuent à la loss. Les tokens système,
    utilisateur et marqueurs de rôle sont masqués à -100 — sinon le modèle apprend à
    régénérer la question/le prompt au lieu de seulement répondre.
    """

    def __init__(self, path, tokenizer, max_length=512):
        self.samples = []
        with open(path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                record = json.loads(line)
                input_ids, labels = self._encode(record, tokenizer)
                if not input_ids or all(l == -100 for l in labels):
                    # Aucun token supervisé (pas de réponse assistant) → exemple inutile
                    continue

                input_ids, labels = self._fit(input_ids, labels, max_length)
                if all(l == -100 for l in labels):
                    # La réponse a entièrement disparu à la troncature → on saute
                    continue

                # Pas de padding ici : le collator rembourre dynamiquement au plus long
                # du batch (économie majeure sur CPU, surtout avec batch_size=1).
                self.samples.append({
                    "input_ids":      torch.tensor(input_ids, dtype=torch.long),
                    "attention_mask": torch.tensor([1] * len(input_ids), dtype=torch.long),
                    "labels":         torch.tensor(labels,    dtype=torch.long),
                })

    @staticmethod
    def _encode(example, tokenizer):
        """Tokenise tour par tour ; renvoie (input_ids, labels) avec prompt masqué."""
        input_ids, labels = [], []
        for msg in example.get("conversations", []):
            role    = msg.get("role", "")
            content = msg.get("content", "")
            if role == "system":
                seg, supervised = f"<|system|>\n{content}</s>\n", False
            elif role == "user":
                seg, supervised = f"<|user|>\n{content}</s>\n<|assistant|>\n", False
            elif role == "assistant":
                seg, supervised = f"{content}</s>\n", True
            else:
                continue
            ids = tokenizer.encode(seg, add_special_tokens=False)
            input_ids.extend(ids)
            labels.extend(ids if supervised else [-100] * len(ids))
        return input_ids, labels

    @staticmethod
    def _fit(input_ids, labels, max_length):
        """
        Tronque à max_length en **préservant la réponse de l'assistant** : on supprime
        d'abord les tokens de prompt (non supervisés, labels == -100) en tête. Sinon une
        séquence prompt+réponse trop longue perdait toute la réponse (exemple inutile).
        """
        if len(input_ids) <= max_length:
            return input_ids, labels
        overflow = len(input_ids) - max_length
        lead = 0
        while lead < len(labels) and labels[lead] == -100:
            lead += 1
        drop = min(overflow, lead)
        input_ids = input_ids[drop:]
        labels = labels[drop:]
        if len(input_ids) > max_length:
            # La réponse seule dépasse max_length → troncature à droite en dernier recours
            input_ids = input_ids[:max_length]
            labels = labels[:max_length]
        return input_ids, labels

    def __len__(self):
        return len(self.samples)

    def __getitem__(self, idx):
        return self.samples[idx]


class PackedDataset(TorchDataset):
    """
    Multipacking : concatène plusieurs exemples courts en séquences de max_length tokens
    séparées par le token EOS, éliminant le padding inutile.

    Gain typique sur CPU : 20-40 % de réduction du nombre d'étapes d'entraînement
    pour un dataset de courtes paires Q/R.
    """

    def __init__(self, path, tokenizer, max_length=512):
        self.packed = []
        eos_id = tokenizer.eos_token_id or 0

        # Tokenise sans padding
        raw_sequences = []
        with open(path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                record = json.loads(line)
                text = self._format(record)
                if not text.strip():
                    continue
                ids = tokenizer.encode(text, truncation=True, max_length=max_length,
                                       add_special_tokens=True)
                raw_sequences.append(ids)

        # Greedy bin-packing
        buffer = []
        for ids in raw_sequences:
            separator = [eos_id] if buffer else []
            if len(buffer) + len(separator) + len(ids) <= max_length:
                buffer.extend(separator)
                buffer.extend(ids)
            else:
                if buffer:
                    self._flush(buffer, max_length)
                buffer = list(ids)
        if buffer:
            self._flush(buffer, max_length)

        original = len(raw_sequences)
        packed = len(self.packed)
        ratio = original / packed if packed else 1.0
        print(f"  Multipacking: {original} exemples → {packed} séquences (ratio {ratio:.1f}x, "
              f"économie de padding: {(1 - 1/ratio)*100:.0f}%)")

    def _flush(self, buffer, max_length):
        # Séquences de longueur variable (<= max_length) : le collator rembourre au
        # plus long du batch. Inutile de pré-rembourrer chaque séquence à max_length.
        self.packed.append({
            "input_ids":      torch.tensor(buffer, dtype=torch.long),
            "attention_mask": torch.tensor([1] * len(buffer), dtype=torch.long),
            "labels":         torch.tensor(list(buffer), dtype=torch.long),
        })

    @staticmethod
    def _format(example):
        text = ""
        for msg in example.get("conversations", []):
            role    = msg.get("role", "")
            content = msg.get("content", "")
            if role == "system":
                text += f"<|system|>\n{content}</s>\n"
            elif role == "user":
                text += f"<|user|>\n{content}</s>\n<|assistant|>\n"
            elif role == "assistant":
                text += f"{content}</s>\n"
        return text

    def __len__(self):
        return len(self.packed)

    def __getitem__(self, idx):
        return self.packed[idx]


def make_pad_collator(pad_id):
    """
    Collator à padding dynamique : rembourre chaque batch à la longueur de sa plus
    longue séquence (et non à un MAX_SEQ_LENGTH fixe). Les labels de padding sont à
    -100, le masque d'attention à 0. À batch_size=1, il n'y a tout simplement aucun
    padding — gain de calcul important sur CPU.
    """
    def collate(features):
        maxlen = max(len(f["input_ids"]) for f in features)
        input_ids, attention, labels = [], [], []
        for f in features:
            ids = f["input_ids"].tolist()
            att = f["attention_mask"].tolist()
            lab = f["labels"].tolist()
            pad = maxlen - len(ids)
            input_ids.append(ids + [pad_id] * pad)
            attention.append(att + [0] * pad)
            labels.append(lab + [-100] * pad)
        return {
            "input_ids":      torch.tensor(input_ids, dtype=torch.long),
            "attention_mask": torch.tensor(attention, dtype=torch.long),
            "labels":         torch.tensor(labels, dtype=torch.long),
        }
    return collate


# ── Modèle ─────────────────────────────────────────────────────
print(f"\nChargement du modèle : {hf_model}")
print("  (premier lancement = téléchargement depuis HuggingFace, peut prendre quelques minutes)")

from peft import get_peft_model, LoraConfig, TaskType, PeftModel

if args.resume_adapter:
    print(f"Mode incrémental — reprise depuis : {args.resume_adapter}")
else:
    print("Mode initial — nouvel adaptateur LoRA")

if USE_UNSLOTH:
    model, tokenizer = FastLanguageModel.from_pretrained(
        model_name=hf_model,
        max_seq_length=512,
        load_in_4bit=True,
    )
    if args.resume_adapter:
        model = PeftModel.from_pretrained(model, args.resume_adapter, is_trainable=True)
    else:
        target_modules = find_target_modules(model)
        print(f"  Modules LoRA ciblés : {target_modules}")
        model = FastLanguageModel.get_peft_model(
            model,
            r=args.lora_rank,
            lora_alpha=args.lora_alpha,
            lora_dropout=0.05,
            target_modules=target_modules,
        )
else:
    tokenizer = AutoTokenizer.from_pretrained(hf_model)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    # SDPA (scaled-dot-product attention) est plus rapide qu'eager quand il est supporté ;
    # repli sur eager pour les architectures qui ne l'implémentent pas.
    try:
        model = AutoModelForCausalLM.from_pretrained(
            hf_model, dtype=torch.float32, attn_implementation="sdpa",
        )
    except (ValueError, ImportError, RuntimeError):
        model = AutoModelForCausalLM.from_pretrained(
            hf_model, dtype=torch.float32, attn_implementation="eager",
        )

    if args.resume_adapter:
        # Charge l'adaptateur existant en mode entraînable (is_trainable=True)
        model = PeftModel.from_pretrained(model, args.resume_adapter, is_trainable=True)
    else:
        target_modules = find_target_modules(model)
        print(f"  Modules LoRA ciblés : {target_modules}")
        peft_config = LoraConfig(
            task_type=TaskType.CAUSAL_LM,
            r=args.lora_rank,
            lora_alpha=args.lora_alpha,
            lora_dropout=0.05,
            target_modules=target_modules,
        )
        model = get_peft_model(model, peft_config)
    model.print_trainable_parameters()

    # Gradient checkpointing : économise la mémoire (utile pour Mistral/Llama3 7-8B sur GPU)
    # au prix d'un peu de calcul. Sur CPU il ralentit sans bénéfice → activé seulement sur GPU.
    if has_gpu:
        model.config.use_cache = False
        model.enable_input_require_grads()

# ── Chargement dataset (PyTorch natif — compatible Python 3.14) ────────────────
MAX_SEQ_LENGTH = 512
print(f"\nChargement du dataset : {args.dataset}")
if args.packing:
    print("  Mode multipacking activé")
    dataset = PackedDataset(args.dataset, tokenizer, max_length=MAX_SEQ_LENGTH)
else:
    dataset = ConversationDataset(args.dataset, tokenizer, max_length=MAX_SEQ_LENGTH)
print(f"  {len(dataset)} séquences d'entraînement")

if len(dataset) == 0:
    print("ERREUR: dataset vide — vérifiez le fichier JSONL et le champ 'conversations'")
    sys.exit(1)

# ── Entraînement ───────────────────────────────────────────────
class ProgressLogger(TrainerCallback):
    def on_log(self, args, state, control, logs=None, **kwargs):
        if not logs:
            return
        if "loss" in logs:
            print(f"  epoch={state.epoch:.2f}  loss={logs['loss']:.4f}")
        if "eval_loss" in logs:
            print(f"  epoch={state.epoch:.2f}  eval_loss={logs['eval_loss']:.4f}")
        sys.stdout.flush()

print(f"\nDébut de l'entraînement ({args.epochs} époque(s), LoRA rank={args.lora_rank}"
      + (", packing=on" if args.packing else "")
      + (", DPO=on" if args.dpo else "") + ")...")
print("  Sur CPU, chaque étape peut prendre plusieurs minutes — c'est normal.\n")

if args.dpo:
    print("Mode DPO activé — chargement des paires chosen/rejected")
    try:
        from trl import DPOTrainer, DPOConfig
        import json as _json

        dpo_data = [_json.loads(l) for l in open(args.dataset) if l.strip()]
        print(f"  {len(dpo_data)} paires DPO chargées")
        if dpo_data and not all(k in dpo_data[0] for k in ("prompt", "chosen", "rejected")):
            raise ValueError(
                "Le fichier DPO doit contenir des objets {prompt, chosen, rejected}. "
                "Passez le fichier dpo_pairs.jsonl (et non l'export SFT 'conversations').")

        try:
            from datasets import Dataset as HFDataset
            hf_dataset = HFDataset.from_list(dpo_data)
        except ImportError:
            # Fallback: simple dict list (trl may accept it)
            hf_dataset = dpo_data

        # ref_model=None : pour un modèle PEFT, DPOTrainer désactive l'adaptateur pour
        # obtenir la référence (modèle de base figé) — inutile et coûteux de dupliquer
        # toute la politique avec copy.deepcopy (doublait l'empreinte mémoire).
        dpo_trainer = DPOTrainer(
            model=model,
            ref_model=None,
            args=DPOConfig(
                max_length=MAX_SEQ_LENGTH,
                max_prompt_length=MAX_SEQ_LENGTH // 2,
                beta=0.1,
                output_dir="./training_output",
                per_device_train_batch_size=1,
                gradient_accumulation_steps=4,
                num_train_epochs=args.epochs,
                learning_rate=args.lr,
                fp16=has_gpu,
                use_cpu=not has_gpu,
                logging_steps=1,
                save_strategy="no",
                report_to="none",
            ),
            train_dataset=hf_dataset,
            processing_class=tokenizer,
        )
        dpo_trainer.add_callback(ProgressLogger())
        dpo_trainer.train()

    except ImportError as e:
        print(f"WARN: DPOTrainer non disponible ({e}) — repli sur SFT avec réponses 'chosen'")
        args.dpo = False

if not args.dpo:
    # Split train/validation optionnel pour suivre l'eval_loss (détection du sur-apprentissage).
    train_dataset, eval_dataset = dataset, None
    if args.val_split and args.val_split > 0 and len(dataset) >= 4:
        import random
        from torch.utils.data import Subset
        indices = list(range(len(dataset)))
        random.Random(42).shuffle(indices)
        n_val = max(1, int(len(dataset) * args.val_split))
        val_idx = sorted(indices[:n_val])
        train_idx = sorted(indices[n_val:])
        eval_dataset = Subset(dataset, val_idx)
        train_dataset = Subset(dataset, train_idx)
        print(f"  Split validation : {len(train_idx)} entraînement / {len(val_idx)} validation")

    pad_id = tokenizer.pad_token_id if tokenizer.pad_token_id is not None else 0

    # PackedDataset et ConversationDataset fournissent input_ids / attention_mask / labels
    # (labels = -100 sur le prompt). Le collator à padding dynamique empile et rembourre les
    # tenseurs SANS recalculer les labels — au contraire de DataCollatorForLanguageModeling
    # qui écraserait le masquage et supprimerait la supervision de l'EOS (pad_token == eos_token).
    trainer = Trainer(
        model=model,
        train_dataset=train_dataset,
        eval_dataset=eval_dataset,
        args=TrainingArguments(
            per_device_train_batch_size=1,
            gradient_accumulation_steps=4,
            warmup_steps=5,
            num_train_epochs=args.epochs,
            learning_rate=args.lr,
            fp16=has_gpu,
            bf16=False,
            logging_steps=1,
            output_dir="./training_output",
            use_cpu=not has_gpu,
            save_strategy="no",
            eval_strategy="epoch" if eval_dataset is not None else "no",
            gradient_checkpointing=has_gpu and not USE_UNSLOTH,
            report_to="none",
            remove_unused_columns=False,
        ),
        data_collator=make_pad_collator(pad_id),
    )
    trainer.add_callback(ProgressLogger())
    trainer.train()

# ── Sauvegarde ─────────────────────────────────────────────────
os.makedirs(args.output, exist_ok=True)
print(f"\nSauvegarde de l'adaptateur LoRA : {args.output}/")
model.save_pretrained(args.output)
tokenizer.save_pretrained(args.output)

if USE_UNSLOTH:
    print(f"Export GGUF : {args.output}.gguf")
    model.save_pretrained_gguf(args.output, tokenizer, quantization_method="q4_k_m")
else:
    print("\nNOTE : Sans GPU/Unsloth, l'export GGUF n'est pas automatique.")
    print("       L'adaptateur LoRA est sauvegardé au format HuggingFace.")
    print("       Pour l'utiliser avec Ollama :")
    print("         1. Fusionnez le modèle de base + l'adaptateur avec llama.cpp")
    print("         2. Ou utilisez directement avec 'transformers' en Python")

print("\nFine-tuning terminé avec succès.")
