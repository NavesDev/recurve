import type { ReactNode } from 'react';
import { Link, Navigate, useLocation } from 'react-router';
import { describeError } from '../../shared/api/describeError';
import type { Permission } from '../../shared/constants/permissions';
import { ROUTES } from '../../shared/constants/routes';
import { Button } from '../../shared/design/Button';
import { useMe, useSession } from '../api';
import { can } from '../domain';
import { LoadingScreen, StatusScreen } from './StatusScreen';

/** No session, no panel: the visitor signs in and comes back where they were going. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const session = useSession();
  const location = useLocation();
  const me = useMe();

  if (!session) {
    const next = location.pathname + location.search;
    return <Navigate to={`${ROUTES.login}?next=${encodeURIComponent(next)}`} replace />;
  }
  if (me.isPending) return <LoadingScreen />;
  if (me.isError) {
    return (
      <StatusScreen
        title="Não foi possível carregar sua conta"
        text={describeError(me.error)}
        action={<Button onClick={() => void me.refetch()}>Tentar de novo</Button>}
      />
    );
  }
  return children;
}

/** A route's permission. Fail-closed: without it, the screen does not render at all. */
export function RequirePermission({ permission, children }: { permission: Permission; children: ReactNode }) {
  const me = useMe();
  if (me.isPending) return <LoadingScreen />;
  if (!can(me.data, permission)) {
    return (
      <StatusScreen
        code="403"
        title="Sem permissão"
        text="Sua conta não tem acesso a esta área. Peça a um administrador, se precisar."
        action={<Link to={ROUTES.home}>Voltar ao início</Link>}
      />
    );
  }
  return children;
}
