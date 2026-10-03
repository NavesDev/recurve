import { Badge } from '../../shared/design/Badge';

export function PlanStatus({ active }: { active: boolean }) {
  return <Badge tone={active ? 'success' : 'neutral'}>{active ? 'Ativo' : 'Inativo'}</Badge>;
}
