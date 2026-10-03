import { describe, expect, it } from 'vitest';
import { ApiError } from './ApiError';
import { describeError } from './describeError';

describe('describeError', () => {
  it('translates a business rule the server explains in English', () => {
    expect(describeError(new ApiError(422, 'Email ana@x.com is already in use by a subscriber')))
      .toBe('Este e-mail já está em uso por outro assinante.');
    expect(describeError(new ApiError(422, 'The plan already has an active MONTHLY price in BRL; replace it instead')))
      .toBe('O plano já tem um preço ativo neste ciclo. Substitua o valor em vez de adicionar outro.');
  });

  it('falls back on the status for anything it does not know', () => {
    expect(describeError(new ApiError(422, 'Something new'))).toBe('A operação não é permitida no estado atual.');
    expect(describeError(new ApiError(0, 'Network failure'))).toBe('Sem conexão com o servidor. Tente de novo.');
    expect(describeError(new ApiError(403, 'Access denied'))).toBe('Você não tem permissão para esta ação.');
    expect(describeError(new ApiError(503, 'A backing service is unavailable')))
      .toBe('O servidor está indisponível no momento. Tente de novo em instantes.');
  });

  it('describes anything that is not an ApiError generically', () => {
    expect(describeError(new Error('boom'))).toBe('Algo deu errado. Tente de novo.');
    expect(describeError('?')).toBe('Algo deu errado. Tente de novo.');
  });
});
