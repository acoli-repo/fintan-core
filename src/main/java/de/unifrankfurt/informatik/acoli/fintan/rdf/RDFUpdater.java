/*
 * Copyright [2017] [ACoLi Lab, Prof. Dr. Chiarcos, Goethe University Frankfurt]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.unifrankfurt.informatik.acoli.fintan.rdf;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.commons.lang3.tuple.MutableTriple;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.QueryParseException;
import org.apache.jena.rdf.listeners.ChangedListener;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.update.Update;
import org.apache.jena.update.UpdateAction;
import org.apache.jena.update.UpdateFactory;
import org.apache.jena.update.UpdateRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.unifrankfurt.informatik.acoli.fintan.core.StreamRdfUpdater;
import de.unifrankfurt.informatik.acoli.fintan.core.FintanCLIManager;


/**
 *  @author Christian Chiarcos {@literal chiarcos@informatik.uni-frankfurt.de}
 *  @author Christian Faeth {@literal faeth@em.uni-frankfurt.de}
 */
public class RDFUpdater extends StreamRdfUpdater {
	static final Logger LOG = LogManager.getLogger(RDFUpdater.class);
	static final String DEFAULTUPDATENAME = "DIRECTUPDATE";
	static final int MAXITERATE = 999;
	static final List<Integer> CHECKINTERVAL = Arrays.asList(3, 10, 25, 50, 100, 200, 500);

	private final Dataset dataset;

	// Configuration Variables with defaults set
	private boolean prefixDeduplication = false;
	private int threads = 0;
	private int lookahead_snts = 0;
	private int lookback_snts = 0;
	private File graphOutputDir = null;
	private File triplesOutputDir = null;

	//for updates
	private final List<Triple<String, String, String>> updates = Collections.synchronizedList(new ArrayList<Triple<String, String, String>>());
	//For graphsout and triplesout
	private final List<String> graphOutputSentences = Collections.synchronizedList(new ArrayList<String>());
	private final List<String> triplesOutputSentences = Collections.synchronizedList(new ArrayList<String>());

	// for thread handling
	private boolean running = false;
	private final List<UpdateThread> updateThreads = Collections.synchronizedList(new ArrayList<UpdateThread>());
	// Buffer providing each thread with its respective sentence(s) to process
	// <List:lookbackBuffer>, <String:currentSentence>, <List:lookaheadBuffer>
	private final List<Triple<List<Model>, Model, List<Model>>> sentBufferThreads = Collections.synchronizedList(new ArrayList<Triple<List<Model>, Model, List<Model>>>());

	private final List<Model> sentBufferLookahead = Collections.synchronizedList(new ArrayList<Model>());
	private final List<Model> sentBufferLookback = Collections.synchronizedList(new ArrayList<Model>());
	// Buffer for outputting sentences in original order
	private final List<Pair<Integer, Model>> sentBufferOut = Collections.synchronizedList(new ArrayList<Pair<Integer, Model>>()); 

	//for statistics
	private final List<List<Pair<Integer,Long>>> dRTs = Collections.synchronizedList(new ArrayList<List<Pair<Integer,Long>>>());
	// iterations and execution time of each update in seconds


	private class UpdateThread extends Thread {
		
		private RDFUpdater updater;
		private int threadID;
		private Dataset memDataset;
		
		/**
		 * Each UpdateThread receives its own ID and a back-reference to the calling Updater.
		 * 
		 * In the current implementation, each thread manages its own in-memory Dataset.
		 * This is the fastest approach since no concurring access on a single Datasets occurs.
		 * However: lots of RAM may be needed.
		 * 
		 * @param updater
		 * 				The calling Updater (= ThreadHandler)
		 * @param id
		 * 				The id of this Thread.
		 */
		public UpdateThread(RDFUpdater updater, int id) {
			this.updater = updater;
			threadID = id;
			memDataset = DatasetFactory.create();
			Iterator<String> iter = updater.dataset.listNames();
			while(iter.hasNext()) {
				String graph = iter.next();
				memDataset.addNamedModel(graph, updater.dataset.getNamedModel(graph));
			}
			memDataset.addNamedModel("https://github.com/acoli-repo/conll-rdf/lookback", ModelFactory.createDefaultModel());
			memDataset.addNamedModel("https://github.com/acoli-repo/conll-rdf/lookahead", ModelFactory.createDefaultModel());
		}
		
		/**
		 * Run the update thread.
		 * Load the buffer, execute the updates with all iterations and graphsout, unload the buffer.
		 */
		public void run() {
			while (updater.running) {
				//Execute Thread

				Model out = null;
				LOG.trace("NOW Processing on thread "+threadID+": outputbuffersize "+sentBufferOut.size());
				Triple<List<Model>, Model, List<Model>> sentBufferThread = sentBufferThreads.get(threadID);
				try {
					loadBuffer(sentBufferThread);
					
					List<Pair<Integer,Long> > ret = executeUpdates(updates);
					if (dRTs.get(threadID).isEmpty())
						dRTs.get(threadID).addAll(ret);
					else
						for (int x = 0; x < ret.size(); ++x)
							dRTs.get(threadID).set(x, new ImmutablePair<Integer, Long>(
									dRTs.get(threadID).get(x).getKey() + ret.get(x).getKey(),
									dRTs.get(threadID).get(x).getValue() + ret.get(x).getValue()));
					
					out = unloadBuffer(sentBufferThread);
				} catch (Exception e) {
//					memDataset.begin(ReadWrite.WRITE);
					memDataset.getDefaultModel().removeAll();
					memDataset.getNamedModel("https://github.com/acoli-repo/conll-rdf/lookback").removeAll();
					memDataset.getNamedModel("https://github.com/acoli-repo/conll-rdf/lookahead").removeAll();
//					memDataset.commit();
//					memDataset.end();
					e.printStackTrace();
//					continue;
				}

				
				
				// synchronized write access to sentBuffer in order to avoid corruption
				synchronized(updater) {
				LOG.trace("NOW PRINTING on thread "+threadID+": outputbuffersize "+sentBufferOut.size());
				for (int i = 0; i < sentBufferOut.size(); i++) {
					if (sentBufferOut.get(i).getLeft() == threadID) {
						sentBufferOut.set(i, new ImmutablePair<Integer, Model>(-1, out));
						break;
					}
				}				
				
				//go to sleep and let Updater take control
					LOG.trace("Updater notified by "+threadID);
					updater.notify();

				}
				try {
					synchronized (this) {
						LOG.trace("Waiting: "+threadID);
						wait();
					}
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		
		/**
		 * Loads Data to this thread's working model.
		 * @param buffer 
		 * 			the model to be read.
		 * @throws Exception
		 */
		private void loadBuffer(Triple<List<Model>, Model, List<Model>> sentBufferThread) throws Exception { //TODO: adjust for TXN-Models
			//load ALL
			try {
//				memDataset.begin(ReadWrite.WRITE);
				
				// for lookback
				for (Model m:sentBufferThread.getLeft()) {
					memDataset.getNamedModel("https://github.com/acoli-repo/conll-rdf/lookback").add(m);
				}
				
				// for current sentence
				memDataset.getDefaultModel().add(sentBufferThread.getMiddle());

				// for lookahead
				for (Model sent:sentBufferThread.getRight()) {
					memDataset.getNamedModel("https://github.com/acoli-repo/conll-rdf/lookahead").add(sent);
				}
				
//				memDataset.commit();
//				Model m = ModelFactory.createDefaultModel().read(new StringReader(buffer),null, "TTL");
//				memAccessor.add(m);
//				memDataset.getDefaultModel().setNsPrefixes(m.getNsPrefixMap());
			} catch (Exception ex) {
				LOG.error("Exception while reading: " + sentBufferThread.getMiddle());
				throw ex;
			} finally {
//				memDataset.end();
			}
			
		}

		/**
		 * Unloads Data from this thread's working model.
		 * Includes comments from original data.
		 * @param buffer
		 * 			Original data for extracting comments.
		 * @param out
		 * 			Output Writer.
		 * @throws Exception
		 */
		private Model unloadBuffer(Triple<List<Model>, Model, List<Model>> sentBufferThread) throws Exception { //TODO: adjust for TXN-Models
			Model out = ModelFactory.createDefaultModel();
//START		ARTIFACT
//			String buffer = sentBufferThread.getMiddle();
			try {
//				BufferedReader in = new BufferedReader(new StringReader(buffer));
//				String line;
//				while((line=in.readLine())!=null) {
//					line=line.trim();
//					if(line.startsWith("#")) out.write(line+"\n");
//				}
//END		ARTIFACT
				out.add(memDataset.getDefaultModel());
			} catch (Exception ex) {
//				memDataset.abort();
//				LOG.error("Exception while unloading: " + buffer);
			} finally {
//				memDataset.begin(ReadWrite.WRITE);
				memDataset.getDefaultModel().removeAll();
				memDataset.getNamedModel("https://github.com/acoli-repo/conll-rdf/lookback").removeAll();
				memDataset.getNamedModel("https://github.com/acoli-repo/conll-rdf/lookahead").removeAll();
//				memDataset.commit();
//				memDataset.end();
			}
			return out;
		}
		
		/**
		 * Executes updates on this thread. Data must be preloaded first.
		 * 
		 * @param updates
		 * 			The updates as a List of Triples containing
		 * 			- update filename
		 * 			- update script
		 * 			- number of iterations
		 * @return
		 * 			List of pairs containing Execution info on each update:
		 * 			- total no. of iterations
		 * 			- total time
		 */
		private List<Pair<Integer, Long>> executeUpdates(List<Triple<String, String, String>> updates) { 

			String sent = new String();
			boolean graphsout = false;
			boolean triplesout = false;
			if (graphOutputDir != null || triplesOutputDir != null) {
				try {
				sent = memDataset.getDefaultModel().listSubjectsWithProperty(
								memDataset.getDefaultModel().getProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"), 
								memDataset.getDefaultModel().getProperty("http://persistence.uni-leipzig.org/nlp2rdf/ontologies/nif-core#Sentence")
							).next().getLocalName();
				} catch (Exception e) {
					sent = "none";
				}
				if (graphOutputSentences.contains(sent)){
					graphsout = true;
				}

				if (triplesOutputSentences.contains(sent)){
					triplesout = true;
				}
				if (graphsout) try {
						produceDot(memDataset.getDefaultModel(), "INIT", null, sent, 0, 0, 0);
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				if (triplesout) try {
						produceNTRIPLES(memDataset.getDefaultModel(), "INIT", null, sent, 0, 0, 0);
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
			}
			List<Pair<Integer,Long> > result = new ArrayList<Pair<Integer,Long> >();
			int upd_id = 1;
			int iter_id = 1;
			for(Triple<String, String, String> update : updates) {
				iter_id = 1;
				Long startTime = System.currentTimeMillis();
				Model defaultModel = memDataset.getDefaultModel();
				ChangedListener cL = new ChangedListener();
				defaultModel.register(cL);
				String oldModel = "";
				int frq = MAXITERATE, v = 0;
				boolean change = true;
				try {
					frq = Integer.parseInt(update.getRight());
				} catch (NumberFormatException e) {
					if (!"*".equals(update.getRight()))
						throw e;
				}
				while(v < frq && change) {
					try {
						UpdateRequest updateRequest;
						updateRequest = UpdateFactory.create(update.getMiddle());
						if (graphsout || triplesout) { //execute Update-block step by step and output intermediate results
							int step = 1;
							Model dM = memDataset.getDefaultModel();
							String dMS = dM.toString();
							ChangedListener cLdM = new ChangedListener();
							dM.register(cLdM);
							for(Update operation : updateRequest.getOperations()) {
								//							memDataset.begin(ReadWrite.WRITE);
								UpdateAction.execute(operation, memDataset);
								//							memDataset.commit();
								//							memDataset.end();
								if (cLdM.hasChanged() && (!dMS.equals(memDataset.getDefaultModel().toString()))) {
									if (graphsout) try {
										produceDot(defaultModel, update.getLeft(), operation.toString(), sent, upd_id, iter_id, step);
									} catch (IOException e) {
										LOG.error("Error while producing DOT for update No. "+upd_id+": "+update.getLeft());
										e.printStackTrace();
									}
									if (triplesout) try {
										produceNTRIPLES(defaultModel, update.getLeft(), operation.toString(), sent, upd_id, iter_id, step);
									} catch (IOException e) {
										LOG.error("Error while producing NTRIPLES for update No. "+upd_id+": "+update.getLeft());
										e.printStackTrace();
									}
								}
								step++;
							}
						} else { //execute updates en bloc
							//						memDataset.begin(ReadWrite.WRITE);
							UpdateAction.execute(updateRequest, memDataset); //REMOVE THE PARAMETERS sent_id, upd_id, iter_id to use deshoe's original file names
							//						memDataset.commit();
							//						memDataset.end();
						}
					} catch (Exception e) {
						LOG.error("Error while processing update No. "+upd_id+": "+update.getLeft());
						e.printStackTrace();
					}
					
					
					if (oldModel.isEmpty()) {
						change = cL.hasChanged();
						LOG.trace("cl.hasChanged(): "+change);
					} else {
						change = !defaultModel.toString().equals(oldModel);
						oldModel = "";
					}
					if (CHECKINTERVAL.contains(v))
						oldModel = defaultModel.toString();
					v++;
					iter_id++;
				}
				if (v == MAXITERATE)
					LOG.warn("Warning: MAXITERATE reached for " + update.getLeft() + ".");
				result.add(new ImmutablePair<Integer, Long>(v, System.currentTimeMillis() - startTime));
				defaultModel.unregister(cL);
				upd_id++;
			}			
			return result;
		}
		
		/**
		 * Produce dotFile for a specific update iteration.
		 * 
		 * @param m
		 * 			The current model.
		 * @param updateSrc
		 * 			The update source filename.
		 * @param updateQuery
		 * 			The update query string.
		 * @param sent
		 * 			The sentence ID.
		 * @param upd_id
		 * 			The update ID.
		 * @param iter_id
		 * 			The ID of the current iteration of the given update on the given sentence.
		 * @param step
		 * 			The single isolated query step of the current update.
		 * @throws IOException
		 */
		private void produceDot(Model m, String updateSrc, String updateQuery, String sent, int upd_id, int iter_id, int step) throws IOException {
			if (graphOutputDir != null) {
				String updateName = (new File(updateSrc)).getName();
				updateName = (updateName != null && !updateName.isEmpty()) ? updateName : UUID.randomUUID().toString();
				
				File outputFile = new File(graphOutputDir, sent
								+"__U"+String.format("%03d", upd_id)
								+"_I" +String.format("%04d", iter_id)
								+"_S" +String.format("%03d", step)
								+"__" +updateName.replace(".sparql", "")+".dot");
				Writer w = new OutputStreamWriter(new FileOutputStream(outputFile), StandardCharsets.UTF_8);
//TODO:				CoNLLRDFViz.produceDot(m, w, updateQuery);
			}		
		}
		
		/**
		 * Produce lexicographically sorted ntriples-file for a specific update iteration.
		 * 
		 * @param m
		 * 			The current model.
		 * @param updateSrc
		 * 			The update source filename.
		 * @param updateQuery
		 * 			The update query string.
		 * @param sent
		 * 			The sentence ID.
		 * @param upd_id
		 * 			The update ID.
		 * @param iter_id
		 * 			The ID of the current iteration of the given update on the given sentence.
		 * @param step
		 * 			The single isolated query step of the current update.
		 * @throws IOException
		 */
		private void produceNTRIPLES(Model m, String updateSrc, String updateQuery, String sent, int upd_id, int iter_id, int step) throws IOException {
			if (triplesOutputDir != null) {
				String updateName = (new File(updateSrc)).getName();
				updateName = (updateName != null && !updateName.isEmpty()) ? updateName : UUID.randomUUID().toString();
				
				File outputFile = new File(triplesOutputDir, sent
								+"__U"+String.format("%03d", upd_id)
								+"_I" +String.format("%04d", iter_id)
								+"_S" +String.format("%03d", step)
								+"__" +updateName.replace(".sparql", "")+".nt");
				//write N3 to String
				StringWriter w = new StringWriter();
				m.write(w, "N-TRIPLE");
				//sort lines
				List<String> list = Arrays.asList(w.toString().split("\\n"));
				Collections.sort(list);
				//Write lines to file
				Writer out = new OutputStreamWriter(new FileOutputStream(outputFile), StandardCharsets.UTF_8);
				for (String s : list) {
					out.write(s+"\n");
				}
				out.flush();
				out.close();
			}		
		}
	}

	/**
	 * Default Constructor providing empty data to the standard constructor.
	 */
	public RDFUpdater() {
		this("", "", 0);
	}
	
	/**
	 * Standard Constructor for Updater. Creates Threads and Buffers for Thread handling.
	 * Also creates the database modules for the respective execution modes.
	 * @param type: The type of database to be used:
	 * 				MEM: fully independent in-memory datasets per thread 
	 * 						(fastest, no transactions, high RAM usage, no HDD)
	 * 				TXN: single transactional in-memory dataset for all threads
	 * 						(in development, medium speed and RAM, no HDD)
	 * 				TDB2: single transactional TDB2-database for all threads
	 * 						(in development, slow-medium speed, low RAM usage, high HDD usage)
	 * 				default: MEM
	 * @param path: 
	 * 				path to database (only for TDB2 or other DB-backed modes)
	 * @param threads
	 * 				Maximum amount of threads for execution.
	 * 				default: threads = number of logical cores available to runtime
	 */
	public RDFUpdater(String type, String path, int threads) {
		if (type.equals("TDB2")) {
			//TODO
			dataset = DatasetFactory.create();//TDB
		} else if (type.equals("TXN")) {
			dataset = DatasetFactory.createTxnMem();
		} else {
			dataset = DatasetFactory.createTxnMem();
		}
//		memAccessor = DatasetAccessorFactory.create(memDataset);

		setThreads(threads);

		running = false;
	}

	public void setThreads(int threads) {
		this.threads = threads;
	}
	public int getThreads() {
		return threads;
	}

	public String[] getUpdateNames() {
		return updates.stream().map(t -> t.getLeft()).toArray(String[]::new);
	}
	public String[] getUpdateStrings() {
		return updates.stream().map(t -> t.getMiddle()).toArray(String[]::new);
	}
	public String[] getUpdateMaxIterations() {
		return updates.stream().map(t -> t.getRight()).toArray(String[]::new);
	}

	/**
	 * Activates the lookahead mode for caching a fixed number of additional sentences per thread.
	 * @param lookahead_snts
	 * 			the number of additional sentences to be cached
	 */
	public void activateLookahead(int lookahead_snts) {
		if (lookahead_snts < 0) lookahead_snts = 0;
		this.lookahead_snts = lookahead_snts;
	}
	public int getLookahead() {
		return lookahead_snts;
	}

	/**
	 * Activates the lookback mode for caching a fixed number of preceding sentences per thread.
	 * @param lookback_snts
	 * 			the number of preceding sentences to be cached
	 */
	public void activateLookback(int lookback_snts) {
		if (lookback_snts < 0) lookback_snts = 0;
		this.lookback_snts = lookback_snts;
	}
	public int getLookback() {
		return lookback_snts;
	}

	/**
	 * Activates the graphsout mode for single graphviz .dot files per execution step.
	 * @param dir
	 * 			folder to store .dot files
	 * @param sentences
	 * 			List of sentenceIDs to be included in graphsout mode (s23_0, s4_0 ...)
	 * @throws IOException
	 */
	public void activateGraphsOut(String dir, List<String> sentences) throws IOException {
		graphOutputSentences.clear();
		graphOutputDir = new File(dir.toLowerCase());
		if (graphOutputDir.exists() || graphOutputDir.mkdirs()) {
			if (! graphOutputDir.isDirectory()) {
				graphOutputDir = null;
				throw new IOException("Error: Given -graphsout DIRECTORY is not a valid directory: " + dir.toLowerCase());
			}
		} else {
			graphOutputDir = null;
			throw new IOException("Error: Failed to create given -graphsout DIRECTORY: " + dir.toLowerCase());
		}
		graphOutputSentences.addAll(sentences);
	}
	public File getGraphOutputDir() {
		return graphOutputDir;
	}
	public String[] getGraphOutputSentences() {
		return graphOutputSentences.toArray(new String[graphOutputSentences.size()]);
	}

	/**
	 * Activates the triplesout mode for single ntriples-files per execution step.
	 * @param dir
	 * 			folder to store .dot files
	 * @param sentences
	 * 			List of sentenceIDs to be included in triplesout mode (s23_0, s4_0 ...)
	 * @throws IOException
	 */
	public void activateTriplesOut(String dir, List<String> sentences) throws IOException {
		triplesOutputSentences.clear();
		triplesOutputDir = new File(dir.toLowerCase());
		if (triplesOutputDir.exists() || triplesOutputDir.mkdirs()) {
			if (! triplesOutputDir.isDirectory()) {
				triplesOutputDir = null;
				throw new IOException("Error: Given -triplesout DIRECTORY is not a valid directory: " + dir.toLowerCase());
			}
		} else {
			triplesOutputDir = null;
			throw new IOException("Error: Failed to create given -triplesout DIRECTORY: " + dir.toLowerCase());
		}
		triplesOutputSentences.addAll(sentences);
	}
	public File getTriplesOutputDir() {
		return triplesOutputDir;
	}
	public String[] getTriplesOutputSentences() {
		return triplesOutputSentences.toArray(new String[triplesOutputSentences.size()]);
	}

	/**
	 * Instruct the Updater to remove duplicates of RDF prefixes, to avoid issues with segmented data using a single prefix header.
	 */
	public void activatePrefixDeduplication() {
		this.prefixDeduplication = true;
	}
	public boolean getPrefixDeduplication() {
		return prefixDeduplication;
	}

	/**
	 * Load external RDF file into a named graph of the local dataset. 
	 * This graph is permanent for the runtime and is accessed read-only by all threads.
	 * The default graph of the local dataset is reserved for updating nif:Sentences and 
	 * can not be defined here.
	 * @param url
	 * 			location of the RDF file to be loaded
	 * @param graph (optional)
	 * 			the named graph to load the data into.
	 * 			default: graph = url
	 * @throws IOException
	 */
	public void loadGraph(URI url, URI graph) throws IOException {
		LOG.info("loading...");
		LOG.info(url +" into "+ graph);
		if (!url.isAbsolute()) {
			url = (new File(url.toString())).toURI();
		}
		if (graph == null) {
			graph = url;
		}
		Model m = ModelFactory.createDefaultModel();
		try {
			m.read(readInURI(url));
			dataset.addNamedModel(graph.toString(), m);
		} catch (IOException ex) {
			LOG.error("Exception while reading " + url + " into " + graph);
			throw ex;
		}
		LOG.info("done...");
	}
	public boolean hasGraph(String name) {
		return dataset.containsNamedModel(name);
	}
	public Model getGraph(String name) {
		//TODO return a copy instead of a reference
		return dataset.getNamedModel(name);
	}

	/**
	 * Define a set of updates to be executed for each sentence processed by this CoNLLRDFUpdater.
	 * Existing updates will be overwritten by calling this function.
	 * @param updatesRaw
	 * 			The new set of updates as a List of String Triples. Each Triple has the following form:
	 * 			<Name of Update>, <update script>OR<path to script>, <iterations>
	 * @throws IOException
	 */
	public void parseUpdates(List<Triple<String, String, String>> updatesRaw) throws IOException, ParseException {
		updates.clear();
		final List<Triple<String, String, String>> updatesOut = new ArrayList<Triple<String, String, String>>(updatesRaw.size());

		int updateNo = 0;
		for(Triple<String, String, String> update: updatesRaw) {
			String updateName = update.getLeft();
			final String updateScriptRaw = update.getMiddle(); // either an URL/ a path to, or the verbatim sparql
			String updateScript = null; // will eventually contain the sparql query
			final String updateIterations = update.getRight();
			updateNo++; // Used for logging

			LOG.debug("Update No."+updateNo+" named "+updateName+" with "+updateIterations+" iterations is\n"+updateScriptRaw);

			/* Possible issues to catch gracefully:
			 * - Path to update query is wrong (Issue#5)
			 * - URL cannot be reached
			 * - provided query is a select query
			 * - verbatim query was not quoted
			 */

			try {
				updateScript = FintanCLIManager.readSourceAsString(updateScriptRaw);
			} catch (Exception e) {
				LOG.debug("Attempt to read Update from File/URL source failed. Attempting to parse as direct update.");
				LOG.debug(e, e);
			}
			
			// check for String as Update and set update name to default
			if (updateScript == null) {
				updateName = DEFAULTUPDATENAME;
				updateScript = updateScriptRaw;
				LOG.debug("StringReader ok");
			}

			try {
				@SuppressWarnings("unused")
				UpdateRequest qexec = UpdateFactory.create(updateScript);
			} catch (QueryParseException e) {
				LOG.error("Failed to parse argument as sparql");
				// if update looks like a file, but can't be found (=> DEFAUTUPDATENAME has been set by runtime "==", not "equals()")
				if(updateScriptRaw.toLowerCase().endsWith(".sparql") && updateName == DEFAULTUPDATENAME) {
					LOG.debug("SPARQL parse exception for Update No. "+updateNo+": "+updateName+"\n" + e + "\n" + updateScript);
					throw new ParseException("The passed update No. "+updateNo+" looks like a file-path, however the file " + updateScriptRaw + " could not be found.");
				} else {
					throw new ParseException("SPARQL parse exception for Update No. "+updateNo+": "+updateName+"\n" + e + "\n" + updateScript); // this is SPARQL code with broken SPARQL syntax
				}
			}
			updatesOut.add(new ImmutableTriple<String, String, String> (updateName, updateScript, updateIterations));
			LOG.debug("Update parsed ok");
		}
		updates.addAll(Collections.synchronizedList(updatesOut));
	}

	/**
	 * Tries to read from a specific URI.
	 * Tries to read content directly or from GZIP
	 * Validates content against UTF-8.
	 * @param uri
	 * 		the URI to be read
	 * @return
	 * 		the text content
	 * @throws MalformedURLException
	 * @throws IOException
	 */
	private static String readInURI(URI uri) throws MalformedURLException, IOException {
		String result = null;
		try {
			result = uri.toString();
			if (result != null && result.endsWith(".gz")) {
				StringBuilder sb = new StringBuilder();
				BufferedReader br = new BufferedReader(new InputStreamReader(new GZIPInputStream(uri.toURL().openStream())));
				for (String line; (line = br.readLine()) != null; sb.append(line));
				result = sb.toString();
				isValidUTF8(result, "Given URI input (" + uri.getPath() + ") is not UTF-8 encoded");
			}
		} catch (Exception ex) {
			LOG.error("Excpetion while reading " + uri.getPath());
			throw ex;
		}
		return result;
	}
	
	private static void isValidUTF8(String s, String message) {
		try 
		{
			s.getBytes("UTF-8");
		} 
		catch (UnsupportedEncodingException e)
		{
		    LOG.error(message + " - Encoding error: " + e.getMessage());
		    System.exit(-1);
		}		
	}

	/**
	 * Processes CoNLL-RDF on the local dataset using the predfined updates and threads.
	 * Streams data from a buffered reader to a buffered writer. Distributes the processing 
	 * across available threads. Each thread handles one sentence at a time.
	 * Caches and outputs the resulting sentences in-order.
	 * @throws IOException
	 */
	protected void processStream() throws IOException {
		initThreads();
		running = true;

		
//		List<Pair<Integer,Long> > dRTs = new ArrayList<Pair<Integer,Long> >(); // iterations and execution time of each update in seconds
		while (getInputStream().canRead()) {
			try {
				Model buffer = getInputStream().read();
				if (buffer == null) continue;

				// GRAPH OUTPUT determine first sentence's id, if none were specified
				if ((graphOutputDir != null) && (graphOutputSentences.isEmpty())) {
					String sentID = readFirstSentenceID(buffer);
					graphOutputSentences.add(sentID);
					LOG.debug("Graph Output defaults to first sentence: " + sentID);
				}
				// TRIPLES OUTPUT determine first sentence's id, if none were specified
				if ((triplesOutputDir != null) && (triplesOutputSentences.isEmpty())) {
					String sentID = readFirstSentenceID(buffer);
					triplesOutputSentences.add(sentID);
					LOG.debug("Triples Output defaults to first sentence: " + sentID);
				}

				//lookahead
				//add ALL sentences to sentBufferLookahead
				sentBufferLookahead.add(buffer);
				if (sentBufferLookahead.size() > lookahead_snts) {
					//READY TO PROCESS 
					// remove first sentence from buffer and process it.
					// !!if lookahead = 0 then only current buffer is in sentBufferLookahead!!
					executeThread(sentBufferLookahead.remove(0));
				}		
				
				//lookback
				//needs to consider lookahead buffer. The full buffer size needs to be lookahead + lookback.
				if (lookback_snts > 0) {
					while (sentBufferLookback.size() >= lookback_snts + sentBufferLookahead.size()) sentBufferLookback.remove(0);
					sentBufferLookback.add(buffer);
				}

				flushOutputBuffer();
			
		} catch (InterruptedException e) {
			LOG.error("Resuming from interrupted thread when reading from default Stream: " +e);
		}
		}


		// LOOKAHEAD work down remaining buffer
		while (sentBufferLookahead.size()>0) {
			executeThread(sentBufferLookahead.remove(0));
			if (lookback_snts > 0) {
				while (sentBufferLookback.size() >= lookback_snts + sentBufferLookahead.size()) sentBufferLookback.remove(0);
			}
		}
			
		
		//wait for threads to finish work
		boolean threadsRunning = true;
		while(threadsRunning) {
			threadsRunning = false;
			for (UpdateThread t:updateThreads) {
				if (t != null)
				if (t.getState() == Thread.State.RUNNABLE || t.getState() == Thread.State.BLOCKED) {
					threadsRunning = true;
				}
			}
		}
		//terminate all threads
		running = false;
		for (UpdateThread t:updateThreads) {
			if (t != null)
			if(t.getState() == Thread.State.NEW) {
				t.start(); //in case of spontaneous resurrection, new threads should not have any work to do at this point
			} else if (!(t.getState() == Thread.State.TERMINATED)) {
				synchronized(t) {
					t.notify();
				}
			}
		}
		
		//sum up statistics
		List<Pair<Integer,Long>> dRTs_sum = new ArrayList<Pair<Integer,Long> >();
		for (List<Pair<Integer,Long>> dRT_thread:dRTs) {
			if (dRTs_sum.isEmpty())
				dRTs_sum.addAll(dRT_thread);
			else
				for (int x = 0; x < dRT_thread.size(); ++x)
					dRTs_sum.set(x, new ImmutablePair<Integer, Long>(
							dRTs_sum.get(x).getKey() + dRT_thread.get(x).getKey(),
							dRTs_sum.get(x).getValue() + dRT_thread.get(x).getValue()));
			
		}
		if (!dRTs_sum.isEmpty())
			LOG.debug("Done - List of iterations and execution times for the updates done (in given order):\n\t\t" + dRTs_sum.toString());

		//final flush
		flushOutputBuffer();
		getOutputStream().terminate();
		
	}

	/**
	 * Retrieve the first "Sentence ID" (nif-core#Sentence -property) from the buffer and return it
	 */
	private String readFirstSentenceID(Model m) {
		String sentID = m.listSubjectsWithProperty(
				m.getProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"), 
				m.getProperty("http://persistence.uni-leipzig.org/nlp2rdf/ontologies/nif-core#Sentence")
			).next().getLocalName();
		return sentID;
	}

	private void initThreads() {
		// Use the processor cores available to runtime (but at least 1) as thread count, if an invalid thread count is provided.
		if (threads <= 0) {
			threads = (Runtime.getRuntime().availableProcessors()>0)?(Runtime.getRuntime().availableProcessors()):(1);
			LOG.info("Falling back to default thread maximum.");
		}
		LOG.info("Executing on "+threads+" processor cores, max.");
		for (int i = 0; i < threads; i++) {
			updateThreads.add(null);
			dataset.addNamedModel("http://thread"+i, ModelFactory.createDefaultModel());
			sentBufferThreads.add(new ImmutableTriple<List<Model>, Model, List<Model>>(
					new ArrayList<Model>(), null, new ArrayList<Model>()));
			dRTs.add(Collections.synchronizedList(new ArrayList<Pair<Integer,Long> >()));
		}
	}

	private synchronized void flushOutputBuffer() {
		LOG.trace("OutBufferSize: "+sentBufferOut.size());

		while (!sentBufferOut.isEmpty()) {
			if (sentBufferOut.get(0).getLeft() >= 0) break;
			
			try {
				getOutputStream().write(sentBufferOut.remove(0).getRight());
			} catch (InterruptedException e) {
				LOG.error("Resuming from interrupted thread when reading from default Stream: " +e);
			}
		}
	}

	private void executeThread(Model buffer) {
		MutableTriple<List<Model>, Model, List<Model>>sentBufferThread =
				new MutableTriple<List<Model>, Model, List<Model>>(
				new ArrayList<Model>(), null, new ArrayList<Model>());
		//sentBufferLookback only needs to be filled up to the current sentence.
		//All other sentences are for further lookahead iterations
//		sentBufferThread.getLeft().addAll(sentBufferLookback);
		for (int i = 0; i < sentBufferLookback.size() - sentBufferLookahead.size(); i++) {
			sentBufferThread.getLeft().add(sentBufferLookback.get(i));
		}
		sentBufferThread.setMiddle(buffer);
		sentBufferThread.getRight().addAll(sentBufferLookahead);
		int i = 0;

		while(i < updateThreads.size()) {
			LOG.trace("ThreadState " + i + ": "+((updateThreads.get(i)!=null)?updateThreads.get(i).getState():"null"));
			if (updateThreads.get(i) == null) {
				sentBufferThreads.set(i, sentBufferThread);
				sentBufferOut.add(new ImmutablePair<Integer, Model>(i, null)); //add last sentences to the end of the output queue.
				updateThreads.set(i, new UpdateThread(this, i));
				updateThreads.get(i).start();
				LOG.trace("restart "+i);
				LOG.trace("OutBufferSize: "+sentBufferOut.size());
				break;
			} else 
				if (updateThreads.get(i).getState() == Thread.State.WAITING) {
				synchronized(updateThreads.get(i)) {
				sentBufferThreads.set(i, sentBufferThread);
				sentBufferOut.add(new ImmutablePair<Integer, Model>(i, null)); //add last sentences to the end of the output queue.
				updateThreads.get(i).notify();
				}
				LOG.trace("wake up "+i);
				break;
			} else 
				if (updateThreads.get(i).getState() == Thread.State.NEW) {
				sentBufferThreads.set(i, sentBufferThread);
				sentBufferOut.add(new ImmutablePair<Integer, Model>(i, null)); //add last sentences to the end of the output queue.
				updateThreads.get(i).start();
				LOG.trace("start "+i);
				LOG.trace("OutBufferSize: "+sentBufferOut.size());
				break;
			} else 
				if (updateThreads.get(i).getState() == Thread.State.TERMINATED) {
				sentBufferThreads.set(i, sentBufferThread);
				sentBufferOut.add(new ImmutablePair<Integer, Model>(i, null)); //add last sentences to the end of the output queue.
				updateThreads.set(i, new UpdateThread(this, i));
				updateThreads.get(i).start();
				LOG.trace("restart "+i);
				LOG.trace("OutBufferSize: "+sentBufferOut.size());
				break;
			}
			
			i++;
			if (i >= updateThreads.size()) {
				try {
					synchronized (this) {
//						System.err.println("Updater waiting");
						wait(20);
					}
				} catch (InterruptedException e) {
					e.printStackTrace();
				} finally {
					i = 0;
				}
			}
		}
	}

	@Override
	public void start() {
		run();
	}

	@Override
	public void run() {
		try {
			processStream();
		} catch (Exception e) {
			LOG.error(e, e);
			System.exit(1);
		}
	}
}