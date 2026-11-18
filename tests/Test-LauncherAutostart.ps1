$ErrorActionPreference = "Stop"
$source = Split-Path -Parent $PSScriptRoot
$compiler = Join-Path $env:WINDIR "Microsoft.NET\Framework64\v4.0.30319\csc.exe"
$temporary = Join-Path ([IO.Path]::GetTempPath()) ("taskbar-autostart-" + [Guid]::NewGuid().ToString("N"))
$installed = Join-Path $temporary "installed"
$other = Join-Path $temporary "other"
$alias = Join-Path $temporary "alias"
New-Item -ItemType Directory -Path $installed, $other | Out-Null
try {
    New-Item -ItemType Junction -Path $alias -Target $installed | Out-Null
    $launcher = Join-Path $installed "TaskbarLabelPickerLauncher.exe"
    & $compiler /nologo /target:exe /platform:x64 /main:LauncherAutostartTests "/out:$launcher" `
        /reference:System.Windows.Forms.dll (Join-Path $source "installer\Launcher.cs") `
        (Join-Path $source "installer\ProfileLocation.cs") (Join-Path $PSScriptRoot "LauncherAutostart.Tests.cs")
    if ($LASTEXITCODE) { throw "Compiling the launcher regression failed ($LASTEXITCODE)." }
    & $launcher $other $alias
    if ($LASTEXITCODE) { throw "Launcher regression failed ($LASTEXITCODE)." }
} finally {
    if ([IO.Directory]::Exists($alias)) { [IO.Directory]::Delete($alias) }
    Remove-Item -LiteralPath $temporary -Recurse
}
