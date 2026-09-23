export type TaskStatus = 'To Do' | 'In Progress' | 'Review' | 'Done'
export const TASK_STATUSES: TaskStatus[] = ['To Do', 'In Progress', 'Review', 'Done']

/** Backend `WorkspaceRole` sabitleri (ADMIN'in degeri WORKSPACE_ADMIN). */
export type WorkspaceRole = 'WORKSPACE_ADMIN' | 'MANAGER' | 'DEVELOPER' | 'VIEWER'

export interface Workspace {
  id: string
  name: string
  planType: string
  role: WorkspaceRole
}

export interface Project {
  id: string
  workspaceId: string
  key: string
  name: string
}

export interface Task {
  id: string
  projectId: string
  sprintId: string | null
  taskNumber: number
  title: string
  status: TaskStatus
  /** yyyy-MM-dd */
  dueDate: string | null
  createdAt: string
  /** Dolu = onaylandi: Kanban'dan kalkar, Tamamlananlar sayfasinda listelenir. */
  approvedAt: string | null
}

export interface Page<T> {
  data: T[]
  nextCursor: string | null
  hasMore: boolean
}

export interface Sprint {
  id: string
  projectId: string
  name: string
  goal: string | null
  status: 'planned' | 'active' | 'completed'
  startDate: string
  endDate: string
  startedAt: string | null
  completedAt: string | null
}

export interface VelocityResponse {
  sprints: {
    sprintId: string
    name: string
    completedAt: string
    committedTasks: number
    completedTasks: number
    committedPoints: number
    completedPoints: number
    spilloverPoints: number
  }[]
  averageVelocity: number | null
}

export interface ThroughputResponse {
  weeks: { weekStart: string; completedTasks: number }[]
}

export interface CycleTimeResponse {
  days: number
  sampleSize: number
  averageSeconds: number | null
  medianSeconds: number | null
  p85Seconds: number | null
  p95Seconds: number | null
}

export interface WebhookIntegration {
  id: string
  provider: string
  secretVersion: number
  createdAt: string
  webhookPath: string
}

export interface SlackIntegration {
  enabled: boolean
  createdAt: string
  updatedAt: string
}
