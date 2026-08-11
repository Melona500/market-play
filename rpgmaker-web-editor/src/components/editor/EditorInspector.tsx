import { useState } from 'react';
import type { Dialogue, DialoguePage, RPGProject } from '../../domain/project';
import { emptyChoiceSettings, emptyServerPage } from '../../domain/serverSettings';
import type { ServerPageSettings } from '../../domain/serverSettings';
import {
  availableExpressions,
  getCharacter,
  normalizedGender,
  portraitSprite,
  type CharacterManifest,
  type ManifestExpression,
  type ManifestGender,
} from '../../services/characterRegistry';
import { visibleLength } from '../../services/projectValidator';
import { CharacterGallery } from '../characters/CharacterGallery';
import { PortraitSprite } from '../characters/PortraitSprite';
import { ConditionBuilder } from '../conditions/ConditionBuilder';

export type InspectorSection = 'character' | 'choices' | 'condition' | 'effects' | 'flow' | 'other';

interface Props {
  section: InspectorSection;
  page: DialoguePage;
  dialogue: Dialogue;
  project: RPGProject;
  manifest: CharacterManifest;
  onClose: () => void;
  onChange: (mutator: (page: DialoguePage) => void) => void;
}

const input =
  'w-full rounded-lg border border-[#2a3039] bg-[#171b21] px-3 py-2 text-sm text-[#eef1f5] outline-none transition focus:border-[#7c8cff]';
const label = 'text-xs font-medium text-[#919aa8]';

const sectionTitle: Record<InspectorSection, [string, string]> = {
  character: ['캐릭터', '현재 페이지에 표시할 인물과 표정을 설정합니다.'],
  choices: ['선택지', '플레이어가 고를 수 있는 분기와 결과를 설정합니다.'],
  condition: ['표시 조건', '이 페이지를 언제 표시할지 정합니다.'],
  effects: ['효과', '페이지를 넘길 때 실행할 게임 효과를 설정합니다.'],
  flow: ['페이지 흐름', '다음 페이지, 조건부 이동, 종료 방식을 설정합니다.'],
  other: ['기타', '페이지 별칭과 고급 표시 옵션입니다.'],
};

function serverOf(page: DialoguePage): ServerPageSettings {
  return page.server ?? emptyServerPage();
}

function SectionHeader({ section, onClose }: { section: InspectorSection; onClose: () => void }) {
  const [title, description] = sectionTitle[section];
  return (
    <header className="flex items-start gap-3 border-b border-[#242a33] px-5 py-4">
      <div>
        <h2 className="text-base font-semibold text-[#f2f4f7]">{title}</h2>
        <p className="mt-1 text-xs leading-5 text-[#77818f]">{description}</p>
      </div>
      <button
        type="button"
        onClick={onClose}
        className="ml-auto rounded-lg px-2 py-1 text-[#788190] hover:bg-[#20252d] hover:text-white"
      >
        ✕
      </button>
    </header>
  );
}

function CharacterPanel({
  page,
  manifest,
  onChange,
}: {
  page: DialoguePage;
  manifest: CharacterManifest;
  onChange: Props['onChange'];
}) {
  const [galleryOpen, setGalleryOpen] = useState(false);
  const character = getCharacter(manifest, page.appearance.characterId);
  const gender = character ? normalizedGender(character, page.appearance.gender) : 'NONE';
  const expressions = character ? availableExpressions(character, gender) : [];
  const expression = expressions.includes(page.appearance.expression as ManifestExpression)
    ? (page.appearance.expression as ManifestExpression)
    : expressions[0];
  const sprite = character && expression ? portraitSprite(manifest, character, gender, expression) : undefined;

  return (
    <div className="space-y-5 p-5">
      <div className="rounded-xl bg-[#171b21] p-4">
        <div className="flex items-center gap-4">
          <PortraitSprite sprite={sprite} size={92} />
          <div className="min-w-0 flex-1">
            <div className="text-sm font-semibold">{character?.label ?? '캐릭터 없음'}</div>
            <div className="mt-1 text-xs text-[#737d8b]">{character?.id ?? '초상화를 선택하지 않았습니다.'}</div>
            <button
              type="button"
              onClick={() => setGalleryOpen(true)}
              className="mt-3 rounded-lg bg-[#252b35] px-3 py-2 text-xs text-[#cdd2da] hover:bg-[#303744]"
            >
              초상화 갤러리 열기
            </button>
          </div>
        </div>
      </div>

      <label className="flex items-center justify-between rounded-xl bg-[#171b21] px-4 py-3 text-sm">
        <div>
          <div className="font-medium">초상화 표시</div>
          <div className="mt-1 text-xs text-[#737d8b]">캐릭터 이미지만 숨깁니다.</div>
        </div>
        <input
          type="checkbox"
          checked={page.appearance.visible}
          onChange={(event) => onChange((draft) => void (draft.appearance.visible = event.target.checked))}
        />
      </label>

      <label className="flex items-center justify-between rounded-xl bg-[#171b21] p-3 text-sm">
        <div>
          <div className="font-medium">화자 이름 표시</div>
          <div className="mt-1 text-xs text-[#737d8b]">끄면 화자 이름과 작은 박스를 숨깁니다.</div>
        </div>
        <input
          type="checkbox"
          checked={page.appearance.speakerVisible}
          onChange={(event) => onChange((draft) => void (draft.appearance.speakerVisible = event.target.checked))}
        />
      </label>

      {character && character.genders.length > 1 && (
        <div>
          <div className={label}>성별</div>
          <div className="mt-2 grid grid-cols-2 gap-2">
            {(['MALE', 'FEMALE'] as ManifestGender[]).map((value) => (
              <button
                key={value}
                type="button"
                onClick={() =>
                  onChange((draft) => {
                    draft.appearance.gender = value === 'FEMALE' ? 'female' : 'male';
                    const nextExpressions = availableExpressions(character, value);
                    if (!nextExpressions.includes(draft.appearance.expression as ManifestExpression))
                      draft.appearance.expression = nextExpressions[0];
                  })
                }
                className={`rounded-lg border px-3 py-2 text-sm ${
                  gender === value
                    ? 'border-[#7c8cff] bg-[#232943]'
                    : 'border-[#2a3039] bg-[#171b21] hover:bg-[#20252d]'
                }`}
              >
                {value === 'MALE' ? '남성' : '여성'}
              </button>
            ))}
          </div>
        </div>
      )}

      {character && (
        <div>
          <div className={label}>표정</div>
          <div className="mt-2 grid grid-cols-2 gap-2">
            {expressions.map((value) => {
              const cell = portraitSprite(manifest, character, gender, value);
              return (
                <button
                  key={value}
                  type="button"
                  onClick={() => onChange((draft) => void (draft.appearance.expression = value))}
                  className={`flex items-center gap-2 rounded-xl border p-2 text-left ${
                    expression === value
                      ? 'border-[#7c8cff] bg-[#232943]'
                      : 'border-[#2a3039] bg-[#171b21] hover:bg-[#20252d]'
                  }`}
                >
                  <PortraitSprite sprite={cell} size={44} />
                  <span className="text-xs">{manifest.expressionLabels[value]}</span>
                </button>
              );
            })}
          </div>
        </div>
      )}

      <label className="flex items-center justify-between rounded-xl bg-[#171b21] px-4 py-3 text-sm">
        <div>
          <div className="font-medium">이전 페이지 외형 이어받기</div>
          <div className="mt-1 text-xs text-[#737d8b]">이전 페이지의 캐릭터/표정 상태를 재사용합니다.</div>
        </div>
        <input
          type="checkbox"
          checked={page.appearance.inheritPrevious}
          onChange={(event) => onChange((draft) => void (draft.appearance.inheritPrevious = event.target.checked))}
        />
      </label>

      {galleryOpen && (
        <CharacterGallery
          manifest={manifest}
          selectedId={page.appearance.characterId}
          gender={page.appearance.gender}
          expression={page.appearance.expression}
          onClose={() => setGalleryOpen(false)}
          onSelect={(selected, selectedGender, selectedExpression) => {
            onChange((draft) => {
              draft.appearance.characterId = selected.id;
              draft.appearance.gender =
                selectedGender === 'FEMALE' ? 'female' : selectedGender === 'MALE' ? 'male' : undefined;
              draft.appearance.expression = selectedExpression;
              draft.appearance.inheritPrevious = false;
              if (!draft.speaker) draft.speaker = selected.label;
            });
            setGalleryOpen(false);
          }}
        />
      )}
    </div>
  );
}

function ChoicesPanel({
  page,
  project,
  onChange,
}: {
  page: DialoguePage;
  project: RPGProject;
  onChange: Props['onChange'];
}) {
  const [expanded, setExpanded] = useState<string>();

  return (
    <div className="space-y-3 p-5">
      <div className="flex items-center justify-between">
        <div className="text-xs text-[#7d8795]">최대 8개</div>
        <div className="text-xs text-[#a9b0bb]">{page.choices.length} / 8</div>
      </div>

      {page.choices.map((rawChoice, index) => {
        const choice = rawChoice as typeof rawChoice & { server?: ReturnType<typeof emptyChoiceSettings> };
        const detailOpen = expanded === choice.id;
        return (
          <div key={choice.id} className="rounded-xl border border-[#282f39] bg-[#171b21] p-3">
            <div className="flex items-center gap-2">
              <span className="grid h-6 w-6 place-items-center rounded-md bg-[#252b35] text-[11px] text-[#8993a1]">
                {index + 1}
              </span>
              <input
                className="min-w-0 flex-1 bg-transparent text-sm font-medium outline-none"
                value={choice.label}
                maxLength={40}
                placeholder="선택지 이름"
                onChange={(event) =>
                  onChange((draft) => {
                    const target = draft.choices.find((entry) => entry.id === choice.id);
                    if (target) target.label = event.target.value;
                  })
                }
              />
              <span className={`text-[10px] ${choice.label.length > 10 ? 'text-red-400' : 'text-[#697382]'}`}>
                {choice.label.length}/10
              </span>
            </div>

            <div className="mt-3 flex justify-end">
              <button
                type="button"
                onClick={() =>
                  onChange((draft) => {
                    draft.choices = draft.choices.filter((entry) => entry.id !== choice.id);
                  })
                }
                className="rounded-lg px-3 text-xs text-[#77818f] hover:bg-red-400/10 hover:text-red-300"
              >
                삭제
              </button>
            </div>

            <button
              type="button"
              onClick={() => setExpanded(detailOpen ? undefined : choice.id)}
              className="mt-3 text-xs text-[#8b99ff]"
            >
              {detailOpen ? '상세 설정 접기' : '상세 설정'}
            </button>

            {detailOpen && (
              <div className="mt-4 space-y-4 border-t border-[#272d36] pt-4">
                <label className={label}>
                  선택지별 화자 Override
                  <input
                    className={`${input} mt-1.5`}
                    value={choice.speakerOverride ?? ''}
                    maxLength={10}
                    placeholder="비워두면 페이지 화자 사용"
                    onChange={(event) =>
                      onChange((draft) => {
                        const target = draft.choices.find((entry) => entry.id === choice.id);
                        if (target) target.speakerOverride = event.target.value || undefined;
                      })
                    }
                  />
                </label>

                <div className="grid grid-cols-2 gap-2">
                  <button
                    type="button"
                    onClick={() =>
                      onChange((draft) => {
                        const target = draft.choices.find((entry) => entry.id === choice.id);
                        if (target) target.endAfterTarget = false;
                      })
                    }
                    className={`rounded-lg border p-3 text-left ${
                      !choice.endAfterTarget ? 'border-[#7c8cff] bg-[#232943]' : 'border-[#2a3039] bg-[#14181e]'
                    }`}
                  >
                    <div className="text-xs font-semibold">CONTINUE</div>
                    <div className="mt-1 text-[10px] text-[#727c8a]">결과 페이지 뒤에도 계속 진행</div>
                  </button>
                  <button
                    type="button"
                    onClick={() =>
                      onChange((draft) => {
                        const target = draft.choices.find((entry) => entry.id === choice.id);
                        if (target) target.endAfterTarget = true;
                      })
                    }
                    className={`rounded-lg border p-3 text-left ${
                      choice.endAfterTarget ? 'border-[#7c8cff] bg-[#232943]' : 'border-[#2a3039] bg-[#14181e]'
                    }`}
                  >
                    <div className="text-xs font-semibold">END</div>
                    <div className="mt-1 text-[10px] text-[#727c8a]">결과 페이지를 보여준 뒤 종료</div>
                  </button>
                </div>

                <div>
                  <div className="mb-2 text-xs font-semibold text-[#aeb5c0]">선택지 표시 조건</div>
                  <ConditionBuilder
                    variables={project.variables}
                    items={project.items}
                    value={choice.server?.condition ?? emptyChoiceSettings().condition}
                    onChange={(mutator) =>
                      onChange((draft) => {
                        const target = draft.choices.find((entry) => entry.id === choice.id) as
                          | (DialoguePage['choices'][number] & { server?: ReturnType<typeof emptyChoiceSettings> })
                          | undefined;
                        if (!target) return;
                        target.server ??= emptyChoiceSettings();
                        mutator(target.server.condition);
                      })
                    }
                  />
                </div>
              </div>
            )}
          </div>
        );
      })}

      {page.choices.length === 0 && (
        <div className="rounded-xl border border-dashed border-[#303743] px-4 py-8 text-center">
          <div className="text-sm text-[#aab1bc]">아직 선택지가 없습니다.</div>
          <div className="mt-1 text-xs text-[#6f7987]">분기가 필요할 때 선택지를 추가하세요.</div>
        </div>
      )}

      <button
        type="button"
        disabled={page.choices.length >= 8}
        onClick={() =>
          onChange((draft) => {
            if (draft.choices.length < 8)
              draft.choices.push({
                id: crypto.randomUUID(),
                label: '',
                server: emptyChoiceSettings(),
              } as DialoguePage['choices'][number]);
          })
        }
        className="w-full rounded-xl border border-dashed border-[#3a4350] px-3 py-3 text-sm text-[#8b99ff] enabled:hover:bg-[#20252d] disabled:opacity-30"
      >
        + 선택지 추가
      </button>
    </div>
  );
}

function EffectsPanel({ page, onChange }: { page: DialoguePage; onChange: Props['onChange'] }) {
  const server = serverOf(page);
  const edit = (mutator: (settings: ServerPageSettings) => void) =>
    onChange((draft) => {
      draft.server ??= emptyServerPage();
      mutator(draft.server);
    });

  return (
    <div className="space-y-4 p-5">
      <div className="rounded-xl border border-[#334056] bg-[#18202c] p-3 text-xs leading-5 text-[#aeb8c8]">
        이 효과는 페이지가 나타날 때가 아니라 플레이어가 대사를 넘기거나 선택지를 확정할 때 한 번 실행됩니다.
      </div>

      <EffectGroup title="🎒 아이템" description="지급/회수 수량은 서버에서 항목당 최대 100개로 제한됩니다.">
        <label className={label}>
          지급
          <textarea
            className={`${input} mt-1.5 min-h-20 resize-y`}
            value={server.effects.giveItems}
            placeholder="minecraft:bread:3, @OWNER/custom_item:1"
            onChange={(event) => edit((settings) => void (settings.effects.giveItems = event.target.value))}
          />
        </label>
        <label className={label}>
          회수
          <textarea
            className={`${input} mt-1.5 min-h-20 resize-y`}
            value={server.effects.takeItems}
            placeholder="minecraft:emerald:2"
            onChange={(event) => edit((settings) => void (settings.effects.takeItems = event.target.value))}
          />
        </label>
      </EffectGroup>

      <EffectGroup title="{x} 변수" description="=, +=, -=, *=, /= 연산을 지원합니다.">
        <label className={label}>
          설정/연산
          <textarea
            className={`${input} mt-1.5 min-h-20 resize-y`}
            value={server.effects.variablesSet}
            placeholder="money-=100, affection+=1, quest_done=true"
            onChange={(event) => edit((settings) => void (settings.effects.variablesSet = event.target.value))}
          />
        </label>
        <label className={label}>
          삭제
          <input
            className={`${input} mt-1.5`}
            value={server.effects.variablesDelete}
            placeholder="temporary_hint, old_flag"
            onChange={(event) => edit((settings) => void (settings.effects.variablesDelete = event.target.value))}
          />
        </label>
        <label className={label}>
          채팅 입력을 저장할 변수
          <input
            className={`${input} mt-1.5`}
            value={server.effects.chatInputVariable}
            placeholder="nickname"
            onChange={(event) => edit((settings) => void (settings.effects.chatInputVariable = event.target.value))}
          />
        </label>
      </EffectGroup>

      <EffectGroup title="🔊 사운드" description="sound id : pitch : volume : repeat(1~10)">
        <textarea
          className={`${input} min-h-20 resize-y`}
          value={server.effects.sounds}
          placeholder="minecraft:entity.villager.yes:1.0:0.8:1"
          onChange={(event) => edit((settings) => void (settings.effects.sounds = event.target.value))}
        />
      </EffectGroup>

      <EffectGroup title="💬 플레이어에게 메시지 보내기" description="{{variable}} placeholder와 HEX 색상을 사용할 수 있습니다.">
        <label className={label}>
          플레이어에게 보낼 메시지
          <input
            className={`${input} mt-1.5`}
            value={server.effects.message}
            placeholder="{{player_name}}님, 완료되었습니다."
            onChange={(event) => edit((settings) => void (settings.effects.message = event.target.value))}
          />
        </label>
        <label className={label}>
          색상
          <div className="mt-1.5 flex gap-2">
            <input
              type="color"
              className="h-10 w-12 rounded-lg border border-[#2a3039] bg-[#171b21] p-1"
              value={/^#[0-9a-f]{6}$/i.test(server.effects.messageColor) ? server.effects.messageColor : '#ffffff'}
              onChange={(event) => edit((settings) => void (settings.effects.messageColor = event.target.value))}
            />
            <input
              className={input}
              value={server.effects.messageColor}
              placeholder="#FFFFFF"
              onChange={(event) => edit((settings) => void (settings.effects.messageColor = event.target.value))}
            />
          </div>
        </label>
      </EffectGroup>

      <EffectGroup title="↩ Return" description="기존 서버의 PAGE / CHOICE 진행 위치로 돌아갑니다.">
        <input
          className={input}
          value={server.effects.returnTarget}
          placeholder="PAGE:p0 / CHOICE:p2#c0"
          onChange={(event) => edit((settings) => void (settings.effects.returnTarget = event.target.value))}
        />
      </EffectGroup>

      <EffectGroup title="⌨ 서버 명령어 · OP 전용" description="{player}, {target} placeholder를 지원합니다.">
        <textarea
          className={`${input} min-h-20 resize-y`}
          value={server.effects.serverCommand}
          placeholder="give {player} minecraft:diamond 1"
          onChange={(event) => edit((settings) => void (settings.effects.serverCommand = event.target.value))}
        />
        <select
          className={input}
          value={server.effects.commandTarget}
          onChange={(event) =>
            edit(
              (settings) =>
                void (settings.effects.commandTarget = event.target.value as ServerPageSettings['effects']['commandTarget']),
            )
          }
        >
          <option value="player">PLAYER · 대화 플레이어</option>
          <option value="all">ALL · 모든 플레이어</option>
          <option value="nearest">NEAREST · 가장 가까운 플레이어</option>
        </select>
      </EffectGroup>
    </div>
  );
}

function EffectGroup({
  title,
  description,
  children,
}: {
  title: string;
  description: string;
  children: React.ReactNode;
}) {
  return (
    <details open className="rounded-xl border border-[#292f39] bg-[#171b21] p-4">
      <summary className="cursor-pointer list-none">
        <div className="text-sm font-semibold">{title}</div>
        <div className="mt-1 text-[11px] text-[#737d8b]">{description}</div>
      </summary>
      <div className="mt-4 space-y-3">{children}</div>
    </details>
  );
}

function FlowPanel({
  page,
  dialogue,
  project,
  onChange,
}: {
  page: DialoguePage;
  dialogue: Dialogue;
  project: RPGProject;
  onChange: Props['onChange'];
}) {
  const server = serverOf(page);
  const edit = (mutator: (settings: ServerPageSettings) => void) =>
    onChange((draft) => {
      draft.server ??= emptyServerPage();
      mutator(draft.server);
      draft.operationOnly = draft.server.operationOnly;
      draft.flow.ending = draft.server.flow.ending;
    });

  return (
    <div className="space-y-5 p-5">
      <section className="rounded-xl bg-[#171b21] p-4">
        <div className="text-sm font-semibold">기본 다음 페이지</div>
        <p className="mt-1 text-xs leading-5 text-[#737d8b]">
          자동을 선택하면 기존 서버의 next page 0 규칙대로 현재 페이지 + 1로 이동합니다.
        </p>
        <select
          className={`${input} mt-3`}
          value={server.flow.nextPageId ?? ''}
          onChange={(event) => edit((settings) => void (settings.flow.nextPageId = event.target.value || undefined))}
        >
          <option value="">자동 · 현재 페이지 + 1</option>
          {dialogue.pages.map((target, index) => (
            <option key={target.id} value={target.id}>
              Page {index + 1} · {target.editorLabel || target.lines.find(Boolean) || '빈 페이지'}
            </option>
          ))}
        </select>
      </section>

      <label className="flex items-center justify-between rounded-xl bg-[#171b21] px-4 py-3">
        <div>
          <div className="text-sm font-semibold">종결 페이지</div>
          <div className="mt-1 text-xs text-[#737d8b]">이 페이지 이후 대화를 종료합니다.</div>
        </div>
        <input
          type="checkbox"
          checked={server.flow.ending}
          onChange={(event) => edit((settings) => void (settings.flow.ending = event.target.checked))}
        />
      </label>

      <label className="flex items-center justify-between rounded-xl bg-[#171b21] px-4 py-3">
        <div>
          <div className="text-sm font-semibold">연산 전용 페이지</div>
          <div className="mt-1 text-xs text-[#737d8b]">대사를 표시하지 않고 효과/흐름만 처리합니다.</div>
        </div>
        <input
          type="checkbox"
          checked={server.operationOnly}
          onChange={(event) => edit((settings) => void (settings.operationOnly = event.target.checked))}
        />
      </label>

      <section className="space-y-4 rounded-xl border border-[#292f39] bg-[#171b21] p-4">
        <div>
          <div className="text-sm font-semibold">조건부 Jump</div>
          <div className="mt-1 text-xs text-[#737d8b]">조건이 참일 때 지정 페이지로 이동합니다.</div>
        </div>
        <label className={label}>
          대상 페이지
          <select
            className={`${input} mt-1.5`}
            value={server.flow.conditionalTargetPageId ?? ''}
            onChange={(event) =>
              edit((settings) => void (settings.flow.conditionalTargetPageId = event.target.value || undefined))
            }
          >
            <option value="">사용 안 함</option>
            {dialogue.pages.map((target, index) => (
              <option key={target.id} value={target.id}>
                Page {index + 1} · {target.editorLabel || target.lines.find(Boolean) || '빈 페이지'}
              </option>
            ))}
          </select>
        </label>
        <div>
          <div className={label}>조건 확인 시점</div>
          <div className="mt-2 grid grid-cols-2 gap-2">
            <button
              type="button"
              onClick={() => edit((settings) => void (settings.flow.conditionalTiming = 'before'))}
              className={`rounded-xl border p-3 text-left ${
                server.flow.conditionalTiming === 'before'
                  ? 'border-[#7c8cff] bg-[#232943]'
                  : 'border-[#2a3039] bg-[#14181e]'
              }`}
            >
              <div className="text-xs font-semibold">페이지 실행 전</div>
              <div className="mt-1 text-[10px] text-[#737d8b]">조건이 참이면 현재 페이지를 건너뜁니다.</div>
            </button>
            <button
              type="button"
              onClick={() => edit((settings) => void (settings.flow.conditionalTiming = 'after'))}
              className={`rounded-xl border p-3 text-left ${
                server.flow.conditionalTiming === 'after'
                  ? 'border-[#7c8cff] bg-[#232943]'
                  : 'border-[#2a3039] bg-[#14181e]'
              }`}
            >
              <div className="text-xs font-semibold">페이지 실행 후</div>
              <div className="mt-1 text-[10px] text-[#737d8b]">현재 대사/효과 후 이동합니다.</div>
            </button>
          </div>
        </div>

        {server.flow.conditionalTargetPageId && (
          <ConditionBuilder
            value={server.flow.condition}
            variables={project.variables}
            items={project.items}
            onChange={(mutator) => edit((settings) => mutator(settings.flow.condition))}
          />
        )}
      </section>
    </div>
  );
}

export function EditorInspector({ section, page, dialogue, project, manifest, onClose, onChange }: Props) {
  return (
    <aside className="flex w-[410px] shrink-0 flex-col border-l border-[#242a33] bg-[#12161b]">
      <SectionHeader section={section} onClose={onClose} />
      <div className="min-h-0 flex-1 overflow-y-auto">
        {section === 'character' && <CharacterPanel page={page} manifest={manifest} onChange={onChange} />}
        {section === 'choices' && (
          <ChoicesPanel page={page} project={project} onChange={onChange} />
        )}
        {section === 'condition' && (
          <div className="p-5">
            <ConditionBuilder
              showReplacement
              variables={project.variables}
              items={project.items}
              value={serverOf(page).displayCondition}
              onChange={(mutator) =>
                onChange((draft) => {
                  draft.server ??= emptyServerPage();
                  mutator(draft.server.displayCondition);
                })
              }
            />
          </div>
        )}
        {section === 'effects' && <EffectsPanel page={page} onChange={onChange} />}
        {section === 'flow' && (
          <FlowPanel page={page} dialogue={dialogue} project={project} onChange={onChange} />
        )}
        {section === 'other' && (
          <div className="space-y-5 p-5">
            <label className={label}>
              페이지 별칭
              <input
                className={`${input} mt-1.5`}
                value={page.editorLabel ?? ''}
                placeholder="예: 물건 제안"
                onChange={(event) => onChange((draft) => void (draft.editorLabel = event.target.value))}
              />
            </label>
            <div className="rounded-xl bg-[#171b21] p-4">
              <div className="text-sm font-semibold">현재 제한</div>
              <div className="mt-3 space-y-2 text-xs text-[#7f8997]">
                <div>대사 줄: {page.lines.length} / 4</div>
                <div>
                  가장 긴 줄: {Math.max(...page.lines.map(visibleLength))} / 30 표시 문자
                </div>
                <div>선택지: {page.choices.length} / 8</div>
              </div>
            </div>
          </div>
        )}
      </div>
    </aside>
  );
}
