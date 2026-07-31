CREATE TABLE IF NOT EXISTS users (
    login TEXT PRIMARY KEY,
    password TEXT,
    name TEXT,
    email TEXT UNIQUE
);
CREATE TABLE IF NOT EXISTS workouts (
    id SERIAL PRIMARY KEY,
    date TEXT,
    login TEXT,
    name TEXT,
    pushups INTEGER,
    squats INTEGER,
    hours REAL
);

CREATE TABLE IF NOT EXISTS studies (
    id SERIAL PRIMARY KEY,
    date TEXT,
    login TEXT,
    name TEXT,
    javahours REAL,
    englishhours REAL,
    hours REAL
);

CREATE TABLE IF NOT EXISTS goals (
    id SERIAL PRIMARY KEY,
    login TEXT,
    type TEXT,
    target REAL
);

CREATE TABLE IF NOT EXISTS day_tasks (
    id SERIAL PRIMARY KEY,
    login TEXT,
    date TEXT,
    text TEXT,
    goal_count REAL,
    progress REAL,
    position INTEGER
);

-- Чаты ИИ-агента. id — тот же UUID, что фронт генерирует через crypto.randomUUID(),
-- чтобы не переделывать логику на фронте под числовые id.
CREATE TABLE IF NOT EXISTS ai_chats (
    id              TEXT PRIMARY KEY,
    login           TEXT NOT NULL REFERENCES users(login) ON DELETE CASCADE,
    title           TEXT NOT NULL,
    goal_text       TEXT,              -- полный (не обрезанный) текст первой цели — нужен для парсинга срока плана
    icon            TEXT,              -- эмодзи категории
    icon_tint       TEXT,              -- цвет фона бейджа
    icon_fg         TEXT,              -- цвет иконки/текста бейджа
    favorite        BOOLEAN NOT NULL DEFAULT FALSE,
    plan_total_days INTEGER,           -- NULL, если план ещё не принят
    plan_start_date TEXT,              -- дата принятия плана (YYYY-MM-DD)
    plan_progress   INTEGER DEFAULT 0, -- ручной прогресс 0-100, выставленный слайдером
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
 
CREATE INDEX IF NOT EXISTS idx_ai_chats_login ON ai_chats(login);
 
-- Сообщения внутри чата. Вложения храним как JSON-текст (упрощённо —
-- без хранения самих файлов в БД, только метаданные + текстовое содержимое).
CREATE TABLE IF NOT EXISTS ai_messages (
    id              SERIAL PRIMARY KEY,
    chat_id         TEXT NOT NULL REFERENCES ai_chats(id) ON DELETE CASCADE,
    role            TEXT NOT NULL,     -- 'user' | 'bot'
    text            TEXT,
    attachments_json TEXT,             -- JSON-массив вложений (может быть NULL)
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
 
CREATE INDEX IF NOT EXISTS idx_ai_messages_chat_id ON ai_messages(chat_id);

ALTER TABLE users ADD COLUMN IF NOT EXISTS google_id TEXT UNIQUE;

-- Верификация почты при обычной регистрации (не через Google):
-- verified=FALSE, пока пользователь не введёт код с почты. Логин заблокирован
-- для неверифицированных (см. LoginController). Код и срок годности храним
-- прямо на строке пользователя — проще, чем отдельная таблица, т.к. активна
-- только ОДНА попытка регистрации на login/email одновременно.
ALTER TABLE users ADD COLUMN IF NOT EXISTS verified BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS verification_code TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS verification_expires TIMESTAMP;

-- "Забыли пароль": отдельные колонки от verification_code/expires выше,
-- чтобы код регистрации и код сброса пароля не смешивались, если оба
-- процесса случайно пересекутся по времени у одного пользователя.
ALTER TABLE users ADD COLUMN IF NOT EXISTS reset_code TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS reset_expires TIMESTAMP;
-- Google-аккаунты приходят уже "подтверждённой" почтой от Google — не гоняем
-- их через этот же процесс. У существующих пользователей (до этой миграции)
-- DEFAULT TRUE не трогает их доступ.

ALTER TABLE day_tasks ADD COLUMN IF NOT EXISTS chat_id TEXT REFERENCES ai_chats(id) ON DELETE SET NULL;

ALTER TABLE users ADD COLUMN IF NOT EXISTS telegram_id TEXT UNIQUE;

ALTER TABLE users ADD COLUMN IF NOT EXISTS telegram_username VARCHAR(255);

ALTER TABLE users ADD COLUMN IF NOT EXISTS avatar_url VARCHAR(500);

-- Категория задачи (Задачи/Личные цели/Досуг) — используется графиком
-- продуктивности и бубликом распределения на странице статистики, а также
-- цветными точками-переключателями на Home и в плане ИИ-агента. DEFAULT
-- 'tasks' — чтобы уже существующие строки (сохранённые до этой миграции)
-- не потерялись из статистики, а просто попали в категорию "Задачи".
ALTER TABLE day_tasks ADD COLUMN IF NOT EXISTS category TEXT NOT NULL DEFAULT 'tasks';