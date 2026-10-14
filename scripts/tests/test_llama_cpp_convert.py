"""Offline regression tests for complete pinned converter provisioning.

The tiny archive fixture tests packaging and launcher execution, not ML conversion.
The trainer Docker build additionally runs both real upstream --help entry points.
"""

import io
import json
import os
import pathlib
import re
import subprocess
import sys
import tarfile

import pytest

SCRIPTS_DIR = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS_DIR))
import llama_cpp_convert as lcc  # noqa: E402


@pytest.fixture(autouse=True)
def isolated_sources(tmp_path, monkeypatch):
    monkeypatch.delenv("SPECTRA_LLAMA_CPP_DIR", raising=False)
    monkeypatch.delenv("LLAMA_CPP_REVISION", raising=False)
    monkeypatch.setattr(lcc, "VENDORED_DIR", str(tmp_path / "image"))


def archive_bytes(extra=None, omit=None):
    files = {name: "# fixture\n" for name in lcc.REQUIRED}
    # This exercises sibling package imports from an arbitrary working directory.
    files["conversion/__init__.py"] = "VALUE = 'conversion-imported'\n"
    files["gguf-py/gguf/__init__.py"] = "VALUE = 'gguf-imported'\n"
    for name in lcc.SCRIPTS:
        files[name] = ("import pathlib, sys\n"
                       "sys.path.insert(0, str(pathlib.Path(__file__).parent / 'gguf-py'))\n"
                       "import conversion, gguf\n"
                       "print(conversion.VALUE, gguf.VALUE, sys.argv[1])\n")
    files.pop(omit, None)
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w:gz") as archive:
        for name, content in files.items():
            info = tarfile.TarInfo(f"llama.cpp-{lcc.commit_for(lcc.DEFAULT_REVISION)}/{name}")
            data = content.encode()
            info.size = len(data)
            archive.addfile(info, io.BytesIO(data))
        if extra:
            archive.addfile(extra)
    return output.getvalue()


def fake_download(monkeypatch, payload=None):
    calls = []

    def download(url, target):
        calls.append(url)
        pathlib.Path(target).write_bytes(payload if payload is not None else archive_bytes())

    monkeypatch.setattr(lcc.urllib.request, "urlretrieve", download)
    return calls


def test_revision_matches_serving_image():
    text = (SCRIPTS_DIR.parent / "deploy/docker/docker-compose.yml").read_text()
    assert set(re.findall(r"LLAMA_CPP_IMAGE_TAG:-server-(\S+?)\}", text)) == {lcc.DEFAULT_REVISION}
    assert re.fullmatch(r"[0-9a-f]{40}", lcc.commit_for(lcc.DEFAULT_REVISION))


@pytest.mark.parametrize("value", ["", "   "])
def test_empty_environment_uses_default(monkeypatch, value):
    monkeypatch.setenv("LLAMA_CPP_REVISION", value)
    assert lcc.pinned_revision() == lcc.DEFAULT_REVISION


def test_sha_override_and_immutable_url(monkeypatch):
    commit = "a" * 40
    monkeypatch.setenv("LLAMA_CPP_REVISION", commit)
    assert commit in lcc.script_url(lcc.SCRIPTS[0])
    assert lcc.commit_for(commit) == commit


@pytest.mark.parametrize("revision", ["main", "master", "../bad", "deadbeef", "b9999"])
def test_unverified_revisions_rejected(revision):
    with pytest.raises(ValueError, match="full commit SHA"):
        lcc.commit_for(revision)


def test_vendor_complete_toolchain_and_both_launchers_execute(tmp_path, monkeypatch):
    calls = fake_download(monkeypatch)
    first = lcc.vendor(lcc.SCRIPTS[0], tmp_path)
    assert calls == [f"{lcc.ARCHIVE_BASE}/{lcc.commit_for(lcc.DEFAULT_REVISION)}"]
    for name in lcc.SCRIPTS:
        script = lcc.resolve(name, tmp_path)
        result = subprocess.run([sys.executable, script, "--help"], cwd="/tmp",
                                capture_output=True, text=True, check=True)
        assert result.stdout.strip() == "conversion-imported gguf-imported --help"
    assert pathlib.Path(first).is_file()
    assert len(calls) == 1


def test_verified_local_copy_works_without_network(tmp_path, monkeypatch):
    fake_download(monkeypatch)
    expected = lcc.vendor(lcc.SCRIPTS[0], tmp_path / "vendored")
    monkeypatch.setenv("SPECTRA_LLAMA_CPP_DIR", str(tmp_path / "vendored"))
    monkeypatch.setattr(lcc.urllib.request, "urlretrieve",
                        lambda *_args: pytest.fail("network used for complete local toolchain"))
    assert lcc.resolve(lcc.SCRIPTS[0], tmp_path / "cache") == expected


def test_partial_or_modified_cache_is_rebuilt(tmp_path, monkeypatch):
    calls = fake_download(monkeypatch)
    first = pathlib.Path(lcc.vendor(lcc.SCRIPTS[0], tmp_path))
    (first.parent / "conversion/base.py").unlink()
    assert lcc.resolve(lcc.SCRIPTS[0], tmp_path) == str(first)
    (first.parent / "gguf-py/gguf/__init__.py").write_text("# modified")
    assert lcc.resolve(lcc.SCRIPTS[1], tmp_path)
    assert len(calls) == 3


def test_loose_script_and_package_are_not_accepted(tmp_path, monkeypatch):
    (tmp_path / f"convert_hf_to_gguf-{lcc.DEFAULT_REVISION}.py").write_text("# obsolete")
    (tmp_path / "convert_hf_to_gguf.py").write_text("# unverified")
    calls = fake_download(monkeypatch)
    assert "spectra-" in lcc.resolve(lcc.SCRIPTS[0], tmp_path)
    assert len(calls) == 1


def test_explicit_invalid_toolchain_fails_actionably(tmp_path, monkeypatch):
    monkeypatch.setenv("SPECTRA_LLAMA_CPP_DIR", str(tmp_path))
    with pytest.raises(RuntimeError, match="Incomplete or incompatible"):
        lcc.resolve(lcc.SCRIPTS[0], tmp_path)


def test_manifest_revision_and_required_files_checked(tmp_path, monkeypatch):
    fake_download(monkeypatch)
    script = pathlib.Path(lcc.vendor(lcc.SCRIPTS[0], tmp_path))
    manifest = script.parent / lcc.MANIFEST
    data = json.loads(manifest.read_text())
    data["commit"] = "b" * 40
    manifest.write_text(json.dumps(data))
    assert lcc._validated_script(tmp_path, lcc.SCRIPTS[0], lcc.DEFAULT_REVISION) is None


def test_incomplete_archive_does_not_publish_cache(tmp_path, monkeypatch):
    fake_download(monkeypatch, archive_bytes(omit="conversion/base.py"))
    with pytest.raises(RuntimeError, match="Incomplete"):
        lcc.vendor(lcc.SCRIPTS[0], tmp_path)
    assert not lcc._toolchain(tmp_path, lcc.DEFAULT_REVISION).exists()


@pytest.mark.parametrize("kind", ["traversal", "symlink", "wrong-commit"])
def test_unsafe_archive_is_rejected(tmp_path, monkeypatch, kind):
    prefix = f"llama.cpp-{lcc.commit_for(lcc.DEFAULT_REVISION)}"
    name = {"traversal": f"{prefix}/conversion/../../escape.py",
            "symlink": f"{prefix}/conversion/link.py",
            "wrong-commit": "llama.cpp-wrong/conversion/base.py"}[kind]
    extra = tarfile.TarInfo(name)
    if kind == "symlink":
        extra.type = tarfile.SYMTYPE
        extra.linkname = "/etc/passwd"
    fake_download(monkeypatch, archive_bytes(extra=extra))
    with pytest.raises(RuntimeError):
        lcc.vendor(lcc.SCRIPTS[0], tmp_path)
    assert not lcc._toolchain(tmp_path, lcc.DEFAULT_REVISION).exists()
