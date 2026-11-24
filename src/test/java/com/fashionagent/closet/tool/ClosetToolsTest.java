package com.fashionagent.closet.tool;

import com.fashionagent.closet.ClosetException;
import com.fashionagent.closet.garment.ClosetService;
import com.fashionagent.closet.garment.Garment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MCP 도구 계층 테스트. REST 서버의 컨트롤러 테스트(@WebMvcTest)와 같은 자리입니다.
 * ClosetService는 Mock으로 바꾸고(정상 동작한다고 가정), 도구가 값을 제대로 넘기는지와
 * 돌려주는 결과·오류 코드만 봅니다. 진짜 DB로 끝까지 보는 건 ClosetIntegrationTest입니다.
 *
 * <p>MCP에는 HTTP 상태 코드가 없어서, 실패는 {"error": {"code", "message"}}로 돌려줍니다
 * (bad_request ≈ 400, not_found ≈ 404, upstream_failed ≈ 502).
 */
class ClosetToolsTest {

    private static final String USER = "7f1e2a3b-0000-4000-8000-000000000001";
    private static final UUID USER_ID = UUID.fromString(USER);

    private ClosetService service;
    private ClosetTools tools;

    @BeforeEach
    void setUp() {
        service = mock(ClosetService.class);
        tools = new ClosetTools(service);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> errorOf(Object result) {
        assertThat(result).isInstanceOf(Map.class);
        Object error = ((Map<String, Object>) result).get("error");
        assertThat(error).as("error 필드").isNotNull();
        return (Map<String, Object>) error;
    }

    @Test
    @DisplayName("closet_search Given 정상 요청 When 부르면 Then userId를 UUID로 바꿔 넘기고 서비스 결과를 그대로 돌려준다")
    void searchOk() {
        // given
        Map<String, Object> found = Map.of("candidates", List.of(), "styleNotes", List.of(), "empty", true);
        given(service.search(USER_ID, "오늘 오전 면접, 체감 9도", "business", 9.5, List.of("c_1"), 8, "formal attire"))
                .willReturn(found);

        // when
        Object result = tools.closetSearch(USER, "오늘 오전 면접, 체감 9도", "business", 9.5, List.of("c_1"), 8,
                "formal attire");

        // then
        assertThat(result).isEqualTo(found);
    }

    @Test
    @DisplayName("Given userId가 없음 When 도구를 부르면 Then bad_request를 돌려주고 서비스는 부르지 않는다")
    void missingUserId() {
        // when
        Object result = tools.closetList(null, null, null);

        // then
        assertThat(errorOf(result)).containsEntry("code", "bad_request");
        then(service).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("Given userId가 UUID가 아님 When 도구를 부르면 Then bad_request를 돌려준다")
    void invalidUserId() {
        // when
        Object result = tools.closetWear("me", List.of("c_1"), null);

        // then
        assertThat(errorOf(result)).containsEntry("code", "bad_request")
                .containsEntry("message", "userId는 UUID여야 해요.");
        then(service).should(never()).wear(any(), anyList(), any());
    }

    @Test
    @DisplayName("closet_remove Given 남의 옷 id When 지우면 Then 서비스의 not_found를 그대로 돌려준다")
    void removeNotFound() {
        // given
        given(service.remove(USER_ID, List.of("c_other")))
                .willThrow(ClosetException.notFound("없는 옷이 있어요: c_other"));

        // when
        Object result = tools.closetRemove(USER, List.of("c_other"));

        // then
        assertThat(errorOf(result)).containsEntry("code", "not_found")
                .containsEntry("message", "없는 옷이 있어요: c_other");
    }

    @Test
    @DisplayName("Given 서비스에서 예상 못 한 오류 When 도구를 부르면 Then 예외를 던지지 않고 upstream_failed와 쉬운 문장을 돌려준다")
    void unexpectedFailure() {
        // given
        given(service.list(USER_ID, "top", 10)).willThrow(new IllegalStateException("db connection reset"));

        // when
        Object result = tools.closetList(USER, "top", 10);

        // then: 안의 오류 문장(db connection reset)은 밖으로 내보내지 않는다
        assertThat(errorOf(result)).containsEntry("code", "upstream_failed")
                .containsEntry("message", "지금은 처리하지 못했어요.");
    }

    @Test
    @DisplayName("closet_save Given 옷 목록 When 저장하면 Then forceNewIds·embeddingModel까지 그대로 넘긴다")
    void saveForwardsAll() {
        // given
        List<Garment> garments = List.of();
        Map<String, Object> saved = Map.of("saved", List.of());
        given(service.save(USER_ID, garments, List.of("g2"), "marqo-fashionSigLIP")).willReturn(saved);

        // when
        Object result = tools.closetSave(USER, garments, List.of("g2"), "marqo-fashionSigLIP");

        // then
        assertThat(result).isEqualTo(saved);
    }

    @Test
    @DisplayName("closet_wear·outfits·merge Given 정상 요청 When 부르면 Then 받은 값을 그대로 넘기고 결과를 돌려준다")
    void otherToolsForward() {
        // given
        given(service.wear(USER_ID, List.of("c_1"), "2026-11-20")).willReturn(Map.of("recorded", 1));
        given(service.outfits(USER_ID, "2026-11-01", "2026-11-20", "c_1", 5)).willReturn(Map.of("outfits", List.of()));
        given(service.merge(USER_ID, "c_2", "c_1")).willReturn(Map.of("garmentId", "c_1"));

        // when · then
        assertThat(tools.closetWear(USER, List.of("c_1"), "2026-11-20")).isEqualTo(Map.of("recorded", 1));
        assertThat(tools.closetOutfits(USER, "2026-11-01", "2026-11-20", "c_1", 5))
                .isEqualTo(Map.of("outfits", List.of()));
        assertThat(tools.closetMerge(USER, "c_2", "c_1")).isEqualTo(Map.of("garmentId", "c_1"));
    }
}
