# Agent instructions

Репозиторий — персональный форк `keiyoushi/extensions-source` с одним расширением
(`src/ru/henchan`) и одной общей темой (`lib-multisrc/multichan`). Апстримная документация
(`CONTRIBUTING.md`) удалена; правила ниже — то, что из неё остаётся обязательным.

## Правила кода расширений

- **Источники наследуются от `KeiSource`** (`libVersion = "1.6"`), не от `HttpSource` напрямую —
  иначе сборка падает.
- **Метаданные внедряются через KSP:** не объявляй `name`, `lang`, `id`, `baseUrl` вручную в
  `@Source`-классе — они задаются блоками `source {}` в `build.gradle.kts`.
- **`SourceFactory` устарел:** несколько источников — это несколько блоков `source {}`, а не фабрика.
- **Сгенерированные настройки:** для зеркал и кастомных URL используй `baseUrl { mirrors(...) }` /
  `baseUrl { custom(...) }` в `source {}`, а не `SharedPreferences`.
- **Deeplink — через DSL:** блок `deeplink {}` в `build.gradle.kts`; `AndroidManifest.xml` руками не
  правится.
- **DTO без `data class`:** для `@Serializable` JSON/Protobuf — обычный `class`, поля `private`,
  `@SerialName` только при несовпадении ключа.
- **Без локальных `Json`/`Proto`:** используй `response.parseAs<T>()`, `toJsonRequestBody()`,
  `parseAsProto<T>()` из `keiyoushi.utils`.
- Не пиши очевидных комментариев; комментируй только неочевидное «почему» (обход бага сайта,
  тонкий инвариант).
- Не изобретай абстракции «на будущее» и не добавляй обработку сценариев, которых не бывает.

## Границы изменений

Обычная правка источника — файлы под `src/ru/henchan/` и, при необходимости, тема
`lib-multisrc/multichan/`. `core/`, `compiler/`, `common/`, `gradle/`, `.github/` — только по
явному запросу: это инфраструктура сборки и публикации.

## Перед коммитом

```bash
./gradlew :src:ru:henchan:lintRelease
./gradlew spotlessApply   # форматирование Kotlin и Gradle-скриптов
```

Линт темы, если менялась она: `./gradlew :lib-multisrc:multichan:lintRelease`.
Линт только расширения не сообщает о проблемах в зависимой теме.
