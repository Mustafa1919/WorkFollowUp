interface TaskDraft {
  title: string
  dueDate: string
  description: string
  assigneeId: string
  storyPoint: string
  sprintId: string
  tagIds: string[]
}

/** Bos alanlar govdeye girmez; sifir puan dolu bir degerdir. */
export function taskCreateBody(draft: TaskDraft, canPlan: boolean) {
  return {
    title: draft.title,
    ...(draft.dueDate && { dueDate: draft.dueDate }),
    ...(draft.description.trim() && { description: draft.description }),
    ...(canPlan && draft.assigneeId && { assigneeId: draft.assigneeId }),
    ...(canPlan && draft.storyPoint !== '' && { storyPoint: Number(draft.storyPoint) }),
    ...(canPlan && draft.sprintId && { sprintId: draft.sprintId }),
    ...(draft.tagIds.length > 0 && { tagIds: draft.tagIds }),
  }
}

export function findLinkedTask<T extends { id: string; taskNumber: number }>(
  tasks: T[], param: string | null, projectKey?: string,
): T | null {
  if (!param) return null
  return tasks.find((task) => task.id === param || (!!projectKey && `${projectKey}-${task.taskNumber}` === param)) ?? null
}
