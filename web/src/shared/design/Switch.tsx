import { useId } from 'react';
import styles from './Switch.module.css';

interface SwitchProps {
  label: string;
  hint?: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
  disabled?: boolean;
}

export function Switch({ label, hint, checked, onChange, disabled }: SwitchProps) {
  const id = useId();
  return (
    <div className={styles.row}>
      <div className={styles.text}>
        <span id={`${id}-label`} className={styles.label}>{label}</span>
        {hint && <span id={`${id}-hint`} className={styles.hint}>{hint}</span>}
      </div>
      <button
        type="button" role="switch" className={styles.switch} aria-checked={checked} disabled={disabled}
        aria-labelledby={`${id}-label`} aria-describedby={hint ? `${id}-hint` : undefined}
        onClick={() => onChange(!checked)}
      >
        <span className={styles.knob} aria-hidden="true" />
      </button>
    </div>
  );
}
