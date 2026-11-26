package com.paicli.plan;

import java.util.*;

/**
 * 执行计划 - 包含一组有依赖关系的任务
 */
public class ExecutionPlan {
    private final String id;
    private final String goal;                    // 计划目标
    private final Map<String, Task> tasks;        // 所有任务
    private final List<String> executionOrder;    // 执行顺序（拓扑排序后）
    private PlanStatus status;
    private String summary;                       // 计划摘要

    public enum PlanStatus {
        CREATED,      // 刚创建
        RUNNING,      // 执行中
        COMPLETED,    // 全部完成
        FAILED,       // 有任务失败
        CANCELLED     // 被取消
    }

    public ExecutionPlan(String id, String goal) {
        this.id = id;
        this.goal = goal;
        this.tasks = new LinkedHashMap<>();  // 保持插入顺序
        this.executionOrder = new ArrayList<>();
        this.status = PlanStatus.CREATED;
    }

    // Getters
    public String getId() { return id; }
    public String getGoal() { return goal; }
    public PlanStatus getStatus() { return status; }
    public String getSummary() { return summary; }

    public void setSummary(String summary) { this.summary = summary; }

    /**
     * 添加任务
     */
    public void addTask(Task task) {
        tasks.put(task.getId(), task);
        // 更新依赖关系
        for (String depId : task.getDependencies()) {
            Task dep = tasks.get(depId);
            if (dep != null) {
                dep.addDependent(task.getId());
            }
        }
    }

    /**
     * 对一个尚未开始的任务提出修订。调用方必须在当前执行批次全部收尾后使用。
     */
    public record PendingTaskRevision(String taskId, String description,
                                      Task.TaskType type, List<String> dependencies) {
        public PendingTaskRevision {
            if (taskId == null || taskId.isBlank()) {
                throw new IllegalArgumentException("修订任务 ID 不能为空");
            }
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("修订后的任务描述不能为空");
            }
            Objects.requireNonNull(type, "任务类型不能为空");
            dependencies = List.copyOf(Objects.requireNonNull(dependencies, "任务依赖不能为空"));
            if (new HashSet<>(dependencies).size() != dependencies.size()) {
                throw new IllegalArgumentException("任务依赖不能重复");
            }
        }
    }

    /**
     * 原子修订待执行节点。已完成节点对象及其结果保持不变；
     * 校验全部通过后才替换任务描述、类型和依赖关系。
     */
    public synchronized void revisePendingTasks(List<PendingTaskRevision> revisions) {
        Objects.requireNonNull(revisions, "任务修订列表不能为空");
        if (revisions.isEmpty()) {
            throw new IllegalArgumentException("至少需要一项任务修订");
        }
        if (status != PlanStatus.CREATED && status != PlanStatus.RUNNING) {
            throw new IllegalStateException("当前计划状态不允许修订：" + status);
        }
        if (tasks.values().stream().anyMatch(task -> task.getStatus() == Task.TaskStatus.RUNNING)) {
            throw new IllegalStateException("当前执行批次尚未结束，不能修订计划");
        }

        Map<String, Task> proposedTasks = new LinkedHashMap<>(tasks);
        Set<String> revisedIds = new HashSet<>();
        for (PendingTaskRevision revision : revisions) {
            Objects.requireNonNull(revision, "任务修订项不能为空");
            if (!revisedIds.add(revision.taskId())) {
                throw new IllegalArgumentException("同一任务不能在一次修订中重复出现：" + revision.taskId());
            }
            Task current = tasks.get(revision.taskId());
            if (current == null) {
                throw new IllegalArgumentException("修订目标不存在：" + revision.taskId());
            }
            if (current.getStatus() != Task.TaskStatus.PENDING) {
                throw new IllegalArgumentException("只能修订尚未开始的任务：" + revision.taskId());
            }
            proposedTasks.put(revision.taskId(), new Task(
                    revision.taskId(), revision.description(), revision.type(), revision.dependencies()));
        }

        for (Task task : proposedTasks.values()) {
            for (String dependencyId : task.getDependencies()) {
                Task dependency = proposedTasks.get(dependencyId);
                if (dependency == null) {
                    throw new IllegalArgumentException("任务 " + task.getId()
                            + " 依赖不存在的任务：" + dependencyId);
                }
                // 终止节点的旧依赖保留作执行记录，不妨碍独立 PENDING 节点修订。
                if (task.getStatus() != Task.TaskStatus.FAILED
                        && task.getStatus() != Task.TaskStatus.SKIPPED
                        && (dependency.getStatus() == Task.TaskStatus.FAILED
                        || dependency.getStatus() == Task.TaskStatus.SKIPPED)) {
                    throw new IllegalArgumentException("任务 " + task.getId()
                            + " 不能依赖失败或已跳过的任务：" + dependencyId);
                }
            }
        }

        List<String> proposedOrder = buildExecutionOrder(proposedTasks);
        if (proposedOrder == null) {
            throw new IllegalArgumentException("修订后的任务依赖存在循环，已保留原计划");
        }

        // 所有校验通过后再更新反向依赖，避免校验失败留下半套关系。
        for (Task task : tasks.values()) {
            for (String dependentId : task.getDependents()) {
                task.removeDependent(dependentId);
            }
        }
        tasks.putAll(proposedTasks);
        for (Task task : tasks.values()) {
            for (String dependencyId : task.getDependencies()) {
                tasks.get(dependencyId).addDependent(task.getId());
            }
        }
        executionOrder.clear();
        executionOrder.addAll(proposedOrder);
    }

    private static List<String> buildExecutionOrder(Map<String, Task> candidateTasks) {
        List<String> order = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (Task task : candidateTasks.values()) {
            if (!visited.contains(task.getId())
                    && !topologicalSort(task, candidateTasks, visited, visiting, order)) {
                return null;
            }
        }
        return order;
    }

    private static boolean topologicalSort(Task task, Map<String, Task> candidateTasks,
                                           Set<String> visited, Set<String> visiting,
                                           List<String> order) {
        String taskId = task.getId();
        if (visiting.contains(taskId)) {
            return false;
        }
        if (visited.contains(taskId)) {
            return true;
        }
        visiting.add(taskId);
        for (String dependencyId : task.getDependencies()) {
            Task dependency = candidateTasks.get(dependencyId);
            if (dependency == null
                    || !topologicalSort(dependency, candidateTasks, visited, visiting, order)) {
                return false;
            }
        }
        visiting.remove(taskId);
        visited.add(taskId);
        order.add(taskId);
        return true;
    }

    /**
     * 获取任务
     */
    public Task getTask(String id) {
        return tasks.get(id);
    }

    /**
     * 获取所有任务
     */
    public Collection<Task> getAllTasks() {
        return tasks.values();
    }

    /**
     * 获取可执行的任务（依赖都已完成）
     */
    public List<Task> getExecutableTasks() {
        return tasks.values().stream()
                .filter(t -> t.isExecutable(tasks))
                .toList();
    }

    /**
     * 计算拓扑排序执行顺序
     */
    public boolean computeExecutionOrder() {
        executionOrder.clear();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();

        for (Task task : tasks.values()) {
            if (!visited.contains(task.getId())) {
                if (!topologicalSort(task, visited, visiting)) {
                    return false;  // 有环
                }
            }
        }

        return true;
    }

    private boolean topologicalSort(Task task, Set<String> visited, Set<String> visiting) {
        String id = task.getId();

        if (visiting.contains(id)) {
            return false;  // 有环
        }
        if (visited.contains(id)) {
            return true;
        }

        visiting.add(id);

        for (String depId : task.getDependencies()) {
            Task dep = tasks.get(depId);
            if (dep != null) {
                if (!topologicalSort(dep, visited, visiting)) {
                    return false;
                }
            }
        }

        visiting.remove(id);
        visited.add(id);
        executionOrder.add(id);
        return true;
    }

    /**
     * 获取执行顺序
     */
    public List<String> getExecutionOrder() {
        if (executionOrder.isEmpty()) {
            computeExecutionOrder();
        }
        return new ArrayList<>(executionOrder);
    }

    /**
     * 获取执行进度
     */
    public double getProgress() {
        if (tasks.isEmpty()) return 1.0;
        long completed = tasks.values().stream()
                .filter(t -> t.getStatus() == Task.TaskStatus.COMPLETED)
                .count();
        return (double) completed / tasks.size();
    }

    /**
     * 是否全部完成
     */
    public boolean isAllCompleted() {
        return tasks.values().stream()
                .allMatch(t -> t.getStatus() == Task.TaskStatus.COMPLETED);
    }

    /**
     * 把直接或间接依赖某个失败任务、仍处于 PENDING 的任务标记为 SKIPPED。
     * 返回按执行顺序排列的被跳过任务，调用方据此向用户说明哪些工作没有执行。
     */
    public List<Task> skipDependentsOf(String failedTaskId) {
        Set<String> blocked = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(failedTaskId));
        while (!queue.isEmpty()) {
            Task current = tasks.get(queue.poll());
            if (current == null) {
                continue;
            }
            for (String dependentId : current.getDependents()) {
                if (blocked.add(dependentId)) {
                    queue.add(dependentId);
                }
            }
        }
        return skipPendingMatching(blocked::contains);
    }

    /** 停止执行时把所有尚未开始的任务标记为 SKIPPED。 */
    public List<Task> skipAllPending() {
        return skipPendingMatching(id -> true);
    }

    private List<Task> skipPendingMatching(java.util.function.Predicate<String> selector) {
        List<Task> skipped = new ArrayList<>();
        for (String id : orderedIds()) {
            Task task = tasks.get(id);
            if (task != null && selector.test(id) && task.getStatus() == Task.TaskStatus.PENDING) {
                task.markSkipped();
                skipped.add(task);
            }
        }
        return List.copyOf(skipped);
    }

    private List<String> orderedIds() {
        return executionOrder.isEmpty() ? new ArrayList<>(tasks.keySet()) : new ArrayList<>(executionOrder);
    }

    /**
     * 是否有失败任务
     */
    public boolean hasFailed() {
        return tasks.values().stream()
                .anyMatch(t -> t.getStatus() == Task.TaskStatus.FAILED);
    }

    /**
     * 标记开始执行
     */
    public void markStarted() {
        this.status = PlanStatus.RUNNING;
    }

    /**
     * 标记完成
     */
    public void markCompleted() {
        this.status = PlanStatus.COMPLETED;
    }

    /**
     * 标记失败
     */
    public void markFailed() {
        this.status = PlanStatus.FAILED;
    }

    /**
     * 标记取消：用户按 ESC 中断执行
     */
    public void markCancelled() {
        this.status = PlanStatus.CANCELLED;
    }

    /**
     * 可视化计划
     */
    public String visualize() {
        StringBuilder sb = new StringBuilder();
        sb.append("╔══════════════════════════════════════════════════════════╗\n");
        sb.append(String.format("║  执行计划: %-46s║%n", goal.length() > 46 ? goal.substring(0, 43) + "..." : goal));
        sb.append("╠══════════════════════════════════════════════════════════╣\n");

        List<String> order = getExecutionOrder();
        for (int i = 0; i < order.size(); i++) {
            String taskId = order.get(i);
            Task task = tasks.get(taskId);
            String statusIcon = getStatusIcon(task.getStatus());
            String deps = task.getDependencies().isEmpty() ? "无" :
                    String.join(",", task.getDependencies());

            sb.append(String.format("║  %d. %s %-20s ", i + 1, statusIcon, task.getId()));
            sb.append(String.format("[%-10s] 依赖: %-15s║%n",
                    task.getType(), deps));
            String desc = task.getDescription().length() > 50
                    ? task.getDescription().substring(0, 47) + "..."
                    : task.getDescription();
            sb.append(String.format("║     %-53s║%n", desc));
        }

        sb.append("╚══════════════════════════════════════════════════════════╝\n");
        sb.append(String.format("   进度: %.0f%% | 状态: %s%n",
                getProgress() * 100, status));

        return sb.toString();
    }

    /**
     * 默认折叠展示，避免完整 DAG 占满终端。
     */
    public String summarize() {
        List<List<Task>> batches = getExecutionBatches();
        List<Task> readyTasks = getExecutableTasks();
        StringBuilder sb = new StringBuilder();
        sb.append("📋 计划摘要\n");
        sb.append("   - 目标: ").append(compactGoal(goal, 48)).append('\n');
        sb.append("   - 任务数: ").append(tasks.size())
                .append(" | 并行批次: ").append(batches.size())
                .append(" | 当前可执行: ").append(readyTasks.size())
                .append(" | 状态: ").append(status).append('\n');

        if (!batches.isEmpty()) {
            sb.append("   - 首批执行: ").append(formatTaskList(batches.get(0), 5)).append('\n');
            if (batches.size() > 1) {
                sb.append("   - 最终收敛: ")
                        .append(formatTaskList(batches.get(batches.size() - 1), 5))
                        .append('\n');
            }
        }

        return sb.toString();
    }

    public List<List<Task>> getExecutionBatches() {
        if (tasks.isEmpty()) {
            return List.of();
        }

        Map<String, Task> remaining = new LinkedHashMap<>(tasks);
        Set<String> completed = new HashSet<>();
        List<List<Task>> batches = new ArrayList<>();

        while (!remaining.isEmpty()) {
            List<Task> batch = remaining.values().stream()
                    .filter(task -> completed.containsAll(task.getDependencies()))
                    .toList();

            if (batch.isEmpty()) {
                break;
            }

            batches.add(batch);
            for (Task task : batch) {
                remaining.remove(task.getId());
                completed.add(task.getId());
            }
        }

        return batches;
    }

    private String compactGoal(String rawGoal, int maxLength) {
        String singleLineGoal = rawGoal
                .replace("\r\n", " ")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim()
                .replaceAll(" {2,}", " ");
        if (singleLineGoal.length() <= maxLength) {
            return singleLineGoal;
        }
        return singleLineGoal.substring(0, maxLength - 3) + "...";
    }

    private String formatTaskList(List<Task> batch, int limit) {
        if (batch.isEmpty()) {
            return "无";
        }

        List<String> taskIds = batch.stream()
                .map(Task::getId)
                .toList();

        if (taskIds.size() <= limit) {
            return String.join(", ", taskIds);
        }

        return String.join(", ", taskIds.subList(0, limit)) + " 等 " + taskIds.size() + " 个任务";
    }

    private String getStatusIcon(Task.TaskStatus status) {
        return switch (status) {
            case PENDING -> "⏳";
            case RUNNING -> "▶️";
            case COMPLETED -> "✅";
            case FAILED -> "❌";
            case SKIPPED -> "⏭️";
        };
    }

    @Override
    public String toString() {
        return String.format("ExecutionPlan[%s: %s] (%d tasks, %s)",
                id, goal, tasks.size(), status);
    }
}
