import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { z } from 'zod';
import { applyFieldErrors } from '../../shared/api/formErrors';
import { Button } from '../../shared/design/Button';
import { Card, FormActions, Stack } from '../../shared/design/Layout';
import { SegmentedControl } from '../../shared/design/SegmentedControl';
import { TextArea, TextField } from '../../shared/design/TextField';
import { parseMoney } from '../../shared/format/money';
import { DESCRIPTION_MAX_LENGTH, INTERVAL_LABEL, INTERVAL_ORDER, NAME_MAX_LENGTH, PLAN_CURRENCY } from '../constants';
import type { PlanInput, PriceInput } from '../domain';
import { optionalAmount } from './moneyField';

const schema = z.object({
  name: z.string().trim().min(1, 'Informe um nome — aparece na fatura do assinante.')
    .max(NAME_MAX_LENGTH, `Até ${NAME_MAX_LENGTH} caracteres.`),
  description: z.string().max(DESCRIPTION_MAX_LENGTH, `Até ${DESCRIPTION_MAX_LENGTH} caracteres.`),
  price: optionalAmount,
  interval: z.enum(['MONTHLY', 'YEARLY']),
});

type Values = z.infer<typeof schema>;

interface PlanFormProps {
  /** Present when editing: no first price then (FR-02.6 edits name and description only). */
  initial?: PlanInput;
  submitLabel: string;
  busy: boolean;
  error: unknown;
  onSubmit: (plan: PlanInput, firstPrice: PriceInput | null) => void;
  onCancel: () => void;
}

export function PlanForm({ initial, submitLabel, busy, error, onSubmit, onCancel }: PlanFormProps) {
  const creating = !initial;
  const { register, handleSubmit, control, setError, formState: { errors } } = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { name: initial?.name ?? '', description: initial?.description ?? '', price: '', interval: 'MONTHLY' },
  });

  useEffect(() => {
    applyFieldErrors(error, setError, ['name', 'description']);
  }, [error, setError]);

  const submit = handleSubmit((values) => {
    const description = values.description.trim() === '' ? null : values.description.trim();
    const amountValue = creating ? parseMoney(values.price) : null;
    onSubmit(
      { name: values.name.trim(), description },
      amountValue === null ? null : { price: amountValue, currency: PLAN_CURRENCY, interval: values.interval },
    );
  });

  return (
    <Stack as="form" wide narrow onSubmit={submit} noValidate>
      <Card>
        <TextField label="Nome do plano" placeholder="Ex. Profissional" error={errors.name?.message} {...register('name')} />
        <TextArea label="Descrição" placeholder="Para quem é este plano" error={errors.description?.message} {...register('description')} />
        {creating && (
          <>
            <TextField label="Preço inicial (R$)" inputMode="decimal" placeholder="0,00" mono
              hint="Opcional. Outros ciclos podem ser adicionados depois." error={errors.price?.message} {...register('price')} />
            <Controller control={control} name="interval" render={({ field }) => (
              <SegmentedControl label="Ciclo" value={field.value} onChange={field.onChange}
                options={INTERVAL_ORDER.map((interval) => ({ value: interval, label: INTERVAL_LABEL[interval] }))} />
            )} />
          </>
        )}
      </Card>
      <FormActions>
        <Button variant="secondary" onClick={onCancel} disabled={busy}>Cancelar</Button>
        <Button type="submit" busy={busy}>{submitLabel}</Button>
      </FormActions>
    </Stack>
  );
}
