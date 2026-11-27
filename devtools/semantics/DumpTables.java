/// P0.5 语义夹具的 JVM 侧采集器（CONTRACT-RUSTFULL §6.2 K18/K19/K20/K21）。
///
/// 一次跑出四张表，供 Rust 侧逐行比对；输出格式统一为 `<key>\t<十六进制码点，空格分隔；空 = 未定义/不可解码>`。
/// Rust 侧对应生成器见 `tools/erbcc-rs/src/bin/semdiff.rs`，编排见 `devtools/erbcc_semantics_diff.py`。
///
/// 为什么要用反射式的「全空间枚举」而不是抽语料样本：这几类差异都只在极端字符/极端字节上出现，
/// 语料里没出现过的值不会在样本里，等到某个玩家改名才炸是最坏结果。
///
/// 用法：
///   javac -d <tmp> DumpTables.java
///   java -cp <tmp> DumpTables upper > upper.tsv
///   java -cp <tmp> DumpTables sjis  > sjis.tsv
///   java -cp <tmp> DumpTables float <bits.txt> > float.tsv      // bits.txt 每行一个 f64 的十六进制位型
///   java -cp <tmp> DumpTables cmp   <pairs.txt> > cmp.tsv       // 每行 "<码点集>\t<码点集>"
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class DumpTables {

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("usage: DumpTables upper|sjis|float|cmp [input]");
            System.exit(2);
        }
        switch (args[0]) {
            case "upper" -> upper();
            case "sjis" -> sjis();
            case "float" -> floatTable(args[1]);
            case "cmp" -> cmpTable(args[1]);
            default -> {
                System.err.println("unknown mode: " + args[0]);
                System.exit(2);
            }
        }
    }

    /// 码点序列 → "3042 3044"（空串 = 空结果）
    private static String hex(String s) {
        StringBuilder sb = new StringBuilder();
        s.codePoints().forEach(cp -> {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("%04X", cp));
        });
        return sb.toString();
    }

    /// K19：全集大写表。强度：全 Unicode 标量空间（跳过代理区）。
    private static void upper() {
        StringBuilder sb = new StringBuilder(1 << 20);
        for (int cp = 0; cp <= 0x10FFFF; cp++) {
            if (cp >= 0xD800 && cp <= 0xDFFF) continue;
            String up = new String(Character.toChars(cp)).toUpperCase(Locale.ROOT);
            sb.append(String.format("%04X", cp)).append('\t').append(hex(up)).append('\n');
        }
        System.out.print(sb);
    }

    /// K21：MS932（windows-31j）字节映射。单字节全空间 + 双字节全空间（含非法尾字节）。
    private static void sjis() {
        Charset cs = Charset.forName("windows-31j");
        StringBuilder sb = new StringBuilder(1 << 18);
        for (int b = 0x00; b <= 0xFF; b++) {
            sb.append(String.format("S%02X", b)).append('\t')
              .append(hex(decode(cs, new byte[] {(byte) b}))).append('\n');
        }
        for (int lead = 0x81; lead <= 0x9F; lead++) pairRows(cs, sb, lead);
        for (int lead = 0xE0; lead <= 0xEF; lead++) pairRows(cs, sb, lead);
        System.out.print(sb);
    }

    private static void pairRows(Charset cs, StringBuilder sb, int lead) {
        for (int trail = 0x40; trail <= 0xFC; trail++) {
            sb.append(String.format("D%02X%02X", lead, trail)).append('\t')
              .append(hex(decode(cs, new byte[] {(byte) lead, (byte) trail}))).append('\n');
        }
    }

    /// 单次解码；不可解码返回空串（编码上等价于「未定义」）。
    private static String decode(Charset cs, byte[] bytes) {
        try {
            return cs.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return "";
        }
    }

    /// K18：Java 的 Double.toString 文案。
    private static void floatTable(String input) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readAllLines(Path.of(input))) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            long bits = Long.parseUnsignedLong(line, 16);
            double v = Double.longBitsToDouble(bits);
            sb.append(String.format("%016X", bits)).append('\t')
              .append(Double.toString(v)).append('\n');
        }
        System.out.print(sb);
    }

    /// K20：Java 的 String.compareTo（UTF-16 码元序）符号。
    private static void cmpTable(String input) throws IOException {
        List<String> lines = Files.readAllLines(Path.of(input));
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] parts = line.split("\t", -1);
            String a = fromHex(parts[0]);
            String b = parts.length > 1 ? fromHex(parts[1]) : "";
            int c = Integer.signum(a.compareTo(b));
            sb.append(parts[0]).append('\t').append(parts.length > 1 ? parts[1] : "")
              .append('\t').append(c).append('\n');
        }
        System.out.print(sb);
    }

    private static String fromHex(String hex) {
        if (hex.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String tok : hex.trim().split(" ")) {
            sb.appendCodePoint(Integer.parseInt(tok, 16));
        }
        return sb.toString();
    }
}
