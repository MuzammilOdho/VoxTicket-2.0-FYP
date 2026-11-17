/** VoxTicket public site chrome — ElevenLabs layout, v4 colour system.
 * No promo banner: the reference nav is a minimal transparent top bar
 * (logo left, links center-left, auth buttons right). The demo CTA is
 * the brand indigo pill in the nav. The footer is a compact single
 * band on eggshell with a stone hairline — logo + tagline only, no
 * duplicated menu. Active nav is brand-ink text with a 2px brand
 * underline. Max content width 1280px, per the reference layout. */
import { useState } from 'react';
import { Link, NavLink } from 'react-router-dom';
import { ButtonLink } from '../ui/primitives';

export function Logo({ sub, dark = false }: { sub?: string; dark?: boolean }) {
  void dark;
  return (
    <Link to="/" className="flex items-center gap-2.5">
      <span className="flex h-6 w-6 items-center justify-center rounded-full bg-brand text-[12px] font-semibold text-white">
        V
      </span>
      <span className="font-display text-[16px] font-semibold tracking-[-0.01em] text-ink">
        VoxTicket{sub ? <span className="text-ink-mute"> {sub}</span> : null}
      </span>
    </Link>
  );
}

export function SiteNav() {
  const [menuOpen, setMenuOpen] = useState(false);
  const link = ({ isActive }: { isActive: boolean }) =>
    `border-b-2 px-3 py-2 text-[15px] transition-colors ${isActive ? 'border-brand font-medium text-brand-ink' : 'border-transparent text-smoke hover:text-ink'}`;
  return (
    <header className="sticky top-0 z-40 border-b border-line bg-eggshell/95">
      <div className="mx-auto flex h-16 max-w-[1280px] items-center justify-between px-6">
        <Logo />
        <nav className="hidden items-center gap-1 md:flex" aria-label="Site">
          <NavLink to="/product" className={link}>Product</NavLink>
          <NavLink to="/how-it-works" className={link}>How it works</NavLink>
          <NavLink to="/technology" className={link}>Technology</NavLink>
        </nav>
        <div className="flex items-center gap-2">
          <Link to="/admin" className="hidden px-3 py-2 text-[15px] text-smoke transition-colors hover:text-ink sm:block">
            Operations
          </Link>
          <ButtonLink to="/demo" variant="primary" className="!px-4 !py-2 !text-[14px]">
            Live demo
          </ButtonLink>
          <button
            onClick={() => setMenuOpen(!menuOpen)}
            className="rounded-full border border-line px-3 py-2 text-[14px] font-medium text-ink md:hidden"
            aria-label={menuOpen ? 'Close menu' : 'Open menu'}
            aria-expanded={menuOpen}
          >
            {menuOpen ? 'Close' : 'Menu'}
          </button>
        </div>
      </div>
      {menuOpen && (
        <nav className="border-t border-line bg-eggshell px-6 py-3 md:hidden" aria-label="Site mobile">
          <NavLink to="/product" onClick={() => setMenuOpen(false)} className="block py-2 text-[15px] text-smoke hover:text-ink">Product</NavLink>
          <NavLink to="/how-it-works" onClick={() => setMenuOpen(false)} className="block py-2 text-[15px] text-smoke hover:text-ink">How it works</NavLink>
          <NavLink to="/technology" onClick={() => setMenuOpen(false)} className="block py-2 text-[15px] text-smoke hover:text-ink">Technology</NavLink>
          <Link to="/admin" onClick={() => setMenuOpen(false)} className="block py-2 text-[15px] text-smoke hover:text-ink">Operations</Link>
        </nav>
      )}
    </header>
  );
}

/* The footer is one band — logo + tagline, nothing else. It used to
 * carry a second nav (Product / How it works / Technology / Live demo /
 * Operations console) that duplicated the header menu exactly, plus a
 * bottom bar that repeated the brand name and tagline a second time.
 * Both are removed: every public page already has the menu in the
 * header, and the brand appears once, here. */
export function SiteFooter() {
  return (
    <footer className="border-t border-line bg-eggshell text-ink">
      <div className="mx-auto flex max-w-[1280px] flex-wrap items-center gap-3 px-6 py-8">
        <Logo />
        <span className="text-[14px] text-smoke">
          Voice-first AI customer support for e-commerce.
        </span>
      </div>
    </footer>
  );
}

export function Page({ children }: { children: React.ReactNode }) {
  return (
    <div className="min-h-screen bg-eggshell text-ink">
      <SiteNav />
      <main>{children}</main>
      <SiteFooter />
    </div>
  );
}
