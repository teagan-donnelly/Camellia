// Thin fetch wrapper. All calls go to /api (proxied to Spring Boot in development).
// No credentials or API keys ever live in the browser; the simulated user id is the only identity sent.

export class ApiError extends Error {
  status: number
  code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

let currentUserId = 'maya.ellison'

export function setApiUser(id: string) {
  currentUserId = id
}

export async function api<T>(path: string, init: RequestInit & { json?: unknown } = {}): Promise<T> {
  const { json, headers, ...rest } = init
  let res: Response
  try {
    res = await fetch(`/api${path}`, {
      ...rest,
      headers: {
        Accept: 'application/json',
        ...(json !== undefined ? { 'Content-Type': 'application/json' } : {}),
        'X-Camellia-User': currentUserId,
        ...headers,
      },
      body: json !== undefined ? JSON.stringify(json) : rest.body,
    })
  } catch {
    throw new ApiError(0, 'NETWORK', 'Cannot reach the Camellia backend. Is it running on port 8080?')
  }
  if (!res.ok) {
    let code = 'HTTP_' + res.status
    let message = res.statusText || 'Request failed'
    try {
      const body = await res.json()
      code = body.code ?? code
      message = body.message ?? message
    } catch {
      if (res.status === 502 || res.status === 504) message = 'The backend is not reachable (proxy error). Start it with mvnw spring-boot:run.'
    }
    throw new ApiError(res.status, code, message)
  }
  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

export const get = <T,>(path: string) => api<T>(path)
export const post = <T,>(path: string, json?: unknown) => api<T>(path, { method: 'POST', json: json ?? {} })
export const patch = <T,>(path: string, json: unknown) => api<T>(path, { method: 'PATCH', json })
