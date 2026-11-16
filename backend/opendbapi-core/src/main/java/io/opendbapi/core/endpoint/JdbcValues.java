package io.opendbapi.core.endpoint;

import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * JDBC value → JSON-friendly value. Dates become ISO-8601 strings (never epoch numbers),
 * binary becomes base64, arrays become lists, driver-specific objects (json, uuid, interval)
 * become their text form. Numbers stay exact ({@code BigDecimal}).
 */
public final class JdbcValues {

    private JdbcValues() {
    }

    public static Object read(ResultSet rs, int column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        return convert(value, rs, column);
    }

    static Object convert(Object value, ResultSet rs, int column) throws SQLException {
        if (value instanceof String || value instanceof Boolean || value instanceof Number) {
            return value;
        }
        if (value instanceof Timestamp ts) {
            int type = rs == null ? java.sql.Types.TIMESTAMP : rs.getMetaData().getColumnType(column);
            if (type == java.sql.Types.TIMESTAMP_WITH_TIMEZONE) {
                return rs.getObject(column, OffsetDateTime.class).toString();
            }
            return ts.toLocalDateTime().toString();
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (value instanceof java.sql.Time t) {
            return t.toLocalTime().toString();
        }
        if (value instanceof java.time.temporal.TemporalAccessor) {
            return value.toString();
        }
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof Blob blob) {
            return Base64.getEncoder().encodeToString(blob.getBytes(1, (int) Math.min(blob.length(), Integer.MAX_VALUE)));
        }
        if (value instanceof Clob clob) {
            return clob.getSubString(1, (int) Math.min(clob.length(), Integer.MAX_VALUE));
        }
        if (value instanceof SQLXML xml) {
            return xml.getString();
        }
        if (value instanceof Array array) {
            List<Object> out = new ArrayList<>();
            for (Object item : (Object[]) array.getArray()) {
                out.add(item == null ? null : convert(item, null, 0));
            }
            return out;
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt.toString();
        }
        return value.toString();
    }
}
