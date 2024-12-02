package org.sylvia;

import com.opencsv.CSVWriter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.BlockingDeque;

public class ReportProcessor {

    private static final Log log = LogFactory.getLog(ReportProcessor.class);
    private BlockingDeque<Record> records;

    public ReportProcessor(String filePath, BlockingDeque<Record> records) throws IOException {
        this.records = records;
        generateReport();
        writeToCSV(filePath);
    }

    // Calculate matrix and generate report for Client2
    private void generateReport() {
        List<Record> recordsList = new ArrayList<>();
        long totalDuration = 0;
        for(Record record : records) {
            recordsList.add(record);
            totalDuration += record.getLatency();
        }

        int p99Index = (int) Math.ceil(0.99 * recordsList.size());
        long p99Latency = recordsList.get(p99Index - 1).getLatency();

        Record lastRecord = recordsList.get(recordsList.size() - 1);
        Record firstRecord = recordsList.get(0);
        long duration = lastRecord.getStartTime() + lastRecord.getLatency() - firstRecord.getStartTime();

        Collections.sort(recordsList, (r1, r2) -> (int) (r1.getLatency() - r2.getLatency()));
        long meanLatency = totalDuration / recordsList.size();
        long medianLatency = recordsList.get(recordsList.size() / 2).getLatency();
        long minLatency = recordsList.get(0).getLatency();
        long maxLatency = recordsList.get(recordsList.size() - 1).getLatency();
        Double throughput = recordsList.size() / (duration / 1000.00);

        System.out.println("---------------------- Report Client2 ----------------------");
        System.out.println("Mean response time (millisecs): " + meanLatency);
        System.out.println("Median response time (millisecs): " + medianLatency);
        System.out.println("p99 (99th percentile) response time: " + p99Latency);
        System.out.println("Min response time (millisecs): " + minLatency);
        System.out.println("Max response time (millisecs): " + maxLatency);
        System.out.println(String.format("Throughput (RPS): %,.2f", throughput));
        System.out.println("---------------------- Report End --------------------------");
    }

    private void writeToCSV(String filePath) throws IOException {
        CSVWriter writer = new CSVWriter(new FileWriter(filePath));
        String[] header = {"Start Time", "Request Type", "Latency", "Response Code"};
        writer.writeNext(header);
        SimpleDateFormat ft = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
        for(Record record : records){
            String[] temp = new String[4];
            temp[0] = ft.format(new Date(record.getStartTime())) + "\t";
            temp[0] = record.getStartTime() + "\t";
            temp[1] = record.getRequestType();
            temp[2] = record.getLatency() + "\t";
            temp[3] = String.valueOf(record.getResponseCode());
            writer.writeNext(temp);
        }
        writer.close();
    }
}
