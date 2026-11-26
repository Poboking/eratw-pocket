package com.eratw.port.android.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 本机收藏存储的定向单测（CONTRACT-MKM-STATS-UI §3 判据 1/4；hermetic，零网络）。
 *
 * 覆盖：读写往返 + 键排序落盘确定性、文件缺席回空集、损坏（语法错/形状不对/schema
 * 不对/条目畸形）回空集不抛、toggle 语义（加/减互逆）。键形唯一真源 =
 * [MarketContract.favoriteKey]（`kind/id`，无版本段）。
 */
class MarketFavoritesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): MarketFavoritesStore = MarketFavoritesStore(File(tmp.root, "favorites.json"))

    @Test
    fun `键形_kind加id无版本段_与契约派生一致`() {
        assertEquals("kojo-pack/demo.era-tw", MarketContract.favoriteKey("kojo-pack", "demo.era-tw"))
    }

    @Test
    fun `读写往返_全量集合保真`() {
        val s = store()
        val keys = setOf(
            MarketContract.favoriteKey("kojo-pack", "demo.era-tw"),
            MarketContract.favoriteKey("dict-pack", "demo.dict"),
        )
        assertTrue(s.write(keys))
        assertEquals(keys, s.read())
    }

    @Test
    fun `落盘键排序_字节面确定性`() {
        val s = store()
        s.write(
            setOf(
                MarketContract.favoriteKey("kojo-pack", "zzz"),
                MarketContract.favoriteKey("dict-pack", "aaa"),
            ),
        )
        val text = File(tmp.root, "favorites.json").readText()
        val aaaAt = text.indexOf("dict-pack/aaa")
        val zzzAt = text.indexOf("kojo-pack/zzz")
        assertTrue("排序后的键应按字典序落盘：$text", aaaAt in 0 until zzzAt)
    }

    @Test
    fun `文件缺席回空集`() {
        assertTrue(store().read().isEmpty())
    }

    @Test
    fun `损坏回空集不抛_语法错`() {
        val f = File(tmp.root, "favorites.json")
        f.writeText("{ broken json")
        assertTrue(store().read().isEmpty())
    }

    @Test
    fun `损坏回空集不抛_形状不对与schema不对`() {
        val f1 = File(tmp.root, "shape.json")
        f1.writeText("""["kojo-pack/a"]""")
        assertTrue(store_at(f1).read().isEmpty())
        val f2 = File(tmp.root, "schema.json")
        f2.writeText("""{"schema":99,"favorites":["kojo-pack/a"]}""")
        assertTrue(store_at(f2).read().isEmpty())
        val f3 = File(tmp.root, "items.json")
        f3.writeText("""{"schema":1,"favorites":{"kojo-pack/a":true}}""")
        assertTrue(store_at(f3).read().isEmpty())
    }

    @Test
    fun `条目畸形跳过_空串与非字符串不进集合`() {
        val f = File(tmp.root, "items.json")
        f.writeText("""{"schema":1,"favorites":["kojo-pack/ok","",123,null,"dict-pack/ok2"]}""")
        val read = store_at(f).read()
        assertEquals(
            setOf("kojo-pack/ok", "dict-pack/ok2"),
            read,
        )
    }

    @Test
    fun `toggle语义_加减互逆且重复添加幂等`() {
        val s = store()
        val key = MarketContract.favoriteKey("kojo-pack", "demo.era-tw")
        val added = s.read() + key
        assertTrue(s.write(added))
        assertTrue(s.read().contains(key))
        val reAdded = s.read() + key
        assertEquals(added, reAdded) // 幂等
        val removed = s.read() - key
        assertTrue(s.write(removed))
        assertFalse(s.read().contains(key))
    }

    private fun store_at(file: File): MarketFavoritesStore = MarketFavoritesStore(file)
}
