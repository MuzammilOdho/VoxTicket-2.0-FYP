/** VoxTicket public site chrome — Linear-style fixed nav + footer. */
import { Link, NavLink } from 'react-router-dom';
import { ButtonLink } from '../ui/primitives';

export function Logo({ sub }: { sub?: string }) {
  return (
    <Link to="/" className="flex items-center gap-2.5">
      <span className="flex h-6 w-6 items-center justify-center rounded-[6px] bg-signal font-mono text-[11px] font-bold text-[#08090a]">
        V
      </span>
      <span className="text-[16px] font-medium tracking-[-0.01em] text-white">
        VoxTicket{sub ? <span className="text-ink-mute"> {sub}</span> : null}
      </span>
    </Link>
  );
}

export function SiteNav() {
  const link = ({ isActive }: { isActive: boolean }) =>
    `px-3 py-2 text-[13px] transition-colors ${isActive ? 'text-white' : 'text-ink-dim hover:text-white'}`;
  return (
    <header className="glass fixed inset-x-0 top-0 z-40 border-b border-line/70">
      <div className="mx-auto flex h-16 max-w-[1200px] items-center justify-between px-6">
        <Logo />
        <nav className="hidden items-center gap-1 md:flex">
          <NavLink to="/product" className={link}>Product</NavLink>
          <NavLink to="/how-it-works" className={link}>How it works</NavLink>
          <NavLink to="/technology" className={link}>Technology</NavLink>
        </nav>
        <div className="flex items-center gap-2">
          <Link to="/admin" className="hidden px-3 py-2 text-[13px] text-ink-dim transition-colors hover:text-white sm:block">
            Operations
          </Link>
          <ButtonLink to="/demo" variant="pill" className="!px-4 !py-2">
            Live demo
          </ButtonLink>
        </div>
      </div>
    </header>
  );
}

export function SiteFooter() {
  return (
    <footer className="border-t border-line">
      <div className="mx-auto grid max-w-[1200px] gap-10 px-6 py-16 md:grid-cols-[1.2fr_2fr]">
        <div>
          <Logo />
          <p className="mt-4 max-w-xs text-[15px] leading-relaxed text-ink-mute">
            Voice-first AI customer support for e-commerce. AI understands the request —
            the system controls what it is allowed to do.
          </p>
        </div>
        <div className="grid grid-cols-2 gap-10 sm:grid-cols-3">
          <div>
            <div className="mb-4 text-[11px] font-medium uppercase tracking-[0.14em] text-ink-mute">Product</div>
            <div className="space-y-2.5 text-[14px]">
              <Link to="/product" className="block text-ink-dim transition-colors hover:text-white">Capabilities</Link>
              <Link to="/how-it-works" className="block text-ink-dim transition-colors hover:text-white">How it works</Link>
              <Link to="/technology" className="block text-ink-dim transition-colors hover:text-white">Technology</Link>
            </div>
          </div>
          <div>
            <div className="mb-4 text-[11px] font-medium uppercase tracking-[0.14em] text-ink-mute">Try it</div>
            <div className="space-y-2.5 text-[14px]">
              <Link to="/demo" className="block text-ink-dim transition-colors hover:text-white">Live demo</Link>
              <Link to="/admin" className="block text-ink-dim transition-colors hover:text-white">Operations console</Link>
            </div>
          </div>
          <div>
            <div className="mb-4 text-[11px] font-medium uppercase tracking-[0.14em] text-ink-mute">Project</div>
            <div className="space-y-2.5 font-mono text-[12px] text-ink-mute">
              <span className="block">VOICE-FIRST SUPPORT</span>
              <span className="block">FINAL-YEAR PROJECT</span>
            </div>
          </div>
        </div>
      </div>
      <div className="border-t border-line/60">
        <div className="mx-auto flex max-w-[1200px] items-center justify-between px-6 py-5 font-mono text-[11px] text-ash">
          <span>VOXTICKET</span>
          <span className="flex items-center gap-2">
            <span className="vq-pulse inline-block h-1.5 w-1.5 rounded-full bg-pulse-green" />
            ALL SYSTEMS NOMINAL
          </span>
        </div>
      </div>
    </footer>
  );
}

export function Page({ children }: { children: React.ReactNode }) {
  return (
    <div className="min-h-screen bg-void text-white">
      <SiteNav />
      <main className="pt-16">{children}</main>
      <SiteFooter />
    </div>
  );
}
