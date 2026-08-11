import type { Dialogue, DialoguePage } from '../../domain/project';
import { emptyChoiceSettings } from '../../domain/serverSettings';
import type { ValidationIssue } from '../../services/projectValidator';
import { useEditorStore } from '../../store/editorStore';
import { useProjectStore } from '../../store/projectStore';

interface Props {
  dialogue: Dialogue;
  activePageId: string;
  issues: ValidationIssue[];
  onSelectPage: (page: DialoguePage) => void;
  onCreatePage: () => void;
  onDuplicatePage: (page: DialoguePage) => void;
  onDeletePage: (page: DialoguePage) => void;
  readOnly?: boolean;
}

function flags(page: DialoguePage, issues: ValidationIssue[]) {
  const result: string[] = [];
  if (page.choices.length) result.push(`선택 ${page.choices.length}`);
  if (page.server?.displayCondition.mode && page.server.displayCondition.mode !== 'none') result.push('조건');
  if (page.server && Object.values(page.server.effects).some((value) => typeof value === 'string' && value.trim())) result.push('효과');
  if (page.server?.flow.ending || page.flow.ending) result.push('종료');
  if (page.server?.operationOnly || page.operationOnly) result.push('연산');
  if (issues.some((issue) => issue.pageId === page.id && issue.severity === 'error')) result.push('오류');
  return result;
}

export function StructuredPageNavigator(props: Props) {
  const projects = useProjectStore((state) => state.projects);
  const mutateProject = useProjectStore((state) => state.mutateProject);
  const selectChoice = useEditorStore((state) => state.selectChoice);
  const activePage = props.dialogue.pages.find((page) => page.id === props.activePageId) ?? props.dialogue.pages[0];

  const addChoice = () => {
    if (props.readOnly || !activePage || activePage.choices.length >= 8) return;
    const project = projects.find((candidate) => candidate.dialogues.some((dialogue) => dialogue.id === props.dialogue.id));
    if (!project) return;
    const choiceId = crypto.randomUUID();
    mutateProject(project.id, (draftProject) => {
      const dialogue = draftProject.dialogues.find((candidate) => candidate.id === props.dialogue.id);
      const page = dialogue?.pages.find((candidate) => candidate.id === activePage.id);
      if (!page || page.choices.length >= 8) return;
      page.choices.push({
        id: choiceId,
        label: `선택지 ${page.choices.length + 1}`,
        responsePages: [],
        server: emptyChoiceSettings(),
      });
    });
    selectChoice(choiceId);
  };

  return (
    <section className="min-h-0 flex-1 overflow-y-auto border-t border-[#242a33] p-3">
      <div className="flex items-center justify-between px-2 py-2">
        <span className="text-[11px] font-semibold tracking-[0.14em] text-[#66717f]">페이지 구조</span>
        <span className="text-[10px] text-[#596371]">{props.dialogue.pages.length}/30</span>
      </div>
      <div className="space-y-2">
        {props.dialogue.pages.map((page, index) => {
          const active = page.id === props.activePageId;
          const pageFlags = flags(page, props.issues);
          return (
            <div key={page.id} className={`group rounded-xl border ${active ? 'border-[#6877e8] bg-[#20263a]' : 'border-[#252b34] bg-[#171b21] hover:border-[#353e4a]'}`}>
              <button type="button" onClick={() => props.onSelectPage(page)} className="w-full p-3 text-left">
                <div className="flex items-center gap-2">
                  <span className={`text-[10px] font-bold ${active ? 'text-[#8996ff]' : 'text-[#68717e]'}`}>{String(index + 1).padStart(2, '0')}</span>
                  <span className="min-w-0 flex-1 truncate text-sm font-semibold">{page.editorLabel || `Page ${index + 1}`}</span>
                  {page.id === props.dialogue.startPageId && <span className="rounded bg-[#273248] px-1.5 py-0.5 text-[9px] text-[#8ca7dd]">START</span>}
                </div>
                <div className="mt-2 truncate pl-6 text-[11px] text-[#687280]">
                  {page.operationOnly || page.server?.operationOnly ? '대사 없이 연산/흐름만 실행' : page.lines.find((line) => line.trim()) || '대사가 비어 있습니다.'}
                </div>
                {!!pageFlags.length && (
                  <div className="mt-2 flex flex-wrap gap-1 pl-6">
                    {pageFlags.map((flag) => <span key={flag} className={`rounded px-1.5 py-0.5 text-[9px] ${flag === '오류' ? 'bg-red-400/10 text-red-300' : 'bg-[#29313d] text-[#9ba6b5]'}`}>{flag}</span>)}
                  </div>
                )}
              </button>
              <div className="hidden border-t border-[#2b313b] px-2 py-1.5 group-hover:flex">
                <button type="button" disabled={props.readOnly || props.dialogue.pages.length >= 30} onClick={() => props.onDuplicatePage(page)} className="rounded px-2 py-1 text-[10px] text-[#7f8997] hover:bg-[#252b34] disabled:opacity-30">복제</button>
                <button type="button" disabled={props.readOnly || props.dialogue.pages.length <= 1} onClick={() => props.onDeletePage(page)} className="ml-auto rounded px-2 py-1 text-[10px] text-[#7f8997] hover:bg-red-400/10 hover:text-red-300 disabled:opacity-30">삭제</button>
              </div>
            </div>
          );
        })}
      </div>
      <button
        type="button"
        disabled={props.readOnly || props.dialogue.pages.length >= 30}
        title={props.dialogue.pages.length >= 30 ? '한 대화에는 최대 30개의 페이지를 만들 수 있습니다.' : undefined}
        onClick={props.onCreatePage}
        className="mt-3 w-full rounded-xl border border-dashed border-[#343c48] px-3 py-3 text-sm text-[#8b99ff] enabled:hover:bg-[#1c2128] disabled:text-[#515a67]"
      >
        + 페이지 추가
      </button>

      <div className="my-4 h-px bg-[#242a33]" />
      <div className="flex items-center justify-between px-2 py-1">
        <span className="text-[11px] font-semibold tracking-[0.14em] text-[#66717f]">현재 페이지 선택지</span>
        <span className="text-[10px] text-[#596371]">{activePage?.choices.length ?? 0}/8</span>
      </div>
      <div className="mt-2 space-y-1">
        {activePage?.choices.map((choice, index) => (
          <button
            key={choice.id}
            type="button"
            onClick={() => selectChoice(choice.id)}
            className="flex w-full items-center gap-2 rounded-lg border border-[#252b34] bg-[#171b21] px-3 py-2 text-left hover:border-[#4d5774] hover:bg-[#1c222b]"
          >
            <span className="text-[10px] font-bold text-[#9d8cff]">{index + 1}</span>
            <span className="min-w-0 flex-1 truncate text-xs text-[#c2c9d3]">{choice.label || '이름 없는 선택지'}</span>
            <span className="text-[9px] text-[#66717f]">후속 {choice.responsePages?.length ?? 0}p</span>
          </button>
        ))}
        {!activePage?.choices.length && (
          <div className="rounded-lg border border-dashed border-[#2c343f] px-3 py-3 text-center text-[10px] text-[#596371]">이 페이지에는 선택지가 없습니다.</div>
        )}
      </div>
      <button
        type="button"
        disabled={props.readOnly || !activePage || activePage.choices.length >= 8}
        onClick={addChoice}
        className="mt-2 w-full rounded-xl border border-dashed border-[#4a405e] px-3 py-3 text-sm text-[#b09cff] enabled:hover:bg-[#211d2b] disabled:opacity-30"
      >
        + 선택지 추가
      </button>
    </section>
  );
}
