package com.eratw.port.platform.sql

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.eratw.port.core.sql.SqlDriver
import com.eratw.port.sqlite.AndroidXSqliteDriver
import java.io.File

/**
 * Android 宿主的 SQLite 驱动工厂（SQLite 迁移 W1）。
 *
 * 只做装配：把 AndroidX bundled 驱动与两个根目录交给 [AndroidXSqliteDriver]，
 * 由它完成连接串解析、沙箱校验与端口类型转换。本对象不缓存连接、不读游戏状态；
 * 装配点（GameHost）由 W3 接线。
 *
 * 两个根的语义：
 * - [seedRoot] 只读种子根（随包 assets 解出的原始数据），首次打开时用于复制种子库；
 * - [writeRoot] 可写根（应用私有数据目录），所有 SQLite 目标文件都落在其下。
 *
 * 用 bundled 而非 framework SQLite：minSdk 21 的系统库不支持 ON CONFLICT DO UPDATE 等
 * 语料用法（SQLite 3.46.0 由 libsqliteJni.so 随 APK 提供）。
 */
object AndroidSqliteHost {
    /**
     * 创建 Android 侧 [SqlDriver]。每次调用新建一个驱动实例；连接生命周期由调用方
     * （SqlSession）管理，线程口径与 core 端口一致：只在创建它的 VM 线程使用。
     */
    fun create(seedRoot: File, writeRoot: File): SqlDriver =
        AndroidXSqliteDriver(
            BundledSQLiteDriver(),
            seedRoot.toPath(),
            writeRoot.toPath(),
        )
}
