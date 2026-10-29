package com.eratw.port.android

import android.util.Log
import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.SpSetArgument
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ART 上的**参数树判别力**取证：同一条 `SET I = 0` / `SET I += 1`，在真机运行时装载回来
 * 必须还是它自己的左值与增量。
 *
 * ## 为什么这条必须跑在设备上，而不是只跑在 JVM 闸里
 *
 * 2026-10-21 的开局死循环 P0 就是这么漏掉的：`ArgInterner.shapeOf` 靠反射建「结构比较表」，
 * 而它当时用 JDK 9 才有的 `trySetAccessible()`，外面还套了 `runCatching`。ART 上拿不到这个
 * 方法 → 异常被咽成 `false` → **所有字段都没进表** → 同类不同形的结点全部塌成「第一个见到
 * 的代表」。`FOR I, 0, 35` 的 INIT 与 STEP 于是都拿到了同一条 `RESULT = GLOBAL` 的 `dest`
 * 与 `args`，计数器恒 0，判定永真，5,000,001 步撞闸。
 *
 * JVM 侧五种形态（急切档 / 惰性档 / `.erbc` 往返 / 真语料体 / 被 CALL 帧）全绿：反射在
 * HotSpot 上是好的，而**内部自检（`hashOf`/`sameStructure`/`canonicalize`）用的就是那张坏表**，
 * 从进程内部根本看不出异常。所以这一条只断言「从外面读回来」的值，跑在真机运行时上。
 *
 * 修好之后 [com.eratw.port.core.stmt.ArgInterner] 每次建表都会自证一次（`probeShapes`）：
 * 反射再退化成那样，装载当场抛「形状表自检失败：… 只差 X 仍被并成同一代表」而不是把错值
 * 带进整局。本用例是那条自检在 ART 上的对拍。
 */
class M10ArgInternArtTest {

    private fun varI() = VarRef("I", listOf(IntLiteral(0L)), operandType = OperandType.INT)

    private fun set(dest: VarRef, op: OperatorCode, term: com.eratw.port.core.expr.Expr?) =
        AssignStmt(dest, op, SpSetArgument(dest, term), 1, "SET")

    private fun program(): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 1)
        // 顺序照 TITLE.ERB：`RESULT = GLOBAL` 摆在最前 —— 塌陷时 INIT/STEP 拿到的就是它这一份
        listOf(
            set(
                VarRef("RESULT", listOf(IntLiteral(0L)), operandType = OperandType.INT),
                OperatorCode.ASSIGNMENT,
                VarRef("GLOBAL", listOf(IntLiteral(0L)), operandType = OperandType.INT),
            ),
            set(varI(), OperatorCode.ASSIGNMENT, IntLiteral(0L)),
            set(varI(), OperatorCode.PLUS, IntLiteral(1L)).apply {
                (args as SpSetArgument).addConst = true
            },
        ).forEach { b.emit(Opcode.PRINT, b.statement(it), 0, SlotAddr.NONE, 0) }
        b.emit(Opcode._HALT, 0, 0, 0, 1)
        return b.build(0)
    }

    private fun assertForSurvives(label: String, program: Program) {
        val a = program.statement(1) as AssignStmt
        val s = program.statement(2) as AssignStmt
        val aArgs = a.args as SpSetArgument
        val sArgs = s.args as SpSetArgument
        Log.i(M10Evidence.TAG, "参数树判别力 $label：dest=${a.dest.name}/${s.dest.name} " +
            "term=${aArgs.term}/${sArgs.term} addConst=${aArgs.addConst}/${sArgs.addConst}")
        assertEquals("$label：FOR INIT 的左值被换掉了（形状表少认 VarRef.name）", varI(), a.dest)
        assertEquals("$label：FOR STEP 的左值被换掉了（形状表少认 VarRef.name）", varI(), s.dest)
        assertEquals(OperatorCode.ASSIGNMENT, a.op)
        assertEquals(OperatorCode.PLUS, s.op)
        assertEquals("$label：FOR INIT 的初值被换掉了", 0L, (aArgs.term as IntLiteral).value)
        assertEquals("$label：FOR STEP 的增量被换掉了（少认 SpSetArgument.term）", 1L, (sArgs.term as IntLiteral).value)
        assertEquals("$label：FOR INIT 的参数左值", varI(), aArgs.dest)
        assertEquals("$label：FOR STEP 的参数左值", varI(), sArgs.dest)
        assertTrue("$label：FOR INIT 不该带 addConst", !aArgs.addConst)
        assertTrue("$label：FOR STEP 的 addConst 必须保留", sArgs.addConst)
    }

    @Test
    fun loadedFromBytesKeepsDistinctArgTrees() {
        val program = program()
        assertForSurvives("字节装载档", ProgramLoader.load(ErbcWriter.encode(program)))
    }

    @Test
    fun lazyMappedArtifactKeepsDistinctArgTrees() {
        val file = File(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "art-interner.erbc")
        file.writeBytes(ErbcWriter.encode(program()))
        assertForSurvives("映射整池档", ProgramLoader.loadFile(file.toPath()))
        val lazy = ProgramLoader.loadMapped(file.toPath())
        assertEquals("loadMapped 返回后已物化条数必须是 0（本用例要覆盖按条物化时去重那条路径）",
            0, lazy.materializedStatementCount)
        assertForSurvives("生产惰性档", lazy)
    }
}
