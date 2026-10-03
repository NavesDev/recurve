import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { z } from 'zod';
import { applyFieldErrors } from '../../shared/api/formErrors';
import { Button } from '../../shared/design/Button';
import { Dialog } from '../../shared/design/Dialog';
import { Stack } from '../../shared/design/Layout';
import { SegmentedControl } from '../../shared/design/SegmentedControl';
import { TextField } from '../../shared/design/TextField';
import { parseMoney } from '../../shared/format/money';
import { INTERVAL_LABEL } from '../constants';
import type { BillingInterval } from '../domain';
import { amount } from './moneyField';

const schema = z.object({ price: amount, interval: z.enum(['MONTHLY', 'YEARLY']) });
type Values = z.infer<typeof schema>;

interface PriceDialogProps {
  open: boolean;
  title: string;
  submitLabel: string;
  amountLabel: string;
  /** Cycles to choose from; one fixed cycle hides the choice. */
  intervals: readonly BillingInterval[];
  busy: boolean;
  error: unknown;
  onSubmit: (price: number, interval: BillingInterval) => void;
  onClose: () => void;
}

export function PriceDialog({ open, title, submitLabel, amountLabel, intervals, busy, error, onSubmit, onClose }: PriceDialogProps) {
  const { register, handleSubmit, control, reset, setError, formState: { errors } } = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { price: '', interval: intervals[0] ?? 'MONTHLY' },
  });

  useEffect(() => {
    if (open) reset({ price: '', interval: intervals[0] ?? 'MONTHLY' });
  }, [open, intervals, reset]);

  useEffect(() => {
    applyFieldErrors(error, setError, ['price', 'interval']);
  }, [error, setError]);

  const submit = handleSubmit((values) => onSubmit(parseMoney(values.price) ?? 0, values.interval));

  return (
    <Dialog
      open={open} title={title} onClose={onClose}
      actions={
        <>
          <Button variant="secondary" onClick={onClose} disabled={busy}>Cancelar</Button>
          <Button type="submit" form="price-form" busy={busy}>{submitLabel}</Button>
        </>
      }
    >
      <Stack as="form" id="price-form" onSubmit={submit} noValidate>
        {intervals.length > 1 && (
          <Controller control={control} name="interval" render={({ field }) => (
            <SegmentedControl label="Ciclo" value={field.value} onChange={field.onChange}
              options={intervals.map((interval) => ({ value: interval, label: INTERVAL_LABEL[interval] }))} />
          )} />
        )}
        <TextField label={amountLabel} inputMode="decimal" placeholder="0,00" mono error={errors.price?.message} {...register('price')} />
      </Stack>
    </Dialog>
  );
}
