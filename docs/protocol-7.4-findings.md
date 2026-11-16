# 7.4.x 线上协议黑盒探测结论

> 目的：判定上游 7.3.92 纯协议套件（`reference/upstream/协议/7.3.92`，MIT）在**当前 7.4.x 服务端**上哪些环节仍然可用，
> 作为 Task 3–9 移植范围的依据。
> 探测日期：2026-11-16。编排脚本：`tools/probe.ps1`；原始输出：`reference/out/*.json`（git-ignored，已脱敏）。

---

## 0. 一句话结论

**传输层与签名层通过。** NetSecKit 报文加解密、`headerSign` 签名头、`/api/vNNNNN/...` 端点路径在当前 7.4.x 网关**仍然被接受**；
唯一阻断点是账号级的**单设备登录保护**——`/api/v70100/login` 在密码校验通过后返回业务码 `10121`（"此账号已在其他设备登录"），
因此 `profile` / `rules` / `history` 三个阶段未能取得真实会话，均为**待验证**，但其端点经代偿探测确认**未变更**。

---

## 1. 探测方法

| 项 | 说明 |
| --- | --- |
| 上游套件 | `reference/upstream/协议/7.3.92`（git clone，未提交、未修改一行） |
| 运行时 | Windows PowerShell 5.1 / Python 3.9.5（`reference/venv`）/ Node v24 + `reference/node/node_modules/playwright` + Chromium 1255 |
| 编排 | `tools/probe.ps1`：一个阶段 = 一次上游 CLI 调用，结果写 `reference/out/<stage>.json` |
| HTTP 观测 | 以 `sitecustomize.py`（`PYTHONPATH` 注入）包裹 `urllib.request.urlopen`，记录 method / url / HTTP status / 响应前 600 字节。**不记录请求头与请求体**（其中含凭据） |
| 脱敏 | 手机号、口令、会话凭据在落盘前替换为 `[REDACTED]`；NetSec 密文块 `"r":"…"` 替换为 `"r":"[encrypted]"` |
| 代偿探测 | `-Stage endpoints`：对受保护端点做两轮无会话调用（完全不带身份头 / 带占位身份头），用于区分"端点消失"与"仅缺会话" |

### 1.1 环境约束（影响复现方式，必须知道）

上游统一入口 `协议/7.3.92/cli.py` **无法在 Python 3.9 下导入**：

```
File "…/running/client.py", line 184
    log(f"… {len(gps_pts)} … {save_record["speed"]}")
SyntaxError: f-string: unmatched '['
```

该文件使用了 PEP 701 的"f-string 内嵌同类引号"语法，要求 **Python ≥ 3.12**；本机仅有 3.9.5（另有 3.11，同样不满足）。
由于 `cli.py → sport_client.py → running.client` 的导入链必然经过该文件，统一入口不可用。

因此逐阶段探测改用**上游自带的分模块 CLI**：`login/cli.py`、`profile/cli.py`、`policy/cli.py`、`history/cli.py`。
它们复用同一套 `core` / `login` / `policy` / `profile` / `history` 客户端代码，**端点、请求体、加解密路径完全一致**，
唯一损失是 `running`（结算、OBS 上传）相关能力——本任务的 5 个阶段都不涉及它。

---

## 2. 差异表

| 阶段 | 上游端点 | 7.4.x 实测结果 | 差异结论 |
| --- | --- | --- | --- |
| `sync-time` | `GET run.gxapp.iydsj.com/api/v3/getserver/gmttime` | HTTP 200；返回服务端毫秒时间戳，本地偏斜约 −2.8s（`reference/out/sync-time.json`） | **通过** |
| 登录①②`checkGeeUse` | `POST /api/v65/security/checkGeeUse` | HTTP 200；答复需要人机校验（未走白名单分支） | **通过** |
| 登录③`geevalidate` | `POST /api/v70270/security/geevalidate` | HTTP 200；业务码 `10000`；极验 v4 滑块（`captchaId 3b02ad…`）第 1 次尝试即解出并通过服务端二次绑定 | **通过** |
| 登录④`login` | `POST /api/v70100/login` | HTTP 200；业务码 `10121`「此账号已在其他设备登录，因账号保护机制，原状态未退出」 | **通过（协议层）**；账号态阻断 |
| `profile` | `GET /api/v70390/user/info`、`GET /api/v76/user/homePageBaseUserInfo` | 未取得会话，未实测 | **待验证**（端点变=否） |
| `rules --mode 1` | `POST /api/v70103/runModePolicy`、`POST /api/v1/getGeoFenceForRun`、`POST /api/v41/running/getPersonalSemesterInfo` | 未取得会话，未实测 | **待验证**（端点变=否） |
| `history` | `POST /api/v70230/runnings/records`、`POST discovery…/api/v70100/run/calendar/list` | 未取得会话，未实测 | **待验证**（端点变=否） |
| 代偿探测（无会话） | 上述 6 个受保护端点 | 不带身份头：HTTP 401「头信息不全」（`ClientParameterException`）；带占位身份头：HTTP 401「该用户不存在」 | **端点变=否**；**签名变=否** |

结论用词说明：**通过** = 协议可用；**待验证** = 未取得会话无法实测；**参数变 / 签名变 / 端点变** = 相对 7.3.92 发生的破坏性变化（本次均为否）。

---

## 3. 逐条证据

1. **时间同步**：`gmttime` 无需鉴权即返回 `{"r":…,"s":…,"v":101}` 密文信封，上游 `ServerTime` 解析成功并算出偏斜；
   NetSec 响应解密（AES-128-CBC + 会话密钥派生）在 7.4.x 上仍然成立。
2. **极验与二次绑定**：极验求解器（`login/geetest_solver.js` + 本地 `gt4.js` 桥接页 + 无头 Chromium）在**第一次尝试**就产出
   完整的极验四元组（批次号、通过票据、生成时间、校验输出），`geevalidate` 返回 `10000`。说明 CAPTCHA_ID 与二次校验端点均未变。
3. **登录**：三层流程全部走通（`checkGeeUse` → `geevalidate` → `login`），`login` 用 HTTP Basic 承载账号口令、
   报文用 NetSec 加密，服务端**完成了身份识别**（否则不会给出"已在其他设备登录"这种账号级答复），
   仅因单设备保护拒绝建立新会话。连续 4 次（约 11 分钟内）结果一致，均为 `10121`，重试无效。
4. **受保护端点**：两轮代偿探测的关键差异在于错误文案——
   - 完全不带身份头 → `头信息不全`：请求在**网关头校验**就被拦下，什么都证明不了；
   - 带占位身份头 → `该用户不存在`：网关头校验**通过**，请求体也已成功解密/验签并进入业务层，只因占位身份不存在而拒绝。
   即 **`headerSign` 的加密头签名在 7.4.x 网关上仍然被接受**，路径也依然是旧版本号路径（不存在的路径会返回 404 而非此结构化的 401）。

---

## 4. 对后续任务的影响（Task 3–9）

| 任务 | 调整建议 |
| --- | --- |
| **Task 3 Crypto/NetSecKit** | 按原计划移植，无需改动。报文信封（`r/s/v`）与响应解密已在 7.4.x 实测通过。**不要**改用 7.4.0 脱壳包里的新算法（本次未发现需要）。 |
| **Task 4 签名** | `headerSign` 在 7.4.x 仍被接受（证据见 §3.4），按原计划移植。**但**结算报文的 31 字段 MD5 签名与 `headerSign` 的配合链路一条都**没在线验证过**（无会话）→ 移植完成后必须标注"未线上验证"，交由 Task 8 在真实会话下回归。 |
| **Task 5 会话与时间同步** | `gmttime` 打通即可照原样移植。`SessionManager` 必须把业务码 `10121` 作为**一等错误**处理：`ErrorCode.AccountLockedElsewhere`，文案提示"在真机退出或等 1 小时后重试"，且**不得自动重试**（实测重试无效，只会继续 10121）。 |
| **Task 6 Policy** | 端点全部存活且路径未变，可照上游 `policy` 模块移植（`runModePolicy` / `getGeoFenceForRun` / `getPersonalSemesterInfo`）。**fixture 目前是占位文件**（`core/src/test/resources/policy/*.json` 内 `_placeholder: true`）：拿到会话后执行 `tools/probe.ps1 -Stage fixture` 即可生成真实响应；在此之前 Task 6 的解析单测只能用手写样例。 |
| **Task 7 轨迹合成** | 纯本地算法，不受线上影响；但 `running` 模块在 Python 3.9 下无法导入，无法用上游做交叉验证，移植时以源码为准。 |
| **Task 8 OBS 上传/结算/回查** | **完全未探测**（OBS 预签名 URL 与 `save/record` 需要会话）。按上游移植，但必须视为"未验证"，验收前需真实会话回归。 |
| **Task 9 CLI 编排 + 规则闸门** | 闸门依赖 `rules` 真实响应，目前只能按上游模型实现；负例（无规则、无围栏）可先用手写 JSON 覆盖。 |
| **Task 12 Android 登录 + GeeTest spike** | 正向信号：极验 v4 在纯 Python + 无头 Chromium 下可稳定解出（1 次通过）。Android 侧建议仍走 WebView 桥接方案，`captchaId` 沿用 `3b02ad…`。 |

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

# 3) 拿到会话后续跑（脚本支持复用已有会话，变量名见脚本顶部 .EXAMPLE）
powershell -NoProfile -ExecutionPolicy Bypass -File tools\probe.ps1 -Stage profile
powershell -NoProfile -ExecutionPolicy Bypass -File tools\probe.ps1 -Stage rules
powershell -NoProfile -ExecutionPolicy Bypass -File tools\probe.ps1 -Stage history
powershell -NoProfile -ExecutionPolicy Bypass -File tools\probe.ps1 -Stage fixture
```

> `reference/` 整体在 `.gitignore` 中：上游源码、虚拟环境、探测输出都不会进入版本库。

---

## 6. 未验证项与风险

1. **只证明了"旧协议仍被服务端接受"**，没有证明"7.4.x 客户端仍使用同一批路径/参数"。
   上游所有请求的 `appVersion` 头都是 `7.3.92`，服务端因此按旧客户端处理（旧版本端点通常长期保留）。
   → 建议 Task 3 拿 `reference/upstream/原始脱壳资料/运动世界7.4.0-FridaDump.zip` 对照路径与参数名，确认 7.4.x 未改版。
2. **`profile` / `rules` / `history` 无真实样本**：fixture 为占位文件；解析模型（`RunRule` / `GeoFenceArea` / `SemesterInfo`）的正确性
   只能在拿到会话后验证。
3. **结算链路零覆盖**：`save/record`、`get_one_record`、OBS 预签名全部未探测，Task 8 的风险最高。
4. **账号态**：探测期间账号始终处于"其他设备已登录"状态；若该状态长期存在，后续任务的线上回归都会被同一业务码卡住，
   需要先解决单设备保护（真机退出或等待 1 小时）。
