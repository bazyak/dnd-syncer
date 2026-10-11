# DND syncer

**English** · [Русский](README.ru.md)

Mode sync between a Pixel 10 Pro (Android 17, root) and a OnePlus Watch 4
(Wear OS 6 / Android 16)

## What syncs

| Event | Result |
|---|---|
| Do Not Disturb on the phone (manually, via tile, on schedule) | same on the watch |
| Do Not Disturb on the watch | same on the phone |
| Theater mode on the watch | Do Not Disturb on the phone |
| DND turned off on the phone | everything clears on the watch, theater included |
| Bedtime mode on the watch | bedtime mode (Digital Wellbeing) on the phone |
| Bedtime mode on the phone | bedtime mode on the watch |

Theater mode is only turned on by hand on the watch: it cannot be enabled
programmatically, and the phone has no equivalent anyway

## State model

Devices exchange a **snapshot** of two independent flags rather than a delta:

```
ModeState(dnd: Boolean, night: Boolean)
```

The receiver applies only what the peer marked as changed and stays silent when
it already matches. Whatever it applied on the peer's command is not sent back:
the peer already knows that value, and a late echo would look like a command to
it and bring back a mode it had just turned off

Applying a command and deciding to publish never overlap, otherwise a publish
could catch an apply halfway and send an intermediate state

### On the watch

`zen_mode` is an OR of all silence sources and does not say which one is
active. So the snapshot is built from three `Settings.Global` keys:

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

Theater and "theater + manual DND" are indistinguishable by the keys: zen_mode is an OR, so under theater it is 1 either way. There is no need to tell them apart: the snapshot is the same, and when DND is turned off from the phone, the watch clears theater and then DND

### On the phone

There bedtime and DND **coexist** while sharing one `zen_mode`, so the state is
read from `dumpsys notification`:

- bedtime — an `AutomaticZenRule` with `type=3` (`TYPE_BEDTIME`) in `STATE_TRUE`;
- DND — `MANUAL_RULE` in `STATE_TRUE`

The trigger is a `ContentObserver` on `zen_mode`, `theater_mode_on`,
`bedtime_mode` and `zen_mode_config_etag`. The last one is essential: if DND is
already on and bedtime is turned on on top, `zen_mode` does not change, while
the etag changes on any zen config edit

Publishing is delayed by 700 ms — a mode change touches several keys in a row,
and without the delay an intermediate state gets sent

## Why everything goes through the shell

`NotificationManager.setInterruptionFilter()` does not work here:

- it creates a zen rule with `enabler` set to our package name, and an app can
  only clear its own rules. Manual DND (`enabler=android`) is off limits to
  it — which is exactly why turning DND off from the phone did not work;
- on the OnePlus Watch 4 it has no effect at all. Verified:

```
adb shell cmd notification set_dnd priority
adb shell dumpsys notification | grep mZenMode
→ mZenMode=ZEN_MODE_IMPORTANT_INTERRUPTIONS   (shell command)
→ mZenMode=ZEN_MODE_OFF                        (setInterruptionFilter)
```

Writing `zen_mode` directly via `WRITE_SECURE_SETTINGS` is out too: the value is
stored, but the system ignores it

The only way that works is `cmd notification set_dnd priority|off`, and only uid
shell (2000) or root may run it. On the phone that is root, on the watch
Shizuku

## Choosing which modes sync

The watch only has filters for its own modes (DND and theater), so the custom
filters of the phone's Driving and Transit modes do not carry over. That makes
syncing these modes rarely needed — hence the setting

## Permissions

**Accessibility** (both devices) — the app enables it for itself: on the phone
via root, on the watch via `WRITE_SECURE_SETTINGS`. This happens when the
process starts and when the app screen is opened, so the service comes back
even after a force stop. The «Разрешить» button on the screen is only needed
while there is no root or `WRITE_SECURE_SETTINGS`

The service handles no events and reads no screen content: it is there only as
a living process that keeps the `ContentObserver` running. It replaced
`NotificationListenerService`, which required notification access

**Phone:** root, no other permissions in the manifest. The Magisk prompt
appears on first launch

**Watch:** the Shizuku permission is granted with a button on the watch screen,
`WRITE_SECURE_SETTINGS` via adb:

```
adb shell pm grant com.bazyak.dndsyncer android.permission.WRITE_SECURE_SETTINGS
```

On first launch the watch also asks for the notification permission. There is
only one notification: DND arrived from the phone, but there is nothing to apply
it with — Shizuku is not running and cannot be started without Wi-Fi. The data
itself travels between the devices over Bluetooth; Wi-Fi is only needed to start
Shizuku

## Shizuku autostart on the watch

The app starts Shizuku on its own as soon as the watch connects to Wi-Fi:

1. catches the network coming up via `registerNetworkCallback`;
2. enables wireless debugging: `Settings.Global["adb_wifi_enabled"] = 1`;
3. sends Shizuku the start intent `moe.shizuku.privileged.api.START` with the
   token in the `auth` extra

If there is no Wi-Fi at the moment of an attempt, the app waits for the network
and finishes the chain right after it connects. Attempts are made when the
watch boots, right after the token arrives, when DND changes on the phone while
Shizuku is not running, once a day around 4 AM, and with the «Запустить» button
on the watch screen

Once Shizuku is up, the watch applies the phone's current state: the phone's
latest snapshot is already on the watch in the Data Layer. Missed commands are
not replayed — while Shizuku was down, the phone's mode may have changed any
number of times, and only the end result matters

The exception is a mode changed on the watch after the phone's latest snapshot: for example, DND was turned on on the watch, and the phone has not yet picked it up and sent a new snapshot. The catch-up leaves such a mode alone, otherwise it would bring back the phone's old value

### Token

Shizuku rejects the start intent without the token, so the token is required.
It is entered on the phone, in the «Токен Shizuku» card, travels to the watch
over the Data Layer and is stored there

If the token is not set or has been removed, the watch does not try to start
Shizuku at all: not when Wi-Fi comes up, not when DND changes on the phone, not
on schedule

The token itself is shown in Shizuku on the watch: «Управляйте Shizuku с помощью
приложений автоматизации» → «Просмотр намерений» → Extras → `auth` (labels from
the Russian UI). It does not fit on the watch screen in full, so it is pulled
via adb with that screen open:

```
adb shell "uiautomator dump /sdcard/ui.xml >/dev/null && cat /sdcard/ui.xml" | grep -oE 'text="[A-Za-z0-9_-]{20,}"' | cut -d'"' -f2
```

## Notes

- **Power Saver** on the Watch 4 switches the watch to the BES2800 chip, where
  Wear OS does not run — syncing stops and catches up on return to Smart mode
- **Scheduled bedtime** is turned off by Wellbeing itself, and that reaches the
  watch through the normal path
