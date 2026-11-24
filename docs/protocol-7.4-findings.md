# 7.4.x 线上协议黑盒探测结论

> 目的：判定上游 7.3.92 纯协议套件（`reference/upstream/协议/7.3.92`，MIT）在**当前 7.4.x 服务端**上哪些环节仍然可用，
> 作为 Task 3–9 移植范围的依据。
> 探测日期：2026-11-16。编排脚本：`tools/probe.ps1`；原始输出：`reference/out/*.json`（git-ignored，已脱敏）。

---

## 0. 一句话结论

**链路整体通，差异集中在「响应字段名」，不在「端点/加解密/签名」。**
NetSecKit 报文加解密、`headerSign` 签名头、极验 v4 人机校验、`/api/vNNNNN/...` 端点路径、登录流程在 7.4.x 上**全部通过**；
但 4 个接口的**响应体字段发生了重命名/结构变化**（`user/info`、`homePageBaseUserInfo`、`getPersonalSemesterInfo`、
`runnings/records`），上游 7.3.92 的数据模型直接沿用会解出空值甚至崩溃。这些差异**必须**在 Task 5/6/9 落地前修掉。

---

## 1. 探测方法

| 项 | 说明 |
| --- | --- |
| 上游套件 | `reference/upstream/协议/7.3.92`（git clone，未提交、未修改一行） |
| 运行时 | Windows PowerShell 5.1 / Python 3.9.5（`reference/venv`）/ Node v24 + `reference/node/node_modules/playwright` + Chromium 1255 |
| 编排 | `tools/probe.ps1`：一个阶段 = 一次上游 CLI 调用，结果写 `reference/out/<stage>.json` |
| HTTP 观测 | 以 `sitecustomize.py`（`PYTHONPATH` 注入）包裹 `urllib.request.urlopen`，记录 method / url / HTTP status / 响应前 600 字节。**不记录请求头与请求体**（其中含凭据） |
| 结构观测 | `-Stage inspect` 打印解密后响应的**键名/类型/长度**（不打印取值），用于区分「7.4.x 改了字段」与「服务端本来就没数据」 |
| 脱敏 | 手机号、口令、会话凭据在落盘前替换为 `[REDACTED]`；NetSec 密文块 `"r":"…"` 替换为 `"r":"[encrypted]"` |
| 代偿探测 | `-Stage endpoints`：对受保护端点做两轮无会话调用（完全不带身份头 / 带占位身份头），用于区分「端点消失」与「仅缺会话」 |

### 1.1 环境约束（影响复现方式，必须知道）

上游统一入口 `协议/7.3.92/cli.py` **无法在 Python 3.9 下导入**：

```
File "…/running/client.py", line 184
    log(f"… {len(gps_pts)} … {save_record["speed"]}")
SyntaxError: f-string: unmatched '['
```

该文件使用了 PEP 701 的「f-string 内嵌同类引号」语法，要求 **Python ≥ 3.12**；本机仅有 3.9.5（另有 3.11，同样不满足）。
由于 `cli.py → sport_client.py → running.client` 的导入链必然经过该文件，统一入口不可用。

因此逐阶段探测改用**上游自带的分模块 CLI**：`login/cli.py`、`profile/cli.py`、`policy/cli.py`、`history/cli.py`。
它们复用同一套 `core` / `login` / `policy` / `profile` / `history` 客户端代码，**端点、请求体、加解密路径完全一致**，
唯一损失是 `running`（结算、OBS 上传）相关能力——本任务的 5 个阶段都不涉及它。

---

## 2. 差异表

| 阶段 | 上游端点 | 7.4.x 实测结果 | 差异结论 |
| --- | --- | --- | --- |
| `sync-time` | `GET run.gxapp.iydsj.com/api/v3/getserver/gmttime` | HTTP 200；服务端时间解析成功，偏斜 −2.84 ~ −2.91s（多次一致） | **通过** |
| 登录① `checkGeeUse` | `POST /api/v65/security/checkGeeUse` | HTTP 200；要求人机校验（未走白名单分支） | **通过** |
| 登录② `geevalidate` | `POST /api/v70270/security/geevalidate` | HTTP 200；业务码 `10000`；极验 v4 滑块**两次独立求解均第 1 次尝试即通过** | **通过** |
| 登录③ `login` | `POST /api/v70100/login` | HTTP 200；业务码 `10000`，返回完整档案与实际会话凭据（`uid=10000001`、`unid=90001`、示例大学 示例校区） | **通过** |
| 登录（同账号二次登录） | 同上 | HTTP 200；业务码 `10121`「此账号已在其他设备登录…」 | **通过**（单会话保护语义未变） |
| `profile`（详细档案） | `GET /api/v70390/user/info` | HTTP 200；解密出 **52 个键**。`phone`/`mobile` 不存在（改为 `telephone`），`major` 不存在 | **参数变**（字段重命名/删减） |
| `profile`（首页基础信息） | `GET /api/v76/user/homePageBaseUserInfo` | HTTP 200；解密出 **9 个键**：无 `studentNo`/`studentId`，`name` 返回的是**昵称**而非真名 | **参数变** |
| `rules --mode 1` | `POST /api/v70103/runModePolicy` | HTTP 200；`runRuleModel` 字段齐全：`dayGoal 2000` / `minDistance 2000` / `speedTop 3.0` / `speedBottom 10.0` / `stepTop 300` / `stepBottom 40` / `validTime 10:00–22:00`（16:04 时段内采集） | **通过** |
| `rules`（时段外复测） | 同上 `runModePolicy` | 08:54（**早于 10:00 有效时段**）HTTP 200 但响应仅 475 B，解密后**不含 `runRuleModel`** → 上游 CLI 报「失败 原因=规则查询为空」 | **通过**（时段外不下发策略，见 §3.5） |
| `rules`（围栏） | `POST /api/v1/getGeoFenceForRun` | HTTP 200；`data.geoFences = null`（`updateTime` 停在 2019-07，即该校**未配置围栏**） | **通过**（无数据，非接口变化） |
| `rules`（学期进度） | `POST /api/v41/running/getPersonalSemesterInfo` | HTTP 200；解密出 7 个键 `{sid, sname, startDate, endDate, semesterGoal, semesterValid, goalMethod}`，**与 7.3.92 字段名完全对不上** | **参数变**（字段重命名） |
| `history`（记录列表） | `POST /api/v70230/runnings/records` | HTTP 200；`data` 直接是**数组**（5 条 × 19 键），上游按 `{recordList:[…]}` 解析 → `AttributeError: 'list' object has no attribute 'get'` | **结构变**（响应信封） |
| `history`（打卡日历） | `POST discovery…/api/v70100/run/calendar/list` | HTTP 200，但业务码 **`-10000`**、`message="ERROR"`、`data=null`（参数 `{year,month}`） | **参数变**（端点存活、入参不被接受；见 §3.6） |
| 代偿探测（无会话） | 上述 6 个受保护端点 | 不带身份头：HTTP 401「头信息不全」（`ClientParameterException`）；带占位身份头：HTTP 401「该用户不存在」 | **端点变=否**；**签名变=否** |

结论用词：**通过** = 协议可用；**参数变 / 结构变** = 响应字段或信封相对 7.3.92 发生破坏性变化；**待验证** = 本次未取到可判定的样本。
**端点变 = 否** 贯穿全部 13 个探测点：不存在的路径会返回 404，而本次全部返回结构化业务响应（含业务级错误码 `-10000`）。

---

## 3. 逐条证据

1. **时间同步**：`gmttime` 无需鉴权即返回 `{"r":…,"s":…,"v":101}` 密文信封，上游 `ServerTime` 解析成功并算出偏斜；
   NetSec 响应解密（AES-128-CBC + 会话密钥派生）在 7.4.x 上仍然成立。
2. **极验与二次绑定**：极验求解器（`login/geetest_solver.js` + 本地 `gt4.js` 桥接页 + 无头 Chromium）在两次独立运行中
   **都是第 1 次尝试即解出**完整的极验四元组（批次号、通过票据、生成时间、校验输出），`geevalidate` 返回 `10000`。
   说明 CAPTCHA_ID 与二次校验端点均未变，Android 侧 WebView 桥接方案可行。
3. **登录**：三层流程全部走通，`login` 用 HTTP Basic 承载账号口令、报文用 NetSec 加密，返回业务码 `10000` 与真实档案；
   随后 `profile` / `rules` / `history` 阶段用该会话直接调通，证明会话凭据确实有效。
   同一账号在 1 分钟内再次登录 → `10121`（账号单会话保护），**重试无效**，语义与 7.3.92 一致。
4. **受保护端点**（代偿探测）：完全不带身份头 → `头信息不全`（网关头校验拦下）；带占位身份头 → `该用户不存在`
   （头校验通过、请求体已解密/验签、进入业务层）。即 **`headerSign` 加密头签名在 7.4.x 网关上仍被接受**，
   路径也仍是旧版本号路径。

### 3.1 字段级差异（本次最需要 Task 5/6/9 注意的部分）

**A. `GET /api/v70390/user/info`** —— 上游 `UserProfileDetails.from_api_data` 取 `phone`/`mobile`，服务端现在只给 `telephone`：

| 上游期望 | 7.4.x 实际 | 影响 |
| --- | --- | --- |
| `phone` / `mobile` | **`telephone`**（11 位） | 档案里的手机号恒为空 |
| `major` | **不存在**（51 个业务键里没有任何专业字段） | 专业恒为空 |
| `studentId` / `studentNo` | 不存在；但 `campusId`（12 位）就是学号 | 无影响（上游已回退到 `campusId`） |
| `uid` `username` `name` `unid` `campusName` `campusId` `depart` `gradeClass` `sex` | 均存在且语义不变 | 无影响 |

其余键为上游未使用的新字段：`nickname alias userTag icon bicon height weight classId departmentId
nameSecret departmentSecret yearSecret role enrollmentYear registed infoComplete completeType hasEnum
specialUser talent canUploadVideo bindingWechat bindingQQ bindingApple bindingHuaWei activateEmail
adVisible creationEnable creditScore showCreditScore headPendantUrl birthday email 等`。

**B. `GET /api/v76/user/homePageBaseUserInfo`** —— 只有 9 个键：
`{gender, name, perSign, campusName, departmentName, enrollmentYear, icon, uid, bicon}`。

| 上游期望 | 7.4.x 实际 | 影响 |
| --- | --- | --- |
| `studentNo` / `studentId` | **不存在** | 首页「学号」恒为空（需改用 `user/info` 的 `campusId`） |
| `name` = 真名 | 实际是**昵称**（真名在 `user/info` 的 `name`） | 首页展示逻辑需区分昵称/真名 |

**C. `POST /api/v41/running/getPersonalSemesterInfo`**：

| 上游期望 | 7.4.x 实际 | 影响 |
| --- | --- | --- |
| `semesterName` | **`sname`** | 学期名恒为空 |
| `targetDistance` / `goalDistance` | **`semesterGoal`** | 目标恒为 0 |
| `totalDistance`（已完成） | **不存在**（7 个键里没有累计里程） | 已完成里程恒为 0，**需另找数据源** |
| `validCount` | 疑为 **`semesterValid`**（键名语义待确认） | 有效次数恒为 0 |
| — | 新增 `sid` `startDate` `endDate` `goalMethod` | 可用于学期区间/目标方式判定 |

**D. `POST /api/v70230/runnings/records`** —— `data` 由对象变成**数组**：

- 7.3.92：`{"data": {"recordList": [ … ], …}}`
- 7.4.x：`{"data": [ {19 键的记录}, … ]}`（本次 `pageSize=5` 返回 5 条，分页参数仍生效）
- 单条记录的 19 个键（实测）：
  `rrid uuid sportType totalTime totalDis startTime stopTime complete recordUrl prizeName status
  avgStepFreq appealStatus stepFreqStatus validDis validTime themeImg type prize`
  —— 与 7.3.92 `RunRecordDetail` 期望的 `rrid/sportType/totalDis/validDis/totalTime/validTime/status/unCompleteReason/startTime/stopTime/avgSpeed/avgStepFreq`
  相比：**缺 `unCompleteReason` 与 `avgSpeed`**，新增 `uuid/complete/recordUrl/prizeName/appealStatus/stepFreqStatus/themeImg/type/prize`。
- 上游 `history/cli.py:41` 直接 `records_res["data"].get("recordList")` → `AttributeError` 退出码 1。
- **Task 9/13 必须按数组解析**，并保留对旧信封的兼容分支；`unCompleteReason`/`avgSpeed` 缺失时需从 `status`/`totalTime`/`totalDis` 自行推导。

---

### 3.5 `runModePolicy` 在有效时段外返回空文档

同一会话、同一端点、同一入参，两次采集的结果不同：

| 采集时刻 | 响应体 | 解密结果 | 上游 CLI |
| --- | --- | --- | --- |
| 2026-11-16 16:04（**时段内**，`validTime` 10:00–22:00） | 1603 B | 完整 `runRuleModel` + `geoFence` | `跑步规则: 模式=计分跑 …` |
| 2026-11-17 08:54（**时段外**，早于 10:00） | 475 B | 仅 `{policy, timestamp, message: null, …}`，**无 `runRuleModel`** | `跑步规则: 失败 原因=规则查询为空` |

即 **端点存活、签名与解密全部正常**，时段外只是不下发规则体。对 Task 6 的要求：
解析器必须把「`runRuleModel` 缺失/为空」当作**正常空态**（返回空策略）而不是异常；
规则闸门在空态下应**拒绝开始跑步**并给出「不在有效时段」提示。

> **两次采集构成对照**：16:04（时段内）有完整 `runRuleModel`，08:54（时段外）为空。
> 因账号为单会话，无法在同一轮内连续对比两个时刻——这是**跨轮**的时段内/时段外对照，不是同轮 A/B。
> 采集轮次与证据见 §5.1。
> `tools/probe.ps1` 已加固：`-Stage fixture` 遇到空规则响应时**不会**覆盖已入库的真实样本（见 §5.1）。

### 3.6 打卡日历 `run/calendar/list` 返回业务错误 `-10000`

```
SHAPE history.calendar_list code=-10000 message="ERROR" data=NoneType
```

- HTTP 200 + 标准 NetSec 信封 → **端点存在、解密与签名均正常**；
- 但业务码是 **`-10000`**、`message="ERROR"`（非 10000/「成功」）、`data=null`；
- 入参为上游 7.3.92 的 `{year, month}`；服务端既不报参数缺失（`ClientParameterException`），也不返回数据，
  说明**要么入参形态变了**（如改 `sid`/`startDate`/`endDate`），**要么该账号/学校未开启日历功能**；
- 结论：**参数变（端点未变）**。Task 9 实现时先把 `-10000` 兜底为「无日历数据」，再按 7.4.x 客户端核对入参。

### 3.7 多校区列表与切换（DEX 静态还原，未线上探测）

首页「当前校园」要做校区下拉，需要**账号可用校区列表**。7.4.0 FridaDump DEX
（`reference/upstream/原始资料/运动世界7.4.0-FridaDump.zip`）里 `net/CommonServer`
的 Retrofit 注解直接给出三条端点（本节全部来自 DEX 注解与实体字段，**未做线上读**：
今天账号还有控制器排定的灰度提交，单会话账号再登录会踢掉手机会话并触发 `10121` 冷却）：

| 用途 | 方法 | 端点 | 返回（`BasicBean<T>` 信封） |
| --- | --- | --- | --- |
| **账号校区列表** | `GET` `getMyCampusList` `@Query("unid")` | `/api/v61101/campus/zonelist?unid=<账号 unid>` | `data: List<MyCampusBean>`，`MyCampusBean{unid:Int, name:String, allowChange:Boolean, isSelected:Boolean}` |
| 切换开关 | `GET` `getIsSwitchSchool` | `/api/v70103/university/getUserInformation` | `data: ChangeCampusBean{openChangeCampus, modelStatus, url…}` |
| **官方切换动作** | `POST` `getUpdateCampus` `@Body RequestBody` | `/api/v61101/user/updatezone` | `data: String` |

补充证据（同为 DEX）：

- `mvi/me/ChangeMyCampusActivity` 的成功回调 `ChangeMyCampusActivity$OooO0OO.onSuccess(String)`
  在切换后**本地改写 `UserInfo.unid`/`campusName`** 并回推 Home 的 LiveData →
  官方语义是「服务端换区 + 本地 unid 跟着换」，之后所有 `selectUnid` 类入参都用新 unid；
- 校区列表为空时该页提示 `暂无任何校区`；
- `CommonApiManager.getUpdateCampus(RequestBody)` 的**请求体字段名无法还原**：
  构造代码位于 base dex 段（各版本 `dump_exact_0/base` 的头表都被截断，`code_off=0`），
  因此 `updatezone` 的入参形态本轮**未知**，也未在线试写。

**本轮实现取舍**：读端点 `campus/zonelist` 按上表移植为 `CampusClient`（只读，安全）；
切换只改**读规则用的 `selectUnid`/`selectedUnid`**（Task 6 既有字段）并持久化本地选择，
**不调用 `updatezone`**（入参未知 + 属写操作，灰度提交日不碰账号状态），
About 区明示「不修改平台账号登记的校区」。校区列表的真实响应形状（信封/字段名）
尚未线上核对，属**未验证项**（见 §6）。

---

## 4. 对后续任务的影响（Task 3–13）

| 任务 | 调整建议 |
| --- | --- |
| **Task 3 Crypto/NetSecKit** | 按原计划移植，无需改动。报文信封（`r/s/v`）与响应解密已在 7.4.x 实测通过。 |
| **Task 4 签名** | `headerSign` 在 7.4.x 仍被接受（§3.4），按原计划移植。**但**结算报文的 31 字段 MD5 签名未在线验证（本次未跑 `--run`）→ 移植后标注「未线上验证」，交 Task 8 回归。 |
| **Task 5 会话与时间同步** | 时间同步与登录链路已实测通过，按原计划移植。需新增两点：① 账号是**单会话**，`10121` 是正常业务码（同账号再次登录必现），`SessionManager` 必须把它作为一等错误（`AccountLockedElsewhere`）并**禁止自动重试**；② `user/info` 映射改用 `telephone` 取手机号，`major` 直接留空。 |
| **Task 6 Policy** | `runModePolicy` 时段内完全通过 → 按上游移植，真实 fixture 已入库。**但必须处理空态**：时段外（§3.5）响应不含 `runRuleModel`，解析器应返回空策略而非抛异常。`getGeoFenceForRun` 通过但**本校围栏为空**（`geoFences=null`），围栏解析/点位逻辑无法用真实数据验证，需另行构造样例。**学期进度必须改模型**：`sname/semesterGoal/semesterValid`，且累计里程无字段 → 建议改调 `/api/v70100/run/data/index` 或 `recordssummary/semester` 取里程，实现前先探测。 |
| **Task 7 轨迹合成** | 纯本地算法，不受线上影响；`running` 模块在 Python 3.9 下无法导入，无法用上游交叉验证，移植以源码为准。 |
| **Task 8 OBS 上传/结算/回查** | **完全未探测**（需要真实 `--run`）。按上游移植，但视为「未验证」，验收前必须真实会话回归。 |
| **Task 9 CLI 编排 + 规则闸门** | 闸门可依赖 `runRuleModel`（已验证），**空态一律拒绝开跑**（§3.5）。历史记录解析必须支持 `data` 为**数组**的新信封（§3.1 D）。打卡日历实测 `-10000`（§3.6）：先兜底为「无数据」，再核对入参。 |
| **Task 12 Android 登录 + GeeTest spike** | 正向信号强化：极验 v4 两次独立求解都是 1 次通过，`captchaId` 沿用 `3b02ad…`。 |
| **Task 13 UI** | 首页基础信息取不到学号、`name` 是昵称；真名/学号请从 `user/info`（`name` / `campusId`）取。 |

---

## 5. 复现

```powershell
# 0) 环境（Step 1，一次性）
git clone https://github.com/Inverx/com.zjwh.android_wh_physicalfitness.git reference/upstream
python -m venv reference/venv
reference/venv/Scripts/pip install playwright
reference/venv/Scripts/playwright install chromium
# 极验求解器是 Node 侧依赖，另需：
cd reference/node; npm install playwright; npx playwright install chromium; cd ../..

# 1) 全流程（凭据只经环境变量传入，见 tools/probe.ps1 顶部 .EXAMPLE）
powershell -NoProfile -ExecutionPolicy Bypass -File tools\probe.ps1

# 2) 单阶段
powershell -NoProfile -ExecutionPolicy Bypass -File tools\probe.ps1 -Stage sync-time
powershell -NoProfile -ExecutionPolicy Bypass -File tools\probe.ps1 -Stage endpoints
powershell -NoProfile -ExecutionPolicy Bypass -File tools\probe.ps1 -Stage inspect

# 3) 复用已有会话跳过登录（变量名见脚本顶部 .EXAMPLE）
#    注意：账号是单会话，重复登录会拿到 10121
powershell -NoProfile -ExecutionPolicy Bypass -File tools\probe.ps1 -Stage history
```

> `reference/` 整体在 `.gitignore` 中：上游源码、虚拟环境、探测输出都不会进入版本库。

### 5.1 fixture 采集的保护规则

`-Stage fixture` 会把解密后的真实响应写入 `core/src/test/resources/policy/`。因为 `runModePolicy` 在有效时段外
返回空文档（§3.5），采集器做了两条保护，避免「一次时段外探测把好样本覆盖成 `{}`」：

- 写入前校验语义：`run_mode_policy.json` 必须含非空 `runRuleModel`，`geo_fence.json` 必须 `error=10000`；
- 已有可用样本、而本次抓到的样本不合格时，**跳过写入并告警**（`[fixture] … 本次响应为空, 保留已有真实样本`）。

因此本目录当前入库的两份样本来自 **2026-11-16 16:04（时段内）** 的真实采集，而非时段外空响应。

**溯源（review 要求补齐）**：样本由 16:04 那次 `-Stage all` 的 `fixture` 阶段产出。

- `run_mode_policy.json` 内的 `timestamp` 字段（毫秒级 Unix 时间）换算为 **2026-11-16 16:04:58.979 +08**；
- 同期 `rules --mode 1` 输出「跑步规则: 模式=计分跑 …」（时段外只输出「失败 原因=规则查询为空」）；
- `runRuleModel` 本身只在时段内下发（§3.5 的 475 B 空响应不含该键）→ 该样本**只能**来自 16:04 这次时段内采集。

**取证局限（如实记录）**：

1. 该轮的 `reference/out/*.json` 已被 11-17 08:54 的复跑覆盖，现存最强证据是 fixture 自身的服务端时间戳与上述对照；
2. 08:54 复跑时防覆盖护栏尚未加入，`run_mode_policy.json` 一度被写成 `{}`，随后按 16:04 读回的完整内容**原样恢复**：
   语义一致、但已无法与被覆盖的原件做哈希比对；
3. 恢复由编辑器写入，故该文件带末尾换行；`geo_fence.json` 仍是 probe `WriteAllText` 产物（无末尾换行）。
   **两者来自同一次采集**，格式差异只是写入方式不同，不是两次不同来源。

---

## 6. 未验证项与风险

1. **`appVersion` 头仍是 7.3.92**：本次只证明「服务端仍然接受 7.3.92 协议」，**不证明 7.4.x 客户端仍用同一批路径/参数**。
   字段级差异（§3.1）恰恰说明客户端侧确实改过模型 → 建议 Task 3 用
   `reference/upstream/原始脱壳资料/运动世界7.4.0-FridaDump.zip` 核对 7.4.x 的字段名，确认取舍。
2. **打卡日历只判定到错误码**：`-10000 / "ERROR"` 已确认端点存活（§3.6），但**入参的新形态未取到**；
   Task 9 先按「无数据」兜底，真实日历能力仍需在 7.4.x 客户端或有效入参下复测。
3. **围栏/点位无真实样本**：该校 `geoFences=null`，`/api/v560/get/1/distance/{d}`（五点）本轮未测（依赖围栏锚点）。
4. **结算链路零覆盖**：`save/record`、`get_one_record`、OBS 预签名全部未探测，Task 8 风险最高。
5. **单会话限制**：探测结束后账号仍被本次会话占用，1 小时内再次登录会得到 `10121`；补测前请等锁释放或在真机登录踢掉旧会话。
6. **规则体只在时段内可验证**：`runModePolicy` 时段外为空（§3.5），任何涉及 `runRuleModel` 的验收都必须安排在 10:00–22:00。
7. **校区列表未线上核对**（§3.7）：`/api/v61101/campus/zonelist` 的信封与字段名来自 DEX 实体还原，
   本轮**没有线上读**（灰度提交日不登录、不碰单会话）；真实响应形状需在提交后补一次只读探测。
8. **`selectUnid` 换区语义未验证**：本工具的校区下拉只改读规则用的 `selectUnid`/`selectedUnid`，
   服务端是否据此下发另一校区的规则/点位，需要多校区账号在时段内做两次对照拉取才能确认；
   官方的 `POST /api/v61101/user/updatezone` 因入参未知且属写操作，本轮**未实现也未调用**。
