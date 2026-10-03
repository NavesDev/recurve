import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { useMe } from '../../auth';
import { describeError } from '../../shared/api/describeError';
import { useListing } from '../../shared/api/useListing';
import { SEARCH_DEBOUNCE_MS } from '../../shared/constants/query';
import { ROUTES } from '../../shared/constants/routes';
import { Badge } from '../../shared/design/Badge';
import { ButtonLink } from '../../shared/design/Button';
import { DataTable, type Column } from '../../shared/design/DataTable';
import { Alert, Cell, FilterSelect, Page, PageHeader, SearchInput, Toolbar } from '../../shared/design/Layout';
import { Pagination } from '../../shared/design/Pagination';
import { formatDate } from '../../shared/format/date';
import { useDebouncedValue } from '../../shared/useDebouncedValue';
import { useUserList } from '../api';
import { USER_LISTING } from '../constants';
import { accessSummary, type User } from '../domain';

export function UserListPage() {
  const { listing, setQuery, setFilter, setSort, setPage, setSize } = useListing(USER_LISTING);
  const [search, setSearch] = useState(listing.q);
  const settled = useDebouncedValue(search, SEARCH_DEBOUNCE_MS);
  const users = useUserList(listing);
  const { data: me } = useMe();

  useEffect(() => {
    if (settled !== listing.q) setQuery(settled);
    // Only a settled search moves the URL; the URL moving does not retype the box.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [settled]);

  const columns: Column<User>[] = [
    {
      key: 'name', header: 'Operador', sortField: 'name.keyword',
      render: (user) => <Cell main={<Link to={ROUTES.userEdit(user.id)}>{user.name}{user.id === me?.id ? ' (você)' : ''}</Link>} sub={user.email} />,
    },
    {
      key: 'access', header: 'Acesso',
      render: (user) => <Badge tone={accessSummary(user.permissions) === 'Acesso total' ? 'action' : 'neutral'}>{accessSummary(user.permissions)}</Badge>,
    },
    { key: 'createdAt', header: 'Criado em', sortField: 'createdAt', render: (user) => formatDate(user.createdAt) },
    { key: 'status', header: 'Status', render: (user) => <Badge tone={user.active ? 'success' : 'neutral'}>{user.active ? 'Ativo' : 'Desativado'}</Badge> },
  ];

  const total = users.data?.total;
  return (
    <Page>
      <PageHeader
        title="Operadores"
        subtitle={total === undefined ? ' ' : `${total} ${total === 1 ? 'operador' : 'operadores'}`}
        actions={<ButtonLink to={ROUTES.userNew}>Novo operador</ButtonLink>}
      />
      <Toolbar>
        <SearchInput label="Buscar operadores" placeholder="Buscar por nome ou e-mail…" value={search} onChange={(event) => setSearch(event.target.value)} />
        <FilterSelect label="Filtrar por status" value={listing.filters.active?.[0] ?? ''}
          onChange={(event) => setFilter('active', event.target.value ? [event.target.value] : [])}>
          <option value="">Ativos e desativados</option>
          <option value="true">Só ativos</option>
          <option value="false">Só desativados</option>
        </FilterSelect>
      </Toolbar>
      {users.isError && <Alert>{describeError(users.error)}</Alert>}
      <DataTable caption="Operadores" columns={columns} rows={users.data?.items ?? []} rowKey={(user) => user.id}
        sort={listing.sort} onSortChange={setSort} loading={users.isPending} empty="Nenhum operador encontrado." />
      {users.data && (
        <Pagination page={listing.page} size={listing.size} total={users.data.total} onPageChange={setPage} onSizeChange={setSize} />
      )}
    </Page>
  );
}
