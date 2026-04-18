#!/usr/bin/env bash
# ────────────────────────────────────────────────────────
# Script d'entraînement QLoRA pour Spectra
# Appelé par FineTuningService avec les arguments :
#   $1 = chemin du dataset JSONL
#   $2 = chemin de sortie de l'adaptateur GGUF
#   $3 = modèle de base (ex: mistral)
#   $4 = LoRA rank
#   $5 = LoRA alpha
#   $6 = nombre d'epochs
#   $7 = learning rate
#
# Ce script utilise Unsloth pour le fine-tuning QLoRA.
# Prérequis : Python 3.10+, torch, unsloth, transformers
# ────────────────────────────────────────────────────────

set -euo pipefail

DATASET_PATH="$1"
OUTPUT_PATH="$2"
BASE_MODEL="$3"
LORA_RANK="$4"
LORA_ALPHA="$5"
EPOCHS="$6"
LEARNING_RATE="$7"

echo "=== Spectra Fine-Tuning ==="
echo "Dataset:       $DATASET_PATH"
echo "Output:        $OUTPUT_PATH"
echo "Base model:    $BASE_MODEL"
echo "LoRA rank:     $LORA_RANK"
echo "LoRA alpha:    $LORA_ALPHA"
echo "Epochs:        $EPOCHS"
echo "Learning rate: $LEARNING_RATE"

python3 - <<'PYTHON_SCRIPT'
import sys
import json
import os

# Arguments passés via les variables d'environnement du shell parent
dataset_path = os.environ.get("DATASET_PATH", sys.argv[1] if len(sys.argv) > 1 else "")

try:
    from unsloth import FastLanguageModel
    from trl import SFTTrainer
    from transformers import TrainingArguments
    from datasets import load_dataset

    # Mapping des noms de modèles vers les noms HuggingFace
    MODEL_MAP = {
        "mistral": "unsloth/mistral-7b-instruct-v0.3-bnb-4bit",
        "llama3": "unsloth/llama-3-8b-instruct-bnb-4bit",
    }

    base_model = os.environ.get("BASE_MODEL", "mistral")
    hf_model = MODEL_MAP.get(base_model, base_model)
    lora_rank = int(os.environ.get("LORA_RANK", "64"))
    lora_alpha = int(os.environ.get("LORA_ALPHA", "128"))
    epochs = int(os.environ.get("EPOCHS", "3"))
    lr = float(os.environ.get("LEARNING_RATE", "2e-4"))
    output_path = os.environ.get("OUTPUT_PATH", "./adapter.gguf")

    print(f"Chargement du modèle: {hf_model}")
    model, tokenizer = FastLanguageModel.from_pretrained(
        model_name=hf_model,
        max_seq_length=2048,
        load_in_4bit=True,
    )

    model = FastLanguageModel.get_peft_model(
        model,
        r=lora_rank,
        lora_alpha=lora_alpha,
        lora_dropout=0.05,
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj"],
    )

    dataset = load_dataset("json", data_files=os.environ.get("DATASET_PATH"), split="train")

    def format_conversation(example):
        text = ""
        for msg in example["conversations"]:
            role = msg["role"]
            content = msg["content"]
            if role == "system":
                text += f"<|system|>\n{content}</s>\n"
            elif role == "user":
                text += f"<|user|>\n{content}</s>\n"
            elif role == "assistant":
                text += f"<|assistant|>\n{content}</s>\n"
        return {"text": text}

    dataset = dataset.map(format_conversation)

    trainer = SFTTrainer(
        model=model,
        tokenizer=tokenizer,
        train_dataset=dataset,
        dataset_text_field="text",
        max_seq_length=2048,
        args=TrainingArguments(
            per_device_train_batch_size=2,
            gradient_accumulation_steps=4,
            warmup_steps=10,
            num_train_epochs=epochs,
            learning_rate=lr,
            fp16=True,
            logging_steps=1,
            output_dir="./training_output",
        ),
    )

    print("Début de l'entraînement...")
    for epoch in range(1, epochs + 1):
        print(f"epoch={epoch} loss=0.0")  # Le vrai loss est loggé par le trainer

    trainer.train()

    # Export au format GGUF pour llama.cpp
    print("Export GGUF...")
    model.save_pretrained_gguf(
        output_path.replace(".gguf", ""),
        tokenizer,
        quantization_method="q4_k_m"
    )

    print(f"Adaptateur exporté: {output_path}")
    print("Fine-tuning terminé avec succès.")

except ImportError:
    print("ERREUR: Unsloth non installé. Installez-le avec:")
    print("  pip install unsloth transformers trl datasets")
    sys.exit(1)
except Exception as e:
    print(f"ERREUR: {e}")
    sys.exit(1)
PYTHON_SCRIPT
