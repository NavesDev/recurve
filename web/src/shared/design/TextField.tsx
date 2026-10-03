import { forwardRef, type InputHTMLAttributes, type SelectHTMLAttributes, type TextareaHTMLAttributes } from 'react';
import { Field, fieldStyles, type FieldProps } from './Field';

type InputProps = FieldProps & InputHTMLAttributes<HTMLInputElement> & { mono?: boolean };

export const TextField = forwardRef<HTMLInputElement, InputProps>(function TextField({ label, hint, error, mono, ...rest }, ref) {
  return (
    <Field label={label} hint={hint} error={error}>
      {(control) => <input ref={ref} {...rest} {...control} className={mono ? `${control.className} ${fieldStyles.mono}` : control.className} />}
    </Field>
  );
});

type AreaProps = FieldProps & TextareaHTMLAttributes<HTMLTextAreaElement>;

export const TextArea = forwardRef<HTMLTextAreaElement, AreaProps>(function TextArea({ label, hint, error, ...rest }, ref) {
  return <Field label={label} hint={hint} error={error}>{(control) => <textarea ref={ref} {...rest} {...control} />}</Field>;
});

type SelectProps = FieldProps & SelectHTMLAttributes<HTMLSelectElement>;

export const Select = forwardRef<HTMLSelectElement, SelectProps>(function Select({ label, hint, error, children, ...rest }, ref) {
  return <Field label={label} hint={hint} error={error}>{(control) => <select ref={ref} {...rest} {...control}>{children}</select>}</Field>;
});
