import { useState } from 'react';
import { Link } from 'react-router';
import { useCan } from '../../auth';
import { describeError } from '../../shared/api/describeError';
import { PERMISSIONS } from '../../shared/constants/permissions';
import { ROUTES } from '../../shared/constants/routes';
import { Badge } from '../../shared/design/Badge';
import { ConfirmDialog } from '../../shared/design/ConfirmDialog';
import { DataTable, type Column } from '../../shared/design/DataTable';
import { Cell, Mono } from '../../shared/design/Layout';
import { Menu, type MenuItem } from '../../shared/design/Menu';
import { useToast } from '../../shared/design/Toast';
import { formatDate, formatDateTime } from '../../shared/format/date';
import { formatMoney } from '../../shared/format/money';
import { usePayers, usePaymentAction, type PaymentAction } from '../api';
import { PAYMENT_STATUS } from '../constants';
import { canConfirm, canRefund, canSend, canSync, type Payment } from '../domain';

interface PaymentTableProps {
  caption: string;
  payments: readonly Payment[];
  loading: boolean;
  sort?: string;
  onSortChange?: (sort: string) => void;
  /** Within one subscriber's page the payer column says nothing new. */
  showPayer?: boolean;
  empty?: string;
}

const DONE: Record<PaymentAction, string> = {
  send: 'Cobrança enviada ao gateway.',
  sync: 'Cobrança atualizada com o gateway.',
  confirm: 'Pagamento confirmado.',
  refund: 'Cobrança reembolsada.',
};

type Pending = { payment: Payment; action: 'confirm' | 'refund' } | null;

export function PaymentTable({ caption, payments, loading, sort, onSortChange, showPayer = true, empty }: PaymentTableProps) {
  const toast = useToast();
  const canManage = useCan(PERMISSIONS.MANAGE_PAYMENTS);
  const canViewSubscribers = useCan(PERMISSIONS.VIEW_SUBSCRIBERS);
  const payers = usePayers(showPayer ? payments.map((payment) => payment.subscriberId) : [], showPayer && canViewSubscribers);
  const act = usePaymentAction();
  const [confirming, setConfirming] = useState<Pending>(null);

  const run = (payment: Payment, action: PaymentAction) => act.mutate({ payment, action }, {
    onSuccess: () => {
      setConfirming(null);
      toast.success(DONE[action]);
    },
    onError: (error) => toast.error(describeError(error)),
  });

  const nameOf = (payment: Payment) => payers.data?.get(payment.subscriberId)?.name;

  function actionsFor(payment: Payment): MenuItem[] {
    if (!canManage) return [];
    return [
      ...(canSend(payment) ? [{ label: 'Reenviar ao gateway', onSelect: () => run(payment, 'send') }] : []),
      ...(canSync(payment) ? [{ label: 'Atualizar com o gateway', onSelect: () => run(payment, 'sync') }] : []),
      ...(canConfirm(payment) ? [{ label: 'Confirmar pagamento manual', onSelect: () => setConfirming({ payment, action: 'confirm' }) }] : []),
      ...(canRefund(payment) ? [{ label: 'Reembolsar', tone: 'danger' as const, onSelect: () => setConfirming({ payment, action: 'refund' }) }] : []),
    ];
  }

  const columns: Column<Payment>[] = [
    ...(showPayer ? [{
      key: 'payer', header: 'Assinante',
      render: (payment: Payment) => {
        const payer = payers.data?.get(payment.subscriberId);
        if (!payer) return <span>—</span>;
        return <Cell main={<Link to={ROUTES.subscriberDetail(payer.id)}>{payer.name}</Link>} sub={payer.email} />;
      },
    }] : []),
    { key: 'amount', header: 'Valor', sortField: 'amount', render: (payment) => <Mono>{formatMoney(payment.amount, payment.currency)}</Mono> },
    { key: 'dueAt', header: 'Vencimento', sortField: 'dueAt', render: (payment) => formatDate(payment.dueAt) },
    { key: 'status', header: 'Status', render: (payment) => <Badge tone={PAYMENT_STATUS[payment.status].tone}>{PAYMENT_STATUS[payment.status].label}</Badge> },
    { key: 'paidAt', header: 'Pago em', sortField: 'paidAt', render: (payment) => formatDateTime(payment.paidAt) },
    {
      key: 'invoice', header: 'Fatura',
      render: (payment) => payment.invoiceUrl
        ? <a href={payment.invoiceUrl} target="_blank" rel="noreferrer noopener">Abrir fatura</a>
        : <span title="O gateway ainda não recebeu esta cobrança">Não enviada</span>,
    },
    {
      key: 'actions', header: '', align: 'end', width: '56px',
      render: (payment) => {
        const who = nameOf(payment);
        return <Menu label={`Ações da cobrança${who ? ` de ${who}` : ''}, vencimento ${formatDate(payment.dueAt)}`} items={actionsFor(payment)} />;
      },
    },
  ];

  return (
    <>
      <DataTable caption={caption} columns={columns} rows={payments} rowKey={(payment) => payment.id}
        loading={loading} sort={sort} onSortChange={onSortChange} empty={empty ?? 'Nenhuma cobrança encontrada.'} />
      <ConfirmDialog
        open={confirming?.action === 'confirm'} title="Confirmar pagamento manual?" confirmLabel="Confirmar pagamento"
        busy={act.isPending} onCancel={() => setConfirming(null)}
        onConfirm={() => confirming && run(confirming.payment, 'confirm')}
      >
        Use quando o cliente pagou por fora do gateway. Se a cobrança foi enviada, o gateway é avisado e para de cobrar.
      </ConfirmDialog>
      <ConfirmDialog
        open={confirming?.action === 'refund'} title="Reembolsar cobrança?" confirmLabel="Reembolsar" tone="danger"
        busy={act.isPending} onCancel={() => setConfirming(null)}
        onConfirm={() => confirming && run(confirming.payment, 'refund')}
      >
        O valor é devolvido ao cliente pelo gateway. A assinatura não é cancelada.
      </ConfirmDialog>
    </>
  );
}
