import { Badge } from '../../shared/design/Badge';
import { SUBSCRIBER_STATUS } from '../constants';
import type { SubscriberStatus } from '../domain';

export function SubscriberStatusBadge({ status }: { status: SubscriberStatus }) {
  return <Badge tone={SUBSCRIBER_STATUS[status].tone}>{SUBSCRIBER_STATUS[status].label}</Badge>;
}
