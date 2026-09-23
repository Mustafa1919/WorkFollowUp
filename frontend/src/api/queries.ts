import { useEffect } from 'react'
import { useInfiniteQuery, useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { subscribeToNotifications, subscribeToProject } from '@/lib/realtime'
import { useSession } from '@/stores/session'
import type {
  CycleTimeResponse,
  Meeting,
  MeetingFrequency,
  MeetingOccurrence,
  Notification,
  Page,
  Project,
  SlackIntegration,
  Sprint,
  Tag,
  Task,
  TaskStatus,
  ThroughputResponse,
  VelocityResponse,
  WebhookIntegration,
  Weekday,
  Workspace,
  WorkspaceMember,
} from '@/lib/types'

/** Tum sorgu anahtarlari workspace'e baglidir: workspace degisince cache karismaz. */
export const keys = {
  workspaces: ['workspaces'] as const,
  projects: (ws: string | null) => ['ws', ws, 'projects'] as const,
  tasks: (ws: string | null, projectId: string) => ['ws', ws, 'tasks', projectId] as const,
  approved: (ws: string | null, projectId: string) => ['ws', ws, 'approved', projectId] as const,
  calendar: (ws: string | null, projectId: string, from: string, to: string) =>
    ['ws', ws, 'tasks', projectId, 'calendar', from, to] as const,
  sprints: (ws: string | null, projectId: string) => ['ws', ws, 'sprints', projectId] as const,
  analytics: (ws: string | null, projectId: string, kind: string) => ['ws', ws, 'analytics', projectId, kind] as const,
  slack: (ws: string | null) => ['ws', ws, 'slack'] as const,
  webhooks: (ws: string | null) => ['ws', ws, 'webhooks'] as const,
  tags: (ws: string | null) => ['ws', ws, 'tags'] as const,
  subtasks: (ws: string | null, taskId: string) => ['ws', ws, 'subtasks', taskId] as const,
  members: (ws: string | null) => ['ws', ws, 'members'] as const,
  notifications: (ws: string | null, unreadOnly: boolean) => ['ws', ws, 'notifications', unreadOnly] as const,
  unreadCount: (ws: string | null) => ['ws', ws, 'notifications', 'unread-count'] as const,
  meetings: (ws: string | null) => ['ws', ws, 'meetings'] as const,
  meetingOccurrences: (ws: string | null, from: string, to: string) =>
    ['ws', ws, 'meetings', 'occurrences', from, to] as const,
}

const ws = () => useSession.getState().workspaceId

// ---------------------------------------------------------------- workspace / project

export function useWorkspaces(enabled = true) {
  return useQuery({
    queryKey: keys.workspaces,
    queryFn: async () => (await api.get<Workspace[]>('/api/v1/workspaces')).data,
    enabled,
  })
}

export function useCreateWorkspace() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (name: string) => (await api.post<{ id: string }>('/api/v1/workspaces', { name })).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.workspaces }),
  })
}

export function useProjects() {
  const workspaceId = useSession((s) => s.workspaceId)
  return useQuery({
    queryKey: keys.projects(workspaceId),
    queryFn: async () => (await api.get<Project[]>('/api/v1/projects')).data,
    enabled: !!workspaceId,
  })
}

export function useCreateProject() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (body: { key: string; name: string }) => (await api.post<Project>('/api/v1/projects', body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.projects(ws()) }),
  })
}

// ---------------------------------------------------------------- tasks

/** Keyset sayfalamayi sonuna kadar yurutur (board tum gorevleri gosterir; 200'luk sayfalar). */
async function fetchAllTasks(projectId: string): Promise<Task[]> {
  const all: Task[] = []
  let cursor: string | null = null
  do {
    const res: { data: Page<Task> } = await api.get<Page<Task>>(`/api/v1/projects/${projectId}/tasks`, {
      params: { limit: 200, cursor: cursor ?? undefined },
    })
    all.push(...res.data.data)
    cursor = res.data.has_more ? res.data.next_cursor : null
  } while (cursor)
  return all
}

export function useTasks(projectId: string) {
  const workspaceId = useSession((s) => s.workspaceId)
  return useQuery({
    queryKey: keys.tasks(workspaceId, projectId),
    queryFn: () => fetchAllTasks(projectId),
    enabled: !!workspaceId && !!projectId,
  })
}

export function useCalendarTasks(projectId: string, from: string, to: string) {
  const workspaceId = useSession((s) => s.workspaceId)
  return useQuery({
    queryKey: keys.calendar(workspaceId, projectId, from, to),
    queryFn: async () =>
      (await api.get<Task[]>(`/api/v1/projects/${projectId}/tasks/calendar`, { params: { from, to } })).data,
    enabled: !!workspaceId && !!projectId,
    placeholderData: (prev) => prev,
  })
}

/** Dashboard: tum projelerin verilen araliktaki gorevleri. */
export function useCalendarTasksForProjects(projects: Project[], from: string, to: string) {
  const workspaceId = useSession((s) => s.workspaceId)
  return useQueries({
    queries: projects.map((p) => ({
      queryKey: keys.calendar(workspaceId, p.id, from, to),
      queryFn: async () =>
        (await api.get<Task[]>(`/api/v1/projects/${p.id}/tasks/calendar`, { params: { from, to } })).data,
    })),
    combine: (results) => ({
      data: results.flatMap((r) => r.data ?? []),
      isLoading: results.some((r) => r.isLoading),
    }),
  })
}

export function useCreateTask(projectId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (body: { title: string; dueDate?: string | null }) =>
      (await api.post<Task>(`/api/v1/projects/${projectId}/tasks`, body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.tasks(ws(), projectId) }),
  })
}

type TaskPatch = { status?: TaskStatus; dueDate?: string | null; sprintId?: string | null }

/**
 * Gorev guncellemesi OPTIMISTIC: surukle-birakta kart aninda yerine oturur, hata olursa geri alinir.
 * Tek alan degisir; backend'de her alanin kendi endpoint'i var.
 */
export function useUpdateTask(projectId: string) {
  const qc = useQueryClient()
  const prefix = keys.tasks(ws(), projectId)
  return useMutation({
    mutationFn: async ({ id, patch }: { id: string; patch: TaskPatch }) => {
      if (patch.status !== undefined) return (await api.patch<Task>(`/api/v1/tasks/${id}`, { status: patch.status })).data
      if (patch.dueDate !== undefined)
        return (await api.put<Task>(`/api/v1/tasks/${id}/due-date`, { dueDate: patch.dueDate })).data
      return (await api.put<Task>(`/api/v1/tasks/${id}/sprint`, { sprintId: patch.sprintId ?? null })).data
    },
    onMutate: async ({ id, patch }) => {
      await qc.cancelQueries({ queryKey: prefix })
      const snapshot = qc.getQueriesData<Task[]>({ queryKey: prefix })
      const original = snapshot.flatMap(([, data]) => data ?? []).find((t) => t.id === id)
      const updated = original ? { ...original, ...patch } : undefined
      for (const [key, data] of snapshot) {
        if (!data) continue
        const exists = data.some((t) => t.id === id)
        // Takvim araligi sorgusu: tarihsiz listeden bir gune birakilan gorev bu cache'te henuz yok.
        const isCalendar = key[4] === 'calendar'
        const inRange =
          isCalendar && updated?.dueDate && updated.dueDate >= (key[5] as string) && updated.dueDate <= (key[6] as string)
        if (exists) qc.setQueryData<Task[]>(key, data.map((t) => (t.id === id ? { ...t, ...patch } : t)))
        else if (inRange && updated) qc.setQueryData<Task[]>(key, [...data, updated])
      }
      return { snapshot }
    },
    onError: (_e, _v, ctx) => ctx?.snapshot.forEach(([key, data]) => qc.setQueryData(key, data)),
    onSettled: () => qc.invalidateQueries({ queryKey: prefix }),
  })
}

export function useUpdateStoryPoint(projectId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({ id, storyPoint }: { id: string; storyPoint: number | null }) =>
      (await api.put<Task>(`/api/v1/tasks/${id}/story-point`, { storyPoint })).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.tasks(ws(), projectId) }),
  })
}

/**
 * Backend WebSocket fan-out'una (`/topic/workspace.{ws}.project.{projectId}`) abone olur; herhangi
 * bir task.events olayinda (durum/etiket/story point/tarih/onay/silme) ilgili sorgulari gecersiz
 * kilar. `keys.tasks(...)` onekini gecersiz kilmak `calendar` anahtarini da kapsar (ayni dizi
 * onekini paylasiyorlar); `approved` ayri bir dal oldugu icin ayrica belirtilir.
 */
export function useProjectRealtime(projectId: string) {
  const workspaceId = useSession((s) => s.workspaceId)
  const qc = useQueryClient()
  useEffect(() => {
    if (!workspaceId || !projectId) return
    return subscribeToProject(workspaceId, projectId, (envelope) => {
      console.debug('[realtime] task.events çerçevesi alındı:', envelope)
      qc.invalidateQueries({ queryKey: keys.tasks(workspaceId, projectId) })
      qc.invalidateQueries({ queryKey: keys.approved(workspaceId, projectId) })
    })
  }, [workspaceId, projectId, qc])
}

// ---------------------------------------------------------------- approval / delete

/** Tamamlananlar sayfasi: onay zamanina gore yeniden eskiye, "daha fazla" ile sayfalanir. */
export function useApprovedTasks(projectId: string) {
  const workspaceId = useSession((s) => s.workspaceId)
  return useInfiniteQuery({
    queryKey: keys.approved(workspaceId, projectId),
    queryFn: async ({ pageParam }) =>
      (
        await api.get<Page<Task>>(`/api/v1/projects/${projectId}/tasks/approved`, {
          params: { limit: 50, cursor: pageParam ?? undefined },
        })
      ).data,
    initialPageParam: null as string | null,
    getNextPageParam: (last) => (last.has_more ? last.next_cursor : null),
    enabled: !!workspaceId && !!projectId,
  })
}

/** Onay / onay geri alma / silme: board, takvim ve Tamamlananlar listesinin hepsini yeniler. */
export function useTaskLifecycle(projectId: string) {
  const qc = useQueryClient()
  const invalidate = () =>
    Promise.all([
      qc.invalidateQueries({ queryKey: keys.tasks(ws(), projectId) }),
      qc.invalidateQueries({ queryKey: keys.approved(ws(), projectId) }),
    ])
  return {
    approve: useMutation({
      mutationFn: async (id: string) => (await api.post<Task>(`/api/v1/tasks/${id}/approval`)).data,
      onSuccess: invalidate,
    }),
    revoke: useMutation({
      mutationFn: async (id: string) => (await api.delete<Task>(`/api/v1/tasks/${id}/approval`)).data,
      onSuccess: invalidate,
    }),
    remove: useMutation({
      mutationFn: async (id: string) => api.delete(`/api/v1/tasks/${id}`),
      onSuccess: invalidate,
    }),
  }
}

// ---------------------------------------------------------------- sprints

export function useSprints(projectId: string) {
  const workspaceId = useSession((s) => s.workspaceId)
  return useQuery({
    queryKey: keys.sprints(workspaceId, projectId),
    queryFn: async () => (await api.get<Sprint[]>(`/api/v1/projects/${projectId}/sprints`)).data,
    enabled: !!workspaceId && !!projectId,
  })
}

export function useSprintActions(projectId: string) {
  const qc = useQueryClient()
  const invalidate = () => qc.invalidateQueries({ queryKey: keys.sprints(ws(), projectId) })
  return {
    create: useMutation({
      mutationFn: async (body: { name: string; goal?: string; startDate: string; endDate: string }) =>
        (await api.post<Sprint>(`/api/v1/projects/${projectId}/sprints`, body)).data,
      onSuccess: invalidate,
    }),
    start: useMutation({
      mutationFn: async (id: string) => (await api.post<Sprint>(`/api/v1/sprints/${id}/start`)).data,
      onSuccess: invalidate,
    }),
    complete: useMutation({
      mutationFn: async (id: string) => (await api.post<Sprint>(`/api/v1/sprints/${id}/complete`)).data,
      onSuccess: invalidate,
    }),
  }
}

// ---------------------------------------------------------------- analytics

export function useAnalytics(projectId: string) {
  const workspaceId = useSession((s) => s.workspaceId)
  const base = `/api/v1/projects/${projectId}/analytics`
  const enabled = !!workspaceId && !!projectId
  return {
    velocity: useQuery({
      queryKey: keys.analytics(workspaceId, projectId, 'velocity'),
      queryFn: async () => (await api.get<VelocityResponse>(`${base}/velocity`, { params: { sprints: 8 } })).data,
      enabled,
    }),
    throughput: useQuery({
      queryKey: keys.analytics(workspaceId, projectId, 'throughput'),
      queryFn: async () => (await api.get<ThroughputResponse>(`${base}/throughput`, { params: { weeks: 12 } })).data,
      enabled,
    }),
    cycleTime: useQuery({
      queryKey: keys.analytics(workspaceId, projectId, 'cycle-time'),
      queryFn: async () => (await api.get<CycleTimeResponse>(`${base}/cycle-time`, { params: { days: 30 } })).data,
      enabled,
    }),
  }
}

// ---------------------------------------------------------------- integrations (ADMIN)

export function useSlack(enabled: boolean) {
  const workspaceId = useSession((s) => s.workspaceId)
  return useQuery({
    queryKey: keys.slack(workspaceId),
    queryFn: async () => {
      const res = await api.get<SlackIntegration>('/api/v1/integrations/slack', { validateStatus: (s) => s < 500 })
      return res.status === 200 ? res.data : null
    },
    enabled: enabled && !!workspaceId,
  })
}

export function useSlackActions() {
  const qc = useQueryClient()
  const invalidate = () => qc.invalidateQueries({ queryKey: keys.slack(ws()) })
  return {
    save: useMutation({
      mutationFn: async (body: { webhookUrl: string; enabled: boolean }) =>
        (await api.put('/api/v1/integrations/slack', body)).data,
      onSuccess: invalidate,
    }),
    remove: useMutation({
      mutationFn: async () => api.delete('/api/v1/integrations/slack'),
      onSuccess: invalidate,
    }),
  }
}

export function useWebhooks(enabled: boolean) {
  const workspaceId = useSession((s) => s.workspaceId)
  return useQuery({
    queryKey: keys.webhooks(workspaceId),
    queryFn: async () => (await api.get<WebhookIntegration[]>('/api/v1/integrations/webhooks')).data,
    enabled: enabled && !!workspaceId,
  })
}

export function useWebhookActions() {
  const qc = useQueryClient()
  const invalidate = () => qc.invalidateQueries({ queryKey: keys.webhooks(ws()) })
  return {
    create: useMutation({
      mutationFn: async () =>
        (await api.post<{ integration: WebhookIntegration; secret: string }>('/api/v1/integrations/webhooks')).data,
      onSuccess: invalidate,
    }),
    rotate: useMutation({
      mutationFn: async (id: string) =>
        (await api.post<{ integration: WebhookIntegration; secret: string }>(`/api/v1/integrations/webhooks/${id}/rotate-secret`))
          .data,
      onSuccess: invalidate,
    }),
    remove: useMutation({
      mutationFn: async (id: string) => api.delete(`/api/v1/integrations/webhooks/${id}`),
      onSuccess: invalidate,
    }),
  }
}

// ---------------------------------------------------------------- tags

export function useTags() {
  const workspaceId = useSession((s) => s.workspaceId)
  return useQuery({
    queryKey: keys.tags(workspaceId),
    queryFn: async () => (await api.get<Tag[]>('/api/v1/tags')).data,
    enabled: !!workspaceId,
  })
}

export function useTagActions() {
  const qc = useQueryClient()
  // Bir etiketin adi/rengi degisince onu tasiyan TUM gorev cevaplari da degismis olur (embed edilmis
  // kopya); tek tek gorev cache'ini yamamak yerine workspace altindaki her seyi (tasks/approved/tags)
  // gecersiz kilmak daha basit ve dogru.
  const invalidateAll = () => qc.invalidateQueries({ queryKey: ['ws', ws()] })
  return {
    create: useMutation({
      mutationFn: async (body: { name: string; color: string }) => (await api.post<Tag>('/api/v1/tags', body)).data,
      onSuccess: invalidateAll,
    }),
    update: useMutation({
      mutationFn: async ({ id, ...body }: { id: string; name: string; color: string }) =>
        (await api.put<Tag>(`/api/v1/tags/${id}`, body)).data,
      onSuccess: invalidateAll,
    }),
    remove: useMutation({
      mutationFn: async (id: string) => api.delete(`/api/v1/tags/${id}`),
      onSuccess: invalidateAll,
    }),
  }
}

/** Bir gorevin etiketlerini atar/kaldirir; TaskDialog + Kanban kartlari icin ortak. */
export function useTaskTagAssignment(projectId: string) {
  const qc = useQueryClient()
  const invalidate = () =>
    Promise.all([
      qc.invalidateQueries({ queryKey: keys.tasks(ws(), projectId) }),
      qc.invalidateQueries({ queryKey: keys.approved(ws(), projectId) }),
    ])
  return {
    assign: useMutation({
      mutationFn: async ({ taskId, tagId }: { taskId: string; tagId: string }) =>
        (await api.put<Task>(`/api/v1/tasks/${taskId}/tags/${tagId}`)).data,
      onSuccess: invalidate,
    }),
    unassign: useMutation({
      mutationFn: async ({ taskId, tagId }: { taskId: string; tagId: string }) =>
        (await api.delete<Task>(`/api/v1/tasks/${taskId}/tags/${tagId}`)).data,
      onSuccess: invalidate,
    }),
  }
}

/** Aktif workspace'teki rol (UI'da yetki gizleme icin; asil kontrol backend'de). */
// ---------------------------------------------------------------- subtask / dependency

/** Alt gorev listesi (TaskDialog) — tam Task alani gerekir, sadece sayi degil. */
export function useSubtasks(taskId: string | undefined) {
  const workspaceId = useSession((s) => s.workspaceId)
  return useQuery({
    queryKey: keys.subtasks(workspaceId, taskId ?? ''),
    queryFn: async () => (await api.get<Task[]>(`/api/v1/tasks/${taskId}/subtasks`)).data,
    enabled: !!workspaceId && !!taskId,
  })
}

/** Bir gorevin ust gorevini atar/kaldirir — TaskDialog'un "Alt Görevler" bolumu icin. */
export function useTaskParentAssignment(projectId: string) {
  const qc = useQueryClient()
  const invalidate = () =>
    Promise.all([
      qc.invalidateQueries({ queryKey: keys.tasks(ws(), projectId) }),
      qc.invalidateQueries({ queryKey: keys.approved(ws(), projectId) }),
      qc.invalidateQueries({ queryKey: ['ws', ws(), 'subtasks'] }),
    ])
  return {
    setParent: useMutation({
      mutationFn: async ({ taskId, parentTaskId }: { taskId: string; parentTaskId: string }) =>
        (await api.put<Task>(`/api/v1/tasks/${taskId}/parent/${parentTaskId}`)).data,
      onSuccess: invalidate,
    }),
    removeParent: useMutation({
      mutationFn: async (taskId: string) => (await api.delete<Task>(`/api/v1/tasks/${taskId}/parent`)).data,
      onSuccess: invalidate,
    }),
  }
}

/**
 * Dependency (Blocked by/Blocking) atar/kaldirir. Workspace geneli oldugundan (proje siniri yok)
 * hangi projenin cache'i etkilenecegi bilinemez — invalidate PROJE FARKI GOZETMEKSIZIN tum
 * workspace 'tasks' sorgularini kapsar (tags'teki invalidateAll ile ayni gerekce).
 */
export function useTaskDependencyAssignment() {
  const qc = useQueryClient()
  const invalidate = () => qc.invalidateQueries({ queryKey: ['ws', ws()] })
  return {
    link: useMutation({
      mutationFn: async ({ taskId, blockingTaskId }: { taskId: string; blockingTaskId: string }) =>
        (await api.put<Task>(`/api/v1/tasks/${taskId}/dependencies/${blockingTaskId}`)).data,
      onSuccess: invalidate,
    }),
    unlink: useMutation({
      mutationFn: async ({ taskId, blockingTaskId }: { taskId: string; blockingTaskId: string }) =>
        (await api.delete<Task>(`/api/v1/tasks/${taskId}/dependencies/${blockingTaskId}`)).data,
      onSuccess: invalidate,
    }),
  }
}

/** Bagimlilik/alt gorev secicileri icin: tum workspace projelerindeki gorevler, tek listede. */
export function useAllTasks(projects: Project[]) {
  const workspaceId = useSession((s) => s.workspaceId)
  return useQueries({
    queries: projects.map((p) => ({
      queryKey: keys.tasks(workspaceId, p.id),
      queryFn: () => fetchAllTasks(p.id),
    })),
    combine: (results) => ({
      data: results.flatMap((r) => r.data ?? []),
      isLoading: results.some((r) => r.isLoading),
    }),
  })
}

export function useCurrentRole() {
  const { data } = useWorkspaces()
  const workspaceId = useSession((s) => s.workspaceId)
  return data?.find((w) => w.id === workspaceId)?.role
}

// ---------------------------------------------------------------- workspace members

export function useMembers(enabled: boolean) {
  const workspaceId = useSession((s) => s.workspaceId)
  return useQuery({
    queryKey: keys.members(workspaceId),
    queryFn: async () => (await api.get<WorkspaceMember[]>('/api/v1/workspaces/members')).data,
    enabled: enabled && !!workspaceId,
  })
}

export function useMemberActions() {
  const qc = useQueryClient()
  const invalidate = () => qc.invalidateQueries({ queryKey: keys.members(ws()) })
  return {
    add: useMutation({
      mutationFn: async (body: { email: string; role: string }) =>
        (await api.post<WorkspaceMember>('/api/v1/workspaces/members', body)).data,
      onSuccess: invalidate,
    }),
    changeRole: useMutation({
      mutationFn: async ({ userId, role }: { userId: string; role: string }) =>
        (await api.patch<WorkspaceMember>(`/api/v1/workspaces/members/${userId}`, { role })).data,
      onSuccess: invalidate,
    }),
    remove: useMutation({
      mutationFn: async (userId: string) => api.delete(`/api/v1/workspaces/members/${userId}`),
      onSuccess: invalidate,
    }),
  }
}

// ---------------------------------------------------------------- notifications (Inbox)

export function useUnreadCount() {
  const workspaceId = useSession((s) => s.workspaceId)
  return useQuery({
    queryKey: keys.unreadCount(workspaceId),
    queryFn: async () => (await api.get<{ count: number }>('/api/v1/notifications/unread-count')).data.count,
    enabled: !!workspaceId,
  })
}

/** Inbox listesi: keyset sayfalama, "daha fazla yükle" ile devam eder (Tamamlananlar ile aynı desen). */
export function useNotifications(unreadOnly: boolean) {
  const workspaceId = useSession((s) => s.workspaceId)
  return useInfiniteQuery({
    queryKey: keys.notifications(workspaceId, unreadOnly),
    queryFn: async ({ pageParam }) =>
      (
        await api.get<Page<Notification>>('/api/v1/notifications', {
          params: { limit: 20, cursor: pageParam ?? undefined, unreadOnly },
        })
      ).data,
    initialPageParam: null as string | null,
    getNextPageParam: (last) => (last.has_more ? last.next_cursor : null),
    enabled: !!workspaceId,
  })
}

export function useNotificationActions() {
  const qc = useQueryClient()
  const invalidate = () => qc.invalidateQueries({ queryKey: ['ws', ws(), 'notifications'] })
  return {
    markRead: useMutation({
      mutationFn: async (id: string) => (await api.post<Notification>(`/api/v1/notifications/${id}/read`)).data,
      onSuccess: invalidate,
    }),
    markAllRead: useMutation({
      mutationFn: async () => api.post('/api/v1/notifications/read-all'),
      onSuccess: invalidate,
    }),
  }
}

/**
 * Backend `/user/queue/notifications` push'una abone olur (bkz. lib/realtime.ts); yeni bir bildirim
 * geldiğinde Inbox listesini ve okunmamış sayacını geçersiz kılar. `useProjectRealtime` ile AYNI
 * desen ama workspace/proje'den BAĞIMSIZ — AppLayout'ta, oturum boyunca TEK yerden çağrılır.
 */
export function useNotificationRealtime() {
  const workspaceId = useSession((s) => s.workspaceId)
  const qc = useQueryClient()
  useEffect(() => {
    if (!workspaceId) return
    return subscribeToNotifications(() => {
      qc.invalidateQueries({ queryKey: ['ws', workspaceId, 'notifications'] })
    })
  }, [workspaceId, qc])
}

// ---------------------------------------------------------------- meetings

export interface MeetingPayload {
  title: string
  description?: string | null
  meetingUrl?: string | null
  startDate: string
  startTime: string
  durationMinutes: number
  frequency: MeetingFrequency
  intervalCount: number
  byWeekday?: Weekday[]
  untilDate?: string | null
  occurrenceCount?: number | null
  reminderMinutesBefore?: number | null
}

/** Toplanti serilerinin tam listesi (yonetim paneli icin) — workspace geneli, proje siniri yok. */
export function useMeetings() {
  const workspaceId = useSession((s) => s.workspaceId)
  return useQuery({
    queryKey: keys.meetings(workspaceId),
    queryFn: async () => (await api.get<Meeting[]>('/api/v1/meetings')).data,
    enabled: !!workspaceId,
  })
}

/** Takvim gorunumu: TUM serilerin verilen araliktaki occurrence'lari, duzlestirilmis. */
export function useMeetingOccurrences(from: string, to: string) {
  const workspaceId = useSession((s) => s.workspaceId)
  return useQuery({
    queryKey: keys.meetingOccurrences(workspaceId, from, to),
    queryFn: async () =>
      (await api.get<MeetingOccurrence[]>('/api/v1/meetings/occurrences', { params: { from, to } })).data,
    enabled: !!workspaceId && !!from && !!to,
    placeholderData: (prev) => prev,
  })
}

export function useMeetingActions() {
  const qc = useQueryClient()
  const invalidate = () => qc.invalidateQueries({ queryKey: ['ws', ws(), 'meetings'] })
  return {
    create: useMutation({
      mutationFn: async (body: MeetingPayload) => (await api.post<Meeting>('/api/v1/meetings', body)).data,
      onSuccess: invalidate,
    }),
    update: useMutation({
      mutationFn: async ({ id, ...body }: MeetingPayload & { id: string }) =>
        (await api.put<Meeting>(`/api/v1/meetings/${id}`, body)).data,
      onSuccess: invalidate,
    }),
    remove: useMutation({
      mutationFn: async (id: string) => api.delete(`/api/v1/meetings/${id}`),
      onSuccess: invalidate,
    }),
  }
}

