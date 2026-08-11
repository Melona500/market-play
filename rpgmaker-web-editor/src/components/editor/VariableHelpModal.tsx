interface Props {
  onClose: () => void;
}

export function VariableHelpModal({ onClose }: Props) {
  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/70 p-6" onMouseDown={onClose}>
      <section className="max-h-[85vh] w-full max-w-2xl overflow-y-auto rounded-2xl border border-[#303846] bg-[#151a21] p-6 text-sm text-[#cbd1da]" onMouseDown={(event) => event.stopPropagation()}>
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-semibold text-white">변수 사용법</h2>
          <button type="button" onClick={onClose} className="rounded-lg px-3 py-1.5 text-[#8f99a7] hover:bg-[#252c36]">닫기</button>
        </div>

        <div className="mt-5 space-y-5 leading-6">
          <section>
            <h3 className="font-semibold text-[#aab2ff]">RPGMaker 개인 변수</h3>
            <p>대사: <code>{'{{호감도}}'}</code></p>
            <p>Skript: <code>{'{rpgmaker::%uuid of player%::호감도}'}</code></p>
            <p className="text-[#8993a1]">플레이어별 값이며 양쪽 변경이 약 1초 안에 동기화됩니다. 한글 이름과 숫자 값을 지원합니다.</p>
          </section>

          <section>
            <h3 className="font-semibold text-[#aab2ff]">Skript 일반 변수</h3>
            <p>Skript 변수 <code>{'{quest_stage}'}</code>는 대사에서 <code>%{'{quest_stage}'}%</code>로 출력합니다.</p>
            <p>조건·효과의 변수 이름에는 <code>skript:quest_stage</code>를 입력합니다.</p>
          </section>

          <section>
            <h3 className="font-semibold text-[#aab2ff]">Skript 개인·묶음 변수</h3>
            <p>개인 변수: <code>%{'{quest::%uuid of player%}'}%</code></p>
            <p>조건·효과: <code>skript:quest::%uuid of player%</code></p>
            <p>묶음 변수 <code>%{'{quest_list::*}'}%</code>는 쉼표로 묶어 표시됩니다.</p>
          </section>

          <section>
            <h3 className="font-semibold text-[#aab2ff]">기본 제공 변수</h3>
            <p><code>{'{{player_name}}'}</code>, <code>{'{{player_uuid}}'}</code>, <code>{'{{player_world}}'}</code>, <code>{'{{player_x}}'}</code>, <code>{'{{player_y}}'}</code>, <code>{'{{player_z}}'}</code>, <code>{'{{player_health}}'}</code>, <code>{'{{player_item}}'}</code></p>
          </section>
        </div>
      </section>
    </div>
  );
}
