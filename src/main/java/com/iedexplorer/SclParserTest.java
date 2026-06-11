package com.iedexplorer;

import com.beanit.iec61850bean.*;
import java.io.*;
import java.util.List;

/**
 * Test simple para verificar que SclParser funciona
 */
public class SclParserTest {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java SclParserTest <scl_file>");
            System.out.println("Example: java SclParserTest C:\\path\\to\\file.icd");
            return;
        }

        String sclPath = args[0];
        System.out.println("=== SCL Parser Test ===");
        System.out.println("File: " + sclPath);

        File file = new File(sclPath);
        if (!file.exists()) {
            System.err.println("ERROR: File not found!");
            return;
        }

        System.out.println("File size: " + (file.length() / 1024) + " KB");
        System.out.println();
        System.out.println("Parsing... (this may take a while for large files)");

        long startTime = System.currentTimeMillis();

        try (FileInputStream fis = new FileInputStream(file)) {
            List<ServerModel> models = SclParser.parse(fis);

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("Parsing completed in " + elapsed + " ms");
            System.out.println();

            if (models == null || models.isEmpty()) {
                System.err.println("ERROR: No server models found in file!");
                return;
            }

            System.out.println("Models found: " + models.size());

            ServerModel model = models.get(0);
            System.out.println();
            System.out.println("=== Model Structure ===");

            int ldCount = 0;
            int lnCount = 0;
            int doCount = 0;
            int daCount = 0;

            for (ModelNode ld : model.getChildren()) {
                ldCount++;
                System.out.println("LD: " + ld.getName());

                for (ModelNode ln : ld.getChildren()) {
                    lnCount++;
                    System.out.println("  LN: " + ln.getName());

                    if (ln instanceof LogicalNode) {
                        for (ModelNode fcdo : ln.getChildren()) {
                            if (fcdo instanceof FcDataObject) {
                                doCount++;
                                FcDataObject fdo = (FcDataObject) fcdo;
                                daCount += countBdas(fdo);
                            }
                        }
                    }
                }
            }

            System.out.println();
            System.out.println("=== Summary ===");
            System.out.println("Logical Devices: " + ldCount);
            System.out.println("Logical Nodes: " + lnCount);
            System.out.println("Data Objects: " + doCount);
            System.out.println("Data Attributes: " + daCount);
            System.out.println();
            System.out.println("SUCCESS: SCL parsing works correctly!");

        } catch (SclParseException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            System.err.println("SCL Parse ERROR after " + elapsed + "ms: " + e.getMessage());
            e.printStackTrace();
        } catch (IOException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            System.err.println("IO ERROR after " + elapsed + "ms: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            System.err.println("UNEXPECTED ERROR after " + elapsed + "ms: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static int countBdas(ModelNode node) {
        int count = 0;
        if (node instanceof BasicDataAttribute) {
            return 1;
        }
        for (ModelNode child : node.getChildren()) {
            count += countBdas(child);
        }
        return count;
    }
}
