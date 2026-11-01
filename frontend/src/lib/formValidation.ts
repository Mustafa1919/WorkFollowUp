export interface ValidityLike {
  valueMissing: boolean; typeMismatch: boolean; patternMismatch: boolean; tooLong: boolean; tooShort: boolean
  rangeUnderflow: boolean; rangeOverflow: boolean; stepMismatch: boolean; badInput: boolean; customError: boolean; valid: boolean
}

export interface FieldLike {
  tagName: string
  type: string
  min?: string; max?: string; minLength?: number; maxLength?: number; step?: string; title?: string
  kind?: string
}

export function validationMessage(validity: ValidityLike, field: FieldLike, ownCustom: boolean): string | null {
  if (validity.valid || (validity.customError && !ownCustom)) return null
  if (validity.valueMissing) {
    if (field.kind === 'date') return 'Lütfen bir tarih seçin.'
    if (field.kind === 'time') return 'Lütfen bir saat girin.'
    if (field.tagName === 'SELECT') return 'Lütfen listeden bir seçim yapın.'
    if (field.type === 'checkbox') return 'Devam etmek için bu kutuyu işaretleyin.'
    if (field.type === 'radio') return 'Lütfen seçeneklerden birini seçin.'
    return 'Lütfen bu alanı doldurun.'
  }
  if (validity.typeMismatch) {
    if (field.type === 'email') return 'Geçerli bir e-posta adresi girin.'
    if (field.type === 'url') return 'Geçerli bir adres girin (ör. https://…).'
    return 'Geçerli bir değer girin.'
  }
  if (validity.badInput) return field.type === 'number' ? 'Geçerli bir sayı girin.' : 'Geçerli bir değer girin.'
  if (validity.tooShort) return `En az ${field.minLength} karakter girin.`
  if (validity.tooLong) return `En fazla ${field.maxLength} karakter girilebilir.`
  if (validity.rangeUnderflow) return field.kind === 'date' ? 'Bu tarih seçilemez.' : `Değer en az ${field.min} olmalı.`
  if (validity.rangeOverflow) return field.kind === 'date' ? 'Bu tarih seçilemez.' : `Değer en fazla ${field.max} olmalı.`
  if (validity.stepMismatch) return field.type === 'number' && (!field.step || field.step === '1') ? 'Tam sayı girin.' : 'Geçerli bir değer girin.'
  if (validity.patternMismatch) return field.title || 'Değer istenen biçimde değil.'
  return null
}

let installed = false

export function installTurkishValidationMessages(): void {
  if (installed) return
  installed = true
  const ownMessages = new WeakSet<Element>()
  function fieldTarget(event: Event) {
    const target = event.target
    return target instanceof window.HTMLInputElement || target instanceof window.HTMLSelectElement || target instanceof window.HTMLTextAreaElement
      ? target : null
  }
  document.addEventListener('invalid', (event) => {
    const field = fieldTarget(event)
    if (!field) return
    const message = validationMessage(field.validity, {
      tagName: field.tagName, type: field.type, title: field.title,
      kind: field.dataset.validationKind,
      ...('min' in field ? { min: field.min, max: field.max, step: field.step } : {}),
    ...('minLength' in field ? { minLength: field.minLength, maxLength: field.maxLength } : {}) }, ownMessages.has(field))
    if (message !== null) {
      field.setCustomValidity(message)
      ownMessages.add(field)
    }
  }, { capture: true })
  function clearMessage(event: Event) {
    const field = fieldTarget(event)
    if (field && ownMessages.has(field)) {
      field.setCustomValidity('')
      ownMessages.delete(field)
    }
  }
  document.addEventListener('input', clearMessage, { capture: true })
  document.addEventListener('change', clearMessage, { capture: true })
}
