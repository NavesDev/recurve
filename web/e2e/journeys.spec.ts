import { expect, test } from '@playwright/test';
import { admin, run, signIn } from './support';

test('signing in through the dev proxy reaches the panel, and signing out leaves it', async ({ page }) => {
  await signIn(page);
  await expect(page.getByRole('heading', { name: 'Visão geral' })).toBeVisible();

  await page.getByRole('button', { name: /sair/i }).click();
  await expect(page.getByRole('heading', { name: 'Entrar' })).toBeVisible();
  await page.goto('/plans');
  await expect(page).toHaveURL(/\/login\?next=%2Fplans/);
});

test('wrong credentials are refused without saying which part was wrong', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('E-mail').fill(admin.email);
  await page.getByLabel('Senha').fill('not-the-password');
  await page.getByRole('button', { name: 'Entrar' }).click();
  await expect(page.getByRole('alert')).toHaveText('E-mail ou senha incorretos.');
});

test('a plan with a price takes a subscriber, who is charged, paid by hand and canceled', async ({ page }) => {
  const planName = `E2E Plano ${run}`;
  const subscriberName = `E2E Assinante ${run}`;
  await signIn(page);

  await page.getByRole('navigation', { name: 'Principal' }).getByRole('link', { name: 'Planos' }).click();
  await page.getByRole('link', { name: 'Novo plano' }).click();
  await page.getByLabel('Nome do plano').fill(planName);
  await page.getByLabel('Preço inicial (R$)').fill('89,90');
  await page.getByRole('button', { name: 'Criar plano' }).click();
  await expect(page.getByRole('heading', { name: planName })).toBeVisible();
  await expect(page.getByRole('table', { name: 'Preços ativos' })).toContainText('89,90');

  await page.getByRole('navigation', { name: 'Principal' }).getByRole('link', { name: 'Assinantes' }).click();
  await page.getByRole('link', { name: 'Novo assinante' }).click();
  await page.getByLabel('Nome').fill(subscriberName);
  await page.getByLabel('E-mail').fill(`e2e-${run}@example.com`);
  await page.getByLabel('CPF ou CNPJ').fill('52998224725');
  await page.getByLabel('Plano e preço').selectOption({ label: `${planName} — R$ 89,90/mês` });
  await page.getByRole('button', { name: 'Cadastrar assinante' }).click();
  await expect(page.getByRole('heading', { name: subscriberName })).toBeVisible();

  await page.getByRole('button', { name: 'Gerar cobrança' }).click();
  const charged = page.getByRole('dialog', { name: 'Cobrança gerada' });
  await expect(charged).toContainText('R$ 89,90');
  await charged.getByRole('button', { name: 'Fechar' }).click();

  const charges = page.getByRole('table', { name: 'Cobranças' });
  await expect(charges).toContainText('Pendente');
  await charges.getByRole('button', { name: /ações da cobrança/i }).click();
  await page.getByRole('menuitem', { name: 'Confirmar pagamento manual' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Confirmar pagamento' }).click();
  await expect(charges).toContainText('Pago');

  await page.getByRole('button', { name: 'Cancelar assinatura' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Cancelar assinatura' }).click();
  await expect(page.getByText('Cancelado', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Gerar cobrança' })).toHaveCount(0);
});

test('an operator sees only the areas their permissions open', async ({ page }) => {
  const email = `e2e-reader-${run}@recurve.local`;
  await signIn(page);

  await page.getByRole('navigation', { name: 'Principal' }).getByRole('link', { name: 'Operadores' }).click();
  await page.getByRole('link', { name: 'Novo operador' }).click();
  await page.getByLabel('Nome').fill(`E2E Leitura ${run}`);
  await page.getByLabel('E-mail de acesso').fill(email);
  await page.getByLabel('Senha inicial').fill('reader-password-1');
  await page.getByLabel('Perfil').selectOption('Leitura');
  await page.getByRole('button', { name: 'Cadastrar operador' }).click();
  await expect(page.getByRole('heading', { name: 'Operadores' })).toBeVisible();

  await page.getByRole('button', { name: /sair/i }).click();
  await signIn(page, email, 'reader-password-1');

  const nav = page.getByRole('navigation', { name: 'Principal' });
  await expect(nav.getByRole('link')).toHaveText(['Visão geral', 'Planos', 'Assinantes', 'Pagamentos']);
  await nav.getByRole('link', { name: 'Planos' }).click();
  await expect(page.getByRole('heading', { name: 'Planos' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Novo plano' })).toHaveCount(0);

  await page.goto('/users');
  await expect(page.getByRole('heading', { name: 'Sem permissão' })).toBeVisible();
});
