import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, json, type Entry } from './api'
import { Button } from './components/ui/button'
import { kickoffTime, MatchStatus, type SportsEvent, type SportsSchedule } from './live-sports'

type Alias = { alias: string; broadcaster: string }
type Correction = { channelId: string; name: string; group: string; allowed: boolean }
type Review = { event: SportsEvent; matches: Entry[]; corrections: Correction[] }
type Media = { id: string; name: string; kind: string; email?: string; label: string }
type Operation = { path: string; method: 'PUT' | 'DELETE'; body?: object }

export function SportsAdmin() {
  const client = useQueryClient()
  const [eventId, setEventId] = useState('')
  const [aliasEdit, setAliasEdit] = useState<Alias>()
  const [channelSearch, setChannelSearch] = useState('')
  const [filter, setFilter] = useState('')
  const [channel, setChannel] = useState('')
  const [minutes, setMinutes] = useState<number>()
  const agenda = useQuery({ queryKey: ['sports-guide'], queryFn: () => api<SportsSchedule>('/api/sports/today'), refetchInterval: 30_000 })
  const aliases = useQuery({ queryKey: ['admin', 'sports-aliases'], queryFn: () => api<Alias[]>('/api/admin/sports/aliases') })
  const review = useQuery({
    queryKey: ['admin', 'sports-review', eventId], enabled: !!eventId,
    queryFn: () => api<Review>('/api/admin/sports/review?' + new URLSearchParams({ event: eventId })),
  })
  const channels = useQuery({
    queryKey: ['admin', 'sports-channels', filter], enabled: !!eventId && !!filter,
    queryFn: () => api<Media[]>('/api/admin/channels?' + new URLSearchParams({ q: filter, kind: 'live' })),
  })
  const update = useMutation({
    mutationFn: ({ path, method, body }: Operation) => api<void>('/api/admin/sports/' + path, body ? json(method, body) : { method }),
    onSuccess: async (_, { path }) => {
      if (path === 'aliases') setAliasEdit(undefined)
      if (path === 'window') setMinutes(undefined)
      await Promise.all([
        client.invalidateQueries({ queryKey: ['admin', 'sports-aliases'] }),
        client.invalidateQueries({ queryKey: ['admin', 'sports-review'] }),
        client.invalidateQueries({ queryKey: ['sports-guide'] }),
        client.invalidateQueries({ queryKey: ['sports-options'] }),
      ])
    },
  })
  const error = agenda.error || aliases.error || review.error || channels.error || update.error
  const chosen = review.data
  function saveAlias(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    update.mutate({ path: 'aliases', method: 'PUT', body: Object.fromEntries(new FormData(event.currentTarget)) })
  }
  return <section className="panel stack">
    <h2>Transmissões Esportivas</h2>
    {error && <p className="error" role="alert">{error.message}</p>}
    {agenda.data?.scoreError && <p className="error" role="status">{agenda.data.scoreError}</p>}
    {agenda.data?.stale && <p role="status">Agenda da fonte indisponível. Exibindo a última consulta de hoje.</p>}
    {update.isSuccess && <p role="status">Alterações salvas.</p>}
    {agenda.data && <>
      <form className="admin-form" onSubmit={event => {
        event.preventDefault()
        if (minutes !== undefined) update.mutate({ path: 'window', method: 'PUT', body: { minutes } })
      }}>
        <label>Antecedência do botão Assistir (minutos)<input type="number" min="0" max="120" step="1" required value={minutes ?? agenda.data.leadMinutes} onChange={event => setMinutes(event.currentTarget.valueAsNumber)} /></label>
        <div><Button disabled={update.isPending || minutes === undefined}>Salvar antecedência</Button></div>
      </form>
      <label>Evento<select value={eventId} onChange={event => { setEventId(event.target.value); setChannel(''); setFilter(''); setChannelSearch('') }}>
        <option value="">Selecione para revisar</option>
        {agenda.data.games.map(game => <option key={game.id} value={game.id}>
          {game.kickoff ? kickoffTime.format(new Date(game.kickoff)) + ' · ' : ''}{game.title} · {game.availableChannels ? game.availableChannels + ' canal(is)' : 'Sem canais'}
        </option>)}
      </select></label>
      {eventId && review.isLoading && <p role="status">Consultando correspondências…</p>}
      {chosen && <>
        <div className="actions"><strong>{chosen.event.title}</strong><MatchStatus game={chosen.event} /></div>
        <p className="muted">Emissoras informadas · {chosen.event.channels.join(', ') || 'A confirmar'}</p>
        <h3>Canais identificados nas suas listas</h3>
        {chosen.matches.length ? <ul className="stack">{chosen.matches.map(item => <li key={item.id} className="actions">
          <span>{item.name} · {item.group}</span>
          <Button variant="outline" disabled={update.isPending} onClick={() => update.mutate({ path: 'override', method: 'PUT', body: { event: eventId, channel: item.id, allowed: false } })}>Excluir deste evento</Button>
        </li>)}</ul> : <p className="muted">Nenhum canal correspondente.</p>}
        <form className="actions" onSubmit={event => { event.preventDefault(); setChannel(''); setFilter(channelSearch.trim()) }}>
          <input aria-label="Buscar canal ao vivo" placeholder="Nome do canal IPTV" value={channelSearch} onChange={event => setChannelSearch(event.target.value)} />
          <Button variant="outline" type="submit" disabled={!channelSearch.trim()}>Buscar canal</Button>
        </form>
        {filter && <div className="actions"><select aria-label="Canal para associar" value={channel} onChange={event => setChannel(event.target.value)}>
          <option value="">{channels.isFetching ? 'Buscando…' : 'Selecione um canal'}</option>
          {channels.data?.filter(item => item.kind === 'live').map(item => <option key={item.id} value={item.id}>{item.name} · {item.label}</option>)}
        </select>
          <Button disabled={!channel || update.isPending} onClick={() => update.mutate({ path: 'override', method: 'PUT', body: { event: eventId, channel, allowed: true } })}>Associar ao evento</Button>
        </div>}
        {chosen.corrections.length > 0 && <div className="stack"><h3>Correções manuais</h3>
          {chosen.corrections.map(item => <div className="actions" key={item.channelId}><span>{item.name} · {item.allowed ? 'Incluído' : 'Excluído'}</span>
            <Button variant="outline" disabled={update.isPending} onClick={() => update.mutate({ path: 'override?' + new URLSearchParams({ event: eventId, channel: item.channelId }), method: 'DELETE' })}>Remover correção</Button></div>)}
        </div>}
      </>}
    </>}
    <form className="admin-form" key={aliasEdit?.alias ?? 'new'} onSubmit={saveAlias}>
      <h3>Aliases de emissoras</h3>
      <label>Nome na lista<input name="alias" maxLength={140} required defaultValue={aliasEdit?.alias} readOnly={!!aliasEdit} /></label>
      <label>Emissora equivalente<input name="broadcaster" maxLength={140} required defaultValue={aliasEdit?.broadcaster} /></label>
      <div className="actions"><Button type="submit" disabled={update.isPending}>Salvar alias</Button>
        {aliasEdit && <Button type="button" variant="outline" onClick={() => setAliasEdit(undefined)}>Cancelar</Button>}</div>
    </form>
    {aliases.data?.map(alias => <div className="actions" key={alias.alias}><span>{alias.alias} · {alias.broadcaster}</span>
      <Button variant="outline" onClick={() => setAliasEdit(alias)}>Editar</Button>
      <Button variant="outline" disabled={update.isPending} onClick={() => update.mutate({ path: 'aliases?' + new URLSearchParams({ alias: alias.alias }), method: 'DELETE' })}>Excluir</Button></div>)}
  </section>
}
