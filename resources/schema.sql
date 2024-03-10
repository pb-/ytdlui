CREATE TABLE job (
  job_id INTEGER PRIMARY KEY AUTOINCREMENT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER,
  status TEXT NOT NULL CHECK (status IN ('pending', 'running', 'done', 'error', 'up-for-retry', 'archived')),
  attempts INTEGER NOT NULL DEFAULT 0,
  url TEXT NOT NULL,
  stdout TEXT,
  stderr TEXT,
  exception TEXT,
  title TEXT,
  filename TEXT
);

CREATE UNIQUE INDEX job_url_unique ON job (url) WHERE status <> 'archived';
