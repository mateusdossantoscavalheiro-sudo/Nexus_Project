import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

public class TelemetryDAO {

    public void insertTelemetry(int assetId, double temp, double humi, double current, double vib, String status) {
        String sql = "INSERT INTO telemetry_log (asset_id, temperature, humidity, current_amps, vibration_x, system_status) VALUES (?, ?, ?, ?, ?, ?)";

        try (Connection conn = DriverManager.getConnection(DatabaseConfig.URL, DatabaseConfig.USER, DatabaseConfig.PASS);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, assetId);
            pstmt.setDouble(2, temp);
            pstmt.setDouble(3, humi);
            pstmt.setDouble(4, current);
            pstmt.setDouble(5, vib);
            pstmt.setString(6, status);

            pstmt.executeUpdate();
            System.out.println("[DATABASE] Log of telemetry save with success!");

        } catch (SQLException e) {
            System.err.println("[DATABASE] Error to connect or save: " + e.getMessage());
        }
    }

    public List<JSONObject> getHistory(int limit) {
        List<JSONObject> history = new ArrayList<>();
        String sql = "SELECT * FROM telemetry_log ORDER BY created_at DESC LIMIT ?";

        try (Connection conn = DriverManager.getConnection(DatabaseConfig.URL, DatabaseConfig.USER, DatabaseConfig.PASS);
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
        } catch (Exception e) {
            System.err.println("[DB ERROR] Failed to get historic: " + e.getMessage());
        }
        return history;
    }

    public List<JSONObject> getCriticalFailures(int limit) {
        List<JSONObject> failures = new ArrayList<>();
        // SQL filtering only for LOCKED_FAILURE
        String sql = "SELECT * FROM telemetry_log WHERE system_status = 'LOCKED_FAILURE' ORDER BY created_at DESC LIMIT ?";

        try (Connection conn = DriverManager.getConnection(DatabaseConfig.URL, DatabaseConfig.USER, DatabaseConfig.PASS);
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
        } catch (Exception e) {
            System.err.println("[DB ERROR] Failed to fetch critical failures: " + e.getMessage());
        }
        return failures;
    }
}