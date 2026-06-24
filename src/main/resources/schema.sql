CREATE TABLE IF NOT EXISTS users (
    login TEXT PRIMARY KEY,
    password TEXT,
    name TEXT
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