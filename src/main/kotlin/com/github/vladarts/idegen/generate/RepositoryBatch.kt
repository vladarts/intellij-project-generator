package com.github.vladarts.idegen.generate

import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException

internal data class RepositoryResult(val entry: ProjectEntry, val cloned: Boolean, val error: String? = null)

/** Completion-order collection keeps a slow clone from blocking progress of local checks. */
internal fun prepareBatch(
    entries: List<ProjectEntry>,
    parallelism: Int,
    checkCanceled: () -> Unit,
    prepare: (ProjectEntry) -> Boolean,
    completed: (RepositoryResult) -> Unit
) {
    require(parallelism > 0)
    if (entries.isEmpty()) return
    val pool = Executors.newFixedThreadPool(parallelism.coerceAtMost(entries.size))
    val completion = ExecutorCompletionService<RepositoryResult>(pool)
    try {
        entries.forEach { entry ->
            checkCanceled()
            completion.submit(java.util.concurrent.Callable {
                try {
                    checkCanceled()
                    RepositoryResult(entry, prepare(entry))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw CancellationException("Repository worker interrupted")
                } catch (e: Exception) {
                    RepositoryResult(entry, false, e.message ?: "Repository preparation failed")
                }
            })
        }
        var count = 0
        while (count < entries.size) {
            checkCanceled()
            val future = completion.poll(100, TimeUnit.MILLISECONDS) ?: continue
            val result = try { future.get() } catch (e: ExecutionException) { throw e.cause ?: e }
            completed(result)
            count++
        }
    } finally {
        pool.shutdownNow()
        var interrupted = false
        while (!pool.isTerminated) {
            try { pool.awaitTermination(100, TimeUnit.MILLISECONDS) }
            catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
