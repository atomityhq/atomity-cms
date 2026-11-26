package io.atomity.generalcms.shared;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PagingTest {

    private static final Map<String, String> SORTABLE = Map.of("title", "title", "date", "publishedAt");

    private static Pageable of(String sort, String direction, Integer page, Integer size) {
        return Paging.of(sort, direction, page, size, SORTABLE, "date");
    }

    private static void assertBadRequest(Throwable thrown, String messagePart) {
        assertThat(thrown).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getErrorCode()).isEqualTo("BAD_REQUEST");
            assertThat(e.getMessage()).contains(messagePart);
        });
    }

    @Test
    void usesTheDefaultSortKeyWhenNoneIsGiven() {
        Pageable pageable = of(null, null, null, null);
        assertThat(pageable.getSort().getOrderFor("publishedAt")).isNotNull();
    }

    @Test
    void mapsAnApiSortKeyToItsEntityAttribute() {
        Pageable pageable = of("title", "asc", null, null);
        assertThat(pageable.getSort().getOrderFor("title")).isNotNull();
        assertThat(pageable.getSort().getOrderFor("publishedAt")).isNull();
    }

    @Test
    void rejectsAnUnknownSortKey() {
        assertThatThrownBy(() -> of("password", null, null, null))
                .satisfies(thrown -> assertBadRequest(thrown, "sort must be one of"));
    }

    @Test
    void directionDefaultsToDescending() {
        Sort.Order order = of("title", null, null, null).getSort().getOrderFor("title");
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void directionAcceptsAscAndDescInAnyCase() {
        assertThat(of("title", "asc", null, null).getSort().getOrderFor("title").getDirection())
                .isEqualTo(Sort.Direction.ASC);
        assertThat(of("title", "ASC", null, null).getSort().getOrderFor("title").getDirection())
                .isEqualTo(Sort.Direction.ASC);
        assertThat(of("title", "desc", null, null).getSort().getOrderFor("title").getDirection())
                .isEqualTo(Sort.Direction.DESC);
        assertThat(of("title", "Desc", null, null).getSort().getOrderFor("title").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void rejectsAnyOtherDirection() {
        assertThatThrownBy(() -> of("title", "up", null, null))
                .satisfies(thrown -> assertBadRequest(thrown, "direction must be asc or desc"));
        assertThatThrownBy(() -> of("title", "", null, null))
                .satisfies(thrown -> assertBadRequest(thrown, "direction must be asc or desc"));
    }

    @Test
    void pageSizeDefaultsToTen() {
        assertThat(of(null, null, null, null).getPageSize()).isEqualTo(10);
    }

    @Test
    void pageSizeIsClampedToOneThroughOneHundred() {
        assertThat(of(null, null, null, 0).getPageSize()).isEqualTo(1);
        assertThat(of(null, null, null, -5).getPageSize()).isEqualTo(1);
        assertThat(of(null, null, null, 25).getPageSize()).isEqualTo(25);
        assertThat(of(null, null, null, 100).getPageSize()).isEqualTo(100);
        assertThat(of(null, null, null, 101).getPageSize()).isEqualTo(100);
        assertThat(of(null, null, null, Integer.MAX_VALUE).getPageSize()).isEqualTo(100);
    }

    @Test
    void pageDefaultsToZeroAndNegativePagesBecomeZero() {
        assertThat(of(null, null, null, null).getPageNumber()).isZero();
        assertThat(of(null, null, -3, null).getPageNumber()).isZero();
        assertThat(of(null, null, 4, null).getPageNumber()).isEqualTo(4);
    }

    @Test
    void rejectsAPageWhoseOffsetOverflowsAnInt() {
        assertThatThrownBy(() -> of(null, null, Integer.MAX_VALUE, 100))
                .satisfies(thrown -> assertBadRequest(thrown, "page is out of range"));
    }

    @Test
    void acceptsTheLastPageWhoseOffsetStillFitsAnInt() {
        // 21_474_836 * 100 = 2_147_483_600, just under Integer.MAX_VALUE.
        assertThat(of(null, null, 21_474_836, 100).getPageNumber()).isEqualTo(21_474_836);
    }

    @Test
    void addsTheIdTiebreakerAfterTheRequestedSort() {
        List<Sort.Order> orders = of("title", "desc", null, null).getSort().toList();
        assertThat(orders).hasSize(2);
        assertThat(orders.get(0).getProperty()).isEqualTo("title");
        assertThat(orders.get(0).getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(orders.get(1).getProperty()).isEqualTo("id");
        assertThat(orders.get(1).getDirection()).isEqualTo(Sort.Direction.ASC);
    }
}
