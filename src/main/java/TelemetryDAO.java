import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.json.JSONObject;

public class TelemetryDAO {

    private Connection getConnection() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", DatabaseConfig.USER);
        props.setProperty("password", DatabaseConfig.PASS);
        props.setProperty("ssl", "true");
        props.setProperty("sslmode", "require");

        props.setProperty("connectTimeout", "30");
        props.setProperty("socketTimeout", "30");
        props.setProperty("loginTimeout", "30");

        System.out.println("[DATABASE] Connecting to: " + DatabaseConfig.URL);
        Connection conn = DriverManager.getConnection(DatabaseConfig.URL, props);
        System.out.println("[DATABASE] ✅ Connection established successfully");
        return conn;
    }

    public void insertTelemetry(int assetId, double temp, double humi, double current, double vib, String status) {
        String sql = "INSERT INTO telemetry_log (asset_id, temperature, humidity, current_amps, vibration_x, system_status) VALUES (?, ?, ?, ?, ?, ?)";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, assetId);
            pstmt.setDouble(2, temp);
            pstmt.setDouble(3, humi);
            pstmt.setDouble(4, current);
            pstmt.setDouble(5, vib);
            pstmt.setString(6, status);

            pstmt.executeUpdate();
            System.out.println("[DATABASE] ✅ Telemetry saved for Asset ID: " + assetId);

        } catch (SQLException e) {
            System.err.println("[DATABASE] ❌ Error saving telemetry: " + e.getMessage());
            System.err.println("[DATABASE] SQL State: " + e.getSQLState());
            e.printStackTrace();
        }
    }

    public List<JSONObject> getHistory(int limit) {
        List<JSONObject> history = new ArrayList<>();
        String sql = "SELECT * FROM telemetry_log ORDER BY created_at DESC LIMIT ?";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, limit);
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                JSONObject item = new JSONObject();
                item.put("id", rs.getInt("asset_id"));
                item.put("temp", rs.getDouble("temperature"));
                item.put("humi", rs.getDouble("humidity"));
                item.put("curr", rs.getDouble("current_amps"));
                item.put("vib", rs.getDouble("vibration_x"));
                item.put("status", rs.getString("system_status"));
                item.put("time", rs.getTimestamp("created_at").toString());
                history.add(item);
            }

            System.out.println("[DATABASE] ✅ Retrieved " + history.size() + " history records");
        } catch (Exception e) {
            System.err.println("[DB ERROR] ❌ Failed to get history: " + e.getMessage());
            e.printStackTrace();
        }
        return history;
    }

    public void saveOrUpdateAsset(int id, String name, double limitTemp, double limitCurr, double limitVib) {

        String sql = "INSERT INTO assets (id, name, limittemp, limitcurr, limitvib, state) " +
                "VALUES (?, ?, ?, ?, ?, 'OFFLINE') " +
                "ON CONFLICT (id) DO UPDATE SET " +
                "name = EXCLUDED.name, " +
                "limittemp = EXCLUDED.limittemp, " +
                "limitcurr = EXCLUDED.limitcurr, " +
                "limitvib = EXCLUDED.limitvib";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, id);
            pstmt.setString(2, name);
            pstmt.setDouble(3, limitTemp);
            pstmt.setDouble(4, limitCurr);
            pstmt.setDouble(5, limitVib);

            int rows = pstmt.executeUpdate();
            System.out.println("[DB] ✅ Sync Success for Asset ID: " + id + " (affected rows: " + rows + ")");

        } catch (SQLException e) {
            System.err.println("[DB ERROR] ❌ Failed to save/update asset ID " + id);
            System.err.println("[DB ERROR] SQL State: " + e.getSQLState());
            System.err.println("[DB ERROR] Error Code: " + e.getErrorCode());
            System.err.println("[DB ERROR] Message: " + e.getMessage());
            e.printStackTrace();

            if (e.getMessage().contains("column") || e.getMessage().contains("does not exist")) {
                System.err.println("\n⚠️ HINT: Check that the ‘assets’ table has the correct columns:");
                System.err.println("   CREATE TABLE IF NOT EXISTS assets (");
                System.err.println("     id INT PRIMARY KEY,");
                System.err.println("     name TEXT,");
                System.err.println("     limittemp FLOAT8,");
                System.err.println("     limitcurr FLOAT8,");
                System.err.println("     limitvib FLOAT8,");
                System.err.println("     state TEXT");
                System.err.println("   );");
            }
        }
    }

    public List<JSONObject> getAllAssets() {
        List<JSONObject> list = new ArrayList<>();
        String sql = "SELECT * FROM assets";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                JSONObject json = new JSONObject();
                json.put("id", rs.getInt("id"));
                json.put("name", rs.getString("name"));

                // 🔧 CORREÇÃO 3: Usar nomes de colunas em lowercase
                json.put("limitTemp", rs.getDouble("limittemp"));
                json.put("limitCurr", rs.getDouble("limitcurr"));
                json.put("limitVib", rs.getDouble("limitvib"));
                json.put("state", rs.getString("state"));

                list.add(json);
            }

            System.out.println("[DATABASE] ✅ Loaded " + list.size() + " assets from database");

        } catch (SQLException e) {
            System.err.println("[DATABASE ERROR] ❌ Could not load assets");
            System.err.println("[DATABASE ERROR] SQL State: " + e.getSQLState());
            System.err.println("[DATABASE ERROR] Message: " + e.getMessage());
            e.printStackTrace();

            if (e.getMessage().contains("does not exist")) {
                System.err.println("\n⚠️ HINT: The ‘assets’ table does not exist. Run this SQL query in Supabase:");
                System.err.println(getCreateAssetsTableSQL());
            }
        }

        return list;
    }

    public List<JSONObject> getCriticalFailures(int limit) {
        List<JSONObject> failures = new ArrayList<>();
        String sql = "SELECT * FROM telemetry_log WHERE system_status = 'LOCKED_FAILURE' ORDER BY created_at DESC LIMIT ?";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, limit);
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                JSONObject item = new JSONObject();
                item.put("id", rs.getInt("asset_id"));
                item.put("temp", rs.getDouble("temperature"));
                item.put("curr", rs.getDouble("current_amps"));
                item.put("vib", rs.getDouble("vibration_x"));
                item.put("status", rs.getString("system_status"));
                item.put("time", rs.getTimestamp("created_at").toString());
                failures.add(item);
            }

            System.out.println("[DATABASE] ✅ Retrieved " + failures.size() + " critical failures");

        } catch (Exception e) {
            System.err.println("[DB ERROR] ❌ Failed to fetch critical failures: " + e.getMessage());
            e.printStackTrace();
        }

        return failures;
    }

    public void deleteAsset(int id) throws SQLException {
        String sql = "DELETE FROM assets WHERE id = ?";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, id);
            int rows = pstmt.executeUpdate();

            if (rows > 0) {
                System.out.println("[DB] ✅ Asset ID " + id + " deleted from database");
            } else {
                System.out.println("[DB] ⚠️ Asset ID " + id + " not found in database");
            }

        } catch (SQLException e) {
            System.err.println("[DB ERROR] ❌ Failed to delete asset ID " + id + ": " + e.getMessage());
            throw e;
        }
    }

    public static String getCreateAssetsTableSQL() {
        return """
                -- Tabela de Assets
                CREATE TABLE IF NOT EXISTS assets (
                    id INT PRIMARY KEY,
                    name TEXT NOT NULL,
                    limittemp FLOAT8 DEFAULT 60.0,
                    limitcurr FLOAT8 DEFAULT 14.0,
                    limitvib FLOAT8 DEFAULT 10.0,
                    state TEXT DEFAULT 'OFFLINE',
                    created_at TIMESTAMP DEFAULT NOW()
                );

                -- Tabela de Telemetria
                CREATE TABLE IF NOT EXISTS telemetry_log (
                    log_id SERIAL PRIMARY KEY,
                    asset_id INT NOT NULL,
                    temperature FLOAT8,
                    humidity FLOAT8,
                    current_amps FLOAT8,
                    vibration_x FLOAT8,
                    system_status TEXT,
                    created_at TIMESTAMP DEFAULT NOW(),
                    FOREIGN KEY (asset_id) REFERENCES assets(id) ON DELETE CASCADE
                );

                -- Índices para melhor performance
                CREATE INDEX IF NOT EXISTS idx_telemetry_asset ON telemetry_log(asset_id);
                CREATE INDEX IF NOT EXISTS idx_telemetry_time ON telemetry_log(created_at DESC);
                CREATE INDEX IF NOT EXISTS idx_telemetry_status ON telemetry_log(system_status);
                """;
    }
}
