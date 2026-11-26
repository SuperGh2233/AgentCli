package com.paicli.plan;

import com.paicli.llm.GLMClient;
import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlannerTest {

    @Test
    void sendsEvenOneStepGoalsToLlmAndAcceptsSingleTaskPlan() throws Exception {
        StubGLMClient client = new StubGLMClient("""
                {
                  "summary": "列出当前目录",
                  "tasks": [
                    {"id": "list", "description": "列出当前目录的文件", "type": "COMMAND", "dependencies": []}
                  ]
                }
                """);
        Planner planner = new Planner(client);

        ExecutionPlan plan = planner.createPlan("列出当前目录的文件");

        assertEquals(1, client.calls, "一步就能完成的目标也由模型规划，不在调用前按关键词短路");
        assertEquals(List.of("task_1"), plan.getExecutionOrder());
        assertEquals("列出当前目录的文件", plan.getTask("task_1").getDescription());
    }

    @Test
    void doesNotTreatShortKeywordGoalsAsSimpleWithoutPlanning() throws Exception {
        for (String goal : List.of("删除 src 下所有文件", "执行 mvn test，修复失败的测试", "查看 README")) {
            StubGLMClient client = new StubGLMClient("""
                    {"summary": "s", "tasks": [{"id": "t", "description": "d", "type": "ANALYSIS", "dependencies": []}]}
                    """);

            new Planner(client).createPlan(goal);

            assertEquals(1, client.calls, goal);
        }
    }

    @Test
    void delegatesComplexGoalToLlmPlannerPath() throws Exception {
        StubGLMClient client = new StubGLMClient("""
                {
                  "summary": "复杂任务",
                  "tasks": [
                    {
                      "id": "task_a",
                      "description": "先读取 pom.xml",
                      "type": "FILE_READ",
                      "dependencies": []
                    },
                    {
                      "id": "task_b",
                      "description": "再验证项目结构",
                      "type": "VERIFICATION",
                      "dependencies": ["task_a"]
                    }
                  ]
                }
                """);
        Planner planner = new Planner(client);
        planner.setProjectMemorySupplier(() -> "## PAI.md 项目记忆\n- 计划前必须读取项目规则");

        ExecutionPlan plan = planner.createPlan("先读取 pom.xml 然后验证项目结构");

        assertEquals("复杂任务", plan.getSummary());
        assertEquals(2, plan.getAllTasks().size());
        assertTrue(plan.getTask("task_2").getDependencies().contains("task_1"));
        assertTrue(client.lastSystemPrompt.contains("计划前必须读取项目规则"));
    }

    @Test
    void proposesOnlyPendingTaskRevisionsAndIncludesCompletedContext() throws Exception {
        QueueGLMClient client = new QueueGLMClient(List.of("""
                {
                  "summary": "根据补充要求调整后续验证",
                  "revisions": [
                    {
                      "id": "task_3",
                      "description": "验证补充要求中的边界条件",
                      "type": "VERIFICATION",
                      "dependencies": ["task_1", "task_2"]
                    }
                  ]
                }
                """));
        Planner planner = new Planner(client);
        ExecutionPlan current = planForRevision();
        current.getTask("task_1").markCompleted("已读取核心配置");

        List<ExecutionPlan.PendingTaskRevision> revisions =
                planner.revisePendingTasks(current, "补充边界条件验证");

        assertEquals(1, client.calls);
        assertEquals(1, revisions.size());
        assertEquals("task_3", revisions.get(0).taskId());
        assertEquals("验证补充要求中的边界条件", revisions.get(0).description());
        assertEquals(Task.TaskType.VERIFICATION, revisions.get(0).type());
        assertEquals(List.of("task_1", "task_2"), revisions.get(0).dependencies());
        assertTrue(client.lastUserPrompt.contains("补充边界条件验证"));
        assertTrue(client.lastUserPrompt.contains("COMPLETED"));
        assertTrue(client.lastUserPrompt.contains("已读取核心配置"));
        assertTrue(client.lastUserPrompt.contains("PENDING"));
        assertTrue(client.lastUserPrompt.contains("只可修改"));
    }

    @Test
    void acceptsNoOpRevisionWithoutInventingTasks() throws Exception {
        QueueGLMClient client = new QueueGLMClient(List.of("""
                {"summary":"当前计划无需变化","revisions":[]}
                """));
        List<ExecutionPlan.PendingTaskRevision> revisions =
                new Planner(client).revisePendingTasks(planForRevision(), "不改变现有安排");
        assertTrue(revisions.isEmpty());
    }

    @Test
    void rejectsRevisionForUnknownOrCompletedTask() {
        QueueGLMClient unknown = new QueueGLMClient(List.of("""
                {"revisions":[{"id":"task_99","description":"x","type":"ANALYSIS","dependencies":[]}]}
                """));
        assertThrows(IOException.class,
                () -> new Planner(unknown).revisePendingTasks(planForRevision(), "update"));

        ExecutionPlan completedPlan = planForRevision();
        completedPlan.getTask("task_1").markCompleted("done");
        QueueGLMClient completed = new QueueGLMClient(List.of("""
                {"revisions":[{"id":"task_1","description":"rewrite","type":"ANALYSIS","dependencies":[]}]}
                """));
        assertThrows(IOException.class,
                () -> new Planner(completed).revisePendingTasks(completedPlan, "rewrite completed"));
    }

    @Test
    void rejectsMalformedRevisionSchemaAndUnknownTaskTypes() {
        for (String response : List.of(
                "{\"revisions\":[{\"id\":\"task_3\",\"description\":\"x\",\"type\":\"ANALYSIS\"}]}",
                "{\"revisions\":[{\"id\":\"task_3\",\"description\":\"x\",\"type\":\"UNKNOWN\",\"dependencies\":[]}]}",
                "{\"revisions\":[{\"id\":\"task_3\",\"description\":\"x\",\"type\":\"ANALYSIS\",\"dependencies\":[\"task_1\",\"task_1\"]}]}",
                "{\"revisions\":[]} trailing"
        )) {
            QueueGLMClient client = new QueueGLMClient(List.of(response));
            assertThrows(IOException.class,
                    () -> new Planner(client).revisePendingTasks(planForRevision(), "update"));
        }
    }

    private static ExecutionPlan planForRevision() {
        ExecutionPlan plan = new ExecutionPlan("revision-plan", "demo");
        Task first = new Task("task_1", "read source", Task.TaskType.FILE_READ);
        Task second = new Task("task_2", "read tests", Task.TaskType.FILE_READ);
        Task third = new Task("task_3", "summarize", Task.TaskType.ANALYSIS,
                List.of("task_1", "task_2"));
        plan.addTask(first);
        plan.addTask(second);
        plan.addTask(third);
        plan.computeExecutionOrder();
        return plan;
    }

    private static final class QueueGLMClient extends GLMClient {
        private final List<String> responses;
        private String lastUserPrompt = "";
        private int calls;

        private QueueGLMClient(List<String> responses) {
            super("test-key");
            this.responses = List.copyOf(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            lastUserPrompt = messages.get(1).content();
            if (calls >= responses.size()) {
                throw new AssertionError("Unexpected LLM call " + (calls + 1));
            }
            return new ChatResponse("assistant", responses.get(calls++), null, 100, 20);
        }
    }

    private static final class StubGLMClient extends GLMClient {
        private final String content;
        private String lastSystemPrompt = "";
        private int calls;

        private StubGLMClient(String content) {
            super("test-key");
            this.content = content;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            this.lastSystemPrompt = messages.get(0).content();
            calls++;
            return new ChatResponse("assistant", content, null, 100, 20);
        }
    }
}
