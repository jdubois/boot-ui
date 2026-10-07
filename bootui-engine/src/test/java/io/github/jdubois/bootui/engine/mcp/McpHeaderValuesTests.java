package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class McpHeaderValuesTests {

    @Test
    void plainAsciiValuesAreReturnedUnchanged() {
        assertThat(McpHeaderValues.decode("get_overview")).isEqualTo("get_overview");
        assertThat(McpHeaderValues.decode("a b\tc")).isEqualTo("a b\tc");
        assertThat(McpHeaderValues.decode("")).isEmpty();
    }

    @Test
    void base64SentinelValuesAreDecodedAsUtf8() {
        // The specification's own examples.
        assertThat(McpHeaderValues.decode("=?base64?SGVsbG8sIOS4lueVjA==?=")).isEqualTo("Hello, 世界");
        assertThat(McpHeaderValues.decode("=?base64?IHBhZGRlZCA=?=")).isEqualTo(" padded ");
        assertThat(McpHeaderValues.decode("=?base64?bGluZTEKbGluZTI=?=")).isEqualTo("line1\nline2");
        assertThat(McpHeaderValues.decode("=?base64?PT9iYXNlNjQ/bGl0ZXJhbD89?="))
                .isEqualTo("=?base64?literal?=");
    }

    @Test
    void malformedValuesAreRejected() {
        assertThat(McpHeaderValues.decode(null)).isNull();
        assertThat(McpHeaderValues.decode(" leading")).isNull();
        assertThat(McpHeaderValues.decode("trailing ")).isNull();
        assertThat(McpHeaderValues.decode("caf\u00e9"))
                .as("non-ASCII must be Base64-encoded")
                .isNull();
        assertThat(McpHeaderValues.decode("line\nbreak")).isNull();
        assertThat(McpHeaderValues.decode("=?base64?not base64!?=")).isNull();
        assertThat(McpHeaderValues.decode("=?base64?/w==?="))
                .as("invalid UTF-8")
                .isNull();
        assertThat(McpHeaderValues.decode("=?BASE64?Zm9v?="))
                .as("markers are case-sensitive")
                .isEqualTo("=?BASE64?Zm9v?=");
    }
}
