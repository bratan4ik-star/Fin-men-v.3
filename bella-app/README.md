# Bella Finance (Android, Kotlin + Jetpack Compose)

Нативна реалізація прототипу `Bella App.dc.html` з handoff-пакета Claude Design.

Екрани: Головна, Капітал та Рахунки (+борги, переказ), Бюджет (Огляд / Операції / Звіт / Ліміти), Інвестиції, Цілі, Пасивний дохід, Калькулятор ОВДП.
Шити: нова операція / рахунок / борг / позиція, редагування, налаштування, довідка, імпорт виписки (.xlsx і CSV), безпека (PIN + біометрія, автоблокування).
Також: світла/темна тема, перемикач валюти, «приховати суми», скасування останньої зміни (до 20 кроків), збереження на пристрої.

Розумна аналітика (вкладка «Огляд» у «Бюджеті»): запас міцності, прогноз витрат, поради й топ-5 витрат. Курси НБУ (екран «Капітал та Рахунки»): живі курси USD, EUR, PLN з датою, ручне оновлення, автооновлення раз на 6 годин і конвертер. Для цього додано дозвіл `INTERNET`. Логіка винесена в `Analytics.kt` і `Rates.kt`, перевіряється юніт-тестами `AnalyticsTest` і `RatesTest`.

## Збірка
Потрібні JDK 17 і Android SDK 34 (шлях до SDK: `ANDROID_HOME` або `sdk.dir` у `local.properties`). Відкрийте папку в Android Studio або з терміналу:

```
./gradlew :app:testDebugUnitTest   # юніт-тести
./gradlew :app:assembleDebug       # debug APK  -> app/build/outputs/apk/debug/
./gradlew :app:assembleRelease     # release APK -> app/build/outputs/apk/release/
```

### Підпис release
Без налаштувань `assembleRelease` дає непідписаний `app-release-unsigned.apk`. Щоб Gradle підписував сам:

1. Створіть ключ (один раз; **збережіть файл і паролі**, без них не оновити застосунок поверх встановленого):
   `keytool -genkeypair -keystore bella-release.jks -alias bella -keyalg RSA -keysize 2048 -validity 10000`
2. Скопіюйте `keystore.properties.example` у `keystore.properties` і заповніть (файл, `*.jks` та `*.keystore` ігноруються git). Або задайте змінні середовища `BELLA_KEYSTORE`, `BELLA_STORE_PASSWORD`, `BELLA_KEY_ALIAS`, `BELLA_KEY_PASSWORD` (зручно для CI).
3. `./gradlew :app:assembleRelease` дасть підписаний APK.

## Відхилення від прототипу
- Phosphor-іконки замінено на Material Icons (extended); шрифт Inter не вбудовано, використовується системний.
- Поля дат: текст `РРРР-ММ-ДД` замість `<input type=date>`.
- Відбиток пальця працює через системний `BiometricPrompt` (у прототипі імітувався).
- Курси валют, «сьогодні» (2026-11-02) і sparkline на головній закладені як у прототипі (статичні).
- Особисті дані карток (`ov` у прототипі) у код не перенесено; оверрайди рахунків накопичуються при редагуванні.
