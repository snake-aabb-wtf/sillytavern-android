# SillyTavern Sol — SillyTavern for Android

把 [SillyTavern](https://github.com/SillyTavern/SillyTavern) 打包成一台手机上就能跑的自足应用：App 内嵌 Node 运行时启动本地服务，WebView 显示界面，所有数据留在应用私有目录。

- **应用名**：SillyTavern Sol
- **包名**：`com.sol.sillytavern`
- **要求**：Android 8.0（API 26）及以上，仅 `arm64-v8a`
- **上游**：SillyTavern v1.19.0（`06bde939fb1e9c4c8d8641d810f0a916b5bce127`），以 git subtree 引入 `SillyTavern/`
- **许可**：AGPL-3.0（见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)）

## 下载

从 [Releases](https://github.com/snake-aabb-wtf/sillytavern-android/releases) 取最新 `st-sol-*` 标签下的 `app-debug.apk`。

- debug 签名，可直接覆盖安装后续构建；`data/` 不会被清空
- APK 约 **200 MiB**；首次启动还要解压约 152 MB 的运行时，建议设备留出 **1.5 GB** 可用空间
- 首次启动会解压运行时，通常 **30–120 秒**，期间不要划掉应用。运行时内容未变时（版本哈希相同）直接复用，不再解压

## 它是怎么工作的

```
MainActivity (Kotlin)
  ├── 解压 assets/runtime.zip → filesDir/runtime/<内容哈希>/
  ├── 在线程里启动 node::Start（nodejs-mobile libnode 18.20.4，small-icu）
  ├── 等待 127.0.0.1:<随机端口> 就绪
  └── WebView 加载该地址（data/ 等全部在应用私有目录）
```

`libnode.so` 不是 npm 包里那份：npm 包用 `--with-intl=none` 构建，V8 不支持 `\p{...}` 正则、也没有 `Intl`，SillyTavern 在 ESM 加载阶段就会抛 `Invalid property name`。本项目用 nodejs-mobile v18.20.4 源码 + `deps/icu-small` 交叉编译 arm64，发布为 Release 资产 `libnode-intl-18.20.4`，Android 构建从该资产获取并校验 `icudt`。

## 功能

- 新建/导入角色卡（含 PNG 角色卡）
- API 配置与正常聊天
- 数据导出/导入（zip，走系统文件选择器）
- 日志查看、分享与导出
- 可拖动的浮动设置按钮（吸附侧边、记住位置）

## 已知限制

- **没有优雅停止 Node**：libnode 没有暴露可用的 Environment 句柄，「停止」= 结束进程。已落盘数据保留，正在写入的内容可能丢
- **扩展安装不支持**：设备上没有系统 git，相关操作返回 501 并提示「Android 版不支持」
- 仅 `arm64-v8a`；x86_64 模拟器装不上
- **导入数据后需要重启一次**应用才生效（导入只是暂存）
- 建议在空闲时导出（导出不会暂停 Node）
- 体积大：debug 构建未做资源压缩

## 构建

**推荐走 CI**（本机编译 Node 与 152 MB 运行时都不轻松）：

- push 到 `main` → 产出 APK artifact
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

`scripts/verify-patches.sh` 会在 CI 中执行：它不仅检查 `ST-ANDROID` 标记是否存在，还会用桩执行被改动的代码，确认桌面与手机两种行为都还在。

## 发版

见 [RELEASING.md](RELEASING.md)。一句话版：

```bash
git tag -a st-sol-1.0.4 -m "..." <commit>
git push origin st-sol-1.0.4
```

## 目录结构

```
android/            Kotlin 外壳（Activity、Node 启动、备份、日志、WebBridge）
scripts/            stage-runtime.sh / verify-patches.sh / smoke-node18.sh
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

七项设备验收已于 2026-11-03 在真机上执行，**全部通过**。清单与逐项记录见 [docs/ACCEPTANCE.md](docs/ACCEPTANCE.md)。CI 只证明能编译与 Node 冒烟，不代替真机检查；后续改动运行时、备份或后台保活相关代码时应重跑对应项。
