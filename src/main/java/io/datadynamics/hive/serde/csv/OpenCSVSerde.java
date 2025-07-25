package io.datadynamics.hive.serde.csv;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.serde.serdeConstants;
import org.apache.hadoop.hive.serde2.AbstractEncodingAwareSerDe;
import org.apache.hadoop.hive.serde2.SerDeException;
import org.apache.hadoop.hive.serde2.SerDeSpec;
import org.apache.hadoop.hive.serde2.SerDeUtils;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.AbstractPrimitiveWritableObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.StringObjectInspector;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;

import java.io.CharArrayReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.CSVWriter;

/**
 * OpenCSVSerde use opencsv to deserialize CSV format.
 * Users can specify custom separator, quote or escape characters. And the default separator(\),
 * quote("), and escape characters(\) are the same as the opencsv library.
 *
 */
@SerDeSpec(schemaProps = {
        serdeConstants.LIST_COLUMNS, serdeConstants.SERIALIZATION_ENCODING,
        OpenCSVSerde.SEPARATORCHAR, OpenCSVSerde.QUOTECHAR, OpenCSVSerde.ESCAPECHAR,
        OpenCSVSerde.APPLYQUOTESTOALL})
public final class OpenCSVSerde extends AbstractEncodingAwareSerDe {

    private ObjectInspector inspector;
    private String[] outputFields;
    private int numCols;
    private List<String> row;

    private char separatorChar;
    private char quoteChar;
    private char escapeChar;
    private boolean applyQuotesToAll;

    public static final String SEPARATORCHAR = "separatorChar";
    public static final String QUOTECHAR = "quoteChar";
    public static final String ESCAPECHAR = "escapeChar";
    public static final String APPLYQUOTESTOALL = "applyQuotesToAll";

    @Override
    public void initialize(Configuration configuration, Properties tableProperties, Properties partitionProperties)
            throws SerDeException {
        super.initialize(configuration, tableProperties, partitionProperties);

        numCols = getColumnNames().size();

        final List<ObjectInspector> columnOIs = new ArrayList<ObjectInspector>(numCols);

        for (int i = 0; i < numCols; i++) {
            columnOIs.add(PrimitiveObjectInspectorFactory.javaStringObjectInspector);
        }

        inspector = ObjectInspectorFactory.getStandardStructObjectInspector(getColumnNames(), columnOIs);
        outputFields = new String[numCols];
        row = new ArrayList<String>(numCols);

        for (int i = 0; i < numCols; i++) {
            row.add(null);
        }

        separatorChar = getProperty(properties, SEPARATORCHAR, CSVWriter.DEFAULT_SEPARATOR);
        quoteChar = getProperty(properties, QUOTECHAR, CSVWriter.DEFAULT_QUOTE_CHARACTER);
        escapeChar = getProperty(properties, ESCAPECHAR, CSVWriter.DEFAULT_ESCAPE_CHARACTER);
        applyQuotesToAll = Boolean.parseBoolean(properties.getProperty(APPLYQUOTESTOALL, "true"));
    }

    private char getProperty(final Properties tbl, final String property, final char def) {
        final String val = tbl.getProperty(property);

        if (val != null) {
            return val.charAt(0);
        }

        return def;
    }

    @Override
    public Writable doSerialize(Object obj, ObjectInspector objInspector) throws SerDeException {
        final StructObjectInspector outputRowOI = (StructObjectInspector) objInspector;
        final List<? extends StructField> outputFieldRefs = outputRowOI.getAllStructFieldRefs();

        if (outputFieldRefs.size() != numCols) {
            throw new SerDeException("Cannot serialize the object because there are "
                    + outputFieldRefs.size() + " fields but the table has " + numCols + " columns.");
        }

        // Get all data out.
        for (int c = 0; c < numCols; c++) {
            final Object field = outputRowOI.getStructFieldData(obj, outputFieldRefs.get(c));
            final ObjectInspector fieldOI = outputFieldRefs.get(c).getFieldObjectInspector();

            // Convert the field to Java class String, because objects of String type
            // can be stored in String, Text, or some other classes.
            if (fieldOI instanceof StringObjectInspector) {
                outputFields[c] = ((StringObjectInspector) fieldOI).getPrimitiveJavaObject(field);
            } else if (fieldOI instanceof AbstractPrimitiveWritableObjectInspector) {
                Object primitiveJavaObject = ((AbstractPrimitiveWritableObjectInspector) fieldOI).getPrimitiveJavaObject(field);
                outputFields[c] = Objects.toString(primitiveJavaObject, null);
            } else {
                throw new UnsupportedOperationException(
                        "Column type of " + fieldOI.getTypeName() + " is not supported with OpenCSVSerde");
            }
        }

        final StringWriter writer = new StringWriter();
        final CSVWriter csv = newWriter(writer, separatorChar, quoteChar, escapeChar);

        try {
            csv.writeNext(outputFields, applyQuotesToAll);
            csv.close();

            return new Text(writer.toString());
        } catch (final IOException ioe) {
            throw new SerDeException(ioe);
        }
    }

    @Override
    public Object doDeserialize(final Writable blob) throws SerDeException {
        Text rowText = (Text) blob;

        CSVReader csv = null;
        try {
            csv = newReader(new CharArrayReader(rowText.toString().toCharArray()), separatorChar,
                    quoteChar, escapeChar);
            final String[] read = csv.readNext();

            for (int i = 0; i < numCols; i++) {
                if (read != null && i < read.length) {
                    row.set(i, read[i]);
                } else {
                    row.set(i, null);
                }
            }

            return row;
        } catch (final Exception e) {
            throw new SerDeException(e);
        } finally {
            if (csv != null) {
                try {
                    csv.close();
                } catch (final Exception e) {
                    log.error("fail to close csv writer", e);
                }
            }
        }
    }

    private CSVReader newReader(final Reader reader, char separator, char quote, char escape) {
        // CSVReader will throw an exception if any of separator, quote, or escape is the same, but
        // the CSV format specifies that the escape character and quote char are the same... very weird
        if (CSVWriter.DEFAULT_ESCAPE_CHARACTER == escape) {
            return new CSVReaderBuilder(reader).withCSVParser(
                    new CSVParserBuilder().withSeparator(separator).withQuoteChar(quote).build()).build();
        } else {
            return new CSVReaderBuilder(reader).withCSVParser(
                    new CSVParserBuilder().withSeparator(separator).withQuoteChar(quote).withEscapeChar(escape).build()).build();
        }
    }

    private CSVWriter newWriter(final Writer writer, char separator, char quote, char escape) {
        if (CSVWriter.DEFAULT_ESCAPE_CHARACTER == escape) {
            return new CSVWriter(writer, separator, quote, '"', "");
        } else {
            return new CSVWriter(writer, separator, quote, escape, "");
        }
    }

    @Override
    public ObjectInspector getObjectInspector() throws SerDeException {
        return inspector;
    }

    @Override
    public Class<? extends Writable> getSerializedClass() {
        return Text.class;
    }

    protected Text transformFromUTF8(Writable blob) {
        Text text = (Text)blob;
        return SerDeUtils.transformTextFromUTF8(text, this.charset);
    }

    protected Text transformToUTF8(Writable blob) {
        Text text = (Text) blob;
        return SerDeUtils.transformTextToUTF8(text, this.charset);
    }
}