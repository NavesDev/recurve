import { useId, type ReactElement, type ReactNode } from 'react';
import styles from './Field.module.css';

export interface FieldProps {
  label: string;
  hint?: ReactNode;
  error?: string | undefined;
}

interface ControlProps {
  id: string;
  'aria-invalid'?: boolean | undefined;
  'aria-describedby'?: string | undefined;
  className: string | undefined;
}

/** Label, hint and error around any control, wired for assistive technology. */
export function Field({ label, hint, error, children }: FieldProps & { children: (props: ControlProps) => ReactElement }) {
  const id = useId();
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined;
  return (
    <div className={styles.field}>
      <label className={styles.label} htmlFor={id}>{label}</label>
      {children({ id, 'aria-invalid': error ? true : undefined, 'aria-describedby': describedBy, className: styles.control })}
      {error ? (
        <span id={`${id}-error`} className={styles.error}>{error}</span>
      ) : hint ? (
        <span id={`${id}-hint`} className={styles.hint}>{hint}</span>
      ) : null}
    </div>
  );
}

export const fieldStyles = styles;
