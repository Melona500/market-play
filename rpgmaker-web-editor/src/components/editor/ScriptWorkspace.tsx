import { useEffect } from 'react';
import type { DialoguePage } from '../../domain/project';
import {
  availableExpressions,
  getCharacter,
  normalizedGender,
  portraitSprite,
  type CharacterManifest,
  type ManifestExpression,
} from '../../services/characterRegistry';
import { visibleLength } from '../../services/projectValidator';
import { useEditorStore } from '../../store/editorStore';
import { PortraitSprite } from '../characters/PortraitSprite';
import { ChoiceBranchWorkspace, visitChoice } from './ChoiceBranchWorkspace';
import { DialogueMovementWorkspace } from './DialogueMovementWorkspace';
import type { InspectorSection } from './EditorInspector';

interface Props {
  page: DialoguePage;
  pageNumber: number;
  manifest: CharacterManifest;
  variableNames?: string[];
  activePanel?: InspectorSection;
  onOpenPanel: (panel: InspectorSection) => void;
  onChange: (mutator: (page: DialoguePage) => void) => void;
}

const panelButtons: Array<[InspectorSection, string, string]> = [
  ['character', '캐릭터', '인물·표정'],
  ['condition', '조건', '표시 규칙'],
  ['effects', '효과', '아이템·변수'],
  ['flow', '대화 이동', '페이지·대화 이동'],
];

export function ScriptWorkspace({
  page,
  pageNumber,
  manifest,
  activePanel,
  onOpenPanel,
  onChange,
}: Props) {
  const character = getCharacter(manifest, page.appearance.characterId);
  const gender = character ? normalizedGender(character, page.appearance.gender) : 'NONE';
  const expressions = character ? availableExpressions(character, gender) : [];
  const expression = expressions.includes(page.appearance.expression as ManifestExpression)
    ? (page.appearance.expression as ManifestExpression)
    : expressions[0];
  const sprite = character && expression ? portraitSprite(manifest, character, gender, expression) : undefined;
  const activeChoiceId = useEditorStore((state) => state.activeChoiceId);
  const selectChoice = useEditorStore((state) => state.selectChoice);
  const selectedChoice = activeChoiceId ? visitChoice(page.choices, activeChoiceId) : undefined;

  useEffect(() => {
    if (!activeChoiceId) return;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') selectChoice(undefined);
    };
    window.addEventListener('keydown', closeOnEscape);
    return () => window.removeEventListener('keydown', closeOnEscape);
  }, [activeChoiceId, selectChoice]);

  useEffect(() => {
    if (activeChoiceId && !selectedChoice) selectChoice(undefined);
  }, [activeChoiceId, selectedChoice, selectChoice]);

  return (
    <section className="flex min-w-0 flex-1 flex-col overflow-hidden bg-[#0f1216]">
      <div className="border-b border-[#20262e] px-8 py-4">
        <div className="mx-auto flex max-w-[920px] items-center gap-3">
          <span className="rounded-md bg-[#1d232b] px-2 py-1 text-[11px] font-semibold text-[#7e8896]">
            PAGE {pageNumber}
          </span>
          <input
            value={page.editorLabel ?? ''}
            onChange={(event) => onChange((draft) => void (draft.editorLabel = event.target.value))}
            placeholder={`Page ${pageNumber} 별칭`}
            className="min-w-0 flex-1 bg-transparent text-lg font-semibold text-[#eef1f5] outline-none placeholder:text-[#4f5866]"
          />
        </div>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto px-8 py-8">
        <div className="mx-auto max-w-[920px]">
          <div className="grid grid-cols-[140px_1fr] gap-7">
            <button
              type="button"
              onClick={() => onOpenPanel('character')}
              className={`group flex min-h-40 flex-col items-center justify-center rounded-2xl border p-4 transition ${
                activePanel === 'character'
                  ? 'border-[#7c8cff] bg-[#20263b]'
                  : 'border-[#252c35] bg-[#15191f] hover:border-[#38414e] hover:bg-[#191e25]'
              }`}
            >
              {page.appearance.visible && character ? (
                <>
                  <PortraitSprite sprite={sprite} size={94} />
                  <div className="mt-3 text-sm font-semibold">{character.label}</div>
                  <div className="mt-1 text-[11px] text-[#747e8d]">
                    {expression ? manifest.expressionLabels[expression] : ''}
                  </div>
                </>
              ) : (
                <>
                  <div className="grid h-24 w-24 place-items-center rounded-xl border border-dashed border-[#333b47] text-3xl text-[#555f6e]">
                    +
                  </div>
                  <div className="mt-3 text-xs text-[#7c8796]">캐릭터 선택</div>
                </>
              )}
            </button>

            <div className="min-w-0">
              <label className="text-xs font-semibold uppercase tracking-[0.14em] text-[#6e7887]">화자</label>
              <div className="mt-2 flex items-center rounded-xl border border-[#282f39] bg-[#15191f] px-4">
                <input
                  value={page.speaker}
                  onChange={(event) => onChange((draft) => void (draft.speaker = event.target.value))}
                  placeholder="화자 이름"
                  className="min-w-0 flex-1 bg-transparent py-3 text-lg font-semibold outline-none placeholder:text-[#4f5865]"
                />
                <span className={`text-xs ${visibleLength(page.speaker) > 10 ? 'text-red-400' : 'text-[#626c79]'}`}>
                  {visibleLength(page.speaker)}/10
                </span>
              </div>
              <div className="mt-2 text-[11px] text-[#737e8c]">화자 이름에도 대사와 같은 색상·굵게·기울임·취소선 코드를 사용할 수 있습니다.</div>

              {!page.appearance.speakerVisible && (
                <div className="mt-2 text-xs text-[#737e8c]">
                  화자 이름 표시가 꺼져 있어 게임에서는 이름과 작은 박스가 숨겨집니다.
                </div>
              )}

              <div className="mt-7 flex items-center justify-between">
                <label className="text-xs font-semibold uppercase tracking-[0.14em] text-[#6e7887]">대사</label>
                <span className="text-xs text-[#626c79]">최대 4줄 · 줄당 표시 문자 30자</span>
              </div>

              <div className="mt-3 rounded-xl border border-[#2b4151] bg-[#111a22] px-4 py-3 text-sm text-[#c9d3dc]">
                <code className="rounded bg-black/25 px-1.5 py-0.5 text-[#9ca8ff]">{'{{변수}}'}</code>으로 대화문에 변수값을 출력할 수 있습니다.{' '}
                <code className="rounded bg-black/25 px-1.5 py-0.5 text-[#9ca8ff]">#색코드:단어</code>로 색을 입힐 수 있습니다. 굵게·기울임·취소선은{' '}
                <code className="rounded bg-black/25 px-1.5 py-0.5 text-[#9ca8ff]">#FF0000:bold,italic,strikethrough:단어</code> 형식입니다.
                {' '}Skript 표현식 <code className="rounded bg-black/25 px-1.5 py-0.5 text-[#9ca8ff]">%player%</code>와 <code className="rounded bg-black/25 px-1.5 py-0.5 text-[#9ca8ff]">{'%{변수::%uuid of player%}%'}</code>도 서버에서 해석됩니다.
              </div>

              <div className="mt-3 space-y-3">
                {page.lines.map((line, index) => {
                  const length = visibleLength(line);
                  const over = length > 30;
                  return (
                    <div
                      key={index}
                      className={`group grid grid-cols-[28px_1fr_60px] items-center gap-3 rounded-xl border px-3 transition ${
                        over
                          ? 'border-red-400/50 bg-red-400/5'
                          : 'border-[#282f39] bg-[#15191f] focus-within:border-[#7c8cff]'
                      }`}
                    >
                      <span className="text-center text-xs font-semibold text-[#5f6978]">{index + 1}</span>
                      <input
                        value={line}
                        onChange={(event) =>
                          onChange((draft) => {
                            draft.lines[index] = event.target.value;
                          })
                        }
                        placeholder={index === 0 ? '대사를 입력하세요.' : '빈 줄'}
                        className="min-w-0 bg-transparent py-4 text-[15px] leading-6 outline-none placeholder:text-[#454e5b]"
                      />
                      <span className={`text-right text-[11px] ${over ? 'text-red-400' : 'text-[#616b78]'}`}>
                        {length}/30
                      </span>
                    </div>
                  );
                })}
              </div>

              {page.lines.some((line) => visibleLength(line) > 30) && (
                <div className="mt-3 rounded-lg bg-red-400/8 px-3 py-2 text-xs text-red-300">
                  Minecraft 표시 제한을 넘은 줄이 있습니다. 입력은 유지되지만 서버 반영 전에 수정해야 합니다.
                </div>
              )}
            </div>
          </div>

          <div className="my-8 h-px bg-[#20262e]" />

          <div>
            <div className="text-xs font-semibold uppercase tracking-[0.14em] text-[#6e7887]">페이지 기능</div>
            <div className="mt-3 grid grid-cols-2 gap-3">
              {panelButtons.map(([panel, title, description]) => {
                const count =
                  panel === 'effects'
                    ? effectCount(page)
                    : panel === 'condition'
                      ? page.server?.displayCondition.mode !== 'none' && page.server?.displayCondition.mode
                        ? 1
                        : 0
                      : panel === 'flow'
                        ? flowCount(page)
                        : 0;
                return (
                  <button
                    key={panel}
                    type="button"
                    onClick={() => onOpenPanel(panel)}
                    className={`rounded-xl border p-4 text-left transition ${
                      activePanel === panel
                        ? 'border-[#7c8cff] bg-[#20263b]'
                        : 'border-[#252c35] bg-[#15191f] hover:border-[#38414e] hover:bg-[#191e25]'
                    }`}
                  >
                    <div className="flex items-center justify-between">
                      <span className="text-sm font-semibold">{title}</span>
                      {count > 0 && (
                        <span className="rounded-full bg-[#303855] px-2 py-0.5 text-[10px] text-[#aab4ff]">{count}</span>
                      )}
                    </div>
                    <div className="mt-1 text-xs text-[#6f7987]">{description}</div>
                  </button>
                );
              })}
            </div>
          </div>

          <div className="my-8 h-px bg-[#20262e]" />
          <div>
            <div className="flex items-center justify-between">
              <div>
                <div className="text-xs font-semibold uppercase tracking-[0.14em] text-[#6e7887]">현재 페이지 선택지</div>
                <div className="mt-1 text-xs text-[#5f6976]">선택지는 왼쪽 사이드바의 ‘선택지 추가’에서도 만들 수 있습니다.</div>
              </div>
              <button
                type="button"
                onClick={() => page.choices[0] ? selectChoice(page.choices[0].id) : onOpenPanel('choices')}
                className="text-xs text-[#b09cff]"
              >
                {page.choices.length ? '선택지 편집' : '선택지 만들기'}
              </button>
            </div>
            {page.choices.length > 0 ? (
              <div className="mt-3 grid gap-2">
                {page.choices.map((choice, index) => (
                  <button
                    key={choice.id}
                    type="button"
                    onClick={() => selectChoice(choice.id)}
                    className="flex items-center rounded-xl border border-[#30283d] bg-[#18151f] px-4 py-3 text-left hover:bg-[#201b29]"
                  >
                    <span className="mr-3 text-xs font-semibold text-[#b09cff]">[{index + 1}]</span>
                    <span className="min-w-0 flex-1 truncate text-sm">
                      {choice.label || <span className="text-[#596270]">이름 없는 선택지</span>}
                    </span>
                    <span className="mr-3 text-[10px] text-[#707987]">후속 {choice.responsePages?.length ?? 0}p</span>
                    <span className="text-xs text-[#687281]">→</span>
                  </button>
                ))}
              </div>
            ) : (
              <button
                type="button"
                onClick={() => onOpenPanel('choices')}
                className="mt-3 w-full rounded-xl border border-dashed border-[#3a3048] px-4 py-5 text-sm text-[#8f7daf] hover:bg-[#191621]"
              >
                선택지를 추가해 플레이어 분기를 만드세요.
              </button>
            )}
          </div>

          {activePanel === 'flow' && <DialogueMovementWorkspace page={page} />}
          {activePanel === 'choices' && <ChoiceBranchWorkspace page={page} manifest={manifest} onChange={onChange} />}
        </div>
      </div>

      {selectedChoice && (
        <div
          className="fixed inset-0 z-[70] flex items-center justify-center bg-black/70 p-6 backdrop-blur-sm"
          onMouseDown={(event) => {
            if (event.currentTarget === event.target) selectChoice(undefined);
          }}
        >
          <div className="flex max-h-[92vh] w-full max-w-[980px] flex-col overflow-hidden rounded-2xl border border-[#4b3b59] bg-[#101218] shadow-2xl">
            <div className="flex items-center gap-3 border-b border-[#2d2634] px-6 py-4">
              <div className="min-w-0 flex-1">
                <div className="text-[11px] font-semibold uppercase tracking-[0.14em] text-[#a98cff]">선택지 상세 편집</div>
                <div className="mt-1 truncate text-base font-semibold text-[#eef1f5]">{selectedChoice.label || '이름 없는 선택지'}</div>
              </div>
              <button
                type="button"
                onClick={() => selectChoice(undefined)}
                className="rounded-lg px-3 py-2 text-sm text-[#8993a1] hover:bg-[#252b35] hover:text-white"
              >
                닫기 ✕
              </button>
            </div>
            <div className="min-h-0 flex-1 overflow-y-auto px-6 pb-6">
              <ChoiceBranchWorkspace page={page} manifest={manifest} onChange={onChange} />
            </div>
          </div>
        </div>
      )}
    </section>
  );
}

function effectCount(page: DialoguePage) {
  const effects = page.server?.effects;
  if (!effects) return 0;
  return [
    effects.giveItems,
    effects.takeItems,
    effects.variablesSet,
    effects.variablesDelete,
    effects.chatInputVariable,
    effects.sounds,
    effects.message,
    effects.returnTarget,
    effects.serverCommand,
  ].filter((value) => value.trim()).length;
}

function flowCount(page: DialoguePage) {
  const flow = page.server?.flow;
  if (!flow) return page.flow.ending ? 1 : 0;
  return [
    flow.nextPageId,
    flow.conditionalTargetPageId,
    flow.ending,
    page.server?.operationOnly,
  ].filter(Boolean).length;
}
