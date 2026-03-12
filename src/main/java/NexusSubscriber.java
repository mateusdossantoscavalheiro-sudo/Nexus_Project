import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;
import java.util.concurrent.ConcurrentHashMap;

public class NexusSubscriber {

    // --- ANSI Colors (Keep existing) ---
    public static final String RESET = "\u001B[0m";
    public static final String RED = "\u001B[31m";
    public static final String YELLOW = "\u001B[33m";
    public static final String GREEN = "\u001B[32m";

    // --- System States (New Industrial Logic) ---
    enum MotorState { OPERATING, STOPPED, LOCKED_FAILURE, MAINTENANCE }
    private static MotorState currentState = MotorState.STOPPED;

    // Timer for 5s persistence
    private static long errorStartTime = 0;
    private static final int ERROR_THRESHOLD_MS = 5000;

    // MQTT & Web Config
    private static MqttClient mqttClient;
    private static final String TELEMETRY_TOPIC = "nexus/motor/1/telemetry";
    private static final String CONTROL_TOPIC = "nexus/motor/1/control";
    private static final ConcurrentHashMap<String, WsContext> webClients = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        // 1. Initialize Javalin Server (HMI)
        var app = Javalin.create(config -> {
            config.staticFiles.add("/public"); // Folder for index.html
        }).start(8080);

        // 2. WebSocket Connection (Bridge to Browser)
        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> webClients.put(ctx.sessionId(), ctx));
            ws.onClose(ctx -> webClients.remove(ctx.sessionId()));
            ws.onMessage(ctx -> handleIhmCommands(ctx.message()));
        });

        System.out.println(GREEN + "NEXUS SERVER: HMI running at http://localhost:8080" + RESET);

        // 3. Initialize MQTT (Existing Logic)
        setupMqtt();
    }

    private static void setupMqtt() {
        String broker = "tcp://broker.hivemq.com:1883";
        String clientId = "JavaServer_Nexus_Analytics";

        try {
            mqttClient = new MqttClient(broker, clientId, new MemoryPersistence());
            MqttConnectOptions connOpts = new MqttConnectOptions();
            connOpts.setCleanSession(true);

            System.out.println("NEXUS SYSTEM: Connecting to broker...");
            mqttClient.connect(connOpts);
            System.out.println("STATUS: Connected. Monitoring Motor 1...\n");

            mqttClient.setCallback(new MqttCallback() {
                public void messageArrived(String topic, MqttMessage message) {
                    try {
                        String payload = new String(message.getPayload());
                        processMotorLogic(payload);
                    } catch (Exception e) {
                        System.err.println("Error processing data: " + e.getMessage());
                    }
                }
                public void connectionLost(Throwable cause) { System.out.println(RED + "Connection Lost!" + RESET); }
                public void deliveryComplete(IMqttDeliveryToken token) {}
            });

            mqttClient.subscribe(TELEMETRY_TOPIC);
        } catch (MqttException e) { e.printStackTrace(); }
    }

    private static void processMotorLogic(String payload) {
        JSONObject data = new JSONObject(payload);
        int id = data.getInt("id");
        double temp = data.getDouble("temp");
        double humi = data.getDouble("humi");
        double curr = data.getDouble("curr");
        double vib = data.getDouble("vib");

        // Safety Analysis (Keep existing methods)
        String st = analyzeTemperature(temp);
        String sc = analyzeCurrent(curr);
        String sh = analyzeHumidity(humi);
        String sv = analyzeVibration(vib);

        // --- 5 SECONDS PERSISTENCE LOGIC ---
        boolean hasCriticalError = st.contains(RED) || sc.contains(RED) || sh.contains(RED) || sv.contains(RED);

        if (hasCriticalError && currentState == MotorState.OPERATING) {
            if (errorStartTime == 0) errorStartTime = System.currentTimeMillis();

            long elapsed = System.currentTimeMillis() - errorStartTime;
            if (elapsed >= ERROR_THRESHOLD_MS) {
                System.out.println(RED + "!! CRITICAL PERSISTENCE DETECTED (5s) !!" + RESET);
                changeState(MotorState.LOCKED_FAILURE);
            }
        } else {
            errorStartTime = 0; // Reset timer if error disappears
        }

        // Render to Terminal (Keep existing view)
        if (currentState != MotorState.MAINTENANCE) {
            renderDashboard(id, temp, st, curr, sc, humi, sh, vib, sv);
        }

        // Broadcast to HMI
        data.put("state", currentState.toString());
        broadcastToIhm(data.toString());
    }

    private static void handleIhmCommands(String command) {
        // Logic Rules: Cannot start if locked or in maintenance
        switch (command) {
            case "START":
                if (currentState == MotorState.STOPPED) changeState(MotorState.OPERATING);
                break;
            case "STOP":
                if (currentState == MotorState.OPERATING) changeState(MotorState.STOPPED);
                break;
            case "MAINTENANCE_IN":
                changeState(MotorState.MAINTENANCE);
                break;
            case "MAINTENANCE_OUT":
                if (currentState == MotorState.MAINTENANCE) changeState(MotorState.STOPPED);
                break;
        }
    }

    private static void changeState(MotorState newState) {
        currentState = newState;
        System.out.println(YELLOW + "STATE CHANGED TO: " + newState + RESET);

        // Command ESP32 via MQTT
        String mqttCmd = (newState == MotorState.OPERATING) ? "START" : "STOP";
        try {
            mqttClient.publish(CONTROL_TOPIC, new MqttMessage(mqttCmd.getBytes()));
        } catch (MqttException e) { e.printStackTrace(); }
    }

    private static void broadcastToIhm(String data) {
        webClients.values().forEach(ctx -> ctx.send(data));
    }

    // --- Existing Methods (Exactly as they were) ---
    private static void renderDashboard(int id, double t, String st, double c, String sc, double h, String sh, double v, String sv) {
        System.out.println("==================================================");
        System.out.println("MOTOR " + id + " - STATUS: " + currentState);
        System.out.println("--------------------------------------------------");
        System.out.printf("Temperature : %5.1f °C  -> %s\n", t, st);
        System.out.printf("Current     : %5.1f A   -> %s\n", c, sc);
        System.out.printf("Humidity    : %5.1f %%   -> %s\n", h, sh);
        System.out.printf("Vibration   : %5.2f     -> %s\n", v, sv);
        System.out.println("==================================================\n");
    }

    private static String analyzeTemperature(double temp) {
        if (temp >= 60.0) return RED + "[RED]: Critical!" + RESET;
        if (temp >= 45.0) return YELLOW + "[YELLOW]: Warning" + RESET;
        return GREEN + "[GREEN]: OK" + RESET;
    }

    private static String analyzeCurrent(double curr) {
        if (curr >= 12.0) return RED + "[RED]: Critical!" + RESET;
        if (curr >= 8.0) return YELLOW + "[YELLOW]: Warning" + RESET;
        return GREEN + "[GREEN]: OK" + RESET;
    }

    private static String analyzeHumidity(double humi) {
        if (humi >= 70.0) return RED + "[RED]: Critical!" + RESET;
        if (humi >= 50.0) return YELLOW + "[YELLOW]: Warning" + RESET;
        return GREEN + "[GREEN]: OK" + RESET;
    }

    private static String analyzeVibration(double vib) {
        if (vib >= 9.0) return RED + "[RED]: Critical!" + RESET;
        if (vib >= 5.0) return YELLOW + "[YELLOW]: Warning" + RESET;
        return GREEN + "[GREEN]: OK" + RESET;
    }
}