"""Resource preflight for training and CPU LoRA merge, before downloading weights.

The estimate is a lower planning budget, not an OOM guarantee: sequence lengths,
allocator fragmentation and custom architectures can need more memory.
"""

from pathlib import Path
import os
import warnings

GIB = 1024 ** 3


def _read_integer(path):
    try:
        value = Path(path).read_text().strip()
        return None if value == "max" else int(value)
    except (OSError, ValueError):
        return None


def available_cpu_memory(meminfo="/proc/meminfo", cgroup_root="/sys/fs/cgroup",
                         process_cgroup="/proc/self/cgroup"):
    """Return available bytes, bounded by all visible ancestor cgroup limits.

    memory.current includes this process and siblings, unlike the host's
    MemAvailable. Support both unified cgroup v2 and legacy memory controllers.
    """
    budgets = []
    try:
        for line in Path(meminfo).read_text().splitlines():
            if line.startswith("MemAvailable:"):
                budgets.append(int(line.split()[1]) * 1024)
    except (OSError, ValueError, IndexError):
        pass

    if not budgets:
        # Native host mode also runs on Windows. Query current available RAM,
        # rather than total installed RAM (which may already be occupied).
        if os.name == "nt":
            import ctypes
            class MemoryStatus(ctypes.Structure):
                _fields_ = [("length", ctypes.c_ulong), ("load", ctypes.c_ulong),
                            ("total_phys", ctypes.c_ulonglong),
                            ("available_phys", ctypes.c_ulonglong),
                            ("total_page", ctypes.c_ulonglong),
                            ("available_page", ctypes.c_ulonglong),
                            ("total_virtual", ctypes.c_ulonglong),
                            ("available_virtual", ctypes.c_ulonglong),
                            ("extended_virtual", ctypes.c_ulonglong)]
            status = MemoryStatus()
            status.length = ctypes.sizeof(status)
            if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
                budgets.append(status.available_phys)
        else:
            try:
                budgets.append(os.sysconf("SC_AVPHYS_PAGES") * os.sysconf("SC_PAGE_SIZE"))
            except (OSError, ValueError, AttributeError):
                pass

    root = Path(cgroup_root)
    locations = [(root, "memory.max", "memory.current"),
                 (root / "memory", "memory.limit_in_bytes", "memory.usage_in_bytes")]
    try:
        for line in Path(process_cgroup).read_text().splitlines():
            _, controllers, relative = line.split(":", 2)
            if ".." in Path(relative).parts:
                continue
            if not controllers:
                base, limit_name, used_name = root, "memory.max", "memory.current"
            elif "memory" in controllers.split(","):
                base = root / "memory"
                limit_name, used_name = "memory.limit_in_bytes", "memory.usage_in_bytes"
            else:
                continue
            current = base / relative.lstrip("/")
            while current != base and base in current.parents:
                locations.append((current, limit_name, used_name))
                current = current.parent
    except (OSError, ValueError):
        pass

    for directory, limit_name, used_name in locations:
        limit = _read_integer(directory / limit_name)
        used = _read_integer(directory / used_name)
        # v1 represents an unlimited controller using a near-int64 sentinel.
        if limit is not None and 0 < limit < 2 ** 60:
            # Even when usage is unavailable, do not fall back to unlimited host RAM.
            budgets.append(max(0, limit - (used or 0)))
    return min(budgets) if budgets else None


def required_memory(parameter_count, *, operation, bytes_per_parameter=4):
    """Reserve weights plus load/merge working space and 2 GiB headroom."""
    if parameter_count <= 0 or bytes_per_parameter <= 0:
        raise ValueError("Le nombre de paramètres et leur taille doivent être positifs")
    if operation not in ("training", "export"):
        raise ValueError(f"Opération inconnue : {operation}")
    multiplier = 2 if operation == "export" else 1.5
    return int(parameter_count * bytes_per_parameter * multiplier) + 2 * GIB


def check_memory_budget(parameter_count, *, operation, model_name,
                        available_bytes=None, device="CPU", bytes_per_parameter=4):
    required = required_memory(parameter_count, operation=operation,
                               bytes_per_parameter=bytes_per_parameter)
    if available_bytes is None and device == "CPU":
        available_bytes = available_cpu_memory()
    if available_bytes is None:
        warnings.warn(
            f"Impossible de déterminer la mémoire {device} disponible avant {operation}. "
            f"Prévoyez au minimum {required / GIB:.1f} GiB libres ; "
            "exposez /proc/meminfo et cgroup sur Linux pour vérifier la limite du conteneur.",
            RuntimeWarning,
        )
        return required
    if available_bytes < required:
        raise RuntimeError(
            f"Mémoire {device} insuffisante pour {operation} de {model_name} : "
            f"budget estimé {required / GIB:.1f} GiB, "
            f"disponible {available_bytes / GIB:.1f} GiB (limites cgroup incluses sur CPU). "
            "Choisissez --base-model tinyllama pour le profil CPU 12 GiB, ou augmentez "
            "TRAINER_MEMORY_LIMIT et la RAM réellement disponible "
            "(Phi-3 CPU + export : prévoir 40g). Pour entraîner sur NVIDIA, utilisez "
            "docker-compose.gpu.yml ; l'export fusionne toujours sur CPU et exige sa RAM. "
            "Ce budget minimal ne garantit pas l'absence d'OOM pour de longues séquences.")
    return required


def model_parameter_count(config):
    """Count the real architecture on meta tensors; never allocate/download weights."""
    import torch
    from transformers import AutoModelForCausalLM

    with torch.device("meta"):
        skeleton = AutoModelForCausalLM.from_config(config, dtype=torch.float32)
    count = sum(parameter.numel() for parameter in skeleton.parameters())
    del skeleton
    return count
