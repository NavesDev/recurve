import { formatMoney } from '../shared/format/money';
import { INTERVAL_ORDER, INTERVAL_UNIT } from './constants';

export type BillingInterval = 'MONTHLY' | 'YEARLY';

export interface Price {
  id: string;
  price: number;
  currency: string;
  interval: BillingInterval;
  active: boolean;
  createdAt: string;
}

export interface Plan {
  id: string;
  name: string;
  description: string | null;
  active: boolean;
  createdAt: string;
  /** Every price the plan has had, inactive ones included (BR-04). */
  prices: Price[];
}

export interface PlanInput {
  name: string;
  description: string | null;
}

export interface PriceInput {
  price: number;
  currency: string;
  interval: BillingInterval;
}

const byInterval = (a: Price, b: Price) => INTERVAL_ORDER.indexOf(a.interval) - INTERVAL_ORDER.indexOf(b.interval);

export function activePrices(plan: Plan): Price[] {
  return plan.prices.filter((price) => price.active).sort(byInterval);
}

/** Inactive prices, newest first: the plan's history (BR-04). */
export function inactivePrices(plan: Plan): Price[] {
  return plan.prices.filter((price) => !price.active).sort((a, b) => b.createdAt.localeCompare(a.createdAt));
}

export function priceLabel(price: Price): string {
  return `${formatMoney(price.price, price.currency)}/${INTERVAL_UNIT[price.interval]}`;
}

export function priceSummary(plan: Plan): string {
  const active = activePrices(plan);
  return active.length ? active.map(priceLabel).join(' · ') : 'Sem preço ativo';
}

/** Cycles with no active price yet: BR-03 allows one per cycle (and currency, BRL only). */
export function freeIntervals(plan: Plan): BillingInterval[] {
  const taken = new Set(activePrices(plan).map((price) => price.interval));
  return INTERVAL_ORDER.filter((interval) => !taken.has(interval));
}

/** FR-02.4: an inactive plan takes no new price. */
export function canAddPrice(plan: Plan): boolean {
  return plan.active && freeIntervals(plan).length > 0;
}

/** FR-02.7: only an active price of an active plan is replaced. */
export function canReplacePrice(plan: Plan, price: Price): boolean {
  return plan.active && price.active;
}

/** FR-02.3. */
export function canDeactivatePrice(price: Price): boolean {
  return price.active;
}

/** FR-02.4. */
export function canDeactivatePlan(plan: Plan): boolean {
  return plan.active;
}
