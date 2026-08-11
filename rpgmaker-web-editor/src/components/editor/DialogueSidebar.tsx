import type { Dialogue, RPGProject } from '../../domain/project';
import type { ValidationIssue } from '../../services/projectValidator';
import { StructuredPageNavigator } from './StructuredPageNavigator';

interface Props {
  project: RPGProject;
  dialogue: Dialogue;
  activePageId: string;
  issues: ValidationIssue[];
  onSelectDialogue: (dialogue: Dialogue) => void;
  onCreateDialogue: () => void;
  onDeleteDialogue?: () => void;
  onSelectPage: (pageId: string) => void;
  onCreatePage: () => void;
  onDuplicatePage: (pageId: string) => void;
  onDeletePage: (pageId: string) => void;
  readOnly?: boolean;
}

export function DialogueSidebar(props: Props) {
  return (
    <nav className="flex w-[300px] shrink-0 flex-col border-r border-[#242a33] bg-[#11151a]">
      <section className="max-h-[34%] overflow-y-auto p-3">
        <div className="px-2 py-2">
          <div className="truncate text-sm font-semibold">{props.project.name}</div>
          <div className="mt-1 text-[10px] uppercase tracking-[0.14em] text-[#606a78]">Dialogues</div>
        </div>

        <div className="mt-2 space-y-1">
          {props.project.dialogues.map((dialogue) => {
            const issueCount = props.issues.filter((issue) => issue.dialogueId === dialogue.id).length;
            return (
              <button
                type="button"
                key={dialogue.id}
                onClick={() => props.onSelectDialogue(dialogue)}
                className={`flex w-full items-center gap-2 rounded-lg px-2.5 py-2 text-left text-sm ${
                  dialogue.id === props.dialogue.id
                    ? 'bg-[#242a35] text-white'
                    : 'text-[#8d97a5] hover:bg-[#1b2027] hover:text-[#d4d8df]'
                }`}
              >
                <span className="text-xs">💬</span>
                <span className="min-w-0 flex-1 truncate">{dialogue.name}</span>
                {issueCount > 0 && (
                  <span className="rounded-full bg-red-400/10 px-1.5 py-0.5 text-[9px] text-red-300">{issueCount}</span>
                )}
              </button>
            );
          })}
        </div>

        <div className="mt-2 grid grid-cols-[1fr_auto] gap-1">
          <button
            type="button"
            onClick={props.onCreateDialogue}
            className="rounded-lg px-2.5 py-2 text-left text-xs text-[#8b99ff] hover:bg-[#1b2027]"
          >
            + 새 대화
          </button>
          {props.onDeleteDialogue && (
            <button
              type="button"
              onClick={props.onDeleteDialogue}
              className="rounded-lg px-2.5 py-2 text-xs text-[#8b929d] hover:bg-red-400/10 hover:text-red-300"
            >
              대화 삭제
            </button>
          )}
        </div>
      </section>

      <StructuredPageNavigator
        readOnly={props.readOnly}
        dialogue={props.dialogue}
        activePageId={props.activePageId}
        issues={props.issues.filter((issue) => issue.dialogueId === props.dialogue.id)}
        onSelectPage={(page) => props.onSelectPage(page.id)}
        onCreatePage={props.onCreatePage}
        onDuplicatePage={(page) => props.onDuplicatePage(page.id)}
        onDeletePage={(page) => props.onDeletePage(page.id)}
      />
    </nav>
  );
}
