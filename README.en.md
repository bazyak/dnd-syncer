# DND syncer

[Русский](README.md) · **English**

Keeps Do Not Disturb, theater mode and bedtime mode in sync between a Pixel 10
Pro (Android 17, rooted) and a OnePlus Watch 4 (Wear OS 6 / Android 16). No
settings screen — just a welcome screen showing the status of each access.

## What syncs

| Event | Result |
|---|---|
| DND on the phone (manually, via tile, on schedule) | same on the watch |
| DND on the watch | same on the phone |
| Theater mode on the watch | DND on the phone |
| DND turned off on the phone | everything clears on the watch, theater included |
| Bedtime mode on the watch | bedtime mode (Digital Wellbeing) on the phone |
| Bedtime mode on the phone | bedtime mode on the watch |

Theater mode is only ever enabled by hand on the watch: it cannot be turned on
programmatically, and the phone has no equivalent.

## State model

Devices exchange a **snapshot** of two independent flags rather than a delta:

```
ModeState(dnd: Boolean, night: Boolean)
```

The receiver compares the snapshot against its own current state and does
nothing when they match, so the loop terminates on its own — no separate echo
suppression is needed.

### On the watch

`zen_mode` is an OR of every silence source and never says which one is active,
so the snapshot is derived from three `Settings.Global` keys:

```
dnd   = theater_mode_on==1 || (zen_mode!=0 && bedtime_mode==0)
night = bedtime_mode==1
```

| bedtime, zen, theater | meaning | dnd | night |
|---|---|---|---|
| 0,0,0 | nothing | false | false |
| 0,1,0 | manual DND | true | false |
| 0,1,1 | theater (± manual DND) | true | false |
| 1,1,0 | bedtime only | false | true |
| 1,1,1 | bedtime + theater | true | true |

Rows 3 and 4 are deliberately indistinguishable — the resulting behaviour is
identical. The `bedtime==0` term works because **on the watch** bedtime and
manual DND are mutually exclusive: enabling one clears the other.

### On the phone

There bedtime and DND coexist while sharing the same `zen_mode`, so state is
parsed out of `dumpsys notification`:

- bedtime — an `AutomaticZenRule` with `type=3` (`TYPE_BEDTIME`) in `STATE_TRUE`;
- DND — `MANUAL_RULE` in `STATE_TRUE`.

The trigger is a `ContentObserver` on `zen_mode`, `theater_mode_on`,
`bedtime_mode` and `zen_mode_config_etag`. That last key is essential: when DND
is already on and bedtime is enabled on top, `zen_mode` does not change, but the
etag changes on any zen config edit.

Publishing is debounced by 700 ms — a mode change touches several keys in
sequence, and without the delay an intermediate state gets sent.

## Why everything goes through the shell

`NotificationManager.setInterruptionFilter()` is not usable:

- it creates a zen rule with `enabler` set to our package name, and an app may
  only clear its own rules. Manual DND (`enabler=android`) is off limits — which
  is exactly why turning DND off from the phone did nothing;
- on the OnePlus Watch 4 it has no effect at all. Verified:

```
adb shell cmd notification set_dnd priority
adb shell dumpsys notification | grep mZenMode
→ mZenMode=ZEN_MODE_IMPORTANT_INTERRUPTIONS   (shell command)
→ mZenMode=ZEN_MODE_OFF                        (setInterruptionFilter)
```

Writing `zen_mode` directly with `WRITE_SECURE_SETTINGS` is out too: the value
is stored but the system ignores it.

The only thing that works is `cmd notification set_dnd priority|off`, and that
is restricted to uid shell (2000) or root. Root covers the phone, Shizuku the
watch.

## Bedtime mode on the phone

The rule belongs to Digital Wellbeing
(`pkg=com.google.android.apps.wellbeing`, `conditionId=.../winddown`), and per
the documentation only that app may own `TYPE_BEDTIME` rules — creating our own
is not an option.

Calling `setAutomaticZenRuleState()` from the app process succeeds without an
error yet is silently ignored: the check looks at the calling uid. So the same
call is made from a separate process running as the system:

```
su 1000 -c "CLASSPATH=<apk> app_process / com.bazyak.dndsyncer.phone.NightHelper <id> <uri> on"
```

No separate dex is required — the app's own APK is already on the device and
works as the CLASSPATH. `NightHelper` reflects its way to
`INotificationManager` via `ServiceManager` and calls
`setAutomaticZenRuleState` with `Condition.SOURCE_USER_ACTION`.

Tried and rejected: Wellbeing's own broadcasts (`TURN_OFF_WIND_DOWN`,
`WIND_DOWN_ALARM_TRIGGERED`, `PAUSE`/`RESUME`) — they get delivered but have no
effect.

## Access

The only permission declared in a manifest is `WRITE_SECURE_SETTINGS` on the
watch. Everything else is granted externally.

**Accessibility service** (both devices) — granted with a button in the app. It
handles no events and reads no screen content: the service exists purely to keep
the process alive so the `ContentObserver` keeps running. It replaced
`NotificationListenerService`, which required notification-reading access.

**Phone:** root. Magisk will prompt on first launch.

**Watch:** Shizuku plus `WRITE_SECURE_SETTINGS`.

```
adb shell pm grant com.bazyak.dndsyncer android.permission.WRITE_SECURE_SETTINGS
```

## Installing

```
./gradlew :mobile:assembleDebug :wear:assembleDebug
adb -s <phone> install -r mobile/build/outputs/apk/debug/mobile-debug.apk
adb -s <watch> install -r wear/build/outputs/apk/debug/wear-debug.apk
```

Both APKs share one `applicationId` and must be signed with the same key,
otherwise the Data Layer will not treat them as one app. Their versions may differ.

### Shizuku on the watch

```
adb install shizuku.apk
adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
```

Grant the app access with the button on the watch screen; if the dialog does not
fit the round display:

```
adb shell pm grant com.bazyak.dndsyncer moe.shizuku.manager.permission.API_V23
```

To have Shizuku start itself after a reboot, grant it `WRITE_SECURE_SETTINGS`
and enable "Start on boot" — it brings up wireless debugging on its own:

```
adb shell pm grant moe.shizuku.privileged.api android.permission.WRITE_SECURE_SETTINGS
```

## Auto-starting Shizuku on the watch

Wear OS keeps Wi-Fi off while the watch is paired to the phone over Bluetooth,
and Shizuku tries to enable wireless debugging exactly once at boot — there is
no network at that moment and it never retries. So the app walks the chain
itself:

1. wait for Wi-Fi via `registerNetworkCallback`;
2. `Settings.Global["adb_wifi_enabled"] = 1`;
3. Shizuku's official auto-start intent:
   `moe.shizuku.privileged.api.START` with the `auth` extra.

**We cannot turn the network on.** Verified on the OnePlus Watch 4: writing
`adb_wifi_enabled` really does toggle wireless debugging, while `wifi_on` is a
mirror — the value is stored but Wi-Fi stays off. `WifiManager.setWifiEnabled()`
has been closed to ordinary apps since Android 10 and there is no way around it.
So after a reboot Wi-Fi has to be switched on by hand, once, from the watch's
Wi-Fi menu.

Everything after that is automatic: the network callback stays registered, so it
covers not just boot but any Wi-Fi drop — come home, the network returns, Shizuku
comes back on its own. Plus attempts at boot, on the first command from the
phone, and once a day around 4 AM.

**The token is required.** Find it in Shizuku itself: "Use Shizuku in automation
apps" → "View intents" → Extras → `auth`. Put it in `gradle.properties`:

```
shizukuAuth=<token>
```

Pressing the refresh button next to the token in Shizuku invalidates the old
one, so rebuild after doing that.

## Versions and APK names

Versions live in `version.properties` at the root, are computed once in the root
`build.gradle.kts` and handed to the modules. The phone and the watch keep
separate counters: a change often touches only one of them.

Build rules:

| situation | result |
|---|---|
| versions equal | the module being built is bumped |
| building the lagging one | it is aligned to the leading one, no bump |
| building the leading one | it is bumped further |
| building both at once | both get `max + 1` |

`versionCode` only ever grows during alignment — otherwise installing over a
previous build would be rejected. Sync and clean leave the numbers alone.

Matching versions across the pair are not required: the Data Layer links the
APKs by `applicationId` and signing key. The rules above exist so drift collapses
at the first shared build instead of accumulating forever.

The resulting files are named:

```
DND syncer - 1.0.7.apk
DND syncer (wear) - 1.0.7.apk
```

`major` and `minor` are edited by hand in the same file.

Gradle's configuration cache is disabled on purpose: with it the configuration
phase is reused and the auto-increment never runs.

## Why the payload is more than just state

A snapshot carries the two flags plus a marker for which fields changed on the
sender. The receiver applies **only the changed** fields.

Without that the two devices resonated, as captured in the log on September 15.
At 07:00 the Wellbeing schedule ends the night on the phone. A second later the
watch publishes `night=true` with reason `zen_mode_config_etag` — nothing had
changed there, the etag just moved, and the code used to publish a snapshot
unconditionally. The watch had been holding `bedtime_mode=1` all night, and that
stale value is what it sent. The phone took it as a command and went back into
night mode. The two then bounced the state 19 times over 14 minutes.

Hence two rules:

- publish only when the state actually changed since the last publish (the last
  published state lives in SharedPreferences and survives a process restart);
- apply only the fields the peer marked as changed.

When a service connects, the snapshot goes out with no markers: that is a state
exchange, not a command.

## Logs

The app writes a verbose log to a file — logcat does not survive a reboot, and
the bug being chased happens once a night.

- phone: `Downloads/DND syncer/dnd-syncer-phone.log` (visible in the Files app)
- watch: `/sdcard/Android/data/com.bazyak.dndsyncer/files/dnd-syncer-watch.log`

The phone screen has a logging switch and a button to pick a different folder
(system picker; the grant is persisted across reboots).

Rotation follows the linux convention: at 4 MB the current file becomes
`.log.1`, the old `.1` shifts to `.2` and so on up to `.3`, and the oldest is
dropped.

Command output is truncated before it reaches the log: `dumpsys notification` is
about a megabyte per call, and without truncation it piled up over two hundred
megabytes in a night.

```
adb -s <watch> pull /sdcard/Android/data/com.bazyak.dndsyncer/files/dnd-syncer-watch.log
```

What gets logged: every ContentObserver fire with the name of the key that
changed, all three flags on every event, snapshots sent and received with their
reason, every shell command with its output, the result of each apply verified
against actual state, and on the phone also the sequence of zen rule states from
`dumpsys notification` plus the `Diff[...]` lines showing who flipped a rule and
when.

The phone screen has a "Записать срез в лог" button that dumps the full rule
picture at the current moment.

## Notes

- **Power Saver** on the Watch 4 switches to the BES2800 chip where Wear OS does
  not run — syncing pauses and catches up on return to Smart mode.
- **Theater cannot be cleared on its own from the phone**: on `dnd=false`,
  `theater_mode_on` is cleared first, otherwise theater keeps `zen_mode` up.
- **Scheduled bedtime** is cleared by Wellbeing itself, and that propagates to
  the watch through the normal path.
- No polling and no timers anywhere: only system callbacks and
  `ContentObserver`.
