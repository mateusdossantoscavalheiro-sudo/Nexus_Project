import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class NexusSubscriber {

    private static final Map<Integer, MotorData> engineFleet = new ConcurrentHashMap<>();
    private static final Map<String, WsContext> sessions = new ConcurrentHashMap<>();
    private static IMqttClient mqttClient;

    public static void main(String[] args) {
        // Initializing the fleet
        engineFleet.put(1, new MotorData(1, "Main Lathe #01"));

        var app = Javalin.create(config -> {
            config.staticFiles.add("/public");
        }).start(8080);

        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> sessions.put(ctx.sessionId(), ctx));
            ws.onClose(ctx -> sessions.remove(ctx.sessionId()));
            ws.onMessage(ctx -> handleWebCommand(ctx.message()));
        });

        setupMQTT();

        System.out.println("\n--------------------------------------");
        System.out.println("NEXUS SYSTEM ONLINE");
        System.out.println("--------------------------------------\n");
    }

    private static void setupMQTT() {
        try {
            mqttClient = new MqttClient("tcp://broker.hivemq.com:1883", "NexusServer_" + System.currentTimeMillis(), new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            mqttClient.connect(options);

            // The HEART of the system: Receiving updates from hardware
            mqttClient.subscribe("nexus/motor/+/telemetry", (topic, msg) -> {
                try {
                    JSONObject json = new JSONObject(new String(msg.getPayload()));
                    int id = json.getInt("id");

                    if (engineFleet.containsKey(id)) {
                        MotorData motor = engineFleet.get(id);

                        // IMPORTANT: We update the state BASED ON HARDWARE feedback
                        motor.updateFromHardware(json);

                        broadcastToWeb(motor.toJson());
                    }
                } catch (Exception e) {
                    System.err.println("Telemetry Error: " + e.getMessage());
                }
            });
        } catch (MqttException e) { e.printStackTrace(); }
    }

    private static void handleWebCommand(String fullCommand) {
        try {
            String[] parts = fullCommand.split(":");
            int motorId = Integer.parseInt(parts[0]);
            String action = parts[1];

            // We only send the command. We DON'T change the state here anymore.
            // The state will only change when the ESP32 confirms it via Telemetry.
            String topic = "nexus/motor/" + motorId + "/control";
            mqttClient.publish(topic, new MqttMessage(action.getBytes()));

            System.out.println("[CMD] Command sent to Hardware: " + action + " for Motor " + motorId);
        } catch (Exception e) {
            System.err.println("Command Error: " + e.getMessage());
        }
    }

    private static void broadcastToWeb(String data) {
        sessions.values().forEach(s -> { if (s.session.isOpen()) s.send(data); });
    }

    static class MotorData {
        int id;
        String name;
        double temp, humi, curr, vib;
        String state = "STOPPED";

        private long errorStartTime = 0;
        private final long ERROR_THRESHOLD_MS = 5000;

        public MotorData(int id, String name) {
            this.id = id;
            this.name = name;
        }

        public void updateFromHardware(JSONObject json) {
            this.temp = json.getDouble("temp");
            this.humi = json.getDouble("humi");
            this.curr = json.getDouble("curr");
            this.vib = json.getDouble("vib");

            // Sincronização de Estado
            if(json.has("state")) {
                this.state = json.getString("state");
            }

            // EXIBIR STATUS NO TERMINAL (Como você pediu)
            System.out.printf("[Motor %d] T: %.1f | C: %.1fA | Status: %s\n", id, temp, curr, state);

            checkSafety();
        }

        private void checkSafety() {
            // AQUI ESTAVA O ERRO: Agora ele verifica se está OPERATING ou OPERATIONAL
            boolean isRunning = state.equals("OPERATING") || state.equals("OPERATIONAL");

            if (isRunning) {
                // 1. Temperatura
                if (temp > 60.0) {
                    if (errorStartTime == 0) errorStartTime = System.currentTimeMillis();
                    if (System.currentTimeMillis() - errorStartTime > ERROR_THRESHOLD_MS) {
                        triggerEmergencyStop("CRITICAL OVERHEAT");
                    }
                } else {
                    errorStartTime = 0;
                }

                // 2. Corrente (Segurança de 14A)
                if (curr > 14.0) {
                    triggerEmergencyStop("ELECTRICAL OVERLOAD");
                }
            }
        }

        private void triggerEmergencyStop(String reason) {
            try {
                this.state = "LOCKED_FAILURE"; // Define o estado de erro localmente para o HTML piscar
                System.err.println("\n[!!!] SECURITY ACTIVATED: " + reason);
                String topic = "nexus/motor/" + id + "/control";
                mqttClient.publish(topic, new MqttMessage("STOP".getBytes()));

                // Enviamos um broadcast imediato para a tela piscar em vermelho
                broadcastToWeb(this.toJson());
            } catch (Exception e) { e.printStackTrace(); }
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