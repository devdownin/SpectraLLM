"""
Spectra — Export d'un adaptateur LoRA au format GGUF (SANS fusion).

Contrairement à export_gguf.py qui fusionne l'adaptateur dans le modèle de base puis
quantifie le tout (un fichier par modèle), ce script produit un **petit GGUF d'adaptateur**
chargé À CHAUD par llama-server par-dessus le modèle de base déjà servi.

Avantages :
  • Pas de duplication du modèle de base (l'adaptateur ne pèse que quelques Mo).
  • Permutation d'adaptateurs sans redémarrage (endpoint /lora-adapters de llama-server).
  • Pas de re-quantization du modèle complet.

Usage :
    python scripts/export_lora_gguf.py \
        --adapter data/fine-tuning/adapter \
        --output  data/fine-tuning/adapter-lora.gguf \
        --base-model phi3
"""

import argparse
import os
import sys
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("--adapter",    default="data/fine-tuning/adapter",
                    help="Répertoire de l'adaptateur LoRA (format HuggingFace/PEFT)")
parser.add_argument("--output",     default="data/fine-tuning/adapter-lora.gguf",
                    help="Fichier GGUF d'adaptateur à produire")
parser.add_argument("--base-model", default="phi3",
                    help="Alias du modèle de base (doit correspondre à l'entraînement)")
parser.add_argument("--outtype",    default="f16", choices=["f16", "f32", "q8_0"],
                    help="Précision des tenseurs LoRA dans le GGUF (f16 = bon compromis)")
args = parser.parse_args()

# Mapping alias → repo HF chargé depuis le manifeste UNIQUE base_models.json (même source
# que train_host.py / export_gguf.py) : l'adaptateur n'est valide que sur SA base.
from base_models import load_base_models
MODEL_MAP = load_base_models()
hf_base = MODEL_MAP.get(args.base_model, args.base_model)

if not os.path.exists(os.path.join(args.adapter, "adapter_config.json")):
    print(f"ERREUR : adaptateur introuvable (adapter_config.json absent) dans {args.adapter}")
    sys.exit(1)

# ── Localiser convert_lora_to_gguf.py (paquet llama_cpp, sinon téléchargement épinglé) ──
# Même révision épinglée que export_gguf.py (cf. llama_cpp_convert) : l'adaptateur GGUF doit
# être lisible par le llama-server qui le chargera, dont l'image est elle aussi épinglée.
from llama_cpp_convert import pinned_revision, resolve

try:
    convert_script = resolve("convert_lora_to_gguf.py",
                             os.path.dirname(os.path.abspath(args.output)))
except Exception as e:
    print(f"  ERREUR téléchargement : {e}")
    print(f"  Conversion manuelle : récupérez convert_lora_to_gguf.py depuis "
          f"github.com/ggml-org/llama.cpp à la révision {pinned_revision()}")
    sys.exit(1)

# ── Conversion adaptateur → GGUF ───────────────────────────────────────────────
print(f"=== Conversion LoRA → GGUF (base: {hf_base}) ===")
cmd = [sys.executable, convert_script, args.adapter,
       "--base", hf_base, "--outfile", args.output, "--outtype", args.outtype]
print("  " + " ".join(cmd))
result = subprocess.run(cmd, capture_output=True, text=True)
if result.returncode != 0:
    print(f"  ERREUR conversion :\n{result.stderr}")
    sys.exit(1)

lora_abs = os.path.abspath(args.output)
print(f"  GGUF d'adaptateur prêt : {lora_abs}\n")

# ── Instructions de service à chaud ────────────────────────────────────────────
print("=== Servir l'adaptateur à chaud (sans fusion) ===")
print("  Option A — au démarrage de llama-server (par-dessus le GGUF de base) :")
print(f"    llama-server -m base-model.gguf --lora-scaled {lora_abs} 1.0\n")
print("  Option B — permutation à chaud sans redémarrage (API llama-server) :")
print(f"    curl -X POST http://localhost:8081/lora-adapters \\")
print(f"      -H 'Content-Type: application/json' \\")
print(f"      -d '[{{\"id\": 0, \"scale\": 1.0}}]'   # 0.0 pour désactiver l'adaptateur")
print("\n  (l'adaptateur doit avoir été déclaré au lancement via --lora "
      + os.path.basename(lora_abs) + ")")
print("\nExport LoRA terminé.")
