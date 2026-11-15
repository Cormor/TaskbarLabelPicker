# 任务栏标签白名单

打开「任务栏标签」，在每个应用右侧配置 **仅图标** 和 **叠放窗口**。

- **仅图标** 隐藏应用的按钮标签。每次开启仅图标都会打开叠放，随后可单独取消。关闭仅图标时，由这次操作自动打开且仍未手动调整的叠放会恢复原设置；手动打开的叠放继续保留。
- **叠放窗口** 将同一应用的多个窗口放在一个原生任务栏按钮里。窗口真的叠起来时，按钮会露出一层同应用图标；剩一个窗口后，露边随之消失。可单独显示标签或隐藏标签。
- 「新应用自动仅图标并叠放」默认关闭。开启后，首次在任务栏观察到、且无历史与已有设置的应用会同时开启两项。关闭只停止后续自动设置，已生成的规则可逐项修改。使用「说明」查看这些行为。
- 「其他已保存应用」列出已有设置但没有采集到历史的应用。
- 「历史」按最后出现时间倒序排列，可给当前未显示的应用预设两项开关。历史从插件运行时实际观察到的任务栏按钮开始，托盘后台继续记录。排序时间在应用实际出现或有新窗口进入任务栏时更新。极短出现的应用在 Windows 提供可识别的按钮事件时也会记录。
- 应用历史和设置合计最多保留 500 个应用。超出时清理最久未打开的一项。点击「清理」可选中一个或多个应用，同时删除它们的历史和设置。仍在任务栏的应用离开后再次出现，才会重新记录。

配置器启动后先读取主屏和副屏任务栏的可见应用按钮，随后由任务栏和规则文件变化通知触发更新。每行显示应用图标和当前窗口标题，图标取自窗口或 Windows Shell。运行中的应用默认显示图标和名称，勾选「仅图标」时隐藏名称。应用的叠放选择可覆盖 Windows 的全局合并设置，没单独选择叠放时沿用系统设置。合并或任务栏溢出时，列表取当前可见的应用按钮。通知区不参与配置。

### 叠放图标的对齐

把前后两张图标作为一组布局。仅图标按钮以原生悬停底板的实际中线为准，带名称的按钮以左侧图标槽为准。两张图在可用宽度内共同放大至多 6%，后图向右露出约 5–6 个物理像素并保留圆角。叠放时，底部运行指示条与双图共用中线，颜色和长短沿用原生焦点状态。用同 DPI、同窗口组截图检查有框悬停时的左右留白、叠层可辨性和静置时的位置。分开或只剩一个窗口时恢复单图。

## 安装和使用

- 双击 `TaskbarLabelPicker-0.1.5-x64-Setup.exe` 安装到当前 Windows 用户。从开始菜单搜索「任务栏标签」打开配置器。安装包固定安装到当前用户的 `%LOCALAPPDATA%\Programs\TaskbarLabelPicker`，包含应用运行时和专用 Windhawk 后台。目标平台为 Windows 11 x64，本机实测 25H2（26200.7623）。
- 登录启动初始关闭。可在安装界面选择，也可在配置器内切换「登录 Windows 时启动」。配置器以当前运行目录与系统已安装应用登记一致为依据，并读回这个应用的实际启动登记。开启后登录只启动到托盘，双击托盘图标或从开始菜单打开设置。
- 从配置器点「支持开发」查看自愿支持入口。点「版本」可检查签名更新、查看变更和安装。自动安装默认关闭。
- 右键托盘图标可查看状态、打开配置器或退出配置器。需要停用模块时，使用 Windows「设置 → 应用 → 已安装的应用 → 任务栏标签」卸载。
- 在相同用户下直接运行新版安装包可升级。设置与历史分别放在 `%USERPROFILE%\.lixing\TaskbarLabelPicker\rules.dat` 和同目录 `history`。首次启动时迁移旧版设置和历史，保留旧目录以供回退；升级和卸载保留现用目录。历史保存应用名称、图标、身份和最后打开时间。
- 若本机已运行其他 Windhawk 实例，关闭该实例后再从开始菜单启动。应用需要与当前 Windows 版本匹配的任务栏符号；安装包已缓存本机实测版本，其他支持的更新版本首次可能需要从 Microsoft 符号服务器获取。

## 构建

模块通过 Windhawk 自带编译器构建，安装包通过 [installer](installer/) 下的 launcher、载荷生成脚本和 NSIS 定义生成。配置器使用 Windows x64 的 .NET Framework 4.x、JDK 21 和 Gradle。以 PowerShell 运行 `Build.ps1 -Gradle <gradle.bat 的路径>`，应用输出在 `build\compose\binaries\main\app\TaskbarLabelPicker`。使用 Gradle 9.6.0 完成本机打包。公开版本随源码提供固定的砺行 Compose 组件快照，文件摘要见 `vendor/lixing-components/SOURCE-SHA256.json`。日常开发可用 `LIXING_TASKBAR_COMPONENTS` 指定对应的组件源码目录。

模块的 Windows 原生标签截获逻辑来自 [m417z 的 Taskbar Labels for Windows 11](https://github.com/ramensoftware/windhawk-mods/blob/5abbcc2ff02fe80a9b46afedbf3f6474f1169f65/mods/taskbar-labels.wh.cpp)，按 [GPL-3.0](mod/LICENSE) 提供。

## 许可

本项目的独立原创部分按 [非商业源码可见许可 1.0](LICENSE) 提供。Windhawk、派生任务栏模块和其他随包运行库保留各自许可，见 [第三方组件与声明](THIRD_PARTY_NOTICES.md)。

### 首次下载的版本核对

从本项目的 [GitHub Releases](https://github.com/Cormor/TaskbarLabelPicker/releases) 下载同一版本的安装包、`lixing-update-manifest.json` 和 `lixing-update-manifest.sig`。使用相同发布标签下的 [公开发行证书](vendor/lixing-update/release-certificate.cer)，从已信任的发布入口核对证书 SHA-256。

```text
18433D307B60C6962508C20E35E36206851BF69E2683A24FA43B2EC689AE69CA
```

已安装应用内的「版本」会用此固定证书验证清单，并在安装前验证安装包。首次安装可在 Windows PowerShell 中运行：

```powershell
$expectedCertificate = '18433D307B60C6962508C20E35E36206851BF69E2683A24FA43B2EC689AE69CA'
if ((Get-FileHash .\release-certificate.cer).Hash -ne $expectedCertificate) { throw '证书指纹不匹配' }
$cert = [Security.Cryptography.X509Certificates.X509Certificate2]::new((Resolve-Path .\release-certificate.cer).Path)
$key = [Security.Cryptography.X509Certificates.RSACertificateExtensions]::GetRSAPublicKey($cert)
$bytes = [IO.File]::ReadAllBytes((Resolve-Path .\lixing-update-manifest.json).Path)
$signature = [IO.File]::ReadAllBytes((Resolve-Path .\lixing-update-manifest.sig).Path)
try {
    $verified = $key.VerifyData($bytes, $signature,
        [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pkcs1)
} finally { $key.Dispose() }
if (!$verified) { throw '发布清单签名不匹配' }
$manifest = [Text.Encoding]::UTF8.GetString($bytes) | ConvertFrom-Json
if ($manifest.appId -cne 'TaskbarLabelPicker' -or $manifest.repo -cne 'Cormor/TaskbarLabelPicker' -or
    $manifest.platform -cne 'win32' -or $manifest.arch -cne 'x64' -or $manifest.channel -cne 'stable') {
    throw '发布目标不匹配'
}
$setup = '.\TaskbarLabelPicker-0.1.5-x64-Setup.exe'
$item = @($manifest.artifacts | Where-Object { $_.kind -eq 'setup' -and $_.name -ceq (Split-Path -Leaf $setup) })
if ($manifest.version -cne '0.1.5' -or $item.Count -ne 1 -or
    (Get-Item $setup).Length -ne $item[0].size -or (Get-FileHash $setup).Hash -ine $item[0].sha256) {
    throw '安装包版本、大小或摘要不匹配'
}
'签名和安装包校验通过'
```

上面的发行证书用于验证应用发布清单。Windows 安装器的系统信任与 SmartScreen 评估由 Windows 独立决定。
