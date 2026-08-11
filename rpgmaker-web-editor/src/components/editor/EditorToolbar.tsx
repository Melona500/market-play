import type { SaveStatus } from '../../store/projectStore';

interface Props {
  projectName: string;
  dialogueName: string;
  saveStatus: SaveStatus;
  canUndo: boolean;
  canRedo: boolean;
  onBack: () => void;
  onUndo: () => void;
  onRedo: () => void;
  onSave: () => void;
}

const statusLabel: Record<SaveStatus, string> = {
  idle: '준비됨',
  saving: '저장 중...',
  saved: '저장됨 ✓',
  error: '저장 실패',
};

export function EditorToolbar(props: Props) {
  return (
    <header className="flex h-14 items-center border-b border-[#242933] bg-[#111419] px-4">
      <button onClick={props.onBack} className="mr-3 rounded-md px-2 py-1 text-[#a1a7b3] hover:bg-[#1d2129]">
        ←
      </button>
      <div className="min-w-0 flex-1 text-sm">
        <span className="text-[#a1a7b3]">{props.projectName}</span>
        <span className="mx-2 text-[#4f5662]">/</span>
        <span className="font-medium text-[#f3f4f6]">{props.dialogueName}</span>
      </div>
      <div className="flex items-center gap-1 rounded-lg bg-[#16191f] p-1 text-xs">
        <button className="rounded-md bg-[#242933] px-3 py-1.5 text-white">Script</button>
        <button disabled className="px-3 py-1.5 text-[#59606c]">Flow</button>
        <button disabled className="px-3 py-1.5 text-[#59606c]">Preview</button>
      </div>
      <div className="ml-auto flex items-center gap-2 pl-6">
        <button disabled={!props.canUndo} onClick={props.onUndo} title="Undo (Ctrl+Z)" className="rounded-md px-2 py-1 text-lg text-[#a1a7b3] enabled:hover:bg-[#1d2129] disabled:opacity-30">↶</button>
        <button disabled={!props.canRedo} onClick={props.onRedo} title="Redo (Ctrl+Shift+Z)" className="rounded-md px-2 py-1 text-lg text-[#a1a7b3] enabled:hover:bg-[#1d2129] disabled:opacity-30">↷</button>
        <button onClick={props.onSave} className="min-w-20 rounded-md px-2 py-1 text-xs text-[#a1a7b3] hover:bg-[#1d2129]">{statusLabel[props.saveStatus]}</button>
        <button disabled className="rounded-lg bg-[#232833] px-3 py-2 text-xs text-[#727987]">▶ 테스트</button>
      </div>
    </header>
  );
}
