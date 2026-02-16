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

