import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Activity, Gauge, Network, Router, ShieldCheck } from 'lucide-react'
import { api, json, type Check, type Connection, type Dns, type NetworkOptions, type NetworkPolicy } from './api'
import { Button } from './components/ui/button'

export const connectionNames = { direct: 'Direta', warp: 'Cloudflare WARP', wireguard: 'WireGuard' }

export function NetworkSettings({ channel }: { channel?: string }) {
  const cache = useQueryClient()
  const options = useQuery({ queryKey: ['network'], queryFn: () => api<NetworkOptions>('/api/network') })
  const [target, setTarget] = useState('')
  const save = useMutation({
    mutationFn: (dns: Dns) => api<NetworkOptions>('/api/network', json('PUT', { dns, connection: options.data?.hostMode })),
    onSuccess: value => { cache.setQueryData(['network'], value); test.reset() },
  })
  const test = useMutation({ mutationFn: (useChannel: boolean) => api<Check[]>('/api/network/test',
    json('POST', useChannel ? { channelId: channel } : { target: target.trim() })) })
  const mode = options.data?.hostMode
  return <div className="network-grid">
    <section className="panel stack">
      <h2><Network /> Rede e Conectividade</h2>
      {options.isError && <p role="alert" className="error">{options.error.message}</p>}
      {options.data && <label>DNS para novas listas
        <select value={options.data.preference.dns} disabled={save.isPending} onChange={event => save.mutate(event.target.value as Dns)}>
          <option value="system" disabled={!options.data.allowSystem}>Automático</option>
          <option value="cloudflare" disabled={!options.data.allowCloudflare}>Cloudflare DoH (1.1.1.1 / 1.0.0.1)</option>
        </select>
      </label>}
      {save.error && <p role="alert" className="error">{save.error.message}</p>}
      <h3>Rota configurada no servidor</h3>
      <div className="route-options">{(Object.keys(connectionNames) as Connection[]).map(connection =>
        <div className="route-option" key={connection} data-active={mode === connection}>
          {connection === 'direct' ? <Router /> : <ShieldCheck />}
          <div><strong>{connectionNames[connection]}</strong><p>{mode === connection ? 'Configurada · execute um teste para verificar' : connection === 'direct' ? 'Depende da rota do host' : 'Exige cliente externo no host'}</p></div>
        </div>)}</div>
      <p className="muted">A rota é definida no servidor. O navegador não liga nem desliga VPNs.</p>
    </section>
    <section className="panel stack">
      <h2><Activity /> Diagnóstico</h2>
      <form className="stack" onSubmit={event => { event.preventDefault(); test.mutate(false) }}>
        <label>Endereço do servidor IPTV <input type="url" placeholder="https://servidor.exemplo" value={target} onChange={event => setTarget(event.target.value)} /></label>
        <div className="actions"><Button type="submit" disabled={test.isPending}><Gauge /> {test.isPending ? 'Testando…' : 'Testar conexão'}</Button>
          {channel && <Button variant="outline" type="button" disabled={test.isPending} onClick={() => test.mutate(true)}>Testar mídia selecionada</Button>}</div>
      </form>
      {test.error && <p className="error" role="alert">{test.error.message}</p>}
      <div className="stack" aria-live="polite">{test.data?.map(check => <div className="diagnostic" key={check.stage}>
        <div><strong>{check.stage}</strong><p className={check.status === 'error' ? 'error' : 'muted'}>{check.detail}</p></div>
        <span className="player-status">{check.status === 'ok' ? 'OK' : 'Falhou'} · {check.latencyMs} ms</span></div>)}</div>
    </section>
  </div>
}

export function NetworkAdmin() {
  const cache = useQueryClient()
  const query = useQuery({ queryKey: ['network-policy'], queryFn: () => api<NetworkPolicy>('/api/admin/network') })
  const [draft, setDraft] = useState<NetworkPolicy>()
  useEffect(() => { setDraft(query.data) }, [query.data])
  const save = useMutation({ mutationFn: (value: NetworkPolicy) => api<NetworkPolicy>('/api/admin/network', json('PUT', value)), onSuccess: value => {
    cache.setQueryData(['network-policy'], value)
    cache.invalidateQueries({ queryKey: ['network'] })
  } })
  if (query.isError) return <p className="error" role="alert">{query.error.message}</p>
  if (!draft) return <p>Carregando configurações…</p>
  return <form className="panel stack" onSubmit={event => { event.preventDefault(); save.mutate(draft) }}>
    <h2><Network /> Rede e Conectividade</h2>
    <div className="flex flex-wrap gap-4">
      <label className="check"><input type="checkbox" checked={draft.allowSystem} onChange={event => setDraft({ ...draft, allowSystem: event.target.checked })} /> Permitir DNS automático</label>
      <label className="check"><input type="checkbox" checked={draft.allowCloudflare} onChange={event => setDraft({ ...draft, allowCloudflare: event.target.checked })} /> Permitir Cloudflare DoH</label>
    </div>
    <label>DNS padrão<select value={draft.defaultDns} onChange={event => setDraft({ ...draft, defaultDns: event.target.value as Dns })}>
      <option value="system" disabled={!draft.allowSystem}>Automático</option><option value="cloudflare" disabled={!draft.allowCloudflare}>Cloudflare DoH</option>
    </select></label>
    <label>Rota do host<select value={draft.hostMode} onChange={event => setDraft({ ...draft, hostMode: event.target.value as Connection })}>
      {(Object.keys(connectionNames) as Connection[]).map(key => <option value={key} key={key}>{connectionNames[key]}</option>)}
    </select></label>
    {draft.hostMode === 'wireguard' && <>
      <label>Endpoint WireGuard<input required placeholder="vpn.exemplo:51820" value={draft.wireGuardEndpoint} onChange={event => setDraft({ ...draft, wireGuardEndpoint: event.target.value })} /></label>
      <label>IP público de saída esperado<input required value={draft.wireGuardEgress} onChange={event => setDraft({ ...draft, wireGuardEgress: event.target.value })} /></label>
    </>}
    <p className="muted">Ative somente um túnel no host antes de selecionar a rota correspondente. Mantenha chaves e perfis no cliente instalado.</p>
    <div className="actions"><Button type="submit" disabled={save.isPending}>Salvar política</Button>{save.isSuccess && <span role="status">Salvo</span>}</div>
    {save.error && <p className="error" role="alert">{save.error.message}</p>}
  </form>
}

