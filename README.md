# DiPlay Lite (Chinese community fork)

A community-adapted build that provides wired and wireless CarPlay for older and various Android head units (API 16+). It is independently evolved from [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay) and the [shilapi/xcertplay](https://github.com/shilapi/xcertplay) receiver base, with a DiAuto-style interface. Upstream attribution, licensing and porting provenance are documented in the [third-party notices](docs/THIRD_PARTY_NOTICES.md) (including JmDNS, Bouncy Castle and the AGPL text for DiAuto).

**Download**: the latest release build is available under [Gitee Releases](https://gitee.com/wangliunian/diplay-lite/releases); install it directly—no local compilation is needed (build instructions are below).

## Disclaimer

**Positioning**: This project is an independent community fork, maintained on a **non-commercial** basis for personal research, owner-to-owner exchange and compatibility maintenance. No commercial sales, paid licensing or commercial support is provided. This positioning does not alter the rights granted by the original code licenses; assets with their own restrictions must comply with those terms—the code's GPL license cannot override asset restrictions.

**License and attribution**:

| Item | Terms |
|---|---|
| Receiver and derivative code | Upstream [GPL-3.0](LICENSE) and existing notices retained; GPL itself permits commercial use and paid distribution subject to its obligations—this fork adds no "non-commercial" clause to it |
| DiAuto-derived interface style | The upstream interface base is DiAuto (AGPL-3.0); modified distributions must retain those notices |
| Opus codec | BSD license |
| CarPlay, iPhone names and icons; automotive brand names and logos | Rights belong to their respective owners; the code license does not automatically grant any right to use brands, marks or other assets |

Upstream projects and original authors retain full authorship credit. This fork is not an official version of the upstream authors or any vendor.

**Trademarks and brands**: CarPlay, iPhone, iPad and Apple are trademarks of Apple Inc.; automotive brand names and logos (BYD, Geely, Ora/Haval, Chery, etc.) belong to their respective owners and are used only for owner identification. This project has no affiliation with, or official support from, Apple, any automaker or the upstream authors, and implies no endorsement.

**Experimental accessory identity**: This project is **not an Apple-certified product**. The experimental CarPlay accessory identity is extracted from publicly available third-party firmware and may stop working after any future iOS update.

**This repository contains no authentication private keys and no Android signing keys**: `identity.pk8` / `certificate.p7b` are explicitly excluded (see `.gitignore`) and blocked by CI—the first step of `.github/workflows/android.yml` runs `scripts/check_public_tree.py`, and any private key, credential or packaged artifact entering the index fails the build. You can also run that script locally as a pre-commit self-check.

**Release builds are provided; everyday use does not require compiling from source**: every release APK is published under [Releases](https://gitee.com/wangliunian/diplay-lite/releases) on the Gitee open-source repository and already bundles the experimental CarPlay accessory identity, so it can be installed and used directly. The source repository and source archives contain no identity assets.

**Building your own release requires providing runtime identity assets yourself**; they are injected at build time and never travel with the source:

- Default source: the private directory `auth/offline-mfi/` at the repository root (containing `identity.pk8` and `certificate.p7b`);
- Alternatively, point the `DIPLAY_IDENTITY_DIR` environment variable at your own directory (more convenient for CI or when switching machines);
- If neither exists, the build continues as a "source-only environment" (the code still compiles), but the resulting APK contains no identity assets and therefore cannot pass CarPlay authentication; the build log warns that identity assets are missing.

Only those two filenames are accepted; a missing one is an error. Keep them safe and **never commit them to any version-control repository** (including private repositories and forks). Compiling and using the result is your own compliance responsibility.

**Compatibility scope and verification status**: compatibility has only been verified on the tested platform/firmware/iOS combinations below; nothing else is promised.

**Model mapping** (project short name ↔ retail device; HU = head unit, Phone = smartphone):

| Short name | Retail device | Type |
|---|---|---|
| K60 | Redmi K60 | Phone |
| C8815 | Huawei C8815 | Phone |
| ONEPLUS A5000 | OnePlus 5 | Phone |
| T3 | Dingwei T3 (T3-A/B/C/D; recent dongle test on T3-B) | HU |
| TS7 | Dingwei TS7 (sprd sp7731e / Mazda 2) | HU |
| EU5 | BAIC EU5 (2019) | HU |
| FE-6 | Geely Binrui (2020) | HU |
| AC822X | FlyAudio GS1 | HU |
| at8317 | Nuoyumi | HU |
| AC8217 | Zhangxun AC8217 | HU |

**Platform and feature scope**:

| Item | Scope / result |
|---|---|
| Verified platforms | K60 (Redmi K60, Android 14 / API34; wireless + wired + external dongle, primary test device), C8815 (Huawei C8815, Android 4.1.2 / API16; minSdk baseline), T3 (Dingwei T3, Android 4.4.2 / API19; T3-A/B/C/D units, recent dongle test on T3-B; built-in Bluetooth unavailable, external dongle can carry Bluetooth), TS7 (Dingwei TS7; historical wired/wireless primary), EU5 (BAIC EU5 2019, Android 6.0.1 / API23), FE-6 (Geely Binrui 2020, Android 5.1 / API22), AC822X (FlyAudio GS1, Android 4.2.2 / API17), at8317 (Nuoyumi, 1920×720), ONEPLUS A5000 (OnePlus 5, Android 10; long-run wired) |
| Confirmed working | Wireless CarPlay (car hotspot): the full flow passes on K60; C8815 connects (the first join is affected by iOS evaluating a hotspot without internet access; measured 76.5 s). Wired CarPlay, full feature set (display / touch / audio / Siri / phone): verified on TS7, ONEPLUS A5000 and K60. External USB Bluetooth dongle: CSR8510 (on-chip firmware, plug-and-play) and CM390 / RTL8761BUV (firmware loaded by the app; verified on T3 hardware). Call audio: EU5 (e5t1). Steering-wheel controls: FE-6 (learning, binding and dispatch). |
| Known limitations / in progress (as of 2026-11-14) | **Resolved/mitigated**: the 35 s wireless cold start was traced to early iOS 16 Wi-Fi link re-establishment; vc268 measures 18.6 s cold / 10.4 s warm (2–3 s on iOS 18), so it is no longer a blocker. T3's built-in SoC Bluetooth is physically unavailable, but an external CSR8510 dongle can establish HCI/ACL, so "wired only" no longer applies. <br>**In progress**: T3 external dongle can enter USB D-state after replug/abnormal exit because the old fd is not released (`open timeout` / `OPEN_TIMEOUT`); requires a head-unit reboot. Reproduced on both 1.5.0-vc249 and 1.5.3-vc268, so it is not a single-version regression. T3 rendering is limited to about 25 fps. AC822X: no `/dev/tun` in the kernel, wired link unavailable. at8317: page-switch stutter (rendering side), mitigated with `videoScalePercent` ≈ 67%. FE-6: no sound after Siri and root microphone capture fails; wireless constrained by the ECARX Bluetooth architecture. PATEO C40DB-EHU (Android 6.0.1 / API23): a single sample shows the wired iAP2 TLS channel dropping after about 17 min (suspected USB physical/driver issue), with no microphone uplink; pending retest. K60: horizontal page switching shows ≥125 ms static-frame freezes (2026-11 screen-recording analysis, under investigation). <br>**New platform pending verification**: AC8217 (Zhangxun AC8217 / Generic Android on ac8317, Android 4.4.2 / API17, 1024×600) did not join wireless CarPlay in its first sample (`credential-no-join`), and JmDNS failed to bind on `ap0`; not yet verified. Android 14 re-prompts for USB permission after dongle replug; T3/KK usually does not prompt but may stall at open. |
| Not promised | Real-car compatibility for other years, chipsets and firmware; NCM/multicast behavior differs significantly on older kernels such as Android 4.4.2 (KK) and is not promised; continued availability after future iOS releases; complete model coverage for features such as Siri and calls; no conclusions are drawn for devices that cannot be attributed in logs (no `ROM version` field). |

"Adapted/verified" refers only to the specific platforms and verified features above; it does not mean that every model, firmware or feature has passed real-car testing.

**No warranty and driving safety**: The software is provided "as is", without any express or implied warranty. Installation and use are at your own risk. Head units are driving-related equipment—**do not operate or configure this app while driving**, and comply with local laws.

**Device modification and diagnostic data**: Some features (wired CarPlay on certain platforms, adb self-boot, the NCM unbind guard, etc.) rely on **modifying your own head unit's system** (root/adb), which may void the warranty or even brick the device; you are fully responsible for the consequences. Diagnostic logs leave the device only when you **explicitly trigger an upload**; the log-upload credential is injected at build time and never committed.

**Signing and overwrite installation**: A personally rebuilt APK usually has a different signature and cannot be installed over an existing signed package (uninstall and reinstall is required; settings are not preserved).

## Credits

- [DiPlay / shihabal3amri](https://github.com/shihabal3amri/DiPlay): direct upstream.
- [xcertplay / shilapi](https://github.com/shilapi/xcertplay): CarPlay receiver base.
- [DiAuto](https://github.com/shihabal3amri/DiAuto): interface style base.
- Original project contributors and owners who took part in real-car testing.

Original licenses, attribution and third-party notices: see [LICENSE](LICENSE).

---

> The remainder of this README is the upstream project's description, preserved for reference.

**CarPlay for compatible Android head units.** Wired and wireless, with the familiar DiAuto interface. Independent app: `com.shihab.diplay`.

[Download & website](https://shihabal3amri.github.io/DiPlay/) · [Release](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.0) · [Report a problem](https://github.com/shihabal3amri/DiPlay/issues/new/choose)

![DiPlay home](site/assets/home.png)

## 0.2.0 — public preview

Install on the **car**, not the iPhone. No jailbreak, dongle, Mac, account or authentication server is required for use. ADB is not needed during everyday use; your head unit must permit APK installation. Wireless supports Wi-Fi Direct or the car’s existing hotspot; Wi-Fi Direct requires Android 10+; the APK supports Android 9+ for wired use.

- Wired USB and wireless CarPlay with local authentication.
- BYD HUD navigation with arrows, distance and street names on verified firmware.
- Car hotspot support, improved audio buffering and saved receive diagnostics.
- Automatic address discovery, fixed-channel Wi-Fi fallbacks and successful-configuration memory.
- Icon/text size, resolution and frame rate; applying a display change reconnects CarPlay.
- Local diagnostic export. Reports are sent only if you choose to share them.
- Separate installation alongside DiAuto. Run one projection app at a time.

This is **not an Apple-certified product**. The APK bundles an experimental accessory identity recovered from public Carlinkit firmware, not a newly provisioned MFi identity for DiPlay. A bundled private key is extractable. Acceptance after future iOS updates, reliability across head units and suitability of that identity for general distribution are unresolved. This release invites community testing; it is not a guarantee of universal compatibility.

The release changes were tested on the development DiLink5.1 car: live windshield guidance and street names work, Car hotspot now starts CarPlay, and Wi-Fi Direct performance is substantially improved. Occasional audio cutouts remain and are deferred to a later update. Broader head-unit and iOS compatibility is not guaranteed. The HUD firmware scope and cleanup limits are documented in [BYD navigation](docs/BYD_NAVIGATION.md).

## Documentation

- [Install and connect](docs/INSTALL.md)
- [Compatibility and troubleshooting](docs/COMPATIBILITY.md)
- [Privacy and diagnostic reports](docs/PRIVACY.md)
- [Build from source](docs/BUILD.md)
- [Validation](docs/VALIDATION.md)
- [Release notes](CHANGELOG.md)
- [Credits and licenses](docs/THIRD_PARTY_NOTICES.md)

The website is available in English, Arabic, Russian, Spanish and Simplified Chinese. The current app interface is English.

## Source and credits

Based on [xcertplay](https://github.com/shilapi/xcertplay), GPL-3.0. The home/settings UI and website adapt [DiAuto](https://github.com/shihabal3amri/DiAuto), AGPL-3.0; that license is included in `docs/licenses`. Preserve those notices when distributing modifications. CarPlay and its icon belong to Apple Inc.; no Apple or BYD affiliation or endorsement is implied.

This repository starts with a clean public source snapshot. Local research, tester reports and release-signing secrets are excluded. The complete source corresponding to the APK is provided with every release; experimental runtime identity assets are described separately in the build instructions and notices.

## Local release packaging

The release APK intentionally contains the experimental accessory identity. The Git repository and source archive exclude all accessory and Android signing keys; tests generate synthetic identities at runtime. Source/CI builds omit runtime identity assets by default. Local release builds explicitly select an external asset directory. Publishing the APK makes its bundled identity extractable; building locally does not preserve that identity's confidentiality.
