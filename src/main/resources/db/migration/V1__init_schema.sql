-- DATABASE_SCHEMA.md Bolum 2 referans alinarak olusturulmustur.

CREATE TABLE workspaces (
    id          UUID PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    plan_type   VARCHAR(20)  NOT NULL DEFAULT 'free',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE users (
    id             UUID PRIMARY KEY,
    email          VARCHAR(255) NOT NULL UNIQUE,
    password_hash  VARCHAR(255) NOT NULL,
    full_name      VARCHAR(100) NOT NULL
);

CREATE TABLE workspace_users (
    workspace_id  UUID NOT NULL REFERENCES workspaces(id),
    user_id       UUID NOT NULL REFERENCES users(id),
    role          VARCHAR(50) NOT NULL,
    PRIMARY KEY (workspace_id, user_id)
);

CREATE TABLE projects (
    id            UUID PRIMARY KEY,
    workspace_id  UUID NOT NULL REFERENCES workspaces(id),
    key           VARCHAR(10)  NOT NULL,
    name          VARCHAR(100) NOT NULL
);

CREATE UNIQUE INDEX uq_projects_workspace_key ON projects(workspace_id, key);

CREATE TABLE sprints (
    id            UUID PRIMARY KEY,
    workspace_id  UUID NOT NULL REFERENCES workspaces(id),
    project_id    UUID NOT NULL REFERENCES projects(id),
    name          VARCHAR(100) NOT NULL,
    goal          TEXT,
    status        VARCHAR(20) NOT NULL DEFAULT 'planned',
    start_date    DATE NOT NULL,
    end_date      DATE NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_sprints_date_range CHECK (end_date > start_date)
);

CREATE INDEX idx_sprints_project_status ON sprints(project_id, status);

-- Is kurali: bir projede ayni anda en fazla bir 'active' sprint olabilir (DATABASE_SCHEMA.md 2.5).
CREATE UNIQUE INDEX one_active_sprint ON sprints(project_id) WHERE status = 'active';

CREATE TABLE tasks (
    id               UUID PRIMARY KEY,
    workspace_id     UUID NOT NULL REFERENCES workspaces(id),
    project_id       UUID NOT NULL REFERENCES projects(id),
    sprint_id        UUID REFERENCES sprints(id),
    parent_task_id   UUID REFERENCES tasks(id),
    task_number      INTEGER NOT NULL,
    title            VARCHAR(255) NOT NULL,
    status           VARCHAR(50) NOT NULL,
    assignee_id      UUID REFERENCES users(id),
    custom_fields    JSONB,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_tasks_project_number ON tasks(project_id, task_number);
CREATE INDEX idx_tasks_workspace_id ON tasks(workspace_id);
CREATE INDEX idx_tasks_assignee_id ON tasks(assignee_id);
CREATE INDEX idx_tasks_sprint_id ON tasks(sprint_id);
CREATE INDEX idx_tasks_custom_fields ON tasks USING GIN (custom_fields);

CREATE TABLE task_events (
    id          UUID PRIMARY KEY,
    task_id     UUID NOT NULL REFERENCES tasks(id),
    actor_id    UUID NOT NULL REFERENCES users(id),
    event_type  VARCHAR(50) NOT NULL,
    old_value   JSONB,
    new_value   JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_task_events_task_id ON task_events(task_id);
CREATE INDEX idx_task_events_created_at ON task_events(created_at);
