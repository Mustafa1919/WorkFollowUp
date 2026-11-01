import { useLayoutEffect, useRef, useState } from 'react'
import * as Popover from '@radix-ui/react-popover'
import { DayPicker } from 'react-day-picker'
import { tr } from 'react-day-picker/locale'
import 'react-day-picker/style.css'
import { cn } from '@/lib/cn'
import { fmt, fromIso, iso } from '@/lib/dates'
import { inputClass } from './Input'

interface DatePickerProps {
  id?: string
  value: string
  onChange: (value: string) => void
  min?: string; max?: string
  disabled?: boolean; required?: boolean
  placeholder?: string
  className?: string; 'aria-label'?: string
}

export function DatePicker({ id, value, onChange, min, max, disabled, required, placeholder = 'Tarih seç', className, 'aria-label': ariaLabel }: DatePickerProps) {
  const [open, setOpen] = useState(false)
  const validationInput = useRef<HTMLInputElement>(null)
  useLayoutEffect(() => {
    const message = value && min && value < min
      ? `Tarih ${fmt(min, 'd MMMM yyyy')} veya sonrası olmalı.`
      : value && max && value > max
        ? `Tarih ${fmt(max, 'd MMMM yyyy')} veya öncesi olmalı.`
        : ''
    validationInput.current?.setCustomValidity(message)
  }, [value, min, max, required])
  const selected = value ? fromIso(value) : undefined
  return (
    <Popover.Root open={open && !disabled} onOpenChange={setOpen}>
      <div className={cn('relative w-full', className)}>
        <Popover.Trigger asChild>
          <button id={id} type="button" disabled={disabled} aria-label={ariaLabel}
            className={cn(inputClass, 'cursor-pointer text-left disabled:cursor-not-allowed disabled:opacity-50', !value && 'text-muted')}>
            {value ? fmt(value, 'd MMMM yyyy') : placeholder}
          </button>
        </Popover.Trigger>
        {(required || min || max) && <input ref={validationInput} required={required} disabled={disabled} value={value} onChange={() => {}}
          data-validation-kind="date" tabIndex={-1} aria-hidden="true"
          className="pointer-events-none absolute inset-0 h-full w-full opacity-0" />}
      </div>
      <Popover.Portal>
        <Popover.Content align="start" sideOffset={6}
          className="pointer-events-auto z-[60] max-h-[var(--radix-popover-content-available-height)] overflow-auto rounded-xl border border-border bg-surface p-3 text-fg shadow-xl outline-none">
          <DayPicker mode="single" locale={tr} weekStartsOn={1} autoFocus selected={selected}
            defaultMonth={selected ?? new Date()}
            disabled={[...(min ? [{ before: fromIso(min) }] : []), ...(max ? [{ after: fromIso(max) }] : [])]}
            onSelect={(date) => {
              if (!date) return
              if (validationInput.current) {
                validationInput.current.setCustomValidity('')
                validationInput.current.dispatchEvent(new Event('input', { bubbles: true }))
              }
              onChange(iso(date))
              setOpen(false)
            }} />
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  )
}
