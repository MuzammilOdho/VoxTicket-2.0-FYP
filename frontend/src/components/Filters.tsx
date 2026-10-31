/** Filter inputs — shadcn style: filled pill fields inside a card.
 *
 * Stage 1 fix: labels are plain sentence-case text, not mono uppercase
 * (mono uppercase at 11 px is harder to scan; mono is kept for values).
 * Stage 5 additions: FilterBar accepts an optional `dateNote` (shown only
 * on pages that actually filter by date — dates are UTC days, and the
 * operator must be able to see that) and removable `chips` that mirror
 * the applied filters, so the operator can always answer "what am I
 * looking at right now" and clear one constraint without resetting all.
 */
import { Button } from './Button';

const fieldLabel = 'flex flex-col gap-1.5 text-[12px] font-medium uppercase tracking-[0.6px] text-ink-mute';
const fieldInput =
  'border border-transparent bg-canvas px-3 py-2 rounded-[18px] text-[14px] text-ink placeholder:text-ink-mute/70 outline-none transition-colors focus:border-line focus:bg-transparent';

export function TextInput(props: React.InputHTMLAttributes<HTMLInputElement> & { label?: string }) {
  const { label, ...rest } = props;
  return (
    <label className={fieldLabel}>
      {label && <span>{label}</span>}
      <input {...rest} className={fieldInput} />
    </label>
  );
}

export function SelectInput({
  label,
  value,
  onChange,
  options,
  allowEmpty = true,
  emptyLabel = 'All',
}: {
  label?: string;
  value: string;
  onChange: (v: string) => void;
  options: { value: string; label: string }[];
  allowEmpty?: boolean;
  emptyLabel?: string;
}) {
  return (
    <label className={fieldLabel}>
      {label && <span>{label}</span>}
      <select value={value} onChange={(e) => onChange(e.target.value)} className={fieldInput}>
        {allowEmpty && <option value="">{emptyLabel}</option>}
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </label>
  );
}

export interface FilterChip {
  label: string;
  onRemove: () => void;
}

export function FilterBar({ children, onReset, dateNote, chips }: {
  children: React.ReactNode;
  onReset?: () => void;
  dateNote?: boolean;
  chips?: FilterChip[];
}) {
  return (
    <div className="mb-6">
      <div className="flex flex-wrap items-end gap-3 rounded-[24px] border border-line bg-paper p-5 shadow-card">
        {children}
        {onReset && (
          <Button variant="ghost" onClick={onReset}>
            Reset
          </Button>
        )}
      </div>
      {dateNote && (
        <p className="mt-2 text-xs text-ink-mute">
          Date filters use UTC days. A day you pick covers that UTC day, not a Karachi day.
        </p>
      )}
      {chips && chips.length > 0 && (
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <span className="text-xs text-ink-mute">Showing:</span>
          {chips.map((chip) => (
            <button
              key={chip.label}
              onClick={chip.onRemove}
              aria-label={`Remove filter ${chip.label}`}
              className="inline-flex min-h-[36px] items-center gap-1.5 rounded-[18px] border border-line bg-paper px-3 text-xs text-ink hover:border-ink-mute"
            >
              {chip.label} <span aria-hidden>×</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
