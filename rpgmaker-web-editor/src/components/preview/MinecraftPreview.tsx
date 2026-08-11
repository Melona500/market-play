import type { CharacterDefinition, DialoguePage } from '../../domain/project';
import { ServerPreviewBadges } from './ServerPreviewBadges';

interface Props {
  page: DialoguePage;
  characters: CharacterDefinition[];
}

export function MinecraftPreview({ page, characters }: Props) {
  const character = characters.find((candidate) => candidate.id === page.appearance.characterId);
  const visibleLines = page.lines.filter(Boolean);

  return (
    <div className="flex min-w-0 flex-1 items-center justify-center overflow-hidden bg-[radial-gradient(circle_at_top,#1c2330_0,#111419_48%,#0e1014_100%)] p-10">
      <div className="w-full max-w-[760px]">
        <div className="mb-4 flex items-center justify-between text-xs text-[#6f7784]">
          <span>MINECRAFT PREVIEW</span>
          <span>1920×1080 · GUI Scale 2</span>
        </div>
        <ServerPreviewBadges page={page} />
        <div className="relative min-h-[400px] rounded-2xl border border-white/5 bg-black/30 p-8 shadow-2xl backdrop-blur-sm">
          <div className="absolute inset-x-8 bottom-8 rounded-lg border-2 border-[#5a5961] bg-[#18181c]/95 px-6 py-5 shadow-[0_0_0_3px_rgba(0,0,0,0.65)]">
            {page.appearance.visible && character && (
              <div className="absolute -top-24 left-5 flex h-20 w-20 items-center justify-center rounded-xl border border-white/10 bg-[#24252c] text-5xl shadow-xl">
                {character.emoji}
              </div>
            )}
            {page.appearance.speakerVisible && page.speaker && <div className="mb-2 text-sm font-bold text-[#f4d35e]">{page.speaker}</div>}
            <div className="min-h-20 space-y-1 font-mono text-[15px] leading-6 text-white">
              {visibleLines.length > 0
                ? visibleLines.map((line, index) => <div key={index}>{line}</div>)
                : <div className="text-white/30">대사를 입력하면 여기에 표시됩니다.</div>}
            </div>
            {page.choices.length > 0 && (
              <div className="mt-4 grid gap-1 border-t border-white/10 pt-3 font-mono text-sm">
                {page.choices.map((choice, index) => (
                  <div key={choice.id} className="text-[#d5d7df]">
                    <span className="mr-2 text-[#8ea1ff]">[{index + 1}]</span>
                    {choice.label || '선택지 이름 없음'}
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
        <div className="mt-4 flex justify-center gap-3 text-xs text-[#707784]">
          <span className="rounded-md bg-[#171a20] px-2 py-1">Space: 빠르게 표시</span>
          <span className="rounded-md bg-[#171a20] px-2 py-1">Shift: 다음</span>
        </div>
      </div>
    </div>
  );
}
