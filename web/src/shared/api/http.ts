import { ApiError, type FieldError } from './ApiError';
import { sessionStore, type SessionStore } from './session';

export interface HttpDependencies {
  fetch: typeof fetch;
  session: SessionStore;
  /** A 403: the operator's permissions may have changed under them. */
  onForbidden: () => void;
}

interface RequestOptions {
  signal?: AbortSignal;
}

export interface Http {
  get<T>(path: string, options?: RequestOptions): Promise<T>;
  post<T = void>(path: string, body?: unknown, options?: RequestOptions): Promise<T>;
  put<T = void>(path: string, body?: unknown, options?: RequestOptions): Promise<T>;
  delete<T = void>(path: string, options?: RequestOptions): Promise<T>;
}

interface ServerError {
  message?: unknown;
  fieldErrors?: unknown;
}

function isFieldError(value: unknown): value is FieldError {
  return typeof value === 'object' && value !== null
    && typeof (value as FieldError).field === 'string' && typeof (value as FieldError).message === 'string';
}

async function toApiError(response: Response): Promise<ApiError> {
  let body: ServerError = {};
  try {
    body = (await response.json()) as ServerError;
  } catch {
    // Not our ApiError body (a proxy's page, an empty answer): keep the status.
  }
  const message = typeof body.message === 'string' ? body.message : response.statusText || `HTTP ${response.status}`;
  const fieldErrors = Array.isArray(body.fieldErrors) ? body.fieldErrors.filter(isFieldError) : [];
  return new ApiError(response.status, message, fieldErrors);
}

async function parse<T>(response: Response): Promise<T> {
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/**
 * JSON over fetch with the session's token. Every failure leaves here as
 * an {@link ApiError} — except a cancellation, which is the caller's own
 * doing. A 401 ends the session (fail-closed); a 403 is reported so the
 * permissions can be read again.
 */
export function createHttp({ fetch: send, session, onForbidden }: HttpDependencies): Http {
  async function request<T>(method: string, path: string, body: unknown, options: RequestOptions = {}): Promise<T> {
    const headers = new Headers({ Accept: 'application/json' });
    const token = session.get()?.token;
    if (token) headers.set('Authorization', `Bearer ${token}`);
    if (body !== undefined) headers.set('Content-Type', 'application/json');

    let response: Response;
    try {
      response = await send(path, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: options.signal,
      });
    } catch (cause) {
      if (cause instanceof DOMException && cause.name === 'AbortError') throw cause;
      throw ApiError.network(cause);
    }

    if (response.ok) return parse<T>(response);

    if (response.status === 401) session.clear();
    if (response.status === 403) onForbidden();
    throw await toApiError(response);
  }

  return {
    get: (path, options) => request('GET', path, undefined, options),
    post: (path, body, options) => request('POST', path, body, options),
    put: (path, body, options) => request('PUT', path, body, options),
    delete: (path, options) => request('DELETE', path, undefined, options),
  };
}

const forbiddenListeners = new Set<() => void>();

/** Lets the auth feature hear about a 403 without shared/ knowing it exists. */
export function onForbidden(listener: () => void): () => void {
  forbiddenListeners.add(listener);
  return () => forbiddenListeners.delete(listener);
}

export const http = createHttp({
  fetch: (...args) => fetch(...args),
  session: sessionStore,
  onForbidden: () => forbiddenListeners.forEach((listener) => listener()),
});
