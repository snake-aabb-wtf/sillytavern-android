# SillyTavern Sol — SillyTavern for Android

把 [SillyTavern](https://github.com/SillyTavern/SillyTavern) 打包成一台手机上就能跑的自足应用：App 内嵌 Node 运行时启动本地服务，界面由原生 Jetpack Compose 客户端绘制，所有数据留在应用私有目录。

Android 端**不再加载网页界面**：角色、会话、聊天三屏由 Kotlin/Compose 直接实现，通过本机 HTTP 调服务端。消息发送走 `POST /api/native/chat`——服务端用无头组装层（jsdom 复现网页端的提示词组装）生成与网页版逐字段一致的 `/generate` 请求体，再把 provider 的 SSE 原样转发给客户端。提示词一致性由 `native/test/build-headless.mjs` 对抓取的浏览器请求体逐字段比对。

- **应用名**：SillyTavern Sol
- **包名**：`com.sol.sillytavern`
- **要求**：Android 8.0（API 26）及以上，仅 `arm64-v8a`
- **上游**：SillyTavern v1.19.0（`06bde939fb1e9c4c8d8641d810f0a916b5bce127`），以 git subtree 引入 `SillyTavern/`
- **许可**：AGPL-3.0（见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)）

## 下载

CI 每次构建上传 artifact `app-debug-apk`（文件 `app-debug.apk`）；推送 `st-sol-*` 标签时，同一 APK 会附到 [Releases](https://github.com/snake-aabb-wtf/sillytavern-android/releases)。原生客户端目前从 `feature/compose-ui` 分支构建，尚无真机验收记录。

- debug 签名，可直接覆盖安装后续构建；`data/` 不会被清空
- APK 约 **203 MiB**（2026-11-04 实测 213,077,334 B）；首次启动还要解压约 145 MB 的运行时，建议设备留出 **1.5 GB** 可用空间
- 首次启动会解压运行时，通常 **30–120 秒**，期间不要划掉应用。运行时内容未变时（版本哈希相同）直接复用，不再解压

## 它是怎么工作的

```
MainActivity (Kotlin)
  ├── 解压 assets/runtime.zip → filesDir/runtime/<内容哈希>/
  ├── 在线程里启动 node::Start（nodejs-mobile libnode 18.20.4，small-icu）
  ├── 等待 127.0.0.1:<随机端口> 就绪
  └── ComposeView 挂载 SolApp(port)（data/ 等全部在应用私有目录）
        ├── GET  /api/native/characters
        ├── GET  /api/native/chats
        └── POST /api/native/chat   （SSE 流式回复）
```

`libnode.so` 不是 npm 包里那份：npm 包用 `--with-intl=none` 构建，V8 不支持 `\p{...}` 正则、也没有 `Intl`，SillyTavern 在 ESM 加载阶段就会抛 `Invalid property name`。本项目用 nodejs-mobile v18.20.4 源码 + `deps/icu-small` 交叉编译 arm64，发布为 Release 资产 `libnode-intl-18.20.4`，Android 构建从该资产获取并校验 `icudt`。

## 当前范围（M1）

原生客户端首版只覆盖浏览与聊天主线：

- 角色列表（头像、名称；数据来自服务端已有角色）
- 会话列表（按更新时间倒序；点击进入已有聊天）
- 流式聊天与停止生成
- 深色模式跟随系统、返回键逐级返回、键盘遮挡处理
- 数据导出/导入（zip，走系统文件选择器）、日志查看/分享/导出（原生设置页）

**尚未提供**（属后续 M2/M3）：新建聊天、角色新建/编辑/PNG 导入、API 设置与连接测试、消息编辑/删除/重发/继续。因此全新安装后没有聊天文件，也无法在应用内配置 API；需要从 1.0.3 覆盖升级保留原有 `data/`，或先导入一份已配置好的数据备份。

## 已知限制

- **首次发送较慢**：冷启动后的第一次提示词组装约 11 秒，之后每条约 2–3 秒（CI 实测；未在低端真机复测）
- **`regenerate` / `continue` 返回 501**：无头组装层目前只实现普通发送；界面尚未暴露这两个操作
- **未做真机验收**：原生十项设备检查仍待执行，CI 只证明编译、单测与提示词 parity
- **没有优雅停止 Node**：libnode 没有暴露可用的 Environment 句柄，「停止」= 结束进程。已落盘数据保留，正在写入的内容可能丢
- **扩展安装不支持**：设备上没有系统 git，相关操作返回 501 并提示「Android 版不支持」
- 仅 `arm64-v8a`；x86_64 模拟器装不上
- **导入数据后需要重启一次**应用才生效（导入只是暂存）
- 建议在空闲时导出（导出不会暂停 Node）
- 体积大：debug 构建未做资源压缩

## 构建

**推荐走 CI**（本机编译 Node 与 145 MB 运行时都不轻松）：

- push 到 `main` 或 `feature/compose-ui` → 产出 APK artifact
- push `st-sol-*` 标签 → 额外发布 GitHub Release
- 工作流：`.github/workflows/android.yml`；`versionName` 由标签推导（标签构建），分支构建则是 `1.0.<run_number>`

需要：

- JDK 17、Android SDK 35、NDK（由 CI 安装）
- Node 20（staging 运行时）与 Node 18.20.4（运行时冒烟）
- `libnode` 头文件来自 npm 包 `nodejs-mobile-react-native@18.20.4`；`libnode.so` 来自 Release 资产 `libnode-intl-18.20.4`

本地步骤：

```bash
bash scripts/stage-runtime.sh          # → dist/runtime.zip（约 5–15 分钟）
cp dist/runtime.zip android/app/src/main/assets/
cd android && ./gradlew testDebugUnitTest assembleDebug
```

提示词 parity 回归（Node 侧，可在本机跑；需要 `fixtures/reference/` 中已有的浏览器抓取）：

```bash
cd native && npm run test:headless     # 末尾打印 RESULT: PASS (3/3)
```

CI 产物：`android/app/build/outputs/apk/debug/app-debug.apk`，artifact 名 `app-debug-apk`。

`scripts/verify-patches.sh` 会在 CI 中执行：它不仅检查 `ST-ANDROID` 标记是否存在，还会用桩执行被改动的代码，确认桌面与手机两种行为都还在。

## 发版

见 [RELEASING.md](RELEASING.md)。一句话版：

```bash
git tag -a st-sol-1.0.4 -m "..." <commit>
git push origin st-sol-1.0.4
```

## 目录结构

```
android/            Kotlin 外壳 + Compose 原生客户端（Activity、Node 启动、备份、日志）
native/             无头提示词组装、/api/native 路由、parity 测试
scripts/            stage-runtime.sh / verify-patches.sh / smoke-node18.sh / prune-public.sh
SillyTavern/        上游子树 + ST-ANDROID 补丁（提交内联，无独立 patch 文件）
docs/               设计文档、计划、设备验收清单
```

## 同步上游

```bash
git subtree pull --prefix SillyTavern \
  https://github.com/SillyTavern/SillyTavern.git release --squash
bash scripts/verify-patches.sh   # 补丁被上游覆盖时会失败
```

补丁以 `// ST-ANDROID` 标记内联在 `SillyTavern/` 中，升级后若上游改动触及这些位置，需要重新应用并跑校验。

## 许可证

本项目以 **AGPL-3.0** 发布，与上游 SillyTavern 一致。分发 APK 即构成分发，对应源码就是本仓库（每个 Release 标签都包含构建该 APK 的完整源码与运行时构建脚本）。

- `NOTICE`：上游版本、修改范围、如何取得对应源码
- `THIRD_PARTY_NOTICES.md`：随 APK 分发的 Node.js、V8、ICU、OpenSSL、AndroidX 等组件
- `SillyTavern/` 内保留上游原始提交与 `SillyTavern/LICENSE`

## 设备验收

上一版 WebView 1.0.x 的七项设备验收已于 2026-11-03 在真机执行并全部通过；当前 Compose 原生客户端的十项验收（冷启动、角色列表、会话列表、流式发送、停止、杀进程重开、深色模式、返回键、键盘遮挡、长会话滚动）**待验收**。清单、逐项通过标准与记录表见 [docs/ACCEPTANCE.md](docs/ACCEPTANCE.md)。CI 只证明能编译、单测与 Node 冒烟，不代替真机检查。
