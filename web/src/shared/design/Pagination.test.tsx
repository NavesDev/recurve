import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { Pagination } from './Pagination';

describe('Pagination', () => {
  it('states the range and moves between pages (FR-07)', async () => {
    const onPageChange = vi.fn();
    render(<Pagination page={1} size={20} total={45} onPageChange={onPageChange} onSizeChange={vi.fn()} />);

    expect(screen.getByText('21–40 de 45')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Próxima' }));
    expect(onPageChange).toHaveBeenLastCalledWith(2);
    await userEvent.click(screen.getByRole('button', { name: 'Anterior' }));
    expect(onPageChange).toHaveBeenLastCalledWith(0);
  });

  it('cannot go before the first page or past the last', () => {
    render(<Pagination page={0} size={20} total={15} onPageChange={vi.fn()} onSizeChange={vi.fn()} />);

    expect(screen.getByRole('button', { name: 'Anterior' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Próxima' })).toBeDisabled();
  });

  it('changes the page size', async () => {
    const onSizeChange = vi.fn();
    render(<Pagination page={0} size={20} total={15} onPageChange={vi.fn()} onSizeChange={onSizeChange} />);

    await userEvent.click(screen.getByRole('radio', { name: '50' }));
    expect(onSizeChange).toHaveBeenCalledWith(50);
  });

  it('says nothing was found on an empty listing', () => {
    render(<Pagination page={0} size={20} total={0} onPageChange={vi.fn()} onSizeChange={vi.fn()} />);
    expect(screen.getByText('0 resultados')).toBeInTheDocument();
  });
});
