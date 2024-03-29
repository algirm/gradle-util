package id.northbit.gradle

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.execution.TaskExecutionGraph
import org.gradle.api.logging.Logger
import org.gradle.kotlin.dsl.extra

private const val PROPERTY_NAME = "id.northbit.gradle.disableTasks"

fun Project.ensureUnreachableTasksDisabled() {
    checkIsRootProject()

    if (extra.has(PROPERTY_NAME)) {
        return
    }

    extra.set(PROPERTY_NAME, true)

    gradle.taskGraph.whenReady {
        DisableTasks(graph = this, logger = logger)
            .disableTasks()
    }
}

private class DisableTasks(
    private val graph: TaskExecutionGraph,
    private val logger: Logger,
) {
    private val rootTasks = findRootTasks()
    private val results = HashMap<Pair<Task, Task>, Boolean>()

    private fun findRootTasks(): List<Task> {
        val rootTasks = ArrayList<Task>()

        val children = HashSet<Task>()
        graph.allTasks.forEach {
            children += graph.getDependencies(it)
        }

        graph.allTasks.forEach {
            if (it !in children) {
                rootTasks += it
            }
        }

        return rootTasks
    }

    fun disableTasks() {
        graph
            .allTasks
            .filterNot { it.enabled }
            .forEach { disableChildren(it) }
    }

    private fun disableChildren(task: Task) {
        graph.getDependencies(task).forEach { child ->
            if (child.enabled) {
                if (!isTaskAccessible(task = child)) {
                    child.enabled = false
                    logger.info("Task disabled: ${child.path}")
                    disableChildren(task = child)
                } else {
                    logger.info("Task accessible: ${child.path}")
                }
            } else {
                logger.info("Task already disabled: ${child.path}")
                disableChildren(task = child)
            }
        }
    }

    private fun isTaskAccessible(task: Task): Boolean =
        rootTasks.any {
            val isPathExists = (it != task) && isPathExists(source = it, destination = task)

            if (isPathExists) {
                logger.info("Task $task accessible from $it")
            }

            isPathExists
        }

    private fun isPathExists(source: Task, destination: Task): Boolean =
        results.getOrPut(source to destination) {
            when {
                !source.enabled -> false
                source == destination -> true.also { logger.info("Task reached: $destination") }

                else -> graph.getDependencies(source).any { isPathExists(source = it, destination = destination) }.also {
                    if (it) {
                        logger.info("Task path found from $source to $destination")
                    }
                }
            }
        }
}
