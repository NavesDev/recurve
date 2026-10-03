import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { z } from 'zod';
import { applyFieldErrors } from '../../shared/api/formErrors';
import { Button } from '../../shared/design/Button';
import { Card, FormActions, Stack } from '../../shared/design/Layout';
import { SegmentedControl } from '../../shared/design/SegmentedControl';
import { Select, TextField } from '../../shared/design/TextField';
import {
  AREAS, EMAIL_MAX_LENGTH, LEVEL_LABEL, NAME_MAX_LENGTH, PASSWORD_MAX_LENGTH, PASSWORD_MIN_LENGTH, PROFILES,
  type Level, type Profile,
} from '../constants';
import { levelsOf, permissionsFrom, profileOf, type Levels, type NewUser, type UserInput } from '../domain';

const levels = z.object({
  plans: z.enum(['none', 'view', 'manage']), subscribers: z.enum(['none', 'view', 'manage']),
  payments: z.enum(['none', 'view', 'manage']), users: z.enum(['none', 'view', 'manage']), system: z.enum(['none', 'view', 'manage']),
});
const base = {
  name: z.string().trim().min(1, 'Informe o nome.').max(NAME_MAX_LENGTH),
  email: z.string().trim().min(1, 'Informe o e-mail.').max(EMAIL_MAX_LENGTH).regex(/^[^@\s]+@[^@\s]+$/, 'E-mail inválido.'),
  levels,
};
const createSchema = z.object({
  ...base,
  password: z.string().min(PASSWORD_MIN_LENGTH, `Ao menos ${PASSWORD_MIN_LENGTH} caracteres.`)
    .refine((text) => new TextEncoder().encode(text).length <= PASSWORD_MAX_LENGTH, `Até ${PASSWORD_MAX_LENGTH} bytes.`),
});
const editSchema = z.object({ ...base, password: z.string() });
type Values = z.infer<typeof createSchema>;

interface UserFormProps {
  /** Present when editing: the password is not changed here. */
  initial?: UserInput;
  submitLabel: string;
  busy: boolean;
  error: unknown;
  onSubmit: (user: NewUser) => void;
  onCancel: () => void;
}

const NONE: Levels = { plans: 'none', subscribers: 'none', payments: 'none', users: 'none', system: 'none' };

export function UserForm({ initial, submitLabel, busy, error, onSubmit, onCancel }: UserFormProps) {
  const creating = !initial;
  const { register, control, handleSubmit, setValue, setError, formState: { errors } } = useForm<Values>({
    resolver: zodResolver(creating ? createSchema : editSchema),
    defaultValues: {
      name: initial?.name ?? '', email: initial?.email ?? '', password: '',
      levels: initial ? levelsOf(initial.permissions) : NONE,
    },
  });
  const current = useWatch({ control, name: 'levels' });
  const profile = profileOf(current);

  useEffect(() => {
    applyFieldErrors(error, setError, ['name', 'email', 'password']);
  }, [error, setError]);

  const submit = handleSubmit((values) => onSubmit({
    name: values.name.trim(), email: values.email.trim(), password: values.password, permissions: permissionsFrom(values.levels),
  }));

  return (
    <Stack as="form" wide narrow onSubmit={submit} noValidate>
      <Card>
        <TextField label="Nome" autoComplete="off" error={errors.name?.message} {...register('name')} />
        <TextField label="E-mail de acesso" type="email" autoComplete="off" error={errors.email?.message} {...register('email')} />
        {creating && (
          <TextField label="Senha inicial" type="password" autoComplete="new-password"
            hint="O operador pode trocá-la depois." error={errors.password?.message} {...register('password')} />
        )}
      </Card>
      <Card title="Permissões por área">
        <Select label="Perfil" hint="O perfil é só um atalho: preenche as permissões e você ajusta item por item."
          value={profile ?? ''} onChange={(event) => {
            const chosen = event.target.value as Profile | '';
            if (chosen) setValue('levels', PROFILES[chosen], { shouldDirty: true });
          }}>
          <option value="">Personalizado</option>
          {(Object.keys(PROFILES) as Profile[]).map((name) => <option key={name} value={name}>{name}</option>)}
        </Select>
        {AREAS.map((area) => (
          <Controller key={area.key} control={control} name={`levels.${area.key}`} render={({ field }) => (
            <SegmentedControl inline label={area.label} value={field.value} onChange={field.onChange}
              options={(area.view ? ['none', 'view', 'manage'] : ['none', 'manage'] as Level[])
                .map((level) => ({ value: level as Level, label: LEVEL_LABEL[level as Level] }))} />
          )} />
        ))}
      </Card>
      <FormActions>
        <Button variant="secondary" onClick={onCancel} disabled={busy}>Cancelar</Button>
        <Button type="submit" busy={busy}>{submitLabel}</Button>
      </FormActions>
    </Stack>
  );
}
