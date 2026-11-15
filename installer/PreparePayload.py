"""Assemble an installer payload from compiled, version-matched components."""
import argparse
import configparser
import re
from pathlib import Path
import shutil
import tarfile


def copy(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)


def write_ini(path: Path, settings: dict[str, dict[str, str]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    config = configparser.ConfigParser(interpolation=None)
    config.optionxform = str
    config.read_dict(settings)
    with path.open("w", encoding="utf-16", newline="\r\n") as stream:
        config.write(stream, space_around_delimiters=False)


def assemble(args: argparse.Namespace) -> None:
    payload = args.output
    if payload.exists():
        raise SystemExit(f"Use a fresh payload path: {payload}")
    source = Path(__file__).resolve().parents[1]
    mod_source = source / "mod" / "taskbar-label-picker.wh.cpp"
    version_match = re.search(r"^// @version\s+(\S+)", mod_source.read_text(encoding="utf-8"), re.M)
    if not version_match:
        raise SystemExit(f"Missing module version in {mod_source}")
    version = version_match.group(1)
    app = args.app
    windhawk = args.windhawk
    engine_version = "1.7.3"
    engine = Path("Engine") / engine_version
    profile = Path("AppData")
    mod_name = "local@taskbar-label-picker"
    vendor = payload / "runtime" / "Windhawk"

    for relative in ("TaskbarLabelPicker.exe", "app/resources/TaskbarBridge.exe", "runtime/bin/java.dll",
                     "app/resources/update/node.exe", "app/resources/update/lixing-update.cjs",
                     "app/resources/update/release-config.json", "app/resources/update/release-certificate.cer",
                     "app/resources/update/NODE-LICENSE.txt"):
        if not (app / relative).is_file():
            raise SystemExit(f"Application image missing {relative}")
    shutil.copytree(app, payload / "app")
    legal = payload / "app" / "Notices"
    copy(source / "LICENSE", legal / "TaskbarLabelPicker-LICENSE.txt")
    copy(source / "THIRD_PARTY_NOTICES.md", legal / "THIRD_PARTY_NOTICES.md")
    component_root = source / "vendor" / "lixing-components"
    if not component_root.is_dir():
        component_root = source.parent / "LixingComponents"
    copy(component_root / "LICENSE", legal / "LixingComponents-LICENSE.txt")
    copy(args.launcher, payload / "TaskbarLabelPickerLauncher.exe")
    for relative in ("windhawk.exe", "windhawk-x64-helper.exe"):
        copy(windhawk / relative, vendor / relative)
    for relative in ("32", "64"):
        shutil.copytree(windhawk / engine / relative, vendor / engine / relative)
    copy(windhawk / engine / "engine.ini", vendor / engine / "engine.ini")

    write_ini(vendor / "windhawk.ini", {"Storage": {
        "Portable": "1",
        "EnginePath": r"Engine\1.7.3",
        "AppDataPath": "AppData",
        "CompilerPath": "Compiler",
        "UIPath": "UI",
    }})
    write_ini(vendor / profile / "settings.ini", {"Settings": {
        "Language": "zh-CN", "HideTrayIcon": "1", "DontAutoShowToolkit": "1", "DisableToolkitHotkey": "1",
        "ModTasksDialogDelay": "2147483647", "DisableUpdateCheck": "1",
    }})
    write_ini(vendor / profile / "Engine" / "settings.ini", {"Settings": {
        "Include": "explorer.exe", "Exclude": "*",
        "InjectIntoCriticalProcesses": "0", "InjectIntoIncompatiblePrograms": "0",
        "InjectIntoGames": "0",
    }})
    write_ini(vendor / profile / "Engine" / "Mods" / f"{mod_name}.ini", {"Mod": {
        "Disabled": "0", "LibraryFileName": args.module.name, "Architecture": "x86-64",
        "Include": "explorer.exe", "Version": version, "LoggingEnabled": "0",
    }})
    mods = profile / "Engine" / "Mods" / "64"
    copy(args.module, vendor / mods / args.module.name)
    for library in ("libc++.whl", "libunwind.whl"):
        copy(windhawk / mods / library, vendor / mods / library)
    if args.symbol_cache:
        # Cache only symbol offsets keyed by Microsoft PDB GUID and PE identity.
        # It contains no user configuration and avoids a first-run download on
        # the already verified Windows build.
        cfg = configparser.ConfigParser(interpolation=None)
        cfg.optionxform = str
        cfg.read(args.symbol_cache, encoding="utf-16")
        write_ini(vendor / profile / "Engine" / "ModsWritable" / f"{mod_name}.ini",
                  {"SymbolCache": dict(cfg["SymbolCache"])})

    notices = vendor / "Notices"
    copy(args.windhawk_source, notices / "windhawk-v1.7.3-source.tar.gz")
    with tarfile.open(args.windhawk_source) as archive:
        with archive.extractfile("windhawk-1.7.3/LICENSE") as license_file:
            (notices / "Windhawk-LICENSE.txt").write_bytes(license_file.read())
    copy(windhawk / "Compiler" / "LICENSE.TXT", notices / "Windhawk-Compiler-Runtime-LICENSE.txt")
    copy(source / "mod" / "LICENSE", notices / "TaskbarLabelPicker-Mod-LICENSE.txt")
    copy(mod_source, notices / "taskbar-label-picker.wh.cpp")


def parse() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ("app", "launcher", "module", "windhawk", "windhawk-source", "output"):
        parser.add_argument("--" + arg, type=Path, required=True)
    parser.add_argument("--symbol-cache", type=Path)
    return parser.parse_args()


if __name__ == "__main__":
    assemble(parse())
