# 砺行应用更新组件 1.0.1

SimRacingOverlay 的 GitHub 下载、更新历史、包校验和安装流程中通用的部分由本目录维护。Electron 主进程直接调用，Compose Desktop 可以启动同一 JavaScript 实现的 Windows 辅助进程。应用拥有自己的更新入口、安装器、用户确认、通知呈现和局部资源激活。应用可以把这份目录及其授权声明固定到自己的源码中，以便独立构建。

## 客户端

Electron 主进程调用 `createReleaseChannel`，注入仓库、应用 ID、已安装版本、平台架构、发布频道、内置发布证书，以及通过系统代理的 request 适配器。默认检查 GitHub 正式 Release 的 `vX.Y.Z` 标签。只使用数字标签的项目传 `tagPrefix: ''`。

- `discover(officialVersion)` 返回 `release`、`available`、`verified`、`canSetup`、`canHotfix`、`trustError`。签名说明在 `release.notes` 及 `release.history`。发现未签名发布时，页面可以展示 GitHub 链接和校验状态。宿主发正式更新通知的条件为 `available && verified`。
- `download('setup', onProgress)` 或 `download('hotfix', onProgress)` 得到专属对象。安装前重新调用 `verifySetup(staged)`，以返回的路径启动固定本机安装命令。
- `verifyHotfix(staged)` 返回应用激活器可用的已验字节副本。应用声明支持的 `hotfixKinds`，清单可绑定 `baseVersion` 或 `moduleVersion`。激活器直接消费已验字节，并负责检查资源协议、暂存、切换、确认运行加载和回滚失败代际。
- 库产物可用 `verifyManifest` 和 `verifyArtifact(path, spec)` 做离线校验。只发布库的项目沿包管理器安装。

Windows 辅助进程用 Python 3.11 或以上打包。脚本固定 Node v24.22.3 Windows x64 二进制和 LICENSE 的 SHA-256。应用随包分发生成目录并保留 `NODE-LICENSE.txt`。Node.js 源代码对应 [v24.22.3](https://github.com/nodejs/node/tree/v24.22.3)。

```sh
python3 package-sidecar.py --output <app-runtime-resources>/update --cache <build-cache>
```

运行 `node.exe --no-warnings cli.cjs --config <release-config.json>`。配置文件由应用打包生成，包含 `appId, repo, currentVersion, platform, arch, channel`。Windows x64 用 `platform: "win32"`、`arch: "x64"`、`channel: "stable"`。可选 `setupFilename` 为含 `{version}` 的本机固定 EXE 文件名模式，`setupArguments` 为固定安装器参数。有可激活热包的应用才填写 `hotfixKinds`。

使用 UTF-8 JSON Lines 标准输入输出。发送 `{"id":1,"action":"check"}`，对应响应 `{"id":1,"result":{"ok":true,"state":{}}}`。状态变化推送 `{"event":"state","state":{}}`。操作有 `check`、`download`、`install`、`snapshot`、`verifyHotfix`。下载默认完整包，指定 `kind: "hotfix"` 取热包。热包验证返回 `result.hotfix`，内含 `version`、`spec` 和 `bytesBase64`。

明确用户确认或启用自动安装后启动安装器。退出应用时关闭 stdin，正在执行的安装器继续运行并写回执。安装器退出零进入等待重启状态，下一次应用实际报告新版本后才显示完成。宿主安装器负责完成原应用退出、事务替换及必要重启。

Kotlin/Compose 可把 `compose/src/main/kotlin` 加入 source set 并复用 `LixingUpdater`。依赖 kotlinx.coroutines 与 kotlinx.serialization.json。适配器用标准管道启动子进程，Windows JDK 21 对管道式子进程使用 CREATE_NO_WINDOW。

Windows 首先使用明确配置的 `HTTPS_PROXY` 或 `ALL_PROXY`（包含其小写形式），其余按系统为目标 URL 选择的代理或直连路径处理。`NO_PROXY` 用于排除代理；每次跳转均先核对 GitHub 地址边界。代理取值不进入日志，TLS 验证保持开启。网络检查使用当前定版 Node 24。独立 Node 脚本需要使用代理时支持 Node 22.21+ 或 Node 24.5+ 的受维护分支。

## 配置

Windows 按用户存放于 `%USERPROFILE%\.lixing\<appId>`。这个位置避开部分 Windows 打包宿主对新建 AppData 文件的虚拟化。Linux 使用 `$XDG_CONFIG_HOME/Lixing/<appId>`，缺少有效绝对值时用 `~/.config/Lixing/<appId>`。macOS 使用 `~/Library/Application Support/Lixing/<appId>`。同一用户下安装在不同目录的同一应用使用同一配置根。

`migrateConfigFiles({appId, legacyRoot, files})` 从已知旧根迁移清单中的文件或目录。调用方在首次写入和其他产品消费者启动前执行迁移。先生成同卷完整副本，再发布到缺失的新位置。已有新配置优先，旧数据保留。对具有业务删除语义的配置，成功迁移后由产品保存来源标识，以免日后把已删除条目重新带回。

## 签名与发布

1. 使用干净源码构建正式安装器和随版本发布的包。构建输入 JSON 的 `artifacts` 项包含 `kind` 及 `file`，相对路径以该 JSON 父目录为准。热包另带 `hotfixKind` 及适用的兼容版本。`releaseNotes` 包含每版文字，以保留跳过多个版本更新时的完整说明。
2. 构建端生成清单后，使用持有证书的本机 Windows 用户签名。签名脚本匹配内置 DER 指纹，与 CurrentUser My 证书存储中的不可导出 CNG 私钥配对。CI 构建、校验和发布公共资产。
3. 按同一个 Git tag 发布产物、`lixing-update-manifest.json` 与 `lixing-update-manifest.sig`。旧项目可为已发布的旧客户端保留原清单，新客户端只验证这两份签名资产。

```text
node build-manifest.cjs --config release-build-input.json --output dist/lixing-update-manifest.json
powershell -NoProfile -File sign-release.ps1 -Certificate release-certificate.cer -Manifest dist/lixing-update-manifest.json
```

清单 schema 2 绑定应用、仓库、频道、版本、平台架构、产物名称、长度、SHA-256 和更新说明。客户端使用内置的证书 SHA-256，先验证 RSA-3072/SHA-256 签名，再读取签名内容。安装和热更新前均二次校验。已观察到的签名版本固定清单摘要，用于识别旧版本重放和同版本资产替换。

这层保护应对传输错误、GitHub 资产篡改和网络伪造。首次获取仍依赖可信安装来源。Windows Authenticode 信任和 SmartScreen 声誉由 Windows 自身判定。具备同一用户文件和进程控制权的本地攻击者可以替换整个客户程序，删除本地信任记录也会清除历史范围内的回放判断。

## 1.0.1 消费文件

在产品仓对照指纹，核对构建和运行加载的版本。分发许可见仓库 LICENSE，所复用 SimRacingOverlay 代码版权归 Cormor。

```text
7afd2aaa7752e93ca6a8680cb0bd1497005c94af6e7fabaad189965eb8a29f0e  lixing-update.cjs
60d22dc5b93bb0e5f24f0a618920718675fcc90f18ba294d7e9328ee6fbf043a  cli.cjs
2564392119d581cc9be2decfd85cc363f84d794e597eff4e0a7ff8a6ad406fa1  config.cjs
87d5163dc82a6bb52c5a1f6148ec0669fbbbab87939a6788e8cbdf133eb36d3b  build-manifest.cjs
7664da56a047d38a3d8f4cb575ee907c7f921653875dc8ceadd2f3d327e63f9e  sign-release.ps1
18433d307b60c6962508c20e35e36206851bf69e2683a24fa43b2ec689ae69ca  release-certificate.cer
19f904f04dab95987c49ba9dcaa3002d0600b4784a5ac433ef9cbe783dc198f3  package-sidecar.py
7d67186161a6b3fa4deb63fa8330f4aefcd576be4b6c80826d33e936a72541b1  compose/src/main/kotlin/com/lixing/update/LixingUpdater.kt
```
