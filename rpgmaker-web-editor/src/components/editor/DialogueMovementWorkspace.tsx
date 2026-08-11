import type { DialoguePage } from '../../domain/project';
import { useProjectStore } from '../../store/projectStore';

interface Props {
  page: DialoguePage;
}

export function playableDialogueName(name: string) {
  const clean = name.replace(/[^\p{L}\p{N}_-]/gu, '_');
  return clean || 'default';
}

export function DialogueMovementWorkspace({ page }: Props) {
  const projects = useProjectStore((state) => state.projects);
  const mutateProject = useProjectStore((state) => state.mutateProject);
  const project = projects.find((candidate) =>
    candidate.dialogues.some((dialogue) => dialogue.pages.some((candidatePage) => candidatePage.id === page.id)),
  );
  const dialogue = project?.dialogues.find((candidate) => candidate.pages.some((candidatePage) => candidatePage.id === page.id));

  if (!project || !dialogue) return null;

  const setNextDialogue = (name: string) => {
    mutateProject(project.id, (draft) => {
      const target = draft.dialogues.find((candidate) => candidate.id === dialogue.id);
      if (target) target.nextDialogueName = name || undefined;
    });
  };

  return (
    <section className="mt-8 rounded-2xl border border-[#4a4030] bg-[#17140e] p-5">
      <div className="text-xs font-semibold uppercase tracking-[0.14em] text-[#f1c66d]">대화 이동</div>
      <h3 className="mt-2 text-base font-semibold text-[#f4ead2]">이 대화가 끝난 뒤</h3>
      <p className="mt-2 text-xs leading-5 text-[#8f846e]">
        현재 대화의 마지막 흐름까지 모두 끝났을 때 자동으로 시작할 다음 대화를 지정합니다. 같은 대화 안의 페이지 이동과 조건부 이동은 오른쪽 대화 이동 패널에서 별도로 설정합니다.
      </p>
      <select
        value={dialogue.nextDialogueName ?? ''}
        onChange={(event) => setNextDialogue(event.target.value)}
        className="mt-4 w-full rounded-xl border border-[#4a4030] bg-[#110f0b] px-3 py-3 text-sm text-[#f4ead2] outline-none focus:border-[#f1c66d]"
      >
        <option value="">다음 대화 없음 · 현재 대화 종료</option>
        {project.dialogues
          .filter((candidate) => candidate.id !== dialogue.id)
          .map((candidate) => {
            const targetName = candidate.server?.remoteName || playableDialogueName(candidate.name);
            return (
              <option key={candidate.id} value={targetName}>
                {candidate.name}
              </option>
            );
          })}
      </select>
      {dialogue.nextDialogueName && (
        <div className="mt-3 rounded-lg bg-[#211c12] px-3 py-2 text-xs text-[#d8bf82]">
          종료 후 → {dialogue.nextDialogueName}
        </div>
      )}
    </section>
  );
}
