import { useId } from 'react';
import { PAGE_SIZES } from '../constants/listing';
import { Button } from './Button';
import styles from './Pagination.module.css';

interface PaginationProps {
  /** Zero-based, as the server counts (FR-07.1). */
  page: number;
  size: number;
  total: number;
  onPageChange: (page: number) => void;
  onSizeChange: (size: number) => void;
}

export function Pagination({ page, size, total, onPageChange, onSizeChange }: PaginationProps) {
  const name = useId();
  const pages = Math.max(1, Math.ceil(total / size));
  const first = page * size + 1;
  const last = Math.min(total, (page + 1) * size);
  const range = total === 0 ? '0 resultados' : `${first}–${last} de ${total}`;

  return (
    <div className={styles.bar}>
      <span className={styles.range}>{range}</span>
      <div className={styles.controls}>
        <fieldset className={styles.sizes}>
          <legend className={styles.legend}>Linhas por página</legend>
          <div className={styles.track}>
            {PAGE_SIZES.map((option) => (
              <label key={option} className={styles.size}>
                <input type="radio" name={name} checked={option === size} onChange={() => onSizeChange(option)} />
                <span>{option}</span>
              </label>
            ))}
          </div>
        </fieldset>
        <span className={styles.pageLabel}>pág. {Math.min(page + 1, pages)}/{pages}</span>
        <Button variant="secondary" size="small" disabled={page <= 0} onClick={() => onPageChange(page - 1)}>Anterior</Button>
        <Button variant="secondary" size="small" disabled={page + 1 >= pages} onClick={() => onPageChange(page + 1)}>Próxima</Button>
      </div>
    </div>
  );
}
