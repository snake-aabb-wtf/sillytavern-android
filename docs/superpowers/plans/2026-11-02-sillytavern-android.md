# SillyTavern Android（WebView + 内嵌 Node）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 产出一个可侧载的 Android APK，在手机本地启动 SillyTavern 服务并用 WebView 显示，数据全部留在 App 私有目录。

**Architecture:** App 进程内通过 JNI 加载 `libnode.so`（Node 18.20.4）运行 SillyTavern 的 Express 服务，只监听 `127.0.0.1` 的随机端口；WebView 加载该地址。服务端代码、生产依赖与预编译前端库打成 `runtime.zip` 放进 assets，首启解压到 `filesDir`。

**Tech Stack:** Kotlin 2.0、AGP 8.7、Gradle 8.9、JDK 17、minSdk 26 / targetSdk 35、Node 18.20.4（nodejs-mobile 预编译）、SillyTavern v1.19.0、GitHub Actions。

**Spec:** `docs/superpowers/specs/2026-11-02-sillytavern-android-design.md`

## Global Constraints

- 包名 `com.sol.sillytavern`，debug 后缀 `.debug`；应用名 `SillyTavern Sol`。
- 只出 `arm64-v8a`。
- 服务只绑 `127.0.0.1`，端口由系统分配。
- 数据目录 `filesDir/data`；运行时目录 `filesDir/runtime`；日志 `filesDir/logs/node.log`。
- 上游 SillyTavern 固定 v1.19.0（commit `06bde939f`），补丁总量 < 80 行。
- Node 运行时固定 18.20.4（`nodejs-mobile-react-native@18.20.4`）。
- 所有构建在 GitHub Actions 完成；本机不装 Android SDK。
- 不加登录口令（已接受 loopback 风险）。
- 不做电脑数据迁移、不做 TTS/图像生成/OAuth、不做扩展更新。

## Review Focus

1. **首启**：`data/` 不存在或解压中断时，App 必须给出可恢复的提示，而不是白屏。
2. **服务起不来**：端口占用、解压损坏、`libnode.so` 加载失败时，必须显示错误页与日志入口。
3. **升级**：覆盖安装后 `data/` 必须原样保留，只替换 `runtime/`。
4. **返回键**：WebView 有历史时返回上一页，没有历史时才退出，不能一点就退出。
5. **导出**：blob 下载必须真正落盘；失败要有提示，不能静默。

---

### Task 1: 仓库骨架与上游子树

**Files:**
- Create: `README.md`、`.gitignore`、`.github/workflows/android.yml`（占位）
- Create: `docs/superpowers/`（已有 spec）
- Subtree: `SillyTavern/`

**Interfaces:**
- Produces: 仓库根目录结构；后续任务都基于 `SillyTavern/` 与 `android/` 两个目录。

- [ ] **Step 1: 清理并初始化仓库**

```bash
cd /mnt/data1/SillyTavern-Android
# 现有 SillyTavern/ 是用户要求克隆的纯净上游副本，工作区干净；替换为 subtree
rm -rf SillyTavern
git init -b main
git config user.name "Sol"
git config user.email "sol@local"
```

- [ ] **Step 2: 写入 `.gitignore`**

```gitignore
android/.gradle/
android/build/
android/app/build/
android/local.properties
android/nodejs-mobile/
SillyTavern/node_modules/
SillyTavern/dist/
*.iml
.idea/
```

- [ ] **Step 3: 引入上游子树（保留历史）**

```bash
git remote add upstream https://github.com/SillyTavern/SillyTavern.git
git subtree add --prefix=SillyTavern upstream release --squash
```

Expected: 输出 `Added dir 'SillyTavern'`；`git log --oneline -1` 出现 subtree 合并提交。

- [ ] **Step 4: 写 README（三行现状 + 构建入口）**

内容：项目是什么、APK 从 Releases 下载、构建见 `.github/workflows/android.yml`。

- [ ] **Step 5: 提交并推到新仓库**

```bash
git add -A
git commit -m "chore: init repo with SillyTavern upstream subtree"
git remote add origin https://github.com/snake-aabb-wtf/sillytavern-android.git
git push -u origin main
```

仓库创建用 GitHub API：`POST /user/repos {"name":"sillytavern-android","private":false}`，已获用户批准。

- [ ] **Step 6: 验证**

Run: `git subtree --help >/dev/null && git log --oneline | head -3`
Expected: 有 subtree 提交；`git status` 干净。

---

### Task 2: 上游补丁（P1–P3）

**Files:**
- Modify: `SillyTavern/src/server-main.js:354`
- Modify: `SillyTavern/package.json`（engines）
- Modify: `SillyTavern/src/endpoints/extensions.js`（git 操作守卫）
- Create: `scripts/verify-patches.sh`

**Interfaces:**
- Produces: 环境变量 `ST_SKIP_WEBPACK=1` 跳过前端编译；`ST_MOBILE=1` 禁用依赖系统 git 的扩展操作。后续 CI 与 App 都依赖这两个开关。

- [ ] **Step 1: 写验证脚本（先失败）**

```bash
#!/usr/bin/env bash
set -e
cd "$(dirname "$0")/../SillyTavern"
grep -q "ST_SKIP_WEBPACK" src/server-main.js
grep -q "ST_MOBILE" src/endpoints/extensions.js
python3 - <<'PY'
import json
d=json.load(open('package.json'))
assert '18' in d['engines']['node'], d['engines']
PY
node --check src/server-main.js
node --check src/endpoints/extensions.js
echo "patches OK"
```

- [ ] **Step 2: 运行，确认失败**

Run: `bash scripts/verify-patches.sh`
Expected: FAIL（grep 找不到 `ST_SKIP_WEBPACK`）。

- [ ] **Step 3: 实现 P1**

`src/server-main.js` 中 `await webpackMiddleware.runWebpackCompiler({ pruneCache: true });` 改为：

```js
if (process.env.ST_SKIP_WEBPACK === '1') {
    console.log('[ST-ANDROID] using prebuilt frontend libraries');
} else {
    await webpackMiddleware.runWebpackCompiler({ pruneCache: true });
}
```

- [ ] **Step 4: 实现 P2**

`package.json` 的 `engines.node` 改为 `">= 18"`。

- [ ] **Step 5: 实现 P3**

在 `src/endpoints/extensions.js` 顶部加：

```js
const ST_MOBILE = process.env.ST_MOBILE === '1';
function blockOnMobile(res) {
    if (!ST_MOBILE) return false;
    res.status(501).json({ error: '此操作需要系统 git，Android 版不支持' });
    return true;
}
```

在 update/branches/switch 三个路由处理函数开头调用 `if (blockOnMobile(res)) return;`。安装路由不动。

- [ ] **Step 6: 验证并提交**

Run: `bash scripts/verify-patches.sh`
Expected: `patches OK`

```bash
git add SillyTavern scripts/verify-patches.sh
git commit -m "feat(st): mobile patches (skip webpack, node18, disable git-only ops)"
```

---

### Task 3: 运行时打包脚本

**Files:**
- Create: `scripts/stage-runtime.sh`
- Create: `scripts/smoke-node18.sh`

**Interfaces:**
- Produces: `dist/runtime.zip`，内含 `server/`（服务端 + 生产 node_modules + 预编译 lib.js）与 `seed/`（已播种的 data 骨架）。CI 与本地验证都吃这个产物。

- [ ] **Step 1: 写 `smoke-node18.sh`（先失败）**

脚本接收 runtime.zip 解压目录，启动 `node server.js`（Node 18 二进制路径由 `NODE18` 环境变量给出），轮询 `http://127.0.0.1:$PORT/` 与 `/api/settings/get`，任一 5xx 或超时退出 1，成功 `kill` 后退出 0。

- [ ] **Step 2: 写 `stage-runtime.sh`**

步骤：`npm ci`（含 dev，webpack 需要）→ 临时 `DATA_ROOT` 启动一次服务（不跳过 webpack，等 `lib.js` 产出与 content 播种，随后 kill）→ `npm prune --omit=dev` → 组装 `dist/runtime/{server,seed}` → `zip -r dist/runtime.zip`。

- [ ] **Step 3: CI 里跑通并在本机做语法检查**

Run: `bash -n scripts/stage-runtime.sh && bash -n scripts/smoke-node18.sh`
Expected: 无输出、退出 0。完整执行只在 CI（本机内存不足，已在设计文档记录）。

- [ ] **Step 4: 提交**

```bash
git add scripts/
git commit -m "build: stage SillyTavern runtime with prebuilt libs and seeded data"
```

---

### Task 4: Android 工程骨架与签名

**Files:**
- Create: `android/settings.gradle.kts`、`android/build.gradle.kts`、`android/gradle.properties`
- Create: `android/app/build.gradle.kts`、`android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/java/com/sol/sillytavern/App.kt`
- Create: `android/keystore/debug.keystore`（固定，密码公开）

**Interfaces:**
- Produces: `assembleDebug` 可产出 `app-debug.apk`；`BuildConfig.APP_VERSION_CODE` 与 `BuildConfig.RUNTIME_VERSION` 供后续任务使用。

- [ ] **Step 1: 生成固定 debug keystore**

```bash
keytool -genkeypair -v -keystore android/keystore/debug.keystore \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass android -keypass android \
  -dname "CN=Android Debug,O=Android,C=US"
```

- [ ] **Step 2: 配置 `build.gradle.kts`**

`applicationId "com.sol.sillytavern"`；`versionCode = (System.getenv("VERSION_CODE") ?: "1000").toInt()`；`versionName "1.0.$versionCode"`；`signingConfigs.debug` 指向上面的 keystore；`abiFilters += "arm64-v8a"`；`buildConfigField("String","RUNTIME_VERSION", "\"${System.getenv("RUNTIME_VERSION") ?: "dev"}\"")`。

- [ ] **Step 3: 最小 Manifest**

`android:usesCleartextTraffic="false"` + `networkSecurityConfig`；Application 指向 `App.kt`；主 Activity 见 Task 7（先占位一个空 Activity 让构建通过）。

- [ ] **Step 4: 构建验证（仅 CI）**

Run: `cd android && ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`。本机无 SDK，此步由 CI 执行。

- [ ] **Step 5: 提交**

---

### Task 5: JNI 桥与 Node 启动

**Files:**
- Create: `android/app/src/main/cpp/CMakeLists.txt`、`jni_bridge.cpp`
- Create: `android/app/src/main/java/com/sol/sillytavern/node/NodeBridge.kt`
- Modify: `android/app/build.gradle.kts`（externalNativeBuild）

**Interfaces:**
- Produces: `NodeBridge.startNode(args: Array<String>, logFd: Int): Int`、`NodeBridge.load(): Boolean`。`logFd` 是 Kotlin 侧打开的文件描述符，stdout/stderr 全部重定向进去。

- [ ] **Step 1: 写 C++ 桥**

```cpp
#include <jni.h>
#include <unistd.h>
#include "node.h"

extern "C" JNIEXPORT jint JNICALL
Java_com_sol_sillytavern_node_NodeBridge_startNode(
        JNIEnv* env, jobject, jobjectArray args, jint logFd) {
    dup2(logFd, STDOUT_FILENO);
    dup2(logFd, STDERR_FILENO);
    int argc = env->GetArrayLength(args);
    std::vector<std::string> store;
    std::vector<char*> argv;
    // 逐个 GetStringUTFChars 拷进 store，argv[i] 指向 store[i].data()
    return node::Start(argc, argv.data());
}
```

- [ ] **Step 2: CMake 链接预编译 lib**

从 `android/nodejs-mobile/libnode/{include,bin/arm64-v8a}` 读取（CI 下载，本地不入库）；`target_link_libraries(stbridge node log)`。

- [ ] **Step 3: Kotlin 封装**

`System.loadLibrary("stbridge")`；`external fun startNode(args: Array<String>, logFd: Int): Int`。

- [ ] **Step 4: CI 验证**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`，APK 内含 `lib/arm64-v8a/libstbridge.so` 与 `libnode.so`。

- [ ] **Step 5: 提交**

---

### Task 6: 运行时安装与数据播种

**Files:**
- Create: `android/app/src/main/java/com/sol/sillytavern/runtime/RuntimeInstaller.kt`
- Create: `android/app/src/test/java/com/sol/sillytavern/runtime/RuntimeInstallerTest.kt`

**Interfaces:**
- Produces: `RuntimeInstaller.ensureInstalled(context, assetName, version): InstallResult`（`Ready(path)` / `Failed(reason)`）；`RuntimeInstaller.seedDataIfMissing(filesDir, runtimeDir)`。

- [ ] **Step 1: 写失败测试**

断言：`needsInstall(markerVersion="", current="1.0.1001")` 为 true；相等为 false；`marker` 文件内容不匹配时 true。用 JVM 纯函数测试，不碰 Android API。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew testDebugUnitTest`
Expected: FAIL，`needsInstall` 未定义。

- [ ] **Step 3: 实现**

`needsInstall` 纯函数；`ensureInstalled` 用 `ZipInputStream` 解压到临时目录，成功后原子 rename 到 `runtime/<version>`，写 `.version`；`seedDataIfMissing` 在 `data/` 不存在时递归复制 `runtime/seed`。

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew testDebugUnitTest`
Expected: PASS。

- [ ] **Step 5: 提交**

---

### Task 7: WebView 宿主、端口与就绪等待

**Files:**
- Create: `android/app/src/main/java/com/sol/sillytavern/ui/MainActivity.kt`
- Create: `android/app/src/main/java/com/sol/sillytavern/server/ServerController.kt`
- Create: `android/app/src/main/res/layout/activity_main.xml`
- Create: `android/app/src/main/res/xml/network_security_config.xml`

**Interfaces:**
- Produces: `ServerController.start(): StartResult(port)`；`ServerController.awaitReady(port, timeoutMs): Boolean`；`MainActivity` 在就绪后 `webView.loadUrl("http://127.0.0.1:$port/")`。

- [ ] **Step 1: 端口选择与就绪探测**

`ServerSocket(0).use { it.localPort }`；`awaitReady` 每 250 ms 请求 `/`，30 s 超时。

- [ ] **Step 2: 启动 Node**

组装参数：`["node", "$runtime/server/server.js", "--port", "$port", "--listen", "false", "--configPath", "$files/config.yaml", "--dataRoot", "$files/data", "--browserLaunchEnabled", "false"]`，env 设 `ST_SKIP_WEBPACK=1`、`ST_MOBILE=1`（C++ 侧 `setenv`）。

- [ ] **Step 3: 失败路径**

加载失败/超时：显示错误布局（原因 + 「查看日志」+「重试」），不显示白屏。

- [ ] **Step 4: 返回键**

`OnBackPressedCallback`：`webView.canGoBack()` 则 `goBack()`，否则 `finish()`。

- [ ] **Step 5: 提交**

---

### Task 8: 前台服务与生命周期

**Files:**
- Create: `android/app/src/main/java/com/sol/sillytavern/server/NodeService.kt`
- Modify: `AndroidManifest.xml`（`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、`POST_NOTIFICATIONS`）

**Interfaces:**
- Produces: 服务在前台运行；`onTaskRemoved` 停止 Node 与自身。

- [ ] **Step 1: 实现服务**

`startForeground(id, notification)`；通知文案「SillyTavern 正在运行」，点击回 MainActivity；`onDestroy` 停 Node 线程。

- [ ] **Step 2: 生命周期接线**

Activity `onStart` 启服务，`onStop` 不停止；`onTaskRemoved` 停止。

- [ ] **Step 3: 提交**

---

### Task 9: 文件选择、下载与 blob 桥

**Files:**
- Create: `android/app/src/main/java/com/sol/sillytavern/web/WebBridge.kt`
- Create: `android/app/src/main/assets/blob_bridge.js`
- Modify: `MainActivity.kt`（`onShowFileChooser`、`DownloadListener`、`addJavascriptInterface`）

**Interfaces:**
- Produces: `@JavascriptInterface fun saveBlob(name: String, dataUrl: String)`；下载走 SAF。

- [ ] **Step 1: 写 `blob_bridge.js`**

拦截 `a[download]` 点击；`blob:` 走 FileReader → `STBridge.saveBlob`；普通 URL 交给 `DownloadListener`。

- [ ] **Step 2: 文件选择**

`onShowFileChooser` 启 `ACTION_GET_CONTENT`，接受 `image/*`、`application/json`、`*/*`。

- [ ] **Step 3: 下载落盘**

`DownloadListener` 用 `DownloadManager` 或 OkHttp 下载到 `Download/`，完成后 Toast。

- [ ] **Step 4: 失败提示**

SAF 取消、写入失败都要 Toast，不静默。

- [ ] **Step 5: 提交**

---

### Task 10: 数据导出/导入

**Files:**
- Create: `android/app/src/main/java/com/sol/sillytavern/backup/DataBackup.kt`
- Create: `android/app/src/main/java/com/sol/sillytavern/ui/SettingsActivity.kt`

**Interfaces:**
- Produces: `DataBackup.export(uri)` 打包 `data/` + `config.yaml`；`DataBackup.import(uri)` 校验后覆盖，需二次确认。

- [ ] **Step 1: 导出**

`ZipOutputStream` 递归写 `filesDir/data` 与 `config.yaml`，经 SAF `ACTION_CREATE_DOCUMENT`。

- [ ] **Step 2: 导入**

解压到临时目录，校验存在 `data/default-user/`（或 `data/` 非空），确认后替换；失败回滚。

- [ ] **Step 3: 提交**

---

### Task 11: 日志页

**Files:**
- Create: `android/app/src/main/java/com/sol/sillytavern/ui/LogActivity.kt`
- Modify: `AndroidManifest.xml`

**Interfaces:**
- Produces: 读取 `filesDir/logs/node.log` 最近 500 行，支持分享/导出。

- [ ] **Step 1: 实现**

`TextView`（等宽字体）+ 刷新 + 分享 Intent。

- [ ] **Step 2: 提交**

---

### Task 12: CI 构建与发版

**Files:**
- Modify: `.github/workflows/android.yml`

**Interfaces:**
- Produces: 每次 push 出 `app-debug.apk`；打 tag `st-sol-*` 时创建 Release。

- [ ] **Step 1: 工作流**

步骤：checkout → setup-java 17 → setup-node 20 → 下载 nodejs-mobile 包并解出 libnode → `bash scripts/stage-runtime.sh` → `bash scripts/smoke-node18.sh dist/runtime` → 复制 `runtime.zip` 到 assets → `./gradlew assembleDebug` → `aapt dump badging` 断言包名 → 上传 artifact。

- [ ] **Step 2: 缓存**

缓存 `~/.npm` 与 Gradle；key 含 `package-lock.json` 哈希。

- [ ] **Step 3: 首次运行并修到全绿**

Run: GitHub Actions 页面确认 `Build` 成功、APK 可下载。

- [ ] **Step 4: 发 Release**

`gh release create st-sol-1.0.<run> --notes ... app-debug.apk`（用现有令牌 helper）。

- [ ] **Step 5: 提交**

---

### Task 13: 验收清单与交接文档

**Files:**
- Create: `docs/ACCEPTANCE.md`
- Create: `HANDOFF.md`（本机私有，加入 `.gitignore`）

- [ ] **Step 1: 写验收清单**

按设计文档 §7 的 7 条，逐条给出操作步骤与期望结果。

- [ ] **Step 2: 写 HANDOFF**

现状、路径、构建/发版、补丁清单、已知问题。

- [ ] **Step 3: 提交（HANDOFF 不入库）**

---

## Self-Review

- 规格覆盖：内嵌运行时（T5）、解压与播种（T6）、WebView 与就绪（T7）、前后台（T8）、文件与 blob（T9）、导出导入（T10）、日志（T11）、CI 与发版（T12）、验收（T13）、补丁（T2）、打包（T3）。范围外项已在 Global Constraints 排除。
- 类型一致性：`startNode(args, logFd)`、`ensureInstalled(context, asset, version)`、`awaitReady(port, timeoutMs)` 在后续任务中引用一致。
- Review Focus 五条均落到任务：首启（T6）、服务起不来（T7 Step 3）、升级保数据（T6 + T4 签名）、返回键（T7 Step 4）、导出（T9 Step 4）。
