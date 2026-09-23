import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { useSession } from '@/stores/session'

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  withCredentials: true,
})

api.interceptors.request.use((config) => {
  const { accessToken, workspaceId } = useSession.getState()
  if (accessToken) config.headers.Authorization = `Bearer ${accessToken}`
  // /workspaces ve /auth header'siz calisir; digerleri tenant secimi ister.
  const url = config.url ?? ''
  if (workspaceId && !url.startsWith('/api/v1/auth') && url !== '/api/v1/workspaces') {
    config.headers['X-Workspace-Id'] = workspaceId
  }
  return config
})

let refreshing: Promise<string | null> | null = null

/** Ayni anda gelen 401'ler tek bir refresh cagrisini paylasir (refresh token tek kullanimlik). */
export function refreshAccessToken(): Promise<string | null> {
  refreshing ??= axios
    .post<{ accessToken: string }>(`${api.defaults.baseURL}/api/v1/auth/refresh`, null, { withCredentials: true })
    .then((res) => {
      useSession.getState().setToken(res.data.accessToken)
      return res.data.accessToken
    })
    .catch(() => {
      useSession.getState().setToken(null)
      return null
    })
    .finally(() => {
      refreshing = null
    })
  return refreshing
}

type RetriableConfig = InternalAxiosRequestConfig & { _retried?: boolean }

api.interceptors.response.use(
  (res) => res,
  async (error: AxiosError) => {
    const config = error.config as RetriableConfig | undefined
    if (error.response?.status === 401 && config && !config._retried && !config.url?.startsWith('/api/v1/auth')) {
      config._retried = true
      const token = await refreshAccessToken()
      if (token) return api(config)
      useSession.getState().clear()
    }
    return Promise.reject(error)
  },
)

/** RFC 7807 ProblemDetail'den okunabilir mesaj. */
export function errorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    const data = error.response?.data as
      | { detail?: string; invalid_params?: { field: string; reason: string }[] }
      | undefined
    if (data?.invalid_params?.length) return data.invalid_params.map((p) => p.reason).join(" ")
    if (data?.detail) return data.detail
    if (!error.response) return 'Sunucuya ulasilamadi.'
    return `Istek basarisiz (${error.response.status}).`
  }
  return 'Beklenmeyen bir hata olustu.'
}
