# Происхождение и авторские права

> **Уведомление об изменении (GNU GPL v3, пункт 5a).**
> Это **изменённая версия** программы [Nagram](https://github.com/NextAlone/Nagram),
> которая, в свою очередь, является форком Telegram для Android.
> Изменения внесены **aevornyx** в июле–августе 2026 года.
> Перечень изменений — ниже в этом файле.

**AxGram для Android** — форк с длинной родословной. Этот файл перечисляет всех,
чей код входит в сборку, и на каких условиях он используется.

Файл существует не для галочки. Лицензия GPL-3.0 прямо требует сохранять
уведомления об авторстве и помечать изменённые файлы. Удалять этот файл или
строки копирайта в исходниках нельзя — именно за это к форкам и предъявляют
претензии.

## Цепочка форков

| Проект | Автор | Роль |
|---|---|---|
| [Telegram для Android](https://github.com/DrKLO/Telegram) | Telegram Messenger LLP | первоисточник |
| [Nekogram](https://gitlab.com/Nekogram/Nekogram) | Nekogram | форк Telegram |
| [NekoX](https://github.com/NekoX-Dev/NekoX) | NekoX Dev | форк Nekogram |
| [Nagram](https://github.com/NextAlone/Nagram) | NextAlone | форк NekoX, прямой предок |

> Nagram отдельно оговаривает, что имя, логотипы и иконки проекта — это
> бренд-активы, на которые GPL прав не даёт, и требует, чтобы форки
> использовали собственный бренд
> ([BRANDING.md](https://github.com/NextAlone/Nagram/blob/main/BRANDING.md)).
> AxGram это требование выполняет: имя, иконки и айдентика собственные,
> заимствований бренд-активов Nagram в сборке нет.
| **AxGram** | aevornyx | этот репозиторий |

Отдельные куски позаимствованы из других форков и оставлены в своих пакетах
именно для того, чтобы их происхождение было видно:

- `moe.hx030.momogram` — Momogram;
- `top.qwq2333.nullgram` — Nullgram;
- `ws.vinta.pangu` — библиотека расстановки пробелов Pangu, к Telegram
  отношения не имеет.

## Лицензия

Проект распространяется по **GNU General Public License v3.0** — той же, под
которой опубликован Telegram для Android. Полный текст в файле `LICENSE`.

Из этого следует:

- исходный код AxGram обязан оставаться открытым;
- любой может форкнуть его на тех же условиях;
- уведомления об авторстве предыдущих участников должны сохраняться;
- изменённые файлы должны быть помечены как изменённые.

## Что изменено в AxGram

### Убрана телеметрия

- **Sentry** — плагин сборки, SDK и рабочий DSN на `o416616.ingest.us.sentry.io`.
  Отправлялись падения, ANR-дампы стека потоков и скриншоты экрана в момент
  сбоя. Удалены зависимость, плагин, блок настроек и тумблер в интерфейсе;
  класс `AnalyticsHelper` оставлен заглушкой, чтобы вызывающий код не менялся.
- **Firebase Crashlytics** — удалены зависимость и gradle-плагин.
- **Реклама исходного проекта** — при открытии списка чатов приложение
  резолвило `@nagram_channel` и `@NagramTips` и предлагало подписаться. Убрано.

Проверено в готовом APK: строк `io/sentry`, `sentry`, `crashlytics` — ноль.

### Убраны обращения к инфраструктуре исходного проекта

- **Удалённая конфигурация.** При каждом запуске приложение резолвило
  телеграм-канал `@nagram_remote_metadata` и тянуло оттуда данные: наборы
  эмодзи, обои, цвета собеседников, метаданные обновлений, правила инлайн-ботов.
  Это и обращение наружу, и чужой контроль над тем, что подгружает приложение.
  Загрузка отключена в `BaseRemoteHelper`, всё работает на встроенных ресурсах.
- **Чужой ключ Google Maps.** В манифесте был зашит ключ исходного проекта
  (их собственный комментарий это и подтверждал): расход шёл по чужой квоте,
  отзыв ключа сломал бы карты, а Google блокирует за использование чужого.
  Вынесен в `local.properties` (`MAPS_V2_API`), в репозиторий не попадает.
  Поскольку без своего ключа Google-карты остаются пустыми, встроенный движок
  OpenStreetMap (MapLibre) включён по умолчанию — ему ключ не нужен. Настройка
  переключается в «Основные».
- **Ссылки на исходный проект** в настройках, профиле и «о программе»
  (`github.com/NextAlone/Nagram`, `NekoX-Dev/NekoX`, `xtaolabs.crowdin.com`,
  каналы `@nagram_channel`, `@NagramTips`) переведены на свой репозиторий.
- **CI-скрипт** больше не публикует сборки в телеграм-каналы исходного проекта:
  идентификаторы каналов берутся из переменных окружения.

### Найдено при финальной проверке

- **Подделка верификации.** `TLRPC.verifiedExtended()` возвращал галочку
  «проверенный аккаунт» для захардкоженного списка: 10 аккаунтов разработчиков
  и 7 каналов исходных проектов, причём проверка `isDeveloper()` была зашита
  в `true`. Списки очищены — работает только настоящая верификация Telegram.
- **Настройки уходили чужому боту.** «Облачные настройки» выгружали конфиг
  боту исходного проекта. Классы удалены вместе с точками входа.
- **Ссылка на чужой канал.** Строка профиля, подписанная «Канал обновлений
  AxGram», открывала канал NekogramX. Переведена на свой канал.
- **Название темы.** Встроенная тема отображалась как «NekoX». Ключ темы
  оставлен (по нему тема сохранена у пользователей), исправлено только
  отображаемое имя.
- **Схема ссылок** `t.me/nasettings` переименована в `t.me/axsettings`.
- **CI:** отладочная публикация уходила в тестовый канал NekoX; шаблон отчёта
  об ошибке вёл в чат NekoX под видом своей ссылки. Исправлено.

### Что осознанно оставлено без изменений

Имена, по которым хранятся данные пользователя. Их переименование стёрло бы
настройки и содержимое:

- файлы настроек `nekox_config`, `nekocloud`, `nekopasscode`,
  `nekodialogconfig`, `nekorecentdialogs`;
- расширения файлов экспорта `.nekox-settings.json`, `.nekox-stickers.json` —
  их смена сломала бы уже сохранённые пользователем выгрузки;
- идентификатор сохранённой темы и сравнения для миграции старых заголовков.

Комментарии вида `// Nagram:` и `// NekoX:`, которыми авторы отмечали свои
правки, тоже оставлены: переписать их на `// AxGram:` значило бы приписать
чужую работу себе.

### Переименование

- пакет приложения → `one.axgram.android`;
- `tw.nekomimi.nekogram` → `one.axgram.core`;
- `xyz.nextalone.nagram` → `one.axgram.extra`;
- классы `Neko*`, `NaConfig` и прочие → `Ax*`;
- имена строковых ресурсов, идентификатор канала уведомлений, теги логов.

Синхронно переименованы функции JNI (`Java_one_axgram_extra_helper_*`):
без этого приложение падало бы с `UnsatisfiedLinkError`.

**Осознанно не переименовано** — идентификаторы, которые хранятся на устройстве
пользователя, и их смена стёрла бы его данные:

- `"NekoX"` в `Theme.java` — идентификатор сохранённой темы;
- сравнения в `DialogsActivity` — миграция старых пользовательских заголовков;
- ключ настройки `NekoConfigMigrate`.

Слово `telegram` остаётся во внутренних именах (`org.telegram.messenger`,
схема TL, поля протокола MTProto). Это техническая необходимость: клиент
работает по протоколу Telegram, переименование ломает и сборку, и подключение.

### Прочее

- Проверка подписи APK (`jni/integrity/integrity.cpp`) обойдена: исходный форк
  сверяет SHA-1 подписи со своим сертификатом и падает при запуске, если сборка
  подписана другим ключом. Для собственной сборки со своим ключом это
  необходимо.
- Исправлены дефекты сохранения удалённых сообщений: одноразовые сообщения
  теперь перехватываются для всех чатов, а не только для открытого; очистка
  истории собеседником больше не стирает переписку; поправлено отображение
  выделения и приглушения удалённых сообщений.

## Товарные знаки

Telegram — товарный знак Telegram Messenger LLP. AxGram не связан с Telegram
Messenger LLP и не является официальным клиентом.

## Ключи и подпись

Сборка требует собственные `TELEGRAM_APP_ID` и `TELEGRAM_APP_HASH`
с [core.telegram.org](https://core.telegram.org/api/obtaining_api_id).
Они задаются в `local.properties`, который **не попадает в репозиторий**.

Ключ подписи (`*.keystore`, `*.jks`) в репозитории тоже отсутствует — им
подписываются обновления, и его утечка позволила бы подсунуть пользователям
чужую сборку под видом обновления.


## Изменение от 27 октября 2026: аккаунты

В AxGram снята зависимость дополнительных аккаунтов от Premium. Изменены UserConfig.java, UserInfoActivity.java, MainTabsActivity.java, ProfileActivity.java, LogoutActivity.java и Components/Premium/DoubledLimitsBottomSheet.java; добавлены one/axgram/core/AxAccountSlots.java и AxAccountLimitAlert.java. Технический предел Java/JNI 128 сохранён.

## Изменение от 28 октября 2026: оформление

Добавлены темы AxGram Glass Light / Dark, системное переключение, возврат прежнего оформления и отдельный раздел настроек. Изменены Theme.java, LaunchActivity.java, SettingsActivity.java, AxSettingsActivity.java, MainTabsActivity.java, MainTabsLayout.java и Components/glass/GlassTabView.java; добавлены one/axgram/core/appearance/ и AxAppearanceSettingsActivity.java. Шрифт Onest распространяется с приложенной лицензией SIL Open Font License в assets/fonts/axgram_onest-OFL.txt.

В версии 1252 добавлены акцентный цвет, независимые настройки размытия обоев и панелей, прозрачность стекла; исправлены цвета индикаторов файлов и синхронизация панели с пейджером. Изменены SharedConfig.java, ChatActivity.java, Components/SizeNotifierFrameLayout.java и классы Components/blur3/drawable/; добавлены AxAppearanceColors.java и AxWallpaperEffects.java.

AxGram appearance hotfix1253: material toggle updates live views without theme recreation; native blur backend stays stable; scoped material drawables refresh on draw. Modified AxAppearance, AxAppearanceSettingsActivity, SharedConfig, BlurredBackgroundDrawable, BlurredBackgroundDrawableRenderNode and version metadata.

AxGram hotfix1254: AxAppearanceSettingsActivity keeps live switch for native animation, synchronizes percentage enabled state immediately and on bind, guards disabled clicks; regression test in AxAppearanceThemeUpdateTest; version metadata updated.

AxGram chat-list appearance1255: added AxChatListStyle, AxChatListSurface, AxChatListBackdrop and sampled approved wallpaper assets; modified DialogsActivity, DialogCell divider rendering, FilterTabsView drawing order/material, FragmentSearchField, Theme dialog typography and AxAppearanceColors tab contrast. Existing list/search/gesture/folder handlers retained. Added AxChatListStyleTest; version metadata updated.

AxGram1256: segmented Glass folder geometry and painter (AxFolderTabsStyle/FilterTabsView), regular Onest labels and neutral selected text; live animated chat-list backdrop color binding and palette-coupled wallpaper blend; Glass quick day/night pairing; independent chat-list wallpaper visibility in AxAppearance/settings/RU+EN resources. Added AxFolderThemeTest; updated version metadata.

AxGram1257: compact folder viewport slots (86 percent for more than three folders), 36dp panel body and 30dp selection; native 52dp touch container retained. Modified AxFolderTabsStyle, FilterTabsView, AxFolderThemeTest and version metadata.

AxGram1258: bottom navigation retains approved geometry and animation; shared AxFolderTabsStyle.panelColor tint and matching top-panel native backing in Glass themes. Modified AxGlassNavigationDrawable, BlurredBackgroundProviderImpl, shared folder material helper and version metadata. Legacy backing retained.

AxGram1259: unified Glass chat header drawing in ActionBar/ChatActivity (standard controls retained), shared folder-derived composer/header material with native blur fallback and appearance controls, Onest chat-header typography in ChatAvatarContainer, and AxChatHeaderTest rendering/theme-fallback regression coverage. Updated version metadata.

AxGram1260: owner rejected merged chat header1259; original separate ActionBar surfaces, header typography and header material restored from1258. Removed the unified-header API, integration and associated experimental rendering tests. Composer material controls remain. Updated version metadata.

AxGram1261: common Glass accent/contrast mapping for settings controls, selection checks, dialog actions and full audio player surfaces in AxAppearanceColors; direct native Telegram wallpaper picker from AxAppearanceSettingsActivity. Added cross-surface contrast coverage in AxAppearanceColorsTest; updated version metadata. Existing chat-header geometry remains unchanged.

AxGram1262: common value-text accent binding in AxAppearanceSettingsActivity and shared full/mini-player palette in AxAppearanceColors; extended cross-surface regression coverage. Updated version metadata.

AxGram1263: ThemeDescription supports AnimatedTextView field text colors for live palette updates; regression coverage keeps an existing animated settings value attached while cycling theme colors. Default blue interactive values retained with readable contrast; custom accent overrides remain available. Updated version metadata.

AxGram1264: optional Lucide ISC static icon family via AxIconPack (110 mapped resources,79 local vectors; original dimensions/state/tint and live stock toggle retained). Shared TextCell/ManageChatTextCell/menu/themed-drawable/profile/settings bindings updated; main settings grouped into Appearance, AxGram features and Telegram settings with existing IDs/routes retained. RU/EN labels and regression/asset coverage added; version metadata updated. License in assets/axgram/icons/LICENSE-lucide.txt.
# AxGram appearance update 1265 (2026-10-29)

Expanded the optional Lucide-based UI icon family to account editing, profile action buttons and secondary settings symbols. Added native glass emblems with theme-aware colours for settings entries and profile actions. Stock icon fallback, routes and account data formats are retained. Lucide attribution and ISC terms are in `TMessagesProj/src/main/assets/axgram/icons/LICENSE-lucide.txt`.


## AxGram first-run appearance, 1266 (2026-10-30)

IntroActivity now renders a native AxGram welcome and shared appearance selection. The original introductory class is derived from Telegram Android (Copyright Nikolai Kudashov, 2013-2018; GNU GPL version 2 or later). Its API integration is retained; Telegram authentication, registration and recovery remain in LoginActivity. Added native artwork, startup resources and shared login backdrop/label typography. Account storage and authentication protocol were not changed.
### AxGram Android 1267 — 2026-10-30

AxGram modified the native welcome theme toggle to use its current mode, avoided
rebuilding welcome controls on every theme animation frame, extended the optional
icon family to Telegram Stars and GRAM, and added fresh-profile chat defaults
(text size 16, message corner radius 17). Existing chat preferences are preserved.
Relevant changes are in AxWelcomeView, IntroActivity, AxAppearance, AxIconPack,
AxChatDefaults and their regression tests. Original authorship and licensing remain.


## AxGram login navigation, 1268 (2026-10-31)

LoginActivity now exposes back navigation throughout ordinary authentication pages, returns primary phone entry to IntroActivity appearance selection, and uses a 48dp back-button touch target. Account addition and management keep existing cancellation routes; Telegram mandatory email prompts retain their own restrictions. Authentication requests and registration logic are unchanged.
