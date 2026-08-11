import { useEffect, useState } from 'react';
import { BUILT_IN_VARIABLES } from '../../domain/builtInVariables';
import type { ItemDefinition, VariableDefinition } from '../../domain/project';
import type { ServerCondition } from '../../domain/serverSettings';
import { conditionSummary } from '../../services/previewEngine';

interface Props {
  value: ServerCondition;
  variables: VariableDefinition[];
  items: ItemDefinition[];
  showReplacement?: boolean;
  onChange: (mutator: (condition: ServerCondition) => void) => void;
}

const input =
  'w-full rounded-lg border border-[#2a3039] bg-[#171b21] px-3 py-2 text-sm text-[#eef1f5] outline-none transition focus:border-[#42d4d0]';
const label = 'text-xs font-medium text-[#919aa8]';
const variableNamePattern = /^(?:[\p{L}\p{N}_.-]+|skript:.+|\{.+\})$/u;

const operators: Array<[ServerCondition['operator'], string]> = [
  ['eq', '같음'],
  ['ne', '다름'],
  ['gt', '초과'],
  ['gte', '이상'],
  ['lt', '미만'],
  ['lte', '이하'],
  ['is-set', '설정됨'],
  ['is-unset', '설정 안 됨'],
];

const modeOptions: Array<[ServerCondition['mode'], string, string]> = [
  ['none', '항상 적용', '조건 없이 항상 적용합니다.'],
  ['variable', '변수로 판단', '플레이어 변수 값을 확인합니다.'],
  ['item', '아이템으로 판단', '보유 아이템을 확인합니다.'],
  ['both', '변수와 아이템 모두', '두 종류의 조건을 모두 만족해야 합니다.'],
  ['any', '변수 또는 아이템', '둘 중 하나만 만족해도 됩니다.'],
];

interface ExtraRow {
  name: string;
  operator: string;
  value: string;
}

function parseExtra(value: string): ExtraRow[] {
  return value
    .split(',')
    .map((entry) => entry.trim())
    .filter(Boolean)
    .map((entry) => {
      const match = entry.match(/^(.+?)\s*(==|=|!=|>=|<=|>|<)\s*(.*)$/u);
      return match
        ? { name: match[1], operator: match[2], value: match[3] }
        : { name: entry, operator: '=', value: '' };
    });
}

function serializeExtra(rows: ExtraRow[]) {
  return rows
    .filter((row) => row.name.trim())
    .map((row) => `${row.name.trim()}${row.operator}${row.value}`)
    .join(', ');
}

function parseItem(value: string) {
  const match = value.trim().match(/^(.+?)(?::(\d+))?$/);
  if (!match) return { id: '', amount: 1, name: '', color: '#FFFFFF' };
  if (match[1].startsWith('@')) {
    const custom = value.trim().match(/^(@[^:]+)(?::(\d+))?$/);
    return { id: custom?.[1] ?? value.trim(), amount: Number(custom?.[2] ?? 1), name: '', color: '#FFFFFF' };
  }
  const parts = value.trim().split(':');
  if (parts.length >= 3 && /^\d+$/.test(parts[2])) {
    return {
      id: `${parts[0]}:${parts[1]}`,
      amount: Number(parts[2]),
      name: parts[3] ?? '',
      color: parts[4] || '#FFFFFF',
    };
  }
  return { id: match[1], amount: Number(match[2] ?? 1), name: '', color: '#FFFFFF' };
}

function serializeItem(item: ReturnType<typeof parseItem>) {
  if (!item.id) return '';
  const base = `${item.id}:${Math.min(100, Math.max(1, item.amount || 1))}`;
  return item.name && !item.id.startsWith('@') ? `${base}:${item.name}:${item.color || '#FFFFFF'}` : base;
}

export function ConditionBuilder({ value, variables, items, showReplacement = false, onChange }: Props) {
  const [extraRows, setExtraRows] = useState<ExtraRow[]>(() => parseExtra(value.extraVariables));
  const item = parseItem(value.itemSpec);
  const needsVariable = ['variable', 'both', 'any'].includes(value.mode);
  const needsItem = ['item', 'both', 'any'].includes(value.mode);
  const valueDisabled = value.operator === 'is-set' || value.operator === 'is-unset';
  const variableInvalid = Boolean(value.variable) && !variableNamePattern.test(value.variable);

  useEffect(() => {
    setExtraRows(parseExtra(value.extraVariables));
  }, [value.extraVariables]);

  const changeExtra = (rows: ExtraRow[]) => {
    setExtraRows(rows);
    onChange((condition) => {
      condition.extraVariables = serializeExtra(rows);
    });
  };

  return (
    <div className="space-y-5" data-rpg-section="condition">
      <div>
        <div className={label}>언제 적용할까요?</div>
        <div className="mt-2 grid grid-cols-2 gap-2">
          {modeOptions.map(([mode, title, description]) => (
            <button
              key={mode}
              type="button"
              onClick={() => onChange((condition) => void (condition.mode = mode))}
              className={`rounded-xl border p-3 text-left transition ${
                value.mode === mode
                  ? 'border-[#42d4d0] bg-[#163038]'
                  : 'border-[#2a3039] bg-[#171b21] hover:border-[#3f6570]'
              }`}
            >
              <div className="text-sm font-semibold">{title}</div>
              <div className="mt-1 text-[11px] leading-4 text-[#77818f]">{description}</div>
            </button>
          ))}
        </div>
      </div>

      {needsVariable && (
        <section className="space-y-3 rounded-xl border border-[#264a52] bg-[#101f26] p-4">
          <div>
            <div className="text-sm font-semibold text-[#bcefed]">변수 조건</div>
            <div className="mt-1 text-xs text-[#7f9ca2]">
              RPGMaker 변수 또는 Skript 변수(예: skript:quest::%uuid of player%)를 사용할 수 있습니다.
            </div>
          </div>

          <div className="grid grid-cols-[1fr_130px_1fr] gap-2">
            <label className={label}>
              변수
              <input
                list="rpgmaker-variable-list"
                className={`${input} mt-1.5 ${variableInvalid ? 'border-red-400/70' : ''}`}
                value={value.variable}
                placeholder="quest.progress / skript:quest::%uuid of player%"
                onChange={(event) => onChange((condition) => void (condition.variable = event.target.value))}
              />
              {variableInvalid && <span className="mt-1 block text-[10px] text-red-300">사용할 수 없는 변수명 형식입니다.</span>}
            </label>
            <label className={label}>
              비교
              <select
                className={`${input} mt-1.5`}
                value={value.operator}
                onChange={(event) =>
                  onChange((condition) => void (condition.operator = event.target.value as ServerCondition['operator']))
                }
              >
                {operators.map(([operator, text]) => (
                  <option key={operator} value={operator}>
                    {text}
                  </option>
                ))}
              </select>
            </label>
            <label className={label}>
              값
              <input
                disabled={valueDisabled}
                className={`${input} mt-1.5 disabled:opacity-40`}
                value={value.value}
                placeholder="1000"
                onChange={(event) => onChange((condition) => void (condition.value = event.target.value))}
              />
            </label>
          </div>

          <datalist id="rpgmaker-variable-list">
            {BUILT_IN_VARIABLES.map((name) => (
              <option key={`builtin-${name}`} value={name} />
            ))}
            {variables.map((variable) => (
              <option key={variable.id} value={variable.name} />
            ))}
          </datalist>

          <div className="rounded-xl border border-[#27434b] bg-[#0c171c] p-3">
            <div className="flex items-center justify-between">
              <div>
                <div className="text-xs font-semibold text-[#b7d7da]">추가 변수 조건</div>
                <div className="mt-1 text-[11px] text-[#728d92]">여러 변수의 관계를 AND / OR / XOR / NOT으로 조합합니다.</div>
              </div>
              <select
                className="rounded-lg border border-[#31515a] bg-[#14262d] px-2 py-1.5 text-xs"
                value={value.variableLogic}
                onChange={(event) =>
                  onChange((condition) =>
                    void (condition.variableLogic = event.target.value as ServerCondition['variableLogic']),
                  )
                }
              >
                <option value="and">모두 만족 (AND)</option>
                <option value="or">하나 이상 (OR)</option>
                <option value="xor">정확히 하나 (XOR)</option>
                <option value="not">반대 조건 (NOT)</option>
              </select>
            </div>

            <div className="mt-3 space-y-2">
              {extraRows.map((row, index) => (
                <div key={`${row.name}-${index}`} className="grid grid-cols-[1fr_96px_1fr_32px] gap-2">
                  <input
                    className={`${input} ${row.name && !variableNamePattern.test(row.name) ? 'border-red-400/70' : ''}`}
                    value={row.name}
                    placeholder="quest_done"
                    onChange={(event) => {
                      const next = structuredClone(extraRows);
                      next[index].name = event.target.value;
                      changeExtra(next);
                    }}
                  />
                  <select
                    className={input}
                    value={row.operator}
                    onChange={(event) => {
                      const next = structuredClone(extraRows);
                      next[index].operator = event.target.value;
                      changeExtra(next);
                    }}
                  >
                    <option value="=">같음</option>
                    <option value="!=">다름</option>
                    <option value=">">초과</option>
                    <option value=">=">이상</option>
                    <option value="<">미만</option>
                    <option value="<=">이하</option>
                  </select>
                  <input
                    className={input}
                    value={row.value}
                    placeholder="true"
                    onChange={(event) => {
                      const next = structuredClone(extraRows);
                      next[index].value = event.target.value;
                      changeExtra(next);
                    }}
                  />
                  <button
                    type="button"
                    className="rounded-lg text-[#76808e] hover:bg-[#262c35] hover:text-red-300"
                    onClick={() => changeExtra(extraRows.filter((_, rowIndex) => rowIndex !== index))}
                  >
                    ×
                  </button>
                </div>
              ))}
            </div>

            <button
              type="button"
              className="mt-3 rounded-lg px-2 py-1.5 text-xs text-[#54d5d1] hover:bg-[#163038]"
              onClick={() => changeExtra([...extraRows, { name: '', operator: '=', value: '' }])}
            >
              + 변수 조건 추가
            </button>
          </div>
        </section>
      )}

      {needsItem && (
        <section className="rounded-xl border border-[#4b3e27] bg-[#211b11] p-4">
          <div className="text-sm font-semibold text-[#f1d39a]">아이템 조건</div>
          {items.length > 0 && (
            <select
              className={`${input} mt-3`}
              value={items.some((candidate) => candidate.minecraftId === item.id) ? item.id : ''}
              onChange={(event) =>
                onChange((condition) =>
                  void (condition.itemSpec = serializeItem({ ...item, id: event.target.value }))
                )
              }
            >
              <option value="">직접 입력 또는 서버 저장 아이템 선택</option>
              {items.map((candidate) => <option key={candidate.id} value={candidate.minecraftId}>{candidate.displayName}</option>)}
            </select>
          )}
          <div className="mt-3 grid grid-cols-[1fr_100px] gap-2">
            <label className={label}>
              아이템 ID
              <input
                className={`${input} mt-1.5`}
                value={item.id}
                placeholder="minecraft:emerald"
                onChange={(event) =>
                  onChange((condition) => {
                    condition.itemSpec = serializeItem({ ...item, id: event.target.value });
                  })
                }
              />
            </label>
            <label className={label}>
              수량
              <input
                type="number"
                min={1}
                max={100}
                className={`${input} mt-1.5`}
                value={item.amount}
                onChange={(event) =>
                  onChange((condition) => {
                    condition.itemSpec = serializeItem({ ...item, amount: Number(event.target.value) || 1 });
                  })
                }
              />
            </label>
          </div>
          {!item.id.startsWith('@') && (
            <div className="mt-3 grid grid-cols-[1fr_120px] gap-2">
              <label className={label}>
                표시 이름 (선택)
                <input
                  className={`${input} mt-1.5`}
                  value={item.name}
                  placeholder="이 이름까지 일치할 때"
                  onChange={(event) =>
                    onChange((condition) =>
                      void (condition.itemSpec = serializeItem({ ...item, name: event.target.value }))
                    )
                  }
                />
              </label>
              <label className={label}>
                이름 색상
                <input
                  className={`${input} mt-1.5`}
                  value={item.color}
                  placeholder="#FFFFFF"
                  onChange={(event) =>
                    onChange((condition) =>
                      void (condition.itemSpec = serializeItem({ ...item, color: event.target.value }))
                    )
                  }
                />
              </label>
            </div>
          )}
        </section>
      )}

      <div className="rounded-xl border border-[#2d4c54] bg-[#0e1a20] px-4 py-3">
        <div className="text-[10px] font-semibold uppercase tracking-[0.16em] text-[#67b9b7]">조건 설명</div>
        <div className="mt-1.5 text-sm text-[#d2e7e7]">{conditionSummary(value)}</div>
      </div>

      {showReplacement && (
        <section className="rounded-xl border border-[#4b3a55] bg-[#1d1623] p-4">
          <div className="text-sm font-semibold text-[#dcc9f5]">조건을 만족하지 않을 때 대체 대사</div>
          <div className="mt-1 text-xs text-[#9385a2]">필요한 경우 원래 대사 대신 최대 4줄을 표시합니다.</div>
          <div className="mt-3 space-y-2">
            {value.replacementLines.map((line, index) => (
              <div key={index} className="grid grid-cols-[24px_1fr_54px] items-center gap-2">
                <span className="text-xs text-[#7d6f89]">{index + 1}</span>
                <input
                  className={input}
                  value={line}
                  onChange={(event) =>
                    onChange((condition) => {
                      condition.replacementLines[index] = event.target.value;
                    })
                  }
                />
                <span className={`text-right text-[10px] ${line.length > 30 ? 'text-red-400' : 'text-[#7d6f89]'}`}>
                  {line.length}/30
                </span>
              </div>
            ))}
          </div>
        </section>
      )}
    </div>
  );
}
