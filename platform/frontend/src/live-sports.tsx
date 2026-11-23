import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Play, X } from 'lucide-react'
import { api, type Entry } from './api'
import { Button } from './components/ui/button'
import { Player } from './library'

export type Team = { name: string; badge: string | null; score: string | null }
export type Score = { id: string; home: Team; away: Team; minute: string | null }
export type SportsEvent = {
  id: string; title: string; sport: string; league: string; round: string | null; kickoff: string | null
  status: 'scheduled' | 'live' | 'halftime' | 'finished' | 'suspended' | 'cancelled' | 'postponed'
  channels: string[]; score: Score | null; opensAt: string | null; availableChannels: number
}
export type SportsSchedule = {
  date: string; updatedAt: string; now: string; stale: boolean; source: string; scoreError: string | null
  leadMinutes: number; games: SportsEvent[]
}

const statuses = {
  scheduled: 'Agendado', live: 'AO VIVO', halftime: 'Intervalo', finished: 'Encerrado',
  suspended: 'Suspenso', cancelled: 'Cancelado', postponed: 'Adiado',
}

export const kickoffTime = new Intl.DateTimeFormat('pt-BR', { hour: '2-digit', minute: '2-digit', timeZone: 'America/Sao_Paulo' })

export function watchOpen(game: SportsEvent, now: number) {
  if (!game.availableChannels) return false
  if (game.status === 'live' || game.status === 'halftime') return true
  return game.status === 'scheduled' && game.opensAt !== null && game.kickoff !== null
    && now >= Date.parse(game.opensAt) && now < Date.parse(game.kickoff)
}

export function canOpenLater(game: SportsEvent, now: number) {
  return game.availableChannels > 0 && game.status === 'scheduled' && game.opensAt !== null && now < Date.parse(game.opensAt)
}

export function MatchStatus({ game }: { game: SportsEvent }) {
  return <span className={'sport-badge match-status status-' + game.status}>
    {statuses[game.status]}{game.score?.minute ? ' · ' + game.score.minute : ''}
  </span>
}

export function MatchTeams({ game }: { game: SportsEvent }) {
  const competitors = game.title.split(/\s+×\s+/)
  const teams = game.score ? [game.score.home, game.score.away]
    : competitors.length === 2 ? competitors.map(name => ({ name, badge: null, score: null })) : []
  if (!teams.length) return <strong>{game.title}</strong>
  return <div className="sport-competitors">{teams.map((side, index) => <div className="sport-team" key={index}>
    {side.badge && <img src={side.badge} width="24" height="24" alt="" loading="lazy" referrerPolicy="no-referrer" />}
    <span>{side.name}</span>{side.score !== null && <strong className="sport-score">{side.score}</strong>}
  </div>)}</div>
}

export function MatchDialog({ game, active, onClose }: { game: SportsEvent; active: boolean; onClose: () => void }) {
  const ref = useRef<HTMLDialogElement>(null)
  const [playing, setPlaying] = useState<Entry>()
  const choices = useQuery({
    queryKey: ['sports-options', game.id],
    enabled: active,
    queryFn: () => api<Entry[]>('/api/sports/options?' + new URLSearchParams({ event: game.id })),
    refetchInterval: 30_000,
    retry: false,
  })
  useEffect(() => {
    const dialog = ref.current
    if (!dialog) return
    dialog.showModal()
    return () => dialog.close()
  }, [])
  return <dialog ref={ref} className="match-dialog" aria-labelledby="match-dialog-title" onCancel={event => { event.preventDefault(); onClose() }}>
    <div className="dialog-head">
      <div><h2 id="match-dialog-title">{game.league || game.sport}</h2><p className="muted">{[game.round, game.kickoff && kickoffTime.format(new Date(game.kickoff))].filter(Boolean).join(' · ')}</p></div>
      <Button type="button" variant="ghost" aria-label="Fechar" onClick={onClose}><X /></Button>
    </div>
    <div className="match-dialog-teams"><MatchTeams game={game} /><MatchStatus game={game} /></div>
    {playing ? <>
      <Player entry={playing} />
      <div className="actions match-dialog-actions"><Button variant="outline" onClick={() => setPlaying(undefined)}>Voltar aos canais</Button><Button variant="outline" onClick={onClose}>Voltar ao guia</Button></div>
    </> : <>
      <h3>Canais disponíveis na sua lista</h3>
      <p className="muted">A programação pode variar entre as versões da emissora.</p>
      {!active && <p role="status">A janela de transmissão está fechada.</p>}
      {active && choices.isPending && <p role="status">Buscando canais…</p>}
      {active && choices.error && <p className="error" role="alert">{choices.error.message}</p>}
      {active && !choices.error && <div className="items">{choices.data?.map(channel => <div className="row" key={channel.id}>
        <button type="button" className="channel" onClick={() => setPlaying(channel)}><Play aria-hidden="true" /><span>{channel.name}<small>{channel.group}</small></span><span className="watch-action">Assistir</span></button>
      </div>)}</div>}
    </>}
  </dialog>
}
