import type { BillingInterval } from '../plan';

export type SubscriberStatus = 'ACTIVE' | 'PAST_DUE' | 'CANCELED';

export interface Subscriber {
  id: string;
  name: string;
  email: string;
  /** CPF or CNPJ, digits only. */
  document: string;
  status: SubscriberStatus;
  planId: string;
  planPriceId: string;
  price: number;
  currency: string;
  interval: BillingInterval;
  startedAt: string;
  nextBillingAt: string | null;
  canceledAt: string | null;
  createdAt: string;
}

export interface SubscriberInput {
  name: string;
  email: string;
  document: string;
}

export interface NewSubscriber extends SubscriberInput {
  planPriceId: string;
}

/** FR-03.7: a canceled subscriber is not edited. */
export function canEdit(subscriber: Subscriber): boolean {
  return subscriber.status !== 'CANCELED';
}

/** FR-03.3. */
export function canCancel(subscriber: Subscriber): boolean {
  return subscriber.status !== 'CANCELED';
}

/** BR-07: a canceled subscriber generates no charge. */
export function canCharge(subscriber: Subscriber): boolean {
  return subscriber.status !== 'CANCELED';
}
