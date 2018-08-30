/*
Copyright 2015 Jason Sherman

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package com.redhat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Set;
import java.util.SortedSet;
import java.util.StringTokenizer;
import java.util.TreeSet;

/**
 * kahaDB MessageDatabase Trace Analyzer
 */
public class KahaDBTraceAnalyzer {

	private SortedSet<String> fullSet = null;
	private SortedSet<String> currentSet = null;
	private SortedSet<String> priorSet = null;
	private static String LOG_FILE = "kahadb.log";
	private int count = -1;
	private int containsAcks = 0;
	private int inUse = 0;
	private static boolean concise = false;
	private static boolean time = false;
	private static boolean debug = false;
	private static boolean verbose = false;
	private boolean checkpointDone = false;

	public static void main(String[] args) {
		if (args.length > 0) {
			LOG_FILE = args[0];
		}
		String sConcise = System.getProperty("concise", "false");

		if (sConcise.equalsIgnoreCase("true")) {
			concise = true;
		}
		String sTime = System.getProperty("time", "false");

		if (sTime.equalsIgnoreCase("true")) {
			time = true;
		}
		String sDebug = System.getProperty("debug", "false");

		if (sDebug.equalsIgnoreCase("true")) {
			debug = true;
		}

		String sVerbose = System.getProperty("verbose", "false");

		if (sVerbose.equalsIgnoreCase("true")) {
			verbose = true;
		}

		KahaDBTraceAnalyzer analyzer = new KahaDBTraceAnalyzer();
		analyzer.analyze();
	}

	private void analyze() {
		try {
			BufferedReader br = null;
			try {
				File file = new File(LOG_FILE);
				if (file.exists()) {
					System.out.println("Using log file: " + file.getAbsolutePath());
					br = new BufferedReader(new FileReader(file));
				}
			} catch (Exception e) {
				try {
					final URL url = new URL(LOG_FILE);
					System.out.println("Using log file at url: " + url);
					br = new BufferedReader(new InputStreamReader(url.openStream()));
				} catch (Exception e2) {
					InputStream stream = this.getClass().getResourceAsStream("/" + LOG_FILE);
					if (stream != null) {
						System.out.println("Using resource at: " + this.getClass().getResource("/" + LOG_FILE));
						br = new BufferedReader(new InputStreamReader(stream));
					}
				}
			}

			if (br == null) {
				System.out.println("Unable to locate log file, please check the name.");
				System.exit(-3);
			}

			String line;
			inUse = 0;

			while ((line = br.readLine()) != null) {
				if ((line.contains("MessageDatabase")) && (line.contains("gc candidates"))) {
					String orginLine = line;
					line = line.substring(line.indexOf("gc candidates"), line.length());
					// beginning of trace output, acquire full journal set
					if (line.contains("set:")) {
						// check if we have processed multiple occurrences
						if (count != -1) {
							logStats();
							// reset containsAcks
							containsAcks = 0;
							checkpointDone = false;
						}
						System.out.println("Acquiring Full Set...\n");
						fullSet = acquireSet(line, false);
						if (time) {
							System.out.println("Time: " + orginLine.substring(0, orginLine.indexOf(",") + 4));
						}
						System.out.println("Full journal set: " + fullSet.size());
						count = fullSet.size();
						priorSet = fullSet;
					}

					// only makes sense to gather stats once we have the full set
					if (fullSet != null) {

						if (line.contains("candidates after")) {
							/*
							 *  this metod will be invoked for: after first tx, producerSequenceIdTrackerLocation, ackMessageFileMapLocation,
							 *  tx range, and each destination found in the check point list
							 */
							processUsage(line);
						}
					}
				}

				// once we have the full working set, track acks and the final checkpoint
				if (fullSet != null) {
					if ((line.contains("MessageDatabase")) && (line.contains("not removing data file"))) {
						containsAcks++;
					}

					if ((line.contains("MessageDatabase")) && (line.contains("Checkpoint done."))) {
						checkpointDone = true;
					}
				}
			}
			br.close();

			logStats();
		} catch (Exception ioe) {
			ioe.printStackTrace();
			System.exit(-1);
		}
	}

	private void processUsage(String line) {
		String name = line.substring(line.indexOf("candidates after") + 17, line.indexOf(":"));
		//System.out.println("name:" + name);
		if (name.contains("dest")) {
			name = acquireDest(line);
		}
		//System.out.println("Processing " + name + " ...");
		currentSet = acquireSet(line, name.contains("tx range"));
		// inUse is calculated by the difference of the prior candidate set number and the current candiate set number
		// the difference is the number of journal files used by this destination
		// This being the case logging order is important in order to get accurate results
		if (debug) {
			System.out.println("Dest: " + name);
			System.out.println("priorSet.length: " + priorSet.size());
			System.out.println("currentSet.length: " + currentSet.size());
		}
		inUse = priorSet.size() - currentSet.size();
		Set<String> differenceSet = new TreeSet<>(priorSet);
		differenceSet.removeAll(currentSet);
		logDestStats(name, inUse, differenceSet);
		count = count - inUse;
		priorSet = currentSet;
	}

	private SortedSet<String> acquireSet(String line, boolean tx) {
		SortedSet<String> identifierSet = new TreeSet<>();

		if (tx) {
			line = line.substring(line.indexOf("]") + 2, line.length());
			line = line.substring(line.indexOf("[") + 1, line.indexOf("]"));

			if (debug) {
				System.out.println("tx range set: " + line);
			}
		} else {
			line = line.substring(line.indexOf("[") + 1, line.indexOf("]"));
		}

		StringTokenizer token = new StringTokenizer(line, ",");

		while (token.hasMoreElements()) {
			identifierSet.add(((String) token.nextElement()).trim());
		}

		return identifierSet;
	}

	private String acquireDest(String line) {
		String type = line.substring(line.indexOf("dest:") + 5, line.indexOf("dest:") + 6);
		line = line.substring(line.indexOf("dest:") + 7, line.length());
		return line.substring(0, line.indexOf(",")) + (type.equals("0") ? " (Queue)" : " (Topic)");
	}

	private void logDestStats(String dest, int count, Set<String> differenceSet) {
		if (concise && count == 0) {
			// do nothing and return
			return;
		}
		if (verbose && differenceSet.size() > 0) {
			System.out.println(count + " --- " + dest + " (" + differenceSet + ")");
		} else {
			System.out.println(count + " --- " + dest);
		}
	}

	private void logStats() {
		if (fullSet != null) {
			if (containsAcks > 0) {
				System.out.println("Journals containing acks: " + containsAcks);
			}
			System.out.println("Candidates for cleanup: " + (count - containsAcks) + "\n");
			System.out.println((checkpointDone ? "Analysis is complete\n" : "!!! Unable to determine if checkpoint is done. Please try increasing log size !!!\n"));
		} else {
			System.out.println("\nUnable to determine full log set\nCheck that TRACE level logging has been enabled for org.apache.activemq.store.kahadb.MessageDatabase"
				+ "\nand that the log contains a full output from the trace logging.  In some cases you may need to increase the log size.\n");
		}
	}
}
