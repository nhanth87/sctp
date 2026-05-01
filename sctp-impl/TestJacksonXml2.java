import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.mobicents.protocols.sctp.netty.SctpPersistData;

public class TestJacksonXml2 {
    public static void main(String[] args) throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<sctp>\n" +
            "  <connectdelay>5000</connectdelay>\n" +
            "  <servers>\n" +
            "    <server name=\"serv1\" hostAddress=\"127.0.0.1\" hostport=\"8012\" started=\"true\" ipChannelType=\"SCTP\" acceptAnonymousConnections=\"false\"/>\n" +
            "  </servers>\n" +
            "  <associations>\n" +
            "    <association name=\"ass1\" hostAddress=\"127.0.0.1\" hostPort=\"0\" peerAddress=\"127.0.0.1\" peerPort=\"8011\" serverName=\"serv1\" ipChannelType=\"SCTP\" type=\"SERVER\" started=\"true\"/>\n" +
            "  </associations>\n" +
            "</sctp>";
        
        XmlMapper mapper = new XmlMapper();
        SctpPersistData data = mapper.readValue(xml, SctpPersistData.class);
        
        System.out.println("Associations map size: " + data.getAssociations().size());
        for (java.util.Map.Entry<String, org.mobicents.protocols.api.Association> entry : data.getAssociations().entrySet()) {
            System.out.println("Key: " + entry.getKey() + ", Name: " + entry.getValue().getName() + ", Started: " + entry.getValue().isStarted());
        }
    }
}
