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

export interface WorkspaceMember {
  userId: string
  email: string
  fullName: string
  role: WorkspaceRole
}

export interface Project {
  id: string
  workspaceId: string
  key: string
  name: string
}

export interface Tag {
  id: string
  name: string
  /** #RRGGBB */
  color: string
  createdAt: string
}

/** Blocking/blockedBy listelerinde kullanilan hafif gorev referansi — tam Task degil. */
export interface TaskRef {
  id: string
  taskNumber: number
  title: string
  status: TaskStatus
}

export interface Task {
  id: string
  projectId: string
  sprintId: string | null
  /** V19: dolu ise bu gorev bir subtask'tir (tek seviye, ayni proje). */
  parentTaskId: string | null
  taskNumber: number
  title: string
  status: TaskStatus
  /** V22: tek atanan kisi (workspace uyesi, VIEWER olamaz). */
  assigneeId: string | null
  /** yyyy-MM-dd */
  dueDate: string | null
  createdAt: string
  /** Dolu = onaylandi: Kanban'dan kalkar, Tamamlananlar sayfasinda listelenir. */
  approvedAt: string | null
  tags: Tag[]
  storyPoint: number | null
  subtaskCount: number
  completedSubtaskCount: number
  /** Bu gorevin bloklamadigi (kimin bekledigi) gorevler. */
  blocking: TaskRef[]
  /** Bu gorevi bloklayan (once bitmesi gereken) gorevler — sadece bilgilendirici. */
  blockedBy: TaskRef[]
  /** V23: silinenler DAHIL toplam yorum sayisi. */
  commentCount: number
}

/** V23: tek seviye yorum, mention `@[userId]` sozdizimiyle. */
export interface Comment {
  id: string
  taskId: string
  authorId: string
  /** Silinmisse "[silindi]" (asil govde API'de gorunmez). */
  body: string
  edited: boolean
  deleted: boolean
  createdAt: string
  updatedAt: string
}

/** Gorev detayi (V22): liste yanitlarinda tasinmayan aciklama + izleyiciler. */
export interface TaskDetail {
  taskId: string
  /** Markdown; null = aciklama yok. */
  description: string | null
  createdBy: string | null
  watcherIds: string[]
  /** Istegi yapan kullanici izliyor mu. */
  watching: boolean
}

/**
 * Backend `PageResponse` zarfi BILEREK snake_case doner (PHASE_1_DETAILED_DESIGN.md Bolum 4.1:
 * `{ "data": [...], "next_cursor": "...", "has_more": true }`) — Java tarafinda alan adi camelCase
 * (`nextCursor`/`hasMore`) olsa da `@JsonProperty` ile acikca override edilmis. Bu tip ONCEDEN
 * camelCase tanimliydi (fiili JSON'la eslesmiyordu): `hasMore`/`nextCursor` HER ZAMAN `undefined`
 * donuyordu, bu da "daha fazla yukle" akisini ilk sayfadan sonra sessizce durduruyordu (kucuk veri
 * setlerinde fark edilmemis gercek bir bug — bkz. oturum notlari).
 */
export interface Page<T> {
  data: T[]
  next_cursor: string | null
  has_more: boolean
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

export type MeetingFrequency = 'ONCE' | 'DAILY' | 'WEEKLY' | 'MONTHLY'
export type Weekday = 'MON' | 'TUE' | 'WED' | 'THU' | 'FRI' | 'SAT' | 'SUN'

/** Toplanti serisi — occurrence'lar DB'de saklanmaz, backend anlik hesaplar (bkz. Mimari.md). */
export interface Meeting {
  id: string
  title: string
  description: string | null
  meetingUrl: string | null
  /** yyyy-MM-dd */
  startDate: string
  /** HH:mm:ss */
  startTime: string
  durationMinutes: number
  frequency: MeetingFrequency
  intervalCount: number
  /** Yalniz frequency='WEEKLY' icin anlamli. */
  byWeekday: Weekday[]
  untilDate: string | null
  occurrenceCount: number | null
  reminderMinutesBefore: number | null
  createdBy: string
  createdAt: string
}

/** Takvim gorunumu icin duzlestirilmis tek bir tekrar (seri degil). */
export interface MeetingOccurrence {
  meetingId: string
  title: string
  meetingUrl: string | null
  /** yyyy-MM-dd */
  date: string
  startTime: string
  durationMinutes: number
}

export interface Notification {
  id: string
  type: string
  taskId: string | null
  projectId: string | null
  title: string
  body: string
  read: boolean
  createdAt: string
}

// ---------------------------------------------------------------- raporlama (donemsel)

export type GoalMetricType = 'COMPLETED_TASKS' | 'COMPLETED_POINTS' | 'CUSTOM'

export interface Goal {
  id: string
  title: string
  metricType: GoalMetricType
  targetValue: number
  /** Yalniz CUSTOM hedeflerde dolu. */
  manualValue: number | null
  year: number
  /** null = yillik hedef. */
  quarter: number | null
  /** null = workspace geneli hedef. */
  projectId: string | null
}

export interface PeriodInfo {
  year: number
  quarter: number | null
  label: string
  /** yyyy-MM-dd */
  startDate: string
  endDate: string
}

export interface PeriodTotals {
  completedTasks: number
  completedPoints: number
  cycleTimeAverageSeconds: number | null
  cycleTimeMedianSeconds: number | null
  cycleTimeP85Seconds: number | null
  cycleTimeSample: number
  sprintCount: number
  sprintCommittedPoints: number
  sprintCompletedPoints: number
  averageVelocity: number | null
}

export interface PeriodProjectSummary {
  projectId: string
  projectKey: string
  projectName: string
  completedTasks: number
  completedPoints: number
  cycleTimeMedianSeconds: number | null
  cycleTimeSample: number
}

export interface GoalProgress {
  id: string
  title: string
  metricType: GoalMetricType
  targetValue: number
  currentValue: number
  /** 0-100 arasina kirpilmis. */
  progressPercent: number
  projectId: string | null
  projectName: string | null
}

/** Sayfanin tamami tek istekte doner: parcalar ayni donem/suzgec kesitinden gelir. */
export interface PeriodReport {
  period: PeriodInfo
  previousPeriod: PeriodInfo
  totals: PeriodTotals
  previousTotals: {
    completedTasks: number
    completedPoints: number
    cycleTimeMedianSeconds: number | null
  }
  projects: PeriodProjectSummary[]
  months: { month: string; completedTasks: number; completedPoints: number }[]
  /** Hedef ilerlemesi proje suzgecinden ETKILENMEZ. */
  goals: GoalProgress[]
  projectFilterApplied: boolean
}
