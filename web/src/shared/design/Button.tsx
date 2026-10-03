import { Link, type LinkProps } from 'react-router';
import type { ButtonHTMLAttributes } from 'react';
import styles from './Button.module.css';

export type ButtonVariant = 'primary' | 'secondary' | 'danger' | 'quiet';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: 'regular' | 'small';
  /** Waiting on the server: shows it and refuses another press. */
  busy?: boolean;
}

export function Button({ variant = 'primary', size = 'regular', busy = false, disabled, className, children, type = 'button', ...rest }: ButtonProps) {
  const classes = [styles.button, styles[variant], size === 'small' && styles.small, className].filter(Boolean).join(' ');
  return (
    <button {...rest} type={type} className={classes} disabled={disabled || busy} aria-busy={busy || undefined}>
      {busy && <span className={styles.spinner} aria-hidden="true" />}
      {children}
    </button>
  );
}

interface ButtonLinkProps extends LinkProps {
  variant?: ButtonVariant;
}

/** Navigation that looks like a button: still a link to assistive technology. */
export function ButtonLink({ variant = 'primary', className, ...rest }: ButtonLinkProps) {
  return <Link {...rest} className={[styles.button, styles[variant], className].filter(Boolean).join(' ')} />;
}
