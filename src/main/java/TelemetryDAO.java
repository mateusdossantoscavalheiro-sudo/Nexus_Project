import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

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
}