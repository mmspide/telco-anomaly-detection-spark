package com.mapr.cell;

import akka.actor.UntypedActor;
import com.mapr.cell.common.CDR;
import com.mapr.cell.common.Config;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.json.JSONObject;

import java.util.Random;

/**
 * Each tower is an actor that receives messages from Callers.
 */
public class Tower extends UntypedActor {
    private static final double MINIMUM_RECEIVE_POWER = -1000;
    private static int TOWER_ID = 1;
    private final Random rand;

    private Antenna ax;
    private String id;

    final String TOPIC_NAME = "/telco:tower%s";
    final String INIT_NAME = "/telco:init";

    private KafkaProducer<String, String> producer;

    public Tower() {
        producer = new KafkaProducer<>(Config.getConfig().getPrefixedProps("kafka."));
        rand = new Random();
        ax = Antenna.omni(rand.nextDouble() * 20e3, rand.nextDouble() * 20e3);
        ax.setPower(1, 0.01);
        id = String.format("%d", TOWER_ID++);
    }

    @Override
    public void onReceive(Object message) throws Exception {
        if (message instanceof Messages.Setup) {
            System.out.printf("Setup complete for tower %s\n", id);
            producer.send(new ProducerRecord<>(INIT_NAME, ax.toJSONObject().put("towerId", id).toString()));
        } else if (message instanceof Messages.SignalReportRequest) {
            Messages.SignalReportRequest m = (Messages.SignalReportRequest) message;
            double r = ax.distance(m.x, m.y);
            double p = ax.power(m.x, m.y);
            if (p > MINIMUM_RECEIVE_POWER) {
                m.source.tell(new Messages.SignalReport(r, p, id, getSelf()));
            }
        } else if (message instanceof Messages.Hello) {
            Messages.Hello helloMessage = (Messages.Hello) message;
            double u = rand.nextDouble();
            if (u < 0.8) {
                System.out.printf("Start call caller %s to tower %s\n", helloMessage.cdr.getCallerId(), id );
                helloMessage.caller.tell(new Messages.Connect(id, getSelf()));
                System.out.println("Connect CDR sent: " + helloMessage.cdr.toJSONObject());
                sendToStream(helloMessage.cdr.toJSONObject());
                if (helloMessage.reconnect) {
                    helloMessage.cdr.setState(CDR.State.RECONNECT);
                    System.out.println("Reconnect CDR sent: " + helloMessage.cdr.toJSONObject());
                    sendToStream(helloMessage.cdr.toJSONObject());
                }
            } else if (u < 0.95) {
                System.out.printf("Failed call caller %s to tower %s\n", helloMessage.cdr.getCallerId(), id );
                helloMessage.cdr.setState(CDR.State.FAIL);
                sendToStream(helloMessage.cdr.toJSONObject());
                helloMessage.caller.tell(new Messages.Fail(id));
            } else {
                // ignore request occasionally ... it will make the caller stronger
            }
        } else if (message instanceof Messages.Disconnect) {
            Messages.Disconnect disconnectMessage = (Messages.Disconnect) message;
            System.out.printf("Finished call caller %s to tower %s\n", disconnectMessage.callerId, id );
            System.out.println("Finished CDR sent: " + disconnectMessage.cdr.toJSONObject());
            sendToStream(disconnectMessage.cdr.toJSONObject());
        } else {
            unhandled(message);
        }
    }


    private void sendToStream(JSONObject jsonObject) {
        producer.send(new ProducerRecord<String, String>(
                String.format(TOPIC_NAME, id),
                jsonObject.toString()), new Callback() {
            @Override
            public void onCompletion(RecordMetadata recordMetadata, Exception e) {
                if (e != null) {
                    System.err.println("Exception occurred while sending :(");
                    System.err.println(e.toString());
                    return;
                }
            }
        });
        producer.flush();
    }
}
