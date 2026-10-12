package com.tailscale.mclink;

public final class HelperProcessFixture {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("exit")) System.exit(7);
        System.out.println("{\"type\":\"ready\",\"mode\":\"host\",\"invite\":\"mcl1_test\"}");
        System.out.flush();
        if (args[0].equals("malformed")) {
            Thread.sleep(100);
            System.out.println("not-json");
            System.out.flush();
        } else if (args[0].equals("oversized")) {
            System.out.println("x".repeat(20 * 1024));
            System.out.flush();
        } else if (args[0].equals("slow-close")) {
            Thread.sleep(30_000);
        }
        while (System.in.read() != -1) { }
    }
}
