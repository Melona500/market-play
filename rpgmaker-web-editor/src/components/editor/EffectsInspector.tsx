import { useState, type ReactNode } from 'react';
import { BUILT_IN_VARIABLES } from '../../domain/builtInVariables';
import type { Dialogue, DialoguePage, RPGProject } from '../../domain/project';
import { emptyServerPage } from '../../domain/serverSettings';
import type { ServerPageSettings } from '../../domain/serverSettings';
import { dialogueReturnOptions } from '../../services/returnTargets';

interface Props {
  page: DialoguePage;
  dialogue: Dialogue;
  project: RPGProject;
  onClose: () => void;
  onChange: (mutator: (page: DialoguePage) => void) => void;
}

type VariableAssignment = {
  name: string;
  operator: '=' | '+=' | '-=' | '*=' | '/=' | 'random';
  value: string;
};

const input =
  'w-full rounded-lg border border-[#3a3147] bg-[#181420] px-3 py-2 text-sm text-[#f4eef8] outline-none transition focus:border-[#9d8cff]';
const label = 'text-xs font-medium text-[#aa9eb5]';

function parseAssignments(value: string): VariableAssignment[] {
  return value
    .split(',')
    .map((entry) => entry.trim())
    .filter(Boolean)
    .map((entry) => {
      const random = entry.match(/^(.+?)\s*=\s*random\(\s*(-?\d+)\s*\.\.\s*(-?\d+)\s*\)$/iu);
      if (random) {
        return { name: random[1], operator: 'random', value: `${random[2]}..${random[3]}` };
      }
      const match = entry.match(/^(.+?)\s*(\+=|-=|\*=|\/=|=)\s*(.*)$/u);
      return match
        ? {
            name: match[1],
            operator: match[2] as VariableAssignment['operator'],
            value: match[3],
          }
        : { name: entry, operator: '=', value: '' };
    });
}

function serializeAssignments(rows: VariableAssignment[]): string {
  return rows
    .filter((row) => row.name.trim())
    .map((row) =>
      row.operator === 'random'
        ? `${row.name}=random(${row.value || '0..100'})`
        : `${row.name}${row.operator}${row.value}`,
    )
    .join(', ');
}

function describeOperator(operator: VariableAssignment['operator']) {
  if (operator === '=') return '값을 설정';
  if (operator === '+=') return '현재 값에 더함';
  if (operator === '-=') return '현재 값에서 뺌';
  if (operator === '*=') return '현재 값에 곱함';
  if (operator === '/=') return '현재 값을 나눔';
  return '지정한 최소~최대 범위에서 정수 난수를 생성해 설정';
}

function appendItem(value: string, reference: string) {
  return [...value.split(/[,\n]/).map((entry) => entry.trim()).filter(Boolean), `${reference}:1`].join(', ');
}

function selectedReturnLabel(dialogue: Dialogue, target: string) {
  return dialogueReturnOptions(dialogue).find((option) => option.value === target)?.label
    ?? (target || 'Return 사용 안 함');
}

export function ReturnPicker({
  dialogue,
  value,
  onChange,
}: {
  dialogue: Dialogue;
  value: string;
  onChange: (value: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const options = dialogueReturnOptions(dialogue);

  return (
    <div className="relative">
      <button
        type="button"
        onClick={() => setOpen((current) => !current)}
        className="flex w-full items-center justify-between rounded-xl border border-[#4a3d59] bg-[#15111c] px-3 py-3 text-left text-sm hover:border-[#7f6c96]"
      >
        <span className="min-w-0 truncate">{selectedReturnLabel(dialogue, value)}</span>
        <span className="ml-3 text-[10px] text-[#a99ab6]">{open ? '▲' : '▼'}</span>
      </button>

      {open && (
        <div className="absolute left-0 right-0 z-40 mt-2 max-h-80 overflow-y-auto rounded-xl border border-[#4a3d59] bg-[#100d15] p-2 shadow-2xl">
          <button
            type="button"
            onClick={() => {
              onChange('');
              setOpen(false);
            }}
            className="w-full rounded-lg px-3 py-2 text-left text-xs text-[#b9adbf] hover:bg-[#251e2d]"
          >
            Return 사용 안 함
          </button>

          {options.map((option) => (
            <button
              key={option.value}
              type="button"
              onClick={() => {
                onChange(option.value);
                setOpen(false);
              }}
              className={`mt-1 w-full rounded-lg py-2 pr-3 text-left text-xs hover:bg-[#251e2d] ${option.kind === 'page' ? 'font-semibold' : 'text-[#c8bdce]'}`}
              style={{ paddingLeft: `${12 + Math.min(option.depth, 16) * 12}px` }}
            >
              {option.kind === 'page' ? '↩' : '└'} {option.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function EffectGroup({
  title,
  description,
  accent = 'violet',
  children,
}: {
  title: string;
  description: string;
  accent?: 'violet' | 'gold' | 'green' | 'red';
  children: ReactNode;
}) {
  const accentClass = {
    violet: 'border-[#4b3c5a] bg-[#1a1421]',
    gold: 'border-[#554629] bg-[#211b11]',
    green: 'border-[#315344] bg-[#102019]',
    red: 'border-[#5a3438] bg-[#231417]',
  }[accent];
  return (
    <details open className={`rounded-xl border p-4 ${accentClass}`} data-rpg-section="effect">
      <summary className="cursor-pointer list-none">
        <div className="text-sm font-semibold">{title}</div>
        <div className="mt-1 text-[11px] text-[#9b8fa4]">{description}</div>
      </summary>
      <div className="mt-4 space-y-3">{children}</div>
    </details>
  );
}

export function EffectsInspector({ page, dialogue, project, onClose, onChange }: Props) {
  const server = page.server ?? emptyServerPage();
  const [selectedItem, setSelectedItem] = useState('');
  const variableRows = parseAssignments(server.effects.variablesSet);
  const edit = (mutator: (settings: ServerPageSettings) => void) =>
    onChange((draft) => {
      draft.server ??= emptyServerPage();
      mutator(draft.server);
    });

  const updateAssignment = (index: number, patch: Partial<VariableAssignment>) => {
    const rows = variableRows.map((row, rowIndex) => (rowIndex === index ? { ...row, ...patch } : row));
    edit((settings) => {
      settings.effects.variablesSet = serializeAssignments(rows);
    });
  };

  return (
    <aside className="flex w-[430px] shrink-0 flex-col border-l border-[#392d45] bg-[#100d15]">
      <header className="flex items-start gap-3 border-b border-[#392d45] bg-[#17111d] px-5 py-4">
        <div>
          <h2 className="text-base font-semibold text-[#f6eff9]">효과</h2>
          <p className="mt-1 text-xs leading-5 text-[#9c90a5]">
            아이템, 변수, 난수, 사운드, 메시지, Return, 서버 명령을 기능별로 설정합니다.
          </p>
        </div>
        <button
          type="button"
          onClick={onClose}
          className="ml-auto rounded-lg px-2 py-1 text-[#9a8da3] hover:bg-[#251e2d] hover:text-white"
        >
          ✕
        </button>
      </header>

      <div className="min-h-0 flex-1 space-y-4 overflow-y-auto p-5">
        <div className="rounded-xl border border-[#4d3b60] bg-[#20182a] p-3 text-xs leading-5 text-[#c8bcd0]">
          효과는 플레이어가 대사를 넘기거나 선택지를 확정할 때 한 번 실행됩니다. 변수명 규칙은 조건 편집기와 동일합니다.
        </div>

        <EffectGroup title="🎒 아이템" description="지급/회수 수량은 서버에서 항목당 최대 100개로 제한됩니다." accent="gold">
          {project.items.length > 0 ? (
            <div className="rounded-xl border border-[#55472b] bg-[#1b170f] p-3">
              <div className="text-xs font-semibold text-[#ead397]">서버 저장 아이템 불러오기</div>
              <select className={`${input} mt-2`} value={selectedItem} onChange={(event) => setSelectedItem(event.target.value)}>
                <option value="">아이템 선택</option>
                {project.items.map((item) => <option key={item.id} value={item.minecraftId}>{item.displayName}</option>)}
              </select>
              <div className="mt-2 grid grid-cols-2 gap-2">
                <button type="button" disabled={!selectedItem} onClick={() => edit((settings) => void (settings.effects.giveItems = appendItem(settings.effects.giveItems, selectedItem)))} className="rounded-lg bg-[#3b321d] px-3 py-2 text-xs text-[#f0d89a] disabled:opacity-30">지급에 추가</button>
                <button type="button" disabled={!selectedItem} onClick={() => edit((settings) => void (settings.effects.takeItems = appendItem(settings.effects.takeItems, selectedItem)))} className="rounded-lg bg-[#3b321d] px-3 py-2 text-xs text-[#f0d89a] disabled:opacity-30">회수에 추가</button>
              </div>
            </div>
          ) : (
            <div className="rounded-lg bg-[#1b170f] px-3 py-2 text-[11px] text-[#a99568]">상단의 서버에서 불러오기를 누르면 게임에서 저장한 아이템이 표시됩니다.</div>
          )}
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

        <EffectGroup title="{x} 변수 · 난수" description="조건과 동일한 변수명을 사용하며 설정/사칙연산/정수 난수 생성을 지원합니다.">
          {variableRows.length === 0 && (
            <div className="rounded-xl border border-dashed border-[#4a3d59] px-3 py-5 text-center text-xs text-[#9b8fa4]">
              아직 변수 연산이 없습니다.
            </div>
          )}
          <div className="space-y-2">
            {variableRows.map((row, index) => (
              <div key={`${index}-${row.name}`} className="rounded-xl border border-[#493b58] bg-[#120f18] p-3">
                <div className="grid grid-cols-[1.15fr_92px_1fr_auto] gap-2">
                  <input
                    className={input}
                    list="rpgmaker-effect-variable-names"
                    value={row.name}
                    placeholder="quest.progress"
                    onChange={(event) => updateAssignment(index, { name: event.target.value })}
                  />
                  <select
                    className={input}
                    value={row.operator}
                    onChange={(event) => {
                      const operator = event.target.value as VariableAssignment['operator'];
                      updateAssignment(index, {
                        operator,
                        value: operator === 'random' && !row.value.includes('..') ? '1..100' : row.value,
                      });
                    }}
                  >
                    <option value="=">설정 =</option>
                    <option value="+=">더하기 +=</option>
                    <option value="-=">빼기 -=</option>
                    <option value="*=">곱하기 *=</option>
                    <option value="/=">나누기 /=</option>
                    <option value="random">난수</option>
                  </select>
                  <input
                    className={input}
                    value={row.value}
                    placeholder={row.operator === 'random' ? '최소..최대 (예: 1..100)' : '값'}
                    onChange={(event) => updateAssignment(index, { value: event.target.value })}
                  />
                  <button
                    type="button"
                    onClick={() =>
                      edit((settings) => {
                        settings.effects.variablesSet = serializeAssignments(
                          variableRows.filter((_, rowIndex) => rowIndex !== index),
                        );
                      })
                    }
                    className="rounded-lg px-2 text-xs text-[#9a8da3] hover:bg-red-400/10 hover:text-red-300"
                  >
                    삭제
                  </button>
                </div>
                <div className="mt-2 text-[10px] text-[#a294ab]">
                  {row.operator === 'random'
                    ? `${row.name || '변수'} = random(${row.value || '1..100'})`
                    : `${row.name || '변수'} ${row.operator} ${row.value || '값'}`}
                  {' · '}{describeOperator(row.operator)}
                </div>
              </div>
            ))}
          </div>

          <datalist id="rpgmaker-effect-variable-names">
            {BUILT_IN_VARIABLES.map((name) => (
              <option key={`builtin-${name}`} value={name} />
            ))}
            {project.variables.map((variable) => (
              <option key={variable.id} value={variable.name} />
            ))}
          </datalist>

          <button
            type="button"
            onClick={() =>
              edit((settings) => {
                settings.effects.variablesSet = serializeAssignments([
                  ...variableRows,
                  { name: 'variable', operator: '=', value: '0' },
                ]);
              })
            }
            className="w-full rounded-xl border border-dashed border-[#5b486e] px-3 py-3 text-sm text-[#b7a7ff] hover:bg-[#251e2d]"
          >
            + 변수 연산 추가
          </button>

          <div className="rounded-xl border border-[#54482e] bg-[#201b12] p-3 text-[11px] leading-5 text-[#d8c795]">
            난수 예시: <code>damage_roll=random(5..20)</code> · Skript 예시: <code>skript:quest::%uuid of player%=진행중</code> · 전용 미러는 <code>skript.quest</code>입니다.
          </div>

          <div className="grid gap-3 pt-1">
            <div className="rounded-xl border border-[#3c3048] bg-[#15111b] p-3">
              <div className="text-xs font-semibold text-[#d7cddd]">변수 삭제</div>
              <div className="mt-1 text-[10px] text-[#94889d]">여러 변수는 쉼표로 구분합니다.</div>
              <input
                className={`${input} mt-2`}
                value={server.effects.variablesDelete}
                placeholder="temporary_hint, old_flag"
                onChange={(event) => edit((settings) => void (settings.effects.variablesDelete = event.target.value))}
              />
            </div>
            <div className="rounded-xl border border-[#3c3048] bg-[#15111b] p-3">
              <div className="text-xs font-semibold text-[#d7cddd]">채팅 입력 → 변수 저장</div>
              <div className="mt-1 text-[10px] text-[#94889d]">
                플레이어가 채팅으로 입력한 값을 지정 변수에 저장합니다.
              </div>
              <input
                className={`${input} mt-2`}
                list="rpgmaker-effect-variable-names"
                value={server.effects.chatInputVariable}
                placeholder="nickname"
                onChange={(event) => edit((settings) => void (settings.effects.chatInputVariable = event.target.value))}
              />
            </div>
          </div>
        </EffectGroup>

        <EffectGroup title="🔊 사운드" description="sound id : pitch : volume : repeat(1~10)" accent="green">
          <textarea
            className={`${input} min-h-20 resize-y`}
            value={server.effects.sounds}
            placeholder="minecraft:entity.villager.yes:1.0:0.8:1"
            onChange={(event) => edit((settings) => void (settings.effects.sounds = event.target.value))}
          />
        </EffectGroup>

        <EffectGroup title="💬 플레이어에게 메시지 보내기" description="{{variable}} placeholder와 HEX 색상을 사용할 수 있습니다." accent="green">
          <label className={label}>
            플레이어에게 보낼 메시지
            <input
              className={`${input} mt-1.5`}
              value={server.effects.message}
              placeholder="{{player}}님, 완료되었습니다."
              onChange={(event) => edit((settings) => void (settings.effects.message = event.target.value))}
            />
          </label>
          <label className={label}>
            색상
            <div className="mt-1.5 flex gap-2">
              <input
                type="color"
                className="h-10 w-12 rounded-lg border border-[#3a3147] bg-[#181420] p-1"
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

        <EffectGroup title="↩ Return" description="돌아갈 페이지나 선택지 화면을 목록에서 직접 선택합니다." accent="gold">
          <ReturnPicker
            dialogue={dialogue}
            value={server.effects.returnTarget}
            onChange={(value) => edit((settings) => void (settings.effects.returnTarget = value))}
          />
          <div className="rounded-lg bg-[#17130d] px-3 py-2 text-[11px] leading-5 text-[#b9aa84]">
            페이지를 선택하면 해당 페이지로 돌아갑니다. 선택지를 선택하면 해당 페이지의 선택지 화면을 다시 엽니다.
          </div>
        </EffectGroup>

        <EffectGroup title="⌨ 서버 명령어 · OP 전용" description="{player}, {target} placeholder를 지원합니다." accent="red">
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
              edit((settings) => {
                settings.effects.commandTarget = event.target.value as ServerPageSettings['effects']['commandTarget'];
              })
            }
          >
            <option value="player">PLAYER · 대화 플레이어</option>
            <option value="all">ALL · 모든 플레이어</option>
            <option value="nearest">NEAREST · 가장 가까운 플레이어</option>
          </select>
        </EffectGroup>
      </div>
    </aside>
  );
}
