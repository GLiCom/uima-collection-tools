/*
 * Copyright 2014 GLiCom / Universitat Pompeu Fabra
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at

 *      http://www.apache.org/licenses/LICENSE-2.0

 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package edu.upf.glicom.uima.reader.mongo;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Logger;

import org.apache.uima.UimaContext;
import org.apache.uima.cas.CAS;
import org.apache.uima.cas.CASException;
import org.apache.uima.collection.CollectionException;
import org.apache.uima.fit.component.CasCollectionReader_ImplBase;
import org.apache.uima.fit.descriptor.ConfigurationParameter;
import org.apache.uima.fit.factory.CollectionReaderFactory;
import org.apache.uima.jcas.JCas;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.util.Progress;
import org.apache.uima.util.ProgressImpl;
import org.xml.sax.SAXException;

import com.mongodb.AggregationOptions;
import com.mongodb.AggregationOutput;
import com.mongodb.BasicDBObject;
import com.mongodb.CommandFailureException;
import com.mongodb.Cursor;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.util.JSON;

import de.tudarmstadt.ukp.dkpro.core.api.metadata.type.DocumentMetaData;

public class MongoCollectionReader extends CasCollectionReader_ImplBase{

	private static final Logger logger = Logger.getLogger(MongoCollectionReader.class.toString());

	public static final String PARAM_MONGOURI = "MongoUri";
	@ConfigurationParameter(name=PARAM_MONGOURI, mandatory=true, defaultValue="mongodb://localhost",
			description="URI of MongoDB service")
	private String mongoUri;
	public static final String PARAM_MONGODB = "MongoDb";
	@ConfigurationParameter(name=PARAM_MONGODB, mandatory=true, defaultValue="",
			description="Name of Mongo DB")
	private String mongoDb;
	public static final String PARAM_MONGOCOLLECTION = "MongoCollection";
	@ConfigurationParameter(name=PARAM_MONGOCOLLECTION, mandatory=true, defaultValue="",
			description="Name of Mongo collection")
	private String mongoCollection;
	public static final String PARAM_IDFIELD = "IdField";
	@ConfigurationParameter(name=PARAM_IDFIELD, mandatory=true, defaultValue="$_id",
			description="Field that contains the document ID")
	private String idField;
	public static final String PARAM_TEXTFIELD = "TextField";
	@ConfigurationParameter(name=PARAM_TEXTFIELD, mandatory=true, defaultValue="text",
			description="Field that contains the document text")
	private String textField;
	public static final String PARAM_LANGUAGE = "Language";
	@ConfigurationParameter(name=PARAM_LANGUAGE, mandatory=false,
			description="the language of the document")
	private String language;
	public static final String PARAM_QUERY = "Query";
	@ConfigurationParameter(name=PARAM_QUERY, mandatory=true, defaultValue="{}",
			description="the query to select documents")
	private String queryString;
	public static final String PARAM_MAXITEMS = "MaxItems";
	@ConfigurationParameter(name=PARAM_MAXITEMS, mandatory=false,
			description="maximum number of items to retrieve")
	private Integer maxItems;

	private MongoClient mongoClient;
	private DB db;
	private DBCollection coll;
	private Iterator<DBObject> resCursor;

	// current document
	private int completed;
	// total number of documents
	private long totalDocs;


	/**
	 * Initialize the component. Retrieve the parameters and process them, 
	 * parsing the field descriptions and preparing the structures needed to
	 * process the documents.
	 *
	 * @param context The UIMA context.
	 *
	 * @throws ResourceInitializationException
	 *             If an error occurs with some resource.
	 *
	 */
	public void initialize(UimaContext context) throws ResourceInitializationException {
		System.out.println("MongoCollectionReader: initialize()...");
		logger.info("initialize()...");
		this.completed = 0;
		try {
			MongoClientURI uri = new MongoClientURI(this.mongoUri);
			this.mongoClient = new MongoClient(uri);
		} catch (UnknownHostException e) {
			throw new ResourceInitializationException(e);
		}
		//m.getDatabaseNames();// to test connection
		this.db = mongoClient.getDB(this.mongoDb);
		logger.info("connected to DB "+this.db.getName());
		this.coll = db.getCollection(this.mongoCollection);
		logger.info("connected to Collection "+this.coll.getName());
		DBObject query = (DBObject) JSON.parse(this.queryString);
		this.totalDocs = this.coll.count(query);
		logger.info("performing query "+query.toString()+" on collection "+this.coll.toString());
		// create our pipeline operations, first with the $match
		DBObject match = new BasicDBObject("$match", query);
		DBObject limit = new BasicDBObject("$limit", this.maxItems);
		// build the $projection operation
		DBObject fields = new BasicDBObject();
		fields.put("id", this.idField);
		fields.put("text", this.textField);
		fields.put("lang", (DBObject) JSON.parse(this.language));
		DBObject project = new BasicDBObject("$project", fields );

		// Finally the $sort operation
		DBObject sort = new BasicDBObject("$sort", new BasicDBObject("id", 1));

		// run aggregation
		List<DBObject> pipeline = Arrays.asList(match, limit, project, sort);
		try {
			AggregationOptions aggregationOptions = AggregationOptions.builder()
					.batchSize(100)
					.outputMode(AggregationOptions.OutputMode.CURSOR)
					.allowDiskUse(true)
					.build();
			this.resCursor = this.coll.aggregate(pipeline, aggregationOptions);
		} catch (CommandFailureException e) { // MongoDB version <2.6 doesn't support cursors
			logger.warning("Your MongoDB version doesn't seem to support cursors for aggregation pipelines. "
					+ "The result set is therefore limited to 16MB. "
					+ "Use a version >=2.6 to access larger amounts of data.\n"
					+ e.toString());
			AggregationOutput output = coll.aggregate(pipeline);
			this.resCursor = output.results().iterator();
		}
		logger.info("initialize() - Done.");
	}

	public void getNext(CAS aCAS) throws IOException, CollectionException {
		JCas jcas;
		try{
			jcas = aCAS.getJCas();
		}
		catch(CASException e){
			throw new CollectionException(e);
		}
		DBObject doc = this.resCursor.next();
		String documentId = doc.get("id").toString(); // hopefully correct conversion to string
		logger.fine(documentId);
		String documentText = "";
		try {
			documentText = doc.get("text").toString(); // should be a String field anyway
		} catch (NullPointerException e) {
			// just leave text empty if document doesn't have one
		}
		DocumentMetaData metadata = null;
		metadata = DocumentMetaData.create(jcas);
		// set language if it was explicitly specified as a configuration parameter
		if(this.language != null){
			String lang = doc.get("lang").toString(); // should be a String field anyway
			jcas.setDocumentLanguage(lang);
			metadata.setLanguage(lang);
		}
		jcas.setDocumentText(documentText);
		metadata.setDocumentId(documentId);
		//metadata.setDocumentTitle(CAS_METADATA_TITLE);
		//metadata.setCollectionId(CAS_METADATA_COLLECTION_ID);
		//metadata.setDocumentUri(CAS_METADATA_DOCUMENT_URI);
		//metadata.setDocumentBaseUri(CAS_METADATA_DOCUMENT_BASE_URI);

		this.completed++;

	}

	public boolean hasNext() throws IOException, CollectionException {
		return this.resCursor.hasNext();
	}

	public Progress[] getProgress(){
		return new Progress[] { new ProgressImpl(this.completed, (int) this.totalDocs, Progress.ENTITIES) };
	}

	public void close() throws IOException {
		this.mongoClient.close();
	}

	/**
	 * return example descriptor (XML) when calling main method
	 * @param args not used
	 * @throws ResourceInitializationException
	 * @throws FileNotFoundException
	 * @throws SAXException
	 * @throws IOException
	 */
	public static void main(String[] args) throws ResourceInitializationException, FileNotFoundException, SAXException, IOException {
		CollectionReaderFactory.createReaderDescription(MongoCollectionReader.class).toXML(System.out);
	}

}
