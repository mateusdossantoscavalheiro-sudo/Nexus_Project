import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/**
 * NEXUS Industrial Controller - Core Server
 * Version: 2.0 (Integrated Plant Management)
 * Role: Bridges MQTT (Hardware), WebSockets (Real-time UI), and REST API (Management).
 */
public class NexusSubscriber {
    // In-memory storage for active engines (Fleet Management)
    private static final Map<Integer, MotorData> engineFleet = new ConcurrentHashMap<>();
    // Active WebSocket sessions for real-time dashboard updates
    private static final Map<String, WsContext> sessions = new ConcurrentHashMap<>();

    private static IMqttClient mqttClient;
    private static final TelemetryDAO telemetryDAO = new TelemetryDAO();

    public static void main(String[] args) {
        // Initializing with a default asset (Can be removed once the Executive UI is used)
        engineFleet.put(1, new MotorData(1, "Main Lathe #01"));

        // Initialize Javalin Web Server on port 8080
        var app = Javalin.create(config -> {
            config.staticFiles.add("/public"); // Serves HTML/JS files
        }).start(8080);

        // --- REST API ROUTES (Level 4 - Plant Management) ---

        // GET: Returns the list of all registered assets
        app.get("/api/assets", ctx -> {
            JSONArray list = new JSONArray();
            engineFleet.values().forEach(motor -> {
                JSONObject obj = new JSONObject();
                obj.put("id", motor.id);
                obj.put("name", motor.name);
                obj.put("limitTemp", motor.limitTemp);
                obj.put("state", motor.state);
                list.put(obj);
            });
            ctx.result(list.toString()).contentType("application/json");
        });

        // POST: Executive Supervision registers a new asset
        app.post("/api/assets", ctx -> {
            JSONObject body = new JSONObject(ctx.body());
            int id = body.getInt("id");
            String name = body.getString("name");
            double tempLimit = body.optDouble("limitTemp", 60.0);

            MotorData newMotor = new MotorData(id, name);
            newMotor.limitTemp = tempLimit; // Set custom safety threshold

            engineFleet.put(id, newMotor);
            System.out.println("[CORE] New Asset Registered: " + name + " (ID: " + id + ")");
            ctx.status(201).result("{\"status\":\"Created\"}");
        });

        // GET: Telemetry history for Charts and Reports
        app.get("/api/history", ctx -> {
            ctx.result(telemetryDAO.getHistory(50).toString());
            ctx.contentType("application/json");
        });

        // GET: Critical failures log for Maintenance Table
        app.get("/api/failures", ctx -> {
            ctx.result(telemetryDAO.getCriticalFailures(5).toString());
            ctx.contentType("application/json");
        });

        // --- WEBSOCKET ROUTES (Real-time Telemetry) ---
        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                sessions.put(ctx.sessionId(), ctx);
                System.out.println("[WS] UI Connection Established: " + ctx.sessionId());
            });
            ws.onClose(ctx -> sessions.remove(ctx.sessionId()));
            ws.onMessage(ctx -> handleWebCommand(ctx.message()));
        });

        setupMQTT();

        System.out.println("\n======================================");
        System.out.println("   NEXUS CORE SYSTEM - ONLINE V2.0    ");
        System.out.println("======================================\n");
    }

    /**
     * Configures MQTT connection to listen to ESP32 telemetry
     */
    private static void setupMQTT() {
        String broker = "tcp://broker.hivemq.com:1883";
        String clientId = "NexusServer_" + System.currentTimeMillis();

        try {
            mqttClient = new MqttClient(broker, clientId, new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setAutomaticReconnect(true);

            mqttClient.connect(options);

            // Dynamic Subscription to all assets under the nexus/motor/ hierarchy
            mqttClient.subscribe("nexus/motor/+/telemetry", (topic, msg) -> {
                try {
                    JSONObject json = new JSONObject(new String(msg.getPayload()));
                    int id = json.getInt("id");

                    // Only process telemetry if the engine is registered in the fleet
                    if (engineFleet.containsKey(id)) {
                        MotorData motor = engineFleet.get(id);
                        motor.updateFromHardware(json);

                        // Save to PostgreSQL via DAO
                        telemetryDAO.insertTelemetry(motor.id, motor.temp, motor.humi, motor.curr, motor.vib, motor.state);

                        // Broadcast updated data to all connected Web Dashboards
                        broadcastToWeb(motor.toJson());
                    }
                } catch (Exception e) {
                    System.err.println("[MQTT] Telemetry Error: " + e.getMessage());
                }
            });
        } catch (MqttException e) {
            System.err.println("[MQTT] Connection Failed: " + e.getMessage());
        }
    }

    /**
     * Processes commands from the Web UI (START/STOP) and forwards to MQTT
     * Expected format "ID:COMMAND" (e.g., "1:START")
     */
    private static void handleWebCommand(String fullCommand) {
        try {
            String[] parts = fullCommand.split(":");
            int motorId = Integer.parseInt(parts[0]);
            String action = parts[1];

            String topic = "nexus/motor/" + motorId + "/control";
            mqttClient.publish(topic, new MqttMessage(action.getBytes()));

            System.out.println("[CMD] " + action + " sent to Asset ID: " + motorId);
        } catch (Exception e) {
            System.err.println("[CMD] Format Error: " + fullCommand);
        }
    }

    private static void broadcastToWeb(String data) {
        sessions.values().forEach(s -> {
            if (s.session.isOpen()) s.send(data);
        });
    }

    /**
     * Inner Class: Represents a single Industrial Motor
     * Handles business logic, safety thresholds, and state management.
     */
    static class MotorData {
        int id;
        String name;
        double temp, humi, curr, vib;
        String state = "STOPPED";

        // Customizable Safety Thresholds (Set by Executive Supervision)
        public double limitTemp = 60.0;
        public double limitCurr = 14.0;
        public double limitVib = 10.0;

        private long lastViolationTime = 0;
        private final long GRACE_PERIOD_MS = 5000; // 5s before emergency shutdown

        public MotorData(int id, String name) {
            this.id = id;
            this.name = name;
        }

        public void updateFromHardware(JSONObject json) {
            this.temp = json.getDouble("temp");
            this.humi = json.getDouble("humi");
            this.curr = json.getDouble("curr");
            this.vib = json.getDouble("vib");

            if(json.has("state")) {
                this.state = json.getString("state");
            }

            checkSafetyProcedures();
        }

        /**
         * Logic for Interlocking and Emergency Shutdown
         */
        private void checkSafetyProcedures() {
            // Safety logic only active during OPERATING mode
            if (!state.equals("OPERATING")) {
                lastViolationTime = 0;
                return;
            }

            // Comparison against dynamic limits
            boolean isViolating = (temp > limitTemp) || (curr > limitCurr) || (Math.abs(vib) > limitVib);

            if (isViolating) {
                if (lastViolationTime == 0) lastViolationTime = System.currentTimeMillis();

                long duration = System.currentTimeMillis() - lastViolationTime;
                if (duration > GRACE_PERIOD_MS) {
                    executeEmergencyShutdown("CRITICAL PARAMETERS EXCEEDED");
                }
            } else {
                lastViolationTime = 0;
            }
        }

        private void executeEmergencyShutdown(String reason) {
            try {
                this.state = "LOCKED_FAILURE";
                System.err.println("\n[SECURITY] EMERGENCY SHUTDOWN FOR ASSET " + id + ": " + reason);

                // Command hardware to stop immediately
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
                    .put("limitTemp", limitTemp)
                    .put("state", state).toString();
        }
    }
}