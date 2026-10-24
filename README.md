# 蓝航Lite（LanhangLite）

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

南昌航空大学「蓝航」App 的第三方轻量客户端（`com.lanhang.lite`）。

> 本项目与南昌航空大学及官方「蓝航」App 无关，接口协议来自公开抓包与逆向分析，仅供学习交流。

## 功能

- 学号密码登录（密码 RSA 加密上送）
- 课表：周视图 / 网格视图，按周缓存，下拉刷新
- 成绩：学期切换、加权平均/学分统计
- 工作圈：融云圈动态浏览、点赞、评论、图片预览
- 桌面小组件（课表）
- 亮色 / 暗色 / 跟随系统主题

## 构建

环境要求：

- JDK 17
- Android SDK（compileSdk 36，minSdk 26）
- AGP 8.5.2 / Kotlin 1.9.24

```powershell
gradle assembleDebug
# 输出：app\build\outputs\apk\debug\app-debug.apk
```

本仓库未附带 Gradle Wrapper，可自行 `gradle wrapper` 或使用本机已安装的 Gradle 8.x。

### Release 签名（可选）

在 `~/.gradle/gradle.properties`（**不要写进仓库**）配置后，release 构建会自动使用：

```properties
lanhangReleaseStoreFile=/path/to/release.jks
lanhangReleaseStorePassword=***
lanhangReleaseKeyAlias=***
lanhangReleaseKeyPassword=***
```

## 数据来源

| 端点 | 用途 |
|---|---|
| `https://nginx-1.app.nchu.edu.cn/api` | 主 API（登录、配置、应用令牌） |
| `http://miniapp.app.nchu.edu.cn` | 课表 / 成绩 miniapp（需经 `go.nchu.edu.cn` 网关握手） |
| `https://nginx-1.app.nchu.edu.cn/moments/api` | 工作圈（融云圈） |

课表与成绩数据、登录会话仅保存在本机（`filesDir` 缓存 + `SharedPreferences`），不上传任何第三方服务器。

## 致谢

- UI 设计风格参考 [sleepy](https://github.com/lingion/sleepy)（GPL-3.0）
- 液态玻璃底栏实验参考 PocketDiary

## 许可证

[MIT](LICENSE)
