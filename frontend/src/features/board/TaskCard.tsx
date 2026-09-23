import { forwardRef, type HTMLAttributes } from 'react'
import { CalendarDays } from 'lucide-react'
import { differenceInCalendarDays, startOfToday } from 'date-fns'
import type { Task } from '@/lib/types'
import { cn } from '@/lib/cn'
import { fmt, fromIso } from '@/lib/dates'
import { StatusDot } from '@/components/ui/misc'
import { taskKey } from '@/lib/status'

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
      <div className="mt-2.5 flex items-center gap-2 text-xs text-muted">
        <StatusDot status={task.status} />
        <span className="font-mono">{taskKey(projectKey, task.taskNumber)}</span>
        {task.dueDate && (
          <span
            className={cn(
              'ml-auto inline-flex items-center gap-1 rounded-md px-1.5 py-0.5',
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
