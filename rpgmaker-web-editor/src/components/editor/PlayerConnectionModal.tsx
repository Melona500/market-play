import { useEffect, useState } from 'react';
import type { PlayerSessionConnection, PublicDialogueSummary, ServerDialogueDocument, ServerDialogueSummary } from '../../services/playerSessionApi';
import { PlayerSessionApiClient } from '../../services/playerSessionApi';

interface Props {
  connection?: PlayerSessionConnection;
  onClose: () => void;
  onImport: (document: ServerDialogueDocument, scope: 'personal' | 'public') => Promise<void> | void;
}

export function PlayerConnectionModal({ connection, onClose, onImport }: Props) {
  const [dialogues, setDialogues] = useState<ServerDialogueSummary[]>([]);
  const [publicDialogues, setPublicDialogues] = useState<PublicDialogueSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState('');

  useEffect(() => {
    if (!connection) return;
    let cancelled = false;
    setLoading(true);
    const api = new PlayerSessionApiClient(connection.sessionId);
    const publicRequest = connection.admin ? api.listPublicDialogues() : Promise.resolve([] as PublicDialogueSummary[]);
    void Promise.all([api.listDialogues(connection.ownerUuid), publicRequest])
      .then(([personal, published]) => {
        if (!cancelled) {
          setDialogues(personal);
          setPublicDialogues(published);
        }
      })
      .catch((error) => {
        if (!cancelled) setMessage(error instanceof Error ? error.message : '대화 목록을 읽지 못했습니다.');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [connection]);

  const importDialogue = async (summary: ServerDialogueSummary, scope: 'personal' | 'public') => {
    if (!connection) return;
    setLoading(true);
    setMessage(`${summary.name} 불러오는 중...`);
    try {
      const api = new PlayerSessionApiClient(connection.sessionId);
      const document = scope === 'public'
        ? await api.getPublicDialogue(summary.name)
        : await api.getDialogue(connection.ownerUuid, summary.name);
      await onImport(document, scope);
      onClose();
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '대화를 불러오지 못했습니다.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/70 p-6">
      <section className="flex max-h-[90vh] w-full max-w-xl flex-col rounded-2xl border border-[#303846] bg-[#151a20] shadow-2xl">
        <header className="flex shrink-0 items-start gap-3 border-b border-[#29313c] px-5 py-4">
          <div>
            <h2 className="font-semibold text-[#f1f3f6]">Minecraft 서버 연결</h2>
            <p className="mt-1 text-xs text-[#7d8794]">API 주소, 토큰, UUID는 이 화면에서 입력하지 않습니다.</p>
          </div>
          <button type="button" onClick={onClose} className="ml-auto rounded-lg px-2 py-1 text-[#7d8794] hover:bg-[#242b34] hover:text-white">
            ✕
          </button>
        </header>

        <div className="min-h-0 flex-1 space-y-4 overflow-y-auto p-5">
          {connection ? (
            <>
              <div className="rounded-xl border border-emerald-400/20 bg-emerald-400/5 p-4">
                <div className="text-[11px] font-semibold tracking-[0.12em] text-emerald-300">서버 연결됨</div>
                <div className="mt-2 text-lg font-semibold text-white">{connection.playerName}</div>
                <div className="mt-1 text-xs text-[#84909e]">
                  {connection.admin
                    ? '관리자 권한으로 모든 공용 대화문을 열고 수정할 수 있습니다.'
                    : '이 링크를 발급받은 플레이어 계정으로만 저장됩니다.'}
                </div>
              </div>

              <div>
                <div className="mb-2 text-xs font-semibold text-[#9aa4b2]">서버의 대화 불러오기 · {dialogues.length}개</div>
                <div className="max-h-72 space-y-2 overflow-y-auto">
                  {loading && dialogues.length === 0 && <div className="py-8 text-center text-xs text-[#74808f]">불러오는 중...</div>}
                  {!loading && dialogues.length === 0 && <div className="rounded-xl border border-dashed border-[#333b47] py-8 text-center text-xs text-[#74808f]">저장된 대화가 없습니다.</div>}
                  {dialogues.map((dialogue) => (
                    <button
                      key={dialogue.name}
                      type="button"
                      disabled={loading}
                      onClick={() => void importDialogue(dialogue, 'personal')}
                      className="flex w-full items-center gap-3 rounded-xl border border-[#2d3540] bg-[#11161c] px-4 py-3 text-left hover:border-[#4d5a6c] disabled:opacity-50"
                    >
                      <div className="min-w-0 flex-1">
                        <div className="truncate text-sm font-semibold">{dialogue.title || dialogue.name}</div>
                        <div className="mt-1 text-[10px] text-[#737e8d]">{dialogue.pages} 페이지 · revision {dialogue.revision.slice(0, 8)}</div>
                      </div>
                      <span className="text-xs text-[#8b99ff]">불러오기</span>
                    </button>
                  ))}
                </div>
              </div>

              {connection.admin && (
                <div>
                  <div className="mb-2 flex items-center justify-between text-xs font-semibold text-[#9aa4b2]">
                    <span>공용 대화문 · {publicDialogues.length}개</span>
                    <span className="text-amber-300">OP 전용 · 전체 수정 가능</span>
                  </div>
                  <div className="space-y-2">
                    {publicDialogues.map((dialogue) => (
                      <button
                        key={dialogue.name}
                        type="button"
                        disabled={loading}
                        onClick={() => void importDialogue(dialogue, 'public')}
                        className="flex w-full items-center gap-3 rounded-xl border border-[#2d3540] bg-[#11161c] px-4 py-3 text-left hover:border-[#4d5a6c] disabled:opacity-50"
                      >
                        <div className="min-w-0 flex-1">
                          <div className="truncate text-sm font-semibold">{dialogue.title || dialogue.name}</div>
                          <div className="mt-1 text-[10px] text-[#737e8d]">
                            {dialogue.pages} 페이지 · {dialogue.publisher || 'RPGMaker'} · 관리자 수정 가능
                          </div>
                        </div>
                        <span className="text-xs text-[#8b99ff]">편집</span>
                      </button>
                    ))}
                    {!loading && publicDialogues.length === 0 && (
                      <div className="rounded-xl border border-dashed border-[#333b47] py-6 text-center text-xs text-[#74808f]">공용 대화문이 없습니다.</div>
                    )}
                  </div>
                </div>
              )}
            </>
          ) : (
            <div className="rounded-xl border border-[#344052] bg-[#121820] p-5">
              <div className="text-sm font-semibold text-white">게임에서 연결 링크를 발급받아 주세요.</div>
              <div className="mt-3 rounded-lg bg-black/20 px-3 py-2 font-mono text-sm text-[#9ca8ff]">/rpgmaker web</div>
              <p className="mt-3 text-xs leading-5 text-[#84909e]">
                명령어가 보내는 링크로 이 웹사이트를 열면 플레이어 닉네임과 UUID가 서버에서 자동으로 연결됩니다.
                별도의 API 토큰이나 UUID 입력은 필요하지 않습니다.
              </p>
            </div>
          )}

          {message && <div className="rounded-lg bg-[#20262f] px-3 py-2 text-xs text-[#aeb7c3]">{message}</div>}
        </div>
      </section>
    </div>
  );
}
