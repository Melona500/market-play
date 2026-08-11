import type { Dialogue, RPGProject } from '../../domain/project';
import type { ValidationIssue } from '../../services/projectValidator';

interface Props {
  issues: ValidationIssue[];
  project: RPGProject;
  onClose: () => void;
  onNavigate: (dialogue: Dialogue, pageId: string | undefined, section: ValidationIssue['section']) => void;
}

export function ValidationPanel({ issues, project, onClose, onNavigate }: Props) {
  const errors = issues.filter((issue) => issue.severity === 'error').length;
  const warnings = issues.length - errors;

  return (
    <aside className="flex w-[430px] shrink-0 flex-col border-l border-[#242a33] bg-[#12161b]">
      <header className="flex items-start border-b border-[#242a33] px-5 py-4">
        <div>
          <div className="text-base font-semibold">오류 검사</div>
          <div className="mt-1 text-xs text-[#788290]">
            {errors ? `오류 ${errors}개` : '오류 없음'} · 경고 {warnings}개
          </div>
        </div>
        <button type="button" onClick={onClose} className="ml-auto rounded-lg px-2 py-1 text-[#788290] hover:bg-[#20252d]">
          ✕
        </button>
      </header>

      <div className="min-h-0 flex-1 overflow-y-auto p-4">
        {issues.length === 0 ? (
          <div className="rounded-xl border border-emerald-400/15 bg-emerald-400/5 px-4 py-8 text-center">
            <div className="text-sm font-semibold text-emerald-200">검사 완료</div>
            <div className="mt-2 text-xs leading-5 text-[#829087]">현재 프로젝트에서 차단해야 할 문제를 찾지 못했습니다.</div>
          </div>
        ) : (
          <div className="space-y-2">
            {issues.map((issue) => {
              const dialogue = project.dialogues.find((entry) => entry.id === issue.dialogueId);
              const pageIndex = dialogue?.pages.findIndex((page) => page.id === issue.pageId) ?? -1;
              return (
                <button
                  type="button"
                  key={issue.id}
                  onClick={() => dialogue && onNavigate(dialogue, issue.pageId, issue.section)}
                  className="w-full rounded-xl border border-[#282f39] bg-[#171b21] p-3 text-left hover:border-[#3a4350] hover:bg-[#1b2027]"
                >
                  <div className="flex items-center gap-2">
                    <span
                      className={`h-2 w-2 rounded-full ${
                        issue.severity === 'error' ? 'bg-red-400' : 'bg-amber-300'
                      }`}
                    />
                    <span className="text-xs font-semibold text-[#aeb6c1]">
                      {dialogue?.name ?? '대화'}
                      {pageIndex >= 0 ? ` / Page ${pageIndex + 1}` : ''}
                    </span>
                    <span className="ml-auto text-[9px] uppercase text-[#606a78]">{issue.section}</span>
                  </div>
                  <div className="mt-2 pl-4 text-xs leading-5 text-[#8c96a4]">{issue.message}</div>
                </button>
              );
            })}
          </div>
        )}
      </div>
    </aside>
  );
}
