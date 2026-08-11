import type { SaveStatus } from '../../store/projectStore';

export type ServerUiStatus =
  | 'disconnected'
  | 'connecting'
  | 'connected'
  | 'syncing'
  | 'applied'
  | 'stale'
  | 'conflict'
  | 'error';

interface Props {
  projectName: string;
  dialogueName: string;
  saveStatus: SaveStatus;
  serverStatus: ServerUiStatus;
  serverMessage?: string;
  issueCount: number;
  errorCount: number;
  canUndo: boolean;
  canRedo: boolean;
  onBack: () => void;
  onUndo: () => void;
  onRedo: () => void;
  onSave: () => void;
  onTest: () => void;
  onValidate: () => void;
  onServer: () => void;
  onApplyServer: () => void;
  onPublishServer?: () => void;
  onPublicHelp?: () => void;
  onVariables?: () => void;
  isAdmin?: boolean;
  readOnly?: boolean;
  isPublic?: boolean;
}

const saveText: Record<SaveStatus, string> = {
  idle: '로컬 준비됨',
  saving: '로컬 저장 중...',
  saved: '로컬 저장됨 ✓',
  error: '로컬 저장 실패',
};

const serverText: Record<ServerUiStatus, string> = {
  disconnected: '서버 연결 안 됨',
  connecting: '서버 연결 중',
  connected: '서버 연결됨',
  syncing: '서버 동기화 중...',
  applied: '서버 반영 완료',
  stale: '서버 데이터가 더 최신임',
  conflict: '서버 변경 충돌',
  error: '서버 오류',
};

function statusDot(status: ServerUiStatus) {
  if (status === 'connected' || status === 'applied') return 'bg-emerald-400';
  if (status === 'syncing' || status === 'connecting') return 'bg-amber-300';
  if (status === 'conflict' || status === 'error' || status === 'stale') return 'bg-red-400';
  return 'bg-[#596371]';
}

export function StudioToolbar(props: Props) {
  const canPullFromServer = !['connecting', 'syncing'].includes(props.serverStatus);

  return (
    <header className="flex h-16 shrink-0 items-center border-b border-[#232a33] bg-[#11151a] px-4">
      <button
        type="button"
        onClick={props.onBack}
        className="mr-3 rounded-lg px-2 py-2 text-[#8993a1] hover:bg-[#20252d] hover:text-white"
      >
        ←
      </button>
      <div className="min-w-0">
        <div className="flex items-center gap-2 text-sm">
          <span className="max-w-40 truncate text-[#788290]">{props.projectName}</span>
          <span className="text-[#424a55]">/</span>
          <span className="max-w-56 truncate font-semibold text-[#e7eaf0]">{props.dialogueName}</span>
        </div>
        <div className="mt-1 flex items-center gap-3 text-[10px] text-[#697382]">
          <span>{saveText[props.saveStatus]}</span>
          <button type="button" onClick={props.onServer} title={props.serverMessage} className="flex items-center gap-1.5 hover:text-[#a8b0bc]">
            <span className={`h-1.5 w-1.5 rounded-full ${statusDot(props.serverStatus)}`} />
            {serverText[props.serverStatus]}
          </button>
        </div>
      </div>

      <div className="ml-auto flex items-center gap-1">
        {props.onVariables && (
          <button type="button" onClick={props.onVariables} className="rounded-lg px-3 py-2 text-xs text-[#aab2ff] hover:bg-[#20252d]">
            변수 도움말
          </button>
        )}
        {props.isAdmin && props.onPublicHelp && (
          <button type="button" onClick={props.onPublicHelp} className="rounded-lg px-3 py-2 text-xs text-amber-300 hover:bg-[#20252d]">
            공용 대화 도움말
          </button>
        )}
        <button
          type="button"
          disabled={props.readOnly || !props.canUndo}
          onClick={props.onUndo}
          title="Undo (Ctrl+Z)"
          className="rounded-lg px-2.5 py-2 text-lg text-[#8c96a4] enabled:hover:bg-[#20252d] disabled:opacity-25"
        >
          ↶
        </button>
        <button
          type="button"
          disabled={props.readOnly || !props.canRedo}
          onClick={props.onRedo}
          title="Redo (Ctrl+Shift+Z)"
          className="rounded-lg px-2.5 py-2 text-lg text-[#8c96a4] enabled:hover:bg-[#20252d] disabled:opacity-25"
        >
          ↷
        </button>
        <button
          type="button"
          disabled={props.readOnly}
          onClick={props.onSave}
          className="rounded-lg px-3 py-2 text-xs text-[#8f99a7] enabled:hover:bg-[#20252d] disabled:opacity-35"
        >
          저장
        </button>
        <button
          type="button"
          onClick={props.onValidate}
          className={`ml-2 rounded-lg px-3 py-2 text-xs ${
            props.errorCount
              ? 'bg-red-400/10 text-red-300 hover:bg-red-400/15'
              : 'text-[#9ba5b3] hover:bg-[#20252d]'
          }`}
        >
          오류 검사 {props.issueCount ? `· ${props.issueCount}` : ''}
        </button>
        <button
          type="button"
          onClick={props.onTest}
          className="ml-1 rounded-lg bg-[#252b35] px-4 py-2 text-xs font-semibold text-[#e4e7ec] hover:bg-[#303744]"
        >
          ▶ 테스트
        </button>
        <button
          type="button"
          disabled={props.readOnly || props.serverStatus === 'syncing'}
          onClick={props.onApplyServer}
          className="ml-1 rounded-lg bg-[#7c8cff] px-4 py-2 text-xs font-semibold text-white hover:brightness-110 disabled:opacity-50"
        >
          서버에 반영
        </button>
        {props.onPublishServer && (
          <button
            type="button"
            disabled={props.readOnly || props.serverStatus === 'syncing'}
            onClick={props.onPublishServer}
            className="ml-1 rounded-lg border border-violet-400/40 px-4 py-2 text-xs font-semibold text-violet-200 enabled:hover:bg-violet-400/10 disabled:opacity-35"
          >
            {props.isPublic ? '공용본 갱신' : '공용으로 저장'}
          </button>
        )}
        <button
          type="button"
          disabled={!canPullFromServer}
          onClick={props.onServer}
          title="서버의 전체 대화 목록을 웹과 동기화합니다. 같은 이름은 중복 생성하지 않고 서버 버전으로 갱신합니다."
          className="ml-1 rounded-lg border border-[#40556a] bg-[#18232d] px-4 py-2 text-xs font-semibold text-[#b9c7d3] enabled:hover:border-[#5f7890] enabled:hover:bg-[#21303d] disabled:cursor-not-allowed disabled:opacity-35"
        >
          서버에서 불러오기
        </button>
      </div>
    </header>
  );
}
