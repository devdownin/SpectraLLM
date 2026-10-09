"""Resolve complete, revision-verified llama.cpp conversion toolchains offline first.

Set SPECTRA_LLAMA_CPP_DIR to a directory populated by ``vendor``. Loose scripts and
unversioned llama_cpp packages cannot establish compatibility and are never used.
LLAMA_CPP_REVISION accepts the supported release tag or a full immutable commit SHA.
"""

import hashlib
import json
import os
import pathlib
import re
import shutil
import tarfile
import tempfile
import urllib.request

# Keep aligned with the llama-server image in docker-compose.yml.
DEFAULT_REVISION = "b9828"
RELEASE_COMMITS = {"b9828": "ebd048fc5e4b43ec4e0b4abe0b9bf66e1724dad0"}
RAW_BASE = "https://raw.githubusercontent.com/ggml-org/llama.cpp"
ARCHIVE_BASE = "https://codeload.github.com/ggml-org/llama.cpp/tar.gz"
VENDORED_DIR = "/opt/llama-cpp-converters"
SCRIPTS = ("convert_hf_to_gguf.py", "convert_lora_to_gguf.py")
MANIFEST = "spectra-converters.json"
REQUIRED = (*SCRIPTS, "conversion/__init__.py", "conversion/base.py",
            "gguf-py/gguf/__init__.py", "gguf-py/pyproject.toml",
            "requirements/requirements-convert_legacy_llama.txt")


def pinned_revision():
    return os.getenv("LLAMA_CPP_REVISION", "").strip() or DEFAULT_REVISION


def commit_for(revision):
    if revision in RELEASE_COMMITS:
        return RELEASE_COMMITS[revision]
    if re.fullmatch(r"[0-9a-f]{40}", revision):
        return revision
    raise ValueError("LLAMA_CPP_REVISION must be a supported release tag or full commit SHA")


def script_url(script_name, revision=None):
    return f"{RAW_BASE}/{commit_for(revision or pinned_revision())}/{script_name}"


def _toolchain(root, revision):
    return pathlib.Path(root) / f"llama.cpp-{commit_for(revision)}"


def _launcher_name(script_name):
    if script_name not in SCRIPTS:
        raise ValueError(f"unsupported converter: {script_name}")
    return f"spectra-{script_name}"


def _validated_script(root, script_name, revision):
    """Only return complete toolchains with matching revision and intact source files."""
    target = _toolchain(root, revision)
    try:
        manifest = json.loads((target / MANIFEST).read_text(encoding="utf-8"))
        hashes = manifest["sha256"]
        if manifest["commit"] != commit_for(revision) or not isinstance(hashes, dict):
            return None
        required = (*REQUIRED, *(_launcher_name(name) for name in SCRIPTS))
        if not all(name in hashes for name in required):
            return None
        for name, expected in hashes.items():
            path = pathlib.PurePosixPath(name)
            if path.is_absolute() or ".." in path.parts:
                return None
            source = target / name
            if source.is_symlink() or not source.is_file():
                return None
            if hashlib.sha256(source.read_bytes()).hexdigest() != expected:
                return None
        return str(target / _launcher_name(script_name))
    except (OSError, ValueError, KeyError, TypeError):
        return None


def find_vendored(script_name, revision):
    explicit = os.getenv("SPECTRA_LLAMA_CPP_DIR", "").strip()
    if explicit:
        found = _validated_script(explicit, script_name, revision)
        if not found:
            raise RuntimeError(f"Incomplete or incompatible converter toolchain in {explicit}; "
                               "populate it with llama_cpp_convert.vendor for this revision")
        return found
    return _validated_script(VENDORED_DIR, script_name, revision)


def resolve(script_name, cache_dir, revision=None):
    rev = revision or pinned_revision()
    _launcher_name(script_name)
    found = find_vendored(script_name, rev) or _validated_script(cache_dir, script_name, rev)
    if found:
        return found
    print(f"  Downloading complete llama.cpp toolchain ({rev}); no verified local copy found")
    return vendor(script_name, cache_dir, rev)


def vendor(script_name, target_dir, revision=None):
    """Install both converters and their sibling packages from one immutable archive.

    Manual extraction never follows links or writes archive paths. Publish only after
    completeness checks; interrupted downloads cannot become a usable cache entry.
    The launchers use a sibling .venv when the trainer build has installed one, otherwise
    the calling Python environment must provide the upstream conversion dependencies.
    """
    rev = revision or pinned_revision()
    commit = commit_for(rev)
    _launcher_name(script_name)
    found = _validated_script(target_dir, script_name, rev)
    if found:
        return found
    root = pathlib.Path(target_dir)
    root.mkdir(parents=True, exist_ok=True)
    target = _toolchain(root, rev)
    with tempfile.TemporaryDirectory(prefix=".llama-cpp-", dir=root) as staging:
        stage = pathlib.Path(staging)
        archive = stage / "source.tar.gz"
        urllib.request.urlretrieve(f"{ARCHIVE_BASE}/{commit}", archive)
        tree = stage / "toolchain"
        tree.mkdir()
        hashes = {}
        extracted_bytes = 0
        with tarfile.open(archive, "r:gz") as source:
            for member in source:
                parts = pathlib.PurePosixPath(member.name).parts
                if not parts or parts[0] != f"llama.cpp-{commit}":
                    raise RuntimeError("Archive does not match the requested llama.cpp commit")
                if ".." in parts or pathlib.PurePosixPath(member.name).is_absolute():
                    raise RuntimeError("Unsafe path in converter archive")
                name = "/".join(parts[1:])
                selected = name in (*SCRIPTS, "LICENSE") or name.startswith(
                    ("conversion/", "gguf-py/", "requirements/"))
                if not selected or member.isdir():
                    continue
                if not member.isfile() or name in hashes:
                    raise RuntimeError("Unsafe or duplicate member in converter archive")
                extracted_bytes += member.size
                if member.size > 16 * 1024 * 1024 or extracted_bytes > 64 * 1024 * 1024 or len(hashes) >= 10000:
                    raise RuntimeError("Converter archive exceeds extraction limits")
                data = source.extractfile(member).read()
                destination = tree / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(data)
                hashes[name] = hashlib.sha256(data).hexdigest()
        if not all(name in hashes and (tree / name).stat().st_size for name in REQUIRED):
            raise RuntimeError("Incomplete llama.cpp converter archive")
        for name in SCRIPTS:
            launcher = _launcher_name(name)
            code = ("import os, pathlib, sys\n"
                    "root = pathlib.Path(__file__).resolve().parent\n"
                    "python = root / '.venv' / 'bin' / 'python'\n"
                    "os.environ.pop('NO_LOCAL_GGUF', None)\n"
                    f"os.execv(str(python) if python.is_file() else sys.executable, "
                    f"[str(python) if python.is_file() else sys.executable, str(root / {name!r}), "
                    "*sys.argv[1:]])\n")
            (tree / launcher).write_text(code, encoding="utf-8")
            hashes[launcher] = hashlib.sha256(code.encode()).hexdigest()
        (tree / MANIFEST).write_text(json.dumps({"commit": commit, "sha256": hashes}),
                                     encoding="utf-8")
        # Replace corrupt/partial caches only after the replacement is fully prepared.
        if target.exists():
            shutil.rmtree(target)
        tree.rename(target)
    return str(target / _launcher_name(script_name))


if __name__ == "__main__":
    import sys

    directory = sys.argv[1] if len(sys.argv) > 1 else VENDORED_DIR
    print(vendor(SCRIPTS[0], directory))
