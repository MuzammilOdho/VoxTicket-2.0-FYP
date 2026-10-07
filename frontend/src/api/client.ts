/** Minimal fetch wrapper: same-origin, HTTP Basic auth, 401 -> login. */

export const AUTH_KEY = 'vt_admin_auth';

export function getAuth(): string | null {
  try {
    return sessionStorage.getItem(AUTH_KEY);
  } catch {
    return null;
  }
}

export function setAuth(base64: string): void {
  sessionStorage.setItem(AUTH_KEY, base64);
}

export function clearAuth(): void {
  sessionStorage.removeItem(AUTH_KEY);
}

export class ApiError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

function goLogin(): void {
  clearAuth();
  // HashRouter: keep the app shell, just switch the route.
  if (!window.location.hash.startsWith('#/login')) {
    window.location.hash = '#/login';
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const headers: Record<string, string> = {};
  const auth = getAuth();
  if (auth) headers['Authorization'] = 'Basic ' + auth;
  if (init?.body !== undefined) headers['Content-Type'] = 'application/json';

  let res: Response;
  try {
    res = await fetch(path, { ...init, headers: { ...headers, ...(init?.headers as Record<string, string> | undefined) } });
  } catch (e) {
    // Backend unreachable (or wrong origin): surface as a normal error state.
    throw new ApiError(0, e instanceof Error ? `Network error: ${e.message}` : 'Network error');
  }

  if (res.status === 401) {
    goLogin();
    throw new ApiError(401, 'Unauthorized - please log in');
  }
  if (res.status === 404) {
    throw new ApiError(404, 'Not found');
  }
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new ApiError(res.status, text || `Request failed (${res.status})`);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export const api = {
  get: <T>(path: string) => request<T>(path),
};

export function isNotFound(e: unknown): boolean {
  return e instanceof ApiError && e.status === 404;
}

/** Login probe: returns true on 200, false on 401/other. Never throws for 401. */
export async function probeLogin(base64: string): Promise<boolean> {
  let res: Response;
  try {
    res = await fetch('/api/v1/admin/system/health', {
      headers: { Authorization: 'Basic ' + base64 },
    });
  } catch {
    return false;
  }
  if (res.status === 401) return false;
  return res.ok;
}
