# SillyTavern Android 客户端（WebView + 内嵌 Node）设计

日期：2026-11-02
状态：待评审

## 1. 意图与缘由

Luminthalia 想在 Android 手机上使用 SillyTavern，不依赖电脑：角色卡、聊天记录、世界书、预设都存在手机本地，App 自己启动服务，WebView 显示现有界面。界面暂不重写，等以后再考虑原生 UI。

成功的标准：装一个 APK，首次打开能进 SillyTavern 界面，能建角色、导入角色卡、聊天、重启 App 后数据还在。日常使用不需要电脑，不需要命令行。

## 2. 目标与非目标

**目标（v1）**

1. 单 APK，侧载安装，启动即用，无需 Termux 或任何额外 App。
2. 内嵌 Node 运行时启动 SillyTavern 服务，只监听 `127.0.0.1`。
3. WebView 加载本机页面，现有功能全部可用。
4. 数据存 App 私有目录，卸载即清理，不写公共存储。
5. 进程在后台时服务不被杀（前台服务 + 常驻通知）。
6. GitHub Actions 出包，本机不构建。

**v1 范围已确认（2026-11-02）**：做聊天、角色卡、预设、世界书、主题、导入导出、扩展安装；不做 TTS、图像生成、OAuth 登录、扩展更新、电脑数据迁移。

**非目标（v1 明确不做）**

- Kotlin/Compose 重写界面。
- Play Store 上架（涉及 16 KB 页对齐、隐私声明等）。
- 手机端跑本地大模型。
- 多用户/远程访问/局域网共享。
- 自动更新 App 或自动更新 SillyTavern。

**反目标（技术成功但算失败）**

- 需要用户自己装 Node、开终端、改配置文件。
- 服务监听 `0.0.0.0`，同网段任何人可访问。
- 数据放在公共 Downloads 目录，卸载后残留一堆文件。
- 每次启动在手机上现场编译前端库（耗时、耗内存、可能被系统杀）。

## 2.1 目标设备（已确认）

- **OPPO Find X3 Pro（PEEM00）**，Android **14**，arm64-v8a。
- Android System WebView **156.0**（Chromium 156），远高于 `toSorted` 需要的 110。
- Android 14 使用 4 KB 页，**16 KB 页对齐风险不适用于本机**；`libnode.so` 的 4 KB 对齐可以直接加载。
- 因此 v1 **只出 arm64-v8a**，不做 x86_64（省 65 MB，模拟器验收改为真机验收）。

## 3. 约束与已核实的事实

- 本机无 Android SDK，内存 1.9 GB，无法本地构建；构建放 GitHub Actions（与 RikkaHub 相同模式）。
- SillyTavern v1.19.0，commit `06bde939f`，`type: module`（ESM），无原生依赖，数据为 JSON 文件。后端 `server.js` + `src/` 约 8801 行，48 个接口模块；前端 203 个 JS 文件、约 14.6 万行，单个 `index.html`。
- `engines.node >= 20` 只是 npm 提示，代码未强制；唯一 Node 20 API 是 `src/server-directory.js` 的 `import.meta.dirname`，已有 `?? path.dirname(fileURLToPath(...))` 兜底。
- **已实测**：用 Node **18.20.4** 跑 `server.js --port 8123 --configPath /tmp/... --dataRoot /tmp/...`，跳过前端库编译后，服务正常监听 `127.0.0.1:8123`，`GET /` 返回 200，RSS 约 **158 MB**。
- **已实测**：不跳过编译时，首次启动会跑 webpack 打包 `public/lib.js`（lodash、highlight.js、DOMPurify 等），在本机 1.9 GB 环境被系统杀掉，日志停在 `Compiling frontend libraries...`。∴ 前端库必须在 CI 预编译，不能放到手机上做。
- `nodejs-mobile-react-native` npm 包 18.20.4 提供 `libnode.so`：arm64-v8a 62 MB、armeabi-v7a 59 MB、x86_64 65 MB，含 Node 头文件；JNI Java 封装是 React Native 专用，需自己写一层启动器。
- **16 KB 页对齐已核实**：arm64 `libnode.so` 的 4 个 `PT_LOAD` 段对齐全是 `0x1000`，没有 `0x4000`。Android 15+ 的 16 KB 页设备会拒绝加载，目标机型必须先确认。
- 前端用到 `toSorted`/`toReversed`（`bookmarks.js`、`world-info.js`、`power-user.js` 等 8 个文件），需要 WebView 的 Chromium ≥ 110。
- 扩展安装走 `src/git/client.js` 的 `isomorphic-git`（纯 JS），**不依赖系统 git**；只有 update/branches/switch 需要系统 git。
- 服务端零 `child_process` 调用，不会因 Android 无 shell 而崩；但扩展的 git 安装/更新依赖 git 二进制，必须禁用。
- SillyTavern 已有 CLI 开关：`--listen false`（默认）、`--browserLaunchEnabled false`（默认）、`--configPath`、`--dataRoot`、`--port`。

## 4. 方案选择

**① 内嵌 Node 18 + WebView（已选定）**
预编译 `libnode.so` 跑在 App 进程内，WebView 访问 `127.0.0.1`。功能全保留，工作量集中在打包、JNI 启动器、生命周期和文件交互。

**② 自行交叉编译 Node 22/24 for Android**
版本对齐，但需 NDK 全量编译，数小时起步，产物数百 MB，每次升级 Node 重来。作为 ① 在 16 KB 页设备上加载失败时的兜底。

**③ Kotlin 重写后端**
否。8800 行后端加大量隐式行为，等于重写 SillyTavern，且永远追不上上游。

选 ① 的理由：唯一能在合理时间内交付「装个 APK 就能用、功能不缺」的路线。

## 5. 架构

### 5.1 仓库布局

项目根 `/mnt/data1/SillyTavern-Android`（新 git 仓库，推 GitHub 私有/公开待定）：

```
SillyTavern-Android/
├── SillyTavern/              # 上游 v1.19.0，git subtree；本地补丁作为其上的提交
├── android/                  # Gradle 工程
│   ├── settings.gradle.kts
│   ├── app/                  # Kotlin 应用模块
│   └── nodejs-mobile/        # 构建时下载 libnode.so，不入库
├── scripts/
│   ├── stage-runtime.sh      # 装依赖、预编译 lib.js、打 runtime.zip
│   └── smoke-node18.sh       # CI 里用 Node 18 启动并断言 200
├── .github/workflows/android.yml
└── docs/superpowers/specs/
```

`SillyTavern/` 用 subtree 引入，保留上游历史；我们的改动是子树之上的普通提交，便于 `git subtree pull` 同步上游后重放/修正补丁。`libnode.so`（62 MB）与 `node_modules` 不入库，CI 下载/安装。

### 5.2 运行时启动顺序

1. **首启解压**：`assets/runtime.zip`（服务端 JS + `node_modules` 生产依赖 + 预编译 `lib.js` + 已播种的 `seed/` 数据骨架）解压到 `filesDir/runtime/`，写入版本标记；App 升级且标记不一致时重新解压（`data/` 不动）。首次启动若 `data/` 不存在，把 `seed/` 整体复制为 `data/`。
2. **选端口**：绑定 `127.0.0.1:0` 取空闲端口（Java 侧 `ServerSocket(0)` 取号后关闭），避免与其它 App 冲突。
3. **JNI 启动 Node**：
   `args = ["node", "<filesDir>/runtime/server.js", "--port", "<P>", "--listen", "false", "--configPath", "<filesDir>/config.yaml", "--dataRoot", "<filesDir>/data", "--browserLaunchEnabled", "false"]`
   环境变量 `NODE_ENV=production`、`ST_SKIP_WEBPACK=1`（补丁 P1）。
4. **等待就绪**：轮询 `http://127.0.0.1:<P>/` 直到 200，最长 30 s；超时显示错误页与日志入口。
5. **WebView 加载**：`http://127.0.0.1:<P>/`。开启 JavaScript、DOM Storage、Database；`usesCleartextTraffic` 仅对 `127.0.0.1` 放行（network security config）。
6. **退出**：`onTaskRemoved` 时停止 Node、停前台服务；返回键优先 `WebView.canGoBack()`。

### 5.3 生命周期与后台

- 前台服务（`foregroundServiceType="specialUse"`，Android 14+ 需声明用途）在 App 进入后台时保活进程；通知文案「SillyTavern 正在运行」，点击回到 App。
- 用户主动「退出」才停服务；从最近任务划掉（`onTaskRemoved`）也停，避免偷偷占内存。
- 省电策略：不做开机自启，不做后台长驻（无聊天任务时不需要）。

### 5.4 数据与文件

- `dataRoot = filesDir/data`，与 `runtime/` 分离，升级只换 runtime。
- 首启生成 `config.yaml`，写入安全默认：`listen: false`、`whitelistMode: false`（仅本机）、`basicAuthMode: false`、`browserLaunch.enabled: false`、`skipContentCheck: true`、`securityOverride: false`。
- **`skipContentCheck: true` 只有在 `data/` 已预置时才成立**：它会让 `checkForNewContent` 直接返回，`default/content/settings.json` 不会被复制，随后 `/api/settings/get` 500，前端进 `reloadLoop`。所以数据骨架必须在 CI 里播种好，不能指望手机首启自己去复制。
- **导入/导出（v1 必须，否则数据等于锁死）**：设置页提供「导出数据」（打包 `data/` + `config.yaml` 为 zip，经 SAF 存到用户选择的位置）与「导入数据」（选 zip，校验结构后覆盖，需二次确认）。这两项由 Android 侧实现，不改前端。
- 角色卡导入（`.png`/`.json`）走 WebView `onShowFileChooser`；聊天导出走 `DownloadListener` + SAF/MediaStore。

### 5.5 对上游的补丁（尽量少，逐条可回退）

| 编号 | 改动 | 原因 |
|---|---|---|
| P1 | `server-main.js`：`ST_SKIP_WEBPACK=1` 时跳过 `runWebpackCompiler`，直接使用预编译产物 | 手机上编译会被杀（已实测） |
| P2 | `package.json` `engines.node` 改为 `>= 18` | 避免 npm 警告与误导 |
| P3 | `ST_MOBILE=1` 时只禁用 update/branches/switch 等依赖系统 git 的扩展操作，安装保留（走 isomorphic-git） | 一刀切会砍掉本可用的功能 |
| P4 | 首启写入上述安全默认配置（可在 `config-init` 或 Android 侧生成，倾向 Android 侧） | 不污染上游逻辑 |

补丁总量目标 < 80 行。若某补丁超过 40 行，重新考虑是否改用 Android 侧注入。

### 5.6 构建流水线

GitHub Actions（ubuntu-latest，7 GB 内存，预装 Android SDK）：

1. checkout（含 subtree）。
2. Node 20：`npm ci --omit=dev`（装生产依赖），再单独装 webpack 所需 devDeps 用于预编译。
3. **预编译 + 播种一次完成**：用 Node 20 以临时 `dataRoot` 启动一次完整服务（不跳过 webpack），产出 `lib.js` 并把 `default/content` 播种进 `data/default-user`；跑通后停掉。
4. **兼容性冒烟**：下载 Node 18.20.4 Linux 版，对着第 3 步产出的目录以 `ST_SKIP_WEBPACK=1` 启动，断言 `GET /` 为 200 且 `/api/settings/get` 不为 5xx，失败即中止。
5. 打包 `runtime.zip`（服务端代码 + 生产 `node_modules` + 预编译 lib + `seed/` = 第 3 步的 data 骨架）。
6. 下载 `nodejs-mobile-react-native@18.20.4`，取 `arm64-v8a/libnode.so` 放入 `jniLibs/`。
7. `./gradlew assembleDebug`（minSdk 26、targetSdk 35、compileSdk 35、AGP 8.7、Kotlin 2.0、JDK 17）。
8. 上传 APK artifact；打 tag 时附到 GitHub Release（沿用 RikkaHub 的发版方式）。

预估：arm64 APK 约 110–140 MB（`libnode.so` 62 MB 不压缩 + runtime 压缩后约 40–60 MB）。

### 5.7 包名与应用名（我的提议，可改）

- `applicationId = com.sol.sillytavern`，debug 后缀 `.debug`，可与任意其他版本共存。
- 应用名 `SillyTavern Sol`，图标沿用上游 `public/favicon` 或 logo。

### 5.8 签名与版本（数据安全的硬要求）

- **固定签名**：仓库提交一个专用 debug keystore（密码与 alias 公开，因为仅用于侧载调试），所有构建共用。GitHub Runner 每次临时生成的 keystore 会导致升级时必须卸载，卸载即清空 `filesDir` 数据，不可接受。
- **versionCode**：取 CI run number（baseline 1000 起），保证每次构建可覆盖升级，且 `data/` 保留。
- 若以后要上 Play Store，再切 release keystore + Secrets，不影响 v1。

### 5.9 日志

假设没有 adb：App 内提供「日志」页，读取 Node 的 stdout/stderr 环形缓冲（最近 500 行）与 `st.log`，并支持「导出日志」到用户选择的位置。出问题时用户直接发截图或文件。

### 5.10 网页端 blob 下载

聊天/预设/主题/世界书导出走 `URL.createObjectURL` + `a.download`，Android `DownloadListener` 拿不到 blob 内容。v1 在 WebView 注入一段脚本，拦截 `a[download]` 的点击，读 blob 转 base64 后经 JS Bridge 交给 Kotlin，再由 SAF 写盘。这是 v1 必须做的一项，否则「导出」全是死按钮。

## 6. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| Node 18 已 EOL，个别依赖运行时用 Node 20+ 语法 | 启动或特定功能崩 | CI 用 Node 18 冒烟；设备上跑通核心路径；必要时降级依赖版本 |
| 16 KB 页设备加载失败 | App 起不来 | **本机不适用**（Android 14，4 KB 页）。换 Android 15+ 设备时再评估方案 ② |
| WebView 的 Chromium < 110 | `toSorted`/`toReversed` 报错 | **本机不适用**（WebView 156）。低版本设备再注入 polyfill |
| 系统内存压力杀进程 | 聊天中断 | 前台服务；数据即时落盘（SillyTavern 本身如此） |
| WebView 文件选择/下载未处理 | 无法导入角色卡、无法导出聊天 | v1 必须实现 `onShowFileChooser` 与 `DownloadListener` |
| 首次解压 200+ MB 慢 | 首启体验差 | 显示进度条；解压一次后不再重复 |
| 上游更新破坏补丁 | 同步困难 | 补丁集中、带 `// ST-ANDROID` 标记；CI 冒烟兜底 |

## 7. 测试计划

**CI 每次构建**

- Node 18 冒烟：启动、`GET /` 200、退出码 0。
- `node --check` 全部改动文件。
- APK 构建成功、`aapt dump badging` 包名与应用名正确。

**设备验收（用户执行）**

1. 首装启动，进度条走完进界面。
2. 新建角色、导入 PNG 角色卡。
3. 配一个 API，发一条消息，收到回复。
4. 杀进程重开，角色与聊天记录都在。
5. 导出数据到 Download，再导入回来。
6. 后台放置 10 分钟，回前台仍可用。
7. 打开扩展页，确认安装按钮给出「Android 不支持」提示而不是崩溃。

## 8. 留给实现者的决定

- JNI 启动器的具体形态（直接 `System.loadLibrary("node")` + 自己声明 native 方法，或移植 RN 模块的 C++ 胶水）。
- 解压实现（`ZipInputStream` 手写 vs 引入轻量库）。
- 端口探测与重试细节。
- 前台服务通知的图标与文案。
- 是否加 x86_64 ABI（模拟器调试用，会让 APK 再大 65 MB）。
- 是否在 v1 就做「导入电脑上现有 data 目录」的迁移向导。

## 9. 已决定（2026-11-02）

1. 目标设备：OPPO Find X3 Pro，Android 14，WebView 156 —— 16 KB 页风险与 `toSorted` 风险均不适用。
2. 不做电脑数据迁移；v1 只有 App 内导出/导入。
3. **接受 loopback 风险**：服务不加口令，同机其他 App 理论上可访问。记录为已知取舍；以后要加口令时，用 basic auth + WebView 自动登录，不改数据格式。
4. 只出 arm64-v8a。
5. 包名 `com.sol.sillytavern`（debug 后缀 `.debug`），应用名 `SillyTavern Sol` —— 若无异议按此执行。
