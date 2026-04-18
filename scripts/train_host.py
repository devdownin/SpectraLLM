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
args = parser.parse_args()

MODEL_MAP = {
    "phi3":     "TinyLlama/TinyLlama-1.1B-Chat-v1.0",   # CPU: ~4.4 Go (phi3 → TinyLlama sur CPU)
    "tinyllama":"TinyLlama/TinyLlama-1.1B-Chat-v1.0",
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

from transformers import AutoTokenizer, AutoModelForCausalLM, TrainingArguments, Trainer, TrainerCallback, DataCollatorForLanguageModeling
import json
import torch
from torch.utils.data import Dataset as TorchDataset

# ── Dataset PyTorch natif (sans lib datasets — incompatible Python 3.14) ──────
class ConversationDataset(TorchDataset):
    def __init__(self, path, tokenizer, max_length=512):
        self.samples = []
        with open(path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                record = json.loads(line)
                text = self._format(record)
                if not text.strip():
                    continue
                enc = tokenizer(
                    text,
                    truncation=True,
                    max_length=max_length,
                    padding="max_length",
                    return_tensors="pt",
                )
                self.samples.append({
                    "input_ids":      enc["input_ids"].squeeze(0),
                    "attention_mask": enc["attention_mask"].squeeze(0),
                    "labels":         enc["input_ids"].squeeze(0).clone(),
                })

    def _format(self, example):
        # Format TinyLlama/Phi chat template
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
        pad_len = max_length - len(buffer)
        self.packed.append({
            "input_ids":      torch.tensor(buffer + [0] * pad_len, dtype=torch.long),
            "attention_mask": torch.tensor([1] * len(buffer) + [0] * pad_len, dtype=torch.long),
            "labels":         torch.tensor(buffer + [-100] * pad_len, dtype=torch.long),
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
        model = FastLanguageModel.get_peft_model(
            model,
            r=args.lora_rank,
            lora_alpha=args.lora_alpha,
            lora_dropout=0.05,
            target_modules=["q_proj", "k_proj", "v_proj", "o_proj"],
        )
else:
    tokenizer = AutoTokenizer.from_pretrained(hf_model)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    model = AutoModelForCausalLM.from_pretrained(
        hf_model,
        dtype=torch.float32,
        attn_implementation="eager",
    )

    if args.resume_adapter:
        # Charge l'adaptateur existant en mode entraînable (is_trainable=True)
        model = PeftModel.from_pretrained(model, args.resume_adapter, is_trainable=True)
    else:
        peft_config = LoraConfig(
            task_type=TaskType.CAUSAL_LM,
            r=args.lora_rank,
            lora_alpha=args.lora_alpha,
            lora_dropout=0.05,
            target_modules=["q_proj", "k_proj", "v_proj", "o_proj"],
        )
        model = get_peft_model(model, peft_config)
    model.print_trainable_parameters()

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
        if logs and "loss" in logs:
            print(f"  epoch={state.epoch:.2f}  loss={logs['loss']:.4f}")
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
        import copy

        dpo_data = [_json.loads(l) for l in open(args.dataset) if l.strip()]
        print(f"  {len(dpo_data)} paires DPO chargées")

        try:
            from datasets import Dataset as HFDataset
            hf_dataset = HFDataset.from_list(dpo_data)
        except ImportError:
            # Fallback: simple dict list (trl may accept it)
            hf_dataset = dpo_data

        ref_model = copy.deepcopy(model)
        ref_model.eval()

        dpo_trainer = DPOTrainer(
            model=model,
            ref_model=ref_model,
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
    # Le PackedDataset et ConversationDataset fournissent tous deux les labels.
    trainer = Trainer(
        model=model,
        train_dataset=dataset,
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
            report_to="none",
            remove_unused_columns=False,
        ),
        data_collator=DataCollatorForLanguageModeling(tokenizer=tokenizer, mlm=False),
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
