package com.pivotal.pxf.plugins.hdfs.utilities;

import java.io.IOException;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.io.BooleanWritable;
import com.pivotal.pxf.api.format.OneRow;
import com.pivotal.pxf.api.utilities.ColumnDescriptor;
import com.pivotal.pxf.api.utilities.InputData;
import org.apache.hadoop.io.ByteWritable;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.FloatWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.VIntWritable;
import org.apache.hadoop.io.Writable;

import com.pivotal.pxf.api.format.OneField;

/*
 * Adapter used for adding a recordkey field to the records output List<OneField>
 */
public class RecordkeyAdapter 
{
	private Log Log;
	
	/*
	 * We need to transform Record keys to java primitive types.
	 * Since the type of the key is the same throughout the file we do the type resolution
	 * in the first call (for the first record) and then use a "Java variation on Function pointer"
	 * to do the extraction for the rest of the records.
	 */
	private interface ValExtractor
	{ 
		public Object get(Object key);
	}
	private ValExtractor extractor = null;
	
	private interface ValConverter {
		public Writable get(Object key);
	}
	private ValConverter converter = null;
	
	public RecordkeyAdapter() {
		Log = LogFactory.getLog(RecordkeyAdapter.class);
	}
	
	/*
	 *  Adds the recordkey to the end of the passed in recFields list.
	 *  If onerow.getKey() returns null, it means that keys are not supported by the underlying source, and the user 
	 *  shouldn't have asked for the recordkey field in the "CREATE EXTERNAL TABLE" statement.
	 *  If a given source type supports keys then for this source type, onerow.getKey() will never return null.
	 *  For example if the source is a SequenceFile, onerow.getKey() will never return null and if the source type
	 *  is an AvroFile then onerow.getKey() will always return null.
	 */ 
	public int appendRecordkeyField(List<OneField> recFields,
												   InputData input,
												   OneRow onerow) throws NoSuchFieldException, IOException
	{
		/* user did not request the recordkey field in the "create external table" statement */
		ColumnDescriptor recordkeyColumn = input.getRecordkeyColumn();
		if (recordkeyColumn == null)
			return 0;
		
		/*
		 * The recordkey was filled in the fileAccessor during execution of method readNextObject
		 * Our current accessor implementations are SequenceFileAccessor, TextFileAccessor and AvroFileAccessor
		 * from SplittableFileAccessor and ProtobufFileAccessor from OddFileAccessor. 
		 * For SequenceFileAccessor, TextFileAccesor and ProtobufFileAccessor the recordkey is set, since
		 * it is returned by the SequenceFileRecordReader or LineRecordReader(for text file) or ProtobufFileAccessor.DynamicMessage. 
		 * But Avro files do not have keys, so the AvroRecordReader will not return a key and in this case
		 * recordkey will be null. If the user required a recordkey in the "create external table" statement
		 * and he reads from an AvroFile, we will throw an exception since the Avro file does not have keys
		 * In the future, additional implementations of FileAccessors will have to set recordkey
		 * during readNextObject(). Otherwise it is null by default and we will throw an exception here, that is
		 * if we get here... a careful user will not specify recordkey in the "create external.." statement and
		 * then we will leave this function one line above.
		 */
		Object recordkey = onerow.getKey();
		if (recordkey == null)
			throw new NoSuchFieldException("Value for field \"recordkey\" was requested but the queried HDFS resource type does not support key"); 
		
		OneField oneField = new OneField();
		oneField.type = recordkeyColumn.columnTypeCode();
		oneField.val = extractVal(recordkey);
		recFields.add(oneField);
		return 1;
	}
	
	/*
	 * Extracts a java primitive type value from the recordkey.
	 * If the key is a Writable implementation we extract the value as a Java primitive.
	 * If the key is already a Java primitive we returned it as is
	 * If it is an unknown type we throw an exception
	 */
	
	private Object extractVal(Object key) throws IOException
	{
		if (extractor == null)
			extractor = InitializeExtractor(key);
			
		return extractor.get(key);
	}
	
	/*
	 * Initialize the extractor object based on the type of the recordkey
	 */
	private ValExtractor InitializeExtractor(Object key)
	{
	    if (key instanceof IntWritable) 
		return new ValExtractor() { public Object get (Object key) {return ((IntWritable)key).get(); }};
	    else if (key instanceof ByteWritable) 
	    	return new ValExtractor() { public Object get (Object key) {return ((ByteWritable)key).get(); }};
	    else if (key instanceof BooleanWritable) 
		return new ValExtractor() { public Object get (Object key) {return ((BooleanWritable)key).get(); }};
	    else if (key instanceof DoubleWritable) 
		return new ValExtractor() { public Object get (Object key) {return ((DoubleWritable)key).get(); }};
	    else if (key instanceof FloatWritable) 
		return new ValExtractor() { public Object get (Object key) {return ((FloatWritable)key).get(); }};
	    else if (key instanceof LongWritable) 
		return new ValExtractor() { public Object get (Object key) {return (long)((LongWritable)key).get(); }};
	    else if (key instanceof Text) 
		return new ValExtractor() { public Object get (Object key) {return ((Text)key).toString(); }};
	    else if (key instanceof VIntWritable) 
		return new ValExtractor() { public Object get (Object key) {return ((VIntWritable)key).get(); }};
	    else
		return new ValExtractor() { public Object get (Object key) 
			{throw new UnsupportedOperationException("Unsupported recordkey data type " + key.getClass().getName());}};
	}
	
	 
	/**
	 * Converts given key object to its matching Writable.
	 * Supported types: Integer, Byte, Boolean, Double, Float, Long, String.
	 * The type is only checked once based on the key, all consequent calls 
	 * must be of the same type. 
	 * 
	 * @param key object to convert
	 * @return Writable object matching given key
	 */
	public Writable convertKeyValue(Object key) {
		if (converter == null) {
			converter = initializeConverter(key);
			Log.debug("converter initilized for type " + key.getClass() + 
			          " (key value: " + key + ")");
		}
		
		return converter.get(key);
	}
	
	private ValConverter initializeConverter(Object key) {
		
	    if (key instanceof Integer) {
		return new ValConverter() { public Writable get(Object key) { return (new IntWritable((Integer)key)); }};
	    }
	    else if (key instanceof Byte) {
		return new ValConverter() { public Writable get(Object key) { return (new ByteWritable((Byte)key)); }};
	    }
	    else if (key instanceof Boolean) {
		return new ValConverter() { public Writable get(Object key) { return (new BooleanWritable((Boolean)key)); }};
	    }
	    else if (key instanceof Double) {
		return new ValConverter() { public Writable get(Object key) { return (new DoubleWritable((Double)key)); }};
	    }
	    else if (key instanceof Float) {
		return new ValConverter() { public Writable get(Object key) { return (new FloatWritable((Float)key)); }};
	    }
	    else if (key instanceof Long) {
		return new ValConverter() { public Writable get(Object key) { return (new LongWritable((Long)key)); }};
	    }
	    else if (key instanceof String) {
		return new ValConverter() { public Writable get(Object key) { return (new Text((String)key)); }};
	    }
	    else
		return new ValConverter() { public Writable get(Object key) 
			{ throw new UnsupportedOperationException("Unsupported recordkey data type " + key.getClass().getName());}};
	}
}