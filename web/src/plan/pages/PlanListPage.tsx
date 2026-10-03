import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { Can, useCan } from '../../auth';
import { describeError } from '../../shared/api/describeError';
import { useListing } from '../../shared/api/useListing';
import { PERMISSIONS } from '../../shared/constants/permissions';
import { SEARCH_DEBOUNCE_MS } from '../../shared/constants/query';
import { ROUTES } from '../../shared/constants/routes';
import { ButtonLink } from '../../shared/design/Button';
import { DataTable, type Column } from '../../shared/design/DataTable';
import { Alert, Cell, FilterSelect, Page, PageHeader, SearchInput, Toolbar } from '../../shared/design/Layout';
import { Menu } from '../../shared/design/Menu';
import { Pagination } from '../../shared/design/Pagination';
import { formatDate } from '../../shared/format/date';
import { useDebouncedValue } from '../../shared/useDebouncedValue';
import { usePlanList } from '../api';
import { PlanStatus } from '../components/PlanStatus';
import { INTERVAL_LABEL, INTERVAL_ORDER, PLAN_LISTING } from '../constants';
import { priceSummary, type Plan } from '../domain';
import styles from './PlanListPage.module.css';

export function PlanListPage() {
  const { listing, setQuery, setFilter, setSort, setPage, setSize } = useListing(PLAN_LISTING);
  const [search, setSearch] = useState(listing.q);
  const settled = useDebouncedValue(search, SEARCH_DEBOUNCE_MS);
  const plans = usePlanList(listing);
  const canManage = useCan(PERMISSIONS.MANAGE_PLANS);
  const navigate = useNavigate();

  useEffect(() => {
    if (settled !== listing.q) setQuery(settled);
    // Only a settled search moves the URL; the URL moving does not retype the box.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [settled]);

  const columns: Column<Plan>[] = [
    {
      key: 'name', header: 'Plano', sortField: 'name.keyword',
      render: (plan) => <Cell main={<Link to={ROUTES.planDetail(plan.id)}>{plan.name}</Link>} sub={plan.description} />,
    },
    { key: 'prices', header: 'Preços ativos', render: (plan) => <span className={styles.prices}>{priceSummary(plan)}</span> },
    { key: 'createdAt', header: 'Criado em', sortField: 'createdAt', render: (plan) => formatDate(plan.createdAt) },
    { key: 'status', header: 'Status', render: (plan) => <PlanStatus active={plan.active} /> },
    {
      key: 'actions', header: '', align: 'end', width: '56px',
      render: (plan) => (
        <Menu label={`Ações de ${plan.name}`} items={[
          { label: 'Ver plano', onSelect: () => navigate(ROUTES.planDetail(plan.id)) },
          ...(canManage ? [{ label: 'Editar plano', onSelect: () => navigate(ROUTES.planEdit(plan.id)) }] : []),
        ]} />
      ),
    },
  ];

  const total = plans.data?.total;
  return (
    <Page>
      <PageHeader
        title="Planos"
        subtitle={total === undefined ? ' ' : `${total} ${total === 1 ? 'plano' : 'planos'}`}
        actions={<Can permission={PERMISSIONS.MANAGE_PLANS}><ButtonLink to={ROUTES.planNew}>Novo plano</ButtonLink></Can>}
      />
      <Toolbar>
        <SearchInput label="Buscar planos" placeholder="Buscar por nome…" value={search} onChange={(event) => setSearch(event.target.value)} />
        <FilterSelect label="Filtrar por ciclo" value={listing.filters.activeIntervals?.[0] ?? ''}
          onChange={(event) => setFilter('activeIntervals', event.target.value ? [event.target.value] : [])}>
          <option value="">Todos os ciclos</option>
          {INTERVAL_ORDER.map((interval) => <option key={interval} value={interval}>Com preço {INTERVAL_LABEL[interval].toLowerCase()}</option>)}
        </FilterSelect>
        <FilterSelect label="Filtrar por status" value={listing.filters.active?.[0] ?? ''}
          onChange={(event) => setFilter('active', event.target.value ? [event.target.value] : [])}>
          <option value="">Ativos e inativos</option>
          <option value="true">Só ativos</option>
          <option value="false">Só inativos</option>
        </FilterSelect>
      </Toolbar>
      {plans.isError && <Alert>{describeError(plans.error)}</Alert>}
      <DataTable
        caption="Planos" columns={columns} rows={plans.data?.items ?? []} rowKey={(plan) => plan.id}
        sort={listing.sort} onSortChange={setSort} loading={plans.isPending}
        empty={listing.q || Object.keys(listing.filters).length ? 'Nenhum plano encontrado.' : 'Nenhum plano cadastrado ainda.'}
      />
      {plans.data && (
        <Pagination page={listing.page} size={listing.size} total={plans.data.total} onPageChange={setPage} onSizeChange={setSize} />
      )}
    </Page>
  );
}
