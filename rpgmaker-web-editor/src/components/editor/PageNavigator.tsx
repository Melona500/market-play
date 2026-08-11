import type { Dialogue, DialoguePage } from '../../domain/project';

interface Props {
  dialogue: Dialogue;
  activePageId: string;
  onSelectPage: (page: DialoguePage) => void;
  onCreatePage: () => void;
}

export function PageNavigator({ dialogue, activePageId, onSelectPage, onCreatePage }: Props) {
  return (
    <section className="scrollbar-thin min-h-0 flex-1 overflow-y-auto border-t border-[#242933] p-3">
      <div className="px-2 py-2 text-[11px] font-semibold tracking-wider text-[#666e7b]">PAGE</div>
      <div className="space-y-1">
        {dialogue.pages.map((page, index) => (
          <button
            key={page.id}
            onClick={() => onSelectPage(page)}
            className={`flex w-full items-center gap-2 rounded-lg px-2 py-2 text-left text-sm ${
              page.id === activePageId ? 'bg-[#242933] text-white' : 'text-[#a1a7b3] hover:bg-[#1d2129]'
            }`}
          >
            <span className={`text-xs ${page.id === activePageId ? 'text-[#7c8cff]' : 'text-[#545c68]'}`}>●</span>
            <span className="w-5 text-xs text-[#707784]">{index + 1}</span>
            <span className="truncate">{page.editorLabel || `Page ${index + 1}`}</span>
          </button>
        ))}
      </div>
      <button
        disabled={dialogue.pages.length >= 10}
        title={dialogue.pages.length >= 10 ? '한 대화에는 최대 10개의 페이지를 만들 수 있습니다.' : undefined}
        onClick={onCreatePage}
        className="mt-3 w-full rounded-lg px-2 py-2 text-left text-sm text-[#7c8cff] enabled:hover:bg-[#1d2129] disabled:cursor-not-allowed disabled:text-[#555c68]"
      >
        + 페이지 추가
      </button>
    </section>
  );
}
