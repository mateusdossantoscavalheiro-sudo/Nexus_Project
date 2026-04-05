import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import io.javalin.http.staticfiles.Location;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import java.util.List;
import java.util.ArrayList;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * NEXUS Industrial Controller - Core Server
 * Version: 4.1 (FIXED - Database Integration)
 * Role: Bridges MQTT, WebSockets, and REST API with Dynamic Asset Switching.
 */
public class NexusSubscriber {
    // In-memory storage for active engines
    private static final Map<Integer, MotorData> engineFleet = new ConcurrentHashMap<>();
    // Active WebSocket sessions
    private static final Map<String, WsContext> sessions = new ConcurrentHashMap<>();

    private static IMqttClient mqttClient;
    private static final TelemetryDAO telemetryDAO = new TelemetryDAO();

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

        try {
            List<JSONObject> saved = telemetryDAO.getAllAssets();
            System.out.println("[DATABASE] Attempting to load assets from Supabase...");

            for (JSONObject a : saved) {
                MotorData m = new MotorData(a.getInt("id"), a.getString("name"));
                m.limitTemp = a.getDouble("limitTemp");
                m.limitCurr = a.getDouble("limitCurr");
                m.limitVib = a.getDouble("limitVib");
                m.state = a.getString("state");
                engineFleet.put(m.id, m);
            }
            System.out.println("[SYSTEM] ✅ Loaded " + engineFleet.size() + " assets from database.");
        } catch (Exception e) {
            System.err.println("[DATABASE] ⚠️ Failed to load assets from database: " + e.getMessage());
            System.err.println("[SYSTEM] Starting with empty asset fleet. You can add assets via the UI.");
            e.printStackTrace();
        }

        var app = Javalin.create(config -> {
            // Servir arquivos estáticos da pasta resources/public
            config.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/";
                staticFiles.directory = "/public";
                staticFiles.location = Location.CLASSPATH;
            });

            config.bundledPlugins.enableCors(cors -> {
                cors.addRule(it -> {
                    it.anyHost();
                });
            });
        }).start(port);

        // --- REST API ROUTES ---

        app.get("/api/health", ctx -> {
            JSONObject health = new JSONObject();
            health.put("status", "online");
            health.put("assets_count", engineFleet.size());
            health.put("db_connection", testDatabaseConnection());
            ctx.json(health.toString()).contentType("application/json");
        });

        app.get("/api/history", ctx -> {
            try {
                List<JSONObject> history = telemetryDAO.getHistory(1000);
                JSONArray arr = new JSONArray();
                for (JSONObject obj : history) {
                    arr.put(obj);
                }
                ctx.result(arr.toString()).contentType("application/json");
            } catch (Exception e) {
                ctx.status(500).result("Error fetching history: " + e.getMessage());
            }
        });

        app.get("/api/failures", ctx -> {
            try {
                List<JSONObject> failures = telemetryDAO.getCriticalFailures(100);
                JSONArray arr = new JSONArray();
                for (JSONObject obj : failures) {
                    arr.put(obj);
                }
                ctx.result(arr.toString()).contentType("application/json");
            } catch (Exception e) {
                ctx.status(500).result("Error fetching failures: " + e.getMessage());
            }
        });

        app.get("/api/assets", ctx -> {
            try {
                JSONArray arr = new JSONArray();
                for (MotorData m : engineFleet.values()) {
                    arr.put(new JSONObject(m.toJson()));
                }
                ctx.result(arr.toString()).contentType("application/json");
                System.out.println("[API] Returned " + engineFleet.size() + " assets");
            } catch (Exception e) {
                System.err.println("[API ERROR] Failed to serialize assets: " + e.getMessage());
                ctx.status(500).result("Internal error");
            }
        });

        app.post("/api/assets", ctx -> {
            try {
                String bodyStr = ctx.body();
                System.out.println("[API] Received POST /api/assets with body: " + bodyStr);

                JSONObject body = new JSONObject(bodyStr);

                if (!body.has("id") || !body.has("name")) {
                    ctx.status(400).result("Missing required fields: id and name");
                    return;
                }

                int id = body.getInt("id");
                String name = body.getString("name");

                MotorData motor = new MotorData(id, name);

                motor.limitTemp = body.optDouble("limitTemp", 60.0);
                motor.limitCurr = body.optDouble("limitCurr", 14.0);
                motor.limitVib = body.optDouble("limitVib", 10.0);

                engineFleet.put(id, motor);
                System.out.println("[API] Asset ID " + id + " saved to RAM");

                try {
                    telemetryDAO.saveOrUpdateAsset(id, name, motor.limitTemp, motor.limitCurr, motor.limitVib);
                    System.out.println("[API] Asset ID " + id + " synced to Supabase");
                    ctx.status(201).result("Asset created and synced to database");
                } catch (Exception dbError) {
                    System.err.println("[API] Failed to sync to database: " + dbError.getMessage());
                    ctx.status(201).result("Asset created in memory, but database sync failed: " + dbError.getMessage());
                }

            } catch (Exception e) {
                e.printStackTrace();
                ctx.status(400).result("Error parsing request: " + e.getMessage());
            }
        });

        app.delete("/api/assets/{id}", ctx -> {
            try {
                int id = Integer.parseInt(ctx.pathParam("id"));

                if (engineFleet.remove(id) != null) {
                    System.out.println("[API] Asset ID " + id + " removed from fleet");

                    try {
                        telemetryDAO.deleteAsset(id);
                    } catch (Exception e) {
                        System.err.println("[API] Failed to delete from database: " + e.getMessage());
                    }

                    ctx.status(200).result("Asset deleted");
                } else {
                    ctx.status(404).result("Asset not found");
                }
            } catch (Exception e) {
                ctx.status(500).result("Error: " + e.getMessage());
            }
        });

        // --- WEBSOCKET ROUTES ---
        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                sessions.put(ctx.sessionId(), ctx);
                System.out.println("[WS] UI Connection Established: " + ctx.sessionId());

                JSONArray currentState = new JSONArray();
                for (MotorData m : engineFleet.values()) {
                    currentState.put(new JSONObject(m.toJson()));
                }
                ctx.send(currentState.toString());
            });

            ws.onClose(ctx -> {
                sessions.remove(ctx.sessionId());
                System.out.println("[WS] Connection closed: " + ctx.sessionId());
            });

            ws.onMessage(ctx -> handleWebCommand(ctx.message()));
        });

        setupMQTT();

        System.out.println("\n======================================");
        System.out.println("   NEXUS CORE SYSTEM - ONLINE V4.1   ");
        System.out.println("======================================");
        System.out.println("   Server running on port: " + port);
        System.out.println("   Assets loaded: " + engineFleet.size());
        System.out.println("======================================\n");
    }

    private static String testDatabaseConnection() {
        try {
            telemetryDAO.getAllAssets();
            return "connected";
        } catch (Exception e) {
            return "disconnected: " + e.getMessage();
        }
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
            System.out.println("[MQTT] Connected to HiveMQ broker");

            // Listen to telemetry from ANY motor ID
            mqttClient.subscribe("nexus/motor/+/telemetry", (topic, msg) -> {
                try {
                    JSONObject json = new JSONObject(new String(msg.getPayload()));
                    int id = json.getInt("id");

                    if (engineFleet.containsKey(id)) {
                        MotorData motor = engineFleet.get(id);
                        motor.updateFromHardware(json);

                        // Save to database
                        try {
                            telemetryDAO.insertTelemetry(motor.id, motor.temp, motor.humi, motor.curr, motor.vib, motor.state);
                        } catch (Exception e) {
                            System.err.println("[MQTT] Failed to save telemetry to DB: " + e.getMessage());
                        }

                        // Broadcast to all Web UIs
                        broadcastToWeb(motor.toJson());
                    }
                } catch (Exception e) {
                    System.err.println("[MQTT] Telemetry Error: " + e.getMessage());
                }
            });
        } catch (MqttException e) {
            System.err.println("[MQTT] Connection Failed: " + e.getMessage());
            System.err.println("[MQTT] System will work without MQTT hardware integration");
        }
    }

    private static void handleWebCommand(String fullCommand) {
        try {
            if (fullCommand.startsWith("SWITCH_ID:")) {
                String targetId = fullCommand.split(":")[1];
                System.out.println("[CORE] Context Switch Requested -> Simulate ID: " + targetId);
                mqttClient.publish("nexus/motor/control", new MqttMessage(fullCommand.getBytes()));
                return;
            }

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
            if (s.session.isOpen()) {
                s.send(data);
            }
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
            this.temp = json.optDouble("temp", 0.0);
            this.humi = json.optDouble("humi", 0.0);
            this.curr = json.optDouble("curr", 0.0);
            this.vib = json.optDouble("vib", 0.0);
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
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        public String toJson() {
            return new JSONObject()
                    .put("id", this.id)
                    .put("name", this.name)
                    .put("temp", this.temp)
                    .put("humi", this.humi)
                    .put("curr", this.curr)
                    .put("vib", this.vib)
                    .put("limitTemp", this.limitTemp)
                    .put("limitCurr", this.limitCurr)
                    .put("limitVib", this.limitVib)
                    .put("state", this.state)
                    .toString();
        }
    }
}
