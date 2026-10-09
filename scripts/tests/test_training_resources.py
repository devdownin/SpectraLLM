"""Memory refusal and cgroup accounting without torch or downloading a model."""
import ast
from pathlib import Path
import sys
from types import SimpleNamespace
from unittest.mock import patch

import pytest

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import training_resources as resources  # noqa: E402


def memory_files(tmp_path, *, host_gib=64):
    meminfo = tmp_path / "meminfo"
    meminfo.write_text(f"MemAvailable: {host_gib * 1024 ** 2} kB\n")
    root = tmp_path / "cgroup"
    root.mkdir()
    proc = tmp_path / "self-cgroup"
    proc.write_text("0::/\n")
    return meminfo, root, proc


def test_container_limit_not_host_ram(tmp_path):
    meminfo, root, proc = memory_files(tmp_path)
    (root / "memory.max").write_text(str(12 * resources.GIB))
    (root / "memory.current").write_text(str(resources.GIB))
    assert resources.available_cpu_memory(meminfo, root, proc) == 11 * resources.GIB


def test_known_cgroup_limit_still_applies_when_usage_unreadable(tmp_path):
    meminfo, root, proc = memory_files(tmp_path)
    (root / "memory.max").write_text(str(12 * resources.GIB))
    assert resources.available_cpu_memory(meminfo, root, proc) == 12 * resources.GIB


def test_ancestor_limit_and_v1(tmp_path):
    meminfo, root, proc = memory_files(tmp_path)
    controller = root / "memory"
    child = controller / "parent" / "child"
    child.mkdir(parents=True)
    proc.write_text("5:memory:/parent/child\n")
    (child / "memory.limit_in_bytes").write_text(str(32 * resources.GIB))
    (child / "memory.usage_in_bytes").write_text(str(resources.GIB))
    (child.parent / "memory.limit_in_bytes").write_text(str(12 * resources.GIB))
    (child.parent / "memory.usage_in_bytes").write_text(str(2 * resources.GIB))
    assert resources.available_cpu_memory(meminfo, root, proc) == 10 * resources.GIB


def test_unlimited_cgroup_uses_host_available(tmp_path):
    meminfo, root, proc = memory_files(tmp_path, host_gib=8)
    (root / "memory.max").write_text("max")
    (root / "memory.current").write_text("20000000")
    assert resources.available_cpu_memory(meminfo, root, proc) == 8 * resources.GIB


@pytest.mark.parametrize("operation", ["training", "export"])
def test_phi3_refused_with_twelve_gib(operation):
    with pytest.raises(RuntimeError, match="TRAINER_MEMORY_LIMIT"):
        resources.check_memory_budget(3_821_079_552, operation=operation,
                                      model_name="phi3", available_bytes=12 * resources.GIB)


def test_tinyllama_export_fits_default_and_phi3_fits_documented_profile():
    resources.check_memory_budget(1_100_048_384, operation="export",
                                  model_name="tinyllama", available_bytes=11 * resources.GIB)
    resources.check_memory_budget(3_821_079_552, operation="export",
                                  model_name="phi3", available_bytes=39 * resources.GIB)


def test_gpu_qlora_budget_is_separate_from_cpu_merge():
    resources.check_memory_budget(3_821_079_552, operation="training", model_name="phi3",
                                  device="GPU", available_bytes=8 * resources.GIB,
                                  bytes_per_parameter=0.5)
    with pytest.raises(RuntimeError, match="Mémoire CPU insuffisante"):
        resources.check_memory_budget(3_821_079_552, operation="export", model_name="phi3",
                                      available_bytes=12 * resources.GIB)


def test_unknown_host_memory_warns_with_budget():
    with patch.object(resources, "available_cpu_memory", return_value=None):
        with pytest.warns(RuntimeWarning, match="Prévoyez au minimum"):
            resources.check_memory_budget(1_000, operation="training", model_name="test")


def test_count_uses_config_meta_tensors_without_weight_download():
    calls = []
    class MetaDevice:
        def __enter__(self):
            calls.append("meta-enter")
        def __exit__(self, *args):
            calls.append("meta-exit")

    def from_config(config, dtype):
        assert calls == ["meta-enter"]
        assert config == "config"
        return SimpleNamespace(parameters=lambda: [SimpleNamespace(numel=lambda: 123)])

    fake_torch = SimpleNamespace(device=lambda device: MetaDevice() if device == "meta" else None,
                                 float32="float32")
    fake_transformers = SimpleNamespace(AutoModelForCausalLM=SimpleNamespace(from_config=from_config))
    with patch.dict(sys.modules, {"torch": fake_torch, "transformers": fake_transformers}):
        assert resources.model_parameter_count("config") == 123
    assert calls == ["meta-enter", "meta-exit"]


@pytest.mark.parametrize("script", ["train_host.py", "export_gguf.py"])
def test_preflight_precedes_model_weight_load(script):
    tree = ast.parse((SCRIPTS / script).read_text())
    calls = [node for node in ast.walk(tree) if isinstance(node, ast.Call)]
    budget_lines = [node.lineno for node in calls if isinstance(node.func, ast.Name)
                    and node.func.id == "check_memory_budget"]
    weight_lines = [node.lineno for node in calls if isinstance(node.func, ast.Attribute)
                    and node.func.attr == "from_pretrained" and isinstance(node.func.value, ast.Name)
                    and node.func.value.id in {"AutoModelForCausalLM", "FastLanguageModel"}]
    assert budget_lines and weight_lines
    assert max(budget_lines) < min(weight_lines)


def test_training_never_exports_gguf_implicitly():
    tree = ast.parse((SCRIPTS / "train_host.py").read_text())
    assert not any(isinstance(node, ast.Attribute) and node.attr == "save_pretrained_gguf"
                   for node in ast.walk(tree))
