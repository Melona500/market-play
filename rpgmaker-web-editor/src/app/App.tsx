import { useEffect, useState } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { Dashboard } from '../components/dashboard/Dashboard';
import { DialogueStudioV2 } from '../components/editor/DialogueStudioV2';
import { connectPlayerSession } from '../services/playerSessionApi';
import { useProjectStore } from '../store/projectStore';

export function App() {
  const hydrated = useProjectStore((state) => state.hydrated);
  const hydrate = useProjectStore((state) => state.hydrate);
  const [connectionError, setConnectionError] = useState<string>();

  useEffect(() => {
    // Reuse /rpgmaker web sessions, or ask the local API to match this address
    // to one online player before the user opens a project. Do not silently hide
    // connectivity/CORS/session failures: they otherwise look like missing OP data.
    void connectPlayerSession()
      .then(() => setConnectionError(undefined))
      .catch((error) => setConnectionError(error instanceof Error ? error.message : 'Minecraft 서버 연결에 실패했습니다.'));
    void hydrate();
  }, [hydrate]);

  if (!hydrated) {
    return (
      <main className="grid min-h-screen place-items-center bg-[#0f1115] text-[#a1a7b3]">
        프로젝트를 불러오는 중...
      </main>
    );
  }

  return (
    <>
      {connectionError && (
        <div className="fixed inset-x-0 top-0 z-[100] flex items-center gap-3 border-b border-red-400/30 bg-[#2a1519]/95 px-5 py-3 text-sm text-red-100 shadow-lg backdrop-blur">
          <span className="min-w-0 flex-1 truncate">서버 연결 오류: {connectionError}</span>
          <button
            type="button"
            onClick={() => setConnectionError(undefined)}
            className="rounded-md px-2 py-1 text-xs text-red-200 hover:bg-white/10"
          >
            닫기
          </button>
        </div>
      )}
      <Routes>
        <Route path="/" element={<Dashboard />} />
        <Route path="/project/:projectId" element={<DialogueStudioV2 />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </>
  );
}
