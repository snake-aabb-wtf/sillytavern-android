# SillyTavern Sol 原生客户端（Compose）设计

- 日期：2026-11-03
- 分支：`feature/compose-ui`
- 状态：待评审 / 待批准
- 相关：`docs/superpowers/specs/`、`HANDOFF.md`

## 1. 意图与理由

现在的 App 是一个全屏 WebView，加载本机 `127.0.0.1` 上的 SillyTavern 网页界面。功能完整，但体验是网页的：滚动发涩、输入法遮挡、返回键行为不由我们控制、没有原生手势，也没法做通知、分享、深色主题跟随等系统集成。

目标是把**界面层**换成 Compose 原生实现，网页界面从此不再出现。引擎（提示词组装、世界书注入、角色卡解析、模型调用）保持现状，继续由内嵌的 Node 服务承担，保证输出质量与现在逐字一致。

## 2. 目标

1. 卸载 `WebView` 依赖：界面、导航、设置、日志全部 Compose。
2. 覆盖日常主线：角色列表 → 会话列表 → 聊天（流式输出、重发、编辑、删除、继续）→ 角色编辑 → API 设置。
3. 首个原生版本可覆盖安装现有 1.0.3，`data/` 原样保留、可回退。
4. 安装包剔除 `public/`（27 MB 源码 / 15.0 MB 压缩），启动不变慢。

## 3. 非目标（本设计不做）

- 不重写引擎：不改提示词管线、世界书、正则、分词、角色卡解析逻辑。
- 不做扩展市场、图片生成、TTS、翻译、Data Maid、统计页。
- 不做群聊与多用户账号。
- 不追求与网页界面 1:1 的功能对等；缺的功能就是没有入口，不做降级跳转（网页已删除）。

## 4. 反目标（做了就算失败）

- 为了「全原生」在客户端重新实现提示词组装，导致输出与服务器不一致。
- 客户端直接改写 `settings.json` 或聊天 `.jsonl` 结构，破坏服务端数据。
- 保留隐藏 WebView 作为兜底，却宣称已原生。
- 首版就追求世界书/群聊全覆盖，主线迟迟不能交付。

## 5. 约束

- 本机无 Android SDK，2 vCPU / 1.9 GB：构建与测试全部走 GitHub Actions。
- 引擎为 SillyTavern v1.19.0（`06bde939f`），AGPL-3.0，分发需附源码（即本仓库）。
- 仅 arm64-v8a，minSdk 26，targetSdk 35。
- 服务器默认配置：`whitelistMode: true`、`basicAuthMode: false`、`enableUserAccounts: false`、`disableCsrfProtection: false` → **无需登录，但所有写请求必须带 CSRF 头**。

## 6. 方案选择

### 6.1 界面层（已定）

| 方案 | 做法 | 取舍 |
| --- | --- | --- |
| ① 换皮 | Compose UI + 保留完整运行时（含 `public/`） | 最稳，但白背 27 MB 无用前端 |
| **② 换皮 + 去 public（选中）** | Compose UI + 保留 Node 引擎，运行时剔除 `public/` | 原生界面、体积略降；扩展/提示词管理无 UI |
| ③ 重写引擎 | Kotlin 直连各家 API | 包小、秒开，但提示词/世界书/角色卡全部重写，以月计 |

### 6.2 提示词组装（评审后修正，关键）

**修正前的错误判断**：原设计假设服务端负责组装提示词，客户端只发消息。核对源码后确认不成立——`src/endpoints/backends/chat-completions.js:2242` 的 `/generate` 只做来源分发与转发，`messages` 由调用方提供；角色卡、世界书激活、提示词管理器、上下文裁剪全部在 `public/scripts/openai.js`（7396 行，632 处 jQuery）与 `world-info.js`（6408 行）中。删掉 `public/` 等于删掉提示词引擎，模型只会收到裸聊天记录。

| 方案 | 做法 | 取舍 |
| --- | --- | --- |
| **A. 组装搬到服务端（选中）** | 在 Node 内新增无头组装模块，复用现有 JS 逻辑，UI 只发「角色 + 会话 + 输入」 | 行为与现在一致，代码同一套；需剥离浏览器依赖，工作量中等 |
| B. Kotlin 重写组装 | 客户端自行拼提示词 | 可彻底去掉 Node，但世界书/预设/分词重写以月计，输出必然漂移 |
| C. 隐藏 WebView 组装 | 保留网页只做组装 | 工作量最小，但等于仍背整页网页代码跑，违背「彻底原生」 |

选 A 的理由：唯一能同时满足「原生界面」与「输出不变」的路径，且复用现成逻辑，不引入第二套实现。

**先验证再动手**：M0 试验（约 3 天）——挑三个角色（普通 / 带世界书 / 带预设覆盖），在 Node 内组装并导出请求体，与网页版同一会话的请求体逐字段比对。通过则继续；不通过则回到方案 B 重新评估。

**M0 结果（2026-11-03，结论：通过）**

- 环境：GitHub Actions `ubuntu-latest` + Node 20。同一 job 内先用 Playwright 录制真实网页的 `/generate` 请求体，再在 jsdom 里用同一份角色卡、会话与 `settings.json` 重放并逐字段比对。
- 用例：`character-plain`（普通）、`character-worldinfo`（世界书条目被激活）、`preset-override`（`temp_openai=0.42`、`top_p_openai=0.85`）。
- 结果：3/3 的 `body` **深度相等**，无差异字段；因此对拍白名单为空（`IGNORED_PATHS = []`），没有"忽略未知字段"这种放水。
- 做法：jsdom 只承担 DOM（`index.html`、jQuery、toastr、polyfill）；`script.js` 及其模块图由 Node 直接求值（jsdom 不支持 `<script type="module">`），页面 `fetch` 换成 Node fetch 并共享 jsdom 的 cookie jar，`/generate` 请求被拦截取出 body。`public/` 未做任何修改。
- 耗时：单例端到端 135–139 s（含启动真实 server 与客户端初始化），其中 `build()` 本身 19.4 / 21.0 / 20.3 s；三例合计 411 s。
- 证据：CI run `41618846320`（`Native parity` job 全绿），产物 `generate-bodies` 同时含 `fixtures/reference` 与 `fixtures/headless`。

结论：方案 A 的可行性门禁通过，按 M1 继续。

## 7. 架构

```
Compose UI (screens, viewmodels)
        │  StateFlow
        ▼
Repository 层（CharacterRepository / ChatRepository / SettingsRepository）
        │  领域模型
        ▼
EngineClient（OkHttp + kotlinx.serialization）
        │  127.0.0.1:<port>，Cookie + x-csrf-token
        ▼
Node 引擎
  ├── PromptBuilder（新，无头组装：角色卡 / 世界书 / 提示词管理器 / 裁剪）
  ├── /api/native/chat（新，组装后流式转发）
  └── 现有文件与设置接口（不改）
```

- **EngineClient**：唯一持有 HTTP 的地方。启动时 `GET /csrf-token` 取令牌并保存 Cookie；写请求带 `x-csrf-token`；遇 403 刷新一次令牌重试。
- **Repository**：把 JSON 映射成领域模型，负责读写顺序；不缓存为真相，服务器文件始终是真相。
- **ViewModel**：`StateFlow<UiState>`，无 Android Context 依赖，便于 JVM 单测。
- **UI**：单 Activity + Navigation Compose；Material3；跟随系统深浅色。

### 7.1 目录

```
android/app/src/main/java/com/sol/sillytavern/
  engine/        EngineClient.kt, CsrfInterceptor.kt, SseReader.kt, EngineError.kt
（服务端新增：src/native/prompt-builder.js、src/endpoints/native-chat.js）
  data/          model/, CharacterRepository.kt, ChatRepository.kt, SettingsRepository.kt
  native/ui/     App.kt, theme/, characters/, chats/, chat/, character/, settings/
  native/vm/     CharactersVM.kt, ChatsVM.kt, ChatVM.kt, CharacterEditVM.kt, ApiSettingsVM.kt
```

现有 `server/`、`runtime/`、`node/`、`backup/`、`ui/LogActivity`、`ui/SettingsActivity` 保留；`ui/MainActivity` 改为 Compose 宿主，`web/WebBridge.kt`、`ui/FloatingGearButton.kt` 删除。

## 8. 引擎接口（已核对源码）

| 用途 | 接口 | 备注 |
| --- | --- | --- |
| CSRF | `GET /csrf-token` | 返回 `{token}`，同响应设 Cookie |
| 角色列表 | `POST /api/characters/all` | 返回数组，含 `avatar`、`name`、`description`、`tags` |
| 角色详情 | `POST /api/characters/get` | body `{avatar_url}` |
| 角色会话列表 | `POST /api/characters/chats` | body `{avatar_url}` |
| 新建/编辑角色 | `POST /api/characters/create` `/edit` | JSON body |
| 导入 PNG 角色卡 | `POST /api/characters/import` | multipart `avatar` |
| 头像 | `POST /api/avatars/get` | 返回图片字节；列表用缩略图接口 |
| 读会话 | `POST /api/chats/get` | body `{avatar_url, file_name}`，返回数组 |
| 存会话 | `POST /api/chats/save` | body `{avatar_url, file_name, chat, chat_metadata}` |
| 改名/删除会话 | `POST /api/chats/rename` `/delete` | |
| 设置读写 | `POST /api/settings/get` `/save` | 全量 JSON |
| 生成（新） | `POST /api/native/chat` | body：`avatar_url`、`file_name`、`user_input`、`type`（send/regenerate/continue）、`options`；服务端组装后流式返回 |
| 生成（底层，保留） | `POST /api/backends/chat-completions/generate` | 仅做来源分发与转发，`messages` 由调用方提供；原生 UI 不直接调用 |

**流式契约**：响应为 `text/event-stream`，每行 `data: <json>`，JSON 为 OpenAI 风格 chunk；收到 `[DONE]` 结束。chunk 可能携带 `choices[0].delta.content`、推理内容、工具调用与图片。客户端只消费 `content` 与结束信号；遇到无法解析的字段跳过而非报错。

**生成请求**：由新增的 `PromptBuilder` 在服务端组装，复用 `public/scripts` 中的角色卡、世界书、提示词管理器逻辑（无头环境垫片），组装结果直接送入现有 `/generate` 的转发路径。客户端**不自行拼任何提示词**，只提供会话标识与用户输入。

**首版支持的来源**：OpenAI 兼容（含 OpenRouter，两者均为 OpenAI 风格 SSE）。Claude / Gemini 的流式结构不同，各自一个模块，放在后续里程碑。

## 9. 界面

1. **启动**：沿用现有启动页与进度；`ServerState.Ready` 后进入角色列表。启动失败页保留「查看日志 / 重试 / 导出日志」。
2. **角色列表**：头像网格或列表（可切换），搜索、按最近使用排序；长按进入编辑；右下角新建/导入。
3. **会话列表**：某角色的会话，显示标题、最后消息摘要、时间；滑动删除（二次确认）；新建会话。
4. **聊天**：
   - 气泡区分角色/用户，支持 Markdown 基础渲染（粗体、斜体、代码块、引用、列表）。
   - 流式输出逐字追加，可中途停止。
   - 长按消息：复制、编辑、删除、重新生成（swipe）。
   - 顶部：返回、会话名、角色名；菜单：重命名会话、导出、清空。
   - 底部输入框随键盘上移，支持多行与发送键。
5. **角色编辑**：名称、描述、首条消息、示例对话、头像替换、标签；保存走 `/api/characters/edit`。
6. **API 设置**：源（OpenAI 兼容/Anthropic/Google 等常用几项）、Base URL、API Key、模型名、温度/最大 token；连接测试。
7. **其他设置**：日志入口、数据导出/导入（复用现有 `DataBackup`）、关于。

## 10. 数据与兼容

- 会话文件格式、`settings.json` 结构完全由服务端定义；客户端只读写服务端给的 JSON，保留未知字段（读-改-写时原样带回）。
- 聊天写入时机：消息落定（流式结束、编辑确认、删除）后整文件保存；流式过程中不写盘。
- 覆盖安装：包名不变，`data/` 不动。首个原生版本与 1.0.3 可互相覆盖（同一调试签名）。

## 11. 打包变更

- `scripts/stage-runtime.sh` 剔除 `public/`（保留 `src/`、`node_modules`、`seed`）。
- `server-main.js:221` 的 `index.html` 回退改为返回 404 JSON；`express.static(public)` 目录不存在时自然跳过。
- 预计安装包从 202 MiB 降到约 187 MiB（`public/` 压缩后 15.0 MB）。
- 扩展相关接口依赖 `public/scripts/extensions`，无 UI 调用；若启动时扫描缺失目录，改为容忍空目录。

## 12. 里程碑

- **M0 组装试验（先行门禁）**：Node 内无头组装三个样例角色，与网页版请求体逐字段比对；不通过则回退重估方案。
- **M1 骨架**：`/api/native/chat` 跑通 + EngineClient + CSRF + 角色列表 + 会话列表 + 聊天只读 + 流式发送。
- **M2 消息操作**：编辑、删除、重新生成、继续、停止、复制。
- **M3 角色与设置**：角色新建/编辑/PNG 导入、API 设置与连接测试。
- **M4 打包与验收**：剔除 `public/`、CI 出包、真机验收、更新文档。
- **M5（可选）**：世界书编辑、预设编辑、群聊、Claude/Gemini 流式。

每个里程碑独立 CI + 独立评审，不合并成一次性大提交。

## 13. 验证

- JVM 单测：SSE 解析（分片、跨行、`[DONE]`、异常 chunk）、CSRF 重试、JSON 读写保真、错误映射。
- CI：`assembleGithubDebug` + 单测；产物可安装。
- 真机验收（新清单）：冷启动、角色列表、发消息流式、停止、重发、编辑、删除、新建会话、角色导入、API 设置保存、杀进程重开数据保留、深色模式、返回键、键盘遮挡。
- 回归：同一角色同一输入，原生版与 1.0.3 输出应一致（引擎未变）。

## 14. 风险

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| SSE chunk 结构随 provider 变化 | 输出缺字或报错 | 只消费 `content`，未知字段跳过；解析失败降级为非流式请求 |
| 聊天 JSON 结构演进 | 保存时丢字段 | 读-改-写保留未知键；不构造新对象覆盖 |
| CSRF/Cookie 处理错误 | 所有写操作 403 | 统一拦截器；403 刷新令牌重试一次并记录日志 |
| 缺世界书入口 | 依赖世界书的角色行为「看起来变了」 | 服务端仍加载世界书；只在 UI 缺失，M5 补 |
| 大聊天渲染卡顿 | 滚动掉帧 | LazyColumn + 分页加载；消息项 `key` 稳定 |
| 无 SDK 本地验证 | 只能靠 CI | 每里程碑 CI 出包 + 真机验收 |

## 15. 由我（实现者）决定的细节

- HTTP：OkHttp + kotlinx.serialization（不引入 Retrofit，减少依赖与反射）。
- 导航：Navigation Compose，路由 `characters` / `chats/{avatar}` / `chat/{avatar}/{file}` / `character/{avatar}` / `settings/api`。
- 状态：每个屏幕一个 ViewModel，`StateFlow`；不使用 Room（服务器文件即真相）。
- Markdown：先做轻量自实现（粗体/斜体/代码/引用/列表/链接），不引入完整解析器。
- 主题：Material3 动态取色（Android 12+），低版本用固定配色。

## 16. 已确认与待确认

已确认（用户答复）：

1. 纯原生，网页界面不再出现；走「换皮 + 去 public」。
2. 日常使用 OpenAI 兼容与 OpenRouter —— 首版只做 OpenAI 风格 SSE，两者共用同一解析器。

待用户确认：

1. 是否同意先做 M0 组装试验（约 3 天）再进入界面开发。
