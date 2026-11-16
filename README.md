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
- Android SDK Platform 35、Build-Tools 35.0.0（`local.properties` 中配置 `sdk.dir`）
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

## 上游致谢（MIT）

本项目的协议分析参考了开源项目 **com.zjwh.android_wh_physicalfitness**
（<https://github.com/Inverx/com.zjwh.android_wh_physicalfitness>），该项目以 MIT License 发布。
本仓库仅借鉴其公开的协议行为与接口结构，未包含其源代码；后续如引入或改写其代码，
将在本段补充完整的上游版权声明与 MIT 许可证全文。

> 占位说明：具体链接与版权信息细节在 Task 3 补齐。
