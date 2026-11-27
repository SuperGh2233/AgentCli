package com.haocli.headless;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** sandbox 权限模式：只在容器里能启用；启用后允许写文件和任意命令，其余模式不变。 */
class HeadlessSandboxModeTest {

    private final BooleanSupplier original = HeadlessOptions.containerCheck;

    @AfterEach
    void restore() {
        HeadlessOptions.containerCheck = original;
    }

    @Test
    void sandboxModeIsRefusedOutsideAContainer() {
        HeadlessOptions.containerCheck = () -> false;

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> HeadlessOptions.parse(new String[]{"-p", "task", "--permission-mode", "sandbox"}));
        assertTrue(error.getMessage().contains(HeadlessOptions.ALLOW_SANDBOX_ENV), error.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> UnattendedPermissions.fromArgs(new String[]{"--permission-mode", "sandbox"}));
    }

    @Test
    void sandboxModeInsideAContainerAllowsAnyCommandAndEdits() {
        HeadlessOptions.containerCheck = () -> true;

        HeadlessOptions options = HeadlessOptions.parse(new String[]{"-p", "task", "--permission-mode", "sandbox"});
        assertEquals(HeadlessOptions.PermissionMode.SANDBOX, options.permissionMode());

        HeadlessToolRegistry registry = new HeadlessToolRegistry(HeadlessOptions.PermissionMode.SANDBOX, List.of());
        assertNull(registry.denialReason("execute_command", "{\"command\":\"apt-get install -y jq && make test\"}"));
        assertNull(registry.denialReason("write_file", "{}"));
        assertTrue(registry.getToolDefinitions().stream().anyMatch(t -> t.name().equals("execute_command")));
        assertNotNull(registry.denialReason("save_memory", "{}"), "tools outside the lists stay denied");
    }

    @Test
    void otherModesAreUnchanged() {
        HeadlessToolRegistry readOnly = new HeadlessToolRegistry(HeadlessOptions.PermissionMode.READ_ONLY, List.of());
        assertNotNull(readOnly.denialReason("execute_command", "{\"command\":\"ls\"}"));
        assertNotNull(readOnly.denialReason("write_file", "{}"));
        assertTrue(readOnly.getToolDefinitions().stream().noneMatch(t -> t.name().equals("execute_command")));

        HeadlessToolRegistry edit = new HeadlessToolRegistry(HeadlessOptions.PermissionMode.EDIT, List.of("mvn -q compile"));
        assertNull(edit.denialReason("write_file", "{}"));
        assertNull(edit.denialReason("execute_command", "{\"command\":\"mvn -q compile\"}"));
        assertNotNull(edit.denialReason("execute_command", "{\"command\":\"rm -rf build\"}"));
    }
}
