"""
Spectra — Fusion adaptateur LoRA + export GGUF pour llama-server
Usage:
    python scripts/export_gguf.py \
        --adapter data/fine-tuning/adapter \
        --output  data/fine-tuning/merged \
        --base-model phi3
"""

import argparse
import sys
import os
import shutil
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("--adapter",    default="data/fine-tuning/adapter")
parser.add_argument("--output",     default="data/fine-tuning/merged")
parser.add_argument("--base-model", default="phi3")
parser.add_argument("--model-name", default="spectra-autoroute",
                    help="Nom du modèle à enregistrer dans Spectra")
args = parser.parse_args()

MODEL_MAP = {
    "phi3":     "microsoft/Phi-3-mini-4k-instruct",
    "tinyllama":"TinyLlama/TinyLlama-1.1B-Chat-v1.0",
    "mistral":  "mistralai/Mistral-7B-Instruct-v0.3",
    "llama3":   "meta-llama/Meta-Llama-3-8B-Instruct",
}
hf_model = MODEL_MAP.get(args.base_model, args.base_model)

# ── Étape 1 : Fusion LoRA → modèle plein ──────────────────────
print("=== Étape 1 : Fusion de l'adaptateur LoRA ===")
import torch
from transformers import AutoTokenizer, AutoModelForCausalLM
from peft import PeftModel

print(f"  Chargement du modèle de base : {hf_model}")
tokenizer = AutoTokenizer.from_pretrained(hf_model)
base_model = AutoModelForCausalLM.from_pretrained(hf_model, dtype=torch.float32)

print(f"  Application de l'adaptateur : {args.adapter}")
model = PeftModel.from_pretrained(base_model, args.adapter)

print("  Fusion (merge_and_unload)...")
model = model.merge_and_unload()

print(f"  Sauvegarde du modèle fusionné : {args.output}/")
os.makedirs(args.output, exist_ok=True)
model.save_pretrained(args.output)
tokenizer.save_pretrained(args.output)
print("  Fusion terminée.\n")

# ── Étape 2 : Conversion GGUF via llama.cpp ───────────────────
print("=== Étape 2 : Conversion GGUF ===")

# Chercher convert_hf_to_gguf.py dans les packages installés
convert_script = None
try:
    import llama_cpp
    pkg_dir = os.path.dirname(llama_cpp.__file__)
    candidate = os.path.join(pkg_dir, "convert_hf_to_gguf.py")
    if os.path.exists(candidate):
        convert_script = candidate
except ImportError:
    pass

# Sinon télécharger le script depuis llama.cpp
if not convert_script:
    script_path = os.path.join(args.output, "convert_hf_to_gguf.py")
    if not os.path.exists(script_path):
        print("  Téléchargement du script de conversion llama.cpp...")
        import urllib.request
        url = "https://raw.githubusercontent.com/ggerganov/llama.cpp/master/convert_hf_to_gguf.py"
        try:
            urllib.request.urlretrieve(url, script_path)
            print(f"  Script téléchargé : {script_path}")
        except Exception as e:
            print(f"  ERREUR téléchargement : {e}")
            print("\n  Conversion manuelle :")
            print(f"    1. Téléchargez convert_hf_to_gguf.py depuis github.com/ggerganov/llama.cpp")
            print(f"    2. Exécutez : python convert_hf_to_gguf.py {args.output} --outtype q8_0")
            sys.exit(0)
    convert_script = script_path

gguf_path = os.path.join(args.output, "model.gguf")
print(f"  Conversion vers : {gguf_path}")
print("  (peut prendre 1-2 minutes...)")

result = subprocess.run(
    [sys.executable, convert_script, args.output,
     "--outfile", gguf_path, "--outtype", "q8_0"],
    capture_output=True, text=True
)
if result.returncode != 0:
    print(f"  ERREUR conversion GGUF :\n{result.stderr}")
    sys.exit(1)

print(f"  GGUF généré : {gguf_path}\n")

# ── Étape 3 : Enregistrement dans llama-server ────────────────
print("=== Étape 3 : Enregistrement du modèle ===")

gguf_abs = os.path.abspath(gguf_path)

print(f"\n  GGUF prêt : {gguf_abs}")
print("\n  Enregistrez le modèle via l'API Spectra :")
print(f"""
    curl -X POST http://localhost:8080/api/fine-tuning/models/register \\
      -H "Content-Type: application/json" \\
      -d '{{
        "name":         "{args.model_name}",
        "type":         "chat",
        "source":       "{gguf_abs}",
        "systemPrompt": "Tu es un assistant spécialisé. Tu réponds de manière précise et professionnelle.",
        "activate":     true
      }}'
""")
print("  Ou placez le fichier GGUF dans le répertoire des modèles llama-server")
print("  et redémarrez le service de chat.")
print("\n  Pour interroger le modèle via Spectra :")
print(f"    curl -X POST http://localhost:8080/api/query \\")
print(f"      -H \"Content-Type: application/json\" \\")
print(f"      -d '{{\"question\": \"Quelle est la procédure en cas de panne sur voie rapide ?\"}}'")
print("\nExport terminé avec succès.")
