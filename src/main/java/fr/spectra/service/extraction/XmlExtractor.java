package fr.spectra.service.extraction;

import fr.spectra.model.ExtractedDocument;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Component
public class XmlExtractor implements DocumentExtractor {

    @Override
    public Set<String> supportedContentTypes() {
        return Set.of("application/xml", "text/xml");
    }

    @Override
    public ExtractedDocument extract(String fileName, InputStream content) throws ExtractionException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Protection contre XXE
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(content);
            doc.getDocumentElement().normalize();

            StringBuilder text = new StringBuilder();
            Map<String, String> metadata = new HashMap<>();
            metadata.put("format", "XML");
            metadata.put("rootElement", doc.getDocumentElement().getTagName());

            flattenXml(doc.getDocumentElement(), "", text);

            return new ExtractedDocument(fileName, "application/xml", text.toString(), 1, metadata);
        } catch (Exception e) {
            throw new ExtractionException("Erreur extraction XML: " + fileName, e);
        }
    }

    private void flattenXml(Element element, String prefix, StringBuilder sb) {
        String path = prefix.isEmpty() ? element.getTagName() : prefix + "." + element.getTagName();
        NodeList children = element.getChildNodes();

        boolean hasChildElements = false;
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element childElement) {
                hasChildElements = true;
                flattenXml(childElement, path, sb);
            }
        }

        if (!hasChildElements) {
            String textContent = element.getTextContent().trim();
            if (!textContent.isEmpty()) {
                sb.append(path).append(": ").append(textContent).append("\n");
            }
        }
    }
}
