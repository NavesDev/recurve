import { useState } from 'react';
import { useParams } from 'react-router';
import { Can, LoadingScreen, StatusScreen } from '../../auth';
import { ChargeButton, SubscriberPayments } from '../../payment';
import { amountPerInterval, usePlanLookup } from '../../plan';
import { ApiError } from '../../shared/api/ApiError';
import { describeError } from '../../shared/api/describeError';
import { PERMISSIONS } from '../../shared/constants/permissions';
import { ROUTES } from '../../shared/constants/routes';
import { Button, ButtonLink } from '../../shared/design/Button';
import { ConfirmDialog } from '../../shared/design/ConfirmDialog';
import { Card, Details, Mono, Page, PageHeader } from '../../shared/design/Layout';
import { useToast } from '../../shared/design/Toast';
import { formatDate } from '../../shared/format/date';
import { formatDocument } from '../../shared/format/document';
import { useCancelSubscriber, useSubscriber } from '../api';
import { SubscriberStatusBadge } from '../components/SubscriberStatusBadge';
import { canCancel, canCharge, canEdit, type Subscriber } from '../domain';

export function SubscriberDetailPage() {
  const { id = '' } = useParams();
  const subscriber = useSubscriber(id);

  if (subscriber.isPending) return <LoadingScreen />;
  if (subscriber.isError) {
    const missing = subscriber.error instanceof ApiError && subscriber.error.status === 404;
    return <StatusScreen code={missing ? '404' : undefined} title={missing ? 'Assinante não encontrado' : 'Não foi possível carregar o assinante'}
      text={describeError(subscriber.error)} action={<ButtonLink variant="secondary" to={ROUTES.subscribers}>Voltar aos assinantes</ButtonLink>} />;
  }
  return <SubscriberDetail subscriber={subscriber.data} />;
}

function SubscriberDetail({ subscriber }: { subscriber: Subscriber }) {
  const toast = useToast();
  const plans = usePlanLookup();
  const cancel = useCancelSubscriber(subscriber.id);
  const [confirming, setConfirming] = useState(false);
  const plan = plans.data?.find((p) => p.id === subscriber.planId);

  return (
    <Page>
      <PageHeader
        title={subscriber.name}
        subtitle={subscriber.email}
        back={{ to: ROUTES.subscribers, label: 'Assinantes' }}
        actions={
          <>
            {canCharge(subscriber) && <ChargeButton subscriberId={subscriber.id} />}
            <Can permission={PERMISSIONS.MANAGE_SUBSCRIBERS}>
              {canEdit(subscriber) && <ButtonLink variant="secondary" to={ROUTES.subscriberEdit(subscriber.id)}>Editar</ButtonLink>}
              {canCancel(subscriber) && <Button variant="danger" onClick={() => setConfirming(true)}>Cancelar assinatura</Button>}
            </Can>
          </>
        }
      />
      <Card>
        <Details items={[
          { term: 'Status', value: <SubscriberStatusBadge status={subscriber.status} /> },
          { term: 'Plano', value: plan?.name ?? '—' },
          { term: 'Valor', value: <Mono>{amountPerInterval(subscriber.price, subscriber.currency, subscriber.interval)}</Mono> },
          { term: 'CPF/CNPJ', value: <Mono>{formatDocument(subscriber.document)}</Mono> },
          { term: 'Início', value: formatDate(subscriber.startedAt) },
          subscriber.status === 'CANCELED'
            ? { term: 'Cancelado em', value: formatDate(subscriber.canceledAt) }
            : { term: 'Próxima cobrança', value: formatDate(subscriber.nextBillingAt) },
        ]} />
      </Card>
      <SubscriberPayments subscriberId={subscriber.id} />
      <ConfirmDialog
        open={confirming} title="Cancelar assinatura?" confirmLabel="Cancelar assinatura" tone="danger" busy={cancel.isPending}
        onCancel={() => setConfirming(false)}
        onConfirm={() => cancel.mutate(undefined, {
          onSuccess: () => {
            setConfirming(false);
            toast.success('Assinatura cancelada.');
          },
          onError: (error) => toast.error(describeError(error)),
        })}
      >
        {subscriber.name} não terá novas cobranças. A data de cancelamento fica registrada e o cadastro não pode mais ser alterado.
      </ConfirmDialog>
    </Page>
  );
}
