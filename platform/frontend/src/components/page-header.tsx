import type { ReactNode } from 'react'

export function PageHeader({ title, description, appearance, actions, status }: {
  title: string
  description?: string
  appearance: ReactNode
  actions?: ReactNode
  status?: ReactNode
}) {
  return <header className="page-header">
    <div className="page-heading">
      <h1>{title}</h1>
      {description && <p>{description}</p>}
    </div>
    <div className="page-toolbar">{appearance}{actions}</div>
    {status}
  </header>
}
