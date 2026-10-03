import { useNavigate, useParams } from 'react-router';
import { LoadingScreen, StatusScreen } from '../../auth';
import { describeError } from '../../shared/api/describeError';
import { isFormLevelError } from '../../shared/api/formErrors';
import { ROUTES } from '../../shared/constants/routes';
import { Alert, Page, PageHeader } from '../../shared/design/Layout';
import { useToast } from '../../shared/design/Toast';
import { useCreatePlan, usePlan, useUpdatePlan } from '../api';
import { PlanForm } from '../components/PlanForm';

export function PlanNewPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const create = useCreatePlan();

  return (
    <Page>
      <PageHeader title="Novo plano" back={{ to: ROUTES.plans, label: 'Planos' }} />
      {isFormLevelError(create.error) && <Alert>{describeError(create.error)}</Alert>}
      <PlanForm
        submitLabel="Criar plano" busy={create.isPending} error={create.error}
        onCancel={() => navigate(ROUTES.plans)}
        onSubmit={(plan, firstPrice) => create.mutate({ plan, firstPrice }, {
          onSuccess: ({ plan: created, priceError }) => {
            if (priceError) toast.error(`Plano criado, mas o preço não: ${describeError(priceError)}`);
            else toast.success('Plano criado.');
            navigate(ROUTES.planDetail(created.id));
          },
        })}
      />
    </Page>
  );
}

export function PlanEditPage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const toast = useToast();
  const plan = usePlan(id);
  const update = useUpdatePlan(id);

  if (plan.isPending) return <LoadingScreen />;
  if (plan.isError) return <StatusScreen title="Plano não encontrado" text={describeError(plan.error)} />;

  return (
    <Page>
      <PageHeader title="Editar plano" back={{ to: ROUTES.planDetail(id), label: plan.data.name }} />
      {isFormLevelError(update.error) && <Alert>{describeError(update.error)}</Alert>}
      <PlanForm
        initial={{ name: plan.data.name, description: plan.data.description }}
        submitLabel="Salvar" busy={update.isPending} error={update.error}
        onCancel={() => navigate(ROUTES.planDetail(id))}
        onSubmit={(input) => update.mutate(input, {
          onSuccess: () => {
            toast.success('Plano atualizado.');
            navigate(ROUTES.planDetail(id));
          },
        })}
      />
    </Page>
  );
}
