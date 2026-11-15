package com.eratw.port.android

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.eratw.port.platform.sql.AndroidSqliteHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * bundled SQLite 真机冒烟（CONTRACT-SQLITE-MIGRATION W3）。
 *
 * 只验一件事：装机后的 `androidx.sqlite:sqlite-bundled`（SQLite 3.46.0）真能开内存库，
 * 且 `sqlite_version()` 报的是随包版本、没有静默回落系统库。minSdk 21 的 framework
 * SQLite 远低于 3.46（不支持语料的 `ON CONFLICT DO UPDATE` 等用法），所以这个读数就是
 * 「没有回落 framework」的直接证据。
 *
 * 走 [AndroidSqliteHost] 而不是直接构造 `BundledSQLiteDriver`：工厂内部构造的正是
 * `BundledSQLiteDriver`，顺带把生产装配路径（W1 的适配器 + A2 的路径布局）一起过一遍；
 * 直接引用 AndroidX 类型则要给 app 的 androidTest 加 `androidx.sqlite:sqlite-bundled`
 * 依赖，而 `app/build.gradle.kts` 不属本 lane。两个根只在文件目标时生效，内存库不落盘。
 *
 * 验收面只到编译（`:app:compileDebugAndroidTestKotlin`），不要求本波真机常跑；
 * 真机跑时 `sqlite_version` 会打 Logcat 便于取证。
 */
class SQLiteBundledSmokeTest {

    @Test
    fun memoryDatabaseReportsBundledSqlite346() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 与 GameHost 的生产装配同形（seedRoot = 游戏根，writeRoot = 游戏根/sql）；
        // :memory: 目标不碰盘，这里只把工厂契约的两个根摆出来。
        val seedRoot = File(context.filesDir, "sqlite-smoke/game")
        val writeRoot = File(seedRoot, "sql")
        val driver = AndroidSqliteHost.create(seedRoot = seedRoot, writeRoot = writeRoot)

        driver.open(":memory:").use { db ->
            db.prepare("SELECT sqlite_version()").use { statement ->
                assertEquals("SELECT sqlite_version() 必须恰有一列", 1, statement.columnCount)
                assertTrue("sqlite_version() 必须有且只有一行", statement.step())
                val version = statement.getText(0)
                Log.i(TAG, "sqlite_version=$version")
                assertEquals(
                    "必须使用随包 SQLite 3.46.0（bundled），不得回落系统库",
                    EXPECTED_VERSION,
                    version,
                )
                assertFalse("sqlite_version() 只应返回一行", statement.step())
            }
        }
    }

    private companion object {
        const val TAG = "SQLiteBundledSmoke"
        const val EXPECTED_VERSION = "3.46.0"
    }
}
