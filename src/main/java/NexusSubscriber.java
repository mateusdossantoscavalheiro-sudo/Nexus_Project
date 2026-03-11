import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;

public class NexusSubscriber {

    // Códigos ANSI para colorir o terminal
    public static final String RESET = "\u001B[0m";
    public static final String RED = "\u001B[31m";
    public static final String YELLOW = "\u001B[33m";
    public static final String GREEN = "\u001B[32m";

    // Textos de Legenda
    public static final String MSG_VERDE = "[VERDE]: Dentro do parametro aceitavel";
    public static final String MSG_AMARELO = "[AMARELO]: Recomendado revisão/manutenção";
    public static final String MSG_VERMELHO = "[VERMELHO]: Critico! Parando motor para evitar perda..";

    public static void main(String[] args) {
        String topic = "nexus/motor/1/telemetry";
        String broker = "tcp://broker.hivemq.com:1883";
        String clientId = "JavaServer_Nexus_Analytics";

        try {
            MqttClient client = new MqttClient(broker, clientId, new MemoryPersistence());
            MqttConnectOptions connOpts = new MqttConnectOptions();
            connOpts.setCleanSession(true);

            System.out.println("NEXUS SYSTEM: Conectando ao broker...");
            client.connect(connOpts);
            System.out.println("STATUS: Conectado. Motor 1 em monitoramento contínuo...\n");

            client.setCallback(new MqttCallback() {
                public void messageArrived(String topic, MqttMessage message) {
                    try {
                        String payload = new String(message.getPayload());
                        JSONObject data = new JSONObject(payload);

                        int id = data.getInt("id");
                        double temp = data.getDouble("temp");
                        double humi = data.getDouble("humi");
                        double curr = data.getDouble("curr");
                        double vib = data.getDouble("vib");

                        // Lógica de Análise (O Cérebro do Sistema)
                        String statusTemp = analisarTemperatura(temp);
                        String statusCurr = analisarCorrente(curr);
                        String statusHumi = analisarHumidade(humi);
                        String statusVib  = analisarVibracao(vib);

                        // Exibição Formatada na IHM do Terminal
                        System.out.println("==================================================");
                        System.out.println("MOTOR " + id + " - RELATÓRIO DE TELEMETRIA");
                        System.out.println("--------------------------------------------------");
                        System.out.printf("Temperatura : %5.1f °C  -> %s\n", temp, statusTemp);
                        System.out.printf("Corrente    : %5.1f A   -> %s\n", curr, statusCurr);
                        System.out.printf("Humidade    : %5.1f %%   -> %s\n", humi, statusHumi);
                        System.out.printf("Vibração    : %5.2f     -> %s\n", vib, statusVib);
                        System.out.println("==================================================\n");

                    } catch (Exception e) {
                        System.err.println("Erro ao processar dados: " + e.getMessage());
                    }
                }

                public void connectionLost(Throwable cause) {
                    System.out.println(RED + "CRÍTICO: Conexão com o broker perdida!" + RESET);
                }

                public void deliveryComplete(IMqttDeliveryToken token) {}
            });

            client.subscribe(topic);

        } catch (MqttException e) {
            e.printStackTrace();
        }
    }

    // --- MÉTODOS DE LÓGICA DE SEGURANÇA ---

    private static String analisarTemperatura(double temp) {
        if (temp >= 60.0) return RED + MSG_VERMELHO + RESET;
        if (temp >= 45.0) return YELLOW + MSG_AMARELO + RESET;
        return GREEN + MSG_VERDE + RESET;
    }

    private static String analisarCorrente(double curr) {
        if (curr >= 12.0) return RED + MSG_VERMELHO + RESET;
        if (curr >= 8.0) return YELLOW + MSG_AMARELO + RESET;
        return GREEN + MSG_VERDE + RESET;
    }

    private static String analisarHumidade(double humi) {
        if (humi >= 70.0) return RED + MSG_VERMELHO + RESET;
        if (humi >= 50.0) return YELLOW + MSG_AMARELO + RESET;
        return GREEN + MSG_VERDE + RESET;
    }

    private static String analisarVibracao(double vib) {
        if (vib >= 9.0) return RED + MSG_VERMELHO + RESET;
        if (vib >= 5.0) return YELLOW + MSG_AMARELO + RESET;
        return GREEN + MSG_VERDE + RESET;
    }
}