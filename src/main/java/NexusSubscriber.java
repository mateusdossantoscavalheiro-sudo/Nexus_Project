import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/**
 * NEXUS PROJECT - CORE SUBSCRIBER
 * This class intercepts data from the Wokwi simulation.
 */
public class NexusSubscriber {

    public static void main(String[] args) {
        String topic = "nexus/motor/1/telemetry";
        String broker = "tcp://broker.hivemq.com:1883";
        String clientId = "JavaServer_Nexus";

        try {
            MqttClient client = new MqttClient(broker, clientId, new MemoryPersistence());
            MqttConnectOptions connOpts = new MqttConnectOptions();
            connOpts.setCleanSession(true);

            System.out.println("NEXUS SYSTEM: Connecting to broker: " + broker);
            client.connect(connOpts);
            System.out.println("STATUS: Connected. Waiting for telemetry...");

            // Callback to handle incoming messages
            client.setCallback(new MqttCallback() {
                public void messageArrived(String topic, MqttMessage message) {
                    String payload = new String(message.getPayload());
                    System.out.println("\n[DATA RECEIVED FROM MOTOR 1]");
                    System.out.println("Payload: " + payload);

                    // Future step: Here we will parse JSON and send to PostgreSQL
                }

                public void connectionLost(Throwable cause) {
                    System.out.println("CRITICAL: Connection lost!");
                }

                public void deliveryComplete(IMqttDeliveryToken token) {}
            });

            client.subscribe(topic);

        } catch (MqttException e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}