package com.timeagent.benchmark

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import java.io.FileOutputStream

/**
 * Benchmark-variant-only fixture seeder (no Hilt, no DI graph).
 *
 * Lives in `src/benchmark`, so debug/release APKs never contain it and production data is
 * never read or shared. Seeding runs at provider creation, before Application.onCreate,
 * copies pre-generated synthetic DBs only when the target file is absent, and never
 * replaces an existing file. Schema migration then happens naturally on the first launch,
 * which the benchmarks exclude via a warm-up launch.
 */
class BenchmarkFixtureProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val context = context ?: return true
        // Seed only the benchmark application id; any other package stays untouched.
        if (context.packageName != BENCHMARK_APPLICATION_ID) return true
        seed(context, "v12-sample.db", "time-agent.db")
        seed(context, "workouts-sample.db", "workouts.db")
        return true
    }

    private fun seed(context: Context, assetName: String, databaseName: String) {
        val target = context.getDatabasePath(databaseName)
        // Never replace existing files: an earlier run may already hold migrated data.
        if (target.exists()) return
        target.parentFile?.mkdirs()
        context.assets.open("$ASSET_DIR/$assetName").use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = throw unsupported("query")

    override fun getType(uri: Uri): String? = throw unsupported("getType")

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw unsupported("insert")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw unsupported("delete")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw unsupported("update")

    private fun unsupported(operation: String) =
        UnsupportedOperationException("BenchmarkFixtureProvider is seed-only: $operation is not supported")

    private companion object {
        const val BENCHMARK_APPLICATION_ID = "com.timeagent.benchmark"
        const val ASSET_DIR = "benchmark-data"
    }
}
