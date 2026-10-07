import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { probeLogin, setAuth } from '../api/client';
import { TextInput } from '../components/Filters';
import { Kicker } from '../ui/primitives';

export function Login() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const navigate = useNavigate();

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const base64 = btoa(`${username}:${password}`);
    const ok = await probeLogin(base64);
    setBusy(false);
    if (ok) {
      setAuth(base64);
      navigate('/admin', { replace: true });
    } else {
      setError('Login failed - check credentials and that the backend is reachable.');
    }
  }

  return (
    <div className="relative flex min-h-screen items-center justify-center overflow-hidden bg-void">
      <div className="bg-grid absolute inset-0" aria-hidden />
      <form
        onSubmit={submit}
        className="g-border relative w-[380px] rounded-xl p-8"
      >
        <div className="flex items-center gap-2.5">
          <span className="flex h-7 w-7 items-center justify-center rounded-[6px] bg-signal font-mono text-xs font-bold text-[#08090a]">
            V
          </span>
          <span className="text-[16px] font-medium tracking-[-0.01em] text-white">VoxTicket</span>
        </div>
        <h1 className="heading-tight mt-6 text-[24px] font-medium text-white">Operations console</h1>
        <p className="mt-1.5 text-[14px] text-fog">Sign in with an admin account (HTTP Basic).</p>
        <div className="mt-6 space-y-4">
          <TextInput label="Username" value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" />
          <TextInput label="Password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" />
        </div>
        {error && (
          <div className="mt-4 rounded-md border border-bad/40 bg-bad/[0.07] p-3 text-[13px] text-bad">{error}</div>
        )}
        <button
          type="submit"
          disabled={busy || !username || !password}
          className="mt-6 w-full rounded-md bg-signal px-4 py-2.5 text-[14px] font-medium tracking-[-0.011em] text-[#08090a] transition-all hover:brightness-110 disabled:cursor-not-allowed disabled:opacity-40"
        >
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
        <div className="mt-5 text-center">
          <Kicker className="!text-[10px]">Restricted area</Kicker>
        </div>
      </form>
    </div>
  );
}
