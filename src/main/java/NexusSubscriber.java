import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;

/**
 * NEXUS PROJECT - INDUSTRIAL MONITORING SYSTEM
 * Java Backend - MQTT Subscriber and Data Analytics Core
 */
public class NexusSubscriber {

    // ANSI Escape Codes for terminal coloring
    public static final String RESET = "\u001B[0m";
    public static final String RED = "\u001B[31m";
    public static final String YELLOW = "\u001B[33m";
    public static final String GREEN = "\u001B[32m";

    // Legend Status Texts (As defined by Project Rules)
    public static final String MSG_GREEN = "[GREEN]: Within acceptable parameters";
    public static final String MSG_YELLOW = "[YELLOW]: Maintenance/Review recommended";
    public static final String MSG_RED = "[RED]: Critical! Stopping motor to prevent damage..";

    public static void main(String[] args) {
        String topic = "nexus/motor/1/telemetry";
        String broker = "tcp://broker.hivemq.com:1883";
        String clientId = "JavaServer_Nexus_Analytics";

        try {
            // Initialize MQTT Client with Memory Persistence
            MqttClient client = new MqttClient(broker, clientId, new MemoryPersistence());
            MqttConnectOptions connOpts = new MqttConnectOptions();
            connOpts.setCleanSession(true);

            System.out.println("NEXUS SYSTEM: Connecting to broker: " + broker);
            client.connect(connOpts);
            System.out.println("STATUS: Connected. Monitoring Motor 1 telemetry...\n");

            // Set up Callback to handle incoming messages
            client.setCallback(new MqttCallback() {
                public void messageArrived(String topic, MqttMessage message) {
                    try {
                        // Extract payload from MQTT message
                        String payload = new String(message.getPayload());
                        JSONObject data = new JSONObject(payload);

                        // Parse JSON fields sent by ESP32
                        int id = data.getInt("id");
                        double temp = data.getDouble("temp");
                        double humi = data.getDouble("humi");
                        double curr = data.getDouble("curr");
                        double vib = data.getDouble("vib");

                        // Apply Safety Logic (The Brain of the System)
                        String statusTemp = analyzeTemperature(temp);
                        String statusCurr = analyzeCurrent(curr);
                        String statusHumi = analyzeHumidity(humi);
                        String statusVib  = analyzeVibration(vib);

                        // Render Formatted Dashboard on Terminal
                        System.out.println("==================================================");
                        System.out.println("MOTOR " + id + " - TELEMETRY REPORT");
                        System.out.println("--------------------------------------------------");
                        System.out.printf("Temperature : %5.1f °C  -> %s\n", temp, statusTemp);
                        System.out.printf("Current     : %5.1f A   -> %s\n", curr, statusCurr);
                        System.out.printf("Humidity    : %5.1f %%   -> %s\n", humi, statusHumi);
                        System.out.printf("Vibration   : %5.2f     -> %s\n", vib, statusVib);
                        System.out.println("==================================================\n");

                    } catch (Exception e) {
                        System.err.println("Error processing data: " + e.getMessage());
                    }
                }

                public void connectionLost(Throwable cause) {
                    System.out.println(RED + "CRITICAL: Connection to MQTT Broker lost!" + RESET);
                }

                public void deliveryComplete(IMqttDeliveryToken token) {}
            });

            // Start listening to the telemetry topic
            client.subscribe(topic);

        } catch (MqttException e) {
            e.printStackTrace();
        }
    }

    // --- SAFETY LOGIC METHODS ---

    private static String analyzeTemperature(double temp) {
        if (temp >= 60.0) return RED + MSG_RED + RESET;
        if (temp >= 45.0) return YELLOW + MSG_YELLOW + RESET;
        return GREEN + MSG_GREEN + RESET;
    }

    private static String analyzeCurrent(double curr) {
        if (curr >= 12.0) return RED + MSG_RED + RESET;
        if (curr >= 8.0) return YELLOW + MSG_YELLOW + RESET;
        return GREEN + MSG_GREEN + RESET;
    }

    private static String analyzeHumidity(double humi) {
        if (humi >= 70.0) return RED + MSG_RED + RESET;
        if (humi >= 50.0) return YELLOW + MSG_YELLOW + RESET;
        return GREEN + MSG_GREEN + RESET;
    }

    private static String analyzeVibration(double vib) {
        if (vib >= 9.0) return RED + MSG_RED + RESET;
        if (vib >= 5.0) return YELLOW + MSG_YELLOW + RESET;
        return GREEN + MSG_GREEN + RESET;
    }
}