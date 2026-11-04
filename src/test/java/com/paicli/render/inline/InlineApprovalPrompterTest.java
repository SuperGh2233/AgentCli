package com.paicli.render.inline;

import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineApprovalPrompterTest {

    @Test
    void enterEchoIsReadableAndOtherControlKeysAreEncoded() throws Exception {
        for (char key : new char[]{'\n', '\r', '\u001B', '\u202E'}) {
            var sink = new ByteArrayOutputStream();
            var prompt = new InlineApprovalPrompter(new PrintStream(sink, true, StandardCharsets.UTF_8),
                    mockTerminalReturning(key), new BufferedReader(new StringReader("")));
            var result = prompt.prompt(ApprovalRequest.of("write_file", "{}", null));
            String output = sink.toString(StandardCharsets.UTF_8);
            boolean enter = key == '\n' || key == '\r';
            assertEquals(enter ? ApprovalResult.Decision.APPROVED : ApprovalResult.Decision.REJECTED, result.decision());
            assertTrue(output.contains(enter ? "Enter" : ApprovalRequest.encodeForDisplay(String.valueOf(key))));
            assertFalse(output.contains("<U+000A>"));
            assertFalse(output.contains("<U+000D>"));
            assertSafeApprovalDisplay(output);
        }
    }

    @Test
    void modifyPromptEncodesOriginalArgumentsWithoutChangingReturnedJson() throws Exception {
        String original = "foo\\bar\u001B[2J\r\n\u202E";
        String modified = "{\"command\":\"foo\\\\bar\u202E\"}";
        var sink = new ByteArrayOutputStream();
        var prompt = new InlineApprovalPrompter(new PrintStream(sink, true, StandardCharsets.UTF_8),
                mockTerminalReturning('m'), new BufferedReader(new StringReader(modified + "\n")));
        var request = ApprovalRequest.of("execute_command", original, null);
        var result = prompt.prompt(request);
        assertEquals(ApprovalResult.Decision.MODIFIED, result.decision());
        assertEquals(modified, result.modifiedArguments());
        assertEquals(original, request.arguments());
        String output = sink.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("当前参数: foo\\bar<U+001B>[2J<U+000D><U+000A><U+202E>"));
        assertSafeApprovalDisplay(output);
    }

    @Test
    void approveAllScopeSafelyDisplaysMcpServerAndPreservesRequest() throws Exception {
        String server = "demo\u001B[2J\r\n\u202E";
        String tool = "mcp__" + server + "__foo\\bar";
        for (String scope : new String[]{"tool", "server"}) {
            var sink = new ByteArrayOutputStream();
            var prompt = new InlineApprovalPrompter(new PrintStream(sink, true, StandardCharsets.UTF_8),
                    mockTerminalReturning('a'), new BufferedReader(new StringReader(scope + "\n")));
            var request = ApprovalRequest.of(tool, "{}", null);
            var result = prompt.prompt(request);
            assertEquals(scope.equals("tool") ? ApprovalResult.Decision.APPROVED_ALL
                    : ApprovalResult.Decision.APPROVED_ALL_BY_SERVER, result.decision());
            assertEquals(tool, request.toolName());
            assertEquals("{}", request.arguments());
            String output = sink.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("整个 MCP server " + ApprovalRequest.encodeForDisplay(server)));
            assertSafeApprovalDisplay(output);
        }
        var sink = new ByteArrayOutputStream();
        var prompt = new InlineApprovalPrompter(new PrintStream(sink, true, StandardCharsets.UTF_8),
                mockTerminalReturning('a'), new BufferedReader(new StringReader("")));
        assertEquals(ApprovalResult.Decision.APPROVED_ALL,
                prompt.prompt(ApprovalRequest.of("foo\\bar\u001B[2J\r\n\u202E", "{}", null)).decision());
        assertTrue(sink.toString(StandardCharsets.UTF_8).contains("后续 foo\\bar<U+001B>[2J<U+000D><U+000A><U+202E>"));
        assertSafeApprovalDisplay(sink.toString(StandardCharsets.UTF_8));
    }

    private static void assertSafeApprovalDisplay(String output) {
        // 允许渲染器自己生成的 SGR 颜色，其他原始控制字符都不能来自动态内容。
        output.replaceAll("\u001B\\[[0-9;]*m", "").codePoints().forEach(cp ->
                assertFalse((cp < 32 && cp != '\n') || (cp >= 0x7F && cp <= 0x9F) || cp == 0x202E));
    }

    @Test
    void singleCharYReturnsApprove() throws Exception {
        Terminal terminal = mockTerminalReturning('y');
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineApprovalPrompter p = new InlineApprovalPrompter(
                new PrintStream(sink, true, StandardCharsets.UTF_8),
                terminal,
                new BufferedReader(new StringReader("")));
        ApprovalResult result = p.prompt(ApprovalRequest.of("write_file", "{\"path\":\"a\"}", "test"));
        assertEquals(ApprovalResult.Decision.APPROVED, result.decision());
    }

    @Test
    void singleCharSReturnsSkip() throws Exception {
        Terminal terminal = mockTerminalReturning('s');
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineApprovalPrompter p = new InlineApprovalPrompter(
                new PrintStream(sink, true, StandardCharsets.UTF_8),
                terminal,
                new BufferedReader(new StringReader("")));
        ApprovalResult result = p.prompt(ApprovalRequest.of("write_file", "{}", "test"));
        assertEquals(ApprovalResult.Decision.SKIPPED, result.decision());
    }

    @Test
    void singleCharNFollowedByReasonReturnsRejected() throws Exception {
        Terminal terminal = mockTerminalReturning('n');
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineApprovalPrompter p = new InlineApprovalPrompter(
                new PrintStream(sink, true, StandardCharsets.UTF_8),
                terminal,
                new BufferedReader(new StringReader("too risky\n")));
        ApprovalResult result = p.prompt(ApprovalRequest.of("write_file", "{}", "test"));
        assertEquals(ApprovalResult.Decision.REJECTED, result.decision());
        assertEquals("too risky", result.reason());
    }

    @Test
    void singleCharAOnBuiltinToolApproveAll() throws Exception {
        Terminal terminal = mockTerminalReturning('a');
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineApprovalPrompter p = new InlineApprovalPrompter(
                new PrintStream(sink, true, StandardCharsets.UTF_8),
                terminal,
                new BufferedReader(new StringReader("")));
        ApprovalResult result = p.prompt(ApprovalRequest.of("write_file", "{}", "test"));
        assertEquals(ApprovalResult.Decision.APPROVED_ALL, result.decision());
    }

    @Test
    void singleCharAOnMcpToolApproveAllByServer() throws Exception {
        Terminal terminal = mockTerminalReturning('a');
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineApprovalPrompter p = new InlineApprovalPrompter(
                new PrintStream(sink, true, StandardCharsets.UTF_8),
                terminal,
                new BufferedReader(new StringReader("server\n")));
        ApprovalResult result = p.prompt(ApprovalRequest.of("mcp__chrome-devtools__click", "{}", "test"));
        assertEquals(ApprovalResult.Decision.APPROVED_ALL_BY_SERVER, result.decision());
    }

    @Test
    void singleCharMWithValidJsonReturnsModified() throws Exception {
        Terminal terminal = mockTerminalReturning('m');
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineApprovalPrompter p = new InlineApprovalPrompter(
                new PrintStream(sink, true, StandardCharsets.UTF_8),
                terminal,
                new BufferedReader(new StringReader("{\"path\":\"safe.txt\"}\n")));
        ApprovalResult result = p.prompt(ApprovalRequest.of("write_file", "{\"path\":\"a\"}", "test"));
        assertEquals(ApprovalResult.Decision.MODIFIED, result.decision());
        assertTrue(result.modifiedArguments().contains("safe.txt"));
    }

    @Test
    void rawModeFailureFallsThroughToReject() throws Exception {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.enterRawMode()).thenThrow(new RuntimeException("no tty"));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineApprovalPrompter p = new InlineApprovalPrompter(
                new PrintStream(sink, true, StandardCharsets.UTF_8),
                terminal,
                new BufferedReader(new StringReader("")));
        ApprovalResult result = p.prompt(ApprovalRequest.of("write_file", "{}", "test"));
        assertNotNull(result);
        assertEquals(ApprovalResult.Decision.REJECTED, result.decision());
    }

    private static Terminal mockTerminalReturning(char ch) throws Exception {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.enterRawMode()).thenReturn(null);
        NonBlockingReader reader = Mockito.mock(NonBlockingReader.class);
        Mockito.when(reader.read()).thenReturn((int) ch);
        Mockito.when(terminal.reader()).thenReturn(reader);
        return terminal;
    }
}
