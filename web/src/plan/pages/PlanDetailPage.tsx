import { useState } from 'react';
import { useParams } from 'react-router';
import { Can, LoadingScreen, StatusScreen, useCan } from '../../auth';
import { ApiError } from '../../shared/api/ApiError';
import { describeError } from '../../shared/api/describeError';
import { PERMISSIONS } from '../../shared/constants/permissions';
import { ROUTES } from '../../shared/constants/routes';
import { Button, ButtonLink } from '../../shared/design/Button';
import { ConfirmDialog } from '../../shared/design/ConfirmDialog';
import { DataTable, type Column } from '../../shared/design/DataTable';
import { Card, Details, Mono, Page, PageHeader } from '../../shared/design/Layout';
import { Menu } from '../../shared/design/Menu';
import { useToast } from '../../shared/design/Toast';
import { formatDate } from '../../shared/format/date';
import { formatMoney } from '../../shared/format/money';
import { useAddPrice, useDeactivatePlan, useDeactivatePrice, usePlan, useReplacePrice } from '../api';
import { PlanStatus } from '../components/PlanStatus';
import { PriceDialog } from '../components/PriceDialog';
import { INTERVAL_LABEL, PLAN_CURRENCY } from '../constants';
import {
  activePrices, canAddPrice, canDeactivatePlan, canDeactivatePrice, canReplacePrice, freeIntervals, inactivePrices,
  type Plan, type Price,
} from '../domain';

export function PlanDetailPage() {
  const { id = '' } = useParams();
  const plan = usePlan(id);

  if (plan.isPending) return <LoadingScreen />;
  if (plan.isError) {
    const missing = plan.error instanceof ApiError && plan.error.status === 404;
    return <StatusScreen code={missing ? '404' : undefined} title={missing ? 'Plano não encontrado' : 'Não foi possível carregar o plano'}
      text={describeError(plan.error)} action={<ButtonLink variant="secondary" to={ROUTES.plans}>Voltar aos planos</ButtonLink>} />;
  }
  return <PlanDetail plan={plan.data} />;
}

type Dialog = { kind: 'add' } | { kind: 'replace'; price: Price } | { kind: 'deactivatePrice'; price: Price } | { kind: 'deactivatePlan' } | null;

function PlanDetail({ plan }: { plan: Plan }) {
  const toast = useToast();
  const canManage = useCan(PERMISSIONS.MANAGE_PLANS);
  const [dialog, setDialog] = useState<Dialog>(null);
  const addPrice = useAddPrice(plan.id);
  const replacePrice = useReplacePrice();
  const deactivatePrice = useDeactivatePrice();
  const deactivatePlan = useDeactivatePlan(plan.id);

  const done = (message: string) => () => {
    setDialog(null);
    toast.success(message);
  };
  const failed = (error: unknown) => toast.error(describeError(error));

  const activeColumns: Column<Price>[] = [
    { key: 'interval', header: 'Ciclo', render: (price) => INTERVAL_LABEL[price.interval] },
    { key: 'price', header: 'Valor', render: (price) => <Mono>{formatMoney(price.price, price.currency)}</Mono> },
    { key: 'since', header: 'Desde', render: (price) => formatDate(price.createdAt) },
    {
      key: 'actions', header: '', align: 'end', width: '56px',
      render: (price) => (
        <Menu label={`Ações do preço ${INTERVAL_LABEL[price.interval]}`} items={canManage ? [
          ...(canReplacePrice(plan, price) ? [{ label: 'Substituir valor', onSelect: () => setDialog({ kind: 'replace', price }) }] : []),
          ...(canDeactivatePrice(price) ? [{ label: 'Desativar preço', tone: 'danger' as const, onSelect: () => setDialog({ kind: 'deactivatePrice', price }) }] : []),
        ] : []} />
      ),
    },
  ];
  const historyColumns: Column<Price>[] = [
    { key: 'interval', header: 'Ciclo', render: (price) => INTERVAL_LABEL[price.interval] },
    { key: 'price', header: 'Valor', render: (price) => <Mono>{formatMoney(price.price, price.currency)}</Mono> },
    { key: 'createdAt', header: 'Criado em', render: (price) => formatDate(price.createdAt) },
  ];
  const history = inactivePrices(plan);
  const replacing = dialog?.kind === 'replace' ? dialog.price : null;

  return (
    <Page>
      <PageHeader
        title={plan.name}
        subtitle={plan.description ?? undefined}
        back={{ to: ROUTES.plans, label: 'Planos' }}
        actions={
          <Can permission={PERMISSIONS.MANAGE_PLANS}>
            <ButtonLink variant="secondary" to={ROUTES.planEdit(plan.id)}>Editar</ButtonLink>
            {canDeactivatePlan(plan) && <Button variant="danger" onClick={() => setDialog({ kind: 'deactivatePlan' })}>Desativar plano</Button>}
          </Can>
        }
      />
      <Card>
        <Details items={[
          { term: 'Status', value: <PlanStatus active={plan.active} /> },
          { term: 'Criado em', value: formatDate(plan.createdAt) },
          { term: 'Preços ativos', value: activePrices(plan).length },
        ]} />
      </Card>
      <Card
        title="Preços ativos"
        actions={canManage && canAddPrice(plan) ? <Button variant="secondary" size="small" onClick={() => setDialog({ kind: 'add' })}>Adicionar preço</Button> : null}
      >
        <DataTable caption="Preços ativos" columns={activeColumns} rows={activePrices(plan)} rowKey={(price) => price.id}
          empty={plan.active ? 'Nenhum preço ativo. Sem preço, o plano não aceita assinantes.' : 'Nenhum preço ativo.'} />
      </Card>
      {history.length > 0 && (
        <Card title="Histórico de preços">
          <DataTable caption="Histórico de preços" columns={historyColumns} rows={history} rowKey={(price) => price.id} />
        </Card>
      )}

      <PriceDialog
        open={dialog?.kind === 'add'} title="Adicionar preço" submitLabel="Adicionar" amountLabel="Valor (R$)"
        intervals={freeIntervals(plan)} busy={addPrice.isPending} error={addPrice.error}
        onClose={() => setDialog(null)}
        onSubmit={(price, interval) => addPrice.mutate({ price, interval, currency: PLAN_CURRENCY }, { onSuccess: done('Preço adicionado.'), onError: failed })}
      />
      <PriceDialog
        open={replacing !== null}
        title={replacing ? `Substituir valor ${INTERVAL_LABEL[replacing.interval].toLowerCase()}` : ''}
        submitLabel="Substituir" amountLabel="Novo valor (R$)"
        intervals={replacing ? [replacing.interval] : []} busy={replacePrice.isPending} error={replacePrice.error}
        onClose={() => setDialog(null)}
        onSubmit={(price) => replacing && replacePrice.mutate({ priceId: replacing.id, price }, { onSuccess: done('Preço substituído.'), onError: failed })}
      />
      <ConfirmDialog
        open={dialog?.kind === 'deactivatePrice'} title="Desativar preço?" confirmLabel="Desativar preço" tone="danger"
        busy={deactivatePrice.isPending} onCancel={() => setDialog(null)}
        onConfirm={() => dialog?.kind === 'deactivatePrice' && deactivatePrice.mutate(dialog.price.id, { onSuccess: done('Preço desativado.'), onError: failed })}
      >
        Novos assinantes não poderão escolher este preço. Quem já assina continua nele.
      </ConfirmDialog>
      <ConfirmDialog
        open={dialog?.kind === 'deactivatePlan'} title="Desativar plano?" confirmLabel="Desativar plano" tone="danger"
        busy={deactivatePlan.isPending} onCancel={() => setDialog(null)}
        onConfirm={() => deactivatePlan.mutate(undefined, { onSuccess: done('Plano desativado.'), onError: failed })}
      >
        O plano deixa de aceitar novos assinantes e novos preços. Quem já assina não é afetado.
      </ConfirmDialog>
    </Page>
  );
}
