/** Command palette — Stage 2 (admin redesign).
 *
 * The admin's second navigation mode (browse = sidebar, jump = palette).
 * Hand-rolled on purpose: no new dependency for a list of routes. It lists
 * every admin route plus recently visited conversation/voice records
 * (stored in localStorage by the detail pages; nothing is sent anywhere).
 * Keyboard: Ctrl/Cmd-K opens, ArrowUp/Down moves, Enter goes, Esc closes
 * and returns focus to the trigger. Screen-reader friendly: a real dialog
 * with a label, and options are buttons (not divs with click handlers).
 * Scope note: v1 searches routes + recents only. Searching record
 * contents would need new backend endpoints and is deliberately not here.
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';

export interface PaletteItem {
  label: string;
  hint?: string;
  to: string;
}

const ROUTES: PaletteItem[] = [
  { label: 'Overview', hint: 'Dashboard', to: '/admin' },
  { label: 'Conversations', hint: 'List', to: '/admin/conversations' },
  { label: 'Orders', hint: 'Operations', to: '/admin/operations/orders' },
  { label: 'Returns', hint: 'Operations', to: '/admin/operations/returns' },
  { label: 'Refunds', hint: 'Operations', to: '/admin/operations/refunds' },
  { label: 'Claims', hint: 'Operations', to: '/admin/operations/claims' },
  { label: 'Verification', hint: 'Operations', to: '/admin/operations/verification' },
  { label: 'Challenges', hint: 'Operations', to: '/admin/operations/challenges' },
  { label: 'Escalations', hint: 'Operations', to: '/admin/operations/escalations' },
  { label: 'Voice', hint: 'Call list', to: '/admin/voice' },
  { label: 'Models', hint: 'AI', to: '/admin/ai/models' },
  { label: 'Routing', hint: 'AI', to: '/admin/ai/routing' },
  { label: 'RAG', hint: 'AI', to: '/admin/ai/rag' },
  { label: 'Evaluation', hint: 'AI', to: '/admin/evaluation' },
  { label: 'Audit', hint: 'System', to: '/admin/audit' },
  { label: 'Health', hint: 'System', to: '/admin/health' },
];

export const RECENT_KEY = 'vt_admin_recent';

export function readRecents(): PaletteItem[] {
  try {
    const raw = localStorage.getItem(RECENT_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw) as PaletteItem[];
    return Array.isArray(parsed) ? parsed.slice(0, 5) : [];
  } catch {
    return [];
  }
}

/** Record a visited detail page for the palette's recent list (max 5). */
export function pushRecent(item: PaletteItem) {
  try {
    const next = [item, ...readRecents().filter((r) => r.to !== item.to)].slice(0, 5);
    localStorage.setItem(RECENT_KEY, JSON.stringify(next));
  } catch {
    /* Private mode or full storage: recents are a convenience, never load-bearing. */
  }
}

export function CommandPalette({ open, onClose, triggerRef }: {
  open: boolean;
  onClose: () => void;
  triggerRef: React.RefObject<HTMLElement | null>;
}) {
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);

  const items = useMemo(() => {
    const q = query.trim().toLowerCase();
    const recents = readRecents();
    const all = [...recents.map((r) => ({ ...r, hint: `Recent · ${r.hint ?? ''}`.trim() })), ...ROUTES];
    if (!q) return all;
    return all.filter((i) => `${i.label} ${i.hint ?? ''}`.toLowerCase().includes(q));
  }, [query, open]);

  useEffect(() => {
    if (open) {
      setQuery('');
      setActive(0);
      // Focus the input on open; Esc returns focus to the trigger.
      const t = window.setTimeout(() => inputRef.current?.focus(), 0);
      return () => window.clearTimeout(t);
    }
  }, [open ]);

  useEffect(() => {
    setActive(0);
  }, [query]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        onClose();
        triggerRef.current?.focus();
      } else if (e.key === 'ArrowDown') {
        e.preventDefault();
        setActive((a) => Math.min(a + 1, items.length - 1));
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        setActive((a) => Math.max(a - 1, 0));
      } else if (e.key === 'Enter') {
        const item = items[active];
        if (item) {
          e.preventDefault();
          navigate(item.to);
          onClose();
        }
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, items, active, navigate, onClose, triggerRef]);

  if (!open) return null;
  return (
    <div className="fixed inset-0 z-[60] flex items-start justify-center pt-[15vh] px-4" role="dialog" aria-modal="true" aria-label="Quick navigation">
      <button aria-label="Close quick navigation" onClick={onClose} className="absolute inset-0 bg-black/40" />
      <div className="relative w-full max-w-lg overflow-hidden rounded-xl border border-line bg-paper shadow-card">
        <div className="p-3">
          <input
            ref={inputRef}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Go to a page, or a recent conversation or call…"
            aria-label="Search admin pages"
            className="w-full rounded-md border border-line bg-paper px-3 py-2 text-[14px] text-ink placeholder:text-ash outline-none focus:border-brand focus:ring-2 focus:ring-brand/15"
          />
        </div>
        <div ref={listRef} className="max-h-[50vh] overflow-y-auto py-1">
          {items.length === 0 ? (
            <p className="px-4 py-3 text-sm text-ink-mute">No matching page. Try a page name like “Voice” or “Audit”.</p>
          ) : (
            items.map((item, i) => (
              <button
                key={`${item.to}-${i}`}
                onClick={() => { navigate(item.to); onClose(); }}
                onMouseEnter={() => setActive(i)}
                className={`flex w-full items-center justify-between px-4 py-2.5 text-left text-sm ${i === active ? 'bg-brand-soft text-brand-ink' : 'text-ink-dim'}`}
              >
                <span>{item.label}</span>
                {item.hint && <span className="text-xs text-ink-mute">{item.hint}</span>}
              </button>
            ))
          )}
        </div>
        <p className="border-t border-line px-4 py-2 text-[12px] text-ink-mute">
          ↑↓ to move · Enter to go · Esc to close
        </p>
      </div>
    </div>
  );
}
