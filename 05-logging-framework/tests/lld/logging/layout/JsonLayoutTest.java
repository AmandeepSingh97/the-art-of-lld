package lld.logging.layout;

import lld.logging.core.LogEvent;
import lld.logging.core.LogLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonLayoutTest {

    @Test
    void producesValidLookingJsonWithEscapedMessage() {
        JsonLayout layout = new JsonLayout();
        LogEvent event = new LogEvent("com.app.Foo", LogLevel.WARN, "quote \" and \\ backslash", null);

        String json = layout.format(event);

        assertTrue(json.startsWith("{"));
        assertTrue(json.contains("\"level\":\"WARN\""));
        assertTrue(json.contains("\"logger\":\"com.app.Foo\""));
        assertTrue(json.contains("\\\"")); // escaped quote
        assertTrue(json.contains("\\\\")); // escaped backslash
        assertTrue(json.trim().endsWith("}"));
    }
}
