import { useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter, Link, Navigate, NavLink, Outlet, Route, Routes, useLocation } from 'react-router-dom'
import { QueryClient, QueryClientProvider, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CalendarDays, ChevronRight, Film, Grid2X2, Heart, Layers, LogOut, Menu, Monitor, Moon, Radio, Shield, SlidersHorizontal, Sun, Tv, UserRound, X } from 'lucide-react'
import { api, ApiError, json, type Profile } from './api'
import { Button } from './components/ui/button'
import { Library } from './library'
import { NetworkSettings } from './network'
import { Admin } from './admin'
import { PageHeader } from './components/page-header'
import { SportsGuide } from './sports'
import './style.css'

type Theme = 'light' | 'dark' | 'system'
const client = new QueryClient({
  defaultOptions: { queries: {
    staleTime: 15_000,
    retry: (count, error) => count < 1 && (!(error instanceof ApiError) || error.status >= 500),
    throwOnError: false,
  } },
})

function useTheme() {
  const [theme, setTheme] = useState<Theme>(() => {
    const value = localStorage.getItem('nexo:theme')
    return value === 'light' || value === 'dark' ? value : 'system'
  })
  useEffect(() => {
    const media = matchMedia('(prefers-color-scheme: dark)')
    const update = () => { document.documentElement.dataset.theme = theme === 'system' ? media.matches ? 'dark' : 'light' : theme }
    update()
    if (theme === 'system') media.addEventListener('change', update)
    localStorage.setItem('nexo:theme', theme)
    return () => media.removeEventListener('change', update)
  }, [theme])
  return { theme, setTheme }
}

function Brand() {
  const title = useQuery({ queryKey: ['platform'], queryFn: () => api<{ name: string }> ('/api/platform'), enabled: useQueryClient().getQueryData(['account']) != null })
  return <Link to="/" className="brand"><span className="brand-icon"><Radio /></span><span>{title.data?.name === undefined || title.data.name === 'Morera IPTV' ? <>Morera <b>IPTV</b></> : title.data.name}</span></Link>
}

function AccountScreen({ mode, appearance }: { mode: 'login' | 'forgot' | 'reset'; appearance: ReactNode }) {
  const cache = useQueryClient()
  const [sent, setSent] = useState(false)
  const token = new URLSearchParams(location.hash.slice(1)).get('token')
  const request = useMutation({ mutationFn: async (values: FormData): Promise<Profile | void> => {
    const email = String(values.get('email') || '')
    const password = String(values.get('password') || '')
    return mode === 'login' ? api<Profile>('/api/auth/login', json('POST', { email, password }))
      : api<void>('/api/auth/' + mode, json('POST', mode === 'forgot' ? { email } : { token, password }))
  }, onSuccess: profile => {
    setSent(true)
    if (profile) cache.setQueryData(['account'], profile)
    if (mode === 'reset') history.replaceState(null, '', '/redefinir')
  } })
  const labels = { login: 'Entrar no Morera IPTV', forgot: 'Recuperar senha', reset: 'Criar nova senha' }
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    request.mutate(new FormData(event.currentTarget))
  }
  return <main className="auth-layout">
    <section className="panel auth-panel">
      <Brand />
      <PageHeader title={labels[mode]} appearance={appearance} />
      {sent && mode !== 'login'
        ? <div className="stack"><p role="status">{mode === 'forgot' ? 'Se houver uma conta associada a esse e-mail, você receberá um link.' : 'Senha atualizada.'}</p><Link to="/login">Voltar ao login</Link></div>
        : <form className="stack" onSubmit={submit}>
          {mode !== 'reset' && <label>E-mail<input type="email" name="email" autoComplete="email" required maxLength={254} /></label>}
          {mode !== 'forgot' && <label>{mode === 'reset' ? 'Nova senha' : 'Senha'}<input type="password" name="password" autoComplete={mode === 'reset' ? 'new-password' : 'current-password'} minLength={mode === 'reset' ? 12 : undefined} required /></label>}
          {request.error && <p role="alert" className="error">{request.error.message}</p>}
          {mode === 'reset' && !token && <p role="alert" className="error">Abra o link recebido por e-mail.</p>}
          <Button type="submit" disabled={request.isPending || (mode === 'reset' && !token)}>{request.isPending ? 'Aguarde…' : mode === 'forgot' ? 'Enviar link' : mode === 'reset' ? 'Salvar senha' : 'Entrar'}</Button>
          <Link to={mode === 'login' ? '/recuperar' : '/login'}>{mode === 'login' ? 'Esqueci minha senha' : 'Voltar ao login'}</Link>
        </form>}
    </section>
  </main>
}

const libraryLinks = [
  { view: null, label: 'Visão geral', icon: Grid2X2 },
  { view: 'live', label: 'Ao vivo', icon: Tv },
  { view: 'movies', label: 'Filmes', icon: Film },
  { view: 'series', label: 'Séries', icon: Layers },
  { view: 'favorites', label: 'Favoritos', icon: Heart },
]

function Layout({ profile }: { profile: Profile }) {
  const cache = useQueryClient()
  const location = useLocation()
  const view = new URLSearchParams(location.search).get('view')
  const [menuOpen, setMenuOpen] = useState(false)
  const closeMenu = () => setMenuOpen(false)
  useEffect(() => { setMenuOpen(false) }, [location.key])
  const logout = useMutation({ mutationFn: () => api<void>('/api/auth/logout', { method: 'POST' }), onSuccess: () => {
    cache.clear()
    cache.setQueryData(['account'], null)
  } })
  return <div className="shell">
    <aside className="sidebar" data-open={menuOpen}>
      <div className="sidebar-heading">
        <Brand />
        <Button type="button" variant="ghost" className="menu-toggle" aria-label={menuOpen ? 'Fechar menu' : 'Abrir menu'} aria-controls="sidebar-menu" aria-expanded={menuOpen} onClick={() => setMenuOpen(open => !open)}>
          {menuOpen ? <X /> : <Menu />}
        </Button>
      </div>
      <div id="sidebar-menu" className="sidebar-content">
        <nav aria-label="Menu principal">
          <div className="nav-group">
            <p className="nav-label">Biblioteca</p>
            {libraryLinks.map(({ view: key, label, icon: Icon }) => {
              const active = location.pathname === '/' && view === key
              return <Link key={label} to={key ? '/?view=' + key : '/'} className="nav" aria-current={active ? 'page' : undefined} onClick={closeMenu}>
                <Icon aria-hidden="true" /><span>{label}</span><ChevronRight className="nav-indicator" aria-hidden="true" />
              </Link>
            })}
          </div>
          <div className="nav-group">
            <p className="nav-label">Plataforma</p>
            <NavLink to="/jogos" className="nav" onClick={closeMenu}><CalendarDays aria-hidden="true" /><span>Guia de esportes</span><ChevronRight className="nav-indicator" aria-hidden="true" /></NavLink>
            <NavLink to="/configuracoes" className="nav" onClick={closeMenu}><SlidersHorizontal aria-hidden="true" /><span>Configurações</span><ChevronRight className="nav-indicator" aria-hidden="true" /></NavLink>
            {profile.role === 'ADMIN' && <NavLink to="/admin" className="nav" onClick={closeMenu}><Shield aria-hidden="true" /><span>Administração</span><ChevronRight className="nav-indicator" aria-hidden="true" /></NavLink>}
          </div>
        </nav>
        <div className="sidebar-footer">
          <span className="sidebar-avatar"><UserRound aria-hidden="true" /></span>
          <div className="sidebar-account">
            <strong>{profile.role === 'ADMIN' ? 'Administrador' : 'Minha conta'}</strong>
            <span title={profile.email}>{profile.email}</span>
          </div>
          <Button type="button" variant="ghost" className="sidebar-logout" aria-label="Sair" title="Sair" disabled={logout.isPending} onClick={() => logout.mutate()}><LogOut /></Button>
        </div>
      </div>
    </aside>
    <main>
      {logout.error && <p className="error" role="alert">{logout.error.message}</p>}
      <Outlet />
    </main>
  </div>
}

function App() {
  const { theme, setTheme } = useTheme()
  const auth = useQuery({ queryKey: ['account'], queryFn: async () => {
    try { return await api<Profile>('/api/auth/me') }
    catch (error) { if (error instanceof ApiError && error.status === 401) return null; throw error }
  } })
  const appearance = <div className="header-appearance" role="group" aria-label="Aparência">
    {([
      { value: 'dark', label: 'Escuro', icon: Moon },
      { value: 'light', label: 'Claro', icon: Sun },
      { value: 'system', label: 'Sistema', icon: Monitor },
    ] as const).map(({ value, label, icon: Icon }) =>
      <Button key={value} type="button" variant="ghost" aria-pressed={theme === value} aria-label={'Modo ' + label.toLowerCase()} title={label} onClick={() => setTheme(value)}><Icon aria-hidden="true" /></Button>
    )}
  </div>
  if (auth.isPending) return <main className="auth-layout">Carregando Morera IPTV…</main>
  if (auth.error) return <main className="auth-layout"><section className="panel stack"><p role="alert">{auth.error.message}</p><Button onClick={() => auth.refetch()}>Tentar novamente</Button></section></main>
  return <Routes>
    <Route path="/login" element={auth.data ? <Navigate to="/" replace /> : <AccountScreen key="login" mode="login" appearance={appearance} />} />
    <Route path="/recuperar" element={<AccountScreen key="forgot" mode="forgot" appearance={appearance} />} />
    <Route path="/redefinir" element={<AccountScreen key="reset" mode="reset" appearance={appearance} />} />
    <Route element={auth.data ? <Layout profile={auth.data} /> : <Navigate to="/login" replace />}>
      <Route index element={<Library appearance={appearance} />} />
      <Route path="jogos" element={<SportsGuide appearance={appearance} />} />
      <Route path="configuracoes" element={<><PageHeader title="Configurações" description="Gerencie a rede e a conectividade do IPTV." appearance={appearance} /><NetworkSettings /></>} />
      <Route path="admin" element={auth.data?.role === 'ADMIN' ? <Admin appearance={appearance} /> : <Navigate to="/" replace />} />
    </Route>
    <Route path="*" element={<Navigate to="/" replace />} />
  </Routes>
}

createRoot(document.getElementById('root')!).render(<QueryClientProvider client={client}><BrowserRouter><App /></BrowserRouter></QueryClientProvider>)








