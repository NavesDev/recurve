import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { Can, useCan } from '../../auth';
import { amountPerInterval, usePlanLookup } from '../../plan';
import { describeError } from '../../shared/api/describeError';
import { useListing } from '../../shared/api/useListing';
import { PERMISSIONS } from '../../shared/constants/permissions';
import { SEARCH_DEBOUNCE_MS } from '../../shared/constants/query';
import { ROUTES } from '../../shared/constants/routes';
import { ButtonLink } from '../../shared/design/Button';
import { DataTable, type Column } from '../../shared/design/DataTable';
import { Alert, Cell, FilterChips, FilterSelect, Mono, Page, PageHeader, SearchInput, Toolbar } from '../../shared/design/Layout';
import { Menu } from '../../shared/design/Menu';
import { Pagination } from '../../shared/design/Pagination';
import { formatDate } from '../../shared/format/date';
import { useDebouncedValue } from '../../shared/useDebouncedValue';
import { useSubscriberList } from '../api';
import { SubscriberStatusBadge } from '../components/SubscriberStatusBadge';
import { SUBSCRIBER_LISTING, SUBSCRIBER_STATUS, SUBSCRIBER_STATUS_ORDER } from '../constants';
import { canEdit, type Subscriber } from '../domain';

export function SubscriberListPage() {
  const { listing, setQuery, setFilter, setSort, setPage, setSize } = useListing(SUBSCRIBER_LISTING);
  const [search, setSearch] = useState(listing.q);
  const settled = useDebouncedValue(search, SEARCH_DEBOUNCE_MS);
  const subscribers = useSubscriberList(listing);
  const plans = usePlanLookup();
  const canManage = useCan(PERMISSIONS.MANAGE_SUBSCRIBERS);
  const navigate = useNavigate();

  useEffect(() => {
    if (settled !== listing.q) setQuery(settled);
    // Only a settled search moves the URL; the URL moving does not retype the box.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [settled]);

  const planName = (id: string) => plans.data?.find((plan) => plan.id === id)?.name ?? '—';

  const columns: Column<Subscriber>[] = [
    {
      key: 'name', header: 'Assinante', sortField: 'name.keyword',
      render: (s) => <Cell main={<Link to={ROUTES.subscriberDetail(s.id)}>{s.name}</Link>} sub={s.email} />,
    },
    { key: 'plan', header: 'Plano', render: (s) => planName(s.planId) },
    {
      key: 'price', header: 'Valor', sortField: 'price',
      render: (s) => <Mono>{amountPerInterval(s.price, s.currency, s.interval)}</Mono>,
    },
    { key: 'status', header: 'Status', render: (s) => <SubscriberStatusBadge status={s.status} /> },
    { key: 'startedAt', header: 'Início', sortField: 'startedAt', render: (s) => formatDate(s.startedAt) },
    { key: 'next', header: 'Próxima cobrança', render: (s) => (s.status === 'CANCELED' ? '—' : formatDate(s.nextBillingAt)) },
    {
      key: 'actions', header: '', align: 'end', width: '56px',
      render: (s) => (
        <Menu label={`Ações de ${s.name}`} items={[
          { label: 'Ver assinante', onSelect: () => navigate(ROUTES.subscriberDetail(s.id)) },
          ...(canManage && canEdit(s) ? [{ label: 'Editar', onSelect: () => navigate(ROUTES.subscriberEdit(s.id)) }] : []),
          { label: 'Copiar e-mail', onSelect: () => void navigator.clipboard?.writeText(s.email) },
        ]} />
      ),
    },
  ];

  const total = subscribers.data?.total;
  return (
    <Page>
      <PageHeader
        title="Assinantes"
        subtitle={total === undefined ? ' ' : `${total} ${total === 1 ? 'assinante' : 'assinantes'}`}
        actions={<Can permission={PERMISSIONS.MANAGE_SUBSCRIBERS}><ButtonLink to={ROUTES.subscriberNew}>Novo assinante</ButtonLink></Can>}
      />
      <Toolbar>
        <SearchInput label="Buscar assinantes" placeholder="Buscar por nome ou e-mail…" value={search} onChange={(event) => setSearch(event.target.value)} />
        <FilterSelect label="Filtrar por plano" value={listing.filters.planId?.[0] ?? ''}
          onChange={(event) => setFilter('planId', event.target.value ? [event.target.value] : [])}>
          <option value="">Todos os planos</option>
          {(plans.data ?? []).map((plan) => <option key={plan.id} value={plan.id}>{plan.name}</option>)}
        </FilterSelect>
        <FilterChips label="Status" selected={listing.filters.status ?? []} onChange={(values) => setFilter('status', values)}
          options={SUBSCRIBER_STATUS_ORDER.map((status) => ({ value: status, label: SUBSCRIBER_STATUS[status].label }))} />
      </Toolbar>
      {subscribers.isError && <Alert>{describeError(subscribers.error)}</Alert>}
      <DataTable caption="Assinantes" columns={columns} rows={subscribers.data?.items ?? []} rowKey={(s) => s.id}
        sort={listing.sort} onSortChange={setSort} loading={subscribers.isPending}
        empty={listing.q || Object.keys(listing.filters).length ? 'Nenhum assinante encontrado.' : 'Nenhum assinante cadastrado ainda.'} />
      {subscribers.data && (
        <Pagination page={listing.page} size={listing.size} total={subscribers.data.total} onPageChange={setPage} onSizeChange={setSize} />
      )}
    </Page>
  );
}
