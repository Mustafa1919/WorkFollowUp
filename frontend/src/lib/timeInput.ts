export function parseTime(text: string): string | null {
  const match = /^(\d{1,2})[:.](\d{2})$/.exec(text.trim())
    ?? /^(\d{1,2})(\d{2})$/.exec(text.trim())
  if (!match) return null
  const hour = Number(match[1])
  const minute = Number(match[2])
  if (hour > 23 || minute > 59) return null
  return `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`
}
