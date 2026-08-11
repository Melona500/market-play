import { useEffect, useState } from 'react';
import {
  loadServerConnection,
  MinecraftApiClient,
  saveServerConnection,
  type ServerConnectionConfig,
  type ServerDialogueDocument,
  type ServerDialogueSummary,
} from '../../services/minecraftApi';

interface Props {
  onClose: () => void;
  onConnected: (config: ServerConnectionConfig) => void;
  onImport: (document: ServerDialogueDocument, config: ServerConnectionConfig) => Promise<void>;
}

export function ServerConnectionModal({ onClose, onConnected, onImport }: Props) {
  const [config, setConfig] = useState(loadServerConnection);
  const [status, setStatus] = useState<'idle' | 'testing' | 'connected' | 'error'>('idle');
  const [message, setMessage] = useState('');
  const [dialogues, setDialogues] = useState<ServerDialogueSummary[]>([]);
  const [loadingName, setLoadingName] = useState<string>();

  const client = () => new MinecraftApiClient(config);

  const connect = async () => {
    if (!config.ownerUuid.trim()) {
      setStatus('error');
      setMessage('서버 저장본을 조회할 플레이어 UUID를 입력하세요.');
      return;
    }
    setStatus('testing');
    setMessage('');
    try {
      const api = client();
      const info = await api.status();
      const list = await api.listDialogues();
      saveServerConnection(config);
      setDialogues(list);
      setStatus('connected');
      setMessage(`연결됨 · RPGMaker ${info.pluginVersion} · API v${info.apiVersion}`);
      onConnected(config);
    } catch (error) {
      setStatus('error');
      setMessage(error instanceof Error ? error.message : '서버 연결에 실패했습니다.');
    }
  };

  useEffect(() => {
    void connect();
  }, []);

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/70 p-8" onMouseDown={onClose}>
      <div
        className="flex max-h-[84vh] w-[720px] flex-col overflow-hidden rounded-2xl border border-[#2a3039] bg-[#13171c] shadow-2xl"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="flex items-start border-b border-[#242a33] px-5 py-4">
          <div>
            <div className="text-base font-semibold">Minecraft 서버 연결</div>
            <div className="mt-1 text-xs text-[#77818f]">
              로컬 자동 저장과 서버 저장은 분리됩니다. 여기서 불러오거나 명시적으로 서버에 반영할 때만 통신합니다.
            </div>
          </div>
          <button type="button" onClick={onClose} className="ml-auto rounded-lg px-2 py-1 text-[#7b8593] hover:bg-[#20252d]">
            ✕
          </button>
        </header>

        <div className="min-h-0 flex-1 overflow-y-auto p-5">
          <div className="grid grid-cols-2 gap-3">
            <label className="col-span-2 text-xs font-medium text-[#929ba8]">
              API 주소
              <input
                value={config.baseUrl}
                onChange={(event) => setConfig((current) => ({ ...current, baseUrl: event.target.value }))}
                className="mt-1.5 w-full rounded-lg border border-[#2a3039] bg-[#181c22] px-3 py-2 text-sm outline-none focus:border-[#7c8cff]"
                placeholder="http://127.0.0.1:25567"
              />
            </label>
            <label className="text-xs font-medium text-[#929ba8]">
              API Token
              <input
                type="password"
                value={config.token}
                onChange={(event) => setConfig((current) => ({ ...current, token: event.target.value }))}
                className="mt-1.5 w-full rounded-lg border border-[#2a3039] bg-[#181c22] px-3 py-2 text-sm outline-none focus:border-[#7c8cff]"
              />
            </label>
            <label className="text-xs font-medium text-[#929ba8]">
              대화 소유자 UUID
              <input
                value={config.ownerUuid}
                onChange={(event) => setConfig((current) => ({ ...current, ownerUuid: event.target.value }))}
                className="mt-1.5 w-full rounded-lg border border-[#2a3039] bg-[#181c22] px-3 py-2 text-sm outline-none focus:border-[#7c8cff]"
                placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
              />
            </label>
          </div>

          <div className="mt-4 flex items-center gap-3">
            <button
              type="button"
              disabled={status === 'testing'}
              onClick={() => void connect()}
              className="rounded-lg bg-[#7c8cff] px-4 py-2 text-xs font-semibold text-white disabled:opacity-50"
            >
              {status === 'testing' ? '연결 확인 중...' : '연결 확인'}
            </button>
            {message && (
              <span className={`text-xs ${status === 'error' ? 'text-red-300' : 'text-[#8f99a7]'}`}>{message}</span>
            )}
          </div>

          <div className="my-6 h-px bg-[#252b34]" />

          <div className="flex items-center justify-between">
            <div>
              <div className="text-sm font-semibold">서버 대화 목록</div>
              <div className="mt-1 text-xs text-[#737d8b]">기존 player-dialogues 저장본을 웹 프로젝트로 가져옵니다.</div>
            </div>
            <span className="text-xs text-[#697382]">{dialogues.length}개</span>
          </div>

          <div className="mt-3 space-y-2">
            {dialogues.map((dialogue) => (
              <div key={dialogue.name} className="flex items-center rounded-xl border border-[#282f39] bg-[#171b21] p-3">
                <div className="min-w-0 flex-1">
                  <div className="truncate text-sm font-semibold">{dialogue.title || dialogue.name}</div>
                  <div className="mt-1 text-[11px] text-[#727c8a]">
                    {dialogue.name} · {dialogue.pages} pages · rev {dialogue.revision.slice(0, 8)}
                  </div>
                </div>
                <button
                  type="button"
                  disabled={loadingName === dialogue.name}
                  onClick={async () => {
                    setLoadingName(dialogue.name);
                    try {
                      const document = await client().getDialogue(dialogue.name);
                      await onImport(document, config);
                      saveServerConnection(config);
                      onConnected(config);
                      onClose();
                    } catch (error) {
                      setStatus('error');
                      setMessage(error instanceof Error ? error.message : '대화를 불러오지 못했습니다.');
                    } finally {
                      setLoadingName(undefined);
                    }
                  }}
                  className="ml-3 rounded-lg bg-[#252b35] px-3 py-2 text-xs text-[#d6dae1] hover:bg-[#303744] disabled:opacity-50"
                >
                  {loadingName === dialogue.name ? '불러오는 중' : '불러오기'}
                </button>
              </div>
            ))}

            {status === 'connected' && dialogues.length === 0 && (
              <div className="rounded-xl border border-dashed border-[#303743] px-4 py-8 text-center text-xs text-[#77818f]">
                이 UUID로 저장된 대화가 없습니다.
              </div>
            )}
          </div>
        </div>

        <footer className="border-t border-[#242a33] px-5 py-3 text-[11px] leading-5 text-[#687280]">
          기본 API는 127.0.0.1에만 바인딩됩니다. 외부에 포트를 공개할 경우 반드시 서버의 API token과 CORS 설정을 변경하세요.
        </footer>
      </div>
    </div>
  );
}
