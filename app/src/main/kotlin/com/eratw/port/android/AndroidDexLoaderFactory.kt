package com.eratw.port.android

import android.content.Context
import dalvik.system.DexClassLoader
import java.io.File

/**
 * ADR-013 S4c：运行包类加载器的 Android 生产实现（[DexLoaderFactory] 的唯一真机实现）。
 *
 * 只做一件事：把装载链交给的内容寻址产物地址包成 [DexClassLoader]。路径是本工厂的输入、
 * 不是它的职责 —— 产物目录、sha256 复算、信任闸都在 [ModDexLoader] 里，本类不做 IO 校验、
 * 不自行拼 code 目录路径（避免把「找产物」与「建类加载器」两处口径拆成两份）。
 *
 * 三条硬约束（ADR-013 D1/D4）：
 * 1. parent 必须是 `context.classLoader`（AppClassLoader）；置空会让 ModApi 类型身份分裂，
 *    包内实现 [com.eratw.port.modapi.EraMod] 在宿主侧 cast 必失败。
 * 2. optimizedDirectory 必须是 `context.codeCacheDir`：Android 14+ 要求 DCL 的 dex 输出落在
 *    可写目录；content-addressed 产物目录只读只增，绝不能用作优化产物落点。
 * 3. 每次 [create] 新开一个 loader；DZ 重装不复用旧实例（ADR-013 D4，原子卸载不承诺）。
 *
 * 真机侧「装载真实 classes.dex 并实例化 EraMod」尚未验证：JVM 单测只能做源码哨兵，
 * 实装载留 TODO(verify)（见交付报告）。
 */
internal class AndroidDexLoaderFactory(private val context: Context) : DexLoaderFactory {
    override fun create(packageId: String, artifactFile: File): ClassLoader = DexClassLoader(
        artifactFile.absolutePath,
        context.codeCacheDir.absolutePath,
        null,
        context.classLoader,
    )
}
