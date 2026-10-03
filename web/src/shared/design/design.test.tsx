import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { Button } from './Button';
import { ConfirmDialog } from './ConfirmDialog';
import { DataTable, type Column } from './DataTable';
import { Dialog } from './Dialog';
import { Menu } from './Menu';
import { SegmentedControl } from './SegmentedControl';
import { Switch } from './Switch';
import { TextField } from './TextField';
import { ToastProvider, useToast } from './Toast';

describe('Button', () => {
  it('shows it is busy and refuses a second press', async () => {
    const onClick = vi.fn();
    render(<Button busy onClick={onClick}>Salvar</Button>);

    const button = screen.getByRole('button', { name: /salvar/i });
    expect(button).toHaveAttribute('aria-busy', 'true');
    await userEvent.click(button);
    expect(onClick).not.toHaveBeenCalled();
  });
});

describe('TextField', () => {
  it('labels the input and announces its error', () => {
    render(<TextField label="Nome do plano" error="Informe um nome." />);

    const input = screen.getByLabelText('Nome do plano');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAccessibleDescription('Informe um nome.');
  });
});

describe('Switch', () => {
  it('is a switch that toggles', async () => {
    function Harness() {
      const [on, setOn] = useState(false);
      return <Switch label="Plano ativo" checked={on} onChange={setOn} />;
    }
    render(<Harness />);

    const control = screen.getByRole('switch', { name: 'Plano ativo' });
    expect(control).toHaveAttribute('aria-checked', 'false');
    await userEvent.click(control);
    expect(control).toHaveAttribute('aria-checked', 'true');
  });
});

describe('SegmentedControl', () => {
  it('is a radio group with one choice', async () => {
    const onChange = vi.fn();
    render(
      <SegmentedControl label="Ciclo" value="MONTHLY" onChange={onChange}
        options={[{ value: 'MONTHLY', label: 'Mensal' }, { value: 'YEARLY', label: 'Anual' }]} />,
    );

    expect(screen.getByRole('radio', { name: 'Mensal' })).toBeChecked();
    await userEvent.click(screen.getByRole('radio', { name: 'Anual' }));
    expect(onChange).toHaveBeenCalledWith('YEARLY');
  });
});

describe('Dialog', () => {
  it('is a labelled modal that Escape closes', async () => {
    const onClose = vi.fn();
    render(<Dialog open title="Gerar cobrança" onClose={onClose}><button>Dentro</button></Dialog>);

    const dialog = screen.getByRole('dialog', { name: 'Gerar cobrança' });
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(dialog).toContainElement(document.activeElement as HTMLElement);
    await userEvent.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalled();
  });

  it('renders nothing when closed', () => {
    render(<Dialog open={false} title="X" onClose={() => {}}>conteúdo</Dialog>);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});

describe('ConfirmDialog', () => {
  it('confirms or cancels', async () => {
    const onConfirm = vi.fn();
    const onCancel = vi.fn();
    render(
      <ConfirmDialog open title="Cancelar assinatura?" confirmLabel="Cancelar assinatura" tone="danger"
        onConfirm={onConfirm} onCancel={onCancel}>Não gera mais cobranças.</ConfirmDialog>,
    );

    await userEvent.click(screen.getByRole('button', { name: 'Cancelar assinatura' }));
    expect(onConfirm).toHaveBeenCalledOnce();
    await userEvent.click(screen.getByRole('button', { name: 'Voltar' }));
    expect(onCancel).toHaveBeenCalledOnce();
  });
});

describe('Menu', () => {
  it('opens its actions and runs the chosen one', async () => {
    const onEdit = vi.fn();
    render(<Menu label="Ações de Pro" items={[{ label: 'Editar', onSelect: onEdit }, { label: 'Desativar', onSelect: vi.fn(), tone: 'danger' }]} />);

    await userEvent.click(screen.getByRole('button', { name: 'Ações de Pro' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Editar' }));
    expect(onEdit).toHaveBeenCalled();
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('closes on Escape', async () => {
    render(<Menu label="Ações" items={[{ label: 'Editar', onSelect: vi.fn() }]} />);

    await userEvent.click(screen.getByRole('button', { name: 'Ações' }));
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('renders nothing without an action (fail-closed: no empty menu)', () => {
    const { container } = render(<Menu label="Ações" items={[]} />);
    expect(container).toBeEmptyDOMElement();
  });
});

interface Row { id: string; name: string; amount: number }
const columns: Column<Row>[] = [
  { key: 'name', header: 'Nome', sortField: 'name.keyword', render: (row) => row.name },
  { key: 'amount', header: 'Valor', align: 'end', render: (row) => row.amount },
];

describe('DataTable', () => {
  it('renders a row per item', () => {
    render(<DataTable caption="Planos" columns={columns} rows={[{ id: '1', name: 'Pro', amount: 10 }]} rowKey={(r) => r.id} />);

    const table = screen.getByRole('table', { name: 'Planos' });
    expect(within(table).getAllByRole('row')).toHaveLength(2);
    expect(within(table).getByRole('cell', { name: 'Pro' })).toBeInTheDocument();
  });

  it('sorts by a sortable column, toggling the direction', async () => {
    const onSortChange = vi.fn();
    const { rerender } = render(
      <DataTable caption="Planos" columns={columns} rows={[]} rowKey={(r) => r.id}
        sort="name.keyword" onSortChange={onSortChange} />,
    );

    expect(screen.getByRole('columnheader', { name: /nome/i })).toHaveAttribute('aria-sort', 'ascending');
    await userEvent.click(screen.getByRole('button', { name: /nome/i }));
    expect(onSortChange).toHaveBeenLastCalledWith('name.keyword:desc');

    rerender(<DataTable caption="Planos" columns={columns} rows={[]} rowKey={(r) => r.id}
      sort="name.keyword:desc" onSortChange={onSortChange} />);
    expect(screen.getByRole('columnheader', { name: /nome/i })).toHaveAttribute('aria-sort', 'descending');
    await userEvent.click(screen.getByRole('button', { name: /nome/i }));
    expect(onSortChange).toHaveBeenLastCalledWith('name.keyword:asc');
    expect(screen.queryByRole('button', { name: /valor/i })).not.toBeInTheDocument();
  });

  it('says so when there is nothing to show', () => {
    render(<DataTable caption="Planos" columns={columns} rows={[]} rowKey={(r) => r.id} empty="Nenhum plano encontrado." />);
    expect(screen.getByText('Nenhum plano encontrado.')).toBeInTheDocument();
  });

  it('shows placeholder rows while loading', () => {
    render(<DataTable caption="Planos" columns={columns} rows={[]} rowKey={(r) => r.id} loading />);
    expect(screen.getByRole('table')).toHaveAttribute('aria-busy', 'true');
  });
});

describe('Toast', () => {
  it('announces a message and lets it go', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    function Trigger() {
      const toast = useToast();
      return <button onClick={() => toast.success('Plano criado.')}>disparar</button>;
    }
    render(<ToastProvider><Trigger /></ToastProvider>);

    await userEvent.click(screen.getByRole('button', { name: 'disparar' }));
    expect(screen.getByRole('status')).toHaveTextContent('Plano criado.');
    act(() => { vi.advanceTimersByTime(6000); });
    expect(screen.queryByText('Plano criado.')).not.toBeInTheDocument();
    vi.useRealTimers();
  });
});
