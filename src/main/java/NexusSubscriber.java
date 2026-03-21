import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * NEXUS Industrial Controller - Core Server
 * Version: 2.5 (Context-Aware Integration)
 * Role: Bridges MQTT, WebSockets, and REST API with Dynamic Asset Switching.
 */
public class NexusSubscriber {
    // In-memory storage for active engines
    private static final Map<Integer, MotorData> engineFleet = new ConcurrentHashMap<>();
    // Active WebSocket sessions
    private static final Map<String, WsContext> sessions = new ConcurrentHashMap<>();

    private static IMqttClient mqttClient;
    // Note: Assuming TelemetryDAO is in your project. If not, comment out DAO lines.
    private static final TelemetryDAO telemetryDAO = new TelemetryDAO();

    public static void main(String[] args) {
        // Default asset
        engineFleet.put(1, new MotorData(1, "Main Lathe #01"));

        // Initialize Javalin
        var app = Javalin.create(config -> {
            config.staticFiles.add("/public");
        }).start(8080);

        // --- REST API ROUTES ---

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

        app.post("/api/assets", ctx -> {
            JSONObject body = new JSONObject(ctx.body());
            int id = body.getInt("id");
            String name = body.getString("name");
            double tempLimit = body.optDouble("limitTemp", 60.0);

            MotorData newMotor = new MotorData(id, name);
            newMotor.limitTemp = tempLimit;

            engineFleet.put(id, newMotor);
            System.out.println("[CORE] New Asset Registered: " + name + " (ID: " + id + ")");
            ctx.status(201).result("{\"status\":\"Created\"}");
        });

        // --- WEBSOCKET ROUTES ---
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
        System.out.println("   NEXUS CORE SYSTEM - ONLINE V2.5    ");
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

            // Listen to telemetry from ANY motor ID
            mqttClient.subscribe("nexus/motor/+/telemetry", (topic, msg) -> {
                try {
                    JSONObject json = new JSONObject(new String(msg.getPayload()));
                    int id = json.getInt("id");

                    if (engineFleet.containsKey(id)) {
                        MotorData motor = engineFleet.get(id);
                        motor.updateFromHardware(json);

                        // Save to database
                        telemetryDAO.insertTelemetry(motor.id, motor.temp, motor.humi, motor.curr, motor.vib, motor.state);

                        // Broadcast to all Web UIs
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
     * UPDATED: Processes commands and context switches
     */
    private static void handleWebCommand(String fullCommand) {
        try {
            // New Logic: Handle "SWITCH_ID:X" command
            if (fullCommand.startsWith("SWITCH_ID:")) {
                String targetId = fullCommand.split(":")[1];
                System.out.println("[CORE] Context Switch Requested -> Simulate ID: " + targetId);

                // Notify ESP32 to change its reporting ID
                // Topic: nexus/motor/control (Global control channel)
                mqttClient.publish("nexus/motor/control", new MqttMessage(fullCommand.getBytes()));
                return;
            }

            // Standard Logic: Handle "ID:ACTION" (e.g., "1:START")
            String[] parts = fullCommand.split(":");
            int motorId = Integer.parseInt(parts[0]);
            String action = parts[1];

            String topic = "nexus/motor/" + motorId + "/control";
            mqttClient.publish(topic, new MqttMessage(action.getBytes()));

            System.out.println("[CMD] " + action + " sent to Asset ID: " + motorId);
        } catch (Exception e) {
            System.err.println("[CMD] Process Error: " + fullCommand + " | " + e.getMessage());
        }
    }

    private static void broadcastToWeb(String data) {
        sessions.values().forEach(s -> {
            if (s.session.isOpen()) s.send(data);
        });
    }

    /**
     * Inner Class: Represents a single Industrial Motor
     */
    static class MotorData {
        int id;
        String name;
        double temp, humi, curr, vib;
        String state = "STOPPED";
        public double limitTemp = 60.0;
        public double limitCurr = 14.0;
        public double limitVib = 10.0;

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
            if(json.has("state")) this.state = json.getString("state");
            checkSafetyProcedures();
        }

        private void checkSafetyProcedures() {
            if (!state.equals("OPERATING")) {
                lastViolationTime = 0;
                return;
            }
            boolean isViolating = (temp > limitTemp) || (curr > limitCurr) || (Math.abs(vib) > limitVib);
            if (isViolating) {
                if (lastViolationTime == 0) lastViolationTime = System.currentTimeMillis();
                if (System.currentTimeMillis() - lastViolationTime > GRACE_PERIOD_MS) {
                    executeEmergencyShutdown("CRITICAL PARAMETERS EXCEEDED");
                }
            } else {
                lastViolationTime = 0;
            }
        }

        private void executeEmergencyShutdown(String reason) {
            try {
                this.state = "LOCKED_FAILURE";
                System.err.println("[SECURITY] EMERGENCY SHUTDOWN ID " + id + ": " + reason);
                mqttClient.publish("nexus/motor/" + id + "/control", new MqttMessage("STOP".getBytes()));
                broadcastToWeb(this.toJson());
            } catch (Exception e) { e.printStackTrace(); }
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