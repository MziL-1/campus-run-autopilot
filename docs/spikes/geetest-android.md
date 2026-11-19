# Spike: GeeTest v4 在 APK 内求解（spec R2，Phase 1 定夺项）

- 日期：2026-11-18
- 关联：spec R2（`docs/superpowers/specs/2026-11-16-campus-run-autopilot-design.md`）、plan Task 12、`docs/protocol-7.4-findings.md` §2/§3
- 真机：`9c76d417`（Android API 36，物理分辨率 1264×2780）
- **结论：选方案 (b) —— WebView 执行官方 gt4 JS 流程取校验参数；官方 Android SDK 因分发门槛在本环境不可获得，且其内部机制同样是 WebView + gt4 JS。**

---

## 1. 问题

上游（PC）用 Playwright + 无头 Chromium 跑 `login/geetest_solver.js` 求解极验 v4 滑块，APK 内不可移植（spec R2）。两条备选：

- **(a)** 集成官方 GeeTest Android SDK，登录时用户手滑一次（spec 原文首选）；
- **(b)** WebView 内执行 v4 JS 环境取校验参数（`lot_number / pass_token / gen_time / captcha_output` 四元组），再提交 `geevalidate`。

判定标准：能在真机上用测试账号完成登录、拿到 Token（本任务硬门槛）。

## 2. 方案 (a)：官方 GeeTest Android SDK

### 证据

| # | 证据 | 来源 |
|---|---|---|
| A1 | 官方文档明确 SDK 获取方式为仪表盘下载：*"Access the SDK from the dashboard — Sign in > SDK Download Center > select the corresponding product SDK"*；安装章节要求手动把 `.zip` 里的 `.aar` 拖进 `libs`。**没有给出任何 Maven/Gradle 坐标。** | docs.geetest.com/BehaviorVerification/deploy/client/android（2026-11-18 抓取） |
| A2 | Maven Central 检索（search.maven.org API，2026-11-18）：`g:com.geetest` **0 条**；`g:com.geetest.captcha` 仅 `captcha:1.5.1`（aar，2021-07），其 POM `scm.url = github.com/GeeTeam/gt3-android-maven` —— 是 **v3** SDK，不是 v4。`gt4` 关键字无任何 Android 工件。 | search.maven.org / repo1.maven.org POM 原文 |
| A3 | GeeTeam GitHub 公开仓库列表（api.github.com，2026-11-18）：`gt3-android-sdk`、`gtapp-android`、`gt-face-android`、`gop-android-sdk` 等 —— **没有 v4 Android SDK 的公开仓库**。 | GitHub API |
| A4 | 官方 API 参考自述实现方式：*"The gt4-index.html file of the assets directory is loaded by default"* —— **官方 SDK 内部就是 WebView 加载 gt4 JS 桥接页**，与方案 (b) 同一机制，只是外面包了一层 `.aar`。 | docs.geetest.com/BehaviorVerification/apirefer/api/android |

### 结论

- **(a) 在本环境不可获得**：唯一官方分发渠道是 GeeTest 仪表盘（需账号登录），本项目没有 GeeTest 账号（`captchaId` 属于上游 App 的 GeeTest 租户，我们只是复用其客户端参数）；中央仓库只有 v3。
- 即便获得，(a) 的内部机制仍是「WebView + gt4-index.html + gt4.js」（A4）—— 与 (b) 等价，只多了一层不可审计的闭源壳 + 约 1.2 MB 体积（文档 "Bundle increment: 1205K"）。
- 因此评估重心转移到 (b)：**自己拼装官方 SDK 内部那套 WebView 桥接**。

## 3. 方案 (b)：WebView 执行 v4 JS 流程

### 证据

| # | 证据 | 来源 |
|---|---|---|
| B1 | 上游 `login/assets/` 含官方 `gt4.js`（v4.2.0，15 364 字节，头部 `v4.2.0 Geetest Inc.`）与官方桥接页 `gt4-index.html`（13 602 字节）；该页为 **Android WebView 原生桥** 而写：`nativeCall` 分支直接调 `window.JSInterface.gt4Notify(JSON.stringify({type, data}))`，回调类型 `ready / result / error / close / fail`。 | `reference/upstream/协议/7.3.92/login/assets/` |
| B2 | **Task 2 已在同一服务端实测该 JS 流程**：同一 `captchaId=3b02ad39bd099fd3a8336d9347a189ab`、同一 `gt4.js`，两次独立求解均**第 1 次尝试即通过**，`geevalidate` 业务码 `10000`，`login` 业务码 `10000` 返回真实会话凭据。 | `docs/protocol-7.4-findings.md` §2 登录②③行、§3.2 |
| B3 | WebView 是 Android 系统组件，无第三方依赖、无仪表盘门槛、无体积增量；求解产物（四元组）与 (a) SDK onSuccess 返回的是同一份数据（`getValidate()`）。 | 平台常识 + A4 |
| B4 | 官方 gt4-index.html 的 `jsBridge` 对 `type:'get'`（GeeToken 风控令牌）的应答是**异步增强项**：上游 PC 求解器用的是更简的自建页、完全不处理 `get`，照样 2/2 通过（B2）→ 不响应 `get` 不阻断主流程。 | `login/geetest_solver.js` HTML_CONTENT + B2 |

### 风险与对策

| 风险 | 对策 |
|---|---|
| `file:///android_asset/` 页面向 `https://gcaptcha4.geetest.com` 等发起的请求受 file 源 CORS 限制 | gt4 面向任意客户域名分发，预期 `Access-Control-Allow-Origin: *`；真机实测确认。若失败 → 改用 `WebViewAssetLoader`（https 源）或 `loadDataWithBaseURL`，机制不变 |
| 滑块位移求解：上游用 Playwright 注入的 canvas 边缘匹配（`getImageData`，`crossOrigin='Anonymous'`） | 同段 JS 可原样在 WebView 页面内执行（CORS 已由 B2 间接证明可用）；算出位移后由原生/adb 派发滑动 |
| 求解兜底 | gt4-index.html 本身可交互：自动匹配失败时**用户在 WebView 里手滑一次**即可 —— 正是 spec 方案 (a) 承诺的 UX，零额外成本 |
| WebView 缺失/被禁用 | 真机验证阶段 `pm list packages` 确认；现代设备均有 WebView provider |

## 4. 选定方案与 CaptchaHandler 形状（本 spike 的决定）

**选择 (b)。** 拼装内容与官方 SDK 内部一致：`gt4.js` + 官方 `gt4-index.html` 作为 APK assets，`WebView.addJavascriptInterface(JSInterface, "JSInterface")` 接收 `gt4Notify` 回调。

**CaptchaHandler 形状**（plan 预飞行裁定「T12 自定」，此处定夺；最终实现 `app/.../login/Authenticator.kt`）：

```kotlin
interface CaptchaHandler {
    /** checkGeeUse 判定需要人机校验时由登录工作线程调用；阻塞直至 UI 交回四元组。 */
    fun solve(request: CaptchaRequest): CaptchaResult

    /** checkGeeUse 判定白名单（无需校验）；不弹挑战，状态机直接进入 Submitting。 */
    fun skip()
}
```

理由：

1. brief 钉死 `Authenticator.login(phone, password, captcha): Token` 为**同步单方法** —— 校验只能是 login 内部的一个同步调用点，阻塞式桥接是最小形状；
2. 状态机 `Idle → NeedCaptcha → Submitting → Success/Error` 由 handler 的两个入口驱动：`solve` 进入时发布 `NeedCaptcha` 给 UI（WebView 弹出），返回后发布 `Submitting`（继续 geevalidate + login）；`skip` 是白名单分支的显式信号——**每次尝试恰好一次回调**（`skip()` 直发 `Submitting`），所以白名单路径同样可观测、可测，而不是绕过 handler 静默前进；
3. 阻塞发生在登录工作线程（`Executor`），带 120s 超时；UI 线程只做「展示 WebView / 回传结果 / 取消」三件事，可测性由 Fake Authenticator + 直接观察状态机覆盖（`LoginViewModelTest` 三腿全绿）。

**登录链路**（与上游 `login/client.py` 一致）：

```text
checkGeeUse ── data==true(白名单) ──► captcha.skip() ─────────────► login(Basic) ► Token
     │ 需要校验
     ▼
captcha.solve ──► WebView gt4 流程（用户滑一次）──► 四元组
     ▼
geevalidate(error==10000) ──► login(Basic) ► Token
```

## 5. 真机验证：测试账号登录拿到 Token

**结论：硬门槛通过 —— 真机 `9c76d417` 用测试账号走完 checkGeeUse → gt4 滑块 → geevalidate → login，拿到真实 Token（uid=10000001, unid=90001），杀进程重开后登录态保持。** 2026-11-19 上午实测，APK 与本仓库提交代码一致。

### 5.1 设备链路（adb 命令与关键输出，凭据仅 inline env 注入、不落任何文件）

| 步骤 | 命令 | 结果 |
|---|---|---|
| 安装 | `adb -s 9c76d417 install -r app\build\outputs\apk\debug\app-debug.apk` | `Success` |
| 启动 | `adb shell am start -n dev.campusrun.autopilot/.MainActivity` | `Starting: Intent … MainActivity` |
| 填表 | `adb shell input tap <字段中心>` + `adb shell input text $env:CAMPUS_*`（凭据仅当次进程 env） | 手机号/密码两字段填充；**软键盘会盖住登录按钮** → `input keyevent KEYCODE_BACK` 收起后 `uiautomator dump` 读按钮 bounds 再点其**中心**（盲点固定坐标会点到 IME） |
| 登录 | `adb shell input tap 632 1794`（bounds 中心） | 状态机 → `NeedCaptcha`，WebView 加载 gt4 |

### 5.2 滑块求解（关键修复与自动化）

- **showBox 修复生效（本次第一修复）**：`gt4-index.html` 顶层 `.geetest_captcha` 恒 `display:none`，官方 SDK 由原生侧触发显示；`Gt4Bridge` 收到 `ready` → `evaluateJavascript("window.jsBridge && window.jsBridge.callback('showBox')")`（须 `post` 回 WebView 线程）。实测：`notify type=ready` 后卡片（301×343 CSS px 蓝顶条 + 拼图 + 滑块）**立即可见**，此前"只见标题下方全白"消失。单测 `Gt4BridgeTest` 钉住该分发（ready→reveal、get→无动作、close→取消、error→可读）。
- **求解脚本**（仓库外临时工具，凭据不相关）：`%LOCALAPPDATA%\Temp\opencode\t12solve.py` —— `adb forward tcp:9222` + Playwright CDP 连 WebView，页面内 canvas 边缘匹配（上游 `geetest_solver.js` 同款算法：bg 梯度边缘图 × slice alpha 边界点扫描，`Access-Control-Allow-Origin: *` 实测确认可 `getImageData`），得 `targetDist=185.24 CSS px (rawDist=208, bestScore=79, scale=0.891)`；按钮行程比 `BTN_TRACK=1.0022` → `BTN_DIST=184.33`；随后 **CDP `Input.dispatchTouchEvent` 按上游 human_track（48–65 点、0.85–1.25s、sigmoid 速度+弧形漂移）拖动滑块**，`touchEnd` 后轮询 `captchaObj.getValidate()` 命中。
- 实测 logcat 链（单次尝试即通过，无失败重试）：

```
09:55:23.254 I gt4 : notify type=result
09:55:23.268 I CampusRun: net> POST /api/v70270/security/geevalidate
09:55:23.415 I CampusRun: net< 200 … type=application/json peekLen=347
09:55:23.432 I CampusRun: net> POST /api/v70100/login
09:55:24.678 I CampusRun: net< 200 … type=application/json peekLen=2179
```

- UI 终态：`登录成功 / uid=10000001  unid=90001 / 会话已加密保存在本机`（截图存于本机 `%TEMP%\t12-loggedin.png`，未入库；凭据与四元组不落报告）。

### 5.3 杀进程重开（token 持久化，spec C7）

```
$ adb -s 9c76d417 shell am force-stop dev.campusrun.autopilot
$ adb -s 9c76d417 shell pidof dev.campusrun.autopilot      # (空 — 进程已死)
$ adb -s 9c76d417 shell am start -n dev.campusrun.autopilot/.MainActivity
Starting: Intent { cmp=dev.campusrun.autopilot/.MainActivity }
$ adb shell uiautomator dump … 解析 text=
登录成功 / uid=10000001  unid=90001 / 会话已加密保存在本机
```

- 佐证：`run-as dev.campusrun.autopilot ls shared_prefs/` → `campusrun_secure.xml`（09:55 登录时写入，EncryptedSharedPreferences AES256-SIV/GCM）在 force-stop 后仍在；**再次 `install -r` 覆盖安装后登录态依旧保持**（第三重证明）。`campusrun_params.xml` 为安装期密钥 blob，与会话分离（logout 不可触碰）。

### 5.4 观察与已知边界

- `favicon.ico` 解析失败（`ERR_NAME_NOT_RESOLVED`）为无害噪声，不阻断。
- 120s captcha 窗口内必须完成"填表→提交→求解→拖动"；首轮因软键盘遮挡浪费窗口超时一次（`人机校验超时（120s 未完成）`，状态机正确回落 Error 可重试），修正点按序列后单次通过。
- 设备为 AOD/熄屏时 `screencap` 只得到近黑帧（状态栏在、内容黑）——先 `input keyevent KEYCODE_WAKEUP` + `wm dismiss-keyguard` + `svc power stayon usb` 再截图。
- `checkGeeUse`/`geevalidate` 响应 `peekLen=347`（JSON 非空）—— 早前"200 空 body"根因（ParamsStore 非 ASCII 密钥）修复的持续验证。

## 6. 局限与后续

- 本 spike 只覆盖**登录**；不涉及跑提交（spec 风险纪律）。
- 自动位移匹配是增强项，不是登录必需：匹配失败时退回人工滑动（WebView 原生交互）。
- `captchaId`、端点、四元组字段沿用上游 7.3.92 实测值（Task 2 已在 7.4.x 验证未变）；若 GeeTest 侧轮换 `captchaId`，仅需改一处常量。
