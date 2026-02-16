#!/bin/sh
set -eu
RES_FILE="${1:-./src/main/resources/application.yml}"
TARGET_FILE="${2:-./target/classes/application.yml}"

update_file() {
  f="$1"
  if [ ! -f "$f" ]; then
    echo "Skip $f (not found)"
    return 0
  fi
  # Обновляем только ключи в блоке spring.datasource.*, не трогая другие username/password
  sed -E -i '/^[[:space:]]*datasource:[[:space:]]*$/,/^[^[:space:]]/ s|(^[[:space:]]*username:[[:space:]]*).*$|\1app_user_a|' "$f"
  sed -E -i '/^[[:space:]]*datasource:[[:space:]]*$/,/^[^[:space:]]/ s|(^[[:space:]]*password:[[:space:]]*).*$|\1app_pass_a|' "$f"
  echo "Updated credentials in $f -> app_user_a"
}

update_file "$RES_FILE"
update_file "$TARGET_FILE"

