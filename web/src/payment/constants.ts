import type { ListingSpec } from '../shared/api/listing';
import { PERMISSIONS } from '../shared/constants/permissions';
import { ROUTES } from '../shared/constants/routes';
import type { Tone } from '../shared/design/Badge';
import type { NavEntry } from '../shared/layout/AppShell';
import type { PaymentStatus } from './domain';

export const paymentNav: NavEntry = { to: ROUTES.payments, label: 'Pagamentos', icon: 'payments', permission: PERMISSIONS.VIEW_PAYMENTS };

/** FR-04.6: what the payment listing offers. */
export const PAYMENT_LISTING: ListingSpec = {
  defaultSort: 'dueAt:desc',
  sorts: ['dueAt', 'amount', 'paidAt', 'createdAt'],
  filters: ['status', 'subscriberId'],
};

export const PAYMENT_STATUS: Record<PaymentStatus, { label: string; tone: Tone }> = {
  PENDING: { label: 'Pendente', tone: 'action' },
  PAID: { label: 'Pago', tone: 'success' },
  FAILED: { label: 'Falhou', tone: 'danger' },
  REFUNDED: { label: 'Reembolsado', tone: 'neutral' },
};

export const PAYMENT_STATUS_ORDER: readonly PaymentStatus[] = ['PENDING', 'PAID', 'FAILED', 'REFUNDED'];
