export function TextInput(props: React.InputHTMLAttributes<HTMLInputElement> & { label?: string }) {
  const { label, ...rest } = props;
  return (
    <label className="flex flex-col gap-1 text-[11px] font-semibold uppercase tracking-[0.1em] text-ink-mute">
      {label && <span>{label}</span>}
      <input
        {...rest}
        className="border border-line bg-canvas px-3 py-1.5 text-sm font-normal normal-case tracking-normal text-ink placeholder:text-ink-mute/60 focus:border-ink-mute focus:outline-none"
      />
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
    <label className="flex flex-col gap-1 text-[11px] font-semibold uppercase tracking-[0.1em] text-ink-mute">
      {label && <span>{label}</span>}
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="border border-line bg-canvas px-3 py-1.5 text-sm font-normal normal-case tracking-normal text-ink focus:border-ink-mute focus:outline-none"
      >
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
    <div className="mb-4 flex flex-wrap items-end gap-3 border border-line bg-raised p-3">
      {children}
      {onReset && (
        <button onClick={onReset} className="border border-line px-3 py-1.5 text-sm text-ink-mute hover:border-ink-mute hover:text-ink">
          Reset
        </button>
      )}
    </div>
  );
}
