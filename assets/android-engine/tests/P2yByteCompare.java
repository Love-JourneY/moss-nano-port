package smoke;
import canto.P2y;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class Smoke2 {
    public static void main(String[] args) throws Exception {
        String base = args[0];
        P2y p = P2y.load(new FileInputStream(base + "/p2y-rules.tsv"));
        List<String> cases = Files.readAllLines(Paths.get(base + "/cases.txt"), StandardCharsets.UTF_8);
        List<String> want  = Files.readAllLines(Paths.get(base + "/expected.txt"), StandardCharsets.UTF_8);
        int pass = 0, fail = 0;
        for (int i = 0; i < cases.size(); i++) {
            String c = cases.get(i); if (c.trim().isEmpty()) continue;
            String w = i < want.size() ? want.get(i) : "";
            String g = p.convert(c);
            boolean ok = g.equals(w);
            if (ok) pass++; else fail++;
            System.out.println((ok ? "  [OK]   " : "  [DIFF] ") + c);
            if (!ok) { System.out.println("         want: " + w); System.out.println("         got : " + g); }
        }
        System.out.println("\n===== P2y 逐字节比对: " + pass + " 相同 / " + fail + " 不同 =====");
        if (fail > 0) System.exit(1);
    }
}
