# 1.7.0-vc318 — 2026-11-17（USB 有线信任修复）

- 修复 USB 有线连接间歇性导致车机卡死、iPhone 反复弹出「要信任此电脑吗」的问题：Lockdown 配对在等待用户点「信任」期间保持同一条连接原地轮询，不再每秒断开重连重发配对请求，弹窗只出现一次。
- 配对总超时由 5 分钟缩短为 2 分钟，并加入配对单飞锁与 3 秒冷却，建链线程不再被未处理的信任弹窗占住数分钟，车机侧不再长时间停留在「正在配对」。
- HostID / SystemBUID 首次生成后持久化，重配时沿用同一身份；已保存的配对记录被瞬时 USB 抖动误判失效时先用同一记录重试一次，确属真失效才重配，iPhone 不再把每次连接当成一台新电脑。

# 1.7.0 — 2026-11-17

- Four-state Bluetooth gate on the home screen (dongle not inserted / connect failed / not paired / stopped); the primary button no longer greys out — tapping it states the reason and offers the matching action.
- Failed-connection card now carries an actionable button (open hotspot / go to pairing / retry), selected by failure reason.
- Unplugging the external dongle no longer tears down an active session, fires a false error, or burns five reconnect attempts; replugging restores the broadcast automatically.
- Fixed Android 10+ reporting "dongle not detected" while the dongle was actually ready.
- Added a disconnect entry while a session is active; fixed state and button mix-ups after rotation or returning from the settings page.
- Link-loss detection shortened from about 22 s to about 10 s on Android 10+; a slow iPhone re-join is no longer judged as a failure too early.
- Fixed a relay-mode stall (up to 93 s) caused by a missing channel field in the hotspot information.
- Fixed colour artifacts at 50%/75% scaling, and post-resume artifacts on Android 4.x devices.
- Fixed an occasional native crash on Bluetooth teardown on Android 4.1.
- Removed the "mDNS connection boost" options; they showed no measurable benefit.
- Removed the hard-coded "30 seconds" from failure messages and shortened seven status labels.

# 1.5.5 — 2026-11-14

- Fixed the wireless link hanging at "building link" after connecting to the car hotspot: the hotspot information sent to the phone was missing the channel number.
- Fixed display artifacts after switching to the home screen and back; only a clean keyframe is shown on resume.
- Connection speed: no longer waits an extra ~8 s when the iPhone is already on the hotspot.
- Unknown hotspot channel now defaults by band (channel 6 for 2.4 GHz, 149 for 5 GHz).
- Log upload anonymisation restored; two misleading diagnostics corrected.

# 1.5.3 — 2026-11-14

- Wireless cold start gated on iPhone network join: cold 36.5 s → 18.6 s, warm 35 s → 10.4 s.

# 0.2.0 — BYD navigation and connection improvements

- Standalone windshield HUD arrows, distance and street names on the verified DiLink5.1 firmware; no ADB, root or computer helper.
- Retain contributor cluster/SOME-IP navigation, route parsing, BYD CarPlay icon and display-size presets.
- Fix Car hotspot startup by using scoped IPv6 when available and binding discovery/probing to the AP interface. Physically confirmed on the development car.
- Drain asynchronously decoded audio during packet gaps and rebuild the music buffer after starvation. Wi-Fi Direct is much better in the user retest; occasional audio cutouts remain for a later version.
- Preserve bounded music-buffer choices, USB read improvements and decoder recovery; fix USB request/close races and keep vendor output outside phone callbacks.
- Save audio/video/receive timing and discovery diagnostics without road names or protocol payloads.
- HUD cleanup on normal end/disconnect/off/stale input; interrupted sessions recover on the next app launch. Force-stop may leave guidance visible until reopening.
- Thanks to @romanchukg-cloud and @georgiyrr for PR #3 and vehicle testing.

# 0.1.0 release restored — 2026-09-25

- Rebuilt and signed the APK locally with explicitly supplied runtime authentication assets.
- Restored release downloads; no app behavior or version-code change from 0.1.0.
- Accessory identity remains in the APK only. No credential files enter Git or the source archive.
- Retained generated test identities and public-source credential checks.
- Source/CI builds omit runtime identity assets by default; local packaging requires an explicit external directory.

# Source reset — 2026-09-25

- Withdrew the 0.1.0 APK and removed its release tag.
- Reset the public branch after preserving restricted local incident records.
- Removed static synthetic test private keys; generate test identities at runtime.
- Removed automatic private-asset packaging and disabled the old release build script.
- Added a build guard rejecting credential asset files.
- Replaced the download site with a five-language suspension notice.

The APK was subsequently rebuilt and restored as described above. Existing copies cannot be recalled by a Git history reset.
