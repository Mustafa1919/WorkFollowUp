import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { useCreateProject } from '@/api/queries'
import { errorMessage } from '@/lib/api'
import { Dialog } from '@/components/ui/Dialog'
import { Field, Input } from '@/components/ui/Input'
import { Button } from '@/components/ui/Button'

/** Proje anahtari adindan turetilir (ör. "Mobil Uygulama" -> "MU"), elle degistirilebilir. */
function suggestKey(name: string) {
  const words = name
    .toLocaleUpperCase('tr')
    .normalize('NFD')
    .replace(/[^A-Z0-9 ]/g, '')
    .split(/\s+/)
    .filter(Boolean)
  const key = words.length > 1 ? words.map((w) => w[0]).join('') : (words[0] ?? '').slice(0, 4)
  return key.slice(0, 10)
}

export function CreateProjectDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (o: boolean) => void }) {
  const [name, setName] = useState('')
  const [key, setKey] = useState('')
  const [keyTouched, setKeyTouched] = useState(false)
  const create = useCreateProject()
  const navigate = useNavigate()

  async function submit(e: FormEvent) {
    e.preventDefault()
    try {
      const project = await create.mutateAsync({ name, key })
      toast.success(`${project.name} oluşturuldu`)
      onOpenChange(false)
      setName('')
      setKey('')
      setKeyTouched(false)
      navigate(`/projects/${project.id}`)
    } catch (err) {
      toast.error(errorMessage(err))
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange} title="Yeni proje" description="Görevler proje anahtarıyla numaralanır (ör. WEB-12).">
      <form onSubmit={submit} className="space-y-4">
        <Field label="Proje adı" id="p-name">
          <Input
            id="p-name"
            autoFocus
            required
            maxLength={100}
            value={name}
            onChange={(e) => {
              setName(e.target.value)
              if (!keyTouched) setKey(suggestKey(e.target.value))
            }}
          />
        </Field>
        <Field label="Anahtar" id="p-key">
          <Input
            id="p-key"
            required
            maxLength={10}
            pattern="[A-Z][A-Z0-9]{1,9}"
            title="2-10 karakter, büyük harfle başlar (A-Z, 0-9)"
            value={key}
            onChange={(e) => {
              setKeyTouched(true)
              setKey(e.target.value.toUpperCase())
            }}
            className="font-mono uppercase"
          />
        </Field>
        <div className="flex justify-end gap-2 pt-2">
          <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
            Vazgeç
          </Button>
          <Button type="submit" loading={create.isPending}>
            Oluştur
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
