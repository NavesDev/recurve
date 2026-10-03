import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { z } from 'zod';
import { activePrices, priceLabel, usePlanLookup } from '../../plan';
import { applyFieldErrors } from '../../shared/api/formErrors';
import { Button } from '../../shared/design/Button';
import { Card, FormActions, Stack } from '../../shared/design/Layout';
import { Select, TextField } from '../../shared/design/TextField';
import { digitsOf, formatDocument } from '../../shared/format/document';
import { EMAIL_MAX_LENGTH, NAME_MAX_LENGTH } from '../constants';
import type { NewSubscriber, SubscriberInput } from '../domain';

const CPF_DIGITS = 11;
const CNPJ_DIGITS = 14;

const base = {
  name: z.string().trim().min(1, 'Informe o nome.').max(NAME_MAX_LENGTH, `Até ${NAME_MAX_LENGTH} caracteres.`),
  email: z.string().trim().min(1, 'Informe o e-mail.').max(EMAIL_MAX_LENGTH).email('E-mail inválido.'),
  document: z.string().refine((text) => [CPF_DIGITS, CNPJ_DIGITS].includes(digitsOf(text).length), 'Informe um CPF (11 dígitos) ou CNPJ (14 dígitos).'),
};
const createSchema = z.object({ ...base, planPriceId: z.string().min(1, 'Escolha o plano e o preço.') });
const editSchema = z.object({ ...base, planPriceId: z.string() });
type Values = z.infer<typeof createSchema>;

interface SubscriberFormProps {
  /** Present when editing: the price is not changed here (FR-03.5 is open). */
  initial?: SubscriberInput;
  submitLabel: string;
  busy: boolean;
  error: unknown;
  onSubmit: (subscriber: NewSubscriber) => void;
  onCancel: () => void;
}

export function SubscriberForm({ initial, submitLabel, busy, error, onSubmit, onCancel }: SubscriberFormProps) {
  const creating = !initial;
  const plans = usePlanLookup();
  const { register, control, handleSubmit, setError, formState: { errors } } = useForm<Values>({
    resolver: zodResolver(creating ? createSchema : editSchema),
    defaultValues: {
      name: initial?.name ?? '', email: initial?.email ?? '',
      document: initial ? formatDocument(initial.document) : '', planPriceId: '',
    },
  });

  useEffect(() => {
    applyFieldErrors(error, setError, ['name', 'email', 'document', 'planPriceId']);
  }, [error, setError]);

  // FR-02.3, FR-02.4: only active prices of active plans take new subscribers.
  const offers = (plans.data ?? []).filter((plan) => plan.active).flatMap((plan) =>
    activePrices(plan).map((price) => ({ id: price.id, label: `${plan.name} — ${priceLabel(price)}` })));

  const submit = handleSubmit((values) => onSubmit({
    name: values.name.trim(), email: values.email.trim(), document: digitsOf(values.document), planPriceId: values.planPriceId,
  }));

  return (
    <Stack as="form" wide narrow onSubmit={submit} noValidate>
      <Card>
        <TextField label="Nome" autoComplete="off" error={errors.name?.message} {...register('name')} />
        <TextField label="E-mail" type="email" autoComplete="off" error={errors.email?.message} {...register('email')} />
        <Controller control={control} name="document" render={({ field }) => (
          <TextField label="CPF ou CNPJ" inputMode="numeric" mono hint="O gateway de pagamento exige o documento."
            error={errors.document?.message} name={field.name} ref={field.ref} onBlur={field.onBlur}
            value={field.value} onChange={(event) => field.onChange(formatDocument(event.target.value))} />
        )} />
        {creating && (
          <Select label="Plano e preço" error={errors.planPriceId?.message} disabled={plans.isPending}
            hint={plans.isPending ? 'Carregando planos…' : offers.length === 0 ? 'Nenhum plano ativo com preço. Crie um em Planos.' : undefined}
            {...register('planPriceId')}>
            <option value="">Escolha…</option>
            {offers.map((offer) => <option key={offer.id} value={offer.id}>{offer.label}</option>)}
          </Select>
        )}
      </Card>
      <FormActions>
        <Button variant="secondary" onClick={onCancel} disabled={busy}>Cancelar</Button>
        <Button type="submit" busy={busy}>{submitLabel}</Button>
      </FormActions>
    </Stack>
  );
}
