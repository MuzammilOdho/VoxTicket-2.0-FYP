/** Admin login — ElevenLabs system (DESIGN-ELEVENLABS.md). Eggshell
 * card on eggshell canvas, black pill sign-in button, black focus.
 * Auth behaviour unchanged: sign-in probe against
 * /api/v1/admin/system/health, credentials kept in sessionStorage only. */
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
    <div className="flex min-h-screen items-center justify-center bg-paper px-6">
      <form
        onSubmit={submit}
        className="w-[380px] rounded-xl border border-line bg-paper p-8 shadow-xl"
      >
        <div className="flex items-center gap-2.5">
          <span className="flex h-7 w-7 items-center justify-center rounded-full bg-brand text-xs font-semibold text-white">
            V
          </span>
          <span className="font-display text-[16px] font-semibold tracking-[-0.01em] text-ink">VoxTicket</span>
        </div>
        <h1 className="font-display mt-6 text-[24px] font-medium tracking-[-0.2px] text-ink">Operations console</h1>
        <p className="mt-1.5 text-[14px] text-smoke">Sign in with your administrator account.</p>
        <div className="mt-6 space-y-4">
          <TextInput label="Username" value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" />
          <TextInput label="Password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" />
        </div>
        {error && (
          <div className="mt-4 rounded-md border border-bad/40 bg-bad/5 p-3 text-[13px] text-[#b42318]">{error}</div>
        )}
        <button
          type="submit"
          disabled={busy || !username || !password}
          className="mt-6 w-full rounded-full bg-brand px-4 py-2.5 text-[14px] font-medium tracking-[-0.011em] text-white transition-colors hover:bg-brand-strong disabled:cursor-not-allowed disabled:opacity-40"
        >
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
        <div className="mt-5 text-center">
          <Kicker>Restricted area</Kicker>
        </div>
      </form>
    </div>
  );
}
