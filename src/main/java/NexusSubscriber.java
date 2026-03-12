import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class NexusSubscriber {

    public static final String RESET = "\u001B[0m";
    public static final String RED = "\u001B[31m";
    public static final String YELLOW = "\u001B[33m";
    public static final String GREEN = "\u001B[32m";

    private static boolean motorIsHalted = false;

    public static void main(String[] args) {
        String topic = "nexus/motor/1/telemetry";
        String broker = "tcp://broker.hivemq.com:1883";
        String clientId = "JavaServer_Nexus_Analytics";

        try {
            MqttClient client = new MqttClient(broker, clientId, new MemoryPersistence());
            MqttConnectOptions connOpts = new MqttConnectOptions();
            connOpts.setCleanSession(true);

            System.out.println("NEXUS SYSTEM: Connecting to broker...");
            client.connect(connOpts);
            System.out.println("STATUS: Connected. Monitoring Motor 1...\n");

            client.setCallback(new MqttCallback() {
                public void messageArrived(String topic, MqttMessage message) {
                    try {
                        String payload = new String(message.getPayload());
                        JSONObject data = new JSONObject(payload);

                        int id = data.getInt("id");

                        // If motor is halted, we ignore processing to simulate maintenance mode
                        if (motorIsHalted) {
                            return;
                        }

                        double temp = data.getDouble("temp");
                        double humi = data.getDouble("humi");
                        double curr = data.getDouble("curr");
                        double vib = data.getDouble("vib");

                        String statusTemp = analyzeTemperature(temp);
                        String statusCurr = analyzeCurrent(curr);
                        String statusHumi = analyzeHumidity(humi);
                        String statusVib  = analyzeVibration(vib);

                        if (statusTemp.contains(RED) || statusCurr.contains(RED) ||
                                statusHumi.contains(RED) || statusVib.contains(RED)) {

                            triggerEmergencyStop(client, id);
                        } else {
                            renderDashboard(id, temp, statusTemp, curr, statusCurr, humi, statusHumi, vib, statusVib);
                        }

                    } catch (Exception e) {
                        System.err.println("Error: " + e.getMessage());
                    }
                }
                public void connectionLost(Throwable cause) {}
                public void deliveryComplete(IMqttDeliveryToken token) {}
            });

            client.subscribe(topic);
        } catch (MqttException e) { e.printStackTrace(); }
    }

    private static void triggerEmergencyStop(MqttClient client, int motorId) {
        motorIsHalted = true;
        sendCommand(client, motorId, "STOP");
        System.out.println(RED + ">> SYSTEM HALTED - MAINTENANCE MODE ACTIVE <<" + RESET);

        // Auto-restart simulation after 10 seconds
        Executors.newSingleThreadScheduledExecutor().schedule(() -> {
            System.out.println(GREEN + ">> ATTEMPTING REMOTE RESTART... <<" + RESET);
            sendCommand(client, motorId, "START");
            motorIsHalted = false;
        }, 10, TimeUnit.SECONDS);
    }

    private static void sendCommand(MqttClient client, int motorId, String cmd) {
        try {
            client.publish("nexus/motor/" + motorId + "/control", new MqttMessage(cmd.getBytes()));
        } catch (MqttException e) { e.printStackTrace(); }
    }

    private static void renderDashboard(int id, double t, String st, double c, String sc, double h, String sh, double v, String sv) {
        System.out.println("==================================================");
        System.out.println("MOTOR " + id + " - TELEMETRY REPORT");
        System.out.println("--------------------------------------------------");
        System.out.printf("Temperature : %5.1f °C  -> %s\n", t, st);
        System.out.printf("Current     : %5.1f A   -> %s\n", c, sc);
        System.out.printf("Humidity    : %5.1f %%   -> %s\n", h, sh);
        System.out.printf("Vibration   : %5.2f     -> %s\n", v, sv);
        System.out.println("==================================================\n");
    }

    // Safety logic methods stay the same (analyzeTemperature, etc...)
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