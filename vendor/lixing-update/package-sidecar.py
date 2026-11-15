#!/usr/bin/env python3
"""Stage the shared updater using the exact public Node.js distribution."""

import argparse
import hashlib
from pathlib import Path
import shutil
import tempfile
from urllib.request import urlopen


VERSION = "v24.22.3"
RUNTIME_FILES = {
    "node.exe": (
        f"https://nodejs.org/dist/{VERSION}/win-x64/node.exe",
        "86fc4785327db5f5c7558dc6156c543b433c313e813a222e088086222ab4afa6",
    ),
    "NODE-LICENSE.txt": (
        f"https://raw.githubusercontent.com/nodejs/node/{VERSION}/LICENSE",
        "5888dbb9a1d2b18f2c3e6c5f6af1b39de658372b402a0577b002777f14c62ace",
    ),
}
SCRIPT_FILES = ("cli.cjs", "lixing-update.cjs", "config.cjs", "release-certificate.cer")


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def stage_runtime(directory: Path, cache: Path) -> None:
    cache.mkdir(parents=True, exist_ok=True)
    for name, (url, expected) in RUNTIME_FILES.items():
        cached_file = cache / name
        if not cached_file.is_file() or sha256(cached_file) != expected:
            with tempfile.NamedTemporaryFile(dir=cache, delete=False) as temporary:
                candidate = Path(temporary.name)
                try:
                    with urlopen(url, timeout=90) as response:
                        shutil.copyfileobj(response, temporary)
                    temporary.close()
                    if sha256(candidate) != expected:
                        raise ValueError(f"Unexpected Node.js asset digest for {name}")
                    candidate.replace(cached_file)
                finally:
                    candidate.unlink(missing_ok=True)
        shutil.copyfile(cached_file, directory / name)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cache", type=Path, required=True)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=args.output.parent) as workspace:
        candidate = Path(workspace) / "update"
        candidate.mkdir()
        stage_runtime(candidate, args.cache / VERSION)
        source = Path(__file__).resolve().parent
        for name in SCRIPT_FILES:
            shutil.copyfile(source / name, candidate / name)
        args.output.mkdir(parents=True, exist_ok=True)
        for item in candidate.iterdir():
            item.replace(args.output / item.name)


if __name__ == "__main__":
    main()
