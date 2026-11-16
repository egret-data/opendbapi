package io.opendbapi.sql.template;

import io.opendbapi.sql.expr.Expression;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Template text → node tree. The template is MyBatis-style XML wrapped in an implicit root,
 * with two conveniences over strict XML: a {@code <} or {@code &} that does not start one of
 * our tags, a CDATA section or an entity is escaped automatically, so {@code a < 5} and
 * {@code a && b} can be written as is.
 */
final class TemplateCompiler {

    private static final Set<String> TAGS = Set.of(
            "if", "where", "set", "trim", "foreach", "choose", "when", "otherwise", "bind", "orderBy");
    private static final Pattern LESS_THAN = Pattern.compile(
            "<(?!/?(?:" + String.join("|", TAGS) + ")\\b|!\\[CDATA\\[)");
    private static final Pattern AMPERSAND = Pattern.compile("&(?!(?:lt|gt|amp|quot|apos|#\\d+|#x[0-9a-fA-F]+);)");
    private static final Pattern PLACEHOLDER = Pattern.compile("#\\{([^}]*)}");
    /** Something written as a tag: {@code <name attr="…"} or {@code <name>}/{@code </name>}. */
    private static final Pattern TAG_LIKE = Pattern.compile(
            "<(/?)([A-Za-z][A-Za-z0-9_-]*)(?=\\s+[A-Za-z_:][\\w:-]*\\s*=\\s*[\"']|\\s*/?>)");
    private static final String ROOT = "sql";

    private final String source;
    private XMLStreamReader xml;

    TemplateCompiler(String source) {
        this.source = source;
    }

    List<SqlNode> compile() {
        if (source.contains("${")) {
            throw new TemplateException("${...} is not supported: it would paste request text into SQL. "
                    + "Use #{...} for values and <orderBy param=\"…\" allow=\"…\"/> for sorting.");
        }
        rejectUnknownTags(source);
        String upper = source.toUpperCase(java.util.Locale.ROOT);
        if (upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY")) {
            throw new TemplateException("DOCTYPE and ENTITY declarations are not allowed in SQL templates.");
        }
        try {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            factory.setProperty(XMLInputFactory.IS_COALESCING, true);
            xml = factory.createXMLStreamReader(new StringReader("<" + ROOT + ">" + escape(source) + "</" + ROOT + ">"));
            xml.nextTag(); // root
            List<SqlNode> nodes = children(ROOT);
            return nodes;
        } catch (XMLStreamException e) {
            int line = e.getLocation() == null ? -1 : e.getLocation().getLineNumber();
            String reason = e.getMessage();
            int cut = reason.indexOf("Message: ");
            reason = cut >= 0 ? reason.substring(cut + 9) : reason;
            throw new TemplateException("Template is not well formed%s: %s".formatted(line > 0 ? " at line " + line : "", reason.strip()), e);
        }
    }

    /**
     * A misspelt tag ({@code <iff test="…">}) would otherwise be escaped into SQL text and only
     * fail later at the database; refuse it here, with its line.
     */
    private static void rejectUnknownTags(String text) {
        Matcher m = TAG_LIKE.matcher(text);
        while (m.find()) {
            if (!TAGS.contains(m.group(2))) {
                int line = 1 + (int) text.substring(0, m.start()).chars().filter(c -> c == '\n').count();
                throw new TemplateException("Unknown tag <%s%s> at line %d. Allowed: %s."
                        .formatted(m.group(1), m.group(2), line,
                                String.join(", ", TAGS.stream().sorted().map(t -> "<" + t + ">").toList())));
            }
        }
    }

    /** Escapes stray {@code <} and {@code &} outside CDATA sections. */
    static String escape(String text) {
        StringBuilder out = new StringBuilder();
        int at = 0;
        while (at < text.length()) {
            int cdata = text.indexOf("<![CDATA[", at);
            String plain = cdata < 0 ? text.substring(at) : text.substring(at, cdata);
            out.append(LESS_THAN.matcher(AMPERSAND.matcher(plain).replaceAll("&amp;")).replaceAll("&lt;"));
            if (cdata < 0) {
                break;
            }
            int end = text.indexOf("]]>", cdata);
            if (end < 0) {
                out.append(text.substring(cdata));
                break;
            }
            out.append(text, cdata, end + 3);
            at = end + 3;
        }
        return out.toString();
    }

    /** Reads child content until the end tag of {@code parent}. */
    private List<SqlNode> children(String parent) throws XMLStreamException {
        List<SqlNode> nodes = new ArrayList<>();
        while (xml.hasNext()) {
            int event = xml.next();
            switch (event) {
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE ->
                        text(xml.getText(), nodes);
                case XMLStreamConstants.START_ELEMENT -> nodes.add(element());
                case XMLStreamConstants.END_ELEMENT -> {
                    return nodes;
                }
                default -> {
                }
            }
        }
        throw new TemplateException("<%s> is not closed.".formatted(parent));
    }

    private SqlNode element() throws XMLStreamException {
        String tag = xml.getLocalName();
        int line = xml.getLocation().getLineNumber();
        return switch (tag) {
            case "if" -> new SqlNode.If(expression(tag, "test", line), children(tag));
            case "where" -> SqlNode.Trim.where(children(tag));
            case "set" -> SqlNode.Trim.set(children(tag));
            case "trim" -> {
                String prefix = attribute("prefix", "");
                String suffix = attribute("suffix", "");
                List<String> prefixOverrides = overrides(attribute("prefixOverrides", ""));
                List<String> suffixOverrides = overrides(attribute("suffixOverrides", ""));
                yield new SqlNode.Trim(prefix, suffix, prefixOverrides, suffixOverrides, children(tag));
            }
            case "foreach" -> {
                Expression collection = expression(tag, "collection", line);
                String item = attribute("item", null);
                String index = attribute("index", null);
                yield new SqlNode.Foreach(collection, item, index, attribute("open", ""), attribute("close", ""),
                        attribute("separator", ""), children(tag));
            }
            case "choose" -> choose(line);
            case "bind" -> {
                String name = required(tag, "name", line);
                Expression value = expression(tag, "value", line);
                children(tag);
                yield new SqlNode.Bind(name, value);
            }
            case "orderBy" -> {
                String param = required(tag, "param", line);
                List<String> allow = Arrays.stream(required(tag, "allow", line).split(","))
                        .map(String::strip).filter(s -> !s.isEmpty()).toList();
                String defaultValue = attribute("default", null);
                children(tag);
                SqlNode.OrderBy node = new SqlNode.OrderBy(param, allow, defaultValue);
                if (defaultValue != null) {
                    try {
                        Arrays.stream(defaultValue.split(",")).filter(s -> !s.isBlank()).forEach(node::resolve);
                    } catch (TemplateException e) {
                        throw new TemplateException("<orderBy> default at line %d: %s".formatted(line, e.getMessage()));
                    }
                }
                yield node;
            }
            default -> throw new TemplateException("Unknown tag <%s> at line %d. Allowed: %s."
                    .formatted(tag, line, String.join(", ", TAGS.stream().sorted().map(t -> "<" + t + ">").toList())));
        };
    }

    private SqlNode choose(int line) throws XMLStreamException {
        List<SqlNode.If> whens = new ArrayList<>();
        List<SqlNode> otherwise = List.of();
        while (xml.hasNext()) {
            int event = xml.next();
            if (event == XMLStreamConstants.END_ELEMENT) {
                if (whens.isEmpty()) {
                    throw new TemplateException("<choose> at line %d needs at least one <when>.".formatted(line));
                }
                return new SqlNode.Choose(whens, otherwise);
            }
            if (event == XMLStreamConstants.START_ELEMENT) {
                String tag = xml.getLocalName();
                int at = xml.getLocation().getLineNumber();
                if ("when".equals(tag)) {
                    whens.add(new SqlNode.If(expression(tag, "test", at), children(tag)));
                } else if ("otherwise".equals(tag)) {
                    otherwise = children(tag);
                } else {
                    throw new TemplateException("<choose> may only contain <when> and <otherwise> (found <%s> at line %d)."
                            .formatted(tag, at));
                }
            } else if (event == XMLStreamConstants.CHARACTERS && !xml.getText().isBlank()) {
                throw new TemplateException("Text directly inside <choose> at line %d; put it in a <when> or <otherwise>."
                        .formatted(line));
            }
        }
        throw new TemplateException("<choose> at line %d is not closed.".formatted(line));
    }

    private void text(String text, List<SqlNode> out) {
        Matcher m = PLACEHOLDER.matcher(text);
        int at = 0;
        while (m.find()) {
            if (m.start() > at) {
                out.add(new SqlNode.Text(text.substring(at, m.start())));
            }
            String inside = m.group(1);
            int comma = inside.indexOf(',');
            String expression = (comma >= 0 ? inside.substring(0, comma) : inside).strip();
            try {
                out.add(new SqlNode.Placeholder(Expression.parse(expression)));
            } catch (TemplateException e) {
                throw new TemplateException("In #{%s}: %s".formatted(inside, e.getMessage()));
            }
            at = m.end();
        }
        if (at < text.length()) {
            out.add(new SqlNode.Text(text.substring(at)));
        }
    }

    private Expression expression(String tag, String attribute, int line) {
        String value = required(tag, attribute, line);
        try {
            return Expression.parse(value);
        } catch (TemplateException e) {
            throw new TemplateException("<%s %s=\"%s\"> at line %d: %s".formatted(tag, attribute, value, line, e.getMessage()));
        }
    }

    private String required(String tag, String attribute, int line) {
        String value = attribute(attribute, null);
        if (value == null || value.isBlank()) {
            throw new TemplateException("<%s> needs a %s attribute (line %d).".formatted(tag, attribute, line));
        }
        return value;
    }

    private String attribute(String name, String fallback) {
        String value = xml.getAttributeValue(null, name);
        return value == null ? fallback : value;
    }

    private static List<String> overrides(String spec) {
        return spec.isEmpty() ? List.of() : Arrays.stream(spec.split("\\|")).filter(s -> !s.isEmpty()).toList();
    }
}
