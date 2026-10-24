# Hametovbr Extensions

Персональный репозиторий расширений для Suwayomi / Mihon. Форк
[keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source), вычищенный
до собственных сборок: всё, кроме нужных для них модулей, удалено.

## Расширения

| Расширение | Пакет | Язык | Тема |
|---|---|---|---|
| HenChan | `eu.kanade.tachiyomi.extension.ru.henchan` | ru | `lib-multisrc/multichan` |

## Готовый репозиторий

Собранные APK и индекс публикуются в [hametovbr/extensions](https://github.com/hametovbr/extensions),
ветка `repo`. URL для Suwayomi / Mihon:

```
https://raw.githubusercontent.com/hametovbr/extensions/repo/index.pb
```

## Сборка

Нужны JDK 17+ (в CI — Temurin 25) и Android SDK с `platforms;android-37.0` и
`build-tools;37.0.0`; путь к SDK — в `local.properties` (`sdk.dir=…`).

```bash
export JAVA_HOME=~/.local/opt/jdk-25
export ANDROID_HOME=~/Android/Sdk

# только APK/jar расширения
./gradlew :src:ru:henchan:assembleRelease

# проверка перед коммитом
./gradlew :src:ru:henchan:lintRelease

# форматирование Kotlin и Gradle-скриптов
./gradlew spotlessApply
```

Готовые файлы: `src/ru/henchan/build/outputs/apk/release/*.apk` и
`src/ru/henchan/build/outputs/jar/release/*.jar`. Без `signingkey.jks` в корне сборка
подписывается debug-ключом — для локальной проверки этого достаточно, для публикации нет.

## CI

`.github/workflows/build_push.yml`: push в `main` → сборка с подписью → GitHub Release с
тегом `henchan-<версия>` → пересборка индекса в ветке `repo` репозитория `hametovbr/extensions`.

Секреты репозитория:

| Секрет | Что это |
|---|---|
| `SIGNING_KEY` | `signingkey.jks` в base64 (одна строка, без переносов) |
| `ALIAS` | алиас ключа в keystore |
| `KEY_STORE_PASSWORD` | пароль keystore |
| `KEY_PASSWORD` | пароль ключа |
| `PUBLISH_TOKEN` | PAT с правом push в `hametovbr/extensions` и созданием релизов |

## Структура

```
src/ru/henchan/          исходники расширения
lib-multisrc/multichan/  общая тема (парсинг движка multichan)
core/                    библиотека KeiSource и утилиты
compiler/                KSP-процессор, генерирующий метаданные источников
gradle/                  build-logic и версии зависимостей
```

## Лицензия

Apache License 2.0, как у исходного проекта.
