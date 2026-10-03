import type { Permission } from '../shared/constants/permissions';

/** FR-05.4: the signed-in operator, permissions already expanded by the server. */
export interface Me {
  id: string;
  name: string;
  email: string;
  permissions: Permission[];
}

export interface IssuedToken {
  token: string;
  expiresAt: string;
}

/** Fail-closed: no operator, no permission. */
export function can(me: Me | null | undefined, permission: Permission): boolean {
  return me?.permissions.includes(permission) ?? false;
}

/** Initials for the avatar: first and last name. */
export function initialsOf(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  const first = parts[0]?.[0] ?? '';
  const last = parts.length > 1 ? (parts[parts.length - 1]?.[0] ?? '') : '';
  return (first + last).toUpperCase();
}

/**
 * Where to go after signing in. Only a path inside the panel: anything
 * else — another site, a protocol-relative URL — goes home instead.
 */
export function safeNext(next: string | null, fallback: string): string {
  if (!next || !next.startsWith('/') || next.startsWith('//') || next.startsWith('/\\')) return fallback;
  return next;
}
