import { useMemo, useState } from 'react';
import {
  availableExpressions,
  normalizedGender,
  portraitSprite,
  type CharacterManifest,
  type CharacterManifestEntry,
  type ManifestExpression,
  type ManifestGender,
} from '../../services/characterRegistry';
import { PortraitSprite } from './PortraitSprite';

interface Props {
  manifest: CharacterManifest;
  selectedId?: string;
  gender?: string;
  expression?: string;
  onSelect: (character: CharacterManifestEntry, gender: ManifestGender, expression: ManifestExpression) => void;
  onClose: () => void;
}

export function CharacterGallery({ manifest, selectedId, gender, expression, onSelect, onClose }: Props) {
  const [query, setQuery] = useState('');
  const filtered = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return manifest.characters.filter(
      (character) =>
        !needle ||
        character.label.toLowerCase().includes(needle) ||
        character.id.toLowerCase().includes(needle),
    );
  }, [manifest, query]);

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/70 p-8" onMouseDown={onClose}>
      <div
        className="flex max-h-[82vh] w-[760px] flex-col overflow-hidden rounded-2xl border border-[#2a3039] bg-[#14181e] shadow-2xl"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="flex items-center gap-3 border-b border-[#242a33] px-5 py-4">
          <div>
            <div className="text-base font-semibold text-[#f4f6f8]">캐릭터 선택</div>
            <div className="mt-0.5 text-xs text-[#7e8795]">Resource Pack의 실제 초상화 목록입니다.</div>
          </div>
          <input
            autoFocus
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="캐릭터 검색"
            className="ml-auto w-64 rounded-lg bg-[#20252d] px-3 py-2 text-sm outline-none placeholder:text-[#606977]"
          />
          <button onClick={onClose} className="rounded-lg px-3 py-2 text-[#8e96a4] hover:bg-[#20252d]">
            ✕
          </button>
        </header>

        <div className="grid grid-cols-4 gap-3 overflow-y-auto p-5">
          {filtered.map((character) => {
            const resolvedGender = normalizedGender(
              character,
              character.id === selectedId ? gender : undefined,
            );
            const expressions = availableExpressions(character, resolvedGender);
            const resolvedExpression =
              character.id === selectedId && expressions.includes(expression as ManifestExpression)
                ? (expression as ManifestExpression)
                : expressions.includes('HAPPY')
                  ? 'HAPPY'
                  : expressions[0];
            const sprite = portraitSprite(manifest, character, resolvedGender, resolvedExpression);
            return (
              <button
                key={character.id}
                onClick={() => onSelect(character, resolvedGender, resolvedExpression)}
                className={`flex min-h-36 flex-col items-center rounded-xl border p-3 text-center transition ${
                  character.id === selectedId
                    ? 'border-[#7c8cff] bg-[#22283a]'
                    : 'border-[#252b34] bg-[#1a1e25] hover:border-[#3a4350] hover:bg-[#20252d]'
                }`}
              >
                <PortraitSprite sprite={sprite} size={82} />
                <div className="mt-3 text-sm font-semibold">{character.label}</div>
                <div className="mt-1 text-[10px] tracking-wide text-[#68717f]">{character.id}</div>
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
}
