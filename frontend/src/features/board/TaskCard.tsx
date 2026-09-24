import { forwardRef, type HTMLAttributes } from 'react'
import { Ban, CalendarDays, ListChecks } from 'lucide-react'
import { differenceInCalendarDays, startOfToday } from 'date-fns'
import type { Task } from '@/lib/types'
import { cn } from '@/lib/cn'
import { fmt, fromIso } from '@/lib/dates'
import { StatusDot, TagChip } from '@/components/ui/misc'
import { taskKey } from '@/lib/status'
import { useMemberMap } from '@/api/queries'
import { Avatar } from '@/components/ui/Avatar'

interface TaskCardProps extends HTMLAttributes<HTMLDivElement> {
  task: Task
  projectKey?: string
  dragging?: boolean
  overlay?: boolean
}

export const TaskCard = forwardRef<HTMLDivElement, TaskCardProps>(function TaskCard(
  { task, projectKey, dragging, overlay, className, ...props },
  ref,
) {
  const diff = task.dueDate ? differenceInCalendarDays(fromIso(task.dueDate), startOfToday()) : null
  const late = diff !== null && diff < 0 && task.status !== 'Done'
  // Bilgilendirici: sadece hala acik (Done olmayan) blocker'lar rozet gerektirir.
  const openBlockers = task.blockedBy.filter((b) => b.status !== 'Done').length
  const memberMap = useMemberMap()
  const assignee = task.assigneeId ? memberMap.get(task.assigneeId) : undefined
  return (
    <div
      ref={ref}
      {...props}
      className={cn(
        'group cursor-grab touch-none rounded-xl border border-border bg-surface p-3 text-left select-none active:cursor-grabbing',
        'transition-[box-shadow,border-color,opacity,transform] duration-200 hover:border-accent/40 hover:shadow-md hover:shadow-black/5',
        dragging && 'opacity-30',
        overlay && 'rotate-2 scale-[1.03] border-accent/60 shadow-2xl shadow-accent/20',
        className,
      )}
    >
      <div className={cn('text-sm leading-snug', task.status === 'Done' && 'text-muted line-through decoration-muted/50')}>
        {task.title}
      </div>
      {task.tags.length > 0 && (
        <div className="mt-1.5 flex flex-wrap gap-1">
          {task.tags.map((tag) => (
            <TagChip key={tag.id} tag={tag} />
          ))}
        </div>
      )}
      <div className="mt-2.5 flex items-center gap-2 text-xs text-muted">
        <StatusDot status={task.status} />
        <span className="font-mono">{taskKey(projectKey, task.taskNumber)}</span>
        {task.storyPoint != null && (
          <span
            className="grid h-4 min-w-4 place-items-center rounded-full bg-surface-2 px-1 text-[10px] font-semibold tabular-nums"
            title="Story point"
          >
            {task.storyPoint}
          </span>
        )}
        {task.subtaskCount > 0 && (
          <span className="inline-flex items-center gap-0.5" title="Alt görevler">
            <ListChecks size={12} />
            {task.completedSubtaskCount}/{task.subtaskCount}
          </span>
        )}
        {openBlockers > 0 && (
          <span className="inline-flex items-center gap-0.5 text-danger" title={`${openBlockers} açık bağımlılık tarafından bloklanıyor`}>
            <Ban size={12} />
          </span>
        )}
        {assignee && <Avatar name={assignee.fullName} seed={assignee.userId} size={18} className="ml-auto" />}
        {task.dueDate && (
          <span
            className={cn(
              !assignee && 'ml-auto',
              'inline-flex items-center gap-1 rounded-md px-1.5 py-0.5',
              late ? 'bg-danger/15 text-danger' : diff === 0 ? 'bg-accent-soft text-accent' : 'bg-surface-2',
            )}
          >
            <CalendarDays size={11} />
            {diff === 0 ? 'Bugün' : fmt(task.dueDate, 'd MMM')}
          </span>
        )}
      </div>
    </div>
  )
})
