package com.paicli.cli;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class JLineInputAdapterTest {
    private static final TerminalInputGateway.Request REQUEST = new TerminalInputGateway.Request(
            "request", TerminalInputGateway.Kind.APPROVAL_CHOICE, "approve> ");

    @Test
    void preservesTextAndRealEmptyEnter() throws Exception {
        for (String line : new String[]{"", "中文\n🚀"}) {
            var adapter = new JLineInputAdapter(reader(() -> line));
            assertEquals(line, adapter.read(REQUEST));
        }
    }

    @Test
    void eofIsNullAndControlCIsNeverEmptyEnter() {
        var eof = new JLineInputAdapter(reader(() -> { throw new EndOfFileException(); }));
        assertNull(eof.read(REQUEST));
        var cancel = new JLineInputAdapter(reader(() -> { throw new UserInterruptException("草稿"); }));
        assertEquals("草稿", assertThrows(UserInterruptException.class, () -> cancel.read(REQUEST)).getPartialLine());
    }

    @Test
    void workerCannotReadAndReentrantReadIsRejected() throws Exception {
        var adapter = new JLineInputAdapter(reader(() -> fail("worker 不得调用 JLine")));
        var worker = Executors.newSingleThreadExecutor();
        try {
            assertTrue(worker.submit(() -> assertThrows(IllegalStateException.class,
                    () -> adapter.read(REQUEST)) != null).get());
        } finally { worker.shutdownNow(); }
        JLineInputAdapter[] nested = new JLineInputAdapter[1];
        nested[0] = new JLineInputAdapter(reader(() -> nested[0].read(REQUEST)));
        assertThrows(IllegalStateException.class, () -> nested[0].read(REQUEST));
    }

    private static LineReader reader(java.util.function.Supplier<String> input) {
        return (LineReader) Proxy.newProxyInstance(LineReader.class.getClassLoader(), new Class<?>[]{LineReader.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("readLine")) return input.get();
                    throw new AssertionError("adapter 不应访问 " + method.getName());
                });
    }
}
