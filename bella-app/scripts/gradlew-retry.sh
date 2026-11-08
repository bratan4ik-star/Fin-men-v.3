#!/usr/bin/env bash
# Запускає ./gradlew з аргументами; якщо збій мережевий (завантаження дистрибутива Gradle чи залежностей:
# HTTP 5xx, тайм-аут, обрив з'єднання) — повторює до GRADLE_RETRIES разів (за замовчуванням 3) з паузою 30, 45, 60 с.
# Помилки компіляції й тестів НЕ повторюються: зелений результат має означати, що код пройшов тести.
set -uo pipefail

gradlew="${GRADLEW:-./gradlew}"            # для перевірки скрипта можна підставити заглушку
max_retries="${GRADLE_RETRIES:-3}"
base="${GRADLE_RETRY_PAUSE:-30}"
net='Server returned HTTP response code: 5[0-9][0-9]|Received status code 5[0-9][0-9]|status code 5[0-9][0-9]|Could not (GET|HEAD) |Read timed out|[Cc]onnect timed out|Connection reset|SocketTimeoutException|UnknownHostException|Remote host terminated the handshake|Premature EOF|SocketException|ZipException|Verification of Gradle distribution failed'
tests_failed='tests completed, [0-9]+ failed|There were failing tests|Compilation error|e: file://'

log="$(mktemp)"; trap 'rm -f "$log"' EXIT
attempt=0
while :; do
  "$gradlew" "$@" 2>&1 | tee "$log"
  code=${PIPESTATUS[0]}
  [ "$code" -eq 0 ] && exit 0
  if grep -Eq "$tests_failed" "$log" || ! grep -Eq "$net" "$log"; then
    exit "$code"                           # не мережа: справжня помилка збірки чи тестів
  fi
  if [ "$attempt" -ge "$max_retries" ]; then
    echo "::error::Gradle: мережевий збій і після $max_retries повторів"
    exit "$code"
  fi
  attempt=$((attempt + 1))
  pause=$((base + (attempt - 1) * base / 2))
  echo "::warning::Gradle: мережевий збій під час завантаження, повтор $attempt з $max_retries через ${pause} с"
  sleep "$pause"
done
