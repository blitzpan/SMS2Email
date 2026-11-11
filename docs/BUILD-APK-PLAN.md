# SMS2Email 命令行构建指南（无需 Android Studio）

> **目标**：在一台装有 **JDK 17** 的机器上，仅用命令行下载**最小 Android SDK** 与依赖，编译出可直接安装到手机的 **debug APK**，全程不安装 Android Studio、不改动项目构建配置。
>
> **实测产物**：`app/build/outputs/apk/debug/SMS2Email-v1.1.40-debug.apk`（约 22 MB，由 AGP 自动 debug 签名）。
> **磁盘预留**：约 **2 GB**（SDK ~560 MB + Gradle 缓存与构建产物 ~1.3 GB）。
> **适用系统**：Windows（命令为 PowerShell）；Linux/macOS 将路径及 `sdkmanager` / `apksigner` 调用方式相应调整即可。
> 项目已自带 `gradlew` / `gradlew.bat`，无需另行安装 Gradle。

---

## 1. 前提条件

| 项 | 要求 | 检查命令 |
| --- | --- | --- |
| JDK | 17+（AGP 9.5 要求 ≥ 17） | `java -version` |
| 磁盘 | ≥ 2 GB 空闲 | `Get-PSDrive` |
| 网络 | 能直连 `dl.google.com` / `maven.google.com` 最佳；否则准备 **SOCKS5** 代理 | 见 §4.2 |

---

## 2. 安装最小 Android SDK（命令行，无需 Android Studio）

把 SDK 装到任意目录（下文以 `D:\android-sdk` 为例）。

```powershell
$sdk = 'D:\android-sdk'

# 1) 下载并解压 command-line tools
curl.exe -L -o "$sdk\cmdline-tools.zip" https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip
Expand-Archive "$sdk\cmdline-tools.zip" -DestinationPath "$sdk\cmdline-tools" -Force
Move-Item "$sdk\cmdline-tools\cmdline-tools" "$sdk\cmdline-tools\latest"
Remove-Item "$sdk\cmdline-tools.zip"
$sm = "$sdk\cmdline-tools\latest\bin\sdkmanager.bat"

# 2) 写入许可（sdkmanager 在管道/非交互下无法接受许可，需手写官方 hash）
New-Item -ItemType Directory -Force "$sdk\licenses" | Out-Null
Set-Content "$sdk\licenses\android-sdk-license" '24333f8a63b6825ea9c5514f83c2829b004f35755072011baa491e2f366b05bc5'
Set-Content "$sdk\licenses\android-sdk-preview-license" '84831b9409646a918e30573bab4c9c91346d8abd98833d3a42a4510f1d34c3e3'

# 3) 安装编译所需组件（API 37 平台、build-tools 36.0.0、platform-tools）
& $sm "platforms;android-37.0" "build-tools;36.0.0" "platform-tools"

# 4) 校验
& $sm --list_installed
```

> `platforms;android-37.0` 即 `compileSdk = release(37)` 所需的正式平台，无需 `android-37`，也不会因此补包。

---

## 3. 项目配置

### 3.1 `local.properties`（本机文件，已被 `.gitignore`，不入库）

```
sdk.dir=D\:\\android-sdk
```

### 3.2 Gradle 分发包

`gradle/wrapper/gradle-wrapper.properties` 已指向**腾讯云镜像**的 Gradle 9.9.0（满足 AGP 9.5 的最低 9.6 要求且可直连；官方 9.9.1 会 307 跳转 GitHub，国内不通）：

```
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-9.9.0-bin.zip
```

> 若环境能直连官方源，也可改回 `https://services.gradle.org/distributions/gradle-9.9.1-bin.zip`。

---

## 4. 依赖解析（关键坑与对策）

### 4.1 直接构建

```powershell
.\gradlew.bat assembleDebug --console=plain
```

多数情况下上述命令即可完成；以下仅在网络受阻时按需处理。

### 4.2 网络代理（当直连 `plugins.gradle.org` / Maven Central 超时）

经实测，本地代理 `127.0.0.1:10808` 是 **SOCKS5**（HTTP 方式返回空），且 Gradle **不会把命令行 `-Dhttps.proxyHost` 注入到 daemon JVM 的启动参数**，导致代理形同虚设、构建在插件仓库处静默挂起。正确做法是用 `JAVA_TOOL_OPTIONS`，让**每个 JVM 在启动期**就带上代理与超时；同时用 `nonProxyHosts` 让 Google / Maven Central **直连**（否则 `dl.google.com` 经 SOCKS 会 TLS 握手终止、Gradle 禁用整个 Google 仓库），仅插件门户走代理：

```powershell
$np = 'localhost|127.*|[::1]|*.google.com|dl.google.com|maven.google.com|repo.maven.apache.org|repo1.maven.org|mirrors.cloud.tencent.com'
$env:JAVA_TOOL_OPTIONS = "-DsocksProxyHost=127.0.0.1 -DsocksProxyPort=10808 -Dhttp.nonProxyHosts=$np -DsocksNonProxyHosts=$np -Dorg.gradle.internal.http.connectionTimeout=30000 -Dorg.gradle.internal.http.socketTimeout=60000"
.\gradlew.bat assembleDebug --console=plain
```

> **关键**：必须在**启动 `gradlew` 的同一 shell / 同一进程树**中设置 `JAVA_TOOL_OPTIONS`，否则子进程不继承该变量。

### 4.3 AboutLibraries 插件的网络检查会挂起

`com.mikepenz.aboutlibraries` 插件在执行 `prepareLibraryDefinitionsDebug` 时会请求 GitHub `rate_limit` API，在受限网络下会挂起，进而卡死整个构建。项目已附带关闭该网络检查的 init script（`docs/aboutlibraries-offline.init.gradle`），通过 `-I` 传入即可，**不改任何构建配置**：

```powershell
.\gradlew.bat assembleDebug --console=plain -I "docs\aboutlibraries-offline.init.gradle"
```

> 该插件仅用于生成「开源许可」列表，离线模式仅缺许可证文本，不影响 APK 与签名。

### 4.4 进度可见性与避免卡顿

构建命令已带 `--console=plain`，Gradle 会实时向终端输出各任务进度，**不会静默卡死**。若某个仓库长时间无响应（例如插件门户或 GitHub 请求挂起），按 §6 处理（代理 / `nonProxyHosts` 分流 / AboutLibraries 离线）。需要强制退出时直接 `Ctrl+C`，或 `.\gradlew.bat --stop` 终止 daemon。

---

## 5. 产物与验证

| 项 | 路径 / 命令 |
| --- | --- |
| APK | `app/build/outputs/apk/debug/SMS2Email-v1.1.40-debug.apk` |
| 签名校验 | `<sdk>\build-tools\36.0.0\apksigner.bat verify --print-certs <apk>`（应含 `Signer #1 ... CN=Android Debug`） |
| 安装到手机 | `<sdk>\platform-tools\adb.exe install -r <apk>` |

Debug APK 由 AGP 首次构建自动生成 `~/.android/debug.keystore` 并签名，无需自行配置。

---

## 6. 常见问题排查

| 现象 | 原因 | 解决 |
| --- | --- | --- |
| 卡在 `plugins.gradle.org`、长时间无日志 | 代理未注入 daemon / 插件仓库响应挂起 | 用 §4.2 的 `JAVA_TOOL_OPTIONS` 方式，而非 `-Dhttps.proxyHost` |
| `Repository Google is disabled` | `dl.google.com` 经 SOCKS TLS 握手终止 | 在 `nonProxyHosts` 中加入 `*.google.com｜dl.google.com｜maven.google.com` 走直连 |
| 卡在 `prepareLibraryDefinitionsDebug` | AboutLibraries 请求 GitHub 挂起 | 加 `-I docs\aboutlibraries-offline.init.gradle` |
| `Could not GET ... gradle-9.9.1` / 307 跳 GitHub | 官方分发源被墙 | 保持 wrapper 的腾讯云镜像 9.9.0（§3.2） |
| 重新构建仍走旧参数 | 复用了旧的 Gradle daemon | 先 `.\gradlew.bat --stop`，或加 `--no-daemon` |

---

## 7. 资源占用与时间参考（实测）

| 项 | 体积 |
| --- | --- |
| Android SDK（命令行组件） | ~560 MB |
| Gradle 缓存（`~/.gradle/caches`） | ~1.08 GB |
| 其余（wrapper dists、app/build、APK） | ~245 MB |
| **合计** | **~1.84 GB** |

| 阶段 | 耗时 |
| --- | --- |
| SDK 安装 | 数分钟 |
| 依赖下载 + 编译 + 打包 | 首次约 **2 分钟**（依赖已预热）；冷启动视网络 8–25 分钟 |
| 二次增量 | 0.5–3 分钟 |

---

## 8. 清理与回滚

```powershell
# 删除 SDK 与全部 Gradle 缓存（释放约 1.8 GB）
Remove-Item D:\android-sdk -Recurse -Force
Remove-Item "$env:USERPROFILE\.gradle\caches" -Recurse -Force
Remove-Item "$env:USERPROFILE\.gradle\wrapper\dists" -Recurse -Force
Remove-Item app\build -Recurse -Force
# 项目配置恢复（如需）
git checkout -- gradle/wrapper/gradle-wrapper.properties
Remove-Item local.properties -Force -ErrorAction SilentlyContinue
```

> 本指南未改动 `app/build.gradle.kts`、`settings.gradle.kts`、`gradle.properties`；仅 `gradle-wrapper.properties`（指向镜像）与 `local.properties`（本机 SDK 路径）为构建所需配置。
