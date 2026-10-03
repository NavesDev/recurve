import { describeError } from '../../shared/api/describeError';
import { useListing } from '../../shared/api/useListing';
import { Alert, FilterChips, Page, PageHeader, Toolbar } from '../../shared/design/Layout';
import { Pagination } from '../../shared/design/Pagination';
import { usePaymentList } from '../api';
import { PaymentTable } from '../components/PaymentTable';
import { PAYMENT_LISTING, PAYMENT_STATUS, PAYMENT_STATUS_ORDER } from '../constants';

export function PaymentListPage() {
  const { listing, setFilter, setSort, setPage, setSize } = useListing(PAYMENT_LISTING);
  const payments = usePaymentList(listing);
  const total = payments.data?.total;

  return (
    <Page>
      <PageHeader title="Pagamentos" subtitle={total === undefined ? ' ' : `${total} ${total === 1 ? 'cobrança' : 'cobranças'}`} />
      <Toolbar>
        <FilterChips label="Status" selected={listing.filters.status ?? []} onChange={(values) => setFilter('status', values)}
          options={PAYMENT_STATUS_ORDER.map((status) => ({ value: status, label: PAYMENT_STATUS[status].label }))} />
      </Toolbar>
      {payments.isError && <Alert>{describeError(payments.error)}</Alert>}
      <PaymentTable caption="Pagamentos" payments={payments.data?.items ?? []} loading={payments.isPending}
        sort={listing.sort} onSortChange={setSort} />
      {payments.data && (
        <Pagination page={listing.page} size={listing.size} total={payments.data.total} onPageChange={setPage} onSizeChange={setSize} />
      )}
    </Page>
  );
}
