import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * NEXUS Industrial Controller
 * Manages MQTT communication with ESP32 and WebSocket with Dashboard.
 */
public class NexusSubscriber {
    private static final Map<Integer, MotorData> engineFleet = new ConcurrentHashMap<>();
    private static final Map<String, WsContext> sessions = new ConcurrentHashMap<>();
    private static IMqttClient mqttClient;

    private static final TelemetryDAO telemetryDAO = new TelemetryDAO();

    public static void main(String[] args) {
        // Initialize Engine #1
        engineFleet.put(1, new MotorData(1, "Main Lathe #01"));

        // Initialize Web Server (Javalin) on port 8080
        var app = Javalin.create(config -> {
            config.staticFiles.add("/public");
        }).start(8080);

        // WebSocket Routes
        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                sessions.put(ctx.sessionId(), ctx);
                System.out.println("[WS] Dashboard session established: " + ctx.sessionId());
            });
            ws.onClose(ctx -> sessions.remove(ctx.sessionId()));
            ws.onMessage(ctx -> handleWebCommand(ctx.message()));
        });

        // Route for general telemetry history (Charts and KPIs)
        app.get("/api/history", ctx -> {
            ctx.result(telemetryDAO.getHistory(50).toString());
            ctx.contentType("application/json");
        });

        // Professional route for critical failures only (Maintenance Table)
        app.get("/api/failures", ctx -> {
            ctx.result(telemetryDAO.getCriticalFailures(5).toString());
            ctx.contentType("application/json");
        });

        setupMQTT();

        System.out.println("\n======================================");
        System.out.println("   NEXUS OPERATIONAL CONTROL ONLINE   ");
        System.out.println("======================================\n");
    }

    private static void setupMQTT() {
        String broker = "tcp://broker.hivemq.com:1883";
        String clientId = "NexusServer_" + System.currentTimeMillis();

        try {
            mqttClient = new MqttClient(broker, clientId, new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setAutomaticReconnect(true);

            mqttClient.connect(options);

            // Subscribe to all motor telemetry
            mqttClient.subscribe("nexus/motor/+/telemetry", (topic, msg) -> {
                try {
                    JSONObject json = new JSONObject(new String(msg.getPayload()));
                    int id = json.getInt("id");

                    if (engineFleet.containsKey(id)) {
                        MotorData motor = engineFleet.get(id);
                        motor.updateFromHardware(json);
                        telemetryDAO.insertTelemetry(motor.id, motor.temp, motor.humi, motor.curr, motor.vib, motor.state);
                        broadcastToWeb(motor.toJson());
                    }
                } catch (Exception e) {
                    System.err.println("[MQTT] Telemetry processing error: " + e.getMessage());
                }
            });
        } catch (MqttException e) {
            System.err.println("[MQTT] Connection failed: " + e.getMessage());
        }
    }

    private static void handleWebCommand(String fullCommand) {
        try {
            String[] parts = fullCommand.split(":");
            int motorId = Integer.parseInt(parts[0]);
            String action = parts[1];

            String topic = "nexus/motor/" + motorId + "/control";
            mqttClient.publish(topic, new MqttMessage(action.getBytes()));

            System.out.println("[CMD] Command " + action + " dispatched to Motor " + motorId);
        } catch (Exception e) {
            System.err.println("[CMD] Invalid command format received from Web: " + fullCommand);
        }
    }

    private static void broadcastToWeb(String data) {
        sessions.values().forEach(s -> {
            if (s.session.isOpen()) s.send(data);
        });
    }

    // --- Motor Data Logic Class ---
    static class MotorData {
        int id;
        String name;
        double temp, humi, curr, vib;
        String state = "STOPPED";

        private long lastViolationTime = 0;
        private final long GRACE_PERIOD_MS = 5000;

        public MotorData(int id, String name) {
            this.id = id;
            this.name = name;
        }

        public void updateFromHardware(JSONObject json) {
            this.temp = json.getDouble("temp");
            this.humi = json.getDouble("humi");
            this.curr = json.getDouble("curr");
            this.vib = json.getDouble("vib");

            // Sync state with Hardware feedback
            if(json.has("state")) {
                this.state = json.getString("state");
            }

            // Real-time Console Logging
            System.out.printf("[MOTOR-%d] Temp: %.1fC | Current: %.1fA | Vib: %.2fG | Status: %s\n",
                    id, temp, curr, vib, state);

            checkSafetyProcedures();
        }

        private void checkSafetyProcedures() {
            // Safety logic only active during OPERATING mode
            if (!state.equals("OPERATING")) {
                lastViolationTime = 0;
                return;
            }

            boolean isViolating = (temp > 60.0) || (curr > 14.0) || (Math.abs(vib) > 10.0);

            if (isViolating) {
                if (lastViolationTime == 0) lastViolationTime = System.currentTimeMillis();

                long duration = System.currentTimeMillis() - lastViolationTime;
                if (duration > GRACE_PERIOD_MS) {
                    executeEmergencyShutdown("CRITICAL PARAMETER EXCEEDED");
                }
            } else {
                lastViolationTime = 0;
            }
        }

        private void executeEmergencyShutdown(String reason) {
            try {
                this.state = "LOCKED_FAILURE";
                System.err.println("\n[SECURITY] EMERGENCY SHUTDOWN INITIATED: " + reason);
                String topic = "nexus/motor/" + id + "/control";
                mqttClient.publish(topic, new MqttMessage("STOP".getBytes()));
                broadcastToWeb(this.toJson());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        public String toJson() {
            return new JSONObject()
                    .put("id", id).put("name", name)
                    .put("temp", temp).put("humi", humi)
                    .put("curr", curr).put("vib", vib)
                    .put("state", state).toString();
        }
    }
}