# 发版流程

所有 APK 由 CI 构建；本机只负责打标签。

## 1. 确认分支状态

```bash
git checkout main
git pull
git log --oneline -1
```

CI 必须在 `main` 上全绿（`Build debug APK` job 的 19 个步骤全部 success）。工作流里已经跑过：

- `scripts/verify-patches.sh`（补丁行为校验，不只是找标记）
- `./gradlew testDebugUnitTest`（17 个 JVM 单测）
- `scripts/smoke-node18.sh`（Node 18 冒烟启动）
- `aapt` 断言包名 / minSdk / ABI / `versionName`
- `unzip -l` 断言 `libnode.so`、`libstbridge.so`、`assets/runtime.zip` 都在

## 2. 打标签

标签名决定 `versionName`：`st-sol-1.0.4` → `versionName 1.0.4`。

```bash
git tag -a st-sol-1.0.4 -m "SillyTavern Sol 1.0.4: <一句话说明>"
git push origin st-sol-1.0.4
```

推标签会同时触发两个 job：

1. `Build debug APK` —— 从该 commit 重新构建
2. `Publish Release` —— 下载 APK，创建 GitHub Release 并上传 `app-debug.apk`

## 3. 自验（不要跳过）

```bash
TAG=st-sol-1.0.4
curl -sL -o /tmp/check.apk \
  "https://github.com/snake-aabb-wtf/sillytavern-android/releases/download/$TAG/app-debug.apk"
stat -c %s /tmp/check.apk          # 应在 2.1 亿字节左右
sha256sum /tmp/check.apk
unzip -l /tmp/check.apk | grep -E 'classes.dex|lib/arm64-v8a|assets/runtime.zip'
unzip -p /tmp/check.apk lib/arm64-v8a/libnode.so | strings -a | grep -c icudt   # 必须 > 0
```

- `icudt` 计数为 0 = 拉到了没有 ICU 的 libnode，SillyTavern 会在 ESM 阶段报 `Invalid property name`，不要发这个包
- 确认包内 `assets/runtime.zip` 大小与 `dist/runtime.zip` 一致
- `versionName` 与标签一致（CI 已断言，仍建议扫一眼 manifest）

## 4. 写 Release notes

CI 只创建带标签名的空 Release，正文需要补：

```bash
gh release edit st-sol-1.0.4 --notes-file /tmp/notes.md
```

或直接改 GitHub 页面。建议结构：

```markdown
## 变更
- ...

## 安装
- 仅 arm64-v8a，Android 8.0+
- 覆盖安装不会清数据；首次启动会解压运行时（30–120 秒）

## 校验
- 大小：212,xxx,xxx B
- sha256：...

## 已知限制
- ...
```

## 5. 回滚

- 标签推错了：`git push origin :refs/tags/st-sol-1.0.4`，然后删掉对应 Release 与 tag，重打
- 已发布但有问题：**不要**复用旧标签名改内容，直接发下一个补丁版本（`1.0.5`），并在 Release notes 里写明撤回原因

## 注意

- `versionCode` 取 `github.run_number`，天然递增；不要手工改小
- 运行时内容变化（`SillyTavern/` 或依赖变动）会自动改变 `RUNTIME_VERSION`（`dist/runtime.zip` 的 sha256 前 12 位），设备下次启动重新解压
- `HANDOFF.md` 是本机交接文档，已在 `.gitignore` 中，不要提交
- `android/app/src/main/assets/runtime.zip` 同样被忽略（145 MiB），由 CI 生成
