import { useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { LoadingScreen, StatusScreen, useMe } from '../../auth';
import { describeError } from '../../shared/api/describeError';
import { isFormLevelError } from '../../shared/api/formErrors';
import { ROUTES } from '../../shared/constants/routes';
import { Badge } from '../../shared/design/Badge';
import { Button } from '../../shared/design/Button';
import { ConfirmDialog } from '../../shared/design/ConfirmDialog';
import { Alert, Page, PageHeader } from '../../shared/design/Layout';
import { useToast } from '../../shared/design/Toast';
import { useCreateUser, useDeactivateUser, useUpdateUser, useUser } from '../api';
import { UserForm } from '../components/UserForm';

export function UserNewPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const create = useCreateUser();
  return (
    <Page>
      <PageHeader title="Novo operador" back={{ to: ROUTES.users, label: 'Operadores' }} />
      {isFormLevelError(create.error) && <Alert>{describeError(create.error)}</Alert>}
      <UserForm
        submitLabel="Cadastrar operador" busy={create.isPending} error={create.error}
        onCancel={() => navigate(ROUTES.users)}
        onSubmit={(user) => create.mutate(user, {
          onSuccess: () => {
            toast.success('Operador cadastrado.');
            navigate(ROUTES.users);
          },
        })}
      />
    </Page>
  );
}

export function UserEditPage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const toast = useToast();
  const { data: me } = useMe();
  const user = useUser(id);
  const update = useUpdateUser(id);
  const deactivate = useDeactivateUser(id);
  const [confirming, setConfirming] = useState(false);

  if (user.isPending) return <LoadingScreen />;
  if (user.isError) return <StatusScreen title="Operador não encontrado" text={describeError(user.error)} />;
  const self = me?.id === user.data.id;

  return (
    <Page>
      <PageHeader
        title={user.data.name}
        subtitle={user.data.active ? undefined : <Badge tone="neutral">Desativado — não consegue entrar no painel</Badge>}
        back={{ to: ROUTES.users, label: 'Operadores' }}
        // Deactivating oneself would lock the operator out mid-session; another operator does it.
        actions={user.data.active && !self && <Button variant="danger" onClick={() => setConfirming(true)}>Desativar operador</Button>}
      />
      {isFormLevelError(update.error) && <Alert>{describeError(update.error)}</Alert>}
      <UserForm
        initial={user.data} submitLabel="Salvar" busy={update.isPending} error={update.error}
        onCancel={() => navigate(ROUTES.users)}
        onSubmit={({ name, email, permissions }) => update.mutate({ name, email, permissions }, {
          onSuccess: () => {
            toast.success('Operador atualizado.');
            navigate(ROUTES.users);
          },
        })}
      />
      <ConfirmDialog
        open={confirming} title="Desativar operador?" confirmLabel="Desativar operador" tone="danger" busy={deactivate.isPending}
        onCancel={() => setConfirming(false)}
        onConfirm={() => deactivate.mutate(undefined, {
          onSuccess: () => {
            setConfirming(false);
            toast.success('Operador desativado.');
          },
          onError: (error) => toast.error(describeError(error)),
        })}
      >
        {user.data.name} não conseguirá mais entrar, e uma sessão aberta deixa de valer na próxima ação. O cadastro é mantido.
      </ConfirmDialog>
    </Page>
  );
}
