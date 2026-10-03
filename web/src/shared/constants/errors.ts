/**
 * pt-BR for what the server says in English. Matched by the server's
 * message, which names the rule; ids and values inside it vary.
 */
export const KNOWN_ERRORS: readonly { pattern: RegExp; message: string }[] = [
  { pattern: /already in use by a subscriber/, message: 'Este e-mail já está em uso por outro assinante.' },
  { pattern: /^Email .* is already in use$/, message: 'Este e-mail já está em uso por outro operador.' },
  { pattern: /already has an active .* price/, message: 'O plano já tem um preço ativo neste ciclo. Substitua o valor em vez de adicionar outro.' },
  { pattern: /^Plan .* is inactive$/, message: 'O plano está inativo e não aceita esta operação.' },
  { pattern: /^Price .* is inactive$/, message: 'O preço está inativo e não aceita esta operação.' },
  { pattern: /is already inactive$/, message: 'Já está inativo.' },
  { pattern: /is already canceled$/, message: 'O assinante já está cancelado.' },
  { pattern: /is canceled and cannot be changed/, message: 'Assinante cancelado não pode ser alterado.' },
  { pattern: /cannot be charged/, message: 'Este assinante não pode ser cobrado: está cancelado ou sem CPF/CNPJ.' },
  { pattern: /was already requested/, message: 'A cobrança deste ciclo já foi gerada.' },
  { pattern: /was already sent to the gateway/, message: 'A cobrança já foi enviada ao gateway.' },
  { pattern: /is already paid/, message: 'A cobrança já está paga.' },
  { pattern: /only a paid payment can be refunded/, message: 'Só uma cobrança paga pode ser reembolsada.' },
  { pattern: /^Payment .* is [A-Z]+$/, message: 'A cobrança não está pendente.' },
];

/** When the message is not known, the status says enough. */
export const STATUS_ERRORS: Readonly<Record<number, string>> = {
  0: 'Sem conexão com o servidor. Tente de novo.',
  400: 'Confira os dados informados.',
  401: 'Sua sessão terminou. Entre novamente.',
  403: 'Você não tem permissão para esta ação.',
  404: 'Não encontrado. Pode ter sido removido.',
  409: 'Outra pessoa alterou estes dados. Recarregue e tente de novo.',
  422: 'A operação não é permitida no estado atual.',
  502: 'O gateway de pagamento ou a busca não respondeu. Tente de novo.',
  503: 'O servidor está indisponível no momento. Tente de novo em instantes.',
};

export const GENERIC_ERROR = 'Algo deu errado. Tente de novo.';
