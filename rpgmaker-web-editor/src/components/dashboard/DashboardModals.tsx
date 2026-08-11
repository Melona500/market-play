import { clearPlayerSession } from '../../services/playerSessionApi';

type ModalKind = 'settings' | 'help';

interface Props {
  kind: ModalKind;
  onClose: () => void;
}

const section = 'rounded-xl border border-[#2a3b4d] bg-[#101923] p-4';

export function DashboardModal({ kind, onClose }: Props) {
  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/65 p-6" onMouseDown={onClose}>
      <section
        className="max-h-[86vh] w-full max-w-3xl overflow-y-auto rounded-2xl border border-[#30475a] bg-[#0b141d] shadow-2xl"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="sticky top-0 z-10 flex items-start gap-4 border-b border-[#26394a] bg-[#0b141d]/95 px-6 py-5 backdrop-blur">
          <div>
            <div className="text-xs font-semibold tracking-[0.16em] text-[#42d4d0]">RPGMAKER</div>
            <h2 className="mt-1 text-xl font-semibold text-white">{kind === 'settings' ? '설정' : '도움말'}</h2>
          </div>
          <button type="button" onClick={onClose} className="ml-auto rounded-lg px-3 py-2 text-[#91a0ad] hover:bg-[#172331] hover:text-white">
            닫기
          </button>
        </header>

        {kind === 'settings' ? <SettingsContent /> : <HelpContent />}
      </section>
    </div>
  );
}

function SettingsContent() {
  const resetSession = () => {
    clearPlayerSession();
    window.alert('저장된 Minecraft 웹 연결 세션을 삭제했습니다. 다시 연결하려면 게임에서 /rpgmaker web을 실행하세요.');
  };

  return (
    <div className="space-y-4 p-6 text-sm text-[#c7d2dc]">
      <div className={section}>
        <h3 className="font-semibold text-white">Minecraft 서버 연결</h3>
        <p className="mt-2 leading-6 text-[#8fa0ae]">
          게임에서 <code className="rounded bg-black/25 px-1.5 py-0.5 text-[#9d8cff]">/rpgmaker web</code>을 실행한 뒤 생성된 링크로 웹을 열면 플레이어 계정과 자동 연결됩니다.
        </p>
        <button type="button" onClick={resetSession} className="mt-4 rounded-lg border border-[#41566a] bg-[#14202b] px-3 py-2 text-xs text-[#c2ced8] hover:bg-[#1b2a37]">
          저장된 연결 세션 초기화
        </button>
      </div>

      <div className={section}>
        <h3 className="font-semibold text-white">저장 방식</h3>
        <p className="mt-2 leading-6 text-[#8fa0ae]">
          웹 편집 내용은 브라우저 로컬 저장소에 자동 저장됩니다. 서버 데이터는 별도이며, 편집기에서 <b className="text-[#dce5ed]">서버에 반영</b>을 눌러야 Minecraft 서버에 적용됩니다.
        </p>
      </div>

      <div className={section}>
        <h3 className="font-semibold text-white">기본 Web API</h3>
        <p className="mt-2 leading-6 text-[#8fa0ae]">
          로컬 개발 환경에서는 기본적으로 <code className="rounded bg-black/25 px-1.5 py-0.5">127.0.0.1:25567</code>의 RPGMaker Web API를 사용합니다. 다른 환경에서는 서버가 제공하는 웹 주소와 허용 Origin 설정을 사용합니다.
        </p>
      </div>
    </div>
  );
}

function HelpContent() {
  return (
    <div className="space-y-4 p-6 text-sm text-[#c7d2dc]">
      <Help title="대화와 페이지">
        왼쪽에서 대화를 선택하고 페이지를 추가합니다. 각 페이지는 화자, 최대 4줄의 대사, 캐릭터/표정, 조건, 효과를 가질 수 있습니다. 새 페이지는 직전 페이지의 화자와 캐릭터 외형을 기본값으로 이어받습니다.
      </Help>
      <Help title="선택지">
        현재 페이지에 플레이어가 고를 선택지를 최대 8개까지 만들 수 있습니다. 왼쪽 페이지 구조 아래의 <b>선택지 추가</b>를 사용하면 현재 페이지에 바로 선택지를 추가하고 편집 창을 엽니다. 선택지에는 표시 조건, 후속 대사 페이지, 후속 대사 안의 중첩 선택지를 설정할 수 있습니다.
      </Help>
      <Help title="선택지 후속 대사">
        선택지를 고른 직후 표시할 대사를 여러 페이지로 작성할 수 있습니다. 각 후속 페이지는 일반 대화처럼 4줄 대사와 캐릭터/표정, 효과를 설정할 수 있고, 그 페이지에 다시 선택지를 넣어 다단계 분기를 만들 수 있습니다.
      </Help>
      <Help title="대화 이동">
        페이지 또는 선택지 분기가 끝난 뒤 어디로 진행할지 정하는 기능입니다. 일반 페이지의 다음 페이지/조건부 이동/종료와 선택지의 최종 대상 페이지/종료를 여기서 구분해 설정합니다. 선택지 내용 자체와 이동 규칙은 별개입니다.
      </Help>
      <Help title="조건과 효과">
        조건은 변수나 아이템 상태에 따라 페이지 또는 선택지를 표시합니다. 효과는 아이템 지급·회수, 변수 연산, 난수식, 사운드, 메시지, 채팅 입력 저장, 서버 명령 등을 실행합니다.
      </Help>
      <Help title="변수">
        대사 안에서 <code className="rounded bg-black/25 px-1.5 py-0.5 text-[#9d8cff]">{'{{변수명}}'}</code>을 사용하면 플레이어별 변수 값을 출력할 수 있습니다. Skript의 <code className="rounded bg-black/25 px-1.5 py-0.5">%player%</code>, <code className="rounded bg-black/25 px-1.5 py-0.5">%player's location%</code>, <code className="rounded bg-black/25 px-1.5 py-0.5">{'%{변수::%uuid of player%}%'}</code>도 사용할 수 있습니다. 조건·효과에서는 <code className="rounded bg-black/25 px-1.5 py-0.5">skript:변수::%uuid of player%</code> 형식을 사용합니다. <code className="rounded bg-black/25 px-1.5 py-0.5 text-[#9d8cff]">#색코드:단어</code>로 색을 입히고, <code className="rounded bg-black/25 px-1.5 py-0.5">#FF0000:bold,italic,strikethrough:단어</code>로 굵기·기울임·취소선을 함께 지정할 수 있습니다. 효과에서는 <code className="rounded bg-black/25 px-1.5 py-0.5">score+=1</code>과 <code className="rounded bg-black/25 px-1.5 py-0.5">roll=random(1..10)</code>도 사용할 수 있습니다.
      </Help>
      <Help title="서버 동기화">
        <b>서버에서 불러오기</b>는 서버의 현재 대화를 웹으로 가져오고, <b>서버에 반영</b>은 웹의 추가·수정·삭제 내용을 서버에 적용합니다. 같은 이름의 대화는 중복 생성하지 않고 기존 대화를 갱신합니다.
      </Help>
      <Help title="테스트와 단축키">
        편집기 테스트 모드로 분기 흐름을 확인할 수 있습니다. Ctrl+S는 즉시 저장, Ctrl+Z / Ctrl+Shift+Z는 실행 취소/다시 실행, Ctrl+Enter는 테스트 실행, Esc는 열린 패널을 닫습니다.
      </Help>
    </div>
  );
}

function Help({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className={section}>
      <h3 className="font-semibold text-white">{title}</h3>
      <div className="mt-2 leading-6 text-[#8fa0ae]">{children}</div>
    </section>
  );
}
