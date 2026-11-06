package com.fashionagent.closet;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP 도구 4개 (앱 레포 docs/contracts/mcp-tools.md "mcp-closet"). 실패해도 예외를 던지지 않고 {"error": {"code", "message"}}를 돌려준다.
 * userId는 GPT가 정하지 않고 agent가 X-User-Id 값으로 덮어써서 넣는다(약속 문서 3절).
 */
@Component
public class ClosetTools {

    private static final Logger log = LoggerFactory.getLogger(ClosetTools.class);

    private final ClosetService service;

    public ClosetTools(ClosetService service) {
        this.service = service;
    }

    @Tool(name = "closet_search", description = """
            RAG 검색 단계: 사용자 옷장에서 지금 상황(일정·날씨)에 맞는 옷 후보와 코디 지식 조각(styleNotes)을 찾아 줍니다. \
            옷 추천 1단계에서 부르고, 추천은 candidates 안의 옷과 styleNotes를 근거로만 하세요. \
            empty가 true면 옷장이 비어 있으니 "옷 확인을 몇 번 하면 옷장이 채워져요"라고 안내하세요. \
            occasion·apparentTemperature를 주면 그 자리·날씨에 맞지 않는 옷(예: 면접에 운동복, 더운 날 겉옷)은 뺍니다. \
            어제·그제 입은 옷은 점수가 조금 낮아집니다(lastWorn으로 언제 입었는지 알 수 있음).""")
    public Object closetSearch(
            @ToolParam(description = "사용자 id(UUID). agent가 채웁니다.") String userId,
            @ToolParam(description = "일정·날씨로 만든 상황 문장. 예: 오늘 오전 면접, 체감 9도") String situation,
            @ToolParam(required = false, description = "mourning | wedding | formal | business | active | casual") String occasion,
            @ToolParam(required = false, description = "체감 온도(섭씨). 예: 9.5") Double apparentTemperature,
            @ToolParam(required = false, description = "후보에서 뺄 garmentId 목록. 예: 어제 입은 옷") List<String> excludeGarmentIds,
            @ToolParam(required = false, description = "후보 최대 개수. 기본 8, 최대 20") Integer limit) {
        return run(() -> service.search(Ids.userId(userId), situation, occasion, apparentTemperature, excludeGarmentIds, limit));
    }

    @Tool(name = "closet_save", description = """
            옷 추천을 마칠 때("이걸로 할게") 2단계에서 clothes_inspect로 본 옷들을 옷장에 기록합니다. 사진은 저장하지 않습니다. \
            이미 있는 옷이면 새로 만들지 않고 합칩니다(isNew=false). 돌려준 garmentId로 closet_wear를 부르세요.""")
    public Object closetSave(
            @ToolParam(description = "사용자 id(UUID). agent가 채웁니다.") String userId,
            @ToolParam(description = "clothes_inspect가 돌려준 Garment 목록(최대 20벌)") List<Garment> garments) {
        return run(() -> service.save(Ids.userId(userId), garments));
    }

    @Tool(name = "closet_wear", description = """
            옷을 입은 날을 기록합니다. closet_save 다음에 그 garmentId들로 부르세요. 같은 옷을 같은 날 두 번 기록해도 한 번으로 셉니다.""")
    public Object closetWear(
            @ToolParam(description = "사용자 id(UUID). agent가 채웁니다.") String userId,
            @ToolParam(description = "closet_save가 돌려준 garmentId 목록. 예: c_17, c_03") List<String> garmentIds,
            @ToolParam(required = false, description = "입은 날(YYYY-MM-DD, 서울 기준). 없으면 오늘") String date) {
        return run(() -> service.wear(Ids.userId(userId), garmentIds, date));
    }

    @Tool(name = "closet_outfits", description = """
            예전에 입은 조합을 돌려줍니다. 같은 날 closet_wear로 기록한 옷을 한 벌로 묶고, 최근 날부터 줍니다. \
            "지난번 면접 때 뭐 입었지?"(schedule_list로 그 날짜를 찾은 뒤 from·to에 넣기), "이 셔츠 전에 뭐랑 입었어?"(garmentId), \
            "요즘 뭐 입었지?"에 쓰세요. 기록이 없으면 outfits가 빈 목록입니다.""")
    public Object closetOutfits(
            @ToolParam(description = "사용자 id(UUID). agent가 채웁니다.") String userId,
            @ToolParam(required = false, description = "이 날부터(YYYY-MM-DD). 없으면 to의 90일 전") String from,
            @ToolParam(required = false, description = "이 날까지(YYYY-MM-DD). 없으면 오늘(서울)") String to,
            @ToolParam(required = false, description = "이 옷을 입은 날만. 예: c_17") String garmentId,
            @ToolParam(required = false, description = "조합(날) 최대 개수. 기본 5, 최대 30") Integer limit) {
        return run(() -> service.outfits(Ids.userId(userId), from, to, garmentId, limit));
    }

    private Object run(Supplier<Object> body) {
        try {
            return body.get();
        } catch (ClosetException e) {
            return error(e.code(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("closet 도구 실패: {}", e.toString());
            return error("upstream_failed", "지금은 처리하지 못했어요.");
        }
    }

    static Map<String, Object> error(String code, String message) {
        return Map.of("error", Map.of("code", code, "message", message));
    }
}
