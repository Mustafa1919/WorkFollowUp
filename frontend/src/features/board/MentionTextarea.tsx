import { useMemo, useRef, useState } from 'react'
import { cn } from '@/lib/cn'
import { filterMembers, findMentionQuery, mentionLabel } from '@/lib/mentions'
import type { MentionMember } from '@/lib/mentions'
import { Avatar } from '@/components/ui/Avatar'
import { inputClass } from '@/components/ui/Input'

/** `map`: gorunur ad -> userId eslemesi (toStorage icin); secimde buraya yazilir. */
type Props = {
  value: string
  onChange: (v: string) => void
  members: MentionMember[]
  map: Map<string, string>
  textareaRef?: React.RefObject<HTMLTextAreaElement | null>
  onCtrlEnter?: () => void
  maxLength: number
  rows?: number
  placeholder?: string
  autoFocus?: boolean
}

/** `@` yazinca imlec altinda uye onerisi acan textarea (yazma ve duzenleme ortak). */
export function MentionTextarea({
  value,
  onChange,
  members,
  map,
  textareaRef,
  onCtrlEnter,
  maxLength,
  rows = 3,
  placeholder,
  autoFocus,
}: Props) {
  const innerRef = useRef<HTMLTextAreaElement>(null)
  const ref = textareaRef ?? innerRef
  const [query, setQuery] = useState<{ start: number; query: string } | null>(null)
  const [active, setActive] = useState(0)

  const options = useMemo(() => (query ? filterMembers(members, query.query, 8) : []), [members, query])
  const open = query !== null && options.length > 0

  function refresh(text: string, caret: number) {
    setQuery(findMentionQuery(text, caret))
    setActive(0)
  }

  function pick(m: MentionMember) {
    if (!query) return
    const el = ref.current
    const caret = el?.selectionStart ?? value.length
    const label = mentionLabel(m, members)
    map.set(label, m.userId)
    const ins = `${label} `
    onChange(value.slice(0, query.start) + ins + value.slice(caret))
    setQuery(null)
    const pos = query.start + ins.length
    requestAnimationFrame(() => {
      el?.focus()
      el?.setSelectionRange(pos, pos)
    })
  }

  return (
    <div className="relative">
      <textarea
        ref={ref}
        autoFocus={autoFocus}
        value={value}
        maxLength={maxLength}
        rows={rows}
        placeholder={placeholder}
        onChange={(e) => {
          onChange(e.target.value)
          refresh(e.target.value, e.target.selectionStart ?? e.target.value.length)
        }}
        onClick={(e) => refresh(value, e.currentTarget.selectionStart ?? value.length)}
        onBlur={() => setQuery(null)}
        onKeyDown={(e) => {
          if (open) {
            if (e.key === 'ArrowDown') {
              e.preventDefault()
              setActive((a) => (a + 1) % options.length)
              return
            }
            if (e.key === 'ArrowUp') {
              e.preventDefault()
              setActive((a) => (a - 1 + options.length) % options.length)
              return
            }
            if (e.key === 'Enter' || e.key === 'Tab') {
              e.preventDefault()
              pick(options[active])
              return
            }
            if (e.key === 'Escape') {
              e.preventDefault()
              e.stopPropagation()
              setQuery(null)
              return
            }
          }
          if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) onCtrlEnter?.()
        }}
        className={cn(inputClass, 'h-auto resize-y py-2 text-sm')}
      />
      {open && (
        <ul
          role="listbox"
          className="absolute bottom-full left-0 z-50 mb-1 max-h-64 w-72 overflow-y-auto rounded-xl border border-border bg-surface p-1.5 shadow-xl"
        >
          {options.map((m, i) => (
            <li
              key={m.userId}
              role="option"
              aria-selected={i === active}
              onMouseDown={(e) => {
                e.preventDefault()
                pick(m)
              }}
              onMouseEnter={() => setActive(i)}
              className={cn(
                'flex cursor-pointer items-center gap-2 rounded-lg px-2 py-1.5 text-sm',
                i === active && 'bg-surface-2',
              )}
            >
              <Avatar name={m.fullName ?? m.email} seed={m.userId} size={18} />
              <span className="truncate">{m.fullName ?? m.email}</span>
              <span className="ml-auto truncate text-xs text-muted">{m.email}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
