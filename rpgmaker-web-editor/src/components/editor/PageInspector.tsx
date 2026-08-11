import { useState } from 'react';
import type { CharacterDefinition, Dialogue, DialoguePage } from '../../domain/project';
import { visibleLength } from '../../services/dialogueText';
import { CharacterSelectorModal } from './CharacterSelectorModal';
import { ServerSettingsPanel } from './ServerSettingsPanel';

interface Props {
  page: DialoguePage;
  dialogue: Dialogue;
  characters: CharacterDefinition[];
  onChange: (mutator: (page: DialoguePage) => void) => void;
  onAddChoice: () => void;
  onRemoveChoice: (choiceId: string) => void;
}

export function PageInspector({ page, dialogue, characters, onChange, onAddChoice, onRemoveChoice }: Props) {
  const [characterOpen, setCharacterOpen] = useState(false);
  const character = characters.find((candidate) => candidate.id === page.appearance.characterId);

  return (
    <aside className="scrollbar-thin w-[420px] shrink-0 overflow-y-auto border-l border-[#242933] bg-[#15181e] p-5">
      <div className="text-xs font-semibold tracking-wider text-[#6f7784]">PAGE</div>
      <div className="mt-1 text-lg font-semibold">{page.editorLabel || '페이지 설정'}</div>

      <section className="mt-6">
        <label className="text-xs font-semibold text-[#9da4b0]">페이지 별칭</label>
        <input
          value={page.editorLabel ?? ''}
          onChange={(event) => onChange((draft) => void (draft.editorLabel = event.target.value))}
          placeholder="예: 물건 제안"
          className="mt-2 w-full rounded-lg bg-[#20242c] px-3 py-2 text-sm outline-none placeholder:text-[#59616e]"
        />
      </section>

      <div className="my-6 h-px bg-[#282d36]" />
      <section>
        <div className="flex items-center justify-between">
          <label className="text-xs font-semibold text-[#9da4b0]">화자</label>
          <span className={`text-xs ${visibleLength(page.speaker) > 10 ? 'text-red-400' : 'text-[#69717e]'}`}>{visibleLength(page.speaker)} / 10</span>
        </div>
        <input
          value={page.speaker}
          onChange={(event) => onChange((draft) => void (draft.speaker = event.target.value))}
          placeholder="화자 이름"
          className="mt-2 w-full rounded-lg bg-[#20242c] px-3 py-2 text-sm outline-none placeholder:text-[#59616e]"
        />
      </section>

      <div className="my-6 h-px bg-[#282d36]" />
      <section>
        <div className="flex items-center justify-between">
          <label className="text-xs font-semibold text-[#9da4b0]">캐릭터</label>
          <label className="flex items-center gap-2 text-xs text-[#8b929e]">
            <input
              type="checkbox"
              checked={page.appearance.visible}
              onChange={(event) => onChange((draft) => void (draft.appearance.visible = event.target.checked))}
            />
            캐릭터 표시
          </label>
          <label className="flex items-center gap-2 text-xs text-[#8b929e]">
            <input
              type="checkbox"
              checked={page.appearance.speakerVisible}
              onChange={(event) => onChange((draft) => void (draft.appearance.speakerVisible = event.target.checked))}
            />
            화자 이름 표시
          </label>
        </div>
        <button onClick={() => setCharacterOpen(true)} className="mt-2 flex w-full items-center justify-between rounded-lg bg-[#20242c] px-3 py-2.5 text-sm hover:bg-[#272c35]">
          <span>{character ? `${character.emoji} ${character.name}` : '캐릭터 선택'}</span>
          <span className="text-[#777f8b]">›</span>
        </button>
        {character && (
          <select
            value={page.appearance.expression ?? character.expressions[0]}
            onChange={(event) => onChange((draft) => void (draft.appearance.expression = event.target.value))}
            className="mt-2 w-full rounded-lg bg-[#20242c] px-3 py-2 text-sm"
          >
            {character.expressions.map((expression) => <option key={expression}>{expression}</option>)}
          </select>
        )}
      </section>

      <div className="my-6 h-px bg-[#282d36]" />
      <section>
        <div className="text-xs font-semibold text-[#9da4b0]">대사</div>
        <div className="mt-3 space-y-2">
          {page.lines.map((line, index) => (
            <div key={index}>
              <div className="mb-1 flex items-center justify-between text-[11px] text-[#6f7784]">
                <span>{index + 1}</span>
                <span className={line.length > 30 ? 'text-red-400' : ''}>{line.length} / 30</span>
              </div>
              <input
                value={line}
                onChange={(event) => onChange((draft) => void (draft.lines[index] = event.target.value))}
                className="w-full rounded-lg bg-[#20242c] px-3 py-2 text-sm outline-none"
                placeholder={index === 0 ? '대사를 입력하세요' : '빈 줄'}
              />
              {line.length > 30 && <div className="mt-1 text-[11px] text-red-400">Minecraft 표시 제한을 {line.length - 30}자 초과했습니다.</div>}
            </div>
          ))}
        </div>
      </section>

      <div className="my-6 h-px bg-[#282d36]" />
      <section>
        <div className="flex items-center justify-between">
          <div className="text-xs font-semibold text-[#9da4b0]">선택지</div>
          <div className="text-xs text-[#6f7784]">{page.choices.length} / 8</div>
        </div>
        <div className="mt-3 space-y-2">
          {page.choices.map((choice, index) => (
            <div key={choice.id} className="rounded-xl bg-[#20242c] p-3">
              <div className="flex gap-2">
                <span className="pt-2 text-xs text-[#737b87]">{index + 1}</span>
                <div className="min-w-0 flex-1">
                  <input
                    value={choice.label}
                    onChange={(event) =>
                      onChange((draft) => {
                        const target = draft.choices.find((candidate) => candidate.id === choice.id);
                        if (target) target.label = event.target.value;
                      })
                    }
                    placeholder="선택지 이름"
                    className="w-full bg-transparent py-1.5 text-sm outline-none"
                  />
                  <div className={`text-right text-[10px] ${choice.label.length > 10 ? 'text-red-400' : 'text-[#69717e]'}`}>{choice.label.length} / 10</div>
                </div>
                <button onClick={() => onRemoveChoice(choice.id)} className="text-xs text-[#737b87] hover:text-red-300">삭제</button>
              </div>
              <select
                value={choice.targetPageId ?? ''}
                onChange={(event) =>
                  onChange((draft) => {
                    const target = draft.choices.find((candidate) => candidate.id === choice.id);
                    if (target) target.targetPageId = event.target.value || undefined;
                  })
                }
                className="mt-2 w-full rounded-lg bg-[#171a20] px-2 py-2 text-xs text-[#b9bec7]"
              >
                <option value="">이동할 페이지 선택</option>
                {dialogue.pages.map((targetPage, targetIndex) => (
                  <option key={targetPage.id} value={targetPage.id}>
                    Page {targetIndex + 1} — {targetPage.editorLabel || `Page ${targetIndex + 1}`}
                  </option>
                ))}
              </select>
            </div>
          ))}
        </div>
        <button
          disabled={page.choices.length >= 8}
          onClick={onAddChoice}
          className="mt-3 w-full rounded-lg bg-[#20242c] px-3 py-2 text-sm text-[#7c8cff] enabled:hover:bg-[#272c35] disabled:text-[#555c68]"
        >
          + 선택지 추가
        </button>
      </section>

      <ServerSettingsPanel page={page} dialogue={dialogue} onChange={onChange} />

      {characterOpen && (
        <CharacterSelectorModal
          characters={characters}
          onClose={() => setCharacterOpen(false)}
          onSelect={(selected) => {
            onChange((draft) => {
              draft.appearance.characterId = selected.id;
              draft.appearance.expression = selected.expressions[0];
              if (!draft.speaker) draft.speaker = selected.name;
            });
            setCharacterOpen(false);
          }}
        />
      )}
    </aside>
  );
}
