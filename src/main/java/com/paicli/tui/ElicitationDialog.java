package com.paicli.tui;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.*;

import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Small nonblocking modal with keyboard-accessible, scrollable full request text. */
final class ElicitationDialog extends BasicWindow {
    ElicitationDialog(String text, LanternaElicitation.Step step, TerminalSize screen, Consumer<String> answer) {
        super("MCP 补充输入");
        setHints(List.of(Hint.MODAL, Hint.CENTERED));
        setCloseWindowWithEscape(true);
        int width = Math.max(12, Math.min(76, screen.getColumns() - 8));
        int height = Math.max(2, Math.min(16, screen.getRows() - 12));
        Panel panel = new Panel(new LinearLayout(Direction.VERTICAL));
        panel.addComponent(new Label("Tab 切换；说明区域用方向键滚动；Esc 取消"));
        panel.addComponent(new TextBox(new TerminalSize(width, height), text, TextBox.Style.MULTI_LINE)
                .setReadOnly(true));
        TextBox input = new TextBox(new TerminalSize(width, 1))
                .setValidationPattern(Pattern.compile("[^\\p{Cntrl}]{0,2048}"));
        if (step == LanternaElicitation.Step.FIELD) panel.addComponent(input);
        Panel buttons = new Panel(new LinearLayout(Direction.HORIZONTAL));
        if (step == LanternaElicitation.Step.URL) {
            buttons.addComponent(new Button("Yes 打开浏览器", () -> finish("open")));
            buttons.addComponent(new Button("No 拒绝", () -> finish("decline")));
        } else if (step == LanternaElicitation.Step.REVIEW) {
            buttons.addComponent(new Button("Submit 提交", () -> finish("submit")));
            buttons.addComponent(new Button("Edit 修改", () -> finish("edit")));
            buttons.addComponent(new Button("Decline 拒绝", () -> finish("decline")));
        } else {
            buttons.addComponent(new Button("确定输入", () -> finish(input.getText())));
        }
        Button cancel = new Button("Cancel 取消", this::close);
        buttons.addComponent(cancel);
        panel.addComponent(buttons);
        setComponent(panel);
        setFocusedInteractable(step == LanternaElicitation.Step.FIELD ? input : cancel);
        // Covers Escape, window removal and cancellation without inventing a text command.
        this.answer = answer;
    }

    private final Consumer<String> answer;
    private boolean completed;

    private void finish(String value) {
        if (completed) return;
        completed = true;
        try { answer.accept(value); }
        finally { super.close(); }
    }

    @Override public void close() {
        if (completed) return;
        completed = true;
        try { answer.accept(null); }
        finally { super.close(); }
    }
}
