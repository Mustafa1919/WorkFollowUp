import type { TaskStatus } from './types'

export const STATUS_META: Record<TaskStatus, { label: string; dot: string; soft: string; text: string }> = {
  'To Do': { label: 'Yapılacak', dot: 'bg-st-todo', soft: 'bg-st-todo/15', text: 'text-st-todo' },
  'In Progress': { label: 'Devam ediyor', dot: 'bg-st-progress', soft: 'bg-st-progress/15', text: 'text-st-progress' },
  Review: { label: 'İncelemede', dot: 'bg-st-review', soft: 'bg-st-review/15', text: 'text-st-review' },
  Done: { label: 'Tamamlandı', dot: 'bg-st-done', soft: 'bg-st-done/15', text: 'text-st-done' },
}

export function taskKey(projectKey: string | undefined, n: number) {
  return `${projectKey ?? 'T'}-${n}`
}
