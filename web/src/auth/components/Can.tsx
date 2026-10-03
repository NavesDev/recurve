import type { ReactNode } from 'react';
import type { Permission } from '../../shared/constants/permissions';
import { useCan } from '../useCan';

/** Renders its children only for an operator who holds the permission; nothing while unsure. */
export function Can({ permission, children }: { permission: Permission; children: ReactNode }) {
  return useCan(permission) ? children : null;
}
