import { useLayoutEffect, useRef, useState } from 'react'
import { cn } from '@/lib/cn'
import { parseTime } from '@/lib/timeInput'
import { inputClass } from './Input'

interface TimeFieldProps {
  id?: string
  value: string
  onChange: (value: string) => void
  disabled?: boolean; required?: boolean; className?: string; 'aria-label'?: string
}

export function TimeField({ value, onChange, className, ...props }: TimeFieldProps) {
  const [draft, setDraft] = useState(value)
  const [previousValue, setPreviousValue] = useState(value)
  const input = useRef<HTMLInputElement>(null)
  if (previousValue !== value) {
    setPreviousValue(value)
    setDraft(value)
  }
  useLayoutEffect(() => {
    input.current?.setCustomValidity(draft === '' || parseTime(draft) ? '' : 'Saat SS:dd biçiminde olmalı (ör. 09:30).')
  }, [draft])
  return <input {...props} ref={input} type="text" inputMode="numeric" placeholder="SS:dd" maxLength={5}
    data-validation-kind="time" className={cn(inputClass, className)} value={draft}
    onChange={(event) => {
      const text = event.target.value
      setDraft(text)
      const normalized = parseTime(text)
      event.target.setCustomValidity(text === '' || normalized ? '' : 'Saat SS:dd biçiminde olmalı (ör. 09:30).')
      if (text === '' || normalized) {
        const next = normalized ?? ''
        setPreviousValue(next)
        onChange(next)
      }
    }}
    onBlur={() => {
      const normalized = parseTime(draft)
      if (normalized) setDraft(normalized)
    }}
  />
}
