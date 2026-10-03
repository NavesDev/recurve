import type { ListingSpec } from '../shared/api/listing';
import { PERMISSIONS, type Permission } from '../shared/constants/permissions';
import { ROUTES } from '../shared/constants/routes';
import type { NavEntry } from '../shared/layout/AppShell';

export const userNav: NavEntry = { to: ROUTES.users, label: 'Operadores', icon: 'users', permission: PERMISSIONS.MANAGE_USERS };

/** FR-06.1: what the operator listing offers. */
export const USER_LISTING: ListingSpec = {
  defaultSort: 'name.keyword',
  sorts: ['name.keyword', 'email.keyword', 'createdAt'],
  filters: ['active'],
};

export type Area = 'plans' | 'subscribers' | 'payments' | 'users' | 'system';
export type Level = 'none' | 'view' | 'manage';

/** BR-01: a view and a manage permission per resource; operators and the system have manage only. */
export const AREAS: readonly { key: Area; label: string; view: Permission | null; manage: Permission }[] = [
  { key: 'plans', label: 'Planos', view: PERMISSIONS.VIEW_PLANS, manage: PERMISSIONS.MANAGE_PLANS },
  { key: 'subscribers', label: 'Assinantes', view: PERMISSIONS.VIEW_SUBSCRIBERS, manage: PERMISSIONS.MANAGE_SUBSCRIBERS },
  { key: 'payments', label: 'Pagamentos', view: PERMISSIONS.VIEW_PAYMENTS, manage: PERMISSIONS.MANAGE_PAYMENTS },
  { key: 'users', label: 'Operadores', view: null, manage: PERMISSIONS.MANAGE_USERS },
  { key: 'system', label: 'Sistema', view: null, manage: PERMISSIONS.MANAGE_SYSTEM },
];

export const LEVEL_LABEL: Record<Level, string> = { none: 'Nenhum', view: 'Ver', manage: 'Gerenciar' };

export type Profile = 'Administrador' | 'Financeiro' | 'Suporte' | 'Leitura';

/** The prototype's profiles: a shortcut that fills the matrix. The server knows only permissions. */
export const PROFILES: Record<Profile, Record<Area, Level>> = {
  Administrador: { plans: 'manage', subscribers: 'manage', payments: 'manage', users: 'manage', system: 'manage' },
  Financeiro: { plans: 'manage', subscribers: 'view', payments: 'manage', users: 'none', system: 'none' },
  Suporte: { plans: 'view', subscribers: 'manage', payments: 'view', users: 'none', system: 'none' },
  Leitura: { plans: 'view', subscribers: 'view', payments: 'view', users: 'none', system: 'none' },
};

export const NAME_MAX_LENGTH = 120;
export const EMAIL_MAX_LENGTH = 255;
/** NFR-04: BCrypt reads 72 bytes; the server refuses more rather than truncate. */
export const PASSWORD_MIN_LENGTH = 8;
export const PASSWORD_MAX_LENGTH = 72;
