import { HashRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { QueryClientProvider } from '@tanstack/react-query';
import { queryClient } from './api/hooks';
import { getAuth } from './api/client';
import { Layout } from './components/Layout';
import { Login } from './pages/Login';
import { Dashboard } from './pages/Dashboard';
import { Conversations } from './pages/Conversations';
import { ConversationDetail } from './pages/ConversationDetail';
import { Operations } from './pages/Operations';
import { VoiceAnalytics } from './pages/VoiceAnalytics';
import { VoiceCallDetail } from './pages/VoiceCallDetail';
import { AiModels } from './pages/AiModels';
import { AiRouting } from './pages/AiRouting';
import { AiRag } from './pages/AiRag';
import { Evaluation } from './pages/Evaluation';
import { AuditLog } from './pages/AuditLog';
import { SystemHealth } from './pages/SystemHealth';

function RequireAuth({ children }: { children: React.ReactNode }) {
  const loc = useLocation();
  if (!getAuth()) {
    return <Navigate to="/login" replace state={{ from: loc.pathname }} />;
  }
  return <>{children}</>;
}

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <HashRouter>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route
            element={
              <RequireAuth>
                <Layout />
              </RequireAuth>
            }
          >
            <Route index element={<Dashboard />} />
            <Route path="conversations" element={<Conversations />} />
            <Route path="conversations/:sessionId" element={<ConversationDetail />} />
            <Route path="operations/:kind" element={<Operations />} />
            <Route path="operations" element={<Navigate to="/operations/orders" replace />} />
            <Route path="voice" element={<VoiceAnalytics />} />
            <Route path="voice/:sessionId" element={<VoiceCallDetail />} />
            <Route path="ai/models" element={<AiModels />} />
            <Route path="ai/routing" element={<AiRouting />} />
            <Route path="ai/rag" element={<AiRag />} />
            <Route path="evaluation" element={<Evaluation />} />
            <Route path="audit" element={<AuditLog />} />
            <Route path="health" element={<SystemHealth />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Route>
        </Routes>
      </HashRouter>
    </QueryClientProvider>
  );
}
