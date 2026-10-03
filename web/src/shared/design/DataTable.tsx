import type { ReactNode } from 'react';
import styles from './DataTable.module.css';

export interface Column<T> {
  key: string;
  header: string;
  render: (row: T) => ReactNode;
  align?: 'start' | 'end';
  /** The server's sort field (FR-06); absent, the column does not sort. */
  sortField?: string;
  width?: string;
}

interface DataTableProps<T> {
  caption: string;
  columns: readonly Column<T>[];
  rows: readonly T[];
  rowKey: (row: T) => string;
  /** Current sort, as the listing contract writes it: `field` or `field:asc|desc`. */
  sort?: string;
  onSortChange?: (sort: string) => void;
  loading?: boolean;
  empty?: ReactNode;
}

const SKELETON_ROWS = 4;

function parseSort(sort: string | undefined): { field?: string; descending: boolean } {
  if (!sort) return { descending: false };
  const [field, direction] = sort.split(':');
  return { field, descending: direction === 'desc' };
}

export function DataTable<T>({ caption, columns, rows, rowKey, sort, onSortChange, loading, empty }: DataTableProps<T>) {
  const current = parseSort(sort);

  return (
    <div className={styles.frame}>
      <table className={styles.table} aria-busy={loading || undefined}>
        <caption className="visually-hidden">{caption}</caption>
        <thead className={styles.head}>
          <tr>
            {columns.map((column) => {
              const active = column.sortField !== undefined && column.sortField === current.field;
              const ariaSort = active ? (current.descending ? 'descending' : 'ascending') : undefined;
              const alignment = column.align === 'end' ? styles.end : '';
              return (
                <th key={column.key} scope="col" aria-sort={ariaSort} className={`${styles.th} ${alignment}`} style={{ width: column.width }}>
                  {column.sortField && onSortChange ? (
                    <button
                      type="button" className={styles.sort} data-active={active}
                      onClick={() => onSortChange(`${column.sortField}:${active && !current.descending ? 'desc' : 'asc'}`)}
                    >
                      {column.header}
                      <span className={styles.arrow} aria-hidden="true">{active ? (current.descending ? '↓' : '↑') : '↕'}</span>
                    </button>
                  ) : (
                    column.header
                  )}
                </th>
              );
            })}
          </tr>
        </thead>
        <tbody>
          {loading && rows.length === 0
            ? Array.from({ length: SKELETON_ROWS }, (_, index) => (
                <tr key={index} className={styles.row}>
                  {columns.map((column) => (
                    <td key={column.key} className={styles.td}><span className={styles.skeleton} /></td>
                  ))}
                </tr>
              ))
            : rows.map((row) => (
                <tr key={rowKey(row)} className={styles.row}>
                  {columns.map((column) => (
                    <td key={column.key} className={`${styles.td} ${column.align === 'end' ? styles.end : ''}`}>{column.render(row)}</td>
                  ))}
                </tr>
              ))}
          {!loading && rows.length === 0 && (
            <tr>
              <td colSpan={columns.length} className={styles.empty}>{empty ?? 'Nada encontrado.'}</td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}
