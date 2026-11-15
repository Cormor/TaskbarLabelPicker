"""Copy the reviewed release sources into a dedicated TaskbarLabelPicker Git repository."""

import argparse
from hashlib import sha256
import json
from pathlib import Path
import shutil
import subprocess


PUBLIC_FILES = (
    ".gitattributes",
    ".gitignore",
    "Build.ps1",
    "LICENSE",
    "README.md",
    "THIRD_PARTY_NOTICES.md",
    "build.gradle.kts",
    "installer/Launcher.cs",
    "installer/PreparePayload.py",
    "installer/ProfileLocation.cs",
    "installer/README.md",
    "installer/TaskbarLabelPicker.nsi",
    "mod/LICENSE",
    "mod/taskbar-label-picker.wh.cpp",
    "native/TaskbarBridge.cs",
    "release-config.json",
    "resources/windows-x64/taskbar-label-picker.ico",
    "settings.gradle.kts",
    "src/main/kotlin/TaskbarPicker.kt",
    "src/main/kotlin/SupportDevelopment.kt",
    "src/main/kotlin/TaskbarUpdates.kt",
    "src/main/resources/icons/taskbar-label-picker.png",
    "src/main/resources/icons/taskbar-label-picker.svg",
    "tools/export-public-source.py",
)


COMPONENT_FILES = (
    "LICENSE",
    "compose/build.gradle.kts",
    "compose/settings.gradle.kts",
    "compose/gallery/build.gradle.kts",
    "compose/gallery/src/main/kotlin/com/lixing/components/gallery/Main.kt",
    "compose/src/main/kotlin/com/lixing/components/CanvasStyle.kt",
    "compose/src/main/kotlin/com/lixing/components/Checks.kt",
    "compose/src/main/kotlin/com/lixing/components/Choice.kt",
    "compose/src/main/kotlin/com/lixing/components/ChoiceHost.kt",
    "compose/src/main/kotlin/com/lixing/components/Commands.kt",
    "compose/src/main/kotlin/com/lixing/components/Feedback.kt",
    "compose/src/main/kotlin/com/lixing/components/Fields.kt",
    "compose/src/main/kotlin/com/lixing/components/Slider.kt",
    "compose/src/main/kotlin/com/lixing/components/Support.kt",
    "compose/src/main/kotlin/com/lixing/components/Theme.kt",
    "compose/src/main/resources/lixing-components/support/alipay-code.png",
    "compose/src/main/resources/lixing-components/support/alipay-marks.png",
    "compose/src/main/resources/lixing-components/support/wechat-code.png",
    "compose/src/main/resources/lixing-components/support/wechat-marks.png",
    "src/lixing_components/definitions.json",
    "src/lixing_components/tokens.json",
    "notices/AudioSubtitleOverlay-LICENSE.txt",
    "notices/SimRacingOverlay-LICENSE.txt",
)


UPDATE_FILES = (
    "build-manifest.cjs",
    "cli.cjs",
    "compose/src/main/kotlin/com/lixing/update/LixingUpdater.kt",
    "config.cjs",
    "lixing-update.cjs",
    "package-sidecar.py",
    "release-certificate.cer",
    "sign-release.ps1",
)


def checked_file_set(source: Path, target: Path, files: tuple[str, ...]) -> None:
    for name in files:
        original = source / name
        destination = target / name
        if not original.is_file():
            raise SystemExit(f"Required public file is missing: {original}")
        if not original.resolve().is_relative_to(source) or not destination.resolve().is_relative_to(target):
            raise SystemExit(f"File leaves its project directory: {name}")


def copy_file_set(source: Path, target: Path, files: tuple[str, ...]) -> dict[str, str]:
    hashes = {}
    for name in files:
        destination = target / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        original = source / name
        if destination.suffix in {".cjs", ".cs", ".json", ".kt", ".kts", ".md", ".py", ".ps1", ".txt"}:
            destination.write_bytes(original.read_bytes().replace(b"\r\n", b"\n"))
        else:
            shutil.copyfile(original, destination)
        hashes[name] = sha256(destination.read_bytes()).hexdigest()
    return hashes


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository", type=Path)
    parser.add_argument("--components", type=Path, required=True)
    args = parser.parse_args()
    source = Path(__file__).resolve().parents[1]
    target = args.repository.resolve()
    if source == target or target.is_relative_to(source):
        raise SystemExit("Use a separate Git repository outside the working source tree.")
    if not (target / ".git").is_dir():
        raise SystemExit("Create a dedicated Git repository at the export destination first.")
    git = ("git", "-C", str(target))
    if subprocess.check_output((*git, "status", "--porcelain"), text=True).strip():
        raise SystemExit("The destination contains local changes. Review them before exporting again.")
    components = args.components.resolve()
    bundled = target / "vendor" / "lixing-components"
    if not bundled.resolve().is_relative_to(target):
        raise SystemExit("Component destination leaves the publication repository.")
    update_source = (components / "update").resolve()
    update_target = target / "vendor" / "lixing-update"
    checked_file_set(update_source, update_target, UPDATE_FILES)
    checked_file_set(source, target, PUBLIC_FILES)
    checked_file_set(components, bundled, COMPONENT_FILES)
    copy_file_set(source, target, PUBLIC_FILES)
    hashes = copy_file_set(components, bundled, COMPONENT_FILES)
    (bundled / "SOURCE-SHA256.json").write_text(json.dumps(hashes, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    hashes = copy_file_set(update_source, update_target, UPDATE_FILES)
    (update_target / "SOURCE-SHA256.json").write_text(json.dumps(hashes, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    subprocess.run((*git, "status", "--short"), check=True)


if __name__ == "__main__":
    main()
