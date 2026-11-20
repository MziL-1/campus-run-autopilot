# Phase 1a 验收记录（spec §6.3 硬门）

- **执行日期**：2026-11-19（灰度前最终验收）
- **基线 commit**：`e429b49`（验收开始前 `git status --porcelain` 为空）
- **环境**：Windows / adb `D:\android\sdk\sdk1\platform-tools\adb.exe`（v35.0.2）/ 真机 `9c76d417`（工具 App `dev.campusrun.autopilot` debug 包已装、会话已登录，冷启动 `session=restored`）/ 官方 App `com.zjwh.android_wh_physicalfitness`（运动世界校园 v7.4.01，同机已装）
- **每日 ≤1 提交纪律**：今日已有的唯一一次提交为 2026-11-19 11:04:56 +08 App 路径一键跑（id=1369631995）。验收全程**未发起第二次提交**（所有回查均为只读）。

## 0. 清单总表

| # | 清单项 | 结果 | 证据 |
|---|---|---|---|
| ① | 全新设备安装→登录→一键跑→**官方 App 回查 valid** | **PARTIAL** — 安装 / 登录 / 一键跑 / 工具侧回查 valid 均 PASS；**官方 App 内回查 PENDING（用户协助步骤）**，指引见 §3 | §1.1 |
| ② | 官方 App 与本工具同机并存，记录设备计数表现（spec §5.5-3 验证项） | **PARTIAL** — 同机并存 PASS（两包并存实测）；**服务端设备计数口径 PENDING（需登录官方 App 查看）**，指引见 §3 | §1.2 |
| ③ | 模拟 `version.json` 高版本 → App 启动提示更新（多镜像之一故意失效验证回退） | **PASS**（AlertDialog 实机渲染 + 可关闭 + 坏镜像回退全链路；关闭 T13 遗留 minor） | §1.3 |
| ④ | 诊断包导出且无凭据 | **PASS**（当日新导出 ×2 拉回逐字节扫描，0 命中） | §1.4 |
| ⑤ | `./gradlew test :cli:liveTest` 全绿 | **PASS**（2026-11-20 更新）——离线全量复跑全绿（core 71 / cli 18 / app 16×2 变体，0 失败）+ `:app:assembleDebug` 成功；**新鲜 `:cli:liveTest` 已于 2026-11-20 13:39 执行并 PASS**（`tests=1 failures=0`，预检 CLEAN 后单次提交，证据见灰度日志第 1 天：`rrid=1370876678`、`2030 m`、`valid=true`、OBS uuid `6a374786…`） | §1.5 |

> 结论：**允许自 2026-11-20 起进入灰度（开发者自用）**，前提是 §2 所列条件全部成立；公开分发仍由 spec §6.3 七日标准把关（模板：`docs/acceptance/grayscale-log-template.md`）。

---

## 1. 逐项执行与证据

### 1.1 ① 安装→登录→一键跑→官方 App 回查

**安装 / 登录 / 一键跑（PASS，引 T12/T13 + 今日复核）**

- 安装：工具 debug APK 安装于真机 9c76d417（T12 装机、T13 冒烟复装），今日会话持久化实证：诊断日志 `app: cold start; session=restored`（见 §1.4 导出内容）。
- 登录：T12 真机登录完成（WebView + GeeTest 人工滑块），2026-11-19；首页无手机号、无登录表单（今日 Home dump 复核）。
- 一键跑：T13 冒烟 **2026-11-19 11:04:56 +08**：`gate: passed → OBS push accepted → settle save/record accepted, rrid=1369631995 → verify: id=1369631995, 2030 m, valid=true`；UI 显示 `提交成功：2030 m，达标（记录 id=1369631995）`。
- 今日只读复核（11:40:47 与 11:47:42 两次冷启动 Home dump）：`最近记录：id=1369631995 · 2030 m · 达标` —— 经 `HistoryClient.latest` 只读回查，最新记录即今日 11:04:56 那次 ⇒ 其后**无任何提交**，今日恰为 1 次（11:04:56 前为 0 次由 T13 pre-flight 记录）。

**官方 App 回查 valid（PENDING — 用户协助）**

- 非侵入尝试（2026-11-19 ~11:43）：`monkey -p com.zjwh.android_wh_physicalfitness -c android.intent.category.LAUNCHER 1` → 当前焦点直达 `com.zjwh.android_wh_physicalfitness.mvi.login.LoginActivity`，uiautomator dump 显示**登录页**（手机号栏记忆值 `151****9136`、密码栏 `请输入密码` 为空、登录按钮未触发）。
- 按裁定**未输入任何凭据、未触发登录**（滑块需人工，且登录官方 App 可能与工具会话互相顶号）。官方 App 内"历史记录显示 11-19 记录有效"这一环因此 **PENDING**，用户操作指引见 §3。
- 说明：设备为用户真机（非出厂重置），"全新设备安装"按"工具全新装机 + 与官方 App 并存"的真实目标形态执行，与 ② 的并存验证一致；工具侧协议回查（同 T9 硬门槛 `HistoryClient valid=true`）两次均 PASS，官方 UI 侧回查待补。

### 1.2 ② 同机并存与设备计数（spec §5.5-3）

- **并存（PASS）**：`adb shell pm list packages -3` 同时列出
  `package:com.zjwh.android_wh_physicalfitness`（官方，v7.4.01）与 `package:dev.campusrun.autopilot`（本工具）。
  11-17（T9 CLI）与 11-19（T13/T14 App）全部提交均在此并存状态下完成，两 App 无互斥、无安装冲突；本工具全程未读取官方 App 私有存储（无 Root，spec §5.5-3 约束）。
- **服务端设备计数表现（PENDING — 需登录官方 App）**：设备台数只有登录官方 App 后在"我的/设备管理"类页面可见；官方 App 当前为登出态（见 ①），非侵入手段无法观测。同物理设备是否因 OAID 同源被计为同一台（§5.5-3 核心问题）、是否触及"单账号 ≤3 设备"红线，均待用户按 §3 完成一次登录后回填。

### 1.3 ③ 更新提示模拟（PASS — 关闭 T13 遗留 minor「update AlertDialog never rendered on device」）

**基线（clean 构建）**：UPDATE_MIRRORS 为占位 jsDelivr/raw，今日 11:40:47（模拟前）与 11:47:42（模拟后复装）两次冷启动均**无**更新弹窗，诊断日志两镜像 `HTTP 404` / `timeout` → 按设计静默（fail-silent 基线成立）。

**模拟方法（临时 debug-only 补丁，未进 git）**：本地 mock `version.json`（`minApkVersionCode=99999` ≫ 当前 `versionCode=1`）经 `adb reverse tcp:18098 tcp:18098` + `python -m http.server 18098` 供到设备 localhost；镜像列表**首位故意放死端口 18099**（设备本机无监听）以证有序回退。临时补丁恰为两处、**仅存在于本地 debug 构建**：

```diff
--- a/app/src/main/java/dev/campusrun/autopilot/MainActivity.kt
+++ b/app/src/main/java/dev/campusrun/autopilot/MainActivity.kt
@@ UPDATE_MIRRORS
-        private val UPDATE_MIRRORS = listOf(
-            "https://cdn.jsdelivr.net/gh/OWNER/REPO@main",
-            "https://raw.githubusercontent.com/OWNER/REPO/main",
-        )
+        // TEMPORARY (T14 acceptance simulation only, never committed): first mirror
+        // deliberately dead (nothing listens on device localhost:18099) ...
+        private val UPDATE_MIRRORS = listOf(
+            "http://127.0.0.1:18099",
+            "http://127.0.0.1:18098",
+        )

--- a/app/src/debug/AndroidManifest.xml (新增, DEBUG source set only)
+<?xml version="1.0" encoding="utf-8"?>
+<!-- TEMPORARY (T14 acceptance simulation only, never committed) ... -->
+<manifest xmlns:android="http://schemas.android.com/apk/res/android">
+    <application android:usesCleartextTraffic="true" />
+</manifest>
```

**结果（全部命中）**：

1. **坏镜像回退**：logcat `11-19 11:45:01.103 I CampusRun: update> update: mirror http://127.0.0.1:18099/config/version.json unreachable: java.net.ConnectException: Failed to connect to /127.0.0.1:18099` → 未中断，继续第二镜像；
2. **第二镜像命中**：mock 服务访问日志 `127.0.0.1 - - [19/Nov/2026 11:45:04] "GET /config/version.json HTTP/1.1" 200`（设备经 adb reverse 的那次请求；另一次 11:44:39 为宿主侧自检）；诊断日志 `update: version document: minApkVersionCode=99999 configVersion=1 protocolParamsVersion=1`；
3. **AlertDialog 实机渲染**（uiautomator dump 逐字）：标题 `发现新版本`；正文 `发现新版本：最低要求版本号 99999（当前 1）。请下载更新最新 APK 后继续使用。`；按钮 `知道了`；截屏留档 `t14-update-dialog.png`（会话本地，未入库）；
4. **可关闭、非阻断**：点击 `知道了` 后复 dump：`dialog still present: False`，`home visible: True`。

**发布安全与清理**：

- 补丁**从未提交**：模拟结束后 `git checkout -- MainActivity.kt` + 删除 debug manifest，`git status --porcelain` 为空（回到 `e429b49`）；release 构建从未包含该覆盖。
- 清理完成：`adb reverse --remove tcp:18098`、mock server（PID 36596）已杀；**以干净树重建 `:app:assembleDebug` 并 `adb install -r` 复装**，复装后冷启动复核：无更新弹窗（`发现新版本` absent）、Home 正常、`最近记录：id=1369631995` 仍在 —— 设备已回到等同发布物的干净状态。
- 结论：更新链路"多镜像有序回退 + 高版本强制提示 + 可关闭"三条行为在真机上端到端成立；正式版仍指向 jsDelivr/raw 占位常量（发布时仅改 `UPDATE_MIRRORS` 一处）。

### 1.4 ④ 诊断包导出且无凭据（PASS — 当日新导出、拉回、逐项扫描）

当日经产品路径新导出（首页 `导出诊断` → `导出诊断包`），并从设备拉回两份互补日志：

| 导出 | 时间（设备 mtime） | 大小 | 覆盖面 |
|---|---|---|---|
| `diagnostics-1795059981156.zip` | 2026-11-19 11:46 | 547 B | 含更新检查日志的当次进程 |
| `diagnostics-1795057647307.zip` | 2026-11-19 11:07 | 907 B | **含完整跑程阶段日志**（sync/rule/synth/gate/upload/settle/verify，最易带凭据的路径） |

扫描对象 = 解压后三个条目（`log.txt`/`config.txt`/`results.txt`）全文 **与 zip 原始字节**（Latin-1 解码后正则），结果：

| 扫描式 | 解压全文命中 | zip 原始字节命中 |
|---|---|---|
| `(?<!\d)1[3-9]\d{9}(?!\d)`（手机号） | 0 | 0 |
| `password` / `passwd` / `pwd` | 0 | 0 |
| `token` / `token=` / `Authorization` | 0 | 0 |
| `secret` / `CAMPUS_` / `unid` / `uid=` | 0 | 0 |

跑程进程导出全文（人工复核同样无凭据；含 `session=restored` 但**无 token 值**）：

```
configVersion=1
app: cold start; session=restored
update: update: mirror https://cdn.jsdelivr.net/gh/OWNER/REPO@main/config/version.json answered HTTP 404
update: update: mirror https://raw.githubusercontent.com/OWNER/REPO/main/config/version.json unreachable: java.net.SocketTimeoutException: timeout
run: sync: server clock skew 539 ms
run: rule: window 10-22 h, pace 180-600 s/km, minDistance 2000 m, dayGoal 2000 m, dailyLimit 0
run: synth: derived 4-point fence around 5 checkpoints
run: synth: seed 2764195757612805891, 761 samples at 1 Hz
run: gate: passed (fence/checkpoints/pace/distance/window/face)
run: upload: OBS push accepted, uuid 4f2045f9-0fc5-4b4f-9b19-90807f1664ab
run: settle: save/record accepted, rrid=1369631995
run: verify: id=1369631995, 2030 m, valid=true
一键跑：提交成功：2030 m，达标（记录 id=1369631995）
```

附注：两份导出中 `[REDACTED]` 标记数为 0 —— 本进程日志恰好不含需脱敏字面量（脱敏行为本身由 `DiagnosticExportTest` 3 项钉死，今日套件全绿；T11 亦以 bait 值做过正反双向断言）。`results.txt` 在 11:46 导出中为空（当次进程无跑程，符合设计）。

### 1.5 ⑤ 测试套件（离线今日全跑；live 按每日 ≤1 裁定引用排期）

**离线（今日执行）**

- `.\gradlew.bat test --console=plain` → **`BUILD SUCCESSFUL in 54s`**（54 actionable tasks: 18 executed, 5 from cache, 31 up-to-date）。
- 结果 XML 计数（`build/test-results/**/TEST-*.xml`）：

| 模块 | tests | failures | errors | 说明 |
|---|---|---|---|---|
| core | 71 | 0 | 0 | 13 个测试类 |
| cli（离线） | 18 | 0 | 0 | CliSmoke 1 + DailyLimitWiring 5 + Pacing 1 + Validator 11 |
| app（debug 变体） | 16 | 0 | 0 | Home 5 + Gt4Bridge 4 + Login 3 + Run 3 + Smoke 1 |
| app（release 变体复跑同一批） | 16 | 0 | 0 | 与 debug 变体同 16 项，共 32 次执行 |
| **合计（去重）** | **105** | **0** | **0** | 今日执行含 release 变体共 121 次 |
| cli `:cli:liveTest`（不在 `test` 内） | — | — | — | 今日**未执行**，见下 |

- XML 时间戳：core/cli 为 2026-11-18 执行（今日 UP-TO-DATE，输入未变仍计绿）；app 为 2026-11-19（T13）执行 + 今日 release 变体复跑。
- `.\gradlew.bat :app:assembleDebug` → **BUILD SUCCESSFUL**（模拟构建与清理后重建各一次，见 1.3）。

**live 部分（裁定路径：引用既有证据 + 排期，不今日重跑）**

- **今日提交预算复核（先于决定）**：两次只读冷启动 Home（11:40:47、11:47:42，走 `HistoryClient.latest`）最新记录恒为 `id=1369631995`（今日 11:04:56 App 路径提交），其后无更新记录 ⇒ 今日已 1 次；此时跑 `:cli:liveTest` 必然构成当日第 2 次提交，违反我们自己的每日 ≤1 风险规则 → **不跑**。
- **两次既有 live 证据**：
  1. T9 `:cli:liveTest` **PASS**，2026-11-17（结果文件 `TEST-dev.campusrun.cli.LiveRunTest.xml`：`tests=1 failures=0 errors=0`，`timestamp=2026-11-17T09:57:36Z` = 本地 17:57:36、文件 mtime 17:57:39，与 T9 链 v4 排程一致；输出 `[live] settle: save/record accepted, rrid=1367719089`、`[live] verify: id=1367719089, 2030 m, valid=true`）；
  2. 2026-11-19 App 路径一键跑 **PASS**，`id=1369631995, 2030 m, valid=true`（11:04:56，今日唯一提交，§1.1）。
- **新鲜 `:cli:liveTest` 已于 2026-11-20 13:39 执行全绿**（灰度第 1 天，排期兑现）：登录铸 token → 预检 `PREFLIGHT=CLEAN` → `CAMPUS_ALLOW_LIVE_SUBMIT=1` → `tests=1 failures=0`；输出 `[live] settle: save/record accepted, rrid=1370876678`、`[live] verify: id=1370876678, 2030 m, valid=true`。执行脚本 `reference/out/_obs/grayscale_day1.ps1`（凭据仅经环境变量，ASCII-only 规避 PS5.1 编码坑）。

---

## 2. 门禁结论（spec §6.3）

1. **允许自 2026-11-20 起进入灰度（开发者自用）**，生效条件：
   - 2026-11-20 执行新鲜 `:cli:liveTest` 全绿（§1.5 排期，第一优先）；
   - 每日 ≤1 提交纪律持续执行；提交保持在校时段 10:00–22:00 内；
   - 灰度日志逐日填写：`docs/acceptance/grayscale-log-template.md`；
   - ①② 的官方 App 侧回查/设备计数按 §3 完成并回填（首次回查不晚于灰度第 2 天，即对 11-20 记录做次日回查）。
2. **公开分发仍被 spec §6.3 硬门锁住**：连续 7 天达标标准见灰度日志模板原文，未满 7 天或任一天出现风控信号 → 不得公开分发。
3. **观察项（记录在案，非阻断）**：
   - **2030 m vs dayGoal 2000 m**（T9 遗留判断项 → 本验收裁定）：两次记录（11-17、11-19）均 2030 m（超广告日上限 +30 m，约 +1.5%），服务端均接受且 `valid=true`，未见任何拒绝或告警 → **判为可接受，列入灰度风控观察**；若任一日服务端收紧（complete=false / 拒收），立即将目标距离收敛为 `≤ dayGoal`。
   - `runRuleModel` 仅 10:00–22:00 可用（T2 探测），提交必须保持窗口内；灰度日志"提交结果"列同时记录提交时刻。
   - 官方 App 登录可能触发 10121 单设备登录保护（见 §3 注意事项），出现时按指引处置并记入"风控观察"。
4. **今日未发现新缺陷**：离线套件全绿、构建成功、设备链路（登录态持久化、历史刷新、诊断导出、更新通道）行为与设计一致。

## 3. PENDING 项用户操作指引（① 官方回查 + ② 设备计数）

> 目标：补齐"官方 App 内回查 valid"与"服务端设备计数"两环。需要人工登录（滑块验证），自动化不代替。全程约 5 分钟，只需做一次（此后灰度期间每天次日回查 1 分钟）。

**注意事项（先读）**

- 请用与工具**相同的账号**登录本机（9c76d417）上的 **运动世界校园**（`com.zjwh.android_wh_physicalfitness`）。
- 官方 App 登录页当前状态（2026-11-19 11:43 实测）：手机号栏已记忆 `151****9136`，**密码栏为空**，停在登录页。
- 滑块验证需**人工完成**（与工具内登录相同，我们不代打码）。
- 登录官方 App 理论上与工具同物理设备（OAID 同源）应互不顶号——但若之后工具登录/提交返回 **10121 单设备登录保护**：等待约 1 小时，或退出官方 App 后在工具内重新登录（滑块人工）。此情况请告知执行者并记入灰度日志"风控观察"。

**步骤**

1. 打开 **运动世界校园** App → 完成登录（手机 + 密码 + 人工滑块）。
2. **[① 官方回查]** 进入跑步记录/历史（跑量/成绩页）：
   - 找到 **2026-11-19** 的记录：**2030 m**，应显示 **有效 / 达标**（对应工具侧 id=1369631995）；
   - 记下官方页显示的日期、距离、有效性原文。
3. **[② 设备计数]** 进入"我的 → 设置 / 账号 / 设备管理"（不同版本入口名略有差异，找"登录设备 / 设备管理"）：
   - 记录**设备台数**与设备列表条目；
   - 重点回答 spec §5.5-3 的问题：**本工具与官方 App 是否计为同一台设备**（同手机上两者并存，预期同源计 1 台）；并确认是否触及"单账号 ≤3 设备"红线。
4. **回填**（二选一）：
   - 直接编辑 `docs/acceptance/grayscale-log-template.md` 对应行的"回查结果"列，并在"风控观察"列记录设备台数结论；或
   - 把两处截图/文字发给执行者，由执行者更新本文件 §1.1 / §1.2 的 PENDING 状态。
5. （可选）回查完成后可退出官方 App；灰度期间**每天次日**重复步骤 1–4 中的步骤 2（回查前一日记录），写入灰度日志。

## 4. 自查记录（self-review）

- **完整性**：五项均已执行并留痕；③④⑤-离线 = PASS（含命令输出/计数/id/时间戳）；①②⑤-live = PARTIAL/PENDING，原因（官方 App 登出需人工滑块、每日 ≤1 纪律）与恢复路径（§3、§1.5 排期）均已写明，无假绿勾。
- **纪律**：今日恰一次提交（11:04:56），验收内所有回查只读；更新模拟补丁为临时 debug-only 且未入库（验收前后 `git status` 均为空）；本提交仅 `docs/acceptance/` 两文件；文档不含密码/token/完整手机号（官方页手机号以 `151****9136` 记录）。
- **诚实性**：T13 2030 m vs dayGoal 的判断项按证据裁定为"可接受+灰度观察"而非隐藏；liveTest 未今日重跑的原因是纪律而非省事，且已排期。
- **证据文件（会话本地，未入库）**：`%TEMP%\opencode\` 下 `t14-home.xml`、`t14-diag2.xml`、`t14-update-prompt.xml`、`t14-update-dialog.png`、`t14-after-dismiss.xml`、`t14-official-login.xml`、`t14-baseline2.xml`、`t14-update-sim\`、`t14-diag-export\`。
- **设备状态（可选清理，按裁定留置）**：`stayon usb`、`screen_off_timeout`、T12 遗留 `adb forward tcp:9222` 未回收；工具为干净重建 debug 包且会话保留；官方 App 留在登录页未动；`adb reverse` 已移除。锁屏 keyguard 在验收中被临时解除（`wm dismiss-keyguard`）。

## 5. 执行者裁定（final-fix wave 补记）

以下三条为整支分支 review 后执行者作出的裁定，记录于此以免后续波次重新讨论。

### 5.1 telemetry = 有意延后（deliberate deferral）

spec §5.1 架构图中的 `telemetry 冷启动/页面曝光埋点模拟（行为一致性）` 层**在 Phase 1a 未实现，
且这是有意延后而非遗漏**：该层的唯一价值是让工具的埋点行为与官方 App 一致，从而降低被
"行为不一致"风控命中的概率；但在拿到"服务端确实校验埋点"的证据之前，实现它只是凭空增加
出错面与维护成本（埋点字段一变就要跟着改，反而更容易暴露差异）。因此裁定：**先不实现，
直到出现服务端依赖埋点的实证**（例如官方 App 抓包显示某埋点缺失会导致校验失败、或风控
信号明确指向行为序列）。届时再按 spec §5.1 补齐，并在灰度日志中记录触发证据。在此之前，
任何"补齐 telemetry"的排期请求都应被当作范围扩张拒绝。

### 5.2 OAID = 延后至验收②的设备计数证据

Phase 1a 的设备身份策略是**基于 `Settings.Secure.ANDROID_ID` + 机型/厂商 hint 的本地持久化
稳定 ID**（`MainActivity.resolveDeviceIdentity` → `DeviceIdentity.stableId()`，首次登录后
以 `DeviceIdentitySnapshot` 固定，之后 hint 变化不再改写）。这是**已记录的策略**而非妥协：
无 Root 读不到官方 App 的私有存储，而 OAID 需要额外的 SDK 接入与用户授权流程，成本高且
收益未知。**是否改用 OAID 同源上报，取决于验收②（本文件 §1.2）的设备计数结论**：若官方
App 与本工具在同一台手机上被服务端计为两台设备，则 OAID 同源的收益成立、值得接入；若
已计为同一台，则维持 androidId 方案、OAID 继续延后。换言之 OAID 不是 Phase 1a 的待办，
而是验收② PENDING 项的**条件后续**。

### 5.3 参数热更 = 强制 APK（常备策略）

**`params` 变更 = forced APK，直到存在 params-doc 端点为止。** 仓库内没有
`config/params.json` 之类的远端协议参数文档，`version.json` 只覆盖最低 APK 版本、配置
版本、协议参数版本与下载镜像 URL 四项（spec §5.6），因此签名盐 / endpoint / RSA 公钥等
协议参数的任何变更都必须通过**发布新 APK** 分发，不得依赖热更通道。这条与
`MainActivity.UPDATE_MIRRORS` KDoc 中的既有说明一致，此处提升为验收文档级的常备策略；
当且仅当 params-doc 端点真正落地后才可重新评估。同一边界下的另一半也成立：逻辑级变更
（含 `minApkVersionCode` 抬升）同样只走"提示新 APK"，不做脚本引擎 / 动态代码加载。
