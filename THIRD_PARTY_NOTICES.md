# 第三方组件与各自许可

根目录 `LICENSE` 适用于有权独立许可的任务栏标签原创部分。以下组件沿用自身许可。这些文件与版本号用于在发布标签内查找实际对应源码。

| 随安装包提供的组件 | 许可与对应出处 |
| --- | --- |
| Windhawk 1.7.3 x64 运行后台 | GPL-3.0。随包提供对应上游源码压缩包与许可证，位于 `runtime/Windhawk/Notices`。上游为 [Windhawk v1.7.3](https://github.com/ramensoftware/windhawk/tree/v1.7.3)。 |
| 本项目原生 Windhawk 任务栏模块 | GPL-3.0-only。对应源码在 [mod](mod/)，另随包提供模块与 [GPL 全文](mod/LICENSE)。源文件头保留 m417z 和 Windhawk 上游来源。 |
| Windhawk 附带的编译器运行库 | 随包保留 Windhawk 上游编译器/运行时分发许可，位于 `runtime/Windhawk/Notices`。 |
| 砺行 Compose 组件 | 见发布源码中的 `vendor/lixing-components/LICENSE`。用于构建此版本的 Compose 接口与资源同随源码快照提供。 |
| 砺行通用更新器 | 源于自研 SimRacingOverlay 更新模块。随 `vendor/lixing-update` 提供固定源码，沿用上述 [砺行组件许可证](vendor/lixing-components/LICENSE)，下列 Node.js 保持上游授权。 |
| Node.js 更新运行时 | 官方 Windows x64 二进制。`NODE-LICENSE.txt` 随更新组件提供，具体版本及官方摘要锁定在 `vendor/lixing-update/package-sidecar.py`。 |
| Java 运行时 | 包含由 JDK 21 生成的运行时，许可证与各模块第三方声明位于应用内 `app/runtime/legal`。 |
| Kotlin、Compose Multiplatform、AndroidX、Skiko、JSON-java 等库 | JAR 保留上游随库的许可证及声明，对应依赖和版本可从该版本的 Gradle 定义、运行库名称以及 [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform)、[Kotlin](https://github.com/JetBrains/kotlin)、[AndroidX](https://github.com/androidx/androidx)、[Skiko](https://github.com/JetBrains/skiko) 和 [JSON-java](https://github.com/stleary/JSON-java) 查询。 |
| 收款码、赞赏码及支付标志 | 支持页按授权提供源资产。支付服务和其标志归各自权利方。 |

GPL 模块可按原许可重建和替换。本安装包的自有代码许可不限制对应 GPL 组件已经授予的权利。
