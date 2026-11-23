export type Profile = { id: string; email: string; role: 'ADMIN' | 'USER' }
export type Playlist = { id: string; label: string; dns: string; createdAt: string }
export type Entry = { id: string; name: string; group: string; type: string; favorite: boolean; tvgId?: string }
export type Group = { name: string; count: number }
export type Dns = 'system' | 'cloudflare'
export type Connection = 'direct' | 'warp' | 'wireguard'
export type Preference = { dns: Dns; connection: Connection }
export type NetworkOptions = { preference: Preference; allowSystem: boolean; allowCloudflare: boolean; hostMode: Connection }
export type NetworkPolicy = Omit<NetworkOptions, 'preference'> & { defaultDns: Dns; wireGuardEndpoint: string; wireGuardEgress: string }
export type Check = { stage: string; status: 'ok' | 'error'; latencyMs: number; detail: string }

export class ApiError extends Error {
  constructor(message: string, readonly status: number) { super(message) }
}

export async function api<T>(path: string, request: RequestInit = {}): Promise<T> {
  const headers = new Headers(request.headers)
  headers.set('X-Nexo-Request', '1')
  if (typeof request.body === 'string') headers.set('Content-Type', 'application/json')
  const response = await fetch(path, { ...request, headers })
  const text = await response.text()
  const data = response.headers.get('content-type')?.includes('application/json') && text ? JSON.parse(text) : undefined
  if (!response.ok) throw new ApiError(data?.error || (response.status === 401 ? 'Sua sessão expirou. Entre novamente.' : 'Não foi possível concluir a operação.'), response.status)
  return data as T
}

export function json(method: string, body: object): RequestInit {
  return { method, body: JSON.stringify(body) }
}

export const format = new Intl.NumberFormat('pt-BR')
