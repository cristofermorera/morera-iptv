import { useState, type FormEvent, type ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Bell, RefreshCw } from 'lucide-react'
import { api, json } from './api'
import { Button } from './components/ui/button'
import { NetworkAdmin } from './network'
import { SportsAdmin } from './sports-admin'
import { PageHeader } from './components/page-header'

const sections = ['Visão geral', 'Usuários', 'Mídia', 'Listas', 'EPG', 'Sessões', 'Configurações', 'Rede', 'Transmissões Esportivas', 'Logs'] as const
type Section = typeof sections[number]
type RecordRow = Record<string, string | number | boolean | null>
type Operation = { path: string; method: string; body?: object }

function DataTable({ rows, columns, actions }: { rows: RecordRow[]; columns: Record<string, string>; actions?: (row: RecordRow) => ReactNode }) {
  return rows.length ? <div className="table-wrap"><table><thead><tr>{Object.values(columns).map(label => <th key={label}>{label}</th>)}{actions && <th>Ações</th>}</tr></thead>
    <tbody>{rows.map((row, index) => <tr key={String(row.id ?? index)}>{Object.keys(columns).map(key => <td key={key}>{row[key] == null ? '—' : typeof row[key] === 'boolean' ? row[key] ? 'Sim' : 'Não' : String(row[key])}</td>)}
      {actions && <td><div className="actions">{actions(row)}</div></td>}</tr>)}</tbody></table></div> : <p className="muted">Nenhum registro encontrado.</p>
}

function AdminHeader({ appearance }: { appearance: ReactNode }) {
  const cache = useQueryClient()
  const [activitiesOpen, setActivitiesOpen] = useState(false)
  const activities = useQuery({
    queryKey: ['admin', '/logs'],
    queryFn: () => api<RecordRow[]>('/api/admin/logs'),
    enabled: activitiesOpen,
  })
  const refresh = useMutation({ mutationFn: async () => {
    await Promise.all([
      cache.invalidateQueries({ queryKey: ['admin'] }, { throwOnError: true }),
      cache.invalidateQueries({ queryKey: ['network-policy'] }, { throwOnError: true }),
    ])
  } })
  return <PageHeader title="Administração" description="Gerencie usuários, conteúdo e conectividade." appearance={appearance} actions={<>
      <details className="admin-activity" onToggle={event => setActivitiesOpen(event.currentTarget.open)} onKeyDown={event => {
        if (event.key === 'Escape' && event.currentTarget.open) {
          event.currentTarget.open = false
          event.currentTarget.querySelector('summary')?.focus()
        }
      }}>
        <summary className="header-tool" aria-label="Atividades recentes" title="Atividades recentes"><Bell aria-hidden="true" /></summary>
        <div className="panel activity-popover">
          <h2>Atividades recentes</h2>
          {activities.isPending ? <p role="status">Carregando…</p>
            : activities.error ? <p className="error" role="alert">{activities.error.message}</p>
            : !activities.data?.length ? <p className="muted">Nenhuma atividade registrada.</p>
            : <ul>{activities.data.slice(0, 5).map(row => <li key={String(row.id)}><strong>{String(row.action).replaceAll('_', ' ')}</strong><span>{row.actor}</span></li>)}</ul>}
        </div>
      </details>
      <Button type="button" variant="ghost" className="header-tool" aria-label="Atualizar dados" title="Atualizar dados" disabled={refresh.isPending} onClick={() => refresh.mutate()}>
        <RefreshCw aria-hidden="true" className={refresh.isPending ? 'animate-spin' : undefined} />
      </Button>
    </>} status={<>
      <span className="sr-only" role="status">{refresh.isPending ? 'Atualizando dados.' : refresh.isSuccess ? 'Dados atualizados.' : ''}</span>
      {refresh.error && <p className="error admin-refresh-error" role="alert">{refresh.error.message}</p>}
    </>} />
}

export function Admin({ appearance }: { appearance: ReactNode }) {
  const cache = useQueryClient()
  const [section, setSection] = useState<Section>('Visão geral')
  const [edit, setEdit] = useState<RecordRow>()
  const [filter, setFilter] = useState('')
  const [query, setQuery] = useState('')
  const [page, setPage] = useState(0)
  const [renaming, setRenaming] = useState(false)
  const [creating, setCreating] = useState(false)
  const [message, setMessage] = useState('')
  const paths: Partial<Record<Section, string>> = {
    'Visão geral': '/dashboard', Usuários: '/users', Mídia: '/channels?' + new URLSearchParams({ q: query, page: String(page) }),
    Listas: '/lists', EPG: '/epg', Sessões: '/sessions', Configurações: '/settings', Logs: '/logs',
  }
  const path = paths[section]
  const result = useQuery({ queryKey: ['admin', path], enabled: !!path, queryFn: () => api<RecordRow[] | RecordRow>('/api/admin' + path) })
  const lists = useQuery({ queryKey: ['admin', '/lists'], enabled: section === 'Mídia' && creating, queryFn: () => api<RecordRow[]>('/api/admin/lists') })
  const mutation = useMutation({ mutationFn: (operation: Operation) => api<{ programs?: number }>('/api/admin' + operation.path, operation.body
    ? json(operation.method, operation.body) : { method: operation.method }), onSuccess: data => {
    setEdit(undefined)
    setCreating(false)
    setRenaming(false)
    setMessage(data?.programs === undefined ? 'Operação concluída.' : data.programs + ' programas lidos.')
    cache.invalidateQueries({ queryKey: ['admin'] })
    for (const key of ['lists', 'stats', 'entries', 'groups', 'platform', 'sports-guide', 'sports-options']) cache.invalidateQueries({ queryKey: [key] })
  } })
  const rows = Array.isArray(result.data) ? result.data : []
  const summary = result.data && !Array.isArray(result.data) ? result.data : undefined
  function change(next: Section) {
    setSection(next); setEdit(undefined); setFilter(''); setQuery(''); setPage(0)
    setCreating(false); setRenaming(false); setMessage(''); mutation.reset()
  }
  function submit(event: FormEvent<HTMLFormElement>, endpoint: string, method: string, convert?: (values: Record<string, FormDataEntryValue>) => object) {
    event.preventDefault()
    const values = Object.fromEntries(new FormData(event.currentTarget))
    mutation.mutate({ path: endpoint, method, body: convert ? convert(values) : values })
  }
  function remove(endpoint: string, question: string) {
    if (window.confirm(question)) mutation.mutate({ path: endpoint, method: 'DELETE' })
  }
  const pending = mutation.isPending
  return <>
    <AdminHeader appearance={appearance} />
    <nav className="admin-tabs category-list" aria-label="Seções administrativas">{sections.map(item =>
      <button type="button" className="category" aria-pressed={section === item} key={item} onClick={() => change(item)}>{item}</button>)}</nav>
    {section === 'Rede' ? <NetworkAdmin /> : section === 'Transmissões Esportivas' ? <SportsAdmin /> : <section className="panel stack">
      <h2>{section}</h2>
      {result.isPending && <p role="status">Carregando…</p>}
      {result.error && <p className="error" role="alert">{result.error.message}</p>}
      {mutation.error && <p className="error" role="alert">{mutation.error.message}</p>}
      {message && <p role="status">{message}</p>}
      {section === 'Visão geral' && summary && <div className="stats admin-stats">{Object.entries({account:'Usuários',playlist:'Listas',channel:'Itens de mídia',epg_source:'Fontes EPG',sessions:'Sessões ativas',status:'Estado'}).map(([key,label]) =>
        <div className="stat" key={key}><span>{label}<strong>{summary[key]}</strong></span></div>)}</div>}
      {section === 'Usuários' && <>
        <DataTable rows={rows} columns={{ email: 'E-mail', role: 'Perfil', enabled: 'Ativo' }} actions={row => <Button variant="outline" onClick={() => setEdit(row)}>Editar</Button>} />
        <form className="admin-form" key={String(edit?.id ?? 'new')} onSubmit={event => submit(event, '/users' + (edit ? '/' + edit.id : ''), edit ? 'PUT' : 'POST', values => ({ ...values, enabled: values.enabled === 'on' }))}>
          <h3>{edit ? 'Editar usuário' : 'Novo usuário'}</h3>
          <label>E-mail<input name="email" type="email" defaultValue={String(edit?.email ?? '')} required /></label>
          <label>{edit ? 'Nova senha (opcional)' : 'Senha'}<input type="password" name="password" minLength={12} autoComplete="new-password" required={!edit} /></label>
          <label>Permissão<select name="role" defaultValue={String(edit?.role ?? 'USER')}><option value="USER">Usuário</option><option value="ADMIN">Administrador</option></select></label>
          <label className="check"><input name="enabled" type="checkbox" defaultChecked={edit?.enabled !== false} /> Ativo</label>
          <div className="actions"><Button disabled={pending}>Salvar usuário</Button>{edit && <Button type="button" variant="outline" onClick={() => setEdit(undefined)}>Cancelar</Button>}</div>
        </form>
      </>}
      {section === 'Mídia' && <>
        <form className="actions" onSubmit={event => { event.preventDefault(); setQuery(filter); setPage(0) }}>
          <input aria-label="Buscar mídia" placeholder="Nome ou categoria" value={filter} onChange={event => setFilter(event.target.value)} />
          <Button variant="outline">Buscar</Button>
          <Button type="button" onClick={() => { setEdit(undefined); setCreating(true); setRenaming(false) }}>Adicionar mídia</Button>
        </form>
        <DataTable rows={rows} columns={{ name: 'Nome', category: 'Categoria', kind: 'Tipo', label: 'Lista' }} actions={row => <>
          <Button variant="outline" onClick={() => { setEdit(row); setRenaming(false); setCreating(false) }}>Editar</Button>
          <Button variant="outline" onClick={() => { setEdit(row); setRenaming(true); setCreating(false) }}>Renomear categoria</Button>
          <Button variant="outline" onClick={() => remove('/channels/' + row.id, 'Excluir este item da lista?')}>Excluir</Button>
        </>} />
        <div className="actions"><Button variant="outline" disabled={!page} onClick={() => setPage(value => value - 1)}>Anterior</Button>
          <Button variant="outline" disabled={rows.length < 100} onClick={() => setPage(value => value + 1)}>Próxima</Button></div>
        {(edit || creating) && <form key={String(edit?.id ?? 'new') + renaming} className="admin-form" onSubmit={event => {
          if (renaming) submit(event, '/categories', 'PUT', values => ({ name: edit?.category, playlistId: edit?.playlist_id, replacement: values.group }))
          else submit(event, edit ? '/channels/' + edit.id : '/channels', edit ? 'PUT' : 'POST', values => ({ ...values, browserPlayback: values.browserPlayback === 'on' }))
        }}>
          <h3>{renaming ? 'Renomear categoria na lista' : creating ? 'Novo item' : 'Editar mídia'}</h3>
          {creating && <label>Lista<select name="playlistId" required><option value="">Selecione</option>{lists.data?.map(row => <option key={String(row.id)} value={String(row.id)}>{row.label}</option>)}</select></label>}
          {!renaming && <label>Nome<input name="name" defaultValue={String(edit?.name ?? '')} required /></label>}
          <label>Categoria<input name="group" defaultValue={String(edit?.category ?? '')} required /></label>
          {!renaming && <><label>Tipo<select name="type" defaultValue={String(edit?.kind ?? 'live')}><option value="live">Canal ao vivo</option><option value="movies">Filme</option><option value="series">Série</option></select></label>
            <label>Identificador XMLTV<input name="tvgId" defaultValue={String(edit?.tvg_id ?? '')} /></label>
            <label>{creating ? 'Endereço da mídia' : 'Novo endereço (opcional)'}<input type="url" name="url" required={creating} /></label><label className="check"><input name="browserPlayback" type="checkbox" defaultChecked={edit?.browser_playback === true} /> Vídeo público, reproduzir direto no navegador</label><p className="muted">Ative somente para fontes públicas sem credenciais no endereço e que permitem acesso pelo navegador.</p></>}
          <div className="actions"><Button disabled={pending}>Salvar</Button><Button type="button" variant="outline" onClick={() => { setEdit(undefined); setCreating(false) }}>Cancelar</Button></div>
        </form>}
      </>}
      {section === 'Listas' && <>
        <DataTable rows={rows} columns={{ label: 'Lista', email: 'Proprietário', dns: 'DNS' }} actions={row => <>
          <Button variant="outline" onClick={() => setEdit(row)}>Renomear</Button>
          <Button variant="outline" onClick={() => remove('/lists/' + row.id, 'Excluir esta lista e todos os itens dela?')}>Excluir</Button></>} />
        {edit && <form className="admin-form" key={String(edit.id)} onSubmit={event => submit(event, '/lists/' + edit.id, 'PUT')}>
          <label>Nome da lista<input name="label" defaultValue={String(edit.label)} required /></label><div><Button disabled={pending}>Salvar nome</Button></div>
        </form>}
      </>}
      {section === 'EPG' && <>
        <DataTable rows={rows} columns={{ label: 'Fonte', updated_at: 'Última importação' }} actions={row => <>
          <Button variant="outline" disabled={pending} onClick={() => mutation.mutate({ path: '/epg/' + row.id + '/refresh', method: 'POST' })}>Atualizar XMLTV</Button>
          <Button variant="outline" onClick={() => remove('/epg/' + row.id, 'Excluir esta fonte e sua programação?')}>Excluir</Button></>} />
        <form className="admin-form" onSubmit={event => submit(event, '/epg', 'POST')}>
          <label>Nome da fonte<input name="label" required /></label><label>URL XMLTV<input type="url" name="url" required /></label>
          <div><Button disabled={pending}>Adicionar fonte EPG</Button></div>
        </form>
      </>}
      {section === 'Sessões' && <DataTable rows={rows} columns={{ email: 'Usuário', agent: 'Dispositivo', createdAt: 'Início', expiresAt: 'Expiração' }}
        actions={row => <Button variant="outline" onClick={() => remove('/sessions/' + row.id, 'Encerrar esta sessão?')}>Encerrar sessão</Button>} />}
      {section === 'Configurações' && summary && <form className="admin-form" key={String(summary.name)} onSubmit={event => submit(event, '/settings', 'PUT')}>
        <label>Nome da plataforma<input name="label" defaultValue={String(summary.name)} maxLength={80} required /></label><div><Button disabled={pending}>Salvar</Button></div>
      </form>}
      {section === 'Logs' && <DataTable rows={rows} columns={{ happened_at: 'Data', actor: 'Usuário', action: 'Atividade' }} />}
    </section>}
  </>
}




