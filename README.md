# hmcl-patcher

自动为**已安装**的 HMCL 注入补丁，解除 3.17.0.362 起加入的“中国大陆地区禁止下载游戏内容”限制。
支持 Linux 与 Windows，支持 HMCL 的全部三种发行形态：

| 文件 | 说明 |
| --- | --- |
| `HMCL-3.17.0.362.jar` | 通用 jar，需要系统里已有 Java |
| `HMCL-3.17.0.362.exe` | Windows 版：原生启动头 + 同一个 jar |
| `HMCL-3.17.0.362.sh` | Linux 版：shell 启动头 + 同一个 jar |

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

## 2. 用法

### 2.1 构建

需要 JDK 17+：

```bash
./gradlew build          # Linux / macOS
gradlew.bat build        # Windows
```

产物：`build/libs/hmcl-patcher.jar`

也可以直接用 JDK 编译（不依赖 Gradle）：

```bash
mkdir -p build/classes
javac -encoding UTF-8 --release 17 -d build/classes $(find src/main/java -name '*.java')
mkdir -p build/libs
jar --create --file build/libs/hmcl-patcher.jar --main-class hmcl.patcher.Main -C build/classes .
```

### 2.2 自动注入（推荐）

```bash
./scripts/patch.sh            # Linux / macOS
scripts\patch.bat             # Windows
```

不带路径时自动扫描常见安装位置：

- Windows：`%LOCALAPPDATA%`、`%APPDATA%`、`%ProgramFiles%`、`%ProgramFiles(x86)%`、桌面、下载、用户目录
- Linux：用户目录、桌面、下载、`~/Applications`、`~/.local/share`、`/opt`、`/usr/share/java/hmcl`
  （deb 安装位置）、`/usr/local/share/java/hmcl`

扫描会校验文件确实是 HMCL（含 `org/jackhuang/hmcl/Main.class`），列表里会显示版本与状态
（`待打补丁` / `已打补丁` / `无此限制`），确认后逐个注入，并生成 `<原文件名>.hmclbak` 备份。

### 2.3 指定文件

```bash
java -jar build/libs/hmcl-patcher.jar -y ~/Downloads/HMCL-3.17.0.362.jar
java -jar build/libs/hmcl-patcher.jar -y 'C:\Users\me\Desktop\HMCL-3.17.0.362.exe'
java -jar build/libs/hmcl-patcher.jar -y /usr/share/java/hmcl   # 目录会被递归扫描
```

### 2.4 其它选项

```
-l, --list       仅列出检测到的 HMCL 及补丁状态
    --verify     同 --list
    --restore    从 * .hmclbak 还原（可与路径一起使用）
-n, --dry-run    只检查不写入
-y, --yes        不询问直接执行
-h, --help       帮助
```

还原示例：

```bash
./scripts/patch.sh --restore            # 还原所有带备份的安装
./scripts/patch.sh --restore ~/HMCL-3.17.0.362.exe
```

`.deb` 安装包本身不是 zip，工具会提示先安装再修补 `/usr/share/java/hmcl/*.sh`（可能需要 `sudo`）。

## 3. 副作用与注意事项

HMCL 会对自身 jar 做 RSA 签名校验（`META-INF/hmcl_signature`）。jar 一旦被修改，签名必然失效，
HMCL 会把自己识别为“非官方构建”：

- **自动检查更新被禁用** —— 这同时也保证了补丁不会被自动更新覆盖；
- **登录微软账号时会显示“非官方构建”提示**，登录功能本身不受影响；
- 其它功能（启动游戏、下载、模组管理等）不受影响。

如果需要恢复官方状态，使用 `--restore` 还原备份即可（备份是逐字节的原始文件）。

另外：

- 手动重新下载 / 升级 HMCL 后，补丁会丢失，重新运行一次工具即可；
- 发行包旁边的 `.sha256` 校验文件不会再匹配，属于预期现象；
- 请先退出正在运行的 HMCL 再打补丁（Windows 上文件占用会导致写入失败，工具会提示）。

## 4. 实现要点

| 文件 | 职责 |
| --- | --- |
| `Main.java` | 命令行解析、自动扫描、批量处理、还原、提示 |
| `InstallationScanner.java` | 按平台扫描常见安装位置并识别 HMCL 版本/补丁状态 |
| `HmclArchive.java` | 拆解 `jar/exe/sh`（zip 中央目录反推启动头长度）、重打包、原子写入、权限保留 |
| `ClassFilePatcher.java` | 极简 class 文件编辑器：定位方法、替换 `Code` 属性，无第三方依赖 |
| `Patcher.java` | 流程编排：校验 → 替换 → 重打包 → 内存/落盘双重校验 → 备份 |

写入是原子的（同目录临时文件 + `ATOMIC_MOVE`），失败不会留下半个文件。

## 5. 验证记录

在 v3.17.0.362 官方发行版上实测：

- 原始 jar，`-Duser.timezone=Asia/Shanghai` 且 `ipwho.is` 不可达 → `isIsChinaMainlandNetwork()` 返回 `true`（下载被锁）；
- 打完补丁的 jar，同样条件 → 返回 `false`，且不再发起任何网络请求（70ms 内返回）；
- `.sh`（`HMCLauncher.sh` + jar）：补丁前后前 6125 字节启动头逐字节相同，jar 内的 class 被正确替换，
  可执行权限保留，`unzip -t` 校验通过；
- `--restore` 还原后的文件与原始文件逐字节一致。

## 6. 许可证

本工具以 GPL-3.0 发布，与 HMCL 保持一致。它只修改你本机上的 HMCL 副本，供个人使用；
分发修改后的 HMCL 时请遵守 HMCL 的 GPLv3 许可并保留其源代码。
