import type { CharacterDefinition } from '../../domain/project';

interface Props {
  characters: CharacterDefinition[];
  onSelect: (character: CharacterDefinition) => void;
  onClose: () => void;
}

export function CharacterSelectorModal({ characters, onSelect, onClose }: Props) {
  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/60" onMouseDown={onClose}>
      <div className="w-[520px] rounded-2xl bg-[#1a1e25] p-5 shadow-2xl" onMouseDown={(event) => event.stopPropagation()}>
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-semibold">캐릭터 선택</h2>
          <button onClick={onClose} className="rounded-md px-2 py-1 text-[#a1a7b3] hover:bg-[#272c35]">✕</button>
        </div>
        <div className="mt-4 grid grid-cols-3 gap-2">
          {characters.map((character) => (
            <button key={character.id} onClick={() => onSelect(character)} className="rounded-xl bg-[#222730] p-4 text-left hover:bg-[#2b313c]">
              <div className="text-3xl">{character.emoji}</div>
              <div className="mt-3 text-sm font-medium">{character.name}</div>
              <div className="mt-1 text-xs text-[#838b98]">{character.expressions.join(' · ')}</div>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
