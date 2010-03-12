/**
 * Copyright (C) 2010 Sanjit Jhala (Hypertable, Inc.)
 *
 * This file is part of Hypertable.
 *
 * Hypertable is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or any later version.
 *
 * Hypertable is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301, USA.
 */

package org.hypertable.examples;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.lang.StringBuilder;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import org.apache.thrift.TException;
import org.hypertable.thriftgen.*;
import org.hypertable.thrift.ThriftClient;
import org.hypertable.hadoop.mapreduce.KeyWritable;

import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.mapreduce.lib.reduce.LongSumReducer;
import org.apache.hadoop.util.LineReader;


/**
 * <p>This class sets up and runs the evaluation programs described in
 * Section 7, <i>Performance Evaluation</i>, of the <a
 * href="http://labs.google.com/papers/bigtable.html">Bigtable</a>
 * paper, pages 8-10.
 *
 * <p>If number of clients > 1, we start up a MapReduce job. Each map task
 * runs an individual client. Each client does about 1GB of data.
 */
public class PerformanceEvaluation {
  protected static final Log log = LogFactory.getLog(PerformanceEvaluation.class.getName());

  private static final int ROW_LENGTH = 1000;
  private static final int ONE_GB = 1024 * 1024 * 1000;
  private static final int ROWS_PER_GB = ONE_GB / ROW_LENGTH;

  public static final String COL_FAMILY_NAME = "column";
  public static final String COL_QUALIFIER_NAME = "data";

  //TODO: For now assume thriftbroker is running locally on default port, read from config later
  protected static final String THRIFTBROKER_HOST = "localhost";
  protected static final int THRIFTBROKER_PORT = 38080;

  protected static final String TABLE_NAME = "PerformanceEvaluation";
  protected static final String
      TABLE_SCHEMA = "<Schema>\n" +
                        "<AccessGroup name='default'>\n" +
                          "<ColumnFamily>\n" +
                            "<Name>column</Name>\n" +
                            "<deleted>false</deleted>\n" +
                          "</ColumnFamily>\n" +
                        "</AccessGroup>\n" +
                      "</Schema>\n";

  private static final String RANDOM_READ = "randomRead";
  private static final String RANDOM_SEEK_SCAN = "randomSeekScan";
  private static final String RANDOM_READ_MEM = "randomReadMem";
  private static final String RANDOM_WRITE = "randomWrite";
  private static final String SEQUENTIAL_READ = "sequentialRead";
  private static final String SEQUENTIAL_WRITE = "sequentialWrite";
  private static final String SCAN = "scan";

  private static final List<String> COMMANDS =
    Arrays.asList(new String [] {RANDOM_READ,
      RANDOM_SEEK_SCAN,
      RANDOM_READ_MEM,
      RANDOM_WRITE,
      SEQUENTIAL_READ,
      SEQUENTIAL_WRITE,
      SCAN});

  volatile Configuration conf;
  private int NUM_CLIENTS = 1;
  private int TOTAL_ROWS = ROWS_PER_GB;
  private static final Path PERF_EVAL_DIR = new Path("hypertable_performance_evaluation");

  /**
   * Regex to parse lines in input file passed to mapreduce task.
   */
  public static final Pattern LINE_PATTERN =
    Pattern.compile("startRow=(\\d+),\\s+" +
    "perClientRunRows=(\\d+),\\s+totalRows=(\\d+),\\s+clients=(\\d+)");

  /**
   * Enum for map metrics.  Keep it out here rather than inside in the Map
   * inner-class so we can find associated properties.
   */
  protected static enum Counter {
    /** elapsed time */
    ELAPSED_TIME_MILLIS,
    /** number of rows */
    ROWS}


  /**
   * Constructor
   */
  public PerformanceEvaluation(final Configuration config) {
    this.conf = config;
  }

  /**
   * Implementations can have their status set.
   */
  static interface Status {
    /**
     * Sets status
     * @param msg status message
     * @throws IOException
     */
    void setStatus(final String msg) throws IOException;
  }

  /**
   *  This class works as the InputSplit of Performance Evaluation
   *  MapReduce InputFormat, and the Record Value of RecordReader.
   *  Each map task will only read one record from a PeInputSplit,
   *  the record value is the PeInputSplit itself.
   */
  public static class PeInputSplit extends InputSplit implements Writable {
    private int startRow = 0;
    private int rows = 0;
    private int totalRows = 0;
    private int clients = 0;

    public PeInputSplit() {
      this.startRow = 0;
      this.rows = 0;
      this.totalRows = 0;
      this.clients = 0;
    }

    public PeInputSplit(int startRow, int rows, int totalRows, int clients) {
      this.startRow = startRow;
      this.rows = rows;
      this.totalRows = totalRows;
      this.clients = clients;
    }

    @Override
    public void readFields(DataInput in) throws IOException {
      this.startRow = in.readInt();
      this.rows = in.readInt();
      this.totalRows = in.readInt();
      this.clients = in.readInt();
    }

    @Override
    public void write(DataOutput out) throws IOException {
      out.writeInt(startRow);
      out.writeInt(rows);
      out.writeInt(totalRows);
      out.writeInt(clients);
    }

    @Override
    public long getLength() throws IOException, InterruptedException {
      return 0;
    }

    @Override
    public String[] getLocations() throws IOException, InterruptedException {
      return new String[0];
    }

    public int getStartRow() {
      return startRow;
    }

    public int getRows() {
      return rows;
    }

    public int getTotalRows() {
      return totalRows;
    }

    public int getClients() {
      return clients;
    }
  }

  /**
   *  InputFormat of Performance Evaluation MapReduce job.
   *  It extends from FileInputFormat, want to use it's methods such as setInputPaths().
   */
  public static class PeInputFormat extends FileInputFormat<NullWritable, PeInputSplit> {

    @Override
    public List<InputSplit> getSplits(JobContext job) throws IOException {
      // generate splits
      List<InputSplit> splitList = new ArrayList<InputSplit>();

      for (FileStatus file: listStatus(job)) {
        Path path = file.getPath();
        FileSystem fs = path.getFileSystem(job.getConfiguration());
        FSDataInputStream fileIn = fs.open(path);
        LineReader in = new LineReader(fileIn, job.getConfiguration());
        int lineLen = 0;
        while(true) {
          Text lineText = new Text();
          lineLen = in.readLine(lineText);
          if(lineLen <= 0) {
        	break;
          }
          Matcher m = LINE_PATTERN.matcher(lineText.toString());
          if((m != null) && m.matches()) {
            int startRow = Integer.parseInt(m.group(1));
            int rows = Integer.parseInt(m.group(2));
            int totalRows = Integer.parseInt(m.group(3));
            int clients = Integer.parseInt(m.group(4));

            log.debug("split["+ splitList.size() + "] " +
                     " startRow=" + startRow +
                     " rows=" + rows +
                     " totalRows=" + totalRows +
                     " clients=" + clients);

            PeInputSplit newSplit = new PeInputSplit(startRow, rows, totalRows, clients);
            splitList.add(newSplit);
          }
        }
        in.close();
      }

      log.info("Total # of splits: " + splitList.size());
      return splitList;
    }

    @Override
    public RecordReader<NullWritable, PeInputSplit> createRecordReader(InputSplit split,
    												TaskAttemptContext context) {
      return new PeRecordReader();
    }

    public static class PeRecordReader extends RecordReader<NullWritable, PeInputSplit> {
      private boolean readOver = false;
      private PeInputSplit split = null;
      private NullWritable key = null;
      private PeInputSplit value = null;

      @Override
      public void initialize(InputSplit split, TaskAttemptContext context)
      						throws IOException, InterruptedException {
        this.readOver = false;
        this.split = (PeInputSplit)split;
      }

      @Override
      public boolean nextKeyValue() throws IOException, InterruptedException {
        if(readOver) {
          return false;
        }

        key = NullWritable.get();
        value = (PeInputSplit)split;

        readOver = true;
        return true;
      }

      @Override
      public NullWritable getCurrentKey() throws IOException, InterruptedException {
        return key;
      }

      @Override
      public PeInputSplit getCurrentValue() throws IOException, InterruptedException {
        return value;
      }

      @Override
      public float getProgress() throws IOException, InterruptedException {
        if(readOver) {
          return 1.0f;
        } else {
          return 0.0f;
        }
      }

      @Override
      public void close() throws IOException {
        // do nothing
      }
    }
  }

  /**
   * MapReduce job that runs a performance evaluation client in each map task.
   */
  public static class EvaluationMapTask
      extends Mapper<NullWritable, PeInputSplit, LongWritable, LongWritable> {

    /** configuration parameter name that contains the command */
    public final static String CMD_KEY = "EvaluationMapTask.command";
    private String cmd;
    private PerformanceEvaluation pe;

    @Override
    protected void setup(Context context) throws IOException, InterruptedException {
    	this.cmd = context.getConfiguration().get(CMD_KEY);
    	this.pe = new PerformanceEvaluation(context.getConfiguration());

    }

    protected void map(NullWritable key, PeInputSplit value, final Context context)
           throws IOException, InterruptedException {

      Status status = new Status() {
        public void setStatus(String msg) {
           context.setStatus(msg);
        }
      };

      // Evaluation task
      long elapsedTime = this.pe.runOneClient(this.cmd, value.getStartRow(),
    		                          value.getRows(), value.getTotalRows(), status);
      // Collect how much time the thing took. Report as map output and
      // to the ELAPSED_TIME_MILLIS counter.
      context.getCounter(Counter.ELAPSED_TIME_MILLIS).increment(elapsedTime);
      context.getCounter(Counter.ROWS).increment(value.rows);
      context.write(new LongWritable(value.startRow), new LongWritable(elapsedTime));
      context.progress();
    }
  }

  /*
   * If table does not already exist, create.
   * @param c Client to use checking.
   * @return True if we created the table.
   * @throws IOException
   */
  private boolean checkTable() throws IOException {
    boolean tableExists = true;
    try {
      ThriftClient htClient = ThriftClient.create(THRIFTBROKER_HOST, THRIFTBROKER_PORT);
      tableExists = htClient.exists_table(TABLE_NAME);
      if (!tableExists) {
        htClient.create_table(TABLE_NAME, TABLE_SCHEMA);
        log.info("Table " + TABLE_NAME + " created");
      }
      else
        log.info("Table " + TABLE_NAME + " exists already");
    }
    catch (Exception e) {
      log.error(e);
      throw new IOException("Unable to setup ThriftClient to check if table exists- " +
                            e.toString());
    }

    return !tableExists;
  }

  /*
   * We're to run multiple clients concurrently.  Setup a mapreduce job.  Run
   * one map per client.  Then run a single reduce to sum the elapsed times.
   * @param cmd Command to run.
   * @throws IOException
   */
  private void runNIsMoreThanOne(final String cmd)
  throws IOException, InterruptedException, ClassNotFoundException {
    try {
      checkTable();
      doMapReduce(cmd);
    } catch (Exception e) {
      log.error("Failed", e);
    }
  }

  /*
   * Run a mapreduce job.  Run as many maps as asked-for clients.
   * Before we start up the job, write out an input file with instruction
   * per client regards which row they are to start on.
   * @param cmd Command to run.
   * @throws IOException
   */
  private void doMapReduce(final String cmd) throws IOException,
  			InterruptedException, ClassNotFoundException {
    Path inputDir = writeInputFile(this.conf);
    this.conf.set(EvaluationMapTask.CMD_KEY, cmd);
    Job job = new Job(this.conf);
    job.setJarByClass(PerformanceEvaluation.class);
    job.setJobName("Hypertable Performance Evaluation");

    job.setInputFormatClass(PeInputFormat.class);
    PeInputFormat.setInputPaths(job, inputDir);

    job.setOutputKeyClass(LongWritable.class);
    job.setOutputValueClass(LongWritable.class);

    job.setMapperClass(EvaluationMapTask.class);
    job.setReducerClass(LongSumReducer.class);

    job.setNumReduceTasks(1);

    job.setOutputFormatClass(TextOutputFormat.class);
    TextOutputFormat.setOutputPath(job, new Path(inputDir,"outputs"));

    job.waitForCompletion(true);
  }

  /*
   * Write input file of offsets-per-client for the mapreduce job.
   * @param c Configuration
   * @return Directory that contains file written.
   * @throws IOException
   */
  private Path writeInputFile(final Configuration c) throws IOException {
    FileSystem fs = FileSystem.get(c);
    if (!fs.exists(PERF_EVAL_DIR)) {
      fs.mkdirs(PERF_EVAL_DIR);
    }
    SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMddHHmmss");
    Path subdir = new Path(PERF_EVAL_DIR, formatter.format(new Date()));
    fs.mkdirs(subdir);
    Path inputFile = new Path(subdir, "input.txt");
    PrintStream out = new PrintStream(fs.create(inputFile));
    // Make input random.
    Map<Integer, String> m = new TreeMap<Integer, String>();
    Random generator = new Random(System.currentTimeMillis());
    int perClientRows = (this.TOTAL_ROWS / this.NUM_CLIENTS);
    try {
      for (int ii = 0; ii < 10; ii++) {
        for (int jj = 0; jj < NUM_CLIENTS; jj++) {
          String s = "startRow=" + ((jj * perClientRows) + (ii * (perClientRows/10))) +
          ", perClientRunRows=" + (perClientRows / 10) +
          ", totalRows=" + this.TOTAL_ROWS +
          ", clients=" + this.NUM_CLIENTS;
          m.put(generator.nextInt(), s);
        }
      }
      for (Map.Entry<Integer, String> e: m.entrySet()) {
        out.println(e.getValue());
      }
    } finally {
      out.close();
    }
    return subdir;
  }

  /*
   * A test.
   * Subclass to particularize what happens per row.
   */
  static abstract class Test {
    protected final Random rand = new Random(System.currentTimeMillis());
    protected final int startRow;
    protected final int perClientRunRows;
    protected final int totalRows;
    private final Status status;
    protected ThriftClient htClient;
    protected long mutator;
    protected int mutatorFlag;
    protected int flushInterval;
    protected String table;
    protected volatile Configuration conf;

    Test(final Configuration conf, final int startRow,
        final int perClientRunRows, final int totalRows, final Status status) {
      super();
      this.startRow = startRow;
      this.perClientRunRows = perClientRunRows;
      this.totalRows = totalRows;
      this.status = status;
      this.htClient = null;
      this.conf = conf;
      this.table = TABLE_NAME;
      this.mutatorFlag = MutatorFlag.NO_LOG_SYNC.getValue();
      this.flushInterval = 0;

      log.info("Running test with startRow=" + startRow +
               ", perClientRunRows=" + perClientRunRows + ",totalRows=" + totalRows +
               ", table=" + table);
    }

    private String generateStatus(final int sr, final int ii, final int lr) {
      return sr + "/" + ii + "/" + lr;
    }

    protected int getReportingPeriod() {
      return Math.max(1, this.perClientRunRows / 10);
    }

    void testSetup() throws IOException {
      try {
        this.htClient = ThriftClient.create(THRIFTBROKER_HOST, THRIFTBROKER_PORT);
        this.mutator = htClient.open_mutator(table, mutatorFlag, flushInterval);
      }
      catch (Exception e) {
        log.error(e);
        throw new IOException ("Unable to setup ThriftClient - " + e.toString());
      }
    }

    void testTakedown()  throws IOException {
      try {
        this.htClient.close_mutator(mutator, true);
      }
      catch (Exception e) {
        log.error(e);
        throw new IOException("Unable to close thrift mutator on table '" + table +"'- " + e.toString());
      }
    }

    /*
     * Run test
     * @return Elapsed time.
     * @throws IOException
     */
    long test() throws IOException {
      long elapsedTime;
      testSetup();
      long startTime = System.currentTimeMillis();
      int reportingPeriod = getReportingPeriod();

      try {
        int lastRow = this.startRow + this.perClientRunRows;
        // Report on completion of 1/10th of total.
        for (int ii = this.startRow; ii < lastRow; ii++) {
          testRow(ii);
          if (status != null && ii > 0 && (ii % reportingPeriod) == 0) {
            status.setStatus(generateStatus(this.startRow, ii, lastRow));
          }
        }
        elapsedTime = System.currentTimeMillis() - startTime;
      } finally {
        testTakedown();
      }
      return elapsedTime;
    }

    /*
     * Test for individual row.
     * @param ii Row index.
     */
    abstract void testRow(final int ii) throws IOException;

    /*
     * @return Test name.
     */
    abstract String getTestName();
  }

  class RandomSeekScanTest extends Test {
    RandomSeekScanTest(final Configuration conf, final int startRow,
        final int perClientRunRows, final int totalRows, final Status status) {
      super(conf, startRow, perClientRunRows, totalRows, status);
    }

    @Override
    void testRow(final int ii) throws IOException {

      /*
      Scan scan = new Scan(getRandomRow(this.rand, this.totalRows));
      scan.addColumn(FAMILY_NAME, QUALIFIER_NAME);
      scan.setFilter(new WhileMatchFilter(new PageFilter(120)));
      ResultScanner s = this.table.getScanner(scan);
      //int count = 0;
      for (Result rr = null; (rr = s.next()) != null;) {
        // log.info("" + count++ + " " + rr.toString());
      }
      s.close();
      */
    }

    @Override
    protected int getReportingPeriod() {
      //
      int reportingPeriod = Math.max(1, this.perClientRunRows / 100);
      return reportingPeriod;
    }

    @Override
    String getTestName() {
      return "randomSeekScanTest";
    }
  }

  class RandomReadTest extends Test {
    RandomReadTest(final Configuration conf, final int startRow,
        final int perClientRunRows, final int totalRows, final Status status) {
      super(conf, startRow, perClientRunRows, totalRows, status);
    }

    @Override
    void testRow(final int ii) throws IOException {
      byte [] value;
      String row;
      row = getRandomRow(this.rand, this.totalRows);
      try {
        value = htClient.get_cell(TABLE_NAME, row, COL_FAMILY_NAME);
        //log.info("Got result for row " + row + " value size=" + value.length);
      }
      catch (Exception e) {
        log.error(e);
        throw new IOException("Unable to get cell via thrift - " + e.toString());
      }
    }

    @Override
    protected int getReportingPeriod() {
      //
      return Math.max(1, this.perClientRunRows / 100);
    }

    @Override
    String getTestName() {
      return "randomRead";
    }
  }

  class RandomWriteTest extends Test {
    RandomWriteTest(final Configuration conf, final int startRow,
        final int perClientRunRows, final int totalRows, final Status status) {
      super(conf, startRow, perClientRunRows, totalRows, status);
    }

    @Override
    void testRow(final int ii) throws IOException {
      Key key = new Key();
      byte [] value;
      Cell cell = new Cell();

      key.row = getRandomRow(this.rand, this.totalRows);
      key.column_family = COL_FAMILY_NAME;
      key.column_qualifier = COL_QUALIFIER_NAME;
      value = generateValue(this.rand);
      cell.key = key;
      cell.value = value;

      try {
        htClient.set_cell(mutator, cell);
      }
      catch (Exception e) {
        log.error(e);
        throw new IOException("Unable to set cell via thrift - " + e.toString());
      }
    }

    @Override
    String getTestName() {
      return "randomWrite";
    }
  }

  class ScanTest extends Test {
    private long scanner;
    private List<List<String>> resultRow;

    ScanTest(final Configuration conf, final int startRow,
        final int perClientRunRows, final int totalRows, final Status status) {
      super(conf, startRow, perClientRunRows, totalRows, status);
      resultRow = new ArrayList<List<String>>();
    }

    @Override
    void testSetup() throws IOException {
      super.testSetup();
      ScanSpec spec = new ScanSpec();

      RowInterval interval = new RowInterval();
      List<RowInterval> rowIntervals = new ArrayList<RowInterval>();

      interval.start_row = formatRowKey(this.startRow);
      interval.end_row = formatRowKey(this.startRow + this.perClientRunRows);
      rowIntervals.add(interval);
      spec.setRow_intervals(rowIntervals);

      spec.setRevs(1);

      //log.info("Creating scanner with spec: " + spec.toString());
      try {
        scanner = htClient.open_scanner(TABLE_NAME, spec, true);
      }
      catch (Exception e) {
        log.error(e);
        throw new IOException("Unable to create thrift scanner - " + e.toString());
      }
    }

    @Override
    void testTakedown() throws IOException {
      try {
        htClient.close_scanner(scanner);
      }
      catch (Exception e) {
        log.error(e);
        throw new IOException("Unable to close thrift scanner - " + e.toString());
      }
      super.testTakedown();
    }


    @Override
    void testRow(final int ii) throws IOException {
      try {
        resultRow = htClient.next_row_as_arrays(scanner);
        if (resultRow == null || resultRow.size() == 0)
          log.info("Got " + ii + "th result which is empty");
      }
      catch (Exception e) {
        log.error(e);
        throw new IOException("Unable to get next row from scanner - " + e.toString());
      }
    }

    @Override
    String getTestName() {
      return "scan";
    }
  }

  class SequentialReadTest extends Test {
    SequentialReadTest(final Configuration conf, final int startRow,
        final int perClientRunRows, final int totalRows, final Status status) {
      super(conf, startRow, perClientRunRows, totalRows, status);
    }

    @Override
    void testRow(final int ii) throws IOException {
      byte [] value;
      String row;
      row = formatRowKey(ii);
      try {
        value = htClient.get_cell(TABLE_NAME, row, COL_FAMILY_NAME);
        //log.info("Got result for row " + row + " value size=" + value.length);
      }
      catch (Exception e) {
        log.error(e);
        throw new IOException("Unable to get cell via thrift - " + e.toString());
      }
    }

    @Override
    String getTestName() {
      return "sequentialRead";
    }
  }

  class SequentialWriteTest extends Test {
    SequentialWriteTest(final Configuration conf, final int startRow,
        final int perClientRunRows, final int totalRows, final Status status) {
      super(conf, startRow, perClientRunRows, totalRows, status);
    }

    @Override
    void testRow(final int ii) throws IOException {
      Key key = new Key();
      byte [] value;
      Cell cell = new Cell();

      key.row = formatRowKey(ii);
      key.column_family = COL_FAMILY_NAME;
      key.column_qualifier = COL_QUALIFIER_NAME;
      value = generateValue(this.rand);
      cell.key = key;
      cell.value = value;

      try {
        htClient.set_cell(mutator, cell);
      }
      catch (Exception e) {
        log.error(e);
        throw new IOException("Unable to set cell via thrift - " + e.toString());
      }
    }

    @Override
    String getTestName() {
      return "sequentialWrite";
    }
  }

  /*
   * formatRowKey passed integer.
   * @param number
   * @return Returns zero-prefixed 10-byte wide decimal version of passed
   * number (Does absolute in case number is negative).
   */
  public static String formatRowKey(final int number) {
    int len = 10;
    StringBuilder str = new StringBuilder(len+1);
    int d = Math.abs(number);
    str.setLength(len);
    for (int ii = len - 1; ii >= 0; ii--) {
      str.setCharAt(ii, (char)((d % 10) + '0'));
      d /= 10;
    }
    return str.toString();
  }

  /*
   * This method takes some time and is done inline uploading data.  For
   * example, doing the mapfile test, generation of the key and value
   * consumes about 30% of CPU time.
   * @return Generated random value to insert into a table cell.
   */
  public static byte[] generateValue(final Random r) {
    byte [] b = new byte [ROW_LENGTH];
    r.nextBytes(b);
    return b;
  }

  static String getRandomRow(final Random random, final int totalRows) {
    return formatRowKey(random.nextInt(Integer.MAX_VALUE) % totalRows);
  }

  long runOneClient(final String cmd, final int startRow,
    final int perClientRunRows, final int totalRows, final Status status)
  throws IOException {
    status.setStatus("Start " + cmd + " at offset " + startRow + " for " +
      perClientRunRows + " rows");
    long totalElapsedTime = 0;
    if (cmd.equals(RANDOM_READ)) {
      Test t = new RandomReadTest(this.conf, startRow, perClientRunRows,
        totalRows, status);
      totalElapsedTime = t.test();
    } else if (cmd.equals(RANDOM_READ_MEM)) {
      throw new UnsupportedOperationException("Not yet implemented");
    } else if (cmd.equals(RANDOM_WRITE)) {
      Test t = new RandomWriteTest(this.conf, startRow, perClientRunRows,
        totalRows, status);
      totalElapsedTime = t.test();
    } else if (cmd.equals(SCAN)) {
      Test t = new ScanTest(this.conf, startRow, perClientRunRows, totalRows, status);
      totalElapsedTime = t.test();
    } else if (cmd.equals(SEQUENTIAL_READ)) {
      Test t = new SequentialReadTest(this.conf, startRow, perClientRunRows,
        totalRows, status);
      totalElapsedTime = t.test();
    } else if (cmd.equals(SEQUENTIAL_WRITE)) {
      Test t = new SequentialWriteTest(this.conf, startRow, perClientRunRows,
        totalRows, status);
      totalElapsedTime = t.test();
    } else if (cmd.equals(RANDOM_SEEK_SCAN)) {
      // TODO: implement this
      throw new UnsupportedOperationException("Not yet implemented");
      //Test t = new RandomSeekScanTest(this.conf, startRow, perClientRunRows,
      //    totalRows, status);
      //totalElapsedTime = t.test();
    } else {
      throw new IllegalArgumentException("Invalid command value: " + cmd);
    }
    status.setStatus("Finished " + cmd + " in " + totalElapsedTime +
      "ms at offset " + startRow + " for " + perClientRunRows + " rows");
    return totalElapsedTime;
  }

  private void runNIsOne(final String cmd) {
    Status status = new Status() {
      public void setStatus(String msg) throws IOException {
        log.info(msg);
      }
    };

    try {
      checkTable();
      runOneClient(cmd, 0, this.TOTAL_ROWS, this.TOTAL_ROWS, status);
    } catch (Exception e) {
      log.error("Failed", e);
    }
  }

  private void runTest(final String cmd) throws IOException,
  				InterruptedException, ClassNotFoundException {
    if (cmd.equals(RANDOM_READ_MEM)) {
      // For this one test, so all fits in memory, make R smaller (See
      // pg. 9 of BigTable paper).
      this.TOTAL_ROWS = (this.TOTAL_ROWS / 10) * NUM_CLIENTS;
    }

    if (this.NUM_CLIENTS == 1) {
      // If there is only one client and one HRegionServer, we assume nothing
      // has been set up at all.
      runNIsOne(cmd);
    } else {
      // Else, run
      runNIsMoreThanOne(cmd);
    }
  }

  private void printUsage() {
    printUsage(null);
  }

  private void printUsage(final String message) {
    if (message != null && message.length() > 0) {
      System.err.println(message);
    }
    System.err.println("Usage: java " + this.getClass().getName() + " \\");
    System.err.println("   [--rows=ROWS] <command> <nclients>");
    System.err.println();
    System.err.println("Options:");
    System.err.println(" rows            Rows each client runs. Default: One million");
    System.err.println();
    System.err.println("Command:");
    System.err.println(" randomRead      Run random read test");
    System.err.println(" randomReadMem   Run random read test where table " +
      "is in memory");
    System.err.println(" randomSeekScan  Run random seek and scan 100 test");
    System.err.println(" randomWrite     Run random write test");
    System.err.println(" sequentialRead  Run sequential read test");
    System.err.println(" sequentialWrite Run sequential write test");
    System.err.println(" scan            Run scan test");
    System.err.println();
    System.err.println("Args:");
    System.err.println(" nclients        Integer. Required. Total number of " +
      "clients (and RangeServers)");
    System.err.println("                 running: 1 <= value <= 500");
    System.err.println("Examples:");
    System.err.println(" To run a single evaluation client:");
    System.err.println(" $ bin/hypertable " +
      "org.hypertable.MapReduce.hadoop.PerformanceEvaluation sequentialWrite 1");
  }

  private void getArgs(final int start, final String[] args) {
    if(start + 1 > args.length) {
      throw new IllegalArgumentException("must supply the number of clients");
    }

    NUM_CLIENTS = Integer.parseInt(args[start]);
    if (NUM_CLIENTS < 1) {
      throw new IllegalArgumentException("Number of clients must be > 1");
    }

    // Set total number of rows to write.
    this.TOTAL_ROWS = this.TOTAL_ROWS * NUM_CLIENTS;
  }

  private int doCommandLine(final String[] args) {
    // Process command-line args. TODO: Better cmd-line processing
    // (but hopefully something not as painful as cli options).
    int errCode = -1;
    if (args.length < 1) {
      printUsage();
      return errCode;
    }

    try {
      for (int ii = 0; ii < args.length; ii++) {
        String cmd = args[ii];
        if (cmd.equals("-h") || cmd.startsWith("--h")) {
          printUsage();
          errCode = 0;
          break;
        }

        final String rows = "--rows=";
        if (cmd.startsWith(rows)) {
          // at this point its per client rows (till we parse NUM_CLIENTS)
          this.TOTAL_ROWS = Integer.parseInt(cmd.substring(rows.length()));
          continue;
        }

        if (COMMANDS.contains(cmd)) {
          getArgs(ii + 1, args);
          runTest(cmd);
          errCode = 0;
          break;
        }

        printUsage();
        break;
      }
    } catch (Exception e) {
      e.printStackTrace();
    }

    return errCode;
  }

  /**
   * @param args
   */
  public static void main(final String[] args) {
    Configuration c = new Configuration();
    System.exit(new PerformanceEvaluation(c).doCommandLine(args));
  }
}
