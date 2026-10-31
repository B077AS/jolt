package io.github.b077as.jolt.win32;

import io.github.b077as.jolt.Notification;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The XML handed to Windows must be well-formed whatever the text contains, or the toast is not shown at all. */
class WindowsToastXmlTest {

    private static Document parse(String xml) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void textWithMarkupAndControlCharactersStaysWellFormed() throws Exception {
        String nasty = "a<b>&\"c'\u0000\u001b d";
        Document doc = parse(WindowsToasts.toastXml(Notification.of(nasty, nasty)));
        NodeList texts = doc.getElementsByTagName("text");
        assertEquals("a<b>&\"c' d", texts.item(0).getTextContent());
        assertEquals("a<b>&\"c' d", texts.item(1).getTextContent());
    }

    @Test
    void buttonsAndReplyBecomeActionsThatCarryTheirOwnArguments() throws Exception {
        Notification n = Notification.of("t", "b").withOnClick(() -> { })
                .withButton("Yes \"sure\"", () -> { }).withButton("No", () -> { })
                .withReply("Send", "say <hi>", text -> { });
        Document doc = parse(WindowsToasts.toastXml(n));
        assertEquals("jolt:body", doc.getDocumentElement().getAttribute("launch"));

        Element input = (Element) doc.getElementsByTagName("input").item(0);
        assertEquals("say <hi>", input.getAttribute("placeHolderContent"));

        NodeList actions = doc.getElementsByTagName("action");
        assertEquals(3, actions.getLength());
        Element reply = (Element) actions.item(0);
        assertEquals("jolt:reply", reply.getAttribute("arguments"));
        assertEquals(input.getAttribute("id"), reply.getAttribute("hint-inputId"));
        assertEquals("Yes \"sure\"", ((Element) actions.item(1)).getAttribute("content"));
        assertEquals("jolt:button:0", ((Element) actions.item(1)).getAttribute("arguments"));
        assertEquals("jolt:button:1", ((Element) actions.item(2)).getAttribute("arguments"));
    }

    @Test
    void aSettingWindowCannotReadDoesNotBlockTheToast() {
        assertEquals(0, WindowsToasts.settingValue(0x80070490, 0), "ERROR_NOT_FOUND (an app with no settings entry yet) means try anyway");
        assertEquals(0, WindowsToasts.settingValue(0x80070490, 2), "a value that was not filled in must not be believed");
        assertEquals(2, WindowsToasts.settingValue(0, 2), "a setting Windows did report (off for the user) is respected");
        assertEquals(0, WindowsToasts.settingValue(0, 0));
    }

    @Test
    void aPlainNotificationHasNoActionsElement() throws Exception {
        assertEquals(0, parse(WindowsToasts.toastXml(Notification.of("t"))).getElementsByTagName("actions").getLength());
    }
}
