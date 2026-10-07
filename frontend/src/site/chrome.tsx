/** VoxTicket public site chrome: minimal top bar + footer. */
import { Link, NavLink } from 'react-router-dom';
import { ButtonLink } from '../ui/primitives';

export function SiteNav() {
  const link = ({ isActive }: { isActive: boolean }) =>
    `text-sm transition-colors ${isActive ? 'text-ink' : 'text-ink-mute hover:text-ink'}`;
  return (
    <header className="sticky top-0 z-40 border-b border-line bg-canvas/95 backdrop-blur">
      <div className="mx-auto flex h-14 max-w-6xl items-center justify-between px-5">
        <Link to="/" className="flex items-center gap-2.5">
          <span className="flex h-6 w-6 items-center justify-center bg-signal font-mono text-xs font-bold text-[#101010]">V</span>
          <span className="text-[15px] font-semibold tracking-tight text-ink">VoxTicket</span>
        </Link>
        <nav className="hidden items-center gap-7 md:flex">
          <NavLink to="/product" className={link}>Product</NavLink>
          <NavLink to="/how-it-works" className={link}>How it works</NavLink>
          <NavLink to="/technology" className={link}>Technology</NavLink>
        </nav>
        <div className="flex items-center gap-3">
          <Link to="/admin" className="hidden text-sm text-ink-mute hover:text-ink sm:block">Operations</Link>
          <ButtonLink to="/demo" variant="primary" className="!px-4 !py-1.5">Live demo</ButtonLink>
        </div>
      </div>
    </header>
  );
}

export function SiteFooter() {
  return (
    <footer className="border-t border-line">
      <div className="mx-auto flex max-w-6xl flex-col gap-6 px-5 py-10 md:flex-row md:items-start md:justify-between">
        <div>
          <div className="flex items-center gap-2.5">
            <span className="flex h-6 w-6 items-center justify-center bg-signal font-mono text-xs font-bold text-[#101010]">V</span>
            <span className="text-[15px] font-semibold text-ink">VoxTicket</span>
          </div>
          <p className="mt-3 max-w-xs text-sm text-ink-mute">
            Voice-first AI customer support for e-commerce. AI understands the request — the system controls what it is allowed to do.
          </p>
        </div>
        <div className="grid grid-cols-2 gap-10 text-sm">
          <div>
            <div className="mb-3 text-[11px] font-semibold uppercase tracking-[0.14em] text-ink-mute">Product</div>
            <div className="space-y-2">
              <Link to="/product" className="block text-ink-dim hover:text-ink">Capabilities</Link>
              <Link to="/how-it-works" className="block text-ink-dim hover:text-ink">How it works</Link>
              <Link to="/technology" className="block text-ink-dim hover:text-ink">Technology</Link>
            </div>
          </div>
          <div>
            <div className="mb-3 text-[11px] font-semibold uppercase tracking-[0.14em] text-ink-mute">Try it</div>
            <div className="space-y-2">
              <Link to="/demo" className="block text-ink-dim hover:text-ink">Live demo</Link>
              <Link to="/admin" className="block text-ink-dim hover:text-ink">Operations console</Link>
            </div>
          </div>
        </div>
      </div>
      <div className="border-t border-line-soft">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-5 py-4 font-mono text-[11px] text-ink-mute">
          <span>VOXTICKET — VOICE-FIRST SUPPORT</span>
          <span>FINAL-YEAR PROJECT</span>
        </div>
      </div>
    </footer>
  );
}

export function Page({ children }: { children: React.ReactNode }) {
  return (
    <div className="min-h-screen bg-canvas text-ink">
      <SiteNav />
      <main>{children}</main>
      <SiteFooter />
    </div>
  );
}
