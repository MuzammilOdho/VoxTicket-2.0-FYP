/** Filter inputs — Linear style: 6px radius, hairline borders. */

const fieldLabel = 'flex flex-col gap-1.5 text-[11px] font-medium uppercase tracking-[0.14em] text-ink-mute';
const fieldInput =
  'rounded-md border border-line bg-white/[0.02] px-3 py-2 text-[13px] font-normal normal-case tracking-normal text-ink placeholder:text-ink-mute/60 outline-none transition-colors focus:border-ink-dim';

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
      <select value={value} onChange={(e) => onChange(e.target.value)} className={`${fieldInput} bg-raised`}>
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

export function FilterBar({ children, onReset }: { children: React.ReactNode; onReset?: () => void }) {
  return (
    <div className="mb-5 flex flex-wrap items-end gap-3 rounded-xl border border-line/70 bg-raised p-4">
      {children}
      {onReset && (
        <button
          onClick={onReset}
          className="rounded-md border border-line px-3 py-2 text-[13px] text-ink-mute transition-colors hover:border-smoke hover:text-ink"
        >
          Reset
        </button>
      )}
    </div>
  );
}
