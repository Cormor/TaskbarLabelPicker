# Windows 安装包

通过项目的 `Build.ps1` 构建配置器和桥接程序。为当前目标使用 Windhawk 1.7.3 编译 [任务栏模块](../mod/taskbar-label-picker.wh.cpp)，并用 Windows x64 .NET Framework 的 C# 编译器构建 `Launcher.cs` 与 `ProfileLocation.cs`。启动器使用 `/target:winexe /platform:x64 /reference:System.Windows.Forms.dll`，图标取 `../resources/windows-x64/taskbar-label-picker.ico`。

公开源码检出后，先用 Python 3.12+ 与固定的 sidecar 配方准备更新组件；已验证的 Node.js 运行时可跨构建复用缓存。

```sh
python3 vendor/lixing-update/package-sidecar.py --output resources/windows-x64/update --cache .cache/node
cp release-config.json resources/windows-x64/update/
```

配置器构建使用 JDK 21、Gradle 9.6.0 与 Windows x64 .NET Framework 4.x。应用发行版本应在 `build.gradle.kts` 和 `release-config.json` 中保持一致。Windhawk 模块按其源码的 `@version` 独立版本化。无需公司私有路径即可从公开快照执行上述命令。准备 Windows 载荷时不要加入本机规则与历史；默认省略 `--symbol-cache`，安装后由 Windows 符号服务按需解析。

在 WSL 运行 `PreparePayload.py`，传入以下文件和目录。

| 参数 | 文件来源 |
| --- | --- |
| `--app` | Compose 的 `build/compose/binaries/main/app/TaskbarLabelPicker` |
| `--launcher` | 已编译的 `TaskbarLabelPickerLauncher.exe` |
| `--module` | 目标平台的任务栏模块 DLL |
| `--windhawk` | 从官方 Windhawk 1.7.3 安装器提取的便携版本 |
| `--windhawk-source` | 官方对应标签 `v1.7.3` 的源代码压缩包 |
| `--symbol-cache` | 可选，带对应 Windows PE/PDB 标识的模块符号偏移缓存 |
| `--output` | 新建的载荷目录 |

使用 NSIS 3.x 将载荷编成单个安装程序。

```sh
makensis -WX -DVERSION=0.1.8 \
  -DPAYLOAD=/absolute/path/to/payload \
  -DOUTPUT=/absolute/path/to/TaskbarLabelPicker-0.1.8-x64-Setup.exe \
  TaskbarLabelPicker.nsi
```

安装身份为 `Lixing.TaskbarLabelPicker`。程序装入当前用户的 `%LOCALAPPDATA%\Programs\TaskbarLabelPicker`。安装后的唯一入口为开始菜单「任务栏标签」，其目标是同目录的 `TaskbarLabelPickerLauncher.exe`。开机启动使用当前用户的 `Run` 登记并经同一个启动器传递 `--tray`。规则与历史保存在 `%USERPROFILE%\.lixing\TaskbarLabelPicker`，卸载只删除应用、专用后台、开始菜单入口和本应用创建的登录启动登记。

离线安装不需要 JDK、Windhawk 开发环境或另行解包。部署 Windows 版本与现有符号缓存不同时，首次启动可按需读取 Microsoft 符号服务器。两个 Windhawk 守护进程不能在同一登录会话并行运行。运行中的第三方 Windhawk 保持由其用户管理。
