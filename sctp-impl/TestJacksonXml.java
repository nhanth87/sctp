import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.databind.JsonNode;

public class TestJacksonXml {
    public static void main(String[] args) throws Exception {
        String xml = "<sctp><associations><association name=\"ass1\" hostAddress=\"127.0.0.1\" hostPort=\"0\" peerAddress=\"127.0.0.1\" peerPort=\"8011\" serverName=\"serv1\" ipChannelType=\"SCTP\" type=\"SERVER\" started=\"true\"/></associations></sctp>";
        XmlMapper mapper = new XmlMapper();
        JsonNode root = mapper.readTree(xml);
        System.out.println("Root: " + root.toString());
        JsonNode assoc = root.get("associations").get("association");
        System.out.println("Association node: " + assoc.toString());
        System.out.println("started node: " + assoc.get("started"));
        System.out.println("started as boolean: " + (assoc.get("started") != null ? assoc.get("started").asBoolean() : "null"));
    }
}
