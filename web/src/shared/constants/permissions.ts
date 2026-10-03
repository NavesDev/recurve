/** Mirror of the server's Permission enum (BR-01). The only place these names are written. */
export const PERMISSIONS = {
  MANAGE_USERS: 'MANAGE_USERS',
  VIEW_PLANS: 'VIEW_PLANS',
  MANAGE_PLANS: 'MANAGE_PLANS',
  VIEW_SUBSCRIBERS: 'VIEW_SUBSCRIBERS',
  MANAGE_SUBSCRIBERS: 'MANAGE_SUBSCRIBERS',
  VIEW_PAYMENTS: 'VIEW_PAYMENTS',
  MANAGE_PAYMENTS: 'MANAGE_PAYMENTS',
  MANAGE_SYSTEM: 'MANAGE_SYSTEM',
} as const;

export type Permission = (typeof PERMISSIONS)[keyof typeof PERMISSIONS];
