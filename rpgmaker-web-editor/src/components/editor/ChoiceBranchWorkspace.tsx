import type { Dialogue, DialogueChoice, DialogueChoiceResponsePage, DialoguePage } from '../../domain/project';
import { emptyChoiceSettings, emptyServerPage } from '../../domain/serverSettings';
import {
  availableExpressions,
  getCharacter,
  normalizedGender,
  type CharacterManifest,
  type ManifestExpression,
} from '../../services/characterRegistry';
import { visibleLength } from '../../services/projectValidator';
import { useProjectStore } from '../../store/projectStore';
import { ConditionBuilder } from '../conditions/ConditionBuilder';
import { playableDialogueName } from './DialogueMovementWorkspace';
import { ReturnPicker } from './EffectsInspector';

interface Props {
  page: DialoguePage;
  manifest: CharacterManifest;
  onChange: (mutator: (page: DialoguePage) => void) => void;
}

const input = 'w-full rounded-lg border border-[#302b38] bg-[#15131a] px-3 py-2 text-sm text-[#eef1f5] outline-none focus:border-[#9d8cff]';

function newResponsePage(): DialogueChoiceResponsePage {
  return {
    id: crypto.randomUUID(),
    lines: ['', '', '', ''],
    appearance: { visible: true, speakerVisible: true, inheritPrevious: false, expression: 'NEUTRAL' },
    choices: [],
    server: emptyServerPage(),
  };
}

function newChoice(label = ''): DialogueChoice {
  return { id: crypto.randomUUID(), label, responsePages: [], server: emptyChoiceSettings() };
}

export function visitChoice(choices: DialogueChoice[], id: string): DialogueChoice | undefined {
  for (const choice of choices) {
    if (choice.id === id) return choice;
    for (const response of choice.responsePages ?? []) {
      const found = visitChoice(response.choices, id);
      if (found) return found;
    }
  }
  return undefined;
}

function visitResponse(choices: DialogueChoice[], id: string): DialogueChoiceResponsePage | undefined {
  for (const choice of choices) {
    for (const response of choice.responsePages ?? []) {
      if (response.id === id) return response;
      const found = visitResponse(response.choices, id);
      if (found) return found;
    }
  }
  return undefined;
}

export function ChoiceBranchWorkspace({ page, manifest, onChange }: Props) {
  return (
    <section className="mt-8 rounded-2xl border border-[#433650] bg-[#131018] p-5">
      <div className="flex items-start justify-between gap-4">
        <div>
          <div className="text-xs font-semibold uppercase tracking-[0.14em] text-[#b09cff]">선택지 상세 편집</div>
          <p className="mt-2 text-xs leading-5 text-[#776f80]">
            선택지 문구 → 후속 대사 → 후속 대사 안의 선택지를 한 구조에서 편집합니다. 각 분기가 끝난 뒤 이어질 다른 대화도 지정할 수 있습니다.
          </p>
        </div>
        <span className="rounded-full bg-[#2b2234] px-2 py-1 text-[10px] text-[#c0aaff]">{page.choices.length}/8</span>
      </div>

      <div className="mt-5 space-y-4">
        {page.choices.map((choice, index) => (
          <ChoiceEditor
            key={choice.id}
            choice={choice}
            index={index}
            depth={0}
            manifest={manifest}
            onChange={onChange}
            onDelete={() => onChange((draft) => void (draft.choices = draft.choices.filter((item) => item.id !== choice.id)))}
          />
        ))}
      </div>

      <button type="button" disabled={page.choices.length >= 8} onClick={() => onChange((draft) => void draft.choices.push(newChoice(`선택지 ${draft.choices.length + 1}`)))} className="mt-4 w-full rounded-xl border border-dashed border-[#51415f] px-3 py-3 text-sm text-[#b09cff] enabled:hover:bg-[#211a27] disabled:opacity-30">
        + 선택지 추가
      </button>
    </section>
  );
}

function ChoiceEditor({ choice, index, depth, manifest, onChange, onDelete }: {
  choice: DialogueChoice;
  index: number;
  depth: number;
  manifest: CharacterManifest;
  onChange: Props['onChange'];
  onDelete: () => void;
}) {
  const projects = useProjectStore((state) => state.projects);
  const project = projects.find((candidate) =>
    candidate.dialogues.some((dialogue) =>
      dialogue.pages.some((page) => visitChoice(page.choices, choice.id)),
    ),
  );
  const dialogues = project?.dialogues ?? [];
  const dialogue = dialogues.find((candidate) =>
    candidate.pages.some((page) => visitChoice(page.choices, choice.id)),
  );
  const mutateChoice = (mutator: (draft: DialogueChoice) => void) =>
    onChange((draftPage) => {
      const target = visitChoice(draftPage.choices, choice.id);
      if (target) mutator(target);
    });

  return (
    <details open={depth === 0} className="rounded-xl border border-[#352d3d] bg-[#17131c] p-4">
      <summary className="cursor-pointer list-none">
        <div className="flex items-center gap-3">
          <span className="grid h-7 w-7 place-items-center rounded-lg bg-[#30243a] text-xs font-semibold text-[#c0aaff]">{index + 1}</span>
          <span className="min-w-0 flex-1 truncate text-sm font-semibold">{choice.label || '이름 없는 선택지'}</span>
          <span className="text-[10px] text-[#72677b]">후속 {choice.responsePages?.length ?? 0}p</span>
        </div>
      </summary>

      <div className="mt-4 space-y-4 border-t border-[#2d2634] pt-4">
        <label className="block text-xs text-[#9990a1]">플레이어에게 보이는 선택지
          <input className={`${input} mt-1.5`} value={choice.label} maxLength={10} onChange={(event) => mutateChoice((draft) => void (draft.label = event.target.value))} />
        </label>
        <label className="block text-xs text-[#9990a1]">후속 대사 화자
          <input className={`${input} mt-1.5`} value={choice.speakerOverride ?? ''} placeholder="비워두면 원래 페이지 화자를 사용" onChange={(event) => mutateChoice((draft) => void (draft.speakerOverride = event.target.value || undefined))} />
        </label>

        <div className="grid grid-cols-[1fr_auto] gap-3">
          <label className="block text-xs text-[#c0aaff]">
            선택 즉시 이동할 페이지
            <select
              className={`${input} mt-1.5`}
              value={choice.targetPageId ?? ''}
              onChange={(event) => mutateChoice((draft) => void (draft.targetPageId = event.target.value || undefined))}
            >
              <option value="">페이지 이동 안 함 · 후속 대사 사용</option>
              {dialogue?.pages.map((target, pageIndex) => (
                <option key={target.id} value={target.id}>
                  Page {pageIndex + 1} · {target.editorLabel || target.lines.find(Boolean) || '빈 페이지'}
                </option>
              ))}
            </select>
          </label>
          <label className="flex items-end">
            <span className="flex h-10 items-center gap-2 rounded-lg bg-[#120f16] px-3 text-xs text-[#a99db1]">
              도착 후 종료
              <input
                type="checkbox"
                checked={choice.endAfterTarget ?? false}
                onChange={(event) => mutateChoice((draft) => void (draft.endAfterTarget = event.target.checked))}
              />
            </span>
          </label>
        </div>

        <label className="block text-xs text-[#c0aaff]">
          이 선택지가 끝난 뒤 대화 이동
          <select
            className={`${input} mt-1.5`}
            value={choice.targetDialogueName ?? ''}
            onChange={(event) => mutateChoice((draft) => void (draft.targetDialogueName = event.target.value || undefined))}
          >
            <option value="">이동 안 함 · 현재 대화 흐름 사용</option>
            {dialogues.filter((candidate) => candidate.id !== dialogue?.id).map((candidate) => (
              <option key={candidate.id} value={candidate.server?.remoteName || playableDialogueName(candidate.name)}>{candidate.name}</option>
            ))}
          </select>
          <span className="mt-1.5 block text-[10px] leading-4 text-[#766b7e]">후속 대사와 중첩 선택지가 모두 끝난 다음 지정한 대화를 시작합니다.</span>
        </label>

        <details className="rounded-xl border border-[#30283a] bg-[#120f16] p-3">
          <summary className="cursor-pointer text-xs font-semibold text-[#d4c8dc]">선택지 표시 조건</summary>
          <div className="mt-3">
            <ConditionBuilder
              variables={project?.variables ?? []}
              items={project?.items ?? []}
              value={choice.server?.condition ?? emptyChoiceSettings().condition}
              onChange={(mutator) =>
                mutateChoice((draft) => {
                  draft.server ??= emptyChoiceSettings();
                  mutator(draft.server.condition);
                })
              }
            />
          </div>
        </details>

        <div className="rounded-xl border border-[#30283a] bg-[#120f16] p-3">
          <div className="flex items-center justify-between">
            <div><div className="text-xs font-semibold text-[#d4c8dc]">후속 대사</div><div className="mt-1 text-[10px] text-[#73697b]">선택 직후 순서대로 표시되는 대사 페이지입니다.</div></div>
            <button type="button" onClick={() => mutateChoice((draft) => void ((draft.responsePages ??= []).push(newResponsePage())))} className="rounded-lg bg-[#2c2234] px-2.5 py-1.5 text-[11px] text-[#c0aaff] hover:bg-[#382a43]">+ 후속 대사</button>
          </div>
          <div className="mt-3 space-y-3">
            {(choice.responsePages ?? []).map((response, responseIndex) => (
              <ResponsePageEditor key={response.id} response={response} index={responseIndex} depth={depth} dialogue={dialogue} manifest={manifest} onChange={onChange} onDelete={() => mutateChoice((draft) => void (draft.responsePages = (draft.responsePages ?? []).filter((item) => item.id !== response.id)))} />
            ))}
            {!choice.responsePages?.length && <div className="rounded-lg border border-dashed border-[#30283a] px-3 py-5 text-center text-[11px] text-[#6e6476]">후속 대사가 없으면 선택 직후 대화 이동 규칙을 적용합니다.</div>}
          </div>
        </div>
        <div className="flex justify-end"><button type="button" onClick={onDelete} className="rounded-lg px-3 py-2 text-xs text-[#86798e] hover:bg-red-400/10 hover:text-red-300">선택지 삭제</button></div>
      </div>
    </details>
  );
}

function ResponsePageEditor({ response, index, depth, dialogue, manifest, onChange, onDelete }: {
  response: DialogueChoiceResponsePage;
  index: number;
  depth: number;
  dialogue?: Dialogue;
  manifest: CharacterManifest;
  onChange: Props['onChange'];
  onDelete: () => void;
}) {
  const character = getCharacter(manifest, response.appearance.characterId);
  const gender = character ? normalizedGender(character, response.appearance.gender) : 'NONE';
  const expressions = character ? availableExpressions(character, gender) : (['NEUTRAL'] as ManifestExpression[]);
  const mutate = (mutator: (draft: DialogueChoiceResponsePage) => void) =>
    onChange((draftPage) => {
      const target = visitResponse(draftPage.choices, response.id);
      if (target) {
        target.server ??= emptyServerPage();
        mutator(target);
      }
    });

  return (
    <details open className="rounded-xl border border-[#2e2933] bg-[#18161b] p-3">
      <summary className="cursor-pointer list-none text-xs font-semibold text-[#aaa1b1]">후속 PAGE {index + 1}</summary>
      <div className="mt-3 space-y-4 border-t border-[#2b2730] pt-3">
        <div className="grid grid-cols-2 gap-2">
          <label className="text-[11px] text-[#8e8595]">캐릭터
            <select className={`${input} mt-1`} value={response.appearance.characterId ?? ''} onChange={(event) => mutate((draft) => {
              draft.appearance.characterId = event.target.value || undefined;
              const selected = getCharacter(manifest, draft.appearance.characterId);
              if (selected) {
                const selectedGender = normalizedGender(selected, draft.appearance.gender);
                draft.appearance.gender = selectedGender === 'FEMALE' ? 'female' : selectedGender === 'MALE' ? 'male' : undefined;
                draft.appearance.expression = availableExpressions(selected, selectedGender)[0] ?? 'NEUTRAL';
                draft.appearance.visible = true;
              }
            })}>
              <option value="">캐릭터 없음</option>
              {manifest.characters.map((entry) => <option key={entry.id} value={entry.id}>{entry.label}</option>)}
            </select>
          </label>
          <label className="text-[11px] text-[#8e8595]">표정
            <select className={`${input} mt-1`} value={response.appearance.expression ?? expressions[0] ?? 'NEUTRAL'} disabled={!character} onChange={(event) => mutate((draft) => void (draft.appearance.expression = event.target.value))}>
              {expressions.map((entry) => <option key={entry} value={entry}>{manifest.expressionLabels[entry]}</option>)}
            </select>
          </label>
        </div>
        <label className="flex items-center justify-between rounded-lg bg-[#121015] px-3 py-2 text-xs text-[#948b9b]">캐릭터 표시
          <input type="checkbox" checked={response.appearance.visible} onChange={(event) => mutate((draft) => void (draft.appearance.visible = event.target.checked))} />
        </label>
        <label className="flex items-center justify-between rounded-lg bg-[#121015] px-3 py-2 text-xs text-[#948b9b]">화자 이름 표시
          <input type="checkbox" checked={response.appearance.speakerVisible} onChange={(event) => mutate((draft) => void (draft.appearance.speakerVisible = event.target.checked))} />
        </label>

        <div>
          <div className="mb-2 flex items-center justify-between text-[11px] text-[#8e8595]"><span>대사</span><span>최대 4줄 · 30자</span></div>
          <div className="space-y-2">
            {response.lines.map((line, lineIndex) => {
              const length = visibleLength(line);
              return (
                <div key={lineIndex} className={`grid grid-cols-[24px_1fr_48px] items-center gap-2 rounded-lg border px-2 ${length > 30 ? 'border-red-400/40 bg-red-400/5' : 'border-[#302b35] bg-[#121015]'}`}>
                  <span className="text-center text-[10px] text-[#716879]">{lineIndex + 1}</span>
                  <input value={line} onChange={(event) => mutate((draft) => void (draft.lines[lineIndex] = event.target.value))} placeholder={lineIndex === 0 ? '후속 대사를 입력하세요.' : '빈 줄'} className="min-w-0 bg-transparent py-3 text-sm outline-none" />
                  <span className={`text-right text-[10px] ${length > 30 ? 'text-red-300' : 'text-[#655d6c]'}`}>{length}/30</span>
                </div>
              );
            })}
          </div>
        </div>

        <details className="rounded-lg border border-[#302b35] bg-[#121015] p-3">
          <summary className="cursor-pointer text-xs font-semibold text-[#a99db1]">후속 대사 효과</summary>
          <div className="mt-3 space-y-2">
            <input className={input} value={response.server?.effects.variablesSet ?? ''} placeholder="변수 설정: score+=1, roll=random(1..10)" onChange={(event) => mutate((draft) => void (draft.server!.effects.variablesSet = event.target.value))} />
            <input className={input} value={response.server?.effects.variablesDelete ?? ''} placeholder="변수 삭제" onChange={(event) => mutate((draft) => void (draft.server!.effects.variablesDelete = event.target.value))} />
            <input className={input} value={response.server?.effects.giveItems ?? ''} placeholder="아이템 지급: minecraft:bread:1[:표시이름:#색상]" onChange={(event) => mutate((draft) => void (draft.server!.effects.giveItems = event.target.value))} />
            <input className={input} value={response.server?.effects.takeItems ?? ''} placeholder="아이템 회수" onChange={(event) => mutate((draft) => void (draft.server!.effects.takeItems = event.target.value))} />
            <input className={input} value={response.server?.effects.sounds ?? ''} placeholder="사운드" onChange={(event) => mutate((draft) => void (draft.server!.effects.sounds = event.target.value))} />
            <div className="grid grid-cols-[1fr_100px] gap-2">
              <input className={input} value={response.server?.effects.message ?? ''} placeholder="플레이어에게 메시지 보내기" onChange={(event) => mutate((draft) => void (draft.server!.effects.message = event.target.value))} />
              <input className={input} value={response.server?.effects.messageColor ?? '#FFFFFF'} placeholder="#FFFFFF" onChange={(event) => mutate((draft) => void (draft.server!.effects.messageColor = event.target.value))} />
            </div>
            <input className={input} value={response.server?.effects.chatInputVariable ?? ''} placeholder="채팅 입력 변수" onChange={(event) => mutate((draft) => void (draft.server!.effects.chatInputVariable = event.target.value))} />
            {dialogue && (
              <div>
                <div className="mb-1 text-[10px] text-[#8e8595]">Return 대상</div>
                <ReturnPicker dialogue={dialogue} value={response.server?.effects.returnTarget ?? ''} onChange={(value) => mutate((draft) => void (draft.server!.effects.returnTarget = value))} />
              </div>
            )}
            <textarea className={`${input} min-h-16 resize-y`} value={response.server?.effects.serverCommand ?? ''} placeholder="서버 명령어 · OP 전용" onChange={(event) => mutate((draft) => void (draft.server!.effects.serverCommand = event.target.value))} />
            <select className={input} value={response.server?.effects.commandTarget ?? 'player'} onChange={(event) => mutate((draft) => void (draft.server!.effects.commandTarget = event.target.value as 'player' | 'all' | 'nearest'))}>
              <option value="player">대화 플레이어</option>
              <option value="all">모든 플레이어 (@a)</option>
              <option value="nearest">가장 가까운 플레이어 (@p)</option>
            </select>
          </div>
        </details>

        <div className="rounded-lg border border-[#392f43] bg-[#15111a] p-3">
          <div className="flex items-center justify-between">
            <div><div className="text-xs font-semibold text-[#beaacf]">이 후속 대사의 선택지</div><div className="mt-1 text-[10px] text-[#766b7e]">선택지 안에 다시 선택지를 만들 수 있습니다.</div></div>
            <button type="button" disabled={response.choices.length >= 8 || depth >= 15} onClick={() => mutate((draft) => void draft.choices.push(newChoice(`선택지 ${draft.choices.length + 1}`)))} className="rounded-lg bg-[#2d2335] px-2 py-1.5 text-[10px] text-[#c0aaff] disabled:opacity-30">+ 선택지</button>
          </div>
          <div className="mt-3 space-y-2">
            {response.choices.map((nested, nestedIndex) => (
              <ChoiceEditor key={nested.id} choice={nested} index={nestedIndex} depth={depth + 1} manifest={manifest} onChange={onChange} onDelete={() => mutate((draft) => void (draft.choices = draft.choices.filter((item) => item.id !== nested.id)))} />
            ))}
          </div>
        </div>
        <div className="flex justify-end"><button type="button" onClick={onDelete} className="text-[11px] text-[#7d7285] hover:text-red-300">후속 페이지 삭제</button></div>
      </div>
    </details>
  );
}
