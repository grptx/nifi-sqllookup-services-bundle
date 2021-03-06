package com.mrcsparker.nifi.sqllookup;

import org.apache.nifi.serialization.SimpleRecordSchema;
import org.apache.nifi.serialization.record.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.math.BigInteger;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SQLResultSetRecordSet implements RecordSet, Closeable {
    private static final Logger logger = LoggerFactory.getLogger(SQLResultSetRecordSet.class);
    private final ResultSet rs;
    private final RecordSchema schema;
    private final Set<String> rsColumnNames;
    private boolean moreRows;

    public SQLResultSetRecordSet(final ResultSet rs, final RecordSchema readerSchema) throws SQLException {
        this.rs = rs;
        moreRows = rs.next();
        this.schema = createSchema(rs, readerSchema);

        rsColumnNames = new HashSet<>();
        final ResultSetMetaData metadata = rs.getMetaData();
        for (int i = 0; i < metadata.getColumnCount(); i++) {
            rsColumnNames.add(metadata.getColumnLabel(i + 1));
        }
    }

    @Override
    public RecordSchema getSchema() {
        return schema;
    }

    @Override
    public Record next() throws IOException {
        try {
            if (moreRows) {
                final Record record = createRecord(rs);
                moreRows = rs.next();
                return record;
            } else {
                return null;
            }
        } catch (final SQLException e) {
            throw new IOException("Could not obtain next record from ResultSet", e);
        }
    }

    @Override
    public void close() {
        try {
            rs.close();
        } catch (final SQLException e) {
            logger.error("Failed to close ResultSet", e);
        }
    }

    private Record createRecord(final ResultSet rs) throws SQLException {
        final Map<String, Object> values = new HashMap<>(schema.getFieldCount());

        for (final RecordField field : schema.getFields()) {
            final String fieldName = field.getFieldName();

            final Object value;
            if (rsColumnNames.contains(fieldName)) {
                if (rs.getObject(fieldName) instanceof java.sql.Array) {
                    value = normalizeArrayValue(rs.getArray(fieldName));
                } else {
                    value = normalizeValue(rs.getObject(fieldName));
                }
            } else {
                value = null;
            }

            values.put(fieldName, value);
        }

        return new MapRecord(schema, values);
    }

    @SuppressWarnings("rawtypes")
    private Object normalizeValue(final Object value) throws SQLException {
        if (value == null) {
            return null;
        }

        if (value instanceof List) {
            return ((List) value).toArray();
        }

        return value;
    }

    private Object normalizeArrayValue(final Array value) throws SQLException {
        if (value == null) {
            return null;
        }

        return value.getArray();
    }

    private static RecordSchema createSchema(final ResultSet rs, final RecordSchema readerSchema) throws SQLException {
        final ResultSetMetaData metadata = rs.getMetaData();
        final int numCols = metadata.getColumnCount();
        final List<RecordField> fields = new ArrayList<>(numCols);

        for (int i = 0; i < numCols; i++) {
            final int column = i + 1;
            final int sqlType = metadata.getColumnType(column);

            final DataType dataType = getDataType(sqlType, rs, column, readerSchema);
            final String fieldName = metadata.getColumnLabel(column);

            final int nullableFlag = metadata.isNullable(column);
            final boolean nullable;
            nullable = nullableFlag != ResultSetMetaData.columnNoNulls;

            final RecordField field = new RecordField(fieldName, dataType, nullable);
            fields.add(field);
        }

        return new SimpleRecordSchema(fields);
    }

    private static DataType getDataType(final int sqlType, final ResultSet rs, final int columnIndex, final RecordSchema readerSchema) throws SQLException {
        switch (sqlType) {
            case Types.ARRAY:
                // The JDBC API does not allow us to know what the base type of an array is through the metadata.
                // As a result, we have to obtain the actual Array for this record. Once we have this, we can determine
                // the base type. However, if the base type is, itself, an array, we will simply return a base type of
                // String because otherwise, we need the ResultSet for the array itself, and many JDBC Drivers do not
                // support calling Array.getResultSet() and will throw an Exception if that is not supported.
                if (rs.isAfterLast()) {
                    return RecordFieldType.ARRAY.getArrayDataType(RecordFieldType.STRING.getDataType());
                }

                final Array array = rs.getArray(columnIndex);
                if (array == null) {
                    return RecordFieldType.ARRAY.getArrayDataType(RecordFieldType.STRING.getDataType());
                }

                final DataType baseType = getArrayBaseType(array);
                return RecordFieldType.ARRAY.getArrayDataType(baseType);
            case Types.BINARY:
            case Types.LONGVARBINARY:
            case Types.VARBINARY:
                return RecordFieldType.ARRAY.getArrayDataType(RecordFieldType.BYTE.getDataType());
            case Types.OTHER: {
                // If we have no records to inspect, we can't really know its schema so we simply use the default data type.
                if (rs.isAfterLast()) {
                    return RecordFieldType.RECORD.getDataType();
                }

                final String columnName = rs.getMetaData().getColumnName(columnIndex);

                if (readerSchema != null) {
                    Optional<DataType> dataType = readerSchema.getDataType(columnName);
                    if (dataType.isPresent()) {
                        return dataType.get();
                    }
                }

                final Object obj = rs.getObject(columnIndex);
                if (!(obj instanceof Record)) {
                    final List<DataType> dataTypes = Stream.of(RecordFieldType.BIGINT, RecordFieldType.BOOLEAN, RecordFieldType.BYTE, RecordFieldType.CHAR, RecordFieldType.DATE,
                            RecordFieldType.DOUBLE, RecordFieldType.FLOAT, RecordFieldType.INT, RecordFieldType.LONG, RecordFieldType.SHORT, RecordFieldType.STRING, RecordFieldType.TIME,
                            RecordFieldType.TIMESTAMP)
                            .map(RecordFieldType::getDataType)
                            .collect(Collectors.toList());

                    return RecordFieldType.CHOICE.getChoiceDataType(dataTypes);
                }

                final Record record = (Record) obj;
                final RecordSchema recordSchema = record.getSchema();
                return RecordFieldType.RECORD.getRecordDataType(recordSchema);
            }
            default: {
                final String columnName = rs.getMetaData().getColumnName(columnIndex);

                if (readerSchema != null) {
                    Optional<DataType> dataType = readerSchema.getDataType(columnName);
                    if (dataType.isPresent()) {
                        return dataType.get();
                    }
                }

                return getFieldType(sqlType).getDataType();
            }
        }
    }

    private static DataType getArrayBaseType(final Array array) throws SQLException {
        final Object arrayValue = array.getArray();
        if (arrayValue == null) {
            return RecordFieldType.STRING.getDataType();
        }
        if (arrayValue instanceof byte[]) {
            return RecordFieldType.BYTE.getDataType();
        }
        if (arrayValue instanceof int[]) {
            return RecordFieldType.INT.getDataType();
        }
        if (arrayValue instanceof long[]) {
            return RecordFieldType.LONG.getDataType();
        }
        if (arrayValue instanceof boolean[]) {
            return RecordFieldType.BOOLEAN.getDataType();
        }
        if (arrayValue instanceof short[]) {
            return RecordFieldType.SHORT.getDataType();
        }
        if (arrayValue instanceof float[]) {
            return RecordFieldType.FLOAT.getDataType();
        }
        if (arrayValue instanceof double[]) {
            return RecordFieldType.DOUBLE.getDataType();
        }
        if (arrayValue instanceof char[]) {
            return RecordFieldType.CHAR.getDataType();
        }
        if (arrayValue instanceof Object[]) {
            final Object[] values = (Object[]) arrayValue;
            if (values.length == 0) {
                return RecordFieldType.STRING.getDataType();
            }

            Object valueToLookAt = null;
            for (Object value : values) {
                valueToLookAt = value;
                if (valueToLookAt != null) {
                    break;
                }
            }
            if (valueToLookAt == null) {
                return RecordFieldType.STRING.getDataType();
            }

            if (valueToLookAt instanceof String) {
                return RecordFieldType.STRING.getDataType();
            }
            if (valueToLookAt instanceof Long) {
                return RecordFieldType.LONG.getDataType();
            }
            if (valueToLookAt instanceof Integer) {
                return RecordFieldType.INT.getDataType();
            }
            if (valueToLookAt instanceof Short) {
                return RecordFieldType.SHORT.getDataType();
            }
            if (valueToLookAt instanceof Byte) {
                return RecordFieldType.BYTE.getDataType();
            }
            if (valueToLookAt instanceof Float) {
                return RecordFieldType.FLOAT.getDataType();
            }
            if (valueToLookAt instanceof Double) {
                return RecordFieldType.DOUBLE.getDataType();
            }
            if (valueToLookAt instanceof Boolean) {
                return RecordFieldType.BOOLEAN.getDataType();
            }
            if (valueToLookAt instanceof Character) {
                return RecordFieldType.CHAR.getDataType();
            }
            if (valueToLookAt instanceof BigInteger) {
                return RecordFieldType.BIGINT.getDataType();
            }
            if (valueToLookAt instanceof java.sql.Time) {
                return RecordFieldType.TIME.getDataType();
            }
            if (valueToLookAt instanceof java.sql.Date) {
                return RecordFieldType.DATE.getDataType();
            }
            if (valueToLookAt instanceof java.sql.Timestamp) {
                return RecordFieldType.TIMESTAMP.getDataType();
            }
            if (valueToLookAt instanceof Record) {
                final Record record = (Record) valueToLookAt;
                return RecordFieldType.RECORD.getRecordDataType(record.getSchema());
            }
        }

        return RecordFieldType.STRING.getDataType();
    }


    private static RecordFieldType getFieldType(final int sqlType) {
        switch (sqlType) {
            case Types.BIGINT:
            case Types.ROWID:
                return RecordFieldType.LONG;
            case Types.BIT:
            case Types.BOOLEAN:
                return RecordFieldType.BOOLEAN;
            case Types.CHAR:
                return RecordFieldType.CHAR;
            case Types.DATE:
                return RecordFieldType.DATE;
            case Types.DECIMAL:
            case Types.DOUBLE:
            case Types.NUMERIC:
            case Types.REAL:
                return RecordFieldType.DOUBLE;
            case Types.FLOAT:
                return RecordFieldType.FLOAT;
            case Types.INTEGER:
                return RecordFieldType.INT;
            case Types.SMALLINT:
                return RecordFieldType.SHORT;
            case Types.TINYINT:
                return RecordFieldType.BYTE;
            case Types.LONGNVARCHAR:
            case Types.LONGVARCHAR:
            case Types.NCHAR:
            case Types.NULL:
            case Types.NVARCHAR:
            case Types.VARCHAR:
                return RecordFieldType.STRING;
            case Types.OTHER:
            case Types.JAVA_OBJECT:
                return RecordFieldType.RECORD;
            case Types.TIME:
            case Types.TIME_WITH_TIMEZONE:
                return RecordFieldType.TIME;
            case Types.TIMESTAMP:
            case Types.TIMESTAMP_WITH_TIMEZONE:
            case -101: // Oracle's TIMESTAMP WITH TIME ZONE
            case -102: // Oracle's TIMESTAMP WITH LOCAL TIME ZONE
                return RecordFieldType.TIMESTAMP;
        }

        return RecordFieldType.STRING;
    }
}
