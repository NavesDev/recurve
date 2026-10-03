import { useState } from 'react';
import { useCan } from '../../auth';
import { PERMISSIONS } from '../../shared/constants/permissions';
import { Card } from '../../shared/design/Layout';
import { Pagination } from '../../shared/design/Pagination';
import { subscriberPaymentsListing, usePaymentList } from '../api';
import { PaymentTable } from './PaymentTable';

/** A subscriber's charges, for the subscriber's page. Absent for an operator who may not view payments. */
export function SubscriberPayments({ subscriberId }: { subscriberId: string }) {
  const canView = useCan(PERMISSIONS.VIEW_PAYMENTS);
  const [page, setPage] = useState(0);
  const listing = subscriberPaymentsListing(subscriberId, page);
  const payments = usePaymentList(listing, canView);

  if (!canView) return null;
  return (
    <Card title="Cobranças">
      <PaymentTable caption="Cobranças" payments={payments.data?.items ?? []} loading={payments.isPending}
        showPayer={false} empty="Nenhuma cobrança gerada ainda." />
      {payments.data && payments.data.total > listing.size && (
        <Pagination page={page} size={listing.size} total={payments.data.total} onPageChange={setPage} onSizeChange={() => {}} />
      )}
    </Card>
  );
}
