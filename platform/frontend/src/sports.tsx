import { useEffect, useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { CalendarDays, Clock3, Globe2, RefreshCw, Search, Trophy, Tv } from 'lucide-react'
import { api, format } from './api'
import { Button } from './components/ui/button'
import { PageHeader } from './components/page-header'

import { canOpenLater, kickoffTime as time, MatchDialog, MatchStatus, MatchTeams, watchOpen, type SportsEvent as Game, type SportsSchedule as Schedule } from './live-sports'

const fullDate = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'full', timeZone: 'America/Sao_Paulo' })
const normalize = (text: string) => text.normalize('NFD').replace(/\p{Diacritic}/gu, '').toLocaleLowerCase('pt-BR')

const sportIcons: Record<string, string> = {
  futebol: '⚽',
  'futebol americano': '🏈',
  nfl: '🏈',
  volei: '🏐',
  'volei de praia': '🏐',
  basquete: '🏀',
  tenis: '🎾',
  squash: '🏸',
  'hoquei no gelo': '🏒',
  automobilismo: '🏎️',
  ciclismo: '🚴',
  natacao: '🏊',
  atletismo: '🏃',
  beisebol: '⚾',
  handebol: '🤾',
  lutas: '🥊',
  boxe: '🥊',
  mma: '🥊',
  rugby: '🏉',
  golfe: '⛳',
  surfe: '🏄',
  skate: '🛹',
  esportes: '🏆',
}

const iconFor = (sport: string) => sportIcons[normalize(sport)] ?? '🏆'

type Group = { title: string; subtitle?: string; games: Game[] }
type Mode = 'competitions' | 'channels'

function groupGames(games: Game[], mode: Mode, timeline: boolean): Group[] {
  if (timeline) return [{ title: 'Ordem de início', games }]
  const grouped = new Map<string, Group>()
  function add(key: string, title: string, game: Game, subtitle?: string) {
    let group = grouped.get(key)
    if (!group) {
      group = { title, subtitle, games: [] }
      grouped.set(key, group)
    }
    group.games.push(game)
  }
  for (const game of games) {
    if (mode === 'competitions') {
      const league = game.league || game.sport
      add(game.sport + '/' + league, league, game, league === game.sport ? undefined : game.sport)
    } else {
      for (const channel of game.channels) add(channel, channel, game)
    }
  }
  const groups = [...grouped.values()]
  if (mode === 'channels') {
    groups.sort((a, b) => a.title.localeCompare(b.title, 'pt-BR'))
    const unconfirmed = games.filter(game => !game.channels.length)
    if (unconfirmed.length) groups.push({ title: 'Transmissão a confirmar', games: unconfirmed })
  }
  return groups
}

function GameRow({ game, showCompetition, now, onWatch }: { game: Game; showCompetition: boolean; now: number; onWatch: () => void }) {
  const blocked = ['cancelled', 'postponed', 'suspended'].includes(game.status)
  return <li className={'sports-event' + (game.status === 'live' ? ' sports-event-live' : '')}>
    {game.kickoff ? <time dateTime={game.kickoff}>{time.format(new Date(game.kickoff))}</time> : <span className="sport-time-unknown">A confirmar</span>}
    <div className="sport-event-meta">
      {game.round && <span>{game.round}</span>}
      <MatchStatus game={game} />
    </div>
    <div className="sport-event-title">
      <MatchTeams game={game} />
      {showCompetition && <p className="muted">{[game.sport, game.league].filter(Boolean).join(' · ')}</p>}
    </div>
    <div className="sport-channels" aria-label="Onde assistir">
      {blocked ? <span className="muted">Confira a programação</span> : game.channels.length
        ? game.channels.map(channel => <span className="sport-badge" key={channel}><Tv aria-hidden="true" />{channel}</span>)
        : <span className="muted">Transmissão a confirmar</span>}
      {watchOpen(game, now) ? <button type="button" className="watch-badge" onClick={onWatch}>Assistir</button>
        : canOpenLater(game, now) && <span className="sport-badge" title={game.opensAt ? 'Abre às ' + time.format(new Date(game.opensAt)) : undefined}>Em breve</span>}
    </div>
  </li>
}

export function SportsGuide({ appearance }: { appearance: ReactNode }) {
  const [sport, setSport] = useState('')
  const [league, setLeague] = useState('')
  const [search, setSearch] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [mode, setMode] = useState<Mode>('competitions')
  const [timeline, setTimeline] = useState(false)
  const [liveOnly, setLiveOnly] = useState(false)
  const [event, setEvent] = useState<string>()
  const [clock, setClock] = useState(Date.now)
  useEffect(() => { const timer = setInterval(() => setClock(Date.now()), 1000); return () => clearInterval(timer) }, [])
  const schedule = useQuery({
    queryKey: ['sports-guide'],
    queryFn: ({ signal }) => api<Schedule>('/api/sports/today', { signal }),
    staleTime: 60_000,
    refetchInterval: 30_000,
  })
  const games = schedule.data?.games ?? []
  const now = schedule.data ? clock + Date.parse(schedule.data.now) - schedule.dataUpdatedAt : clock
  const selected = games.find(game => game.id === event)
  const liveCount = games.filter(game => game.status === 'live' || game.status === 'halftime').length
  const sports = [...new Set(games.map(game => game.sport))].sort((a, b) => a.localeCompare(b, 'pt-BR'))
  const selection = sports.includes(sport) ? sport : ''
  const inSport = games.filter(game => !selection || game.sport === selection)
  const leagues = [...new Set(inSport.map(game => game.league).filter(Boolean))].sort((a, b) => a.localeCompare(b, 'pt-BR'))
  const competition = leagues.includes(league) ? league : ''
  const query = normalize(search.trim())
  const visible = inSport.filter(game => (!competition || game.league === competition)
    && (!confirmed || game.channels.length > 0)
    && (!liveOnly || game.status === 'live' || game.status === 'halftime')
    && normalize([game.title, game.league, game.sport, ...game.channels].join(' ')).includes(query))
  const groups = groupGames(visible, mode, timeline)
  const date = schedule.data ? new Date(schedule.data.date + 'T12:00:00-03:00') : undefined

  return <>
    <PageHeader title="Guia de esportes" description="Eventos de hoje e onde assistir no Brasil." appearance={appearance} actions={
      <Button className="header-tool" variant="ghost" onClick={() => schedule.refetch()} disabled={schedule.isFetching} aria-label="Atualizar agenda" title="Atualizar agenda">
        <RefreshCw aria-hidden="true" className={schedule.isFetching ? 'animate-spin' : undefined} />
      </Button>
    } />
    <section className="panel sports-guide" aria-labelledby="sports-guide-heading">
      <div className="sports-guide-header">
        <h2 id="sports-guide-heading">Onde assistir</h2>
        <span className="sport-badge"><Globe2 aria-hidden="true" />Brasil</span>
      </div>
      <div className="sports-views" role="group" aria-label="Organizar programação">
        <button type="button" aria-pressed={mode === 'competitions' && !timeline} onClick={() => { setMode('competitions'); setTimeline(false) }}><Trophy aria-hidden="true" /> Por competições</button>
        <button type="button" aria-pressed={mode === 'channels' && !timeline} onClick={() => { setMode('channels'); setTimeline(false) }}><Tv aria-hidden="true" /> Por canais</button>
      </div>
      <div className="sports-guide-body">
        {schedule.isPending && <p role="status">Carregando a programação…</p>}
        {schedule.error && <p role="alert" className="error">{schedule.error.message}</p>}
        {schedule.data && <>
          {schedule.data.stale && <p role="status" className="muted">A fonte está indisponível. Exibindo a última programação consultada hoje.</p>}
          <div className="sports-toolbar">
            <select aria-label="Filtrar competição" value={competition} onChange={event => setLeague(event.target.value)}>
              <option value="">Todas as competições</option>{leagues.map(item => <option key={item}>{item}</option>)}
            </select>
            <button className="sports-time-toggle" type="button" aria-pressed={timeline} onClick={() => setTimeline(value => !value)}><Clock3 aria-hidden="true" /> Por horário</button>
            <button className="sports-time-toggle" type="button" aria-pressed={liveOnly} onClick={() => setLiveOnly(value => !value)}>Ao vivo <span className="sport-badge">{liveCount}</span></button>
            {date && <time className="sports-date" dateTime={schedule.data.date} title={fullDate.format(date)}><CalendarDays aria-hidden="true" /> {fullDate.format(date)}</time>}
          </div>
          <div className="sports-filters">
            <label className="search"><Search aria-hidden="true" /><input type="search" placeholder="Buscar evento ou canal" aria-label="Buscar evento, competição ou canal" value={search} onChange={event => setSearch(event.target.value)} /></label>
            <select aria-label="Filtrar esporte" value={selection} onChange={event => { setSport(event.target.value); setLeague('') }}>
              <option value="" data-icon="🏆">Todos os esportes</option>{sports.map(item => <option value={item} data-icon={iconFor(item)} key={item}>{item}</option>)}
            </select>
            <label className="check"><input type="checkbox" checked={confirmed} onChange={event => setConfirmed(event.target.checked)} /> Com transmissão informada</label>
          </div>
          <div className="sports-summary"><span>Horário de Brasília</span><span>{format.format(visible.length)} {visible.length === 1 ? 'evento' : 'eventos'}</span></div>
          {visible.length > 0 && groups.map((group, index) => <section className="sport-card" key={index}>
            <h3>{timeline ? <Clock3 aria-hidden="true" /> : mode === 'channels' ? <Tv aria-hidden="true" /> : <span className="sport-emblem" aria-hidden="true">{iconFor(group.games[0].sport)}</span>}
              <span>{group.subtitle && <span className="sport-card-sport">{group.subtitle} / </span>}{group.title}</span>{!timeline && mode === 'competitions' && <Trophy className="sport-card-trophy" aria-hidden="true" />}</h3>
            <ul>{group.games.map(game => <GameRow key={game.id} game={game} showCompetition={timeline || mode === 'channels'} now={now} onWatch={() => setEvent(game.id)} />)}</ul>
          </section>)}
          {!visible.length && <p className="empty">{games.length ? 'Nenhum evento corresponde aos filtros.' : 'Nenhum evento listado para hoje.'}</p>}
          {schedule.data.scoreError && <p role="status" className="muted">{schedule.data.scoreError}</p>}
          <p className="muted sports-source">Fonte <a href={schedule.data.source} target="_blank" rel="noreferrer">Sportune</a> · Placar <a href="https://www.espn.com.br/futebol/resultados" target="_blank" rel="noreferrer">ESPN</a> · Consultado às {time.format(new Date(schedule.data.updatedAt))}</p>
        </>}
      </div>
    </section>
    {selected && <MatchDialog key={selected.id} game={selected} active={watchOpen(selected, now)} onClose={() => setEvent(undefined)} />}
  </>
}







