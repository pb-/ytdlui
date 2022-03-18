CREATE TABLE job (
  job_id INTEGER PRIMARY KEY AUTOINCREMENT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER,
  status TEXT NOT NULL CHECK (status IN ('pending', 'running', 'done', 'error', 'up-for-retry')),
  attempts INTEGER NOT NULL DEFAULT 0,
  url TEXT UNIQUE NOT NULL,
  stdout TEXT,
  stderr TEXT,
  exception TEXT,
  title TEXT,
  filename TEXT
);
