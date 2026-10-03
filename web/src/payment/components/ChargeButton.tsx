import { useState } from 'react';
import { Can } from '../../auth';
import { describeError } from '../../shared/api/describeError';
import { PERMISSIONS } from '../../shared/constants/permissions';
import { Button } from '../../shared/design/Button';
import { Dialog } from '../../shared/design/Dialog';
import { Details } from '../../shared/design/Layout';
import { useToast } from '../../shared/design/Toast';
import { formatDate } from '../../shared/format/date';
import { formatMoney } from '../../shared/format/money';
import { useRequestPayment } from '../api';
import type { Payment } from '../domain';

/** FR-04.1: the subscriber's current cycle, charged now; the result says where the customer pays. */
export function ChargeButton({ subscriberId, disabled }: { subscriberId: string; disabled?: boolean }) {
  const toast = useToast();
  const request = useRequestPayment();
  const [charged, setCharged] = useState<Payment | null>(null);

  return (
    <Can permission={PERMISSIONS.MANAGE_PAYMENTS}>
      <Button variant="secondary" disabled={disabled} busy={request.isPending}
        onClick={() => request.mutate(subscriberId, { onSuccess: setCharged, onError: (error) => toast.error(describeError(error)) })}>
        Gerar cobrança
      </Button>
      <Dialog open={charged !== null} title="Cobrança gerada" onClose={() => setCharged(null)}
        actions={<Button onClick={() => setCharged(null)}>Fechar</Button>}>
        {charged && (
          <>
            <Details items={[
              { term: 'Valor', value: formatMoney(charged.amount, charged.currency) },
              { term: 'Vencimento', value: formatDate(charged.dueAt) },
            ]} />
            {charged.invoiceUrl
              ? <a href={charged.invoiceUrl} target="_blank" rel="noreferrer noopener">Abrir fatura — o cliente escolhe Pix, boleto ou cartão</a>
              : <span>O gateway ainda não recebeu a cobrança. Use “Reenviar ao gateway” na lista de cobranças.</span>}
          </>
        )}
      </Dialog>
    </Can>
  );
}
