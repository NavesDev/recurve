import { expect, type APIRequestContext, type Page } from '@playwright/test';

export const admin = {
  email: process.env.E2E_EMAIL ?? '',
  password: process.env.E2E_PASSWORD ?? '',
};

if (!admin.email || !admin.password) {
  throw new Error('Set E2E_EMAIL and E2E_PASSWORD to an operator holding every permission.');
}

/** Unique per run: e-mails are unique on the server (BR-02) and runs share the database. */
export const run = Date.now().toString(36);

export async function signIn(page: Page, email = admin.email, password = admin.password) {
  await page.goto('/login');
  await page.getByLabel('E-mail').fill(email);
  await page.getByLabel('Senha').fill(password);
  await page.getByRole('button', { name: 'Entrar' }).click();
  await expect(page.getByRole('navigation', { name: 'Principal' })).toBeVisible();
}

/** A token for arranging data through the API, as the panel would. */
export async function tokenFor(request: APIRequestContext, email = admin.email, password = admin.password) {
  const response = await request.post('/api/auth/token', { data: { email, password } });
  expect(response.ok()).toBeTruthy();
  return ((await response.json()) as { token: string }).token;
}
