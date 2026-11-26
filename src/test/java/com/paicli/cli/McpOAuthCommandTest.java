package com.paicli.cli;

import com.paicli.mcp.McpServerManager;
import org.junit.jupiter.api.Test;
import java.util.function.Consumer;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class McpOAuthCommandTest {
    static class Manager extends McpServerManager {
        String action;
        Manager(){super(null,Path.of("/tmp"));}
        @Override public String login(String name,Consumer<String> output){action="login "+name;output.accept("browser");return "logged in";}
        @Override public String logout(String name){action="logout "+name;return "logged out";}
    }
    @Test void parserAndMainDispatchBothCommands() {
        try(var manager=new Manager()) {
            var login=CliCommandParser.parse("/mcp login remote");assertEquals(CliCommandParser.CommandType.MCP_LOGIN,login.type());
            assertEquals("logged in",Main.handleMcpOAuthCommand(login,manager,s->assertEquals("browser",s)));assertEquals("login remote",manager.action);
            var logout=CliCommandParser.parse("/mcp logout remote");assertEquals(CliCommandParser.CommandType.MCP_LOGOUT,logout.type());
            assertEquals("logged out",Main.handleMcpOAuthCommand(logout,manager,s->fail()));assertEquals("logout remote",manager.action);
            assertTrue(Main.handleMcpOAuthCommand(CliCommandParser.parse("/mcp login"),manager,s->fail()).contains("用法"));
            assertTrue(Main.handleMcpOAuthCommand(CliCommandParser.parse("/mcp logout a b"),manager,s->fail()).contains("用法"));
        }
    }
}
