import { useId } from 'react';
import styles from './SegmentedControl.module.css';

interface Option<T extends string> {
  value: T;
  label: string;
}

interface SegmentedControlProps<T extends string> {
  label: string;
  value: T;
  options: readonly Option<T>[];
  onChange: (value: T) => void;
  /** Label beside the control, as a row of a list (the permission matrix). */
  inline?: boolean;
}

/** A choice of a few, all visible: a radio group in the prototype's track. */
export function SegmentedControl<T extends string>({ label, value, options, onChange, inline }: SegmentedControlProps<T>) {
  const id = useId();
  return (
    <div className={inline ? `${styles.group} ${styles.inline}` : styles.group}>
      <span id={`${id}-label`} className={styles.legend}>{label}</span>
      <div role="radiogroup" aria-labelledby={`${id}-label`} className={styles.track}>
        {options.map((option) => (
          <label key={option.value} className={styles.option}>
            <input type="radio" name={id} value={option.value} checked={option.value === value}
              onChange={() => onChange(option.value)} />
            <span>{option.label}</span>
          </label>
        ))}
      </div>
    </div>
  );
}
