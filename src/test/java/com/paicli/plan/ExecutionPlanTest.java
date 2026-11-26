package com.paicli.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

class ExecutionPlanTest {

    @Test
    void computeExecutionOrderRespectsDependencies() {
        ExecutionPlan plan = new ExecutionPlan("plan_1", "demo");
        Task task1 = new Task("task_1", "create project", Task.TaskType.COMMAND);
        Task task2 = new Task("task_2", "read pom", Task.TaskType.FILE_READ, List.of("task_1"));
        Task task3 = new Task("task_3", "verify structure", Task.TaskType.VERIFICATION, List.of("task_2"));

        plan.addTask(task1);
        plan.addTask(task2);
        plan.addTask(task3);

        assertEquals(List.of("task_1", "task_2", "task_3"), plan.getExecutionOrder());
    }

    @Test
    void executableTasksWaitUntilDependenciesComplete() {
        ExecutionPlan plan = new ExecutionPlan("plan_2", "demo");
        Task task1 = new Task("task_1", "create project", Task.TaskType.COMMAND);
        Task task2 = new Task("task_2", "read pom", Task.TaskType.FILE_READ, List.of("task_1"));

        plan.addTask(task1);
        plan.addTask(task2);

        assertEquals(List.of(task1), plan.getExecutableTasks());

        task1.markCompleted("done");

        assertEquals(List.of(task2), plan.getExecutableTasks());
    }

    @Test
    void addDependencyMutatesTaskState() {
        Task task = new Task("task_1", "read pom", Task.TaskType.FILE_READ);

        task.addDependency("task_0");

        assertEquals(List.of("task_0"), task.getDependencies());
    }

    @Test
    void addTaskBuildsDependentRelationship() {
        ExecutionPlan plan = new ExecutionPlan("plan_3", "demo");
        Task task1 = new Task("task_1", "create project", Task.TaskType.COMMAND);
        Task task2 = new Task("task_2", "read pom", Task.TaskType.FILE_READ, List.of("task_1"));

        plan.addTask(task1);
        plan.addTask(task2);

        assertTrue(plan.getTask("task_1").getDependents().contains("task_2"));
    }

    @Test
    void executableTasksCanExposeParallelBatch() {
        ExecutionPlan plan = new ExecutionPlan("plan_4", "demo");
        Task task1 = new Task("task_1", "read pom", Task.TaskType.FILE_READ);
        Task task2 = new Task("task_2", "list dir", Task.TaskType.COMMAND);
        Task task3 = new Task("task_3", "verify", Task.TaskType.VERIFICATION, List.of("task_1", "task_2"));

        plan.addTask(task1);
        plan.addTask(task2);
        plan.addTask(task3);

        assertEquals(List.of(task1, task2), plan.getExecutableTasks());

        task1.markCompleted("done");
        assertEquals(List.of(task2), plan.getExecutableTasks());

        task2.markCompleted("done");
        assertEquals(List.of(task3), plan.getExecutableTasks());
    }

    @Test
    void summarizeKeepsPlanPreviewCompact() {
        ExecutionPlan plan = new ExecutionPlan("plan_5",
                "请把任务拆成可并行的 DAG:\n1. 读取 pom.xml\n2. 列出 src/main/java");
        Task task1 = new Task("task_1", "read pom", Task.TaskType.FILE_READ);
        Task task2 = new Task("task_2", "list src main java", Task.TaskType.COMMAND);
        Task task3 = new Task("task_3", "summarize project", Task.TaskType.ANALYSIS, List.of("task_1", "task_2"));

        plan.addTask(task1);
        plan.addTask(task2);
        plan.addTask(task3);

        String summary = plan.summarize();

        assertTrue(summary.contains("任务数: 3 | 并行批次: 2 | 当前可执行: 2"));
        assertTrue(summary.contains("首批执行: task_1, task_2"));
        assertTrue(summary.contains("最终收敛: task_3"));
        assertTrue(!summary.contains("╔════════"));
    }

    @Test
    void pendingRevisionChangesOnlyWaitingNodeAndPreservesCompletedResults() {
        ExecutionPlan plan = new ExecutionPlan("plan-revise", "demo");
        Task a = new Task("a", "read a", Task.TaskType.FILE_READ);
        Task b = new Task("b", "read b", Task.TaskType.FILE_READ);
        Task c = new Task("c", "original analysis", Task.TaskType.ANALYSIS, List.of("a", "b"));
        plan.addTask(a);
        plan.addTask(b);
        plan.addTask(c);
        plan.computeExecutionOrder();
        a.markCompleted("result-a");
        b.markCompleted("result-b");

        plan.revisePendingTasks(List.of(new ExecutionPlan.PendingTaskRevision(
                "c", "revised verification", Task.TaskType.VERIFICATION, List.of("a"))));

        assertSame(a, plan.getTask("a"));
        assertSame(b, plan.getTask("b"));
        assertEquals(Task.TaskStatus.COMPLETED, a.getStatus());
        assertEquals("result-a", a.getResult());
        assertEquals("result-b", b.getResult());
        assertEquals("revised verification", plan.getTask("c").getDescription());
        assertEquals(Task.TaskType.VERIFICATION, plan.getTask("c").getType());
        assertEquals(List.of("a"), plan.getTask("c").getDependencies());
        assertEquals(List.of("c"), a.getDependents());
        assertEquals(List.of(), b.getDependents());
        assertEquals(List.of(plan.getTask("c")), plan.getExecutableTasks());
        assertEquals(2.0 / 3.0, plan.getProgress());
    }

    @Test
    void rejectsRevisionOfAlreadyCompletedStepWithoutChangingItsResult() {
        ExecutionPlan plan = new ExecutionPlan("completed", "demo");
        Task a = new Task("a", "finished", Task.TaskType.ANALYSIS);
        plan.addTask(a);
        plan.computeExecutionOrder();
        a.markCompleted("verified result");
        assertThrows(IllegalArgumentException.class, () -> plan.revisePendingTasks(List.of(
                new ExecutionPlan.PendingTaskRevision("a", "rewrite", Task.TaskType.COMMAND, List.of()))));
        assertSame(a, plan.getTask("a"));
        assertEquals("verified result", a.getResult());
        assertEquals(Task.TaskStatus.COMPLETED, a.getStatus());
    }

    @Test
    void runningBatchBlocksAnyRevisionUntilItsResultsAreSettled() {
        ExecutionPlan plan = new ExecutionPlan("running", "demo");
        Task a = new Task("a", "running", Task.TaskType.COMMAND);
        Task b = new Task("b", "pending", Task.TaskType.ANALYSIS, List.of("a"));
        plan.addTask(a);
        plan.addTask(b);
        plan.computeExecutionOrder();
        a.markStarted();
        assertThrows(IllegalStateException.class, () -> plan.revisePendingTasks(List.of(
                new ExecutionPlan.PendingTaskRevision("b", "update", Task.TaskType.ANALYSIS, List.of("a")))));
        assertSame(b, plan.getTask("b"));
        assertEquals("pending", b.getDescription());
    }

    @Test
    void invalidOrCyclicRevisionIsAtomicAndKeepsOriginalDag() {
        ExecutionPlan plan = new ExecutionPlan("atomic", "demo");
        Task a = new Task("a", "first", Task.TaskType.ANALYSIS);
        Task b = new Task("b", "second", Task.TaskType.ANALYSIS, List.of("a"));
        plan.addTask(a);
        plan.addTask(b);
        plan.computeExecutionOrder();
        List<String> order = plan.getExecutionOrder();

        assertThrows(IllegalArgumentException.class, () -> plan.revisePendingTasks(List.of(
                new ExecutionPlan.PendingTaskRevision("a", "changed", Task.TaskType.COMMAND, List.of("b")),
                new ExecutionPlan.PendingTaskRevision("b", "changed too", Task.TaskType.COMMAND, List.of("a")))));
        assertThrows(IllegalArgumentException.class, () -> plan.revisePendingTasks(List.of(
                new ExecutionPlan.PendingTaskRevision("b", "invalid", Task.TaskType.COMMAND, List.of("missing")))));
        assertThrows(IllegalArgumentException.class, () -> plan.revisePendingTasks(List.of(
                new ExecutionPlan.PendingTaskRevision("b", "first", Task.TaskType.COMMAND, List.of("a")),
                new ExecutionPlan.PendingTaskRevision("b", "duplicate", Task.TaskType.COMMAND, List.of("a")))));

        assertSame(a, plan.getTask("a"));
        assertSame(b, plan.getTask("b"));
        assertEquals("first", a.getDescription());
        assertEquals("second", b.getDescription());
        assertEquals(List.of(), a.getDependencies());
        assertEquals(List.of("a"), b.getDependencies());
        assertEquals(List.of("b"), a.getDependents());
        assertEquals(order, plan.getExecutionOrder());
    }

    @Test
    void revisionCannotBecomeDependentOnFailedOrSkippedWork() {
        ExecutionPlan plan = new ExecutionPlan("failed-dependency", "demo");
        Task failed = new Task("failed", "failed work", Task.TaskType.ANALYSIS);
        Task waiting = new Task("waiting", "waiting work", Task.TaskType.ANALYSIS);
        plan.addTask(failed);
        plan.addTask(waiting);
        plan.computeExecutionOrder();
        failed.markFailed("failed");
        assertThrows(IllegalArgumentException.class, () -> plan.revisePendingTasks(List.of(
                new ExecutionPlan.PendingTaskRevision("waiting", "unsafe", Task.TaskType.ANALYSIS, List.of("failed")))));
        assertEquals(List.of(), waiting.getDependencies());
        assertSame(waiting, plan.getTask("waiting"));
    }

    @Test
    void executionBatchesFollowDagLayers() {
        ExecutionPlan plan = new ExecutionPlan("plan_6", "demo");
        Task task1 = new Task("task_1", "read pom", Task.TaskType.FILE_READ);
        Task task2 = new Task("task_2", "list main", Task.TaskType.COMMAND);
        Task task3 = new Task("task_3", "list test", Task.TaskType.COMMAND);
        Task task4 = new Task("task_4", "read readme", Task.TaskType.FILE_READ);
        Task task5 = new Task("task_5", "summarize", Task.TaskType.ANALYSIS, List.of("task_1", "task_2", "task_3", "task_4"));

        plan.addTask(task1);
        plan.addTask(task2);
        plan.addTask(task3);
        plan.addTask(task4);
        plan.addTask(task5);

        List<List<Task>> batches = plan.getExecutionBatches();

        assertEquals(List.of(task1, task2, task3, task4), batches.get(0));
        assertEquals(List.of(task5), batches.get(1));
    }

    @Test
    void terminalOldDependenciesDoNotBlockIndependentPendingRevision() {
        ExecutionPlan plan = new ExecutionPlan("terminal-dependencies", "demo");
        Task failed = new Task("failed", "failed", Task.TaskType.ANALYSIS);
        Task skipped = new Task("skipped", "skipped", Task.TaskType.ANALYSIS, List.of("failed"));
        Task failedAfterSkip = new Task("failed-after-skip", "failed too", Task.TaskType.ANALYSIS, List.of("skipped"));
        Task waiting = new Task("waiting", "original", Task.TaskType.ANALYSIS);
        for (Task task : List.of(failed, skipped, failedAfterSkip, waiting)) plan.addTask(task);
        plan.computeExecutionOrder();
        failed.markFailed("original failure");
        plan.skipDependentsOf("failed");
        failedAfterSkip.markFailed("terminal failure");

        plan.revisePendingTasks(List.of(new ExecutionPlan.PendingTaskRevision(
                "waiting", "updated", Task.TaskType.ANALYSIS, List.of())));
        assertEquals("updated", plan.getTask("waiting").getDescription());
        assertSame(failed, plan.getTask("failed"));
        assertSame(skipped, plan.getTask("skipped"));
        assertSame(failedAfterSkip, plan.getTask("failed-after-skip"));
        assertEquals(Task.TaskStatus.SKIPPED, skipped.getStatus());
        assertEquals(Task.TaskStatus.FAILED, failedAfterSkip.getStatus());
        assertEquals(List.of("failed"), skipped.getDependencies());
        assertEquals(List.of("skipped"), failedAfterSkip.getDependencies());
        assertEquals("original failure", failed.getError());

        Task revised = plan.getTask("waiting");
        for (String invalidDependency : List.of("failed", "skipped", "missing", "waiting")) {
            assertThrows(IllegalArgumentException.class, () -> plan.revisePendingTasks(List.of(
                    new ExecutionPlan.PendingTaskRevision("waiting", "unsafe", Task.TaskType.ANALYSIS,
                            List.of(invalidDependency)))));
            assertSame(revised, plan.getTask("waiting"));
        }
    }
}
