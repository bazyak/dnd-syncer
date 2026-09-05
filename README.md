# DND syncer

**Русский** · [English](README.en.md)

Синхронизация режимов между Pixel 10 Pro (Android 17, root) и OnePlus Watch 4
(Wear OS 6 / Android 16). Настроек нет — только приветственный экран со
статусом доступов.

## Что синхронизируется

| Событие | Результат |
|---|---|
| «Не беспокоить» на телефоне (вручную, плиткой, по расписанию) | то же на часах |
| «Не беспокоить» на часах | то же на телефоне |
| Театральный режим на часах | «Не беспокоить» на телефоне |
| Выключение DND на телефоне | на часах гаснет всё, включая театр |
| Ночной режим на часах | ночной режим (Digital Wellbeing) на телефоне |
| Ночной режим на телефоне | ночной режим на часах |

Театр включается только вручную на часах: программно поднять его нельзя, да и
на телефоне аналога нет.

## Модель состояния

Между устройствами ходит **снимок** из двух независимых признаков, а не дельта:

```
ModeState(dnd: Boolean, night: Boolean)
```

Приёмник сравнивает снимок со своим текущим и молчит при совпадении — за счёт
этого цикл обрывается сам, отдельная защита от эха не нужна.

### На часах

`zen_mode` — это OR всех источников тишины, сам по себе он источник не
называет. Поэтому снимок собирается из трёх ключей `Settings.Global`:

```
dnd   = theater_mode_on==1 || (zen_mode!=0 && bedtime_mode==0)
night = bedtime_mode==1
```

| bedtime, zen, theater | что это | dnd | night |
|---|---|---|---|
| 0,0,0 | ничего | false | false |
| 0,1,0 | ручной DND | true | false |
| 0,1,1 | театр (± ручной DND) | true | false |
| 1,1,0 | только ночь | false | true |
| 1,1,1 | ночь + театр | true | true |

Строки 3 и 4 неразличимы намеренно: поведение в них одинаковое. `bedtime==0` во
второй скобке работает потому, что **на часах** ночь и ручной DND
взаимоисключающие — включение одного гасит другое.

### На телефоне

Там ночь и DND **сосуществуют**, а `zen_mode` у них общий, поэтому состояние
читается из `dumpsys notification`:

- ночь — `AutomaticZenRule` с `type=3` (`TYPE_BEDTIME`) в состоянии `STATE_TRUE`;
- DND — `MANUAL_RULE` в состоянии `STATE_TRUE`.

Триггером служит `ContentObserver` на `zen_mode`, `theater_mode_on`,
`bedtime_mode` и `zen_mode_config_etag`. Последний обязателен: если DND уже
включён и сверху включается ночь, `zen_mode` не меняется, а etag меняется при
любой правке zen-конфига.

Публикация задержана на 700 мс — смена режима трогает несколько ключей подряд,
и без задержки уезжает промежуточное состояние.

## Почему всё делается через shell

`NotificationManager.setInterruptionFilter()` не подходит:

- он создаёт zen-правило с `enabler` = имя нашего пакета, а приложение может
  гасить только собственные правила. Ручной DND (`enabler=android`) для него
  неприкосновенен — именно поэтому выключение с телефона не работало;
- на OnePlus Watch 4 он вообще не даёт эффекта. Проверено:

```
adb shell cmd notification set_dnd priority
adb shell dumpsys notification | grep mZenMode
→ mZenMode=ZEN_MODE_IMPORTANT_INTERRUPTIONS   (shell-команда)
→ mZenMode=ZEN_MODE_OFF                        (setInterruptionFilter)
```

Прямая запись `zen_mode` через `WRITE_SECURE_SETTINGS` тоже отпадает: значение
ложится, но система его игнорирует.

Работает единственный способ — `cmd notification set_dnd priority|off`, а его
пускает только uid shell (2000) или root. На телефоне это root, на часах
Shizuku.

## Ночной режим на телефоне

Правило принадлежит Digital Wellbeing
(`pkg=com.google.android.apps.wellbeing`, `conditionId=.../winddown`), а
владеть правилами `TYPE_BEDTIME` по документации может только оно — своё
завести нельзя.

`setAutomaticZenRuleState()` из процесса приложения проходит без ошибки, но
система его молча игнорирует: проверка смотрит на uid вызывающего. Поэтому тот
же вызов запускается отдельным процессом от имени системы:

```
su 1000 -c "CLASSPATH=<apk> app_process / com.bazyak.dndsyncer.phone.NightHelper <id> <uri> on"
```

Отдельный dex не нужен — APK приложения уже лежит на устройстве и годится как
CLASSPATH. `NightHelper` через рефлексию берёт `INotificationManager` из
`ServiceManager` и зовёт `setAutomaticZenRuleState` с
`Condition.SOURCE_USER_ACTION`.

Пробовались и отброшены: широковещательные интенты Wellbeing
(`TURN_OFF_WIND_DOWN`, `WIND_DOWN_ALARM_TRIGGERED`, `PAUSE`/`RESUME`) —
доставляются, но эффекта не дают.

## Разрешения

В манифестах объявлено только `WRITE_SECURE_SETTINGS` на часах. Всё остальное —
внешние доступы.

**Специальные возможности** (оба устройства) — выдаётся кнопкой в приложении.
События не обрабатываются, содержимое экрана не читается: сервис нужен как
живой процесс, чтобы `ContentObserver` работал постоянно. Это заменило
`NotificationListenerService`, который требовал доступа к чтению уведомлений.

**Телефон:** root. Диалог Magisk появится при первом запуске.

**Часы:** Shizuku и `WRITE_SECURE_SETTINGS`.

```
adb shell pm grant com.bazyak.dndsyncer android.permission.WRITE_SECURE_SETTINGS
```

## Установка

```
./gradlew :mobile:assembleDebug :wear:assembleDebug
adb -s <phone> install -r mobile/build/outputs/apk/debug/mobile-debug.apk
adb -s <watch> install -r wear/build/outputs/apk/debug/wear-debug.apk
```

Оба APK — один `applicationId` и один ключ подписи, иначе Data Layer не свяжет
их в одно приложение.

### Shizuku на часах

```
adb install shizuku.apk
adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
```

Разрешение приложению выдаётся кнопкой на экране часов; если диалог не влезает
в круглый экран:

```
adb shell pm grant com.bazyak.dndsyncer moe.shizuku.manager.permission.API_V23
```

Чтобы Shizuku поднимался сам после перезагрузки, выдай ему
`WRITE_SECURE_SETTINGS` и включи «Start on boot» — он сам поднимет беспроводную
отладку на старте:

```
adb shell pm grant moe.shizuku.privileged.api android.permission.WRITE_SECURE_SETTINGS
```

## Автозапуск Shizuku на часах

Wear OS не поднимает Wi-Fi, пока часы связаны с телефоном по Bluetooth, а
Shizuku при загрузке пробует включить беспроводную отладку ровно один раз —
сети в этот момент нет, и повторять он не умеет. Поэтому цепочку приложение
проходит само:

1. `Settings.Global["wifi_on"] = 1` и ожидание сети;
2. `Settings.Global["adb_wifi_enabled"] = 1`;
3. официальный intent автозапуска Shizuku:
   `moe.shizuku.privileged.api.START` с extra `auth`.

Запускается в двух случаях: по первой же команде с телефона, если Shizuku не
отвечает, и раз в сутки около 4 утра, когда часы почти наверняка на зарядке
рядом с домашним Wi-Fi. Если сети нет, на часы приходит уведомление с просьбой
подойти ближе и повторить.

**Токен обязателен.** Смотреть в самом Shizuku: «Управляйте Shizuku с помощью
приложений автоматизации» → «Просмотр намерений» → Extras → `auth`. Прописать
в `gradle.properties`:

```
shizukuAuth=<токен>
```

Если нажать в Shizuku кнопку обновления рядом с токеном, старый перестанет
работать и сборку надо повторить.

## Версии и имена APK

Номер версии лежит в `version.properties` в корне и увеличивается сам при
каждой сборке (`assemble`, `install`, `bundle`); на sync и clean не меняется.
Считается один раз в корневом `build.gradle.kts` и раздаётся обоим модулям —
иначе телефон и часы разъехались бы в номерах, а Data Layer требует
одинаковых версий у пары APK.

Готовые файлы называются так:

```
DND syncer - 1.0.7.apk
DND syncer (wear) - 1.0.7.apk
```

`major` и `minor` правятся руками в том же файле.

Конфигурационный кэш Gradle отключён намеренно: при нём фаза конфигурации
переиспользуется и автоинкремент перестаёт срабатывать.

## Нюансы

- **Power Saver** на Watch 4 переключает часы на BES2800, Wear OS не работает —
  синхронизация встаёт и досогласуется при возврате в Smart mode.
- **Театр не выключить с телефона поодиночке**: при `dnd=false` снимается
  сначала `theater_mode_on`, иначе театр удержит `zen_mode`.
- **Ночь по расписанию** Wellbeing гасит сам, и это уезжает на часы обычным
  путём.
- Никаких пуллингов и таймеров: только колбэки системы и `ContentObserver`.
