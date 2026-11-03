# 原生 Compose 客户端实施计划（M0 + M1）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 证明提示词组装能在无浏览器的 Node 环境里逐字段复现，并交付一个能列角色、列会话、流式聊天的最小原生 Compose 客户端。

**Architecture:** 服务端新增无头组装层（jsdom 承载现有 `public/scripts` 逻辑）与 `/api/native/chat` 接口，复用现有 provider 转发路径；Android 端用 OkHttp 调本机引擎，Compose 单 Activity 承载角色/会话/聊天三屏。网页界面不再加载。

**Tech Stack:** Node 18（内嵌 libnode 18.20.4）、jsdom、Express、Playwright（仅 CI 参考抓取）、Kotlin + Jetpack Compose (Material3)、OkHttp、kotlinx.serialization、JUnit4。

**Spec:** `docs/superpowers/specs/2026-11-03-compose-native-client-design.md`

## Global Constraints

- 包名 `com.sol.sillytavern`，minSdk 26，targetSdk 35，仅 `arm64-v8a`。
- 引擎默认配置：`whitelistMode: true`、`basicAuthMode: false`、`enableUserAccounts: false`、`disableCsrfProtection: false`；所有写请求必须带 `x-csrf-token`。
- 本机无 Android SDK，Android 单测与构建**只能在 CI** 运行；Node 侧脚本可本机运行（Node 24 可用）。
- 服务端改动放在仓库根 `native/`，**不得直接修改 `SillyTavern/` 子树**（该目录由 `git subtree` 同步上游）；由 `scripts/stage-runtime.sh` 在打包时叠加。
- 数据文件（`settings.json`、聊天 `.jsonl`/`.json`）的字段由服务端定义；客户端读-改-写必须保留未知字段。
- 首版只支持 OpenAI 风格 SSE（覆盖 OpenAI 兼容与 OpenRouter）。
- 每个任务独立提交；提交信息用 `feat:` / `test:` / `fix:` 前缀。

## Review Focus

1. **SSE 分块边界**：一个 `data:` 事件被 TCP 分片拆开，或 `[DONE]` 后没有换行——用户会看到回复卡住不结束。
2. **CSRF 令牌过期**：长时间后台后写请求 403——用户会遇到「发不出消息」且无提示。
3. **聊天 JSON 未知字段**：保存时重建对象导致 `swipes`、`extra`、`variables` 丢失——用户会丢重发记录和变量。
4. **服务端返回 HTML 错误页**：500 时 body 是 HTML 而非 JSON——客户端解析崩溃或显示空消息。
5. **多字节字符跨块**：emoji/中文被切在两个 chunk 之间——用户会看到乱码或半个字。

每条在对应任务的测试里固定（见 Task 5、6、8、9 的测试步骤）。

---

### Task 1: 参考抓取——用真实网页抓出 `/generate` 请求体

**Files:**
- Create: `.github/ci/fixtures/character-plain.json`
- Create: `.github/ci/fixtures/character-worldinfo.json`
- Create: `.github/ci/fixtures/chat-plain.json`
- Create: `native/test/capture-reference.mjs`
- Create: `native/package.json`（devDependencies：`playwright`、`jsdom`；`type: module`）
- Create: `.github/workflows/native-parity.yml`

**Interfaces:**
- Produces: `fixtures/reference/<name>.json`，每份为 `{ url, body }`，`body` 是发往 `/api/backends/chat-completions/generate` 的完整 JSON。
- Produces: `npm run capture:reference`（在 `native/` 内执行）。

- [ ] **Step 1: 写夹具**

`character-plain.json` 为最小 v2 角色卡（`name: "Fixture Plain"`、`description: "A calm test character."`、`first_mes: "Hello."`、`mes_example: ""`）。`character-worldinfo.json` 额外带 `character_book`，含一条 `keys: ["apple"]`、`content: "APPLE FACT: apples are red."` 的条目。`chat-plain.json` 为两条消息（`{name: "Fixture Plain", is_user: false, mes: "Hello."}`、`{name: "User", is_user: true, mes: "Tell me about apples."}`）。

- [ ] **Step 2: 写抓取脚本**

`native/test/capture-reference.mjs` 做四件事：把夹具复制进临时 data 目录；`spawn` `node server.js --port <free> --listen false --configPath <tmp config>`；用 Playwright Chromium 打开 `http://127.0.0.1:<port>/`；`page.route('**/api/backends/chat-completions/generate')` 截获请求，记录 `postData` 后 `fulfill` 一个最小 SSE（`data: {"choices":[{"delta":{"content":"ok"}}]}\n\ndata: [DONE]\n\n`）。

- [ ] **Step 3: 在 CI 里跑一次，确认抓得到**

`.github/workflows/native-parity.yml`：`workflow_dispatch` + `push` 到 `feature/compose-ui`，步骤为 checkout、安装 Node 20、`npm ci`（在 `SillyTavern/`，仅用于启动服务）、`npm ci`（在 `native/`）、`npx playwright install --with-deps chromium`、`npm run capture:reference`、上传 `fixtures/reference` 为 artifact。

Run: 推送后查看 Actions `native-parity`。
Expected: 成功，artifact 内两份 JSON 均非空，且每份含 `body.messages` 数组。

- [ ] **Step 4: 提交**

```bash
git add .github/ci/fixtures native/package.json native/package-lock.json native/test/capture-reference.mjs .github/workflows/native-parity.yml
git commit -m "test: capture reference generate requests from the web UI"
```

---

### Task 2: 无头组装——在 jsdom 里复现同一请求

**Files:**
- Create: `native/prompt-builder/dom-host.mjs`
- Create: `native/prompt-builder/index.mjs`
- Create: `native/test/build-headless.mjs`
- Modify: `native/test/capture-reference.mjs`（改为也可输出 `fixtures/headless/<name>.json`）

**Interfaces:**
- Produces: `createBuilder({ dataDir, port }) -> Promise<Builder>`；`Builder.build({ avatarUrl, fileName, userInput, type }) -> Promise<{ url: string, body: object }>`；`Builder.close()`。
- Consumes: Task 1 的夹具与参考 JSON。

- [ ] **Step 1: 写不通过的对拍测试**

`native/test/build-headless.mjs` 读取 `fixtures/reference/*.json`，对每份调用 `Builder.build` 生成 `fixtures/headless/<name>.json`，逐字段深比较 `body`。断言失败时打印首个差异路径（如 `body.messages[3].content`）。

Run: `node native/test/build-headless.mjs`
Expected: FAIL，错误为 `createBuilder is not a function`（模块尚未实现）。

- [ ] **Step 2: 实现 dom-host**

用 jsdom 以 `runScripts: 'dangerously'`、`resources: 'usable'` 打开 `SillyTavern/public/index.html`，`url` 指向本机端口；把 `window.fetch` 替换为 Node 侧 `fetch` 并自动补 `x-csrf-token` 与 Cookie；等待 `window.SillyTavern` 就绪信号（页面 `jQuery(document).ready` 后设置的自定义标志，若无则在 `index.html` 注入一行 `window.__ready = true`）。

- [ ] **Step 3: 实现 index.mjs**

`createBuilder` 启动临时 data 目录 + 真实 `server.js`（与 Task 1 同参），建 jsdom 宿主，暴露 `build()`：通过 `window.evaluate` 调用页面内的 `createGenerationParameters(settings, model, type, messages)`，再调用页面的 `sendOpenAIRequest` 前置逻辑拿到最终 body；若该函数不直接可用，改为拦截页面内 `fetch` 到 `/generate` 的调用并取出 body。`close()` 关闭窗口与服务。

- [ ] **Step 4: 跑到全绿**

Run: `node native/test/build-headless.mjs`
Expected: PASS，两份夹具的 `body` 与参考**逐字段相等**（允许忽略 `stream` 以外的运行时随机字段，如 `user`；忽略项须写进测试的白名单常量）。

- [ ] **Step 5: 提交**

```bash
git add native/prompt-builder native/test/build-headless.mjs
git commit -m "feat: assemble chat requests headlessly in jsdom"
```

- [ ] **Step 6: 写结论**

在 spec 的 §6.2 末尾追加一段「M0 结果」：通过/不通过、差异字段、耗时。若不通过，**停止后续任务**，回报并回退到方案 B。

---

### Task 3: `/api/native/chat` 接口（SSE 透传）

**Files:**
- Create: `native/endpoints/native-chat.mjs`
- Create: `native/test/native-chat.test.mjs`
- Modify: `scripts/stage-runtime.sh`（叠加 `native/` 到 `dist/runtime/server/native/`，并在 `server-main.js` 末尾注入挂载行）

**Interfaces:**
- Produces: `POST /api/native/chat`，body `{ avatar_url: string, file_name: string, user_input: string, type: "send"|"regenerate"|"continue" }`；响应 `text/event-stream`，逐块原样转发 provider 的 `data:` 行。
- Consumes: `Builder.build`（Task 2）。

- [ ] **Step 1: 写失败测试**

`native/test/native-chat.test.mjs` 用 `node:test` + 假 Builder（返回固定 body）与假 provider（返回三块 SSE），断言：状态 200、`content-type` 含 `text/event-stream`、响应体按序包含三块、结束于 `[DONE]`。另加一条：`user_input` 为空时返回 400 JSON。

Run: `node --test native/test/native-chat.test.mjs`
Expected: FAIL（路由模块不存在）。

- [ ] **Step 2: 实现路由**

导出 `createNativeChatRouter({ builder, forward })`；`forward` 复用 `server-main` 已有的 provider 分发（注入而非重写）。流式时 `response.write(chunk)`，客户端断开时 `AbortController.abort()`。

- [ ] **Step 3: 挂载与打包**

`stage-runtime.sh` 在 `npm ci` 之后、打包之前：`cp -r native dist/runtime/server/native`；再向 `dist/runtime/server/server-main.js` 末尾追加三行（顶层 await 在该文件已是 ESM，合法；Express 允许 listen 后继续注册路由）：

```js
const { default: nativeRouter } = await import('./native/endpoints/native-chat.mjs');
app.use('/api/native', nativeRouter);
console.info('[native] /api/native mounted');
```

- [ ] **Step 4: 全绿并提交**

Run: `node --test native/test/native-chat.test.mjs`
Expected: PASS（4 个断言全过）。

```bash
git add native/endpoints native/test/native-chat.test.mjs scripts/stage-runtime.sh
git commit -m "feat: add /api/native/chat streaming endpoint"
```

---

### Task 3b: 把每条消息的准备时间压到 3 秒内

**Files:**
- Modify: `native/prompt-builder/index.mjs`
- Modify: `native/prompt-builder/dom-host.mjs`
- Modify: `native/endpoints/native-chat.mjs`
- Modify: `native/test/latency-probe.mjs`（或同名 CI 探针）
- Test: `native/test/chat-state-isolation.mjs`（保持绿色）

**Interfaces:**
- Consumes: `Builder.build({ avatarUrl, fileName, userInput, type })`（Task 2）、`/api/native/chat`（Task 3）。
- Produces: 不变的路由契约；warm 首字节 ≤ 3000 ms（CI 探针断言）。

**背景：** 当前 warm 首字节 6.3–23.5 s，主要耗在「每次 turn 重新 `POST /api/chats/get` + `printMessages()` 渲染整段历史」与页面主线程的 jsdom 开销。这个数字不可接受，且可能影响 `/api/native/chat` 的形态，因此在做界面前解决。

- [ ] **Step 1: 先量再改**

在 `build()` 内按阶段打点（page ready / chat load / render / set input / click / capture），跑 `native/test/latency-probe.mjs` 记录一次三段式耗时。把数字写进报告，后续每步对照。

- [ ] **Step 2: 跳过整页渲染**

不要走 `reloadCurrentChat()`。改为直接 `GET /api/chats/get`（或读该 `fileName` 的聊天文件）拿到 JSON，写入 `ctx().chat` 与 `characters[i].chat`，再触发发送。断言：三份 parity 夹具的 `body` 仍逐字段相等；两轮隔离测试仍绿。

- [ ] **Step 3: 页面常驻 + 启动预热**

服务启动时异步预热 jsdom 页面（现有 warm-up 保留），并把「角色选择 / 设置加载」从每次 `build()` 移到首次构建时缓存。

- [ ] **Step 4: 主线程让位**

若 `build()` 仍阻塞 HTTP 事件循环，把 builder 放进 `node:worker_threads`，路由通过消息通道取 `{url, body}`；SSE 转发仍在主线程。断言：构建期间健康检查接口仍能在 200 ms 内响应。

- [ ] **Step 5: 门禁**

`latency-probe` 增加硬断言：warm 首字节 ≤ 3000 ms，超过即 CI 失败（附阶段耗时）。若最终仍 > 3000 ms，报告实测分布与瓶颈，不得把断言放宽。

- [ ] **Step 6: 提交**

```bash
git add native
git commit -m "perf: cut warm prompt-build latency to the interactive budget"
```

### Task 4: Android 接入 Compose 与依赖

**Files:**
- Modify: `android/app/build.gradle.kts`
- Modify: `android/build.gradle.kts`（如缺 Compose 插件版本）
- Create: `android/app/src/main/java/com/sol/sillytavern/native/ui/theme/Theme.kt`
- Create: `android/app/src/main/java/com/sol/sillytavern/native/ui/App.kt`
- Modify: `android/app/src/main/java/com/sol/sillytavern/ui/MainActivity.kt`

**Interfaces:**
- Produces: `@Composable fun SolApp(port: Int)`；`SolTheme(content: @Composable () -> Unit)`。

- [ ] **Step 1: 加依赖**

`buildFeatures { compose = true }`；`composeOptions.kotlinCompilerExtensionVersion` 对齐 Kotlin 版本；依赖 `androidx.compose:compose-bom:2024.10.01`、`ui`、`material3`、`activity-compose`、`navigation-compose`、`lifecycle-viewmodel-compose`、`okhttp:4.12.0`、`kotlinx-serialization-json:1.7.3`（并应用 `kotlin("plugin.serialization")`）。

- [ ] **Step 2: 空壳宿主**

`MainActivity.onCreate` 在 `ServerState.Ready` 后 `setContent { SolTheme { SolApp(port) } }`；`SolApp` 先只显示 `Text("RikkaHub? no — Sol")`（临时占位，Task 7 替换）。保留启动页与失败页逻辑，删除 `WebView` 初始化与 `WebBridge` 调用。

- [ ] **Step 3: CI 编译**

Run: 推送后 Actions `android` 的 `assembleDebug`。
Expected: 成功；APK 可安装且在真机/模拟器上显示占位文字。

- [ ] **Step 4: 提交**

```bash
git add android
git commit -m "feat(android): host a Compose UI instead of the WebView"
```

---

### Task 5: EngineClient（CSRF + JSON + SSE 解析）

**Files:**
- Create: `android/app/src/main/java/com/sol/sillytavern/native/engine/EngineClient.kt`
- Create: `android/app/src/main/java/com/sol/sillytavern/native/engine/SseParser.kt`
- Create: `android/app/src/test/java/com/sol/sillytavern/native/engine/SseParserTest.kt`
- Create: `android/app/src/test/java/com/sol/sillytavern/native/engine/CsrfRetryTest.kt`

**Interfaces:**
- Produces: `class EngineClient(baseUrl: String, client: OkHttpClient)`；`suspend fun token(): String`；`suspend fun postJson(path: String, body: JsonElement): JsonElement`；`fun stream(path: String, body: JsonElement): Flow<ChatEvent>`。
- Produces: `sealed interface ChatEvent { data class Delta(val text: String) : ChatEvent; data object Done : ChatEvent; data class Failure(val message: String) : ChatEvent }`。
- Produces: `class SseParser { fun feed(bytes: ByteArray): List<String>; fun takeEvent(line: String): String? }`（`feed` 处理跨块边界与多字节字符）。

- [ ] **Step 1: 写失败的 SSE 测试**

`SseParserTest` 四条：单块完整事件；事件被切成 `data: {"cho` + `ices"...}`；emoji 被切在 4 字节中间；`[DONE]` 无尾随换行。

Run: CI `./gradlew testDebugUnitTest --tests '*SseParserTest*'`
Expected: FAIL（类不存在）。

- [ ] **Step 2: 实现 SseParser**

用 `java.nio.charset.CharsetDecoder` 增量解码（`CodingErrorAction.REPLACE` 会切坏字符，必须 `REPORT` 并在残字节处保留），按 `\n\n` 分事件，取 `data:` 行。

- [ ] **Step 3: 写失败的 CSRF 测试**

`CsrfRetryTest` 用 MockWebServer：第一次写请求返回 403，断言客户端刷新 `/csrf-token` 后重试一次并成功；第二次仍 403 时抛出 `EngineError.Csrf`。

- [ ] **Step 4: 实现 EngineClient**

OkHttp `Interceptor` 统一注入 `x-csrf-token` 与 Cookie（`CookieJar` 内存实现）；`stream()` 用 `callbackFlow`，`awaitClose { call.cancel() }`；非 2xx 且 `content-type` 非 JSON 时，取前 200 字节转成 `Failure("HTTP $code")`。

- [ ] **Step 5: 跑到全绿并提交**

Run: CI `testDebugUnitTest`
Expected: PASS（`SseParserTest` 4 条 + `CsrfRetryTest` 2 条）。

```bash
git add android/app/src/main/java/com/sol/sillytavern/native/engine android/app/src/test/java/com/sol/sillytavern/native/engine
git commit -m "feat(android): engine client with csrf handling and sse parsing"
```

---

### Task 6: 角色与会话仓库

**Files:**
- Create: `android/app/src/main/java/com/sol/sillytavern/native/data/model/Models.kt`
- Create: `android/app/src/main/java/com/sol/sillytavern/native/data/CharacterRepository.kt`
- Create: `android/app/src/main/java/com/sol/sillytavern/native/data/ChatRepository.kt`
- Create: `android/app/src/test/java/com/sol/sillytavern/native/data/ChatRepositoryTest.kt`

**Interfaces:**
- Produces: `data class Character(val avatar: String, val name: String, val description: String, val tags: List<String>)`；`data class ChatSummary(val file: String, val title: String, val lastMessage: String, val updatedAt: Long)`；`data class ChatMessage(val raw: JsonObject)`（原样保留整个对象）。
- Produces: `CharacterRepository.list(): List<Character>`、`ChatRepository.list(avatar): List<ChatSummary>`、`ChatRepository.load(avatar, file): List<ChatMessage>`、`ChatRepository.save(avatar, file, messages: List<ChatMessage>, metadata: JsonObject)`。
- Consumes: `EngineClient.postJson`（Task 5）。

- [ ] **Step 1: 写失败的保真测试**

`ChatRepositoryTest`（MockWebServer）：读入一条含 `swipes`、`extra.variables`、自定义顶层键的聊天 JSON，`load` 后原样 `save`，断言发出的请求体与原始 JSON 逐字段相等（`JsonElement` 比较，忽略键顺序）。

Run: CI `testDebugUnitTest --tests '*ChatRepositoryTest*'`
Expected: FAIL。

- [ ] **Step 2: 实现仓库**

只做字段提取与转发；`save` 直接把 `ChatMessage.raw` 重新序列化，不重构对象。标题缺失时回退到文件名去扩展名。

- [ ] **Step 3: 全绿并提交**

Run: CI `testDebugUnitTest`
Expected: PASS。

```bash
git add android/app/src/main/java/com/sol/sillytavern/native/data android/app/src/test/java/com/sol/sillytavern/native/data
git commit -m "feat(android): character and chat repositories preserving raw json"
```

---

### Task 7: 角色列表与会话列表界面

**Files:**
- Create: `android/app/src/main/java/com/sol/sillytavern/native/ui/characters/CharactersScreen.kt`
- Create: `android/app/src/main/java/com/sol/sillytavern/native/ui/chats/ChatsScreen.kt`
- Create: `android/app/src/main/java/com/sol/sillytavern/native/vm/CharactersVM.kt`
- Create: `android/app/src/main/java/com/sol/sillytavern/native/vm/ChatsVM.kt`
- Modify: `android/app/src/main/java/com/sol/sillytavern/native/ui/App.kt`

**Interfaces:**
- Consumes: `CharacterRepository`、`ChatRepository`（Task 6）。
- Produces: 路由 `characters`、`chats/{avatar}`、`chat/{avatar}/{file}`。

- [ ] **Step 1: 角色列表**

LazyVerticalGrid 两列，头像用 `POST /api/avatars/get` 取字节（Coil `AsyncImage` 需加依赖 `io.coil-kt:coil-compose:2.7.0`）；加载中显示进度；空态显示「还没有角色」。

- [ ] **Step 2: 会话列表**

按 `updatedAt` 倒序；点击进入 `chat/{avatar}/{file}`；返回键回角色列表。

- [ ] **Step 3: 手动验证**

Run: CI 出包 → 真机安装。
Expected: 能看到已有角色与各角色的会话；点击进入聊天页（Task 8 前为占位）。

- [ ] **Step 4: 提交**

```bash
git add android/app/src/main/java/com/sol/sillytavern/native/ui android/app/src/main/java/com/sol/sillytavern/native/vm
git commit -m "feat(android): character and chat list screens"
```

---

### Task 8: 聊天页（历史渲染 + 流式发送）

**Files:**
- Create: `android/app/src/main/java/com/sol/sillytavern/native/ui/chat/ChatScreen.kt`
- Create: `android/app/src/main/java/com/sol/sillytavern/native/vm/ChatVM.kt`
- Create: `android/app/src/main/java/com/sol/sillytavern/native/ui/chat/Markdown.kt`
- Create: `android/app/src/test/java/com/sol/sillytavern/native/vm/ChatVMSendTest.kt`

**Interfaces:**
- Consumes: `ChatRepository`、`EngineClient.stream`（Task 5）。
- Produces: `ChatVM.state: StateFlow<ChatUiState>`；`ChatVM.send(text: String)`；`ChatVM.stop()`。

- [ ] **Step 1: 写失败的发送测试**

`ChatVMSendTest` 用假 EngineClient 依次发出 `Delta("Hel")`、`Delta("lo")`、`Done`，断言：状态含一条 `Hello` 的助手消息；`Done` 后触发一次 `save`；`stop()` 后流被取消且不保存半截消息。

Run: CI `testDebugUnitTest --tests '*ChatVMSendTest*'`
Expected: FAIL。

- [ ] **Step 2: 实现 ChatVM**

`send` 调 `POST /api/native/chat`，边收边更新最后一条消息；异常转成可读提示（HTTP 码 + 首行）；`stop` 取消 job 并保留已收到内容为「已停止」。

- [ ] **Step 3: 实现界面**

LazyColumn（`key = index`），气泡左右分布，Markdown 轻量渲染（粗体/斜体/行内代码/代码块/引用/列表/链接）；底部输入框 `imePadding()`；流式时显示「停止」按钮。

- [ ] **Step 4: 手动验证**

Run: CI 出包 → 真机。
Expected: 打开历史会话能看到消息；发一条能逐字出现；中途停止能立即停。

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/java/com/sol/sillytavern/native/ui/chat android/app/src/main/java/com/sol/sillytavern/native/vm/ChatVM.kt android/app/src/test/java/com/sol/sillytavern/native/vm
git commit -m "feat(android): chat screen with streaming replies"
```

---

### Task 9: 剔除 `public/` 并打包验证

**Files:**
- Modify: `scripts/stage-runtime.sh`
- Modify: `docs/ACCEPTANCE.md`（新增原生验收项）

**Interfaces:**
- Produces: `dist/runtime.zip` 不含 `public/`；APK 可安装、可聊天。

- [ ] **Step 1: 改打包脚本**

`stage-runtime.sh` 在拷贝服务端源码后 `rm -rf "$SERVER_DIR/public"`；保留 `public/lib.js`？否——`lib.js` 是前端 bundle，一并删除；确认 `src/` 内无运行时读取 `public/` 的路径（grep 结果只有 `server-main.js:221/242` 与 `users.js:1057`）。

- [ ] **Step 2: 不碰子树**

不修改 `SillyTavern/src/`。输出已确认：`src/` 仅在 `server-main.js:221/242`（静态页）与 `users.js:1057`（登录页）引用 `public/`，原生客户端都不会请求这些路径；缺失目录时 `express.static` 直接跳过。若真机上出现 500，记录到 HANDOFF 再单独处理。

- [ ] **Step 3: CI 出包并验证**

Run: 推送后 Actions `android`。
Expected: APK 体积下降 ≥ 12 MB；真机上冷启动、角色列表、发消息均正常（引擎不依赖 `public/`）。

- [ ] **Step 4: 更新验收清单并提交**

```bash
git add scripts/stage-runtime.sh docs/ACCEPTANCE.md
git commit -m "build: drop the web UI assets from the runtime"
```

---

### Task 10: 端到端回归与文档

**Files:**
- Modify: `HANDOFF.md`（本机，不入库）
- Modify: `README.md`
- Modify: `docs/ACCEPTANCE.md`

- [ ] **Step 1: 回归对比**

同一角色、同一输入，分别用 1.0.3（网页版）与原生版发送，比较模型实际收到的请求体（由服务端 `node.log` 记录或临时日志），逐字段一致。
Expected: 除时间戳/随机 id 外无差异。

- [ ] **Step 2: 真机十项**

冷启动、角色列表、会话列表、发送流式、停止、杀进程重开、深色模式、返回键、键盘遮挡、长会话滚动。

- [ ] **Step 3: 更新文档并提交**

```bash
git add README.md docs/ACCEPTANCE.md
git commit -m "docs: native client status and acceptance"
```

---

## 后续计划（不在本计划内）

M2 消息操作（编辑/删除/重发/继续/复制）、M3 角色编辑与 API 设置、M5 世界书与预设，待 M1 落地后各自单独出计划；M0 若不通过，整条路线回退到方案 B 重新评估。
