# DiPlay Lite（中文社区维护分支）

为老款及各平台安卓车机（API 16+）提供有线及无线 CarPlay 的社区适配版，
基于 [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay) 及
[shilapi/xcertplay](https://github.com/shilapi/xcertplay) 接收器基础独立演化，
DiAuto 风格界面。上游署名、许可与移植溯源详见
[第三方声明](docs/THIRD_PARTY_NOTICES.md)（含 JmDNS、Bouncy Castle 与 DiAuto 的 AGPL 文本）。

**下载**：最新发行包见 [Gitee Releases](https://gitee.com/wangliunian/diplay-lite/releases)，安装即用，无需自行编译（源码编译说明见下文）。

## 免责声明

**定位**：本项目为独立社区修改版，按**非商业**方式维护，仅供个人研究、车友交流与
兼容性维护，不提供商业销售、付费授权或商业技术支持。该定位不改变原代码许可证
赋予的权利；素材另有许可限制的，须单独符合相应条款，不能仅凭代码的 GPL 许可
忽略素材限制。

**许可与署名**：

| 内容                              | 适用说明                                                                        |
| ------------------------------- | --------------------------------------------------------------------------- |
| 接收器及相应衍生代码                      | 保留上游 [GPL-3.0](LICENSE) 及原有通知；GPL 本身允许商业使用与收费分发，同时要求履行相应义务，本分支不为其新增"禁止商用"条款 |
| DiAuto 衍生界面风格                   | 上游界面基础为 DiAuto（AGPL-3.0），分发修改版须保留相应声明                                       |
| Opus 编解码器                       | BSD 许可                                                                      |
| CarPlay、iPhone 名称与图标，各车厂品牌名称与图标 | 权利归各自权利人；代码许可证不自动授予品牌、标识或其他素材的使用许可                                          |

上游项目与原作者保留完整署名。本分支不代表上游作者或任何厂商的官方版本。

**商标与品牌**：CarPlay、iPhone、iPad、Apple 为 Apple Inc. 的商标；各汽车品牌名称
与图标（比亚迪、吉利、欧拉/哈弗、奇瑞等）归各自权利人所有，仅供车主个性化识别。
本项目与 Apple、各车厂、上游作者无任何隶属或官方支持关系，亦不构成任何背书。

**实验性配件身份**：本项目**不是 Apple 认证产品**。所用实验性 CarPlay 配件身份从公开
第三方固件提取而来，未来 iOS 更新随时可能使其失效。

**本仓库不包含任何认证私钥与 Android 签名密钥**：`identity.pk8` / `certificate.p7b`
已被明确排除（见 `.gitignore`），并由 CI 强制拦截——`.github/workflows/android.yml`
第一步即运行 `scripts/check_public_tree.py`，任何私钥、凭证或打包产物进入索引都会让
构建直接失败。本地亦可直接执行该脚本做提交前自检。

**发行包已提供，日常使用无需自行编译**：每版发行 APK 在 Gitee 开源仓
[Releases](https://gitee.com/wangliunian/diplay-lite/releases) 提供，包内已含实验性
CarPlay 配件身份，可直接安装使用；源码仓与源码包不包含任何身份资产。

**自行出包才需自行准备运行时身份资产**，构建期注入，不随源码流转：

- 默认来源：仓库根下的私有目录 `auth/offline-mfi/`（内含 `identity.pk8`、`certificate.p7b`）；
- 也可用环境变量 `DIPLAY_IDENTITY_DIR` 指向自有目录覆盖（CI / 换机时更方便）；
- 两者皆无时按「纯源码环境」继续构建（代码仍可正常编译），产出 APK 不含身份资产，
  因而无法通过 CarPlay 认证，构建日志会给出身份资产缺失提醒。

身份资产只允许上述两个文件名，缺一即报错；请自行妥善保管，**切勿提交到任何版本
仓库**（含私有仓与 fork）。自行编译与使用由用户自行承担合规责任。

**适配范围与验证状态**：兼容性仅在下列实测平台/固件/iOS 组合上验证过，其余不作承诺。

**当前软件上机机型实测对照**（项目简称 ↔ 市售机型；车机简称 HU，手机 Phone，平板 Tablet）：

| 项目简称 | 市售机型 | 类型 |
|---|---|---|
| K60 | Redmi K60 | 手机 |
| C8815 | 华为 C8815 | 手机 |
| ONEPLUS A5000 | 一加 5 | 手机 |
| S8 | 三星 Galaxy S8（国行） | 手机 |
| S9+ | 三星 Galaxy S9+（国行） | 手机 |
| Tab3-7 | 三星 Galaxy Tab 3 7.0 | 平板 |
| ANE-AL00 | 华为 nova 3e | 手机 |
| Pad5 | 小米 Pad 5（11 英寸 WiFi，待官方复核） | 平板 |
| Redmi14C | Redmi 14C（4G，待官方复核） | 手机 |
| A72-5G | OPPO A72 5G | 手机 |
| T3 | 鼎微 T3（T3-A/B/C/D；近期 dongle 实测 T3-B） | 车机 |
| TS7 | 鼎微 TS7（sprd sp7731e / Mazda 2） | 车机 |
| EU5 | 北汽 19 款 EU5 | 车机 |
| FE-6 | 吉利 20 款缤瑞 | 车机 |
| AC822X | 飞歌 GS1 | 车机 |
| AC8227 | ATC AC8227 公版 | 车机 |
| at8317 | 糯玉米 | 车机 |
| AC8217 | 掌讯 AC8217 | 车机 |
| C40DB | 博泰 C40DB-EHU（北汽 C40 平台，推断） | 车机 |
| MX6DQ | 飞思卡尔 SABRESD-MX6DQ（公版） | 车机 |
| MX9 | 飞思卡尔 MX9（公版） | 车机 |
| 8227L | 联发科 8227L demo（公版） | 车机 |
| BEP10A | 创维酷开 BEP10A（待核实） | 车机/盒子 |
| E17 | GM E17 | 车机 |

> 含已上机实测、历史记录与待验证样本；括注“推断 / 公版 / 待核实”者不代表已通过完整功能验证。

**平台与功能范围**：

| 项目 | 范围 / 结果 |
|---|---|
| 已验证平台 | K60（Redmi K60，Android 14 / API34，无线 + 有线 + 外置 dongle 主力实测）、C8815（华为 C8815，Android 4.1.2 / API16，minSdk 基准）、T3（鼎微 T3，Android 4.4.2 / API19；T3-A/B/C/D 四台，近期 dongle 实测为 T3-B；内置蓝牙不可用，外置 dongle 可走蓝牙链路）、TS7（鼎微 TS7，历史无线/有线主力）、EU5（北汽 19 款 EU5，Android 6.0.1 / API23）、FE-6（吉利 20 款缤瑞，Android 5.1 / API22）、AC822X（飞歌 GS1，Android 4.2.2 / API17）、at8317（糯玉米，1920×720）、ONEPLUS A5000（一加 5，Android 10，有线全功能长跑） |
| 已确认可用 | 无线 CarPlay（车机热点）：K60 全流程通过；C8815 可连（首次入网受 iOS 对「无外网热点」评估影响，实测 76.5 s）。有线 CarPlay 全功能（显示/触摸/音频/Siri/电话）：TS7、ONEPLUS A5000、K60 实测。外置 USB 蓝牙 dongle：CSR8510（片内固件，即插即用）、CM390 / RTL8761BUV（App 侧灌固件，T3 真机已验证）。通话收音：EU5（e5t1）。方控：FE-6（学习绑定 + 派发）。 |
| 已知限制 / 攻坚中（截至 2026-11-17） | **已定案/已缓解**：无线冷启动 35 s 已定案为 iOS 16 早期 Wi-Fi 链路重建，vc268 实测冷 18.6 s / 热 10.4 s（iOS 18 同链路 2~3 s），不再作为阻塞项；vc317 未改冷启动路径；K60 上插回外置蓝牙后的会话恢复实测 7.9 s 出画。T3 内置 SoC 蓝牙物理不可用，但外置 CSR8510 dongle 可建立 HCI/ACL 链路，不再表述为「T3 只能有线」。<br>**仍在攻坚**：T3 外置 dongle 在拔插/异常退出后可能因旧 fd 未释放进入 USB D 态，表现为 `open 超时` / `OPEN_TIMEOUT`，需重启车机；1.5.0-vc249 与 1.5.3-vc268 均可复现，不是单一版本回归。T3 渲染约 25 fps 瓶颈。AC822X：内核无 `/dev/tun`，有线链路不可用。at8317：切页卡顿（渲染端），暂以 `videoScalePercent`≈67% 等比缩放缓解。FE-6：Siri 后无声、root 麦克风直采失效；无线受 ECARX 蓝牙架构限制。PATEO C40DB-EHU（博泰，Android 6.0.1 / API23）：单次样本约 17 min 后 iAP2 TLS 断链（疑 USB 物理/驱动），且无麦克风上行，待复测。K60：横向切页存在 ≥125 ms 静止帧顿挫（2026-11 录屏分析，定位中）。<br>**新平台待验证**：AC8217（掌讯 AC8217 / Generic Android on ac8317，Android 4.4.2 / API17，1024×600）：首个样本未入网——日志显示尝试过程中车机热点转为关闭，且在复用热点句柄后 AirPlay 端口绑定报 EADDRNOTAVAIL 失败；JmDNS 多播在 ap0 上也以 ENODEV 失败。尚未验证可用性。Android 14 重插 dongle 会重新弹 USB 授权；T3/KK 通常不弹授权，但可能卡在 open。 |
| 尚未承诺 | 其他年份、芯片和固件的实车兼容性；Android 4.4.2 等老内核（KK）的 NCM/组播行为差异显著，兼容性不承诺；未来 iOS 版本的持续可用性；Siri/通话等功能的完整车型覆盖；日志中不可归因的机型（无 `ROM版本` 字段）不作结论。 |

"已适配/已验证"仅指上述具体平台和已验证功能，不表示所有车型、固件和每项功能
均已通过实车测试。

**无担保与行车安全**：软件按"现状"提供，不附任何明示或默示担保。安装与使用风险
由用户自担。车机属于行车相关设备——**驾驶中请勿操作或配置本应用**，并遵守当地
法律法规。

**设备修改与诊断数据**：部分功能（特定平台的有线 CarPlay、adb 自举、NCM 解绑守护
等）依赖**修改用户自有车机的系统**（root/adb），可能导致失去保修甚至设备变砖，后果
完全由用户自行承担。诊断日志仅在用户**主动触发上传**时才会离开设备；日志上传凭据
由构建时注入，绝不入库。

**签名与覆盖安装**：个人重新编译的 APK 签名通常不同，不能直接覆盖既有签名的
安装包（需卸载重装，设置不保留）。

## 致谢

- [DiPlay / shihabal3amri](https://github.com/shihabal3amri/DiPlay)：直接上游。
- [xcertplay / shilapi](https://github.com/shilapi/xcertplay)：CarPlay 接收器基础。
- [DiAuto](https://github.com/shihabal3amri/DiAuto)：界面风格基础。
- 原项目贡献者及参与实车测试的车友。

原始许可、署名和第三方通知见 [LICENSE](LICENSE)。
