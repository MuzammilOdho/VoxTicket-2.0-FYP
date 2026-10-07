/** Minimal motion utilities: scroll reveals + GSAP hero entrances.
 *  Everything respects prefers-reduced-motion. Animations are deliberately
 *  quiet — a 16px rise and fade, staggered, nothing bouncy. */
import { useEffect, useRef, type CSSProperties, type ReactNode } from 'react';
import gsap from 'gsap';

export function usePrefersReducedMotion(): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false;
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/* ---------------- scroll reveal ---------------- */

export function Reveal({
  children,
  delay = 0,
  className = '',
  as: Tag = 'div',
}: {
  children: ReactNode;
  delay?: number;
  className?: string;
  as?: 'div' | 'section' | 'li' | 'span';
}) {
  const ref = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    if (usePrefersReducedMotion()) {
      el.classList.add('is-visible');
      return;
    }
    const io = new IntersectionObserver(
      (entries) => {
        for (const e of entries) {
          if (e.isIntersecting) {
            e.target.classList.add('is-visible');
            io.unobserve(e.target);
          }
        }
      },
      { threshold: 0.12, rootMargin: '0px 0px -8% 0px' },
    );
    io.observe(el);
    return () => io.disconnect();
  }, []);

  const style = { '--reveal-delay': `${delay}ms` } as CSSProperties;
  // Tag polymorphism with a single ref type — div covers all uses here.
  return (
    <Tag ref={ref as never} style={style} className={`reveal ${className}`}>
      {children}
    </Tag>
  );
}

/* ---------------- GSAP hero entrance ----------------
   Staggers elements tagged .hero-el inside the ref'd container:
   fade + 20px rise, 700ms, expo.out. Runs once on mount. */

export function useHeroEntrance<T extends HTMLElement>() {
  const ref = useRef<T | null>(null);

  useEffect(() => {
    const root = ref.current;
    if (!root || usePrefersReducedMotion()) return;
    const els = root.querySelectorAll('.hero-el');
    if (els.length === 0) return;
    const ctx = gsap.context(() => {
      gsap.fromTo(
        els,
        { opacity: 0, y: 22 },
        { opacity: 1, y: 0, duration: 0.75, ease: 'expo.out', stagger: 0.09, overwrite: true },
      );
    }, root);
    return () => ctx.revert();
  }, []);

  return ref;
}

/* ---------------- GSAP gentle float ----------------
   Slow ambient drift for decorative layers (grid, orbs). */

export function useAmbientFloat<T extends HTMLElement>(distance = 14, duration = 7) {
  const ref = useRef<T | null>(null);

  useEffect(() => {
    const el = ref.current;
    if (!el || usePrefersReducedMotion()) return;
    const ctx = gsap.context(() => {
      gsap.to(el, {
        y: -distance,
        duration,
        ease: 'sine.inOut',
        yoyo: true,
        repeat: -1,
      });
    }, el);
    return () => ctx.revert();
  }, [distance, duration]);

  return ref;
}
