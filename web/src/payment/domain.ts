export type PaymentStatus = 'PENDING' | 'PAID' | 'FAILED' | 'REFUNDED';

export interface Payment {
  id: string;
  subscriberId: string;
  /** A snapshot of the price when charged (BR-05). */
  amount: number;
  currency: string;
  status: PaymentStatus;
  dueAt: string;
  paidAt: string | null;
  refundedAt: string | null;
  /** The gateway's id: present once the gateway took the charge. */
  externalId: string | null;
  invoiceUrl: string | null;
  createdAt: string;
}

/** Who a charge is for, as the payment listing shows it. */
export interface Payer {
  id: string;
  name: string;
  email: string;
}

/** FR-04.7: a pending charge the gateway never took can be sent again; it is never charged twice. */
export function canSend(payment: Payment): boolean {
  return payment.status === 'PENDING' && payment.externalId === null;
}

/** FR-04.7: only a charge the gateway holds can be brought up to date by asking it. */
export function canSync(payment: Payment): boolean {
  return payment.externalId !== null;
}

/** FR-04.5: paid outside the gateway. A failed charge may still be paid late; a paid or refunded one may not. */
export function canConfirm(payment: Payment): boolean {
  return payment.status === 'PENDING' || payment.status === 'FAILED';
}

/** BR-08. */
export function canRefund(payment: Payment): boolean {
  return payment.status === 'PAID';
}
