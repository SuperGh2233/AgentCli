package com.paicli.mcp.elicitation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ElicitationPrompterTest {
    private static final ObjectMapper M = new ObjectMapper();

    private ElicitationRequest form() throws Exception {
        ObjectNode schema = (ObjectNode) M.readTree("""
                {
                  "type":"object",
                  "properties":{
                    "username":{"type":"string","title":"Username","minLength":2,"maxLength":30},
                    "age":{"type":"integer","minimum":18,"maximum":120}
                  },
                  "required":["username"]
                }
                """);
        return new ElicitationRequest("github", "login", ElicitationRequest.Mode.FORM,
                "Please provide a username", schema, null);
    }

    @Test
    void parsesFormRequestAndEncodesResponse() throws Exception {
        var entry = M.readTree("""
                {"method":"elicitation/create","params":{"message":"Who are you?","requestedSchema":{
                  "type":"object","properties":{"name":{"type":"string"}},"required":["name"]
                }}}
                """);
        ElicitationRequest request = ElicitationRequest.parse("demo", "identity", entry);
        assertEquals(ElicitationRequest.Mode.FORM, request.mode());
        assertEquals("demo", request.serverName());
        assertEquals("identity", request.requestKey());

        ObjectNode content = M.createObjectNode().put("name", "alice");
        var encoded = new ElicitationResponse(ElicitationResponse.Action.ACCEPT, content).toJson();
        assertEquals("accept", encoded.path("action").asText());
        assertEquals("alice", encoded.path("content").path("name").asText());
        assertFalse(new ElicitationResponse(ElicitationResponse.Action.DECLINE, null).toJson().has("content"));
    }

    @Test
    void rejectsUnsupportedMethodsAndSensitiveOrNestedSchemas() throws Exception {
        var sampling = M.readTree("""
                {"method":"sampling/createMessage","params":{}}
                """);
        assertThrows(IllegalArgumentException.class, () -> ElicitationRequest.parse("demo", "x", sampling));

        var secret = M.readTree("""
                {"type":"object","properties":{"apiKey":{"type":"string"}}}
                """);
        assertThrows(IllegalArgumentException.class, () ->
                new ElicitationRequest("demo", "x", ElicitationRequest.Mode.FORM, "Need a key", secret, null));

        var nested = M.readTree("""
                {"type":"object","properties":{"profile":{"type":"object","properties":{"name":{"type":"string"}}}}}
                """);
        assertThrows(IllegalArgumentException.class, () ->
                new ElicitationRequest("demo", "x", ElicitationRequest.Mode.FORM, "Profile", nested, null));
    }

    @Test
    void formCanBeReviewedEditedAndSubmitted() throws Exception {
        var answers = new ArrayDeque<>(java.util.List.of("alice", "21", "edit", "bob", "22", "submit"));
        StringBuilder output = new StringBuilder();
        ElicitationResponse response = ElicitationPrompter.prompt(form(), output,
                prompt -> answers.pollFirst(), uri -> fail("form mode must not open a browser"));

        assertEquals(ElicitationResponse.Action.ACCEPT, response.action());
        assertEquals("bob", response.content().path("username").asText());
        assertEquals(22, response.content().path("age").asInt());
        assertTrue(output.toString().contains("请检查即将发送"));
    }

    @Test
    void formRetriesInvalidValuesAgainstSchemaConstraints() throws Exception {
        var answers = new ArrayDeque<>(java.util.List.of("alice", "17", "21", "submit"));
        StringBuilder output = new StringBuilder();
        ElicitationResponse response = ElicitationPrompter.prompt(form(), output,
                prompt -> answers.pollFirst(), uri -> fail("form mode must not open a browser"));
        assertEquals(ElicitationResponse.Action.ACCEPT, response.action());
        assertEquals(21, response.content().path("age").asInt());
        assertTrue(output.toString().contains("不能小于 18"));
    }

    @Test
    void formSupportsTitledMultiSelectEnumArrays() throws Exception {
        var schema = M.readTree("""
                {"type":"object","properties":{"colors":{"type":"array","minItems":1,"maxItems":2,
                  "items":{"anyOf":[{"const":"#f00","title":"Red"},{"const":"#0f0","title":"Green"}]}}},
                  "required":["colors"]}
                """);
        var request = new ElicitationRequest("demo", "colors", ElicitationRequest.Mode.FORM,
                "Choose colors", schema, null);
        var answers = new ArrayDeque<>(java.util.List.of("#f00,#0f0", "submit"));
        StringBuilder output = new StringBuilder();
        ElicitationResponse response = ElicitationPrompter.prompt(request, output,
                prompt -> answers.pollFirst(), uri -> fail("form mode must not open a browser"));
        assertEquals(ElicitationResponse.Action.ACCEPT, response.action());
        assertEquals("#f00", response.content().path("colors").get(0).asText());
        assertTrue(output.toString().contains("Red (#f00)"));
    }

    @Test
    void formUsesUnicodeCodePointsForStringLength() throws Exception {
        var schema = M.readTree("""
                {"type":"object","properties":{"name":{"type":"string","minLength":2,"maxLength":2}},"required":["name"]}
                """);
        var request = new ElicitationRequest("demo", "unicode", ElicitationRequest.Mode.FORM,
                "Enter two characters", schema, null);
        var answers = new ArrayDeque<>(java.util.List.of("😀", "😀a", "submit"));
        StringBuilder output = new StringBuilder();
        ElicitationResponse response = ElicitationPrompter.prompt(request, output,
                prompt -> answers.pollFirst(), uri -> fail("form mode must not open a browser"));
        assertEquals(ElicitationResponse.Action.ACCEPT, response.action());
        assertEquals("😀a", response.content().path("name").asText());
        assertTrue(output.toString().contains("长度需在 2 到 2 之间"));
    }

    @Test
    void rejectsOverflowingSchemaBoundsAndDisplayControlCharacters() throws Exception {
        var overflow = M.readTree("""
                {"type":"object","properties":{"name":{"type":"string","maxLength":4294967297}}}
                """);
        assertThrows(IllegalArgumentException.class, () -> new ElicitationRequest(
                "demo", "overflow", ElicitationRequest.Mode.FORM, "Name", overflow, null));

        var bidi = M.readTree("""
                {"type":"object","properties":{"name":{"type":"string","title":"Name\\u202E"}}}
                """);
        assertThrows(IllegalArgumentException.class, () -> new ElicitationRequest(
                "demo", "bidi", ElicitationRequest.Mode.FORM, "Name", bidi, null));
    }

    @Test
    void formDeclineDoesNotReturnContent() throws Exception {
        var answers = new ArrayDeque<>(java.util.List.of("alice", "", "decline"));
        ElicitationResponse response = ElicitationPrompter.prompt(form(), new StringBuilder(),
                prompt -> answers.pollFirst(), uri -> fail("form mode must not open a browser"));
        assertEquals(ElicitationResponse.Action.DECLINE, response.action());
        assertNull(response.content());
    }

    @Test
    void urlModeOnlyOpensAfterExplicitConsent() throws Exception {
        ElicitationRequest request = new ElicitationRequest("payments", "authorize",
                ElicitationRequest.Mode.URL, "Complete authorization in the provider site",
                null, URI.create("https://example.test/authorize"));
        AtomicInteger opened = new AtomicInteger();
        ElicitationResponse declined = ElicitationPrompter.prompt(request, new StringBuilder(),
                prompt -> "decline", uri -> opened.incrementAndGet());
        assertEquals(ElicitationResponse.Action.DECLINE, declined.action());
        assertEquals(0, opened.get());

        ElicitationResponse accepted = ElicitationPrompter.prompt(request, new StringBuilder(),
                prompt -> "open", uri -> {
                    assertEquals(request.url(), uri);
                    opened.incrementAndGet();
                });
        assertEquals(ElicitationResponse.Action.ACCEPT, accepted.action());
        assertEquals(1, opened.get());
        assertNull(accepted.content());
    }

    @Test
    void urlModeRejectsUnsafeSchemesAndInvalidPorts() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new ElicitationRequest("demo", "port",
                ElicitationRequest.Mode.URL, "Open this", null, URI.create("https://example.test:99999/")));
        assertThrows(IllegalArgumentException.class, () -> new ElicitationRequest("demo", "scheme",
                ElicitationRequest.Mode.URL, "Open this", null, URI.create("javascript:alert(1)")));
    }

    @Test
    void browserFailureCancelsUrlMode() throws Exception {
        ElicitationRequest request = new ElicitationRequest("demo", "x",
                ElicitationRequest.Mode.URL, "Open this", null, URI.create("https://example.test/"));
        ElicitationResponse response = ElicitationPrompter.prompt(request, new StringBuilder(),
                prompt -> "open", uri -> { throw new java.io.IOException("browser unavailable"); });
        assertEquals(ElicitationResponse.Action.CANCEL, response.action());
    }

    @Test
    void requiredEmptyInputStillValidatesEnumAndFormat() throws Exception {
        for (String rule : java.util.List.of("\"enum\":[\"alice\"]", "\"format\":\"email\"")) {
            var schema = M.readTree("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"," + rule
                    + "}},\"required\":[\"name\"]}");
            var request = new ElicitationRequest("demo", "empty", ElicitationRequest.Mode.FORM, "Name", schema, null);
            String valid = rule.contains("enum") ? "alice" : "alice@example.test";
            var answers = new ArrayDeque<>(java.util.List.of("", valid, "submit"));
            var response = ElicitationPrompter.prompt(request, new StringBuilder(),
                    prompt -> answers.pollFirst(), uri -> fail("must not open browser"));
            assertEquals(ElicitationResponse.Action.ACCEPT, response.action());
            assertEquals(valid, response.content().path("name").asText());
        }
    }

    @Test
    void emailDefaultsUseSameValidationAsEnteredValues() throws Exception {
        var schema = M.readTree("""
                {"type":"object","properties":{"email":{"type":"string","format":"email","default":"alice@example.test"}}}
                """);
        var request = new ElicitationRequest("demo", "default", ElicitationRequest.Mode.FORM, "Email", schema, null);
        var answers = new ArrayDeque<>(java.util.List.of("", "submit"));
        var response = ElicitationPrompter.prompt(request, new StringBuilder(),
                prompt -> answers.pollFirst(), uri -> fail("must not open browser"));
        assertEquals(ElicitationResponse.Action.ACCEPT, response.action());
        assertEquals("alice@example.test", response.content().path("email").asText());
    }

    @Test
    void enumOneOfAndArrayDefaultsAreValidatedAndCopied() throws Exception {
        var schema = M.readTree("""
                {"type":"object","properties":{
                  "color":{"type":"string","oneOf":[{"const":"red","title":"Red"}],"default":"red"},
                  "tags":{"type":"array","items":{"type":"string","enum":["a","b"]},"minItems":1,"maxItems":2,"default":["a"]}
                }}
                """);
        var request = new ElicitationRequest("demo", "defaults", ElicitationRequest.Mode.FORM, "Choose", schema, null);
        ((ObjectNode) schema.path("properties").path("color")).put("default", "bad");
        ((ObjectNode) request.requestedSchema().path("properties").path("color")).put("default", "bad");
        var answers = new ArrayDeque<>(java.util.List.of("", "a,a", "a,b", "submit"));
        var response = ElicitationPrompter.prompt(request, new StringBuilder(),
                prompt -> answers.pollFirst(), uri -> fail("must not open browser"));
        assertEquals(ElicitationResponse.Action.ACCEPT, response.action());
        assertEquals("red", response.content().path("color").asText());
        assertEquals(2, response.content().path("tags").size());
        for (String field : java.util.List.of(
                "{\"type\":\"string\",\"enum\":[\"a\"],\"default\":\"b\"}",
                "{\"type\":\"array\",\"items\":{\"anyOf\":[{\"const\":\"a\"}]},\"default\":[\"b\"]}",
                "{\"type\":\"array\",\"items\":{\"type\":\"string\",\"enum\":[\"a\"]},\"default\":[\"a\",\"a\"]}",
                "{\"type\":\"string\",\"pattern\":\".*\"}")) {
            var invalid = M.readTree("{\"type\":\"object\",\"properties\":{\"choice\":" + field + "}}");
            assertThrows(IllegalArgumentException.class, () -> new ElicitationRequest(
                    "demo", "invalid", ElicitationRequest.Mode.FORM, "Choose", invalid, null));
        }
    }

    @Test
    void requestParsingRejectsWrongTypesAndMixedModes() throws Exception {
        for (String params : java.util.List.of(
                "{\"message\":1}", "{\"mode\":null,\"message\":\"Name\"}",
                "{\"mode\":\"url\",\"message\":\"Open\",\"url\":\"https://example.test\",\"requestedSchema\":null}",
                "{\"message\":\"Name\",\"url\":null}",
                "{\"mode\":\"url\",\"message\":\"Open\",\"url\":\"https://user@example.test\"}")) {
            var entry = M.readTree("{\"method\":\"elicitation/create\",\"params\":" + params + "}");
            assertThrows(IllegalArgumentException.class, () -> ElicitationRequest.parse("demo", "key", entry));
        }
    }

    @Test
    void missingInputAndDefaultHandlerFailClosed() throws Exception {
        ElicitationResponse response = ElicitationPrompter.prompt(form(), new StringBuilder(),
                prompt -> null, uri -> fail("browser must not open"));
        assertEquals(ElicitationResponse.Action.CANCEL, response.action());
        assertEquals(ElicitationResponse.Action.CANCEL,
                McpElicitationHandler.CANCEL.elicit(form()).action());
    }
}
