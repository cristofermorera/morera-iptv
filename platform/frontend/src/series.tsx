import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ChevronLeft, ChevronRight, Heart, Layers, Play } from 'lucide-react'
import { api, format, type Entry } from './api'
import { Button } from './components/ui/button'

export type SeriesTitle = { key: string; title: string; seasons: number; episodes: number }
type Episode = Entry & { season: number | null; episode: number | null }

export function SeriesCard({ series, onOpen }: { series: SeriesTitle; onOpen: () => void }) {
  return <button className="series-card" type="button" onClick={onOpen}>
    <span className="tile"><Layers aria-hidden="true" /></span>
    <span className="series-card-title"><strong>{series.title}</strong><small>
      {series.seasons > 0 && <>{format.format(series.seasons)} {series.seasons === 1 ? 'temporada' : 'temporadas'} · </>}
      {format.format(series.episodes)} {series.episodes === 1 ? 'episódio' : 'episódios'}
    </small></span>
    <ChevronRight aria-hidden="true" />
  </button>
}

export function SeriesEpisodes({ list, group, series, selectedId, onBack, onPlay, onFavorite, favoritePending }: {
  list: string
  group: string
  series: SeriesTitle
  selectedId?: string
  onBack: () => void
  onPlay: (entry: Entry) => void
  onFavorite: (entry: Entry) => void
  favoritePending: boolean
}) {
  const [season, setSeason] = useState<number | null>()
  const result = useQuery({
    queryKey: ['series-episodes', list, group, series.key],
    queryFn: () => api<Episode[]>('/api/lists/' + list + '/series/episodes?' + new URLSearchParams({ key: series.key, group })),
  })
  const seasons = new Map<number | null, Episode[]>()
  for (const episode of result.data ?? []) {
    if (!seasons.has(episode.season)) seasons.set(episode.season, [])
    seasons.get(episode.season)!.push(episode)
  }
  const active = season !== undefined && seasons.has(season) ? season : seasons.keys().next().value
  const episodes = active === undefined ? [] : seasons.get(active) ?? []
  return <div className="series-detail">
    <Button variant="outline" onClick={onBack}><ChevronLeft aria-hidden="true" /> Voltar às séries</Button>
    <h3>{series.title}</h3>
    {result.isPending && <p role="status">Carregando episódios…</p>}
    {result.error && <p role="alert" className="error">{result.error.message}</p>}
    {seasons.size > 0 && <label className="season-picker">Temporada
      <select value={active === null ? 'unknown' : active} onChange={event => setSeason(event.target.value === 'unknown' ? null : Number(event.target.value))}>
        {[...seasons].map(([number, items]) => <option key={number ?? 'unknown'} value={number ?? 'unknown'}>
          {number === null ? 'Sem temporada informada' : 'Temporada ' + number} ({format.format(items.length)})
        </option>)}
      </select>
    </label>}
    {episodes.map(entry => <div className={'row' + (entry.id === selectedId ? ' current' : '')} key={entry.id}>
      <button className="channel" type="button" title={entry.name} onClick={() => onPlay(entry)}><Play aria-hidden="true" /><span>
        {entry.episode === null ? entry.name : 'Episódio ' + entry.episode}
        <small>{entry.name}</small>
      </span></button>
      <Button type="button" variant="ghost" aria-label={(entry.favorite ? 'Remover dos favoritos ' : 'Adicionar aos favoritos ') + entry.name}
        aria-pressed={entry.favorite} disabled={favoritePending} onClick={() => onFavorite(entry)}><Heart aria-hidden="true" /></Button>
    </div>)}
  </div>
}
