package in.moonman.hishaab.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CursorTest {

    @Test
    void roundTrips() {
        Cursor cursor = new Cursor("2026-11-17T10:15:30Z", "0195f0a2-7c1e-7a3b-9d4e-1234567890ab");
        String encoded = cursor.encode();

        assertThat(encoded).matches("[A-Za-z0-9_-]+");
        assertThat(Cursor.decode(encoded)).isEqualTo(cursor);
    }

    /** The last value is base64url of "no-separator". */
    @ParameterizedTest
    @ValueSource(strings = {"", "!!!not-base64!!!", "bm8tc2VwYXJhdG9y"})
    void garbageIsInvalidCursor(String encoded) {
        assertThatThrownBy(() -> Cursor.decode(encoded))
                .isInstanceOf(Cursor.InvalidCursorException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("INVALID_CURSOR"));
    }
}
