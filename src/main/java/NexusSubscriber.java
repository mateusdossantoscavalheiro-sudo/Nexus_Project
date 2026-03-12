import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class NexusSubscriber {

    // Storage for all active engines and web sessions
    private static final Map<Integer, MotorData> engineFleet = new ConcurrentHashMap<>();
    private static final Map<String, WsContext> sessions = new ConcurrentHashMap<>();
    private static IMqttClient mqttClient;

    public static void main(String[] args) {
        // Initialize default engine for testing
        engineFleet.put(1, new MotorData(1, "Main Lathe #01"));

        // --- Javalin Server Setup ---
        var app = Javalin.create(config -> {
            config.staticFiles.add("/public"); // index.html (Login) is served here
        }).start(8080);

        // --- WebSocket Bridge (Frontend <-> Java) ---
        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                sessions.put(ctx.sessionId(), ctx);
                System.out.println("[WS] New session connected: " + ctx.sessionId());
            });

            ws.onClose(ctx -> sessions.remove(ctx.sessionId()));

            ws.onMessage(ctx -> {
                String message = ctx.message();
                handleWebCommand(message);
            });
        });

        // --- MQTT Setup (Wokwi <-> Java) ---
        setupMQTT();

        System.out.println("\n--------------------------------------");
        System.out.println("NEXUS SYSTEM STARTING...");
        System.out.println("HMI available at http://localhost:8080");
        System.out.println("--------------------------------------\n");
    }

    private static void setupMQTT() {
        String broker = "tcp://broker.hivemq.com:1883";
        String clientId = "NexusServer_" + System.currentTimeMillis();

        try {
            mqttClient = new MqttClient(broker, clientId, new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);

            mqttClient.connect(options);

            // Subscribe to all motor telemetries using wildcard (+)
            mqttClient.subscribe("nexus/motor/+/telemetry", (topic, msg) -> {
                try {
                    JSONObject json = new JSONObject(new String(msg.getPayload()));
                    int id = json.getInt("id");

                    if (engineFleet.containsKey(id)) {
                        MotorData motor = engineFleet.get(id);
                        motor.update(json);

                        // Broadcast updated state to all web clients
                        broadcastToWeb(motor.toJson());
                    }
                } catch (Exception e) {
                    System.err.println("Error processing telemetry: " + e.getMessage());
                }
            });

        } catch (MqttException e) {
            System.err.println("MQTT Connection Failed: " + e.getMessage());
        }
    }

    private static void handleWebCommand(String fullCommand) {
        // Expected format "motorId:COMMAND" (e.g., "1:START")
        try {
            String[] parts = fullCommand.split(":");
            int motorId = Integer.parseInt(parts[0]);
            String action = parts[1];

            String topic = "nexus/motor/" + motorId + "/control";
            MqttMessage message = new MqttMessage(action.getBytes());
            mqttClient.publish(topic, message);

            System.out.println("[CMD] Sent " + action + " to Motor " + motorId);

            // If command is maintenance related, update state manually
            if(action.equals("MAINTENANCE_IN")) engineFleet.get(motorId).state = "MAINTENANCE";
            if(action.equals("MAINTENANCE_OUT")) engineFleet.get(motorId).state = "STOPPED";

        } catch (Exception e) {
            System.err.println("Invalid command format: " + fullCommand);
        }
    }

    private static void broadcastToWeb(String data) {
        sessions.values().forEach(s -> {
            if (s.session.isOpen()) s.send(data);
        });
    }

    // --- Inner Class for Engine Representation ---
    static class MotorData {
        int id;
        String name;
        double temp, humi, curr, vib;
        String state = "STOPPED";

        // Timer for safety logic
        private long errorStartTime = 0;
        private final long ERROR_THRESHOLD_MS = 5000;

        public MotorData(int id, String name) {
            this.id = id;
            this.name = name;
        }

        public void update(JSONObject json) {
            this.temp = json.getDouble("temp");
            this.humi = json.getDouble("humi");
            this.curr = json.getDouble("curr");
            this.vib = json.getDouble("vib");

            // 1. CRITICAL: Overheating Logic (5-second persistence)
            if (temp > 60.0 && state.equals("OPERATING")) {
                if (errorStartTime == 0) errorStartTime = System.currentTimeMillis();
                if (System.currentTimeMillis() - errorStartTime > ERROR_THRESHOLD_MS) {
                    triggerSafetyLock("OVERHEAT");
                }
            }
            // 2. CRITICAL: Electrical Overload (Instant shutdown if Current > 14A)
            else if (curr > 14.0 && state.equals("OPERATING")) {
                triggerSafetyLock("ELECTRICAL OVERLOAD");
            }
            // 3. CRITICAL: Mechanical Failure (Instant shutdown if Vibration > 10G)
            else if (vib > 10.0 && state.equals("OPERATING")) {
                triggerSafetyLock("EXCESSIVE VIBRATION");
            }
            else {
                errorStartTime = 0; // Reset timer if conditions are normal
            }

            // 4. WARNINGS: Environment & Maintenance Alerts (Console only, no shutdown)
            checkWarnings();
        }

        private void triggerSafetyLock(String reason) {
            this.state = "LOCKED_FAILURE";
            sendCommandToHardware("STOP");
            System.out.println("\n[!!!] EMERGENCY SHUTDOWN - Motor " + id + " (" + name + ")");
            System.out.println("[REASON]: " + reason);
        }

        private void checkWarnings() {
            if (humi > 75.0) {
                System.out.println("[WARN] High Humidity on Motor " + id + ": " + humi + "%");
            }
            if (curr > 10.0 && curr <= 14.0) {
                System.out.println("[WARN] High Current on Motor " + id + ": " + curr + "A");
            }
        }

        private void sendCommandToHardware(String cmd) {
            try {
                String topic = "nexus/motor/" + id + "/control";
                mqttClient.publish(topic, new MqttMessage(cmd.getBytes()));
            } catch (Exception e) { e.printStackTrace(); }
        }

        public String toJson() {
            JSONObject json = new JSONObject();
            json.put("id", id);
            json.put("name", name);
            json.put("temp", temp);
            json.put("humi", humi);
            json.put("curr", curr);
            json.put("vib", vib);
            json.put("state", state);
            return json.toString();
        }
    }
}