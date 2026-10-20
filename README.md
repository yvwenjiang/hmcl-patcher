# hmcl-patcher

自动为**已安装**的 HMCL 注入补丁，解除 3.17.0.362 起加入的“中国大陆地区禁止下载游戏内容”限制。
支持 Linux 与 Windows，支持 HMCL 的全部三种发行形态：

| 文件 | 说明 |
| --- | --- |
| `HMCL-3.17.0.362.jar` | 通用 jar，需要系统里已有 Java |
| `HMCL-3.17.0.362.exe` | Windows 版：原生启动头 + 同一个 jar（已实测支持） |
| `HMCL-3.17.0.362.sh` | Linux 版：shell 启动头 + 同一个 jar |

## 免责条款

1. 本工具仅供个人学习、研究使用，只修改你本机上的 HMCL 副本。
2. 本工具与 HMCL 官方（HMCL-dev）无关，未经其授权或认可。
3. 修改后的 HMCL 会失去官方签名，被识别为“非官方构建”：自动更新被禁用，登录微软账号时会显示提示，其它功能不受影响。
4. 使用本工具可能违反你所在地区的法律法规或相关服务条款，请自行判断并承担全部风险。
5. 本工具按“现状”提供，不提供任何明示或暗示的担保；因使用本工具造成的任何直接或间接损失（包括但不限于数据丢失、账号异常、无法更新）均由使用者自行承担。
6. 请在使用前自行备份原始文件；继续使用即表示你已阅读、理解并同意以上全部条款。

命令行每次运行都会打印以上条款；窗口模式必须勾选“我已阅读并同意以上免责条款”才能点击“开始修复”。

## 1. 原理

### 1.1 HMCL 的限制是怎么实现的

`v3.17.0.361 → v3.17.0.362` 只有一个提交 `583e004d7 在中国大陆地区禁用游戏下载功能 (#7017)`。
其中新增的 `org.jackhuang.hmcl.util.RegionUtils` 提供两个判定入口：

- `isIsChinaMainlandMachine()`：根据时区（`Asia/Shanghai`）、语言国家代码、Windows `GetUserGeoID`
  判断“机器在中国大陆”。镜像下载优选等既有逻辑用的就是它。
- `isIsChinaMainlandNetwork()`：在机器判定之上再请求 `https://ipwho.is`，`country_code == "CN"`
  视为大陆网络，**请求失败也按大陆处理**。

下载页 `VersionsPage` / `DownloadListPage` 在 `isIsChinaMainlandNetwork()` 为真时，把整个页面
`setDisable(true)` 并加上 `download.china` 警告，于是无法下载。

### 1.2 本工具做了什么

工具不重新打包 HMCL 源码，而是直接改写 jar 里的一个 class 文件：

- 只改 `org/jackhuang/hmcl/util/RegionUtils.class` 中 `isIsChinaMainlandNetwork()Z` 的
  `Code` 属性，把它替换为 `iconst_0; ireturn`（即 `return false;`）；
- 常量池、字段、其它方法、其它 class、资源文件全部**原样复制**；
- 因为不解析原方法体，所以**与 HMCL 版本无关**：只要类里有这个方法（362 及之后）就能打补丁；
- 补丁后不再访问 `ipwho.is`，也就没有额外的网络请求。

`.exe` / `.sh` 是“启动头 + jar”的自解压结构（HMCL 的 `ExecutableHeaderHelper` 就是这么生成的），
工具通过 zip 中央目录反推出启动头长度，只替换其后的 jar，头部与原文件逐字节一致，
Linux 上还会保留可执行权限。

## 2. Windows 专项：更智能的查找与修复

### 2.1 多路查找

自动扫描会依次尝试：

1. `%LOCALAPPDATA%`、`%APPDATA%`、`%ProgramFiles%`、`%ProgramFiles(x86)%`、桌面、下载、用户目录；
2. **快捷方式**：桌面、开始菜单、任务栏固定项、最近使用（`%APPDATA%\Microsoft\Windows\Recent`）中
   名字含 `HMCL` 的 `.lnk`，用 PowerShell 解析出真实目标；
3. **注册表**：`HKCU/HKLM\...\Uninstall` 里的 `DisplayIcon` / `InstallLocation`；
4. **深度扫描**（`--deep` 或窗口里的“深度扫描”按钮）：遍历所有固定磁盘（限制深度，自动跳过
   系统目录、缓存目录等）。

### 2.2 找不到时：拖进窗口即可

自动扫描没有结果时，工具会**自动打开窗口**（也可用 `--gui` 手动打开）。窗口支持：

- 把 HMCL 的 `exe` / `jar` / `sh` **直接拖进窗口**，或点击“选择文件…”；
- 显示识别到的版本与状态（`待打补丁` / `已打补丁` / `无此限制`）；
- 一键“开始修复”“还原备份”“深度扫描”；
- 全部操作在后台线程执行，不会卡住界面。

Windows 上双击 `patch.bat`（或没有终端时直接运行）都会进入窗口模式。

### 2.3 更聪明的写入

- HMCL 正在运行时先给出警告（Windows 上 exe 被占用会写入失败）；
- 写入失败会自动重试 3 次（给用户时间关闭 HMCL）；
- 仍然没有写权限（例如放在 `Program Files`）时，会在同目录（不可写则在家目录）生成
  `HMCL-<版本>-patched.exe`，并在日志/命令行里告诉你改用这个副本；
- 写入采用同目录临时文件 + 原子替换，失败不会留下半个文件。

## 3. 代码混淆

发布用的 `build/libs/hmcl-patcher.jar` 由 **ProGuard 7.10** 处理：

- 所有非入口类 / 字段 / 方法重命名为 `a`、`b`、`c` …；
- 去掉行号、局部变量等调试信息，`SourceFile` 重命名为 `SourceFile`；
- 只保留入口类 `hmcl.patcher.Main` 的类名和 `main` 方法（jar 清单需要）。

映射表输出在 `build/obfuscation/mapping.txt`（仅本地调试用，不随产物发布）。
未混淆的调试版本位于 `build/libs/hmcl-patcher-dev.jar`。

构建命令：

```bash
./gradlew build          # Linux / macOS，产物 build/libs/hmcl-patcher.jar（已混淆）
gradlew.bat build        # Windows
./gradlew obfuscate      # 只重新执行混淆
```

ProGuard 只依赖 Maven Central 上的 `com.guardsquare:proguard-base`，首次构建需要联网下载。

## 4. 用法

### 4.1 自动注入（推荐）

```bash
./scripts/patch.sh            # Linux / macOS
scripts\patch.bat             # Windows
```

不带路径时自动扫描；找到就逐个注入并生成 `<原文件名>.hmclbak` 备份，找不到就打开窗口让你拖文件进去。

### 4.2 指定文件

```bash
java -jar build/libs/hmcl-patcher.jar -y ~/Downloads/HMCL-3.17.0.362.jar
java -jar build/libs/hmcl-patcher.jar -y "C:\Users\me\Desktop\HMCL-3.17.0.362.exe"
java -jar build/libs/hmcl-patcher.jar -y /usr/share/java/hmcl   # 目录会被递归扫描
```

### 4.3 其它选项

```
-l, --list       仅列出检测到的 HMCL 及补丁状态
    --verify     同 --list
    --restore    从备份（*.hmclbak）还原（可与路径一起使用）
-n, --dry-run    只检查不写入
-y, --yes        不询问直接执行
    --deep       深度扫描整机（Windows 扫描所有固定磁盘）
    --gui        强制打开图形窗口（支持拖放文件）
-h, --help       帮助
```

还原示例：

```bash
./scripts/patch.sh --restore            # 还原所有带备份的安装
./scripts/patch.sh --restore ~/HMCL-3.17.0.362.exe
```

`.deb` 安装包本身不是 zip，工具会提示先安装再修补 `/usr/share/java/hmcl/*.sh`（可能需要 `sudo`）。

## 5. 副作用与注意事项

HMCL 会对自身 jar 做 RSA 签名校验（`META-INF/hmcl_signature`）。jar 一旦被修改，签名必然失效，
HMCL 会把自己识别为“非官方构建”：

- **自动检查更新被禁用** —— 这同时也保证了补丁不会被自动更新覆盖；
- **登录微软账号时会显示“非官方构建”提示**，登录功能本身不受影响；
- 其它功能（启动游戏、下载、模组管理等）不受影响。

需要恢复官方状态时用 `--restore` 还原备份（备份是逐字节的原始文件）。另外：

- 手动重新下载 / 升级 HMCL 后补丁会丢失，重新运行一次工具即可；
- 发行包旁边的 `.sha256` 校验文件不会再匹配，属于预期现象；
- 打补丁前请先退出正在运行的 HMCL（Windows 上文件占用会导致写入失败）。

## 6. 实现要点

| 文件 | 职责 |
| --- | --- |
| `Main.java` | 命令行解析、自动扫描、批量处理、还原、免责条款、窗口模式入口 |
| `Gui.java` | Swing 窗口：拖放、文件选择、修复/还原/深度扫描、日志 |
| `InstallationScanner.java` | 按平台扫描安装位置，识别版本与补丁状态，深度扫描 |
| `WindowsLocator.java` | Windows：快捷方式解析、注册表、固定磁盘 |
| `SystemProbe.java` | 平台判断、检测 HMCL 是否在运行 |
| `HmclArchive.java` | 拆解 `jar/exe/sh`（zip 中央目录反推启动头长度）、重打包、原子写入、权限保留 |
| `ClassFilePatcher.java` | 极简 class 文件编辑器：定位方法、替换 `Code` 属性，无第三方依赖 |
| `Patcher.java` | 流程编排：校验 → 替换 → 重打包 → 双重校验 → 备份 / 重试 / 生成副本 |
| `Disclaimer.java` | 免责条款文本 |

## 7. 验证记录

在 v3.17.0.362 官方发行版上实测：

- 原始 jar，`-Duser.timezone=Asia/Shanghai` 且 `ipwho.is` 不可达 → `isIsChinaMainlandNetwork()` 返回 `true`（下载被锁）；
- 打完补丁的 jar，同样条件 → 返回 `false`，且不再发起任何网络请求（70ms 内返回）；
- **Windows 版 `HMCL-3.17.0.362.exe`**：454144 字节启动头逐字节不变、仍以 `MZ` 开头、
  内嵌 jar 的 class 被正确替换、`unzip -t` 校验通过；
- `.sh`（`HMCLauncher.sh` + jar）：启动头逐字节相同、可执行权限保留；
- 只读目录场景：自动降级为 `HMCL-<版本>-patched.exe` 副本；
- `--restore` 还原后的文件与原始文件逐字节一致；
- 混淆后的 jar 通过 `java -jar` 正常运行并完成上述全部操作。

## 8. 许可证

本工具以 GPL-3.0 发布，与 HMCL 保持一致。它只修改你本机上的 HMCL 副本，供个人使用；
分发修改后的 HMCL 时请遵守 HMCL 的 GPLv3 许可并保留其源代码。
