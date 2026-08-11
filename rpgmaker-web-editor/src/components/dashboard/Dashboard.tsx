import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { loadCharacterManifest } from '../../services/characterRegistry';
import { importMinecraftDialogue } from '../../services/minecraftCompatibility';
import {
  connectPlayerSession,
  PlayerSessionApiClient,
  type PlayerSessionConnection,
  type PublicDialogueSummary,
} from '../../services/playerSessionApi';
import { createDialogue } from '../../services/projectFactory';
import { useEditorStore } from '../../store/editorStore';
import { useProjectStore } from '../../store/projectStore';
import { DashboardModal } from './DashboardModals';

function formatRelative(iso: string) {
  const minutes = Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / 60000));
  if (minutes < 1) return '방금 수정';
  if (minutes < 60) return `${minutes}분 전 수정`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}시간 전 수정`;
  return `${Math.floor(hours / 24)}일 전 수정`;
}

const serverNameKey = (value: string) => value.trim().toLocaleLowerCase();

export function Dashboard() {
  const navigate = useNavigate();
  const [modal, setModal] = useState<'settings' | 'help'>();
  const [connection, setConnection] = useState<PlayerSessionConnection>();
  const [publicDialogues, setPublicDialogues] = useState<PublicDialogueSummary[]>([]);
  const [publicLoading, setPublicLoading] = useState(false);
  const [publicMessage, setPublicMessage] = useState('');
  const projects = useProjectStore((state) => state.projects);
  const mutateProject = useProjectStore((state) => state.mutateProject);
  const selectDialogue = useEditorStore((state) => state.selectDialogue);
  const workspace = projects[0];
  const dialogues = projects.flatMap((project) =>
    project.dialogues.map((dialogue) => ({ project, dialogue })),
  );

  useEffect(() => {
    let cancelled = false;
    void connectPlayerSession()
      .then(async (next) => {
        if (cancelled || !next) return;
        setConnection(next);
        if (!next.admin) return;
        setPublicLoading(true);
        try {
          const list = await new PlayerSessionApiClient(next.sessionId).listPublicDialogues();
          if (!cancelled) setPublicDialogues(list);
        } catch (error) {
          if (!cancelled) setPublicMessage(error instanceof Error ? error.message : '공용 대화문을 불러오지 못했습니다.');
        } finally {
          if (!cancelled) setPublicLoading(false);
        }
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);

  const openDialogue = (projectId: string, dialogueId: string, firstPageId?: string) => {
    selectDialogue(dialogueId, firstPageId);
    navigate(`/project/${projectId}`);
  };

  const openPublicDialogue = async (summary: PublicDialogueSummary) => {
    if (!workspace || !connection?.admin) return;
    setPublicLoading(true);
    setPublicMessage(`${summary.title || summary.name} 불러오는 중...`);
    try {
      const api = new PlayerSessionApiClient(connection.sessionId);
      const [document, manifest] = await Promise.all([
        api.getPublicDialogue(summary.name),
        loadCharacterManifest(),
      ]);
      const ownerUuid = document.ownerUuid || summary.ownerUuid || connection.ownerUuid;
      const imported = importMinecraftDialogue(
        document.name,
        document.dialogue,
        document.revision,
        ownerUuid,
        manifest,
      );
      imported.server = {
        ...imported.server,
        ownerUuid,
        remoteName: document.name,
        revision: document.revision,
        scope: 'public',
        publisher: document.publisher || summary.publisher,
      };

      const existing = workspace.dialogues.find(
        (dialogue) =>
          dialogue.server?.scope === 'public' &&
          serverNameKey(dialogue.server.remoteName || dialogue.name) === serverNameKey(document.name),
      );
      if (existing) imported.id = existing.id;

      mutateProject(workspace.id, (draft) => {
        const index = draft.dialogues.findIndex(
          (dialogue) =>
            dialogue.server?.scope === 'public' &&
            serverNameKey(dialogue.server.remoteName || dialogue.name) === serverNameKey(document.name),
        );
        if (index >= 0) draft.dialogues[index] = imported;
        else draft.dialogues.push(imported);
      });
      setPublicMessage('');
      openDialogue(workspace.id, imported.id, imported.pages[0]?.id);
    } catch (error) {
      setPublicMessage(error instanceof Error ? error.message : '공용 대화문을 열지 못했습니다.');
    } finally {
      setPublicLoading(false);
    }
  };

  const handleCreate = () => {
    if (!workspace) return;
    const entered = window.prompt('새 대화 이름', '새 대화');
    if (entered === null) return;
    const name = entered.trim();
    if (!name) {
      window.alert('대화 이름을 입력해 주세요.');
      return;
    }
    const duplicate = dialogues.some(
      ({ dialogue }) => dialogue.name.trim().toLocaleLowerCase() === name.toLocaleLowerCase(),
    );
    if (duplicate) {
      window.alert(`'${name}' 이름의 대화가 이미 있습니다. 다른 이름을 사용해 주세요.`);
      return;
    }

    const next = createDialogue(name);
    mutateProject(workspace.id, (draft) => void draft.dialogues.push(next));
    openDialogue(workspace.id, next.id, next.pages[0]?.id);
  };

  const handleDelete = (projectId: string, dialogueId: string) => {
    const project = projects.find((candidate) => candidate.id === projectId);
    const dialogue = project?.dialogues.find((candidate) => candidate.id === dialogueId);
    if (!project || !dialogue) return;
    if (!window.confirm(`'${dialogue.name}' 대화를 삭제할까요?\n서버에 연결된 대화라면 다음 '서버에 반영' 때 서버에서도 삭제됩니다.`)) return;

    mutateProject(project.id, (draft) => {
      const target = draft.dialogues.find((candidate) => candidate.id === dialogue.id);
      if (target?.server?.ownerUuid && target.server.remoteName) {
        draft.pendingServerDeletes ??= [];
        const exists = draft.pendingServerDeletes.some(
          (entry) => entry.ownerUuid === target.server!.ownerUuid && entry.remoteName === target.server!.remoteName,
        );
        if (!exists) {
          draft.pendingServerDeletes.push({
            ownerUuid: target.server.ownerUuid,
            remoteName: target.server.remoteName,
            revision: target.server.revision,
          });
        }
      }
      draft.dialogues = draft.dialogues.filter((candidate) => candidate.id !== dialogue.id);
    });
  };

  return (
    <main className="min-h-screen bg-transparent px-12 py-10 text-[#eef4f8]">
      <header className="mx-auto flex max-w-6xl items-center justify-between rounded-2xl border border-[#26394a] bg-[#0d1721]/85 px-6 py-5 shadow-xl shadow-black/10 backdrop-blur">
        <div>
          <div className="text-sm font-semibold tracking-[0.18em] text-[#42d4d0]">RPGMAKER</div>
          <h1 className="mt-2 text-2xl font-semibold">대화</h1>
          <p className="mt-1 text-xs text-[#8091a1]">대화 제작 · 조건 · 효과 · 서버 동기화</p>
        </div>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={() => setModal('help')}
            className="rounded-lg border border-[#33485b] bg-[#14202b] px-3 py-2 text-sm text-[#aab8c5] hover:border-[#42d4d0]/60 hover:bg-[#1a2734]"
          >
            도움말
          </button>
          <button
            type="button"
            onClick={() => setModal('settings')}
            className="rounded-lg border border-[#33485b] bg-[#14202b] px-3 py-2 text-sm text-[#aab8c5] hover:border-[#9d8cff]/60 hover:bg-[#1a2734]"
          >
            설정
          </button>
        </div>
      </header>

      <section className="mx-auto mt-12 max-w-6xl">
        <div className="flex items-end justify-between">
          <div>
            <p className="text-sm text-[#8fa0ae]">웹과 Minecraft 서버에서 사용하는 대화 목록입니다.</p>
            <h2 className="mt-2 text-3xl font-semibold">대화 목록</h2>
          </div>
          <button
            onClick={handleCreate}
            disabled={!workspace}
            className="rounded-xl bg-gradient-to-r from-[#35bfc0] to-[#8f7cf6] px-4 py-2.5 text-sm font-semibold text-white shadow-lg shadow-[#42d4d0]/10 enabled:hover:brightness-110 disabled:cursor-not-allowed disabled:opacity-40"
          >
            + 새 대화 추가
          </button>
        </div>

        <div className="mt-8 grid grid-cols-3 gap-4">
          {dialogues.map(({ project, dialogue }, index) => {
            const accents = ['#42d4d0', '#9d8cff', '#f1c66d'];
            const accent = accents[index % accents.length];
            return (
              <article
                key={`${project.id}:${dialogue.id}`}
                className="group relative overflow-hidden rounded-2xl border border-[#26394a] bg-[#101923]/92 p-5 shadow-lg shadow-black/10 transition hover:-translate-y-0.5 hover:border-[#4c6277] hover:bg-[#172331]"
              >
                <div className="absolute inset-x-0 top-0 h-1 opacity-80" style={{ background: accent }} />
                <button
                  className="w-full text-left"
                  onClick={() => openDialogue(project.id, dialogue.id, dialogue.pages[0]?.id)}
                >
                  <div className="flex items-center gap-2">
                    <span className="h-2.5 w-2.5 rounded-full" style={{ background: accent }} />
                    <h3 className="truncate text-lg font-semibold">{dialogue.name}</h3>
                  </div>
                  <div className="mt-8 flex items-center justify-between text-sm">
                    <span className="text-[#a9b7c3]">페이지 {dialogue.pages.length}개</span>
                    <span className="rounded-full border border-[#31475a] bg-[#0c151e] px-2 py-1 text-[10px] text-[#758798]">
                      {dialogue.server?.remoteName ? 'SERVER LINKED' : 'WEB'}
                    </span>
                  </div>
                  <div className="mt-2 text-xs text-[#6f8292]">{formatRelative(project.updatedAt)}</div>
                </button>
                <div className="mt-4 flex items-center justify-between opacity-0 transition group-hover:opacity-100">
                  <span className="truncate text-[10px] text-[#627485]">{project.name}</span>
                  <button
                    type="button"
                    className="rounded-md px-2 py-1 text-xs text-[#91a0ad] hover:bg-red-400/10 hover:text-red-300"
                    onClick={() => handleDelete(project.id, dialogue.id)}
                  >
                    대화 삭제
                  </button>
                </div>
              </article>
            );
          })}
        </div>

        {dialogues.length === 0 && (
          <div className="mt-8 rounded-2xl border border-dashed border-[#304659] bg-[#0d1721]/60 px-6 py-16 text-center text-sm text-[#8294a3]">
            아직 대화가 없습니다. 위의 ‘새 대화 추가’로 첫 대화를 만드세요.
          </div>
        )}
      </section>

      {connection?.admin && (
        <section className="mx-auto mt-12 max-w-6xl border-t border-[#26394a] pt-10">
          <div className="flex items-end justify-between">
            <div>
              <p className="text-sm text-amber-200/70">OP 계정에서만 조회하고 수정할 수 있습니다.</p>
              <h2 className="mt-2 text-2xl font-semibold">공용 대화문</h2>
            </div>
            <span className="rounded-full border border-amber-300/20 bg-amber-300/5 px-3 py-1 text-xs text-amber-200">
              {publicDialogues.length}개
            </span>
          </div>

          {publicMessage && <div className="mt-4 rounded-xl border border-[#3c4653] bg-[#121b24] px-4 py-3 text-sm text-[#aeb8c5]">{publicMessage}</div>}

          <div className="mt-6 grid grid-cols-3 gap-4">
            {publicDialogues.map((dialogue) => (
              <button
                key={dialogue.name}
                type="button"
                disabled={publicLoading}
                onClick={() => void openPublicDialogue(dialogue)}
                className="rounded-2xl border border-amber-300/20 bg-[#151b20] p-5 text-left transition hover:-translate-y-0.5 hover:border-amber-300/45 hover:bg-[#1b232b] disabled:opacity-50"
              >
                <div className="flex items-center justify-between gap-3">
                  <h3 className="truncate font-semibold text-white">{dialogue.title || dialogue.name}</h3>
                  <span className="shrink-0 text-[10px] font-semibold text-amber-200">편집</span>
                </div>
                <div className="mt-5 text-xs text-[#9aa5b2]">{dialogue.pages} 페이지 · {dialogue.publisher || 'RPGMaker'}</div>
                <div className="mt-1 truncate text-[10px] text-[#687583]">{dialogue.name}</div>
              </button>
            ))}
          </div>

          {!publicLoading && publicDialogues.length === 0 && !publicMessage && (
            <div className="mt-6 rounded-2xl border border-dashed border-amber-300/20 bg-amber-300/[0.03] px-6 py-10 text-center text-sm text-[#8995a3]">
              서버에 등록된 공용 대화문이 없습니다.
            </div>
          )}
        </section>
      )}

      {modal && <DashboardModal kind={modal} onClose={() => setModal(undefined)} />}
    </main>
  );
}
