import { useNavigate, useParams } from 'react-router';
import { LoadingScreen, StatusScreen } from '../../auth';
import { describeError } from '../../shared/api/describeError';
import { isFormLevelError } from '../../shared/api/formErrors';
import { ROUTES } from '../../shared/constants/routes';
import { Alert, Page, PageHeader } from '../../shared/design/Layout';
import { useToast } from '../../shared/design/Toast';
import { useCreateSubscriber, useSubscriber, useUpdateSubscriber } from '../api';
import { SubscriberForm } from '../components/SubscriberForm';
import { canEdit } from '../domain';

export function SubscriberNewPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const create = useCreateSubscriber();
  return (
    <Page>
      <PageHeader title="Novo assinante" back={{ to: ROUTES.subscribers, label: 'Assinantes' }} />
      {isFormLevelError(create.error) && <Alert>{describeError(create.error)}</Alert>}
      <SubscriberForm
        submitLabel="Cadastrar assinante" busy={create.isPending} error={create.error}
        onCancel={() => navigate(ROUTES.subscribers)}
        onSubmit={(subscriber) => create.mutate(subscriber, {
          onSuccess: (created) => {
            toast.success('Assinante cadastrado.');
            navigate(ROUTES.subscriberDetail(created.id));
          },
        })}
      />
    </Page>
  );
}

export function SubscriberEditPage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const toast = useToast();
  const subscriber = useSubscriber(id);
  const update = useUpdateSubscriber(id);

  if (subscriber.isPending) return <LoadingScreen />;
  if (subscriber.isError) return <StatusScreen title="Assinante não encontrado" text={describeError(subscriber.error)} />;
  if (!canEdit(subscriber.data)) {
    return <StatusScreen title="Assinante cancelado" text="Uma assinatura cancelada não pode ser alterada." />;
  }
  return (
    <Page>
      <PageHeader title="Editar assinante" back={{ to: ROUTES.subscriberDetail(id), label: subscriber.data.name }} />
      {isFormLevelError(update.error) && <Alert>{describeError(update.error)}</Alert>}
      <SubscriberForm
        initial={subscriber.data} submitLabel="Salvar" busy={update.isPending} error={update.error}
        onCancel={() => navigate(ROUTES.subscriberDetail(id))}
        onSubmit={({ name, email, document }) => update.mutate({ name, email, document }, {
          onSuccess: () => {
            toast.success('Assinante atualizado.');
            navigate(ROUTES.subscriberDetail(id));
          },
        })}
      />
    </Page>
  );
}
