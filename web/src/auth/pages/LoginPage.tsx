import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { useNavigate, useSearchParams } from 'react-router';
import { z } from 'zod';
import { ApiError } from '../../shared/api/ApiError';
import { describeError } from '../../shared/api/describeError';
import { ROUTES } from '../../shared/constants/routes';
import { Button } from '../../shared/design/Button';
import { Logo } from '../../shared/design/Icon';
import { Alert } from '../../shared/design/Layout';
import { TextField } from '../../shared/design/TextField';
import { useSignIn } from '../api';
import { safeNext } from '../domain';
import styles from './LoginPage.module.css';

const schema = z.object({
  email: z.string().trim().min(1, 'Informe o e-mail.'),
  password: z.string().min(1, 'Informe a senha.'),
});

type Credentials = z.infer<typeof schema>;

export function LoginPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const signIn = useSignIn();
  const { register, handleSubmit, formState: { errors } } = useForm<Credentials>({ resolver: zodResolver(schema) });

  const onSubmit = handleSubmit((credentials) => {
    signIn.mutate(credentials, {
      onSuccess: () => navigate(safeNext(params.get('next'), ROUTES.home), { replace: true }),
    });
  });

  // Wrong email, wrong password and inactive operator read the same, as on the server.
  const failure = signIn.error instanceof ApiError && signIn.error.status === 401
    ? 'E-mail ou senha incorretos.'
    : signIn.error ? describeError(signIn.error) : null;

  return (
    <main className={styles.screen}>
      <div className={styles.card}>
        <div className={styles.brand}><Logo /> Recurve</div>
        <div>
          <h1 className={styles.title}>Entrar</h1>
          <p className={styles.text}>Use o e-mail e a senha da sua conta de operador.</p>
        </div>
        {failure && <Alert>{failure}</Alert>}
        <form className={styles.form} onSubmit={onSubmit} noValidate>
          <TextField label="E-mail" type="email" autoComplete="username" error={errors.email?.message} {...register('email')} />
          <TextField label="Senha" type="password" autoComplete="current-password" error={errors.password?.message} {...register('password')} />
          <Button type="submit" className={styles.submit} busy={signIn.isPending}>Entrar</Button>
        </form>
      </div>
    </main>
  );
}
