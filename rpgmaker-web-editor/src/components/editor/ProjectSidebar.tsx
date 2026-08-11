import type { Dialogue, RPGProject } from '../../domain/project';

interface Props {
  project: RPGProject;
  activeDialogueId: string;
  onSelectDialogue: (dialogue: Dialogue) => void;
  onCreateDialogue: () => void;
}

export function ProjectSidebar({ project, activeDialogueId, onSelectDialogue, onCreateDialogue }: Props) {
  return (
    <section className="scrollbar-thin max-h-[44%] overflow-y-auto p-3">
      <div className="px-2 py-2 text-sm font-semibold">{project.name}</div>
      <div className="mt-3 px-2 text-[11px] font-semibold tracking-wider text-[#666e7b]">대화</div>
      <div className="mt-2 space-y-1">
        {project.dialogues.map((dialogue) => (
          <button
            key={dialogue.id}
            onClick={() => onSelectDialogue(dialogue)}
            className={`flex w-full items-center gap-2 rounded-lg px-2 py-2 text-left text-sm ${
              dialogue.id === activeDialogueId ? 'bg-[#242933] text-white' : 'text-[#a1a7b3] hover:bg-[#1d2129]'
            }`}
          >
            <span>💬</span>
            <span className="truncate">{dialogue.name}</span>
          </button>
        ))}
      </div>
      <button onClick={onCreateDialogue} className="mt-2 w-full rounded-lg px-2 py-2 text-left text-sm text-[#7c8cff] hover:bg-[#1d2129]">
        + 새 대화
      </button>

      <div className="my-5 h-px bg-[#242933]" />
      <div className="space-y-1 text-sm text-[#747c89]">
        <div className="rounded-lg px-2 py-2">👤 캐릭터</div>
        <div className="rounded-lg px-2 py-2">{'{x}'} 변수</div>
        <div className="rounded-lg px-2 py-2">🎒 아이템</div>
      </div>
      <div className="my-5 h-px bg-[#242933]" />
      <div className="rounded-lg px-2 py-2 text-sm text-[#747c89]">⚙ 프로젝트 설정</div>
    </section>
  );
}
