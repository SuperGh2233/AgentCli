package com.paicli.hitl;

import java.util.regex.Pattern;

/**
 * Conservative matcher for simple shell command prefixes.
 * It deliberately refuses shell syntax rather than trying to parse Bash.
 */
final class CommandPrefixMatcher {
    private static final Pattern SIMPLE = Pattern.compile(
            "[A-Za-z0-9_./:@%+=,-]+(?:\\s+[A-Za-z0-9_./:@%+=,-]+)*");

    private CommandPrefixMatcher() { }

    static boolean isSafeSimpleCommand(String command) {
        return command != null && !command.isBlank()
                && command.codePoints().noneMatch(Character::isISOControl)
                && SIMPLE.matcher(command.strip()).matches();
    }

    static String normalizeWhitespace(String command) {
        return command == null ? "" : command.strip().replaceAll("\\s+", " ");
    }

    static boolean matches(String command, String prefix) {
        if (!isSafeSimpleCommand(command) || !isSafeSimpleCommand(prefix)) return false;
        String normalizedCommand = normalizeWhitespace(command);
        String normalizedPrefix = normalizeWhitespace(prefix);
        return normalizedCommand.equals(normalizedPrefix)
                || normalizedCommand.startsWith(normalizedPrefix + " ");
    }
}
