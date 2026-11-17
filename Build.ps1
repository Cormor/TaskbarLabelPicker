param([string]$Gradle = "gradle", [switch]$Offline)

$ErrorActionPreference = "Stop"
$components = $env:LIXING_TASKBAR_COMPONENTS
if (!$components) {
    $components = Join-Path $PSScriptRoot "vendor/lixing-components/compose"
    if (!(Test-Path -LiteralPath $components -PathType Container)) {
        $components = Join-Path $PSScriptRoot "../LixingComponents/compose"
    }
}
$components = (Resolve-Path -LiteralPath $components).Path
if (!(Test-Path -LiteralPath (Join-Path $components "src/main/kotlin/com/lixing/components/Support.kt") -PathType Leaf)) {
    throw "Use the release snapshot of LixingComponents with Support.kt. See installer/README.md."
}
$env:LIXING_TASKBAR_COMPONENTS = $components

$framework = Join-Path $env:WINDIR "Microsoft.NET\Framework64\v4.0.30319"
$cscArgs = @(
    "/nologo", "/target:winexe", "/platform:x64", "/utf8output",
    "/out:$PSScriptRoot/native\TaskbarBridge.exe",
    "/reference:$framework/WPF\UIAutomationClient.dll",
    "/reference:$framework/WPF\UIAutomationTypes.dll",
    "/reference:$framework/WPF\WindowsBase.dll",
    "$PSScriptRoot/installer\ProfileLocation.cs",
    "$PSScriptRoot/native\TaskbarBridge.cs",
    "$PSScriptRoot/native\TaskbarAutomationEvents.cs"
)
& (Join-Path $framework "csc.exe") @cscArgs
if ($LASTEXITCODE) { throw "Compiling TaskbarBridge.exe failed." }

$gradleArgs = @("-p", $PSScriptRoot, "--console=plain", "createDistributable")
if ($Offline) { $gradleArgs += "--offline" }
& $Gradle @gradleArgs
if ($LASTEXITCODE) { throw "Building TaskbarLabelPicker failed." }
