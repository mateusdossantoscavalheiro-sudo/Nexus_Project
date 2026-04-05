public class DatabaseConfig {

    private static String getEnvOrDefault(String envVar, String defaultValue) {
        String value = System.getenv(envVar);
        if (value != null && !value.trim().isEmpty()) {
            System.out.println("[CONFIG] Using environment variable: " + envVar);
            return value;
        }
        System.out.println("[CONFIG] Using default value for: " + envVar);
        return defaultValue;
    }

    private static final String SUPABASE_URI = "jdbc:postgresql://db.mvjpcsmurtsimsysrexf.supabase.co:5432/postgres";
    private static final String SUPABASE_USER = "postgres";
    private static final String SUPABASE_PASS = "%zVN/.2BzF$rUKK";

    public static final String URL = getEnvOrDefault("DATABASE_URL", SUPABASE_URI);
    public static final String USER = getEnvOrDefault("DATABASE_USER", SUPABASE_USER);
    public static final String PASS = getEnvOrDefault("DATABASE_PASS", SUPABASE_PASS);

    public static void printConfig() {
        System.out.println("\n========================================");
        System.out.println("   DATABASE CONFIGURATION");
        System.out.println("========================================");
        System.out.println("URL:  " + maskUrl(URL));
        System.out.println("USER: " + USER);
        System.out.println("PASS: " + maskPassword(PASS));
        System.out.println("========================================\n");
    }

    private static String maskUrl(String url) {
        if (url.length() > 30) {
            return url.substring(0, 20) + "..." + url.substring(url.length() - 10);
        }
        return url;
    }

    private static String maskPassword(String pass) {
        return "***" + pass.substring(Math.max(0, pass.length() - 3));
    }

    public static boolean testConnection() {
        try {
            Class.forName("org.postgresql.Driver");
            java.sql.Connection conn = java.sql.DriverManager.getConnection(URL, USER, PASS);
            conn.close();
            System.out.println("[CONFIG] ✅ Database connection test SUCCESSFUL");
            return true;
        } catch (Exception e) {
            System.err.println("[CONFIG] ❌ Database connection test FAILED");
            System.err.println("[CONFIG] Error: " + e.getMessage());
            return false;
        }
    }
}