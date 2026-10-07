/** Demo customer assignment: fetched from the backend (dev/test profile),
 *  never hardcoded. Each mount assigns a fresh random customer, so a reload
 *  always starts a new session as a new customer. */
import { useCallback, useEffect, useRef, useState } from 'react';

export interface DemoOrder {
  number: string;
  status: string;
  fulfillmentStatus: string;
  items: string;
  total: string;
  currency: string;
}

export interface DemoCustomer {
  phone: string;
  name: string;
  email: string;
  status: string;
  orders: DemoOrder[];
}

export function useDemoCustomer() {
  const [customer, setCustomer] = useState<DemoCustomer | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const seq = useRef(0);

  const assign = useCallback(async () => {
    const my = ++seq.current;
    setLoading(true);
    setError(null);
    try {
      const res = await fetch('/api/v1/demo/customer/random');
      if (!res.ok) {
        const t = await res.text().catch(() => '');
        throw new Error(
          t || `Customer assignment failed (${res.status}). Is the backend running with the dev profile?`,
        );
      }
      const data = (await res.json()) as DemoCustomer;
      if (seq.current === my) setCustomer(data);
    } catch (e) {
      if (seq.current === my) {
        setError(e instanceof Error ? e.message : 'Could not assign a customer');
        setCustomer(null);
      }
    } finally {
      if (seq.current === my) setLoading(false);
    }
  }, []);

  // Assign once per mount — a reload is a new session with a new customer.
  useEffect(() => {
    assign();
  }, [assign]);

  return { customer, loading, error, reassign: assign };
}
