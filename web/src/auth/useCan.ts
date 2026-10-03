import type { Permission } from '../shared/constants/permissions';
import { useMe } from './api';
import { can } from './domain';

/** Fail-closed: false while `me` loads, fails or lacks the permission. */
export function useCan(permission: Permission): boolean {
  const { data } = useMe();
  return can(data, permission);
}
