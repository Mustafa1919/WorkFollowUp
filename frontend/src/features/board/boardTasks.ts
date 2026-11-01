import type { Task } from '../../lib/types'

/** Atanan filtresinde eslesen alt gorevlerin ust gorevlerini de panoya alir. */
export function kanbanTasks(all: Task[], filtered: Task[], assigneeFilterActive: boolean): Task[] {
  if (!assigneeFilterActive) return filtered.filter((task) => task.parentTaskId == null)

  const visibleIds = new Set(filtered.map((task) => task.parentTaskId ?? task.id))
  return all.filter((task) => task.parentTaskId == null && visibleIds.has(task.id))
}
