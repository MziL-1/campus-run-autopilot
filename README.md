# 校园跑自动驾驶（CampusRun Autopilot）

面向高校"校园跑"场景的 Android 工具：登录本人账号后，按配置的路线自动完成打卡。
详细设计见 `docs/superpowers/specs/2026-11-16-campus-run-autopilot-design.md`。

## 模块

| 模块     | 类型              | 职责                                                     |
| -------- | ----------------- | -------------------------------------------------------- |
| `:core`  | Kotlin/JVM        | 平台协议、轨迹生成、纯逻辑；**禁止依赖任何 Android API** |
| `:cli`   | Kotlin/JVM        | 本地调试工具，复用 `:core` 验证协议与轨迹                |
| `:app`   | Android + Compose | APK 外壳与 UI                                            |

## 环境要求

- JDK 17（已在 JDK 17.0.12 与 21.0.9 上验证）
- Android SDK Platform 35（`local.properties` 中配置 `sdk.dir`）；Build-Tools 由 AGP
  按需自动下载/管理，无需预装（如本机已装 35.0.0 亦可直接复用）
- Gradle 8.10.2（通过仓库内 wrapper 提供，无需本机安装）

> 环境注意：本仓库位于非 ASCII 路径（`D:\a校园跑改路径`）。`gradle.properties` 中
> `android.overridePathCheck=true` 与 `-Dfile.encoding=GBK` 都是该路径下的必要设置
> （后者用于让测试 worker 的 classpath argfile 编码与 JDK 启动器一致），请勿删除。

技术栈：Kotlin 2.0.21 · AGP 8.7.2 · Compose BOM 2024.10.01 · kotlinx-serialization 1.7.3 ·
OkHttp 4.12.0 · JUnit 5.10.2。`minSdk 26`，`targetSdk`/`compileSdk` 35，
`applicationId = dev.campusrun.autopilot`。

## 构建与测试

```bash
./gradlew test              # 全部模块单元测试
./gradlew :app:assembleDebug # 产出 app/build/outputs/apk/debug/app-debug.apk
```

## 上游致谢与许可证（MIT）

本项目的协议实现**移植 / 衍生自**开源项目 **com.zjwh.android_wh_physicalfitness**
（<https://github.com/Inverx/com.zjwh.android_wh_physicalfitness>）：`core` 模块中的
平台协议、NetSecKit 加解密、签名、轨迹合成与结算字段等大量逻辑直接移植自上游
`协议/7.3.92` 包（Task 3–8），并按本仓库的 Kotlin/JVM 模块结构重组。上游以 MIT
License 发布，其版权声明与许可证全文如下：

```text
MIT License

Copyright (c) 2026 Inverx

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

本仓库自身以 MIT License 发布，完整文本见根目录 [`LICENSE`](LICENSE)
（上游版权行 + 本项目版权行）。

### 第三方资产声明（NOTICE）

`app/src/main/assets/geetest/*`（`gt4.js` / `gt4-index.html` / `gt4-loading.gif`）为
**GeeTest（极验）v4.2.0 的客户端资源**，来自上游仓库，用于与目标平台的人机校验流程
兼容。该资产**不在上游 MIT 许可范围内**：其再分发权利依 GeeTest 客户端条款而定，
本项目仅以"随包分发以保持登录可用"的目的使用，不主张对其任何权利。如权利人提出
异议，将按 spec §7 的下架配合机制处理。

### 配置热更边界

协议参数（签名盐 / endpoint / RSA 公钥等）变更 = **强制发新 APK**，直到存在
`params-doc` 端点为止；热更通道只覆盖 `version.json` 级别的配置数据，不加载任何动态
代码（详见 `MainActivity.UPDATE_MIRRORS` KDoc 与 spec §5.6 热更边界）。
