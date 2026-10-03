import { Outlet, useMatches, useNavigate } from 'react-router';
import { can, initialsOf, RequireAuth, signOut, useMe } from './auth';
import { overviewNav } from './overview';
import { paymentNav } from './payment';
import { planNav } from './plan';
import { ROUTES } from './shared/constants/routes';
import { AppShell } from './shared/layout/AppShell';
import { subscriberNav } from './subscriber';
import { systemNav } from './system';
import { userNav } from './user';

const NAV = [overviewNav, planNav, subscriberNav, paymentNav, userNav, systemNav];

/** The signed-in frame: composes each feature's entry with the operator's permissions. */
export function Shell() {
  return (
    <RequireAuth>
      <SignedInShell />
    </RequireAuth>
  );
}

function SignedInShell() {
  const { data: me } = useMe();
  const navigate = useNavigate();
  const matches = useMatches();
  const section = matches
    .map((match) => (match.handle as { section?: string } | undefined)?.section)
    .filter(Boolean)
    .pop();

  if (!me) return null;
  const nav = NAV.filter((entry) => !entry.permission || can(me, entry.permission));
  return (
    <AppShell
      nav={nav}
      operator={{ name: me.name, email: me.email, initials: initialsOf(me.name) }}
      section={section}
      onSignOut={() => {
        signOut();
        navigate(ROUTES.login, { replace: true });
      }}
    >
      <Outlet />
    </AppShell>
  );
}
