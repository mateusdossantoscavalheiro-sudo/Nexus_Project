public class DatabaseConfig {

    private static final String SUPABASE_URI = "jdbc:postgresql://postgres:%zVN/.2BzF$rUKK@db.mvjpcsmurtsimsysrexf.supabase.co:5432/postgres";
    private static final String SUPABASE_USER = "postgres";
    private static final String SUPABASE_PASS = "%zVN/.2BzF$rUKK";

    public static final String URL = System.getenv("DATABASE_URL") != null
            ? System.getenv("DATABASE_URL")
            : SUPABASE_URI;

    public static final String USER = System.getenv("DATABASE_USER") != null
            ? System.getenv("DATABASE_USER")
            : SUPABASE_USER;

    public static final String PASS = System.getenv("DATABASE_PASS") != null
            ? System.getenv("DATABASE_PASS")
            : SUPABASE_PASS;
}