import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { probeLogin, setAuth } from '../api/client';
import { TextInput } from '../components/Filters';

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
      navigate('/', { replace: true });
    } else {
      setError('Login failed - check credentials and that the backend is reachable.');
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-slate-100">
      <form onSubmit={submit} className="w-80 rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
        <div className="mb-1 text-lg font-bold text-slate-900">VoxTicket Admin</div>
        <p className="mb-4 text-xs text-slate-500">Sign in with an admin account (HTTP Basic).</p>
        <div className="space-y-3">
          <TextInput label="Username" value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" />
          <TextInput label="Password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" />
        </div>
        {error && <div className="mt-3 rounded-lg bg-red-50 p-2 text-xs text-red-700">{error}</div>}
        <button
          type="submit"
          disabled={busy || !username || !password}
          className="mt-4 w-full rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700 disabled:opacity-50"
        >
          {busy ? 'Signing in...' : 'Sign in'}
        </button>
      </form>
    </div>
  );
}
