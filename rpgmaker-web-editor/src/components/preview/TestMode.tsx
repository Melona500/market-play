import { useEffect, useMemo, useState } from 'react';
import type { Dialogue } from '../../domain/project';
import {
  getCharacter,
  normalizedGender,
  portraitSprite,
  type CharacterManifest,
  type ManifestExpression,
} from '../../services/characterRegistry';
import {
  advancePreview,
  choosePreview,
  createPreviewState,
  evaluateCondition,
  findPreviewPage,
  visibleChoices,
  type PreviewState,
} from '../../services/previewEngine';
import { parseDialogueText } from '../../services/dialogueText';
import { PortraitSprite } from '../characters/PortraitSprite';

interface Props {
  dialogue: Dialogue;
  manifest: CharacterManifest;
  onExit: () => void;
}

function parseValue(value: string): string | number | boolean {
  if (value === 'true') return true;
  if (value === 'false') return false;
  const numeric = Number(value);
  return value.trim() !== '' && Number.isFinite(numeric) ? numeric : value;
}

function FormattedText({ value, variables }: { value: string; variables: PreviewState['variables'] }) {
  return parseDialogueText(value, variables).map((segment, index) => (
    <span
      key={index}
      style={{
        color: segment.color,
        fontWeight: segment.bold ? 700 : undefined,
        fontStyle: segment.italic ? 'italic' : undefined,
        textDecoration: segment.strikethrough ? 'line-through' : undefined,
      }}
    >
      {segment.text}
    </span>
  ));
}

export function TestMode({ dialogue, manifest, onExit }: Props) {
  const [seedVariables, setSeedVariables] = useState<Record<string, string | number | boolean>>({});
  const [seedItems, setSeedItems] = useState<Record<string, number>>({});
  const [state, setState] = useState<PreviewState>(() => createPreviewState(dialogue));
  const [newVariable, setNewVariable] = useState('');
  const [newItem, setNewItem] = useState('');

  const restart = (variables = seedVariables, items = seedItems) =>
    setState(createPreviewState(dialogue, variables, items));

  useEffect(() => {
    setSeedVariables({});
    setSeedItems({});
    setState(createPreviewState(dialogue));
  }, [dialogue.id]);

  const page = useMemo(
    () => findPreviewPage(dialogue, state.currentPageId),
    [dialogue, state.currentPageId],
  );
  const character = page ? getCharacter(manifest, page.appearance.characterId) : undefined;
  const gender = character ? normalizedGender(character, page?.appearance.gender) : 'NONE';
  const expression = (page?.appearance.expression ?? 'NEUTRAL') as ManifestExpression;
  const sprite = character ? portraitSprite(manifest, character, gender, expression) : undefined;
  const displayCondition = page?.server?.displayCondition;
  const displayAllowed = displayCondition ? evaluateCondition(displayCondition, state) : true;
  const replacementLines =
    !displayAllowed && displayCondition
      ? displayCondition.replacementLines.filter((line) => line.trim())
      : undefined;
  const visibleLines = replacementLines?.length ? replacementLines : page?.lines.filter((line) => line.trim()) ?? [];
  const choices = page && !state.ended ? visibleChoices(page, state) : [];

  const editVariable = (name: string, value: string) => {
    const next = { ...seedVariables, [name]: parseValue(value) };
    setSeedVariables(next);
    restart(next, seedItems);
  };

  const removeVariable = (name: string) => {
    const next = { ...seedVariables };
    delete next[name];
    setSeedVariables(next);
    restart(next, seedItems);
  };

  const editItem = (name: string, amount: number) => {
    const next = { ...seedItems, [name]: Math.max(0, amount) };
    setSeedItems(next);
    restart(seedVariables, next);
  };

  return (
    <section className="flex min-w-0 flex-1 overflow-hidden bg-[#0e1115]">
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-14 items-center border-b border-[#232a33] bg-[#11151a] px-5">
          <div>
            <div className="text-sm font-semibold">테스트 모드</div>
            <div className="text-[11px] text-[#6d7785]">
              서버에 저장하지 않고 현재 편집 데이터를 시뮬레이션합니다. 실제 서버에서는 F로 타이핑 전체 표시와 다음 진행을 모두 조작하며, 수동 전체 표시 후 0.5초 동안 다음 진행이 잠깁니다.
            </div>
          </div>
          <button
            type="button"
            onClick={() => restart()}
            className="ml-auto rounded-lg px-3 py-2 text-xs text-[#a5aebb] hover:bg-[#20252d]"
          >
            ↻ 처음부터
          </button>
          <button
            type="button"
            onClick={onExit}
            className="ml-2 rounded-lg bg-[#252b35] px-3 py-2 text-xs font-semibold text-[#e5e8ed] hover:bg-[#303744]"
          >
            편집으로 돌아가기
          </button>
        </header>

        <div className="flex min-h-0 flex-1 items-center justify-center overflow-y-auto p-8">
          <div className="w-full max-w-[900px]">
            <div className="mb-3 flex items-center justify-between text-xs text-[#697382]">
              <span>MINECRAFT DIALOGUE TEST</span>
              <span>{state.flow.length} flow steps · safety 64</span>
            </div>

            <div className="relative min-h-[520px] overflow-hidden rounded-2xl border border-[#272e38] bg-[radial-gradient(circle_at_50%_10%,#233043,#11161d_50%,#0b0e12_100%)] shadow-2xl">
              <div className="absolute inset-x-12 bottom-12 rounded-lg border-2 border-[#595b62] bg-[#17181c]/96 px-7 py-6 shadow-[0_0_0_4px_rgba(0,0,0,.55)]">
                {state.ended ? (
                  <div className="py-12 text-center">
                    <div className="text-lg font-bold text-[#e3e6eb]">대화 종료</div>
                    <div className="mt-2 text-sm text-[#89929f]">
                      {state.safetyError ?? '현재 테스트 경로가 정상적으로 종료되었습니다.'}
                    </div>
                    <button
                      type="button"
                      onClick={() => restart()}
                      className="mt-5 rounded-lg bg-[#7c8cff] px-4 py-2 text-sm font-semibold text-white"
                    >
                      다시 테스트
                    </button>
                  </div>
                ) : page ? (
                  <>
                    {page.appearance.visible && character && (
                      <div className="absolute -top-28 left-6">
                        <PortraitSprite sprite={sprite} size={104} className="border border-white/10 shadow-xl" />
                      </div>
                    )}
                    {page.appearance.speakerVisible && state.speaker && (
                      <div className="mb-3 text-sm font-bold text-[#f0d566]">
                        <FormattedText value={state.speaker} variables={{ player_name: 'Player', ...state.variables }} />
                      </div>
                    )}
                    <div className="min-h-28 space-y-1.5 font-mono text-[16px] leading-7 text-white">
                      {page.server?.operationOnly ? (
                        <div className="text-[#8b94a0]">연산 전용 페이지를 자동으로 처리했습니다.</div>
                      ) : visibleLines.length ? (
                        visibleLines.map((line, index) => (
                          <div key={index}>
                            <FormattedText value={line} variables={{ player_name: 'Player', ...state.variables }} />
                          </div>
                        ))
                      ) : (
                        <div className="text-white/30">표시할 대사가 없습니다.</div>
                      )}
                    </div>

                    {choices.length > 0 ? (
                      <div className="mt-5 grid gap-2 border-t border-white/10 pt-4">
                        {choices.map((choice, index) => (
                          <button
                            type="button"
                            key={choice.id}
                            onClick={() => setState((current) => choosePreview(dialogue, current, choice))}
                            className="rounded-md px-2 py-1.5 text-left font-mono text-sm text-[#d9dde5] hover:bg-white/5"
                          >
                            <span className="mr-2 text-[#91a0ff]">[{index + 1}]</span>
                            <FormattedText
                              value={choice.label || '이름 없는 선택지'}
                              variables={{ player_name: 'Player', ...state.variables }}
                            />
                            <span className="ml-2 text-[10px] text-[#6f7785]">
                              {choice.endAfterTarget ? 'END' : 'CONTINUE'}
                            </span>
                          </button>
                        ))}
                      </div>
                    ) : (
                      <button
                        type="button"
                        onClick={() => setState((current) => advancePreview(dialogue, current))}
                        className="mt-5 w-full rounded-md border-t border-white/10 pt-4 text-right font-mono text-xs text-[#a5adba] hover:text-white"
                      >
                        F · 다음 ›
                      </button>
                    )}
                  </>
                ) : (
                  <div className="py-12 text-center text-red-300">현재 페이지를 찾을 수 없습니다.</div>
                )}
              </div>
            </div>

            {!displayAllowed && replacementLines?.length === 0 && (
              <div className="mt-3 rounded-lg bg-amber-400/8 px-3 py-2 text-xs text-amber-200">
                표시 조건을 만족하지 않았지만 replacement lines가 비어 있어 원래 대사를 표시합니다.
              </div>
            )}
          </div>
        </div>
      </div>

      <aside className="w-[340px] shrink-0 overflow-y-auto border-l border-[#242a33] bg-[#12161b] p-4">
        <div className="text-xs font-semibold uppercase tracking-[0.14em] text-[#707a88]">Debug / Test Variables</div>
        <p className="mt-2 text-xs leading-5 text-[#697382]">
          테스트 값만 변경합니다. 프로젝트나 Minecraft 서버 변수에는 저장되지 않습니다.
        </p>

        <div className="mt-5">
          <div className="text-xs font-semibold text-[#aab2bd]">Variables</div>
          <div className="mt-2 space-y-2">
            {Object.entries(seedVariables).map(([name, value]) => (
              <div key={name} className="grid grid-cols-[1fr_100px_28px] gap-2">
                <div className="truncate rounded-lg bg-[#1b2027] px-2 py-2 text-xs">{name}</div>
                <input
                  className="rounded-lg bg-[#1b2027] px-2 py-2 text-xs outline-none focus:ring-1 focus:ring-[#7c8cff]"
                  value={String(value)}
                  onChange={(event) => editVariable(name, event.target.value)}
                />
                <button type="button" onClick={() => removeVariable(name)} className="text-[#6f7885] hover:text-red-300">
                  ×
                </button>
              </div>
            ))}
          </div>
          <div className="mt-2 flex gap-2">
            <input
              value={newVariable}
              onChange={(event) => setNewVariable(event.target.value)}
              placeholder="money"
              className="min-w-0 flex-1 rounded-lg bg-[#1b2027] px-3 py-2 text-xs outline-none"
            />
            <button
              type="button"
              onClick={() => {
                const name = newVariable.trim();
                if (!name) return;
                editVariable(name, '0');
                setNewVariable('');
              }}
              className="rounded-lg bg-[#252b35] px-3 py-2 text-xs"
            >
              추가
            </button>
          </div>
        </div>

        <div className="mt-6">
          <div className="text-xs font-semibold text-[#aab2bd]">Items</div>
          <div className="mt-2 space-y-2">
            {Object.entries(seedItems).map(([name, amount]) => (
              <div key={name} className="grid grid-cols-[1fr_70px] gap-2">
                <div className="truncate rounded-lg bg-[#1b2027] px-2 py-2 text-xs">{name}</div>
                <input
                  type="number"
                  min={0}
                  className="rounded-lg bg-[#1b2027] px-2 py-2 text-xs outline-none"
                  value={amount}
                  onChange={(event) => editItem(name, Number(event.target.value) || 0)}
                />
              </div>
            ))}
          </div>
          <div className="mt-2 flex gap-2">
            <input
              value={newItem}
              onChange={(event) => setNewItem(event.target.value)}
              placeholder="minecraft:emerald"
              className="min-w-0 flex-1 rounded-lg bg-[#1b2027] px-3 py-2 text-xs outline-none"
            />
            <button
              type="button"
              onClick={() => {
                const name = newItem.trim();
                if (!name) return;
                editItem(name, 1);
                setNewItem('');
              }}
              className="rounded-lg bg-[#252b35] px-3 py-2 text-xs"
            >
              추가
            </button>
          </div>
        </div>

        <div className="mt-6">
          <div className="text-xs font-semibold text-[#aab2bd]">현재 State</div>
          <div className="mt-2 rounded-xl bg-[#171b21] p-3 text-xs text-[#8b95a3]">
            <div>Page: {dialogue.pages.findIndex((candidate) => candidate.id === state.currentPageId) + 1}</div>
            <div className="mt-1">Ended: {String(state.ended)}</div>
          </div>
        </div>

        <div className="mt-6">
          <div className="text-xs font-semibold text-[#aab2bd]">Flow</div>
          <div className="mt-2 space-y-1 text-xs text-[#7e8896]">
            {state.flow.slice(-10).map((step, index) => (
              <div key={`${step}-${index}`}>→ {step}</div>
            ))}
          </div>
        </div>

        <div className="mt-6">
          <div className="text-xs font-semibold text-[#aab2bd]">Effect Log</div>
          <div className="mt-2 space-y-1 text-[11px] leading-5 text-[#7e8896]">
            {state.effects.length ? state.effects.slice(-12).map((effect, index) => <div key={`${effect}-${index}`}>{effect}</div>) : '아직 실행된 효과가 없습니다.'}
          </div>
        </div>
      </aside>
    </section>
  );
}
