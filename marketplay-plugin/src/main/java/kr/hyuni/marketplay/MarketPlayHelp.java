package kr.hyuni.marketplay;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

final class MarketPlayHelp {
    static final int TOTAL_PAGES = 5;

    private MarketPlayHelp() { }

    static boolean show(CommandSender sender, String[] args) {
        int page = parsePage(args);
        if (page < 1) {
            sender.sendMessage(Component.text("사용법: /marketplay help [1-" + TOTAL_PAGES + "]", NamedTextColor.YELLOW));
            return true;
        }

        sender.sendMessage(Component.text("시장놀이 도움말 " + page + "/" + TOTAL_PAGES, NamedTextColor.GOLD));
        for (Entry entry : entries(page, sender.hasPermission("marketplay.admin"))) {
            sender.sendMessage(Component.text(entry.command(), NamedTextColor.AQUA)
                    .append(Component.text(" - " + entry.description(), NamedTextColor.GRAY)));
        }
        sender.sendMessage(Component.text("페이지 이동: /mp help <1-" + TOTAL_PAGES + "> · /marketplay help <1-" + TOTAL_PAGES + ">", NamedTextColor.YELLOW));
        return true;
    }

    static int parsePage(String[] args) {
        if (args.length < 2) return 1;
        try {
            int page = Integer.parseInt(args[1]);
            return page >= 1 && page <= TOTAL_PAGES ? page : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    static List<Entry> entries(int page, boolean admin) {
        List<Entry> entries = new ArrayList<>();
        switch (page) {
            case 1 -> {
                entries.add(new Entry("/mp", "돈·내공·계급·활력·생활 숙련도 상태를 확인합니다."));
                entries.add(new Entry("/mp menu [분류]", "시장놀이 종합 GUI를 열고 주요 시설과 기능을 찾습니다."));
                entries.add(new Entry("/mp market", "생활도구 상점을 열어 도구를 구매하고 판매 기능으로 이동합니다."));
                entries.add(new Entry("/mp tools", "구매한 생활도구와 사용 방식을 확인합니다."));
                entries.add(new Entry("/mp sell", "주손에 든 판매 가능한 시장놀이 자원을 즉시 판매합니다."));
                entries.add(new Entry("/mp board", "주민 게시글을 조회합니다. post로 작성하고 remove로 본인 글을 삭제합니다."));
            }
            case 2 -> {
                entries.add(new Entry("/mp home", "개인 주택 생성·입장·확장과 주택 관련 기능을 이용합니다."));
                entries.add(new Entry("/mp mail", "편지·선물·초대 등 우편 기능을 확인하고 이용합니다."));
                entries.add(new Entry("/mp art", "그림 작품 제작·보관·전시·판매·선물 기능을 이용합니다."));
                entries.add(new Entry("/mp explore <지역>", "바다·광산·왕성·광장 등 해금된 탐험 지역으로 이동합니다."));
                entries.add(new Entry("/mp craft <종류>", "탐험 재료로 반지·목걸이·왕실 선물·장식을 제작합니다."));
                entries.add(new Entry("/mp royal", "왕실 평판, 의뢰와 왕실 상점을 이용합니다."));
                entries.add(new Entry("/mp knight", "기사 시험과 전직 진행 상태를 확인하고 도전합니다."));
            }
            case 3 -> {
                entries.add(new Entry("/mp exchange", "거래소 매물을 조회·등록·구매·취소하고 거래 통계를 확인합니다."));
                entries.add(new Entry("/mp stall", "물리 노점을 맡거나 반납합니다."));
                entries.add(new Entry("/mp restaurant", "재료·주문·조리·서빙·평점·수익의 레스토랑 영업을 진행합니다."));
                entries.add(new Entry("/mp guild", "상단 창설·가입·공동 창고·공동 프로젝트를 관리합니다."));
                entries.add(new Entry("/mp service", "다른 플레이어에게 제공하는 서비스 경제 기능을 이용합니다."));
            }
            case 4 -> {
                entries.add(new Entry("/mp endgame", "후반 마을에 입장하거나 방독면·살충기 같은 후반 장비를 준비합니다."));
                entries.add(new Entry("/mp dungeon", "쓰레기장·해적선·아누비스 던전을 개인 또는 상단 단위로 진행합니다."));
                entries.add(new Entry("/mp tower", "무한 탑을 시작·입장하고 상태와 기록을 확인합니다."));
                entries.add(new Entry("/mp dragon", "용 관련 성장·전투 콘텐츠를 진행합니다."));
                entries.add(new Entry("/mp deeds", "선행과 관련된 후반 진행 상황을 다룹니다."));
                entries.add(new Entry("/mp warrior", "후반 전직/전사 계열 성장 기능을 이용합니다."));
                entries.add(new Entry("/mp heaven", "천국 계열 후반 콘텐츠를 진행합니다."));
            }
            case 5 -> {
                entries.add(new Entry("Shift + 손 바꾸기", "어디서든 시장놀이 종합 메뉴를 엽니다."));
                entries.add(new Entry("NPC 우클릭", "시설 안내와 RPGMaker 대화창을 엽니다."));
                entries.add(new Entry("/mp help [페이지]", "현재 도움말을 엽니다. /marketplay help도 동일합니다."));
                entries.add(new Entry("도구 사용", "대부분의 생활도구는 대상 상호작용 시 자동 사용되며 낚싯대는 직접 듭니다."));
                if (admin) {
                    entries.add(new Entry("/mp reload", "MarketPlay 설정을 다시 읽습니다. 관리자 전용입니다."));
                    entries.add(new Entry("/mp admin money add|set ...", "플레이어 돈을 관리자 사유와 함께 추가하거나 설정합니다."));
                    entries.add(new Entry("/mp admin item give ...", "시장놀이 태그가 적용된 아이템을 플레이어에게 지급합니다."));
                }
            }
            default -> { }
        }
        return List.copyOf(entries);
    }

    record Entry(String command, String description) { }
}
