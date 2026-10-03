import type { ListingSpec } from '../shared/api/listing';
import { PERMISSIONS } from '../shared/constants/permissions';
import { ROUTES } from '../shared/constants/routes';
import type { Tone } from '../shared/design/Badge';
import type { NavEntry } from '../shared/layout/AppShell';
import type { SubscriberStatus } from './domain';

export const subscriberNav: NavEntry = {
  to: ROUTES.subscribers, label: 'Assinantes', icon: 'subscribers', permission: PERMISSIONS.VIEW_SUBSCRIBERS,
};

/** FR-06.3: what the subscriber listing offers. */
export const SUBSCRIBER_LISTING: ListingSpec = {
  defaultSort: 'startedAt:desc',
  sorts: ['startedAt', 'price', 'name.keyword'],
  filters: ['status', 'planId'],
};

export const SUBSCRIBER_STATUS: Record<SubscriberStatus, { label: string; tone: Tone }> = {
  ACTIVE: { label: 'Ativo', tone: 'success' },
  PAST_DUE: { label: 'Atrasado', tone: 'warn' },
  CANCELED: { label: 'Cancelado', tone: 'neutral' },
};

export const SUBSCRIBER_STATUS_ORDER: readonly SubscriberStatus[] = ['ACTIVE', 'PAST_DUE', 'CANCELED'];

export const NAME_MAX_LENGTH = 120;
export const EMAIL_MAX_LENGTH = 255;
