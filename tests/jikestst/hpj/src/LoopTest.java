import java.io.*;
import java.util.*;
import java.lang.*;

class LoopTest {
    public static void main(String[] argv) { int a;
                                             int b;
                                             int c;
                                             int d;
                                             int e;
                                             int accum = 0;
                                             int f;
                                             b = 0;
                                             c = 7;
                                             d = 9;
                                             e = 5;
                                             for (a = 0; a < 5; a++) e = e + c + d;
                                             if (e == 85) ; else accum++;
                                             for (e = 5, a = 6; a > 1; a--) e = e + c + d;
                                             if (e == 85) ; else accum += 2;
                                             for (e = 5, a = 6; a != 1; a--) e = e + c + d;
                                             if (e == 85) ; else accum += 4;
                                             for (b = 0, a = 5; a >= 0; a--) b = b + c + d;
                                             if (b == 96) ; else accum += 8;
                                             for (a = 6, e = 5; e == 5; a--) e = e + c + d;
                                             if (e == 21) ; else accum += 16;
                                             for (b = 0, a = -4; a <= 0; a++) b = b + c + d;
                                             if (b == 80) ; else accum += 32;
                                             System.out.println(accum);
                                             System.exit(accum); }
    
    public LoopTest() { super(); }
}
