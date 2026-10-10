// Thin fetch wrapper. All calls go to /api (proxied to Spring Boot in development).
// No credentials or API keys ever live in the browser. The SIMULATED demo identity travels in an HttpOnly,
// server-signed session cookie that the browser sends automatically; the client never asserts a user id per request.

export class ApiError extends Error {
  status: number
  code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

export const DEFAULT_USER = 'maya.ellison'
const STORAGE_KEY = 'camellia.user'

export function storedUserId(): string {
  try {
    return localStorage.getItem(STORAGE_KEY) ?? DEFAULT_USER
  } catch {
    return DEFAULT_USER
  }
}

export function rememberUserId(id: string) {
  try { localStorage.setItem(STORAGE_KEY, id) } catch { /* storage unavailable */ }
}

// One shared re-selection when several requests hit an expired session at once (e.g. after a backend restart).
let reselecting: Promise<void> | null = null

async function reselectStoredUser() {
  reselecting ??= send('/session', { method: 'POST', json: { userId: storedUserId() } })
    .then(async (res) => {
      if (res.ok) return
      // The stored id may be stale (user removed); fall back to the default demo user.
      const retry = await send('/session', { method: 'POST', json: { userId: DEFAULT_USER } })
      if (!retry.ok) throw await toError(retry)
      rememberUserId(DEFAULT_USER)
    })
    .finally(() => { reselecting = null })
  return reselecting
}

type Init = RequestInit & { json?: unknown }

async function send(path: string, init: Init): Promise<Response> {
  const { json, headers, ...rest } = init
  try {
    return await fetch(`/api${path}`, {
      ...rest,
      credentials: 'same-origin',
      headers: {
        Accept: 'application/json',
        ...(json !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...headers,
      },
      body: json !== undefined ? JSON.stringify(json) : rest.body,
    })
  } catch {
    throw new ApiError(0, 'NETWORK', 'Cannot reach the Camellia backend. Is it running on port 8080?')
  }
}

async function toError(res: Response): Promise<ApiError> {
  let code = 'HTTP_' + res.status
  let message = res.statusText || 'Request failed'
  try {
    const body = await res.json()
    code = body.code ?? code
    message = body.message ?? message
  } catch {
    if (res.status === 502 || res.status === 504) message = 'The backend is not reachable (proxy error). Start it with mvnw spring-boot:run.'
  }
  return new ApiError(res.status, code, message)
}

export async function api<T>(path: string, init: Init = {}): Promise<T> {
  let res = await send(path, init)
  if (res.status === 401 && !(path === '/session' && init.method === 'POST')) {
    const err = await toError(res)
    if (err.code !== 'DEMO_SESSION_REQUIRED') throw err
    await reselectStoredUser()
    res = await send(path, init)
  }
  if (!res.ok) throw await toError(res)
  if (res.status === 204 || res.headers.get('content-length') === '0') return undefined as T
  return res.json() as Promise<T>
}

export const get = <T,>(path: string) => api<T>(path)
export const post = <T,>(path: string, json?: unknown) => api<T>(path, { method: 'POST', json: json ?? {} })
export const patch = <T,>(path: string, json: unknown) => api<T>(path, { method: 'PATCH', json })
