class Trychk3 {
    public static void main(String[] args) {
        int i;
        int j;
        int sum;
        int value;
        j = 0;
        sum = 0;
        value = 5;
        for (i = 0; i < 5; i++) { try { j = j + 1;
                                        j = other(j);
                                        if (j > 6) break;
                                        sum = sum + i; }
                                  catch (java.io.IOException e) { j = j * 2;
                                                                  sum = sum + value;
                                                                  if (i > 1) { break; } } }
        if (value != 5) j = j + 1;
        System.out.println(j + sum);
        System.exit(j + sum); }
    
    static int other(int x) throws java.io.IOException { if (x > 5) throw new java.io.IOException("stuff");
                                                         x = x + 1;
                                                         return x; }
    
    public Trychk3() { super(); }
}
