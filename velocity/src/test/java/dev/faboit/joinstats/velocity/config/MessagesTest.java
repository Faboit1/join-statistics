package dev.faboit.joinstats.velocity.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class MessagesTest {

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void substitutesPlaceholders() {
        Messages messages = new Messages();
        String rendered = plain(messages.render(messages.playerNotFound, "query", "Notch"));
        assertTrue(rendered.contains("Notch"), rendered);
        assertFalse(rendered.contains("<query>"), "the placeholder should be consumed");
    }

    @Test
    void resolvesTheColourTagsDefinedAtTheTopOfTheFile() {
        Messages messages = new Messages();
        Component rendered = messages.render("<good>ok</good> <bad>no</bad> <accent>hi</accent>");
        assertEquals("ok no hi", plain(rendered),
                "custom style tags must render as styling, not as literal text");
    }

    @Test
    void appliesTheConfiguredColourToItsTag() {
        Messages messages = new Messages();
        messages.good = "#00ff00";
        Component rendered = messages.render("<good>ok</good>");
        assertEquals(net.kyori.adventure.text.format.TextColor.fromCSSHexString("#00ff00"),
                rendered.children().isEmpty() ? rendered.color()
                        : rendered.children().get(0).color());
    }

    @Test
    void acceptsAColourWithoutItsLeadingHash() {
        Messages messages = new Messages();
        messages.accent = "ff8800";
        assertDoesNotThrow(() -> messages.render("<accent>styled</accent>"));
    }

    @Test
    void expandsThePrefix() {
        Messages messages = new Messages();
        messages.prefix = "<gray>[JS]</gray> ";
        assertTrue(plain(messages.render("<prefix>done")).startsWith("[JS] "));
    }

    @Test
    void treatsSubstitutedValuesAsTextNotMarkup() {
        // A username or a kick reason must never be able to inject MiniMessage tags.
        Messages messages = new Messages();
        String hostile = "<red><bold>gotcha</bold></red>";
        Component rendered = messages.render("<value><query></value>", "query", hostile);
        assertEquals(hostile, plain(rendered),
                "player-supplied text must come out verbatim, not be parsed as markup");
    }

    @Test
    void handlesAMissingPlaceholderWithoutThrowing() {
        Messages messages = new Messages();
        assertDoesNotThrow(() -> messages.render(messages.forgetConfirm));
    }

    @Test
    void everyShippedAlertTemplateRenders() {
        Messages messages = new Messages();
        for (String type : messages.alerts.keySet()) {
            String template = messages.alertTemplate(type);
            assertDoesNotThrow(() -> messages.render(template,
                            "player", "Alice", "others", "Bob", "kind", "VPN",
                            "network", "Example", "distance", "900", "elapsed", "5m",
                            "speed", "10800", "count", "6", "window", "2m",
                            "country", "Germany", "previous", "OldName",
                            "duration", "9h", "message", "fallback"),
                    "the shipped '" + type + "' template should render");
        }
    }

    @Test
    void anUnknownAlertTypeFallsBackToItsMessage() {
        Messages messages = new Messages();
        String rendered = plain(messages.render(messages.alertTemplate("something-new"),
                "message", "a thing happened"));
        assertTrue(rendered.contains("a thing happened"));
    }
}
