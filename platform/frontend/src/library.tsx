import { useEffect, useState, useRef, type FormEvent, type ReactNode } from 'react'
import type Hls from 'hls.js'
import { useSearchParams } from 'react-router-dom'
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Film, Heart, Layers, Play, Plus, Search, Signal, Tv, X } from 'lucide-react'
import { api, format, json, type Entry, type Group, type NetworkOptions, type Playlist } from './api'
import { Button } from './components/ui/button'
import { NetworkSettings } from './network'
import { PageHeader } from './components/page-header'
import { SeriesCard, SeriesEpisodes, type SeriesTitle } from './series'

const categoriesPerPage = 60

const kinds = [
  { key: 'live', label: 'Canais ao vivo', icon: Tv, color: 'blue' },
  { key: 'movies', label: 'Filmes', icon: Film, color: 'purple' },
  { key: 'series', label: 'Séries', icon: Layers, color: 'cyan' },
  { key: 'favorites', label: 'Favoritos', icon: Heart, color: 'pink' },
] as const

function ImportDialog({ onClose, onImport }: { onClose: () => void; onImport: (id: string) => void }) {
  const ref = useRef<HTMLDialogElement>(null)
  const [mode, setMode] = useState('xtream')
  const options = useQuery({ queryKey: ['network'], queryFn: () => api<NetworkOptions>('/api/network') })
  const [dns, setDns] = useState('')
  useEffect(() => { ref.current?.showModal() }, [])
  const upload = useMutation({ mutationFn: (input: FormData) => {
    const chosen = dns || options.data?.preference.dns
    if (mode === 'file') {
      const file = input.get('file')
      if (!(file instanceof File) || !file.size) throw new Error('Selecione um arquivo M3U.')
      if (file.size > 1024 ** 3) throw new Error('A lista excede o limite de 1 GB.')
      const search = new URLSearchParams({ label: file.name })
      if (chosen) search.set('dns', chosen)
      return api<{ id: string }>('/api/lists?' + search, {
        method: 'POST', headers: { 'Content-Type': 'application/vnd.apple.mpegurl' }, body: file,
      })
    }
    return api<{ id: string }>('/api/lists', json('POST', { ...Object.fromEntries(input), mode, dns: chosen }))
  }, onSuccess: result => onImport(result.id) })
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    upload.mutate(new FormData(event.currentTarget))
  }
  return <dialog ref={ref} onCancel={event => { event.preventDefault(); if (!upload.isPending) onClose() }}>
    <div className="dialog-head"><h2>Adicionar lista</h2><Button type="button" variant="ghost" aria-label="Fechar" disabled={upload.isPending} onClick={onClose}><X /></Button></div>
    <form onSubmit={submit} className="stack">
      <label>Tipo de conexão<select value={mode} onChange={event => setMode(event.target.value)} disabled={upload.isPending}><option value="xtream">Servidor Xtream Codes</option><option value="url">Link M3U / M3U8</option><option value="file">Arquivo M3U</option></select></label>
      {mode === 'file' ? <label>Arquivo<input name="file" type="file" accept=".m3u,.m3u8" required /></label>
        : <label>{mode === 'xtream' ? 'Endereço do servidor' : 'Endereço da lista'}<input name="url" type="url" placeholder="https://servidor.exemplo:8080" required /></label>}
      {mode === 'xtream' && <div className="credentials"><label>Usuário<input name="username" autoComplete="off" required /></label><label>Senha<input name="password" type="password" autoComplete="off" required /></label></div>}
      {options.data && <label>DNS da conexão<select value={dns || options.data.preference.dns} onChange={event => setDns(event.target.value)}>
        <option value="cloudflare" disabled={!options.data.allowCloudflare}>Cloudflare (DNS sobre HTTPS)</option>
        <option value="system" disabled={!options.data.allowSystem}>DNS automático</option>
      </select></label>}
      {upload.error && <p className="error" role="alert">{upload.error.message}</p>}
      <Button type="submit" disabled={upload.isPending}>{upload.isPending ? 'Importando lista…' : 'Carregar lista'}</Button>
    </form>
  </dialog>
}

export function Player({ entry }: { entry?: Entry }) {
  const video = useRef<HTMLVideoElement>(null)
  const program = useQuery({ queryKey: ['epg-now', entry?.id], enabled: !!entry?.tvgId, queryFn: () => api<{ title: string; starts_at: string; ends_at: string }[]>('/api/epg/' + entry?.id + '/now'), refetchInterval: 60_000 })
  const [state, setState] = useState('Em espera')
  const [detail, setDetail] = useState('')
  const [retryKey, setRetryKey] = useState(0)
  const current = useRef<{ id: string; time: number } | null>(null)
  useEffect(() => {
    const media = video.current
    if (!media || !entry) return
    let stopped = false
    let hls: Hls | undefined
    let timer: ReturnType<typeof setTimeout> | undefined
    let attempts = 0
    let stable: ReturnType<typeof setTimeout> | undefined
    const stop = new AbortController()
    const teardown = () => {
      hls?.destroy()
      hls = undefined
      media.onerror = null
      media.pause()
      media.removeAttribute('src')
      media.load()
    }
    function retry() {
      if (stopped || timer) return
      clearTimeout(stable)
      if (attempts >= 3) {
        setState('Indisponível')
        setDetail('Confira a conexão ou selecione outro item.')
        return
      }
      setState('Reconectando')
      setDetail('Falha transitória na reprodução.')
      timer = setTimeout(() => { timer = undefined; start() }, 1000 * 2 ** attempts++)
    }
    function play() {
      media!.play().catch(error => {
        if (stopped) return
        if (error.name === 'NotAllowedError') { setState('Pronto'); setDetail('Use o botão de reprodução do vídeo.') }
      })
    }
    async function start() {
      teardown()
      setState(attempts ? 'Reconectando' : 'Carregando')
      setDetail('')
      try {
        const source = await api<{ url: string; hls: boolean }>('/api/playback/' + entry!.id, { signal: stop.signal })
        if (stopped) return
        media!.onplaying = () => {
          setState('Reproduzindo'); setDetail('')
          clearTimeout(stable)
          stable = setTimeout(() => { attempts = 0 }, 30_000)
        }
        media!.onerror = () => {
          if (media!.error?.code === MediaError.MEDIA_ERR_NETWORK) retry()
          else { setState('Indisponível'); setDetail('O navegador não conseguiu reproduzir esta mídia.') }
        }
        media!.onloadedmetadata = () => {
          if (entry!.type !== 'live' && current.current?.id === entry!.id && Number.isFinite(media!.duration))
            media!.currentTime = Math.min(current.current.time, media!.duration)
        }
        media!.ontimeupdate = () => { current.current = { id: entry!.id, time: media!.currentTime } }
        if (source.hls && !media!.canPlayType('application/vnd.apple.mpegurl')) {
          const { default: HlsPlayer, ErrorTypes } = await import('hls.js')
          if (stopped) return
          if (HlsPlayer.isSupported()) {
            hls = new HlsPlayer()
            hls.on(HlsPlayer.Events.MANIFEST_PARSED, play)
            hls.on(HlsPlayer.Events.ERROR, (_, data) => {
              if (!data.fatal) return
              if (data.type === ErrorTypes.NETWORK_ERROR) retry()
              else { setState('Indisponível'); setDetail('O formato ou codec da mídia pode ser incompatível.') }
            })
            hls.loadSource(source.url)
            hls.attachMedia(media!)
            return
          }
        }
        media!.src = source.url
        play()
      } catch (error) {
        if (stopped) return
        if (typeof error === 'object' && error && 'status' in error && Number(error.status) < 500) {
          setState('Indisponível'); setDetail(error instanceof Error ? error.message : 'Fonte indisponível.')
        } else retry()
      }
    }
    function online() { if (attempts > 0 || timer) { clearTimeout(timer); timer = undefined; attempts = 0; start() } }
    window.addEventListener('online', online)
    start()
    return () => { stopped = true; stop.abort(); clearTimeout(timer); clearTimeout(stable); teardown(); window.removeEventListener('online', online) }
  }, [entry, retryKey])

  return <section className="panel player"><div className="panel-title"><h2>Reprodutor</h2><span className="player-status">{entry ? state : 'Em espera'}</span></div>
    <div className="screen">{entry ? <video ref={video} controls playsInline /> : <div className="placeholder"><div className="orbit"><Play /></div><h3>Pronto para assistir?</h3><p>Adicione uma lista e selecione um canal.</p></div>}</div>
    <div className="now"><span className="tile"><Signal /></span><div><strong>{entry?.name ?? 'Nenhum canal selecionado'}</strong><p aria-live="polite">{entry ? detail || entry.group : 'O conteúdo selecionado será exibido aqui.'}</p>{program.data?.[0] && <p>{program.data[0].title}</p>}</div>
      {entry && state === 'Indisponível' && <Button variant="outline" onClick={() => setRetryKey(value => value + 1)}>Reconectar</Button>}</div>
  </section>
}

export function Library({ appearance }: { appearance: ReactNode }) {
  const cache = useQueryClient()
  const [importing, setImporting] = useState(false)
  const [chosen, setChosen] = useState('')
  const [params, setParams] = useSearchParams()
  const requested = params.get('view')
  const view = kinds.some(kind => kind.key === requested) ? requested! : 'all'
  const [category, setCategory] = useState('')
  const [categoryQuery, setCategoryQuery] = useState('')
  const [categoryLimit, setCategoryLimit] = useState(categoriesPerPage)
  const [search, setSearch] = useState('')
  const [term, setTerm] = useState('')
  const [selected, setSelected] = useState<Entry>()
  const [diagnosing, setDiagnosing] = useState(false)
  const [openSeries, setOpenSeries] = useState<{ item: SeriesTitle; scope: string }>()
  useEffect(() => { const timer = setTimeout(() => setTerm(search.trim()), 250); return () => clearTimeout(timer) }, [search])
  const lists = useQuery({ queryKey: ['lists'], queryFn: () => api<Playlist[]>('/api/lists') })
  const list = chosen || lists.data?.[0]?.id || ''
  const path = '/api/lists/' + list
  const groups = useQuery({ queryKey: ['groups', list, view], enabled: !!list, queryFn: () => api<Group[]>(path + '/categories?' + new URLSearchParams({ view })) })
  const stats = useQuery({ queryKey: ['stats', list], enabled: !!list, queryFn: () => api<Record<string, number>>(path + '/stats') })
  const items = useInfiniteQuery({
    queryKey: ['entries', list, view, category, term], enabled: !!list && view !== 'series', initialPageParam: 0,
    queryFn: ({ pageParam }) => api<{ entries: Entry[]; total: number }>(path + '/entries?' + new URLSearchParams({ view, group: category, q: term, page: String(pageParam) })),
    getNextPageParam: (last, pages) => pages.length * 100 < last.total ? pages.length : undefined,
  })
  const series = useInfiniteQuery({
    queryKey: ['series', list, category, term], enabled: !!list && view === 'series', initialPageParam: 0,
    queryFn: ({ pageParam }) => api<{ series: SeriesTitle[]; total: number }>(path + '/series?' + new URLSearchParams({ group: category, q: term, page: String(pageParam) })),
    getNextPageParam: (last, pages) => pages.length * 100 < last.total ? pages.length : undefined,
  })
  const seriesScope = JSON.stringify([list, view, category, term])
  const opened = openSeries?.scope === seriesScope ? openSeries.item : undefined
  const titles = series.data?.pages.flatMap(page => page.series) ?? []
  const visibleItems = view === 'series' ? series : items
  const favorite = useMutation({ mutationFn: (entry: Entry) => api<void>('/api/favorites/' + entry.id, { method: entry.favorite ? 'DELETE' : 'PUT' }), onSuccess: () => {
    cache.invalidateQueries({ queryKey: ['entries', list] })
    cache.invalidateQueries({ queryKey: ['series-episodes', list] })
    cache.invalidateQueries({ queryKey: ['stats', list] })
    cache.invalidateQueries({ queryKey: ['groups', list, 'favorites'] })
  } })
  useEffect(() => {
    setCategory(''); setCategoryQuery(''); setCategoryLimit(categoriesPerPage)
  }, [view])
  function changeView(next: string) { setParams(next === 'all' ? {} : { view: next }) }
  function changeList(id: string) { setChosen(id); changeView('all'); setCategory(''); setCategoryQuery(''); setCategoryLimit(categoriesPerPage); setSelected(undefined); setSearch(''); setTerm('') }
  const options = groups.data ?? []
  const visible = options.filter(group => group.name.toLocaleLowerCase('pt-BR').includes(categoryQuery.toLocaleLowerCase('pt-BR').trim()))
  const displayed = visible.slice(0, categoryLimit)
  const active = options.find(group => group.name === category)
  const entries = items.data?.pages.flatMap(page => page.entries) ?? []
  const errors = lists.error || groups.error || visibleItems.error || favorite.error

  return <>
    <PageHeader title="Painel IPTV" description="Gerencie e assista ao conteúdo da sua lista." appearance={appearance} actions={<>
      {!!list && <select aria-label="Lista ativa" value={list} onChange={event => changeList(event.target.value)}>
        {!lists.data?.some(item => item.id === list) && <option value={list}>Lista carregada</option>}
        {lists.data?.map(item => <option key={item.id} value={item.id}>{item.label}</option>)}</select>}
        <Button className="header-primary" onClick={() => setImporting(true)}><Plus /> Adicionar lista</Button></>} />
    <div className="stats">{kinds.map(({ key, label, icon: Icon, color }) => <button key={key} type="button" className={'stat ' + color} aria-pressed={view === key} onClick={() => changeView(view === key ? 'all' : key)}>
      <span className="tile"><Icon /></span><span>{label}<strong>{format.format(stats.data?.[key] ?? 0)}</strong></span>
    </button>)}</div>
    {errors && <p className="error" role="alert">{errors.message}</p>}
    {list && <section className="panel categories"><div className="panel-title"><h2>Categorias</h2>{options.length > 0 && <label className="search"><Search /><input type="search" aria-label="Buscar categoria" placeholder="Buscar categoria" value={categoryQuery} onChange={event => { setCategoryQuery(event.target.value); setCategoryLimit(categoriesPerPage) }} /></label>}</div>
      <div className="category-list" role="group" aria-label="Filtrar conteúdo por categoria">
        <button className="category" aria-pressed={!category} onClick={() => setCategory('')}><span className="category-name">Todas</span><span className="category-count">{format.format(view === 'series' ? stats.data?.series ?? 0 : options.reduce((sum, group) => sum + group.count, 0))}</span></button>
        {[...(active && !displayed.includes(active) ? [active] : []), ...displayed].map(group => <button className="category" key={group.name} aria-pressed={category === group.name} onClick={() => setCategory(group.name)} title={group.name}><span className="category-name">{group.name}</span><span className="category-count">{format.format(group.count)}</span></button>)}
        {visible.length > categoryLimit && <Button variant="outline" className="category-more" onClick={() => setCategoryLimit(value => value + categoriesPerPage)}>Mostrar mais categorias</Button>}
        {!visible.length && categoryQuery && <p className="category-empty">Nenhuma categoria encontrada.</p>}
      </div></section>}
    <div className="dashboard"><Player entry={selected} />
      <section className="panel library"><div className="panel-title"><h2>{kinds.find(kind => kind.key === view)?.label ?? 'Todos os canais'}</h2><span className="player-status">{format.format(visibleItems.data?.pages[0]?.total ?? 0)} {view === 'series' ? 'séries' : 'itens'}</span></div>
        <label className="search"><Search /><input type="search" aria-label="Buscar na biblioteca" placeholder={view === 'series' ? 'Buscar série ou episódio' : 'Buscar na biblioteca'} value={search} onChange={event => setSearch(event.target.value)} /></label>
        <div className="items">
          {view === 'series' ? opened
            ? <SeriesEpisodes key={seriesScope + opened.key} list={list} group={category} series={opened} selectedId={selected?.id}
                onBack={() => setOpenSeries(undefined)} onPlay={setSelected} onFavorite={favorite.mutate} favoritePending={favorite.isPending} />
            : titles.map(title => <SeriesCard key={title.key} series={title} onOpen={() => setOpenSeries({ item: title, scope: seriesScope })} />)
            : entries.map(entry => <div className={'row' + (entry.id === selected?.id ? ' current' : '')} key={entry.id}>
          <button className="channel" type="button" onClick={() => setSelected(entry)}><Play /><span>{entry.name}<small>{entry.group}</small></span></button>
          <Button type="button" variant="ghost" aria-label={(entry.favorite ? 'Remover dos favoritos: ' : 'Adicionar aos favoritos: ') + entry.name} aria-pressed={entry.favorite} disabled={favorite.isPending} onClick={() => favorite.mutate(entry)}><Heart /></Button>
        </div>)}
          {!(view === 'series' ? titles.length : entries.length) && <div className="empty"><Tv /><strong>{!list ? 'Sua biblioteca está vazia' : visibleItems.isFetching ? 'Carregando…' : 'Nenhum resultado'}</strong>{!list && <Button onClick={() => setImporting(true)}>Importar lista</Button>}</div>}
          {!opened && visibleItems.hasNextPage && <Button variant="outline" disabled={visibleItems.isFetchingNextPage} onClick={() => visibleItems.fetchNextPage()}>Mostrar mais</Button>}
        </div>
      </section></div>
    {selected && <div className="network-shortcut"><Button variant="outline" onClick={() => setDiagnosing(value => !value)}>{diagnosing ? 'Fechar diagnóstico' : 'Diagnosticar reprodução'}</Button></div>}
    {selected && diagnosing && <NetworkSettings channel={selected.id} />}
    {importing && <ImportDialog onClose={() => setImporting(false)} onImport={id => { changeList(id); setImporting(false); cache.invalidateQueries({ queryKey: ['lists'] }) }} />}
  </>
}







