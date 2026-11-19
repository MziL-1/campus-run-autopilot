# Phase 1a — 运动世界协议引擎 Android APK 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付一个免费 Android APK：登录运动世界校园账号后一键生成并提交合规跑步记录，官方 App 内可查到有效成绩。

**Architecture:** 纯 Kotlin/JVM `core` 模块承载全部协议逻辑（同步 API），被 `cli` JVM 工具（黑盒探测、一键跑、验收）与 Android `app` 壳（Compose）共享。模块边界按上游 Python 套件（Inverx，MIT，`协议/7.3.92/`）移植；先用上游 CLI 对线上 7.4.x 做黑盒探测定差异，再逐模块移植。

**Tech Stack:** Kotlin 2.0.21 · JDK 17 · Gradle 8.10.2 · AGP 8.7.2 · OkHttp 4.12.0 · kotlinx-serialization 1.7.3 · JUnit 5.10.2 · Jetpack Compose BOM 2024.10.01

**Spec:** `docs/superpowers/specs/2026-11-16-campus-run-autopilot-design.md`（本计划全部论据来源）

**Scope:** 仅覆盖设计 §5.2 的 **Phase 1a**。1b（虚拟定位引擎）与 Phase 2（桌面端）各自另立计划。

## Global Constraints

- 免费分发、**无后端**、不收集/上传用户凭证（spec §2 反目标、C7）
- 风控优先于功能：每日默认提交 ≤1 次；配速/时段/围栏/必经点必须与 policy 一致（spec C2、§5.5）
- MIT 署名：README 与 App 关于页保留上游链接与致谢（spec C3、§5.3）
- 热更边界：盐/endpoint/字段集进配置；逻辑变更=提示新 APK；**禁止**脚本引擎/动态代码加载（spec §5.6）
- 凭据只存 `EncryptedSharedPreferences`（App）或本地文件（CLI），永不入 Git、永不外发（spec C7、§7）
- `applicationId = dev.campusrun.autopilot`，minSdk 26，targetSdk/compileSdk 35
- `core` 模块禁止依赖任何 Android API
- Live 测试一律用 JUnit tag `live` 隔离（Gradle 任务 `:cli:liveTest`）；凭据仅经环境变量 `CAMPUS_PHONE` / `CAMPUS_PASSWORD` 传入

## Review Focus

以下 5 项是 spec 隐含、最可能咬人的失败模式；每项在拥有它的任务里有对应测试：

1. **7.4.x 协议漂移**：上游按 7.3.92 实现，线上是 7.4.x——盲移植会在签名/端点处全军覆没。→ Task 2 探测报告先行，Task 9 的 live 测试必须在 7.4.x 真实跑通才算完成。
2. **GeeTest v4 APK 内无法求解**（spec R2）：登录做不进 App = 整个 APK 无意义。→ Task 12 spike 步骤以"真机登录拿到 token"为硬性验证。
3. **轨迹违反服务端规则**（出围栏/漏必经点/配速越界/人脸窗口内提交）→ Task 7 property 测试 + Task 9 的 `RunPolicy.validate` 负例测试。
4. **凭据泄漏进日志/诊断包**：免费开源分发下 token/密码出现在用户回传的诊断包里。→ Task 11 断言导出内容中密码/token/手机号均为 `[REDACTED]`。
5. **提交时序异常**（瞬时提交、时长与距离矛盾触发风控）→ Task 9 离线测试断言结算按 `durationSec` 分桶节奏推进，非一次性瞬时提交。

---

### Task 1: 工程骨架与环境

**Files:**
- Create: `settings.gradle.kts`、`build.gradle.kts`、`gradle.properties`、Gradle wrapper、`.gitignore`
- Create: `core/build.gradle.kts`、`cli/build.gradle.kts`、`app/build.gradle.kts`（空壳 + 依赖声明）
- Create: `README.md`（含 MIT 上游致谢段落，占位后 Task 3 补链接细节）

**Interfaces:**
- Produces: 三模块可构建；`./gradlew test` 绿（每模块一个 smoke test）。后续任务全部在此骨架内工作。

- [ ] **Step 1: 环境检查**

Run: `java -version`（需 17）、`$env:ANDROID_HOME` 或 `local.properties` 中 `sdk.dir` 存在、`git --version`
Expected: 三项齐备；Android SDK 缺失时先安装 platform 35 + build-tools（AGP 会报明确错误，按报错补齐）。

- [ ] **Step 2: 初始化仓库与 Gradle 骨架**

`git init`；创建上述构建文件（Gradle wrapper 用 `gradle wrapper --gradle-version 8.10.2` 生成）；`.gitignore` 必须含 `reference/`、`.env`、`local.properties`、`*.log`。
Run: `./gradlew projects`
Expected: 输出含 `:core`、`:cli`、`:app`

- [ ] **Step 3: 每模块 smoke test**

各模块放一个 trivial 断言（如 `assertEquals(1, 1)`，包名 `dev.campusrun.*`）。
Run: `./gradlew test :app:assembleDebug`
Expected: 全绿，APK 产出。

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "chore: gradle multi-module skeleton (core/cli/app)"
```

---

### Task 2: 线上协议黑盒探测（7.4.x 差异报告）

**Files:**
- Create: `tools/probe.ps1`（探测编排脚本，逐阶段调用上游 CLI 并保存输出）
- Create: `docs/protocol-7.4-findings.md`（交付物）
- Create: `core/src/test/resources/policy/`（本任务顺带保存 rules/policy 原始响应作 fixture，**先占位**，Task 6 使用）

**Interfaces:**
- Consumes: 无（上游 Python CLI，位于 `reference/upstream/`）
- Produces: 差异报告与 fixture；**Task 3–9 的移植范围以本报告为准**——若某阶段 7.4.x 已失效，对应任务先按报告调整。

- [ ] **Step 1: 拉取上游并装运行环境**

```powershell
git clone https://github.com/Inverx/com.zjwh.android_wh_physicalfitness.git reference/upstream
python -m venv reference/venv
reference/venv/Scripts/pip install -r "reference/upstream/协议/7.3.92/requirements.txt" 2>$null; reference/venv/Scripts/pip install playwright
reference/venv/Scripts/playwright install chromium
```
Expected: 无报错（7.3.92 核心为标准库，缺包按 requirements 补）。

- [ ] **Step 2: 逐阶段探测（需用户提供账号，凭据走环境变量）**

探测阶段（每阶段输出存 `reference/out/<stage>.json`）：`sync-time` → 登录（`-u/-p`，含极验滑块）→ `profile` → `rules --mode 1` → `history`。
Expected: 每阶段记录 HTTP 结果与成败。

- [ ] **Step 3: 写差异报告**

`docs/protocol-7.4-findings.md` 含表格：阶段 / 上游端点 / 7.4.x 实测结果 / 差异结论（通过·参数变·签名变·端点变），并列出后续任务需调整项。
Run: `Select-String -Path docs/protocol-7.4-findings.md -Pattern "通过|失效|变化"` 存在结论行。
**红线检查**：`Select-String -Path docs\protocol-7.4-findings.md -Pattern "token|password|1[3-9]\d{9}" -CaseSensitive` 必须无输出。

- [ ] **Step 4: Commit**

```bash
git add tools/probe.ps1 docs/protocol-7.4-findings.md core/src/test/resources/policy
git commit -m "docs: probe live 7.4.x protocol, record findings"
```

---

### Task 3: Crypto 移植（NetSecKit 4.2.4）

**Files:**
- Create: `core/src/main/kotlin/dev/campusrun/core/crypto/NetSecKit.kt`
- Create: `core/src/main/kotlin/dev/campusrun/core/config/ProtocolParams.kt`
- Create: `tools/gen-vectors.py`（驱动上游 Python 生成跨语言测试向量）
- Test: `core/src/test/kotlin/dev/campusrun/core/crypto/NetSecKitTest.kt`、`core/src/test/resources/vectors/netsec.json`

**Interfaces:**
- Produces:
  - `data class ProtocolParams(val aesKey: ByteArray, val kdfSalt: ByteArray, val rsaPublicKey: String, val endpointBase: String, val signSalt: String, val versionTag: Int)` — 线格式细节以上游 `协议/7.3.92/core/` 对应实现为准；本类是 Task 10 热更的载荷。
  - `object NetSecKit { fun encrypt(plain: ByteArray, params: ProtocolParams): ByteArray; fun decrypt(payload: ByteArray, params: ProtocolParams): ByteArray }`
  - 覆盖 spec C2 所列：AES-128-CBC + Feistel 变体 KDF + RSA PKCS#1 v1.5 密钥封装，与上游 Python 输出逐字节一致。

- [ ] **Step 1: 生成跨语言向量**

`tools/gen-vectors.py` import 上游 core 模块，对固定输入集（≥3 组 plain/key/wrap）输出 `vectors/netsec.json`。
Run: `python tools/gen-vectors.py`
Expected: 文件生成且含 `cases[]`。

- [ ] **Step 2: 写失败测试**

```kotlin
@Test fun `netsec roundtrip`() { val p = params(); assertEquals(plain, NetSecKit.decrypt(NetSecKit.encrypt(plain, p), p)) }
@Test fun `netsec matches python vectors`() {
    val cases = loadVectors("vectors/netsec.json")
    cases.forEach { c -> assertArrayEquals(c.expectedCipher, NetSecKit.encrypt(c.plain, params(c)), "case ${c.name}") }
}
```
Run: `./gradlew :core:test`
Expected: FAIL（`NetSecKit` 未定义）。

- [ ] **Step 3: 按上游实现移植**

在 `NetSecKit.kt` 实现三段算法；Feistel KDF 逐轮逻辑与上游 Python 对照，不自行"改良"。
Run: `./gradlew :core:test`
Expected: PASS（两测试）。

- [ ] **Step 4: Commit**

```bash
git add core tools/gen-vectors.py
git commit -m "feat(core): port NetSecKit 4.2.4 crypto with python cross-vectors"
```

---

### Task 4: 签名移植（headerSign / tokenSign / 31 字段结算 MD5）

**Files:**
- Create: `core/src/main/kotlin/dev/campusrun/core/crypto/Signer.kt`
- Test: `core/src/test/kotlin/dev/campusrun/core/crypto/SignerTest.kt`、`vectors/sign.json`（gen-vectors.py 扩展）

**Interfaces:**
- Consumes: `ProtocolParams`（Task 3）
- Produces:
  - `object Signer { fun headerSign(headers: Map<String, String>, params: ProtocolParams): String; fun tokenSign(token: String, params: ProtocolParams): String; fun settlementDigest(fields: List<String>, salt: String): String }`
  - `settlementDigest` 的字段顺序与加盐规则按上游 31 字段结算实现（含字段拼接分隔符）；列表由 Task 8 组装。

- [ ] **Step 1: 扩展向量生成**

`gen-vectors.py` 增加三类固定输入 → `vectors/sign.json`。Run 后文件含 `header[]`、`token[]`、`settlement[]`。
Run: `python tools/gen-vectors.py`

- [ ] **Step 2: 写失败测试**

`headerSign matches python` / `tokenSign matches python` / `settlementDigest 31-field golden value`（golden 值来自向量文件）。
Run: `./gradlew :core:test` → Expected: FAIL。

- [ ] **Step 3: 实现 `Signer.kt`**
Run: `./gradlew :core:test` → Expected: PASS。

- [ ] **Step 4: Commit**

```bash
git add core tools/gen-vectors.py
git commit -m "feat(core): port header/token/settlement signatures"
```

---

### Task 5: 会话、时间同步与设备身份

**Files:**
- Create: `core/src/main/kotlin/dev/campusrun/core/session/{SessionManager.kt, TimeSync.kt, TokenStore.kt}`
- Create: `core/src/main/kotlin/dev/campusrun/core/identity/DeviceIdentity.kt`
- Create: `core/src/main/kotlin/dev/campusrun/core/api/ApiClient.kt`（OkHttp 薄封装：base、headers 注入、序列化）
- Test: `core/src/test/kotlin/dev/campusrun/core/session/TimeSyncTest.kt`、`identity/DeviceIdentityTest.kt`

**Interfaces:**
- Consumes: `Signer`、`NetSecKit`（Task 3/4）
- Produces:
  - `data class Token(val value: String, val uid: String, val unid: String, val expiresAtMs: Long?)`
  - `interface TokenStore { fun save(t: Token); fun load(): Token?; fun clear() }` + `class InMemoryTokenStore`（CLI 用；App 的 EncryptedSharedPreferences 实现在 Task 12）
  - `class TimeSync(val api: ApiClient) { fun serverOffsetMs(): Long }`（GMT 对齐，spec §5.1 时序要求）
  - `class DeviceIdentity(private val persistedSalt: ByteArray, private val hardwareHints: Map<String, String>) { fun stableId(): String }`
  - 同一 `persistedSalt` 跨实例生成相同 `stableId`；`hardwareHints` 提供 OAID 等硬件级标识（App 注入；spec §5.5-3）。

- [ ] **Step 1: 写失败测试**

TimeSync：fixture 中 server 时间戳与本地时间差 → `offset == 期望值`。
DeviceIdentity：`same salt + same hints → equal id`；`different salt → different id`；`hints 缺失（空 Map）→ 不抛异常且 id 稳定`。
Run: `./gradlew :core:test` → FAIL。

- [ ] **Step 2: 实现 ApiClient / TimeSync / TokenStore / DeviceIdentity**
Run: `./gradlew :core:test` → PASS。

- [ ] **Step 3: Commit**

```bash
git add core
git commit -m "feat(core): session, time sync, stable device identity"
```

---

### Task 6: Policy 模块（规则 / 围栏 / 点位 / 人脸窗口）

**Files:**
- Create: `core/src/main/kotlin/dev/campusrun/core/policy/{RunPolicy.kt, PolicyClient.kt}`
- Test: `core/src/test/kotlin/dev/campusrun/core/policy/PolicyClientTest.kt`（消费 Task 2 fixture）

**Interfaces:**
- Consumes: `ApiClient`、`Token`（Task 5）、Task 2 保存的 policy 响应 fixture
- Produces:
  - `data class LatLng(val lat: Double, val lng: Double)`
  - `data class RunPolicy(val requiredPoints: List<LatLng>, val optionalPoints: List<LatLng>, val fence: List<LatLng>, val paceMinSecPerKm: Int, val paceMaxSecPerKm: Int, val validFromHour: Int, val validToHour: Int, val maxDistancePerRunM: Int, val dailyRunLimit: Int, val faceWindow: ClosedRange<Long>? /* epoch ms，spec R6；无则 null */)`
  - `class PolicyClient(val api: ApiClient) { fun fetch(token: Token, mode: Int): RunPolicy }`

- [ ] **Step 1: 写失败测试（用真实 fixture）**

解析 `policy/<school>.json` → 断言具体值（`paceMinSecPerKm == 210`、`fence.size >= 4`、必经点 ≥1——**数值以 fixture 实际内容钉死**，从 Task 2 输出抄录）。
Run: `./gradlew :core:test` → FAIL。

- [ ] **Step 2: 实现模型与解析**
Run: `./gradlew :core:test` → PASS。

- [ ] **Step 3: Commit**

```bash
git add core
git commit -m "feat(core): run policy model and parser (fence/points/pace/face window)"
```

---

### Task 7: 轨迹合成器

**Files:**
- Create: `core/src/main/kotlin/dev/campusrun/core/track/{TrackSynthesizer.kt, Geo.kt}`
- Test: `core/src/test/kotlin/dev/campusrun/core/track/TrackSynthesizerTest.kt`

**Interfaces:**
- Consumes: `RunPolicy`（Task 6）
- Produces:
  - `data class TrackPoint(val lat: Double, val lng: Double, val elapsedSec: Int, val speedMps: Double, val stepRateHz: Double)`
  - `object TrackSynthesizer { fun synthesize(policy: RunPolicy, targetDistanceM: Int, durationSec: Int, seed: Long): List<TrackPoint> }`
  - 同 seed 结果确定性一致；路径全长 = 围栏内合法路径（spec C2）。

- [ ] **Step 1: 写失败测试（property 风格）**

用 Task 6 fixture 的围栏与点位，`synthesize(policy, 2000, 900, seed = 42L)`：
```kotlin
@Test fun `all points inside fence`()        // Geo.insidePolygon 全 true
@Test fun `visits all required points`()     // 到每个必经点最短距离 <= 20.0
@Test fun `path length within 3 percent`()   // abs(len - 2000) / 2000 <= 0.03
@Test fun `mean pace within policy window`() // 210 <= meanPaceSecPerKm <= paceMax
@Test fun `elapsed monotonic and ends at durationSec`
@Test fun `deterministic per seed and distinct across seeds`()  // 42==42, 42!=43
```
Run: `./gradlew :core:test` → FAIL。

- [ ] **Step 2: 实现合成器**

路线算法自选（围栏内网格寻路 / 采样拼接均可），配速曲线需含自然波动（spec §5.5-1）；`stepRateHz` 在 70–240 区间（spec C2 步频）。

Run: `./gradlew :core:test` → PASS。

- [ ] **Step 3: Commit**

```bash
git add core
git commit -m "feat(core): fence-contained track synthesis with pace/step constraints"
```

---

### Task 8: OBS 上传、结算与回查

**Files:**
- Create: `core/src/main/kotlin/dev/campusrun/core/upload/{RunUploader.kt, Settlement.kt, HistoryClient.kt}`
- Test: `core/src/test/kotlin/dev/campusrun/core/upload/{RunUploaderTest.kt, SettlementTest.kt}`

**Interfaces:**
- Consumes: `TrackPoint`/`TrackSynthesizer`（7）、`RunPolicy`（6）、`Signer`/`NetSecKit`（3/4）、`ApiClient`/`TimeSync`（5）
- Produces:
  - `class RunUploader(val api: ApiClient, val netsec: NetSecKit, val signer: Signer, val timeSync: TimeSync)` with `fun uploadTrack(token: Token, track: List<TrackPoint>): UploadHandle`（OBS 异步推送，按上游流程）
  - `object Settlement { fun buildRequest(policy: RunPolicy, track: List<TrackPoint>, handle: UploadHandle, params: ProtocolParams): ByteArray }`（31 字段 + `Signer.settlementDigest`，字段组装按上游）
  - `class HistoryClient(val api: ApiClient) { fun latest(token: Token): RunRecord? }`
  - `data class RunRecord(val id: String, val distanceM: Int, val valid: Boolean)`

- [ ] **Step 1: 写失败测试（离线）**

- `buildRequest` 对固定 track 输出：31 字段齐全、digest 与 `gen-vectors.py` 生成的 golden 值一致、`startTime/stopTime` 差 == `durationSec`。
- `uploadTrack` 用 MockWebServer 断言：请求 URL/headers 按上游流程、payload 经 NetSecKit 可解。
Run: `./gradlew :core:test` → FAIL。

- [ ] **Step 2: 实现三类**（OBS 流程细节以 7.4.x 探测报告修正过的上游代码为准）
Run: `./gradlew :core:test` → PASS。

- [ ] **Step 3: Commit**

```bash
git add core
git commit -m "feat(core): OBS upload, settlement build, history verify"
```

---

### Task 9: CLI 一键跑编排 + 规则闸门与负例

**Files:**
- Create: `core/src/main/kotlin/dev/campusrun/core/policy/RunPolicyValidator.kt`
- Create: `cli/src/main/kotlin/dev/campusrun/cli/Main.kt`（子命令 `probe` / `run --distance --time [--seed]` / `verify`）
- Test: `cli/src/test/kotlin/dev/campusrun/cli/{ValidatorTest.kt, PacingTest.kt}`、`cli/src/test/kotlin/dev/campusrun/cli/LiveRunTest.kt`（tag `live`）
- Modify: `cli/build.gradle.kts`（加 `liveTest` 任务，`useJUnitPlatform { includeTags("live") }`）

**Interfaces:**
- Consumes: Task 3–8 全部
- Produces:
  - `object RunPolicyValidator { fun validate(track: List<TrackPoint>, policy: RunPolicy, nowEpochMs: Long): List<String> }`（返回违规列表，空 = 放行；闸门覆盖：出围栏、缺必经点、配速越界、时段外、**人脸窗口内**（spec R6）、每日次数上限）
  - CLI `run` = sync → policy → synth → **validate（有违规即拒绝提交）** → upload → settle → verify
  - Gradle 任务 `:cli:liveTest`

- [ ] **Step 1: 写负例与闸门测试**

```kotlin
@Test fun `track outside fence rejected`()      // 人为偏移点 → 含"出围栏"
@Test fun `missing required point rejected`()    // 删除必经点经过段 → 含"缺必经点"
@Test fun `too fast pace rejected`()             // duration 减半 → 含"配速"
@Test fun `face window blocked`()                // now 落在 policy.faceWindow → 含"人脸"
@Test fun `settlement pacing respects durationSec`()  // Review Focus #5：离线断言提交按 durationSec 分桶推进，非瞬时
```
Run: `./gradlew :core:test :cli:test` → FAIL。

- [ ] **Step 2: 实现 Validator 与 CLI 编排**
Run: `./gradlew :core:test :cli:test` → PASS。

- [ ] **Step 3: live 测试（需账号；Review Focus #1 的硬门槛）**

`LiveRunTest`（tag `live`，`@EnabledIfEnvironmentVariable(named="CAMPUS_PHONE", ...)`）：真实跑 1 次（≤2km 短程、时段内），断言 `HistoryClient.latest` 返回 `valid == true`。
Run: `$env:CAMPUS_PHONE=...; $env:CAMPUS_PASSWORD=...; ./gradlew :cli:liveTest`
Expected: PASS——**7.4.x 端到端成立；若失败，回 Task 2 报告补差异再修**。

- [ ] **Step 4: Commit**

```bash
git add core cli
git commit -m "feat: one-shot CLI run with policy gate and negative tests"
```

---

### Task 10: 更新通道与配置热更

**Files:**
- Create: `core/src/main/kotlin/dev/campusrun/core/config/{RemoteVersionInfo.kt, UpdateChecker.kt, ParamsStore.kt}`
- Create: `config/version.json`（仓库内样本，发布时同步到 CDN）
- Test: `core/src/test/kotlin/dev/campusrun/core/config/{UpdateCheckerTest.kt, ParamsStoreTest.kt}`（MockWebServer）

**Interfaces:**
- Consumes: `ProtocolParams`（Task 3）
- Produces:
  - `data class RemoteVersionInfo(val minApkVersionCode: Int, val configVersion: Int, val protocolParamsVersion: Int, val apkMirrors: List<String>)`（spec §5.6 已钉字段）
  - `class UpdateChecker { fun check(baseUrls: List<String>): RemoteVersionInfo? }`（多镜像顺序回退）
  - `class ParamsStore(val bundled: ProtocolParams) { fun current(): ProtocolParams; fun applyRemote(json: String): Boolean }`（远程 params version > 内置 → 热替换；逻辑级变更由 `RemoteVersionInfo.minApkVersionCode` 触发"提示新 APK"，**无脚本引擎**）

- [ ] **Step 1: 写失败测试**

`first mirror fails second succeeds`；`remote params with higher version applied without restart`；`minApkVersionCode > current → flags updateRequired()`。
Run: `./gradlew :core:test` → FAIL。

- [ ] **Step 2: 实现**
Run: `./gradlew :core:test` → PASS。

- [ ] **Step 3: Commit**

```bash
git add core config
git commit -m "feat: update channel and hot config params"
```

---

### Task 11: 诊断包导出（脱敏）

**Files:**
- Create: `core/src/main/kotlin/dev/campusrun/core/diag/{RedactedLog.kt, DiagnosticExport.kt}`
- Test: `core/src/test/kotlin/dev/campusrun/core/diag/DiagnosticExportTest.kt`

**Interfaces:**
- Produces:
  - `class RedactedLog { fun w(tag: String, msg: String); fun lines(): List<String> }`——写入即脱敏
  - `object DiagnosticExport { fun export(log: RedactedLog, configVersion: Int, lastResults: List<String>, outDir: Path): Path }`（zip，spec §6.3）

- [ ] **Step 1: 写失败测试**

日志喂入 `password=hunter2`、`token=abc123`、`phone=<11位手机号>` → 导出 zip 内所有条目：`hunter2`/`abc123`/`<11位手机号>` 均**不出现**，且出现对应 `[REDACTED]`。
Run: `./gradlew :core:test` → FAIL。

- [ ] **Step 2: 实现**（脱敏规则：token、password、手机号、uid 四类；后续可扩）
Run: `./gradlew :core:test` → PASS。

- [ ] **Step 3: Commit**

```bash
git add core
git commit -m "feat: redacted diagnostic export"
```

---

### Task 12: Android 登录 + GeeTest spike（Review Focus #2）

**Files:**
- Create: `docs/spikes/geetest-android.md`（spike 结论）
- Create: `app/src/main/kotlin/dev/campusrun/app/login/{LoginScreen.kt, LoginViewModel.kt, Authenticator.kt}`
- Create: `app/src/main/kotlin/dev/campusrun/app/data/AndroidTokenStore.kt`（EncryptedSharedPreferences 实现 core 的 `TokenStore`）
- Test: `app/src/test/kotlin/dev/campusrun/app/login/LoginViewModelTest.kt`

**Interfaces:**
- Consumes: `TokenStore`、`ApiClient`（Task 5）
- Produces:
  - `interface Authenticator { fun login(phone: String, password: String, captcha: CaptchaHandler): Token }`
  - `class LoginViewModel(auth: Authenticator)`：状态机 `Idle → NeedCaptcha → Submitting → Success(Token) | Error(msg)`
  - AndroidTokenStore 满足 spec C7（本地加密、不外发）

- [ ] **Step 1: GeeTest spike（spec R2）**

评估两条路：(a) 官方 GeeTest Android SDK（用户手滑一次）；(b) WebView 执行 v4 流程取校验参数。写 `docs/spikes/geetest-android.md`：方案、证据、选择。
硬性验证：**真机用测试账号完成登录拿到 Token**（可先用临时桥接：WebView 成功后把 token 交给 CLI 验证一次 `--profile`）。
Expected: 文档含明确选择与登录成功截图/输出。

- [ ] **Step 2: 写 ViewModel 失败测试**

Fake `Authenticator`：`Success → state is Success(token)`；`captcha required → NeedCaptcha`；`throw → Error 含可读信息且**不含密码**`。
Run: `./gradlew :app:testDebugUnitTest` → FAIL。

- [ ] **Step 3: 实现登录 UI 与 AndroidTokenStore**
Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug` → PASS/绿。
手工验证：真机安装 → 登录 → 杀进程重开 → 已登录状态保持（token 持久化）。

- [ ] **Step 4: Commit**

```bash
git add app docs/spikes
git commit -m "feat(app): login flow with geetest integration and secure token store"
```

---

### Task 13: Android 一键跑与进度/历史 UI

**Files:**
- Create: `app/src/main/kotlin/dev/campusrun/app/run/{RunScreen.kt, RunViewModel.kt}`
- Create: `app/src/main/kotlin/dev/campusrun/app/home/{HomeScreen.kt, HomeViewModel.kt}`（进度/历史，消费 history/score）
- Create: `app/src/main/kotlin/dev/campusrun/app/diag/DiagnosticsScreen.kt`（调 Task 11 导出）
- Modify: `app/.../MainActivity.kt`（启动时调 `UpdateChecker`，需更新 → 提示，spec G4/R5）
- Test: `app/src/test/kotlin/dev/campusrun/app/run/RunViewModelTest.kt`、`home/HomeViewModelTest.kt`

**Interfaces:**
- Consumes: `RunUploader`/`RunPolicyValidator`/`HistoryClient`（6–9）、`UpdateChecker`/`ParamsStore`（10）、`DiagnosticExport`（11）
- Produces: 完整 App 流程（登录 → 首页进度 → 一键跑 → 历史回查 → 导出诊断）；启动更新提示。

- [ ] **Step 1: 写 ViewModel 失败测试（Fake core）**

`run success → Success 状态且显示里程`；`validator 违规 → Error 显示首条违规、未调用 upload`（用 fake 断言 upload 未被调用）；`update required → 提示态`；`历史为空 → 空态文案`。
Run: `./gradlew :app:testDebugUnitTest` → FAIL。

- [ ] **Step 2: 实现三个 Screen + 启动更新检查**（Compose，Material 3）
Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug` → PASS/绿。

- [ ] **Step 3: 手工冒烟（真机）**

安装 → 登录 → 首页显示本校规则与进度 → 一键跑提交 → 历史出现记录 → 导出诊断包（本地文件存在）。
Expected: 全部通过，记录在官方 App 同步可见。

- [ ] **Step 4: Commit**

```bash
git add app
git commit -m "feat(app): one-shot run, progress/history, diagnostics, update prompt"
```

---

### Task 14: Phase 1a 验收与灰度就绪

**Files:**
- Create: `docs/acceptance/phase1a.md`（清单 + 结果记录）
- Create: `docs/acceptance/grayscale-log-template.md`（7 天灰度日志模板，spec §6.3 通过标准原文）

**Interfaces:**
- Consumes: 全部任务产出
- Produces: 验收记录——通过才允许开始灰度（spec §6.3 硬门）。

- [ ] **Step 1: E2E 验收清单执行并记录**

清单项：① 全新设备安装→登录→一键跑→**官方 App 回查 valid**；② 官方 App 与本工具同机并存，记录设备计数表现（spec §5.5-3 验证项）；③ 模拟 `version.json` 高版本 → App 启动提示更新（多镜像之一故意失效验证回退）；④ 诊断包导出且无凭据；⑤ `./gradlew test :cli:liveTest` 全绿。
Expected: `docs/acceptance/phase1a.md` 五项全 PASS。

- [ ] **Step 2: 灰度就绪**

写入灰度日志模板（通过标准原文：连续 7 天每日提交成功、次日回查有效、零风控信号）。
Expected: 模板存在，含日期/提交结果/回查结果/风控观察列。

- [ ] **Step 3: Commit**

```bash
git add docs/acceptance
git commit -m "docs: phase1a acceptance record, grayscale ready"
```

---

## 自查记录（self-review）

- **Spec 覆盖**：G1→T9/T13/T14；G2→T3–9；G4→T10；G6/反目标（无后端、无凭证外发）→Global Constraints + T11；C2/C3/C5/C7→Global Constraints；R1→T2/T9live；R2→T12；R5→T10/T13；R6→T6/T9；§6.1→T2；§6.3→T11/T14。1b/Phase 2 不在本计划（Scope 已声明）。
- **类型一致性**：`ProtocolParams`(T3)→T4/T10；`Token`/`ApiClient`(T5)→T6/T8/T12；`RunPolicy`(T6)→T7/T8/T9；`TrackPoint`(T7)→T8/T9；`Signer.settlementDigest`(T4)→T8。
- **比例**：无整段实现代码；算法仅 T7 允许自选路线，行为全部由 property 测试钉死。
