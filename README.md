## HikariCP + Postgres: прототип динамической ротации пароля (Java 17, Spring Boot 3)

### Цель
Построить минимальный, но реалистичный прототип, демонстрирующий, что пул соединений HikariCP в Spring Boot 3-приложении может «на лету» переключаться на новые логин/пароль PostgreSQL без рестарта приложения; смена выполняется по REST‑вызову служебного эндпоинта, который инициирует «горячую» подмену пула.

### Ключевая идея
- В БД заранее созданы два пользователя приложения с разными паролями.
- Смена логина/пароля выполняется по REST‑вызову: приложение принимает новые значения стандартных ключей `spring.datasource.username/password` и через HikariConfigMXBean обновляет креденшелы работающего пула; при необходимости через HikariPoolMXBean выполняется `softEvictConnections`, чтобы новые соединения создавались уже с обновлёнными данными. Пересоздания `HikariDataSource` не требуется.

В прототипе используется API пула: `HikariConfigMXBean` для изменения `username/password` на лету и `HikariPoolMXBean.softEvictConnections()` для мягкого перевыпуска соединений — без пересоздания пула.

---

## Архитектура
- **Приложение**: Spring Boot 3 (Java 17), запускается ЛОКАЛЬНО из IDE (не в Docker).
- **Пул**: HikariCP.
- **База**: PostgreSQL в Docker Compose.
- **Смена параметров**: служебный REST‑эндпоинт, который принимает новые `spring.datasource.*` и обновляет действующий пул через `HikariConfigMXBean` (+ опционально `HikariPoolMXBean.softEvictConnections`).

Схема:
1) Docker Compose поднимает `postgres`.
2) Приложение подключается к БД с пользователем `app_user_a` и паролем `app_pass_a`.
3) Вызываем служебный REST‑эндпоинт для установки пользователя `app_user_b/app_pass_b`.
4) Приложение обновляет креденшелы в пуле через `HikariConfigMXBean` и выполняет мягкую эвикцию соединений; новые соединения создаются от имени `app_user_b`.

---

## Инфраструктура (Docker Compose)
Приложение НЕ запускается в Docker. Docker Compose поднимает только инфраструктуру БД.

План файлов:
```
docker/
  db/
    init/
      01-app-user.sql
docker-compose.yml
```

Будущий `docker-compose.yml`:

```yaml
version: "3.9"
services:
  postgres:
    image: postgres:16-alpine
    container_name: pg-rotation-demo
    environment:
      POSTGRES_DB: app
      POSTGRES_USER: admin
      POSTGRES_PASSWORD: adminpw
    ports:
      - "5432:5432"
    volumes:
      - ./docker/db/init:/docker-entrypoint-initdb.d:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U admin -d app"]
      interval: 5s
      timeout: 3s
      retries: 10
    restart: unless-stopped
```

Инициализация пользователя приложения (будущий `docker/db/init/01-app-user.sql`):

```sql
-- База данных
CREATE DATABASE appdb;

-- Два пользователя приложения с разными паролями
CREATE USER app_user_a WITH PASSWORD 'app_pass_a';
CREATE USER app_user_b WITH PASSWORD 'app_pass_b';

-- Права на подключение и создание объектов (упрощённо для прототипа)
GRANT ALL PRIVILEGES ON DATABASE appdb TO app_user_a;
GRANT ALL PRIVILEGES ON DATABASE appdb TO app_user_b;

-- Настройки схемы и базовые объекты
ALTER DATABASE appdb SET search_path TO public;
-- Тестовая таблица (необязательно)
-- CREATE TABLE IF NOT EXISTS public.healthcheck(id int primary key, note text);
```

Запуск инфраструктуры:
```bash
docker compose up -d
docker compose ps
```

---

## Приложение (локально из IDE)
Требования:
- JDK 17
- Maven

Базовые настройки приложения (пример `application.yml`):

```yaml
spring:
  application:
    name: hikari-password-rotation
  datasource:
    url: jdbc:postgresql://localhost:5432/appdb
    username: app_user_a
    password: app_pass_a
    driver-class-name: org.postgresql.Driver
    hikari:
      max-lifetime: 60000  # 60 секунд, после чего соединения будут переоткрываться

server:
  port: 8080
```

---

## Скрипты переключения креденшелов (для наблюдаемой ротации)
Добавим простые скрипты, которые вызывают REST‑админку приложения, передают новые стандартные ключи `spring.datasource.username/password` и тем самым сразу триггерят «горячую» подмену пула без периодического опроса.

План файлов:
```
scripts/
  switch-to-user-a.sh
  switch-to-user-b.sh
```

Содержимое `scripts/switch-to-user-a.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
BASE_URL="${1:-http://localhost:8080}"
curl -sS -X POST "$BASE_URL/internal/config/override" \
  -H "Content-Type: application/json" \
  -d '{"spring.datasource.username":"app_user_a","spring.datasource.password":"app_pass_a"}'
echo
echo "Switched spring.datasource.* to app_user_a"
```

Содержимое `scripts/switch-to-user-b.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
BASE_URL="${1:-http://localhost:8080}"
curl -sS -X POST "$BASE_URL/internal/config/override" \
  -H "Content-Type: application/json" \
  -d '{"spring.datasource.username":"app_user_b","spring.datasource.password":"app_pass_b"}'
echo
echo "Switched spring.datasource.* to app_user_b"
```

Эти скрипты обращаются к служебному REST‑эндпоинту приложения, который обновляет значения в динамическом `PropertySource` внутри `ConfigurableEnvironment`, применяет их к пулу через `HikariConfigMXBean` и при необходимости вызывает `softEvictConnections`. Приложение использует только стандартные ключи `spring.datasource.*`.

В логах приложения при подмене пула должны быть сообщения вида:
- "Detected credentials change: from app_user_a to app_user_b"
- "Created new pool for username=app_user_b, validated connection ok"
- "Swapped active DataSource, old pool scheduled for soft-evict"
- Для наглядности будет реализован REST `/db/whoami`, который выполняет `select current_user` и пишет в лог пользователя, с которым выполняется запрос.

---

## Механизм обновления пула без пересоздания
- По REST‑вызову с новыми `spring.datasource.username/password` приложение:
  - обновляет значения через `HikariConfigMXBean` действующего пула;
  - выполняет проверку `SELECT 1` на новом соединении;
  - вызывает `HikariPoolMXBean.softEvictConnections()` для мягкой эвикции существующих коннекшенов, чтобы новые создавались уже с обновлёнными креденшелами;
  - активные запросы завершаются на старых соединениях, новые — идут с новыми логином/паролем.

Почему не менять пароль «внутри» пула:
- Hikari не поддерживает безопасного live-изменения `username/password` через конфиг; корректно — пересоздать пул и подменить.

---

## Сценарий проверки ротации пароля
1) Поднять инфраструктуру:
   ```bash
   docker compose up -d
   ```
2) Запустить приложение из IDE (профиль по умолчанию).
3) Убедиться, что `/db/ping` отвечает 200 OK и выполняется простой запрос `SELECT 1`. Вызов `/db/whoami` возвращает `app_user_a`.
4) Выполнить переключение на пользователя B:
   ```bash
   bash ./scripts/switch-to-user-b.sh
   ```
5) Подождать обновления соединений: сразу после вызова эндпоинта новые коннекшены создаются с новыми креденшелами (при soft-evict), для полного обновления всех коннекшенов можно также дождаться > `max-lifetime` (~60–90 секунд).
6) Проверить, что:
   - `/db/ping` отвечает 200 OK;
   - `/db/whoami` возвращает `app_user_b`;
   - в логах видно, что новые соединения создаются с `username=app_user_b`.
7) Переключиться обратно на пользователя A:
   ```bash
   bash ./scripts/switch-to-user-a.sh
   ```
   Дождаться >60 секунд и убедиться, что `/db/whoami` снова показывает `app_user_a`, а в логах отмечена новая подмена пула.

Отказоустойчивость:
- Если указать неверный пароль — подмена не выполняется (валидация перед swap); текущий пул остаётся рабочим.

---

## Безопасность
- В DEV можно держать файл креденшелов локально в проекте; в PROD используйте секрет-хранилище/файлы с ограниченными правами.
- Рекомендуется интеграция с внешним секрет-хранилищем и контроль доступа.

---

## Альтернативы и интеграции
- Spring Cloud `@RefreshScope` + внешнее секрет-хранилище (Vault/Consul/Config Server) — можно добавить для автоматического обновления пропертей без собственного планировщика.
- Использование `HikariPoolMXBean` — полезно для метрик/эвикции, но не решает смену пароля.

---

## Дальнейшие шаги реализации
1) Подготовить `docker-compose.yml` и `01-app-user.sql`.
2) Создать каркас Spring Boot 3 (Java 17) с Maven.
3) Добавить `HotSwappableDataSource` и служебный REST для мгновенной смены `spring.datasource.username/password` с немедленной «горячей» подменой пула.
4) Добавить эндпоинт `/db/ping` для проверки доступности БД.
5) Повторить сценарий ротации и задокументировать результаты.

---

## Версии и требования
- Java 17
- Spring Boot 3.x
- PostgreSQL 16 (alpine)
- HikariCP (входит в Spring Boot Starter JDBC)
- Docker + Docker Compose

---

## Быстрый старт (кратко)
1) Поднимите БД:
   ```bash
   docker compose up -d
   ```
2) Запустите приложение из IDE.
3) Проверьте `/db/ping` и `/db/whoami` (должно быть `app_user_a`).
4) Выполните `bash ./scripts/switch-to-user-b.sh`, подождите >60 секунд и убедитесь, что `/db/whoami` показывает `app_user_b`.

