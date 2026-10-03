import { RuleTester } from 'eslint';
import { describe, it } from 'vitest';
import { featureBoundaries, noLiteralPermission, noLiteralPath } from './architecture.js';

RuleTester.describe = describe;
RuleTester.it = it;

const tester = new RuleTester({ languageOptions: { ecmaVersion: 2024, sourceType: 'module' } });
const src = '/repo/web/src';

describe('feature-boundaries', () => {
  tester.run('feature-boundaries', featureBoundaries, {
    valid: [
      { name: 'a feature reads its own files', filename: `${src}/plan/pages/PlanListPage.tsx`, code: "import { usePlans } from '../api';" },
      { name: 'a feature reads shared', filename: `${src}/plan/api.ts`, code: "import { http } from '../shared/api/http';" },
      { name: 'a feature reads another through its index file', filename: `${src}/subscriber/api.ts`, code: "import { usePlanLookup } from '../plan/index';" },
      { name: 'a feature reads another through its folder', filename: `${src}/subscriber/pages/X.tsx`, code: "import { usePlanLookup } from '../../plan';" },
      { name: 'a feature reads auth through its index', filename: `${src}/plan/pages/X.tsx`, code: "import { useCan } from '../../auth';" },
      { name: 'shared reads shared', filename: `${src}/shared/layout/Sidebar.tsx`, code: "import { Button } from '../design/Button';" },
      { name: 'the app root reads feature indexes', filename: `${src}/App.tsx`, code: "import { planRoutes } from './plan';" },
      { name: 'packages are not this rule\'s concern', filename: `${src}/shared/api/http.ts`, code: "import { QueryClient } from '@tanstack/react-query';" },
    ],
    invalid: [
      { name: 'shared never reads a feature', filename: `${src}/shared/layout/Sidebar.tsx`, code: "import { useCan } from '../../auth';", errors: [{ messageId: 'sharedImportsFeature' }] },
      { name: 'a feature never reaches inside another', filename: `${src}/subscriber/api.ts`, code: "import { planKeys } from '../plan/api';", errors: [{ messageId: 'privateFile' }] },
      { name: 'another feature\'s constants are private too', filename: `${src}/payment/pages/X.tsx`, code: "import { STATUS } from '../../subscriber/constants';", errors: [{ messageId: 'privateFile' }] },
      { name: 'the app root reaches no inside either', filename: `${src}/App.tsx`, code: "import { PlanListPage } from './plan/pages/PlanListPage';", errors: [{ messageId: 'privateFile' }] },
      { name: 'a re-export counts as an import', filename: `${src}/subscriber/index.ts`, code: "export { planKeys } from '../plan/api';", errors: [{ messageId: 'privateFile' }] },
    ],
  });
});

describe('no-literal-permission', () => {
  tester.run('no-literal-permission', noLiteralPermission, {
    valid: [
      { filename: `${src}/shared/constants/permissions.ts`, code: "export const P = { VIEW_PLANS: 'VIEW_PLANS' };" },
      { filename: `${src}/plan/pages/X.tsx`, code: "const label = 'Visualizar planos';" },
    ],
    invalid: [
      { filename: `${src}/plan/pages/X.tsx`, code: "useCan('MANAGE_PLANS');", errors: [{ messageId: 'literal' }] },
      { filename: `${src}/user/constants.ts`, code: "const x = `VIEW_PAYMENTS`;", errors: [{ messageId: 'literal' }] },
    ],
  });
});

describe('no-literal-path', () => {
  tester.run('no-literal-path', noLiteralPath, {
    valid: [
      { filename: `${src}/shared/constants/routes.ts`, code: "export const ROUTES = { plans: '/plans' };" },
      { filename: `${src}/plan/api.ts`, code: "http.get('/api/plans');" },
      { filename: `${src}/shared/api/http.ts`, code: "const base = '/api';" },
      { filename: `${src}/plan/pages/X.tsx`, code: "const fraction = '1/2'; const unit = 'R$ /mês';" },
      { filename: `${src}/plan/pages/X.tsx`, code: "import x from './y';" },
    ],
    invalid: [
      { filename: `${src}/plan/pages/X.tsx`, code: "navigate('/plans/new');", errors: [{ messageId: 'route' }] },
      { filename: `${src}/plan/pages/X.tsx`, code: "fetch('/api/plans');", errors: [{ messageId: 'api' }] },
      { filename: `${src}/shared/layout/Sidebar.tsx`, code: "const to = `/subscribers`;", errors: [{ messageId: 'route' }] },
    ],
  });
});
