import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from './ApiError';
import { createHttp } from './http';
import { createSessionStore, type SessionStore } from './session';

const NOW = new Date('2026-01-15T10:00:00Z').getTime();

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

describe('http client', () => {
  let session: SessionStore;
  let fetchMock: ReturnType<typeof vi.fn<typeof fetch>>;
  let onForbidden: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    sessionStorage.clear();
    session = createSessionStore(() => NOW);
    fetchMock = vi.fn<typeof fetch>();
    onForbidden = vi.fn();
  });

  const client = () => createHttp({ fetch: fetchMock, session, onForbidden });
  const sentHeaders = () => new Headers(fetchMock.mock.calls[0]?.[1]?.headers);

  it('sends the session token as a Bearer credential', async () => {
    session.set({ token: 'abc', expiresAt: '2026-01-15T18:00:00Z' });
    fetchMock.mockResolvedValue(json(200, { ok: true }));

    await client().get('/api/me');

    expect(sentHeaders().get('Authorization')).toBe('Bearer abc');
  });

  it('sends no credential without a session', async () => {
    fetchMock.mockResolvedValue(json(200, {}));

    await client().get('/api/me');

    expect(sentHeaders().has('Authorization')).toBe(false);
  });

  it('sends a body as JSON and answers the parsed body', async () => {
    fetchMock.mockResolvedValue(json(201, { id: '1' }));

    const created = await client().post<{ id: string }>('/api/plans', { name: 'Pro' });

    expect(created).toEqual({ id: '1' });
    expect(fetchMock.mock.calls[0]?.[1]?.method).toBe('POST');
    expect(fetchMock.mock.calls[0]?.[1]?.body).toBe('{"name":"Pro"}');
    expect(sentHeaders().get('Content-Type')).toBe('application/json');
  });

  it('answers undefined for an empty body', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 200 }));

    expect(await client().post('/api/payments/1/sync')).toBeUndefined();
  });

  it('turns the server error body into an ApiError', async () => {
    fetchMock.mockResolvedValue(json(400, {
      status: 400, error: 'Bad Request', message: 'Request validation failed',
      timestamp: '2026-01-15T10:00:00Z', fieldErrors: [{ field: 'name', message: 'must not be blank' }],
    }));

    const error = await client().post('/api/plans', {}).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 400, message: 'Request validation failed',
      fieldErrors: [{ field: 'name', message: 'must not be blank' }] });
  });

  it('turns a body that is not ours into an ApiError with its status', async () => {
    fetchMock.mockResolvedValue(new Response('<html>Bad Gateway</html>', { status: 502 }));

    await expect(client().get('/api/plans')).rejects.toMatchObject({ status: 502, fieldErrors: [] });
  });

  it('turns a network failure into an ApiError of status 0', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));

    await expect(client().get('/api/plans')).rejects.toMatchObject({ status: 0 });
  });

  it('lets a cancellation through untouched', async () => {
    fetchMock.mockRejectedValue(new DOMException('Aborted', 'AbortError'));

    await expect(client().get('/api/plans')).rejects.toMatchObject({ name: 'AbortError' });
  });

  it('signs out on 401 (fail-closed)', async () => {
    session.set({ token: 'abc', expiresAt: '2026-01-15T18:00:00Z' });
    fetchMock.mockResolvedValue(json(401, { status: 401, error: 'Unauthorized', message: 'Authentication required', timestamp: '', fieldErrors: [] }));

    await expect(client().get('/api/plans')).rejects.toBeInstanceOf(ApiError);
    expect(session.get()).toBeNull();
  });

  it('reports a 403 so permissions can be reread', async () => {
    fetchMock.mockResolvedValue(json(403, { status: 403, error: 'Forbidden', message: 'Access denied', timestamp: '', fieldErrors: [] }));

    await expect(client().get('/api/plans')).rejects.toMatchObject({ status: 403 });
    expect(onForbidden).toHaveBeenCalledOnce();
  });
});
