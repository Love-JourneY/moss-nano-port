package smoke;
import canto.CantoTokenTable;
import java.io.*;
import java.util.*;

public class SmokeTok {
    public static void main(String[] a) throws Exception {
        CantoTokenTable t = CantoTokenTable.load(new FileInputStream(a[0] + "/syllable-ids.tsv"));
        System.out.println("  表条目 = " + t.size());
        int pass=0, fail=0;
        // 已知:gam1→[8802,10385] · jat6→[307,319,10752]
        int[] x = t.encode("gam1 jat6 tin1 hei3 gei2 hou2");
        System.out.println("  encode(\"gam1 jat6 tin1 hei3 gei2 hou2\") = " + Arrays.toString(x));
        boolean ok = x.length == 14 && x[0] == 8802 && x[1] == 10385 && x[2] == 307;
        System.out.println((ok?"  [OK]   ":"  [FAIL] ") + "与 Python 参照一致(14 个 id,开头 8802/10385/307)");
        if(ok)pass++;else fail++;
        // 未知音节应被跳过而不抛
        List<String> miss = new ArrayList<>();
        int[] y = t.encode("gam1 zzz9 hou2", miss);
        System.out.println("  含未知音节 ⇒ ids=" + Arrays.toString(y) + " 未命中=" + miss);
        boolean ok2 = y.length > 0 && miss.contains("zzz9");
        System.out.println((ok2?"  [OK]   ":"  [FAIL] ") + "未知音节被记录且不抛");
        if(ok2)pass++;else fail++;
        System.out.println("\n===== " + pass + " 通过 / " + fail + " 失败 =====");
        if (fail>0) System.exit(1);
    }
}
