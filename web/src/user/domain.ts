import type { Permission } from '../shared/constants/permissions';
import { AREAS, PROFILES, type Area, type Level, type Profile } from './constants';

export interface User {
  id: string;
  name: string;
  email: string;
  /** As granted; MANAGE_* brings its VIEW_* on the server (BR-01). */
  permissions: Permission[];
  active: boolean;
  createdAt: string;
}

export interface UserInput {
  name: string;
  email: string;
  permissions: Permission[];
}

export interface NewUser extends UserInput {
  password: string;
}

export type Levels = Record<Area, Level>;

export function levelOf(permissions: readonly Permission[], area: Area): Level {
  const entry = AREAS.find((candidate) => candidate.key === area);
  if (!entry) return 'none';
  if (permissions.includes(entry.manage)) return 'manage';
  return entry.view && permissions.includes(entry.view) ? 'view' : 'none';
}

export function levelsOf(permissions: readonly Permission[]): Levels {
  return Object.fromEntries(AREAS.map((area) => [area.key, levelOf(permissions, area.key)])) as Levels;
}

/** The fewest permissions granting these levels: manage implies view, so view is not sent with it. */
export function permissionsFrom(levels: Levels): Permission[] {
  return AREAS.flatMap((area) => {
    const level = levels[area.key];
    if (level === 'manage') return [area.manage];
    return level === 'view' && area.view ? [area.view] : [];
  });
}

export function profilePermissions(profile: Profile): Permission[] {
  return permissionsFrom(PROFILES[profile]);
}

/** The profile these levels match exactly, if any. */
export function profileOf(levels: Levels): Profile | null {
  const match = (Object.keys(PROFILES) as Profile[]).find((profile) =>
    AREAS.every((area) => PROFILES[profile][area.key] === levels[area.key]));
  return match ?? null;
}

export function accessSummary(permissions: readonly Permission[]): string {
  const levels = levelsOf(permissions);
  const reached = AREAS.filter((area) => levels[area.key] !== 'none').length;
  if (AREAS.every((area) => levels[area.key] === 'manage')) return 'Acesso total';
  if (reached === 0) return 'Sem acesso';
  return `${reached} de ${AREAS.length} áreas`;
}
