import { useInfiniteQuery, useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { useSession } from '@/stores/session'
import type {
  CycleTimeResponse,
  Page,
  Project,
  SlackIntegration,
  Sprint,
  Task,
  TaskStatus,
  ThroughputResponse,
  VelocityResponse,
  WebhookIntegration,
  Workspace,
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
    cursor = res.data.hasMore ? res.data.nextCursor : null
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
    getNextPageParam: (last) => (last.hasMore ? last.nextCursor : null),
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

/** Aktif workspace'teki rol (UI'da yetki gizleme icin; asil kontrol backend'de). */
export function useCurrentRole() {
  const { data } = useWorkspaces()
  const workspaceId = useSession((s) => s.workspaceId)
  return data?.find((w) => w.id === workspaceId)?.role
}

